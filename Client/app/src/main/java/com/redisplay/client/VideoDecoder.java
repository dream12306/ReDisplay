package com.redisplay.client;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the complete MediaCodec pump.
 *
 * Network input and decoded output deliberately run independently. A decoded
 * frame is rendered from MediaCodec's output callback as soon as it becomes
 * available; rendering never waits for a later network packet to call decode().
 */
public class VideoDecoder {
    private static final String TAG = "ReDisplay_Decoder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long INPUT_BUFFER_WAIT_MS = 1;
    private static final long IDLE_FPS_TIMEOUT_MS = 500;

    private final Object lifecycleLock = new Object();
    private final Object statsLock = new Object();
    private final LinkedBlockingQueue<Integer> availableInputBuffers = new LinkedBlockingQueue<>();

    private volatile MediaCodec codec;
    private volatile Surface surface;
    private volatile boolean isConfigured = false;
    private HandlerThread codecCallbackThread;

    private int width = 2800;
    private int height = 1752;
    private int fps = 120;

    private final AtomicLong renderedFrameCount = new AtomicLong();
    private final AtomicLong queuedInputCount = new AtomicLong();
    private final AtomicLong droppedInputCount = new AtomicLong();
    private volatile long lastStatsTimeMs = System.currentTimeMillis();
    private volatile long lastOutputTimeMs = 0;
    private volatile float currentFps = 0.0f;

    private final MediaCodec.Callback codecCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec mediaCodec, int index) {
            if (mediaCodec == codec && isConfigured) {
                availableInputBuffers.offer(index);
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec mediaCodec, int index,
                                            MediaCodec.BufferInfo info) {
            if (mediaCodec != codec || !isConfigured) {
                releaseOutputQuietly(mediaCodec, index, false);
                return;
            }

            try {
                // Output readiness is the render clock. Do not wait for another
                // compressed input packet before presenting this frame.
                mediaCodec.releaseOutputBuffer(index, true);
                lastOutputTimeMs = System.currentTimeMillis();
                renderedFrameCount.incrementAndGet();
                publishStatsIfDue();
            } catch (IllegalStateException e) {
                if (mediaCodec == codec && isConfigured) {
                    Log.w(TAG, "Unable to render decoded output: " + e.getMessage());
                }
            }
        }

        @Override
        public void onError(MediaCodec mediaCodec, MediaCodec.CodecException error) {
            if (mediaCodec == codec) {
                Log.e(TAG, "MediaCodec asynchronous error: " + error.getDiagnosticInfo(), error);
            }
        }

        @Override
        public void onOutputFormatChanged(MediaCodec mediaCodec, MediaFormat format) {
            if (mediaCodec == codec) {
                Log.i(TAG, "Decoder output format changed: " + format);
            }
        }
    };

    public VideoDecoder(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void setSurface(Surface newSurface) {
        synchronized (lifecycleLock) {
            surface = newSurface;
            if (newSurface == null || !newSurface.isValid()) {
                // A codec must not outlive the Surface it renders into.
                releaseCodecLocked();
                return;
            }

            MediaCodec current = codec;
            if (current != null && isConfigured) {
                try {
                    current.setOutputSurface(newSurface);
                    Log.i(TAG, "Dynamic setOutputSurface succeeded");
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "setOutputSurface failed, re-initializing: " + e.getMessage());
                }
            }
        }
        init();
    }

    public void init() {
        synchronized (lifecycleLock) {
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "Surface is not ready, skipping init");
                return;
            }

            releaseCodecLocked();

            MediaCodec newCodec = null;
            HandlerThread newCallbackThread = null;
            try {
                newCallbackThread = new HandlerThread("ReDisplay-CodecCallbacks");
                newCallbackThread.start();
                Handler callbackHandler = new Handler(newCallbackThread.getLooper());

                newCodec = MediaCodec.createDecoderByType(MIME_TYPE);
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

                try {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                } catch (Exception ignored) {}
                try {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                } catch (Exception ignored) {}
                try {
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                } catch (Exception ignored) {}
                try {
                    format.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(fps, 60));
                } catch (Exception ignored) {}

                newCodec.setCallback(codecCallback, callbackHandler);
                newCodec.configure(format, surface, null, 0);

                codecCallbackThread = newCallbackThread;
                codec = newCodec;
                isConfigured = true;
                resetStatsLocked();
                newCodec.start();

                Log.i(TAG, "[Stage 6 MediaCodec Param] KEY_FRAME_RATE=" + fps
                        + ", KEY_OPERATING_RATE=" + Math.max(fps, 60));
                Log.i(TAG, "Hardware decoder started in asynchronous callback mode: "
                        + newCodec.getName() + " (" + width + "x" + height + " @" + fps + "Hz)");
            } catch (IOException | RuntimeException e) {
                isConfigured = false;
                codec = null;
                availableInputBuffers.clear();
                releaseCodecQuietly(newCodec);
                if (newCallbackThread != null) {
                    newCallbackThread.quitSafely();
                }
                codecCallbackThread = null;
                Log.e(TAG, "Failed to initialize asynchronous decoder: " + e.getMessage(), e);
            }
        }
    }

    public void reconfigure(int newWidth, int newHeight, int newFps) {
        boolean needsInit;
        synchronized (lifecycleLock) {
            needsInit = width != newWidth || height != newHeight || fps != newFps || !isConfigured;
            if (needsInit) {
                width = newWidth;
                height = newHeight;
                fps = newFps > 0 ? newFps : 120;
            }
        }
        if (needsInit) {
            init();
        }
    }

    public void decode(byte[] data, int offset, int length, long timestampUs, boolean isKeyFrame) {
        MediaCodec current = codec;
        Surface currentSurface = surface;
        if (!isConfigured || current == null || currentSurface == null || !currentSurface.isValid()) {
            return;
        }

        Integer inputIndex;
        try {
            inputIndex = availableInputBuffers.poll(INPUT_BUFFER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            droppedInputCount.incrementAndGet();
            return;
        }

        if (inputIndex == null) {
            droppedInputCount.incrementAndGet();
            publishStatsIfDue();
            return;
        }

        if (current != codec || !isConfigured) {
            return;
        }

        try {
            ByteBuffer inputBuffer = current.getInputBuffer(inputIndex);
            if (inputBuffer == null || length > inputBuffer.capacity()) {
                droppedInputCount.incrementAndGet();
                Log.w(TAG, "Decoder input buffer unavailable or too small: payload=" + length);
                current.queueInputBuffer(inputIndex, 0, 0, timestampUs, 0);
                publishStatsIfDue();
                return;
            }

            inputBuffer.clear();
            inputBuffer.put(data, offset, length);
            int flags = isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            current.queueInputBuffer(inputIndex, 0, length, timestampUs, flags);
            queuedInputCount.incrementAndGet();
            publishStatsIfDue();
        } catch (RuntimeException e) {
            droppedInputCount.incrementAndGet();
            if (current == codec && isConfigured) {
                Log.e(TAG, "Decode input error: " + e.getMessage(), e);
            }
        }
    }

    public float getFps() {
        if (System.currentTimeMillis() - lastOutputTimeMs > IDLE_FPS_TIMEOUT_MS) {
            return 0.0f;
        }
        return currentFps;
    }

    public void release() {
        synchronized (lifecycleLock) {
            releaseCodecLocked();
        }
    }

    private void publishStatsIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastStatsTimeMs < 1000) {
            return;
        }

        synchronized (statsLock) {
            now = System.currentTimeMillis();
            long elapsedMs = now - lastStatsTimeMs;
            if (elapsedMs < 1000) {
                return;
            }

            long rendered = renderedFrameCount.getAndSet(0);
            long queued = queuedInputCount.getAndSet(0);
            long dropped = droppedInputCount.getAndSet(0);
            currentFps = (float) (rendered * 1000.0 / elapsedMs);
            Log.i(TAG, "[Stage 6 MediaCodec] Decoded: "
                    + String.format(java.util.Locale.US, "%.1f", currentFps)
                    + " FPS | Queued: " + queued + ", Dropped: " + dropped
                    + " | Output: async");
            lastStatsTimeMs = now;
        }
    }

    private void resetStatsLocked() {
        renderedFrameCount.set(0);
        queuedInputCount.set(0);
        droppedInputCount.set(0);
        currentFps = 0.0f;
        lastOutputTimeMs = 0;
        lastStatsTimeMs = System.currentTimeMillis();
    }

    private void releaseCodecLocked() {
        isConfigured = false;
        availableInputBuffers.clear();

        MediaCodec oldCodec = codec;
        codec = null;
        releaseCodecQuietly(oldCodec);

        HandlerThread oldCallbackThread = codecCallbackThread;
        codecCallbackThread = null;
        if (oldCallbackThread != null) {
            oldCallbackThread.quitSafely();
        }
    }

    private static void releaseCodecQuietly(MediaCodec mediaCodec) {
        if (mediaCodec == null) {
            return;
        }
        try {
            mediaCodec.stop();
        } catch (Exception ignored) {}
        try {
            mediaCodec.release();
        } catch (Exception ignored) {}
    }

    private static void releaseOutputQuietly(MediaCodec mediaCodec, int index, boolean render) {
        try {
            mediaCodec.releaseOutputBuffer(index, render);
        } catch (Exception ignored) {}
    }
}
