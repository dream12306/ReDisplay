package com.redisplay.client;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkServer implements Runnable {
    private static final String TAG = "ReDisplay_Net";
    public static final int PORT = 27183;
    public static final int MAGIC = 0x52445350; // "RDSP"
    public static final int PACKET_TYPE_PONG = 4;
    public static final int PACKET_TYPE_PEN = 5;
    public static final int PEN_PAYLOAD_SIZE = 20;

    public static final int PEN_ACTION_HOVER_ENTER = 1;
    public static final int PEN_ACTION_HOVER_MOVE = 2;
    public static final int PEN_ACTION_HOVER_EXIT = 3;
    public static final int PEN_ACTION_DOWN = 4;
    public static final int PEN_ACTION_MOVE = 5;
    public static final int PEN_ACTION_UP = 6;
    public static final int PEN_ACTION_CANCEL = 7;

    public static final int PEN_TOOL_STYLUS = 1;
    public static final int PEN_TOOL_ERASER = 2;
    public static final int PEN_BUTTON_BARREL = 1;
    public static final int PEN_BUTTON_SECONDARY = 2;

    public interface StatusListener {
        void onConnected(String hostInfo);
        void onDisconnected();
        void onResolutionChanged(int width, int height, int fps);
        void onStatsUpdate(float fps, int bitrateKbps);
    }

    private final VideoDecoder decoder;
    private final StatusListener listener;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile ClientWriter clientWriter;
    private final AtomicInteger penSequence = new AtomicInteger();
    private Thread serverThread;

    public NetworkServer(VideoDecoder decoder, StatusListener listener) {
        this.decoder = decoder;
        this.listener = listener;
    }

    public synchronized void start() {
        if (!running) {
            running = true;
            serverThread = new Thread(this, "ReDisplay-NetServer");
            serverThread.start();
        }
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        Socket currentClient = clientSocket;
        if (currentClient != null) {
            try {
                currentClient.close();
            } catch (IOException ignored) {}
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Override
    public void run() {
        Log.i(TAG, "NetworkServer listening on port " + PORT + "...");
        while (running) {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);

                while (running) {
                    Log.i(TAG, "Waiting for Host connection via ADB tunnel...");
                    if (listener != null) listener.onDisconnected();

                    Socket socket = serverSocket.accept();
                    clientSocket = socket;
                    socket.setTcpNoDelay(true);
                    // Keep only a small amount of stale video in flight. The old
                    // 4 MiB buffer could hide more than half a second at 60 Mbps.
                    socket.setReceiveBufferSize(256 * 1024);
                    socket.setSendBufferSize(64 * 1024);

                    Log.i(TAG, "Host connected from: " + socket.getRemoteSocketAddress());
                    if (listener != null) listener.onConnected(socket.getRemoteSocketAddress().toString());

                    handleClient(socket);
                    clientSocket = null;
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "ServerSocket error, restarting: " + e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {}
                }
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    public boolean sendPenEvent(
            int action,
            int tool,
            int buttons,
            int normalizedX,
            int normalizedY,
            int normalizedPressure,
            int tiltXHundredths,
            int tiltYHundredths,
            long timestampUs) {
        ClientWriter writer = clientWriter;
        if (writer == null) return false;

        PenPacket packet = new PenPacket();
        packet.sequence = penSequence.incrementAndGet();
        packet.action = action;
        packet.tool = tool;
        packet.buttons = buttons;
        packet.x = clampUnsignedShort(normalizedX);
        packet.y = clampUnsignedShort(normalizedY);
        packet.pressure = clampUnsignedShort(normalizedPressure);
        packet.tiltXHundredths = clampSignedShort(tiltXHundredths);
        packet.tiltYHundredths = clampSignedShort(tiltYHundredths);
        packet.timestampUs = timestampUs;
        return writer.enqueuePen(packet);
    }

    private static int clampUnsignedShort(int value) {
        return Math.max(0, Math.min(65535, value));
    }

    private static int clampSignedShort(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private void handleClient(Socket socket) {
        byte[] buffer = new byte[1024 * 1024 * 4]; // 4MB frame buffer
        long lastStatTime = System.currentTimeMillis();
        long totalBytesInSec = 0;
        long rxVideoFrames = 0;

        ClientWriter writer = null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            writer = new ClientWriter(socket, out);
            clientWriter = writer;
            writer.start();

            while (running && !socket.isClosed()) {
                // Read 4-byte Magic
                int magic = in.readInt();
                if (magic != MAGIC) {
                    Log.e(TAG, "Invalid magic: " + Integer.toHexString(magic) + ", resyncing...");
                    break;
                }

                // Header fields
                int packetType = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                in.readShort(); // reserved
                long timestampUs = in.readLong();
                int payloadSize = in.readInt();

                if (payloadSize < 0 || payloadSize > buffer.length) {
                    Log.e(TAG, "Illegal payload size: " + payloadSize);
                    break;
                }

                // Read exact payload
                in.readFully(buffer, 0, payloadSize);
                totalBytesInSec += payloadSize + 20;

                if (packetType == 1) { // Video Frame
                    rxVideoFrames++;
                    boolean isKeyFrame = (flags & 0x01) != 0;
                    decoder.decode(buffer, 0, payloadSize, timestampUs, isKeyFrame);
                } else if (packetType == 2) { // Config / Resolution Change
                    if (payloadSize >= 12) {
                        int w = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
                        int h = ((buffer[4] & 0xFF) << 24) | ((buffer[5] & 0xFF) << 16) | ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
                        int fps = ((buffer[8] & 0xFF) << 24) | ((buffer[9] & 0xFF) << 16) | ((buffer[10] & 0xFF) << 8) | (buffer[11] & 0xFF);
                        Log.i(TAG, "Config received: " + w + "x" + h + " @" + fps + "Hz");
                        decoder.reconfigure(w, h, fps);
                        if (listener != null) listener.onResolutionChanged(w, h, fps);
                    }
                } else if (packetType == 3) { // Ping / Pong for latency measurement
                    writer.enqueuePong(timestampUs);
                }

                // Periodic stats update
                long now = System.currentTimeMillis();
                if (now - lastStatTime >= 1000) {
                    double elapsedSec = (now - lastStatTime) / 1000.0;
                    int bitrateKbps = (int) (totalBytesInSec * 8 / 1024 / elapsedSec);
                    float rxFps = (float) (rxVideoFrames / elapsedSec);
                    Log.i(TAG, "[Stage 5 Android TCP] Recv: " + String.format(java.util.Locale.US, "%.1f", rxFps) + " FPS (" + String.format(java.util.Locale.US, "%.1f", bitrateKbps / 1000.0f) + " Mbps)");
                    if (listener != null) listener.onStatsUpdate(decoder.getFps(), bitrateKbps);
                    totalBytesInSec = 0;
                    rxVideoFrames = 0;
                    lastStatTime = now;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Client disconnected: " + e.getMessage());
        } finally {
            clientWriter = null;
            if (writer != null) writer.stop();
            try {
                socket.close();
            } catch (IOException ignored) {}
            if (listener != null) listener.onDisconnected();
        }
    }

    private static final class PenPacket {
        int sequence;
        int action;
        int tool;
        int buttons;
        int x;
        int y;
        int pressure;
        int tiltXHundredths;
        int tiltYHundredths;
        long timestampUs;

        boolean isCoalescible() {
            return action == PEN_ACTION_MOVE || action == PEN_ACTION_HOVER_MOVE;
        }
    }

    private static final class OutboundPacket {
        final int type;
        final long timestampUs;
        final PenPacket pen;

        OutboundPacket(int type, long timestampUs, PenPacket pen) {
            this.type = type;
            this.timestampUs = timestampUs;
            this.pen = pen;
        }

        boolean isCoalesciblePen() {
            return pen != null && pen.isCoalescible();
        }
    }

    private static final class ClientWriter implements Runnable {
        private static final int MAX_QUEUE_DEPTH = 64;

        private final Socket socket;
        private final DataOutputStream out;
        private final ArrayDeque<OutboundPacket> queue = new ArrayDeque<>();
        private volatile boolean active = true;
        private Thread thread;

        ClientWriter(Socket socket, DataOutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void start() {
            thread = new Thread(this, "ReDisplay-InputWriter");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        boolean enqueuePen(PenPacket pen) {
            synchronized (queue) {
                if (!active) return false;

                OutboundPacket tail = queue.peekLast();
                if (pen.isCoalescible() && tail != null && tail.pen != null
                        && tail.pen.action == pen.action) {
                    queue.removeLast();
                    queue.addLast(new OutboundPacket(PACKET_TYPE_PEN, pen.timestampUs, pen));
                    queue.notifyAll();
                    return true;
                }

                if (queue.size() >= MAX_QUEUE_DEPTH) {
                    boolean removedMove = false;
                    Iterator<OutboundPacket> iterator = queue.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().isCoalesciblePen()) {
                            iterator.remove();
                            removedMove = true;
                            break;
                        }
                    }
                    if (!removedMove && pen.isCoalescible()) return false;
                }

                queue.addLast(new OutboundPacket(PACKET_TYPE_PEN, pen.timestampUs, pen));
                queue.notifyAll();
                return true;
            }
        }

        void enqueuePong(long timestampUs) {
            synchronized (queue) {
                if (!active) return;
                queue.addLast(new OutboundPacket(PACKET_TYPE_PONG, timestampUs, null));
                queue.notifyAll();
            }
        }

        void stop() {
            active = false;
            synchronized (queue) {
                queue.notifyAll();
            }
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void run() {
            try {
                while (active && !socket.isClosed()) {
                    OutboundPacket packet;
                    synchronized (queue) {
                        while (active && queue.isEmpty()) {
                            queue.wait();
                        }
                        if (!active) break;
                        packet = queue.removeFirst();
                    }
                    writePacket(packet);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                Log.i(TAG, "Input writer disconnected: " + e.getMessage());
                try {
                    socket.close();
                } catch (IOException ignored) {}
            } finally {
                active = false;
            }
        }

        private void writePacket(OutboundPacket packet) throws IOException {
            int payloadSize = packet.pen != null ? PEN_PAYLOAD_SIZE : 0;
            out.writeInt(MAGIC);
            out.writeByte(packet.type);
            out.writeByte(0);
            out.writeShort(0);
            out.writeLong(packet.timestampUs);
            out.writeInt(payloadSize);

            PenPacket pen = packet.pen;
            if (pen != null) {
                out.writeInt(pen.sequence);
                out.writeByte(pen.action);
                out.writeByte(pen.tool);
                out.writeShort(pen.buttons);
                out.writeShort(pen.x);
                out.writeShort(pen.y);
                out.writeShort(pen.pressure);
                out.writeShort(pen.tiltXHundredths);
                out.writeShort(pen.tiltYHundredths);
                out.writeShort(0);
            }
            out.flush();
        }
    }
}
