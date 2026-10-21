package com.redisplay.client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SurfaceHolder.Callback, NetworkServer.StatusListener {
    private static final String TAG = "ReDisplay_Main";

    private DisplaySurfaceView surfaceView;
    private TextView statsOverlay;
    private WheelOverlayView wheelOverlay;
    private VideoDecoder decoder;
    private NetworkServer server;
    private DisplayService displayService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GestureDetector gestureDetector;
    private Runnable hideOverlayRunnable;
    private int negotiatedFps = 120;
    private boolean penInContent = false;
    private boolean penDown = false;
    private int lastPenX = 0;
    private int lastPenY = 0;
    private float lastPenParentX = 0;
    private float lastPenParentY = 0;
    private boolean macroRecording = false;
    private boolean mouseMode = false;
    private boolean eraserMode = false;
    private final WheelInteractionState wheelInteractionState = new WheelInteractionState();
    private final WheelGestureGuard wheelGestureGuard = new WheelGestureGuard();
    private final float[] penCoordinates = new float[2];
    private FingerGestureState fingerGestureState;
    private int fingerPrimaryPointerId = -1;
    private int touchInputMode = TouchInputConfig.MODE_AUTO;
    private int touchPressurePercent = TouchInputConfig.DEFAULT_PRESSURE_PERCENT;
    private long lastStylusEventMs = -10_000L;
    private Runnable commitFingerRunnable;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            displayService = ((DisplayService.LocalBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            displayService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on and show above lockscreen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        // Support cutout to draw behind camera hole
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(lp);
        }

        // Initialize persistent Decoder and Network Server
        decoder = new VideoDecoder(2800, 1752);
        server = new NetworkServer(decoder, this);
        server.start();

        // Build UI
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        surfaceView = new DisplaySurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        FrameLayout.LayoutParams svParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER
        );
        rootLayout.addView(surfaceView, svParams);

        // Subtle stats overlay
        statsOverlay = new TextView(this);
        statsOverlay.setTextColor(Color.GREEN);
        statsOverlay.setTextSize(13);
        statsOverlay.setPadding(30, 30, 30, 30);
        statsOverlay.setBackgroundColor(Color.parseColor("#80000000"));
        statsOverlay.setText("ReDisplay 等待主机连接...\n小米手写笔: Windows Ink | 手指双击切换画面模式");
        statsOverlay.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        rootLayout.addView(statsOverlay, overlayParams);

        wheelOverlay = new WheelOverlayView(this);
        rootLayout.addView(wheelOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);

        fingerGestureState = new FingerGestureState(
                ViewConfiguration.get(this).getScaledTouchSlop(), 110L);
        commitFingerRunnable = () -> {
            if (fingerGestureState.commitPending() == FingerGestureState.Result.START_INK) {
                beginFingerInk(
                        fingerGestureState.getDownX(),
                        fingerGestureState.getDownY(),
                        fingerGestureState.getDownTimeMs()
                );
            }
        };

        hideOverlayRunnable = () -> statsOverlay.setVisibility(View.GONE);
        handler.postDelayed(hideOverlayRunnable, 4000);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleStatsOverlay();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                DisplaySurfaceView.ScaleMode newMode = surfaceView.toggleScaleMode();
                String modeText = (newMode == DisplaySurfaceView.ScaleMode.FIT) ? "保持比例居中 (FIT)" :
                                  (newMode == DisplaySurfaceView.ScaleMode.FILL) ? "等比裁剪铺满 (FILL)" : "全屏强制拉伸 (STRETCH)";
                showTemporaryMessage("画面模式: " + modeText);
                return true;
            }
        });

        rootLayout.setOnTouchListener((v, event) -> {
            if (wheelOverlay.isShowing() && !isStylusEvent(event)) {
                return handleWheelPointerEvent(event);
            }
            if (isStylusEvent(event)) return handlePenMotionEvent(event);
            if (isFingerInputEnabled()) return handleFingerTouchEvent(event);
            return gestureDetector.onTouchEvent(event);
        });
        rootLayout.setOnHoverListener((v, event) ->
                isStylusEvent(event) && handlePenMotionEvent(event));

        // Bind Foreground Service
        Intent intent = new Intent(this, DisplayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE);

        // Stage 7 Read-only Probe: Choreographer VSYNC measurement
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            private int vSyncCount = 0;
            private long lastVsyncLog = System.currentTimeMillis();

            @Override
            public void doFrame(long frameTimeNanos) {
                vSyncCount++;
                long now = System.currentTimeMillis();
                if (now - lastVsyncLog >= 1000) {
                    double elapsedSec = (now - lastVsyncLog) / 1000.0;
                    float vsyncFps = (float) (vSyncCount / elapsedSec);
                    Display d = getCurrentDisplay();
                    float modeFps = (d != null && d.getMode() != null) ? d.getMode().getRefreshRate() : 0.0f;
                    Log.i(TAG, "[Stage 7 Choreographer] VSync: " + String.format(java.util.Locale.US, "%.1f", vsyncFps) + " Hz | Active Display Mode: " + modeFps + " Hz");
                    vSyncCount = 0;
                    lastVsyncLog = now;
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private boolean isStylusEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) {
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                    || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                return true;
            }
        }
        return false;
    }

    private boolean handlePenMotionEvent(MotionEvent event) {
        lastStylusEventMs = SystemClock.uptimeMillis();
        cancelFingerGestureForStylus();
        int maskedAction = event.getActionMasked();
        boolean terminalAction = maskedAction == MotionEvent.ACTION_UP
                || maskedAction == MotionEvent.ACTION_POINTER_UP
                || maskedAction == MotionEvent.ACTION_CANCEL;
        if (wheelGestureGuard.shouldConsume(terminalAction)) return true;
        int pointerIndex = Math.min(event.getActionIndex(), Math.max(0, event.getPointerCount() - 1));
        lastPenParentX = event.getX(pointerIndex);
        lastPenParentY = event.getY(pointerIndex);

        if (wheelOverlay != null && wheelOverlay.isShowing()) {
            wheelOverlay.updateSelection(lastPenParentX, lastPenParentY);
            if (maskedAction == MotionEvent.ACTION_DOWN
                    || maskedAction == MotionEvent.ACTION_POINTER_DOWN) {
                handleWheelTap(true);
            }
            return true;
        }

        boolean clampToContent = penDown && maskedAction == MotionEvent.ACTION_MOVE;
        boolean inside = surfaceView.mapParentPointToNormalized(
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                penCoordinates,
                clampToContent
        );

        if (inside || clampToContent) {
            lastPenX = Math.round(penCoordinates[0] * 65535.0f);
            lastPenY = Math.round(penCoordinates[1] * 65535.0f);
        }

        int action;
        switch (maskedAction) {
            case MotionEvent.ACTION_HOVER_ENTER:
                if (!inside) return true;
                action = NetworkServer.PEN_ACTION_HOVER_ENTER;
                penInContent = true;
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                if (!inside) {
                    if (penInContent) {
                        sendPenSample(event, pointerIndex, NetworkServer.PEN_ACTION_HOVER_EXIT);
                        penInContent = false;
                    }
                    return true;
                }
                action = penInContent
                        ? NetworkServer.PEN_ACTION_HOVER_MOVE
                        : NetworkServer.PEN_ACTION_HOVER_ENTER;
                penInContent = true;
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (!penInContent) return true;
                action = NetworkServer.PEN_ACTION_HOVER_EXIT;
                penInContent = false;
                break;
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!inside) return true;
                action = NetworkServer.PEN_ACTION_DOWN;
                penInContent = true;
                penDown = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!penDown) return true;
                action = NetworkServer.PEN_ACTION_MOVE;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (!penDown) return true;
                action = NetworkServer.PEN_ACTION_UP;
                penDown = false;
                penInContent = inside;
                break;
            case MotionEvent.ACTION_CANCEL:
                if (!penDown && !penInContent) return true;
                action = NetworkServer.PEN_ACTION_CANCEL;
                penDown = false;
                penInContent = false;
                break;
            default:
                return true;
        }

        sendPenSample(event, pointerIndex, action);
        return true;
    }

    private boolean handleWheelPointerEvent(MotionEvent event) {
        int pointerIndex = Math.min(event.getActionIndex(), Math.max(0, event.getPointerCount() - 1));
        wheelOverlay.updateSelection(event.getX(pointerIndex), event.getY(pointerIndex));
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP) {
            handleWheelTap(false);
        }
        return true;
    }

    private boolean isFingerInputEnabled() {
        // Once a finger gesture owns the stream, keep routing every subsequent
        // event to it until all pointers are released. penInContent becomes true
        // during ink, so checking auto-mode eligibility first would split a stroke.
        if (fingerGestureState != null && fingerGestureState.isActive()) return true;
        if (touchInputMode == TouchInputConfig.MODE_STYLUS_ONLY) return false;
        if (touchInputMode == TouchInputConfig.MODE_FINGER) return true;
        return !penInContent && SystemClock.uptimeMillis() - lastStylusEventMs > 1000L;
    }

    private boolean handleFingerTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final long timeMs = event.getEventTime();

        if (action == MotionEvent.ACTION_DOWN) {
            fingerPrimaryPointerId = event.getPointerId(0);
            fingerGestureState.onDown(timeMs, event.getX(0), event.getY(0));
            handler.removeCallbacks(commitFingerRunnable);
            handler.postDelayed(commitFingerRunnable, 110L);
            return true;
        }

        if (!fingerGestureState.isActive()) return true;

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            float[] centroid = touchCentroid(event);
            FingerGestureState.Result result = fingerGestureState.onAdditionalDown(
                    event.getPointerCount(), timeMs, centroid[0], centroid[1]);
            if (fingerGestureState.isChord()) {
                handler.removeCallbacks(commitFingerRunnable);
                surfaceView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                showTemporaryMessage("双指：下键 · 松开执行");
            } else if (result == FingerGestureState.Result.START_INK) {
                handler.removeCallbacks(commitFingerRunnable);
                beginFingerInk(fingerGestureState.getDownX(), fingerGestureState.getDownY(),
                        fingerGestureState.getDownTimeMs());
                int primaryIndex = event.findPointerIndex(fingerPrimaryPointerId);
                if (primaryIndex >= 0) moveFingerInk(event.getX(primaryIndex), event.getY(primaryIndex), timeMs);
            } else if (result == FingerGestureState.Result.CANCELLED) {
                handler.removeCallbacks(commitFingerRunnable);
                if (event.getPointerCount() >= 3) {
                    showTemporaryMessage("三指操作已禁用");
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (fingerGestureState.isChord()) {
                float[] centroid = touchCentroid(event);
                if (fingerGestureState.onChordMove(timeMs, centroid[0], centroid[1]) ==
                        FingerGestureState.Result.CANCELLED) {
                    showTemporaryMessage("多指手势已取消");
                }
                return true;
            }
            int primaryIndex = event.findPointerIndex(fingerPrimaryPointerId);
            if (primaryIndex < 0) return true;
            FingerGestureState.Result result = fingerGestureState.onPrimaryMove(
                    timeMs, event.getX(primaryIndex), event.getY(primaryIndex));
            if (result == FingerGestureState.Result.START_INK) {
                handler.removeCallbacks(commitFingerRunnable);
                beginFingerInk(fingerGestureState.getDownX(), fingerGestureState.getDownY(),
                        fingerGestureState.getDownTimeMs());
                moveFingerInk(event.getX(primaryIndex), event.getY(primaryIndex), timeMs);
            } else if (result == FingerGestureState.Result.MOVE_INK) {
                moveFingerInk(event.getX(primaryIndex), event.getY(primaryIndex), timeMs);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(commitFingerRunnable);
            int actionIndex = event.getActionIndex();
            int liftedId = event.getPointerId(actionIndex);
            int remainingPointers = event.getPointerCount() - 1;
            float chordX = fingerGestureState.getChordAnchorX();
            float chordY = fingerGestureState.getChordAnchorY();
            FingerGestureState.Result result = liftedId == fingerPrimaryPointerId
                    ? fingerGestureState.onPrimaryUp(timeMs, remainingPointers)
                    : fingerGestureState.onOtherPointerUp(timeMs, remainingPointers);
            handleFingerResult(result, event.getX(actionIndex), event.getY(actionIndex),
                    chordX, chordY, timeMs);
            if (remainingPointers == 0) {
                fingerGestureState.onAllPointersReleased();
                fingerPrimaryPointerId = -1;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(commitFingerRunnable);
            handleFingerResult(fingerGestureState.onCancel(), lastPenParentX, lastPenParentY,
                    lastPenParentX, lastPenParentY, timeMs);
            fingerPrimaryPointerId = -1;
            return true;
        }
        return true;
    }

    private void handleFingerResult(
            FingerGestureState.Result result,
            float x,
            float y,
            float chordX,
            float chordY,
            long timeMs) {
        switch (result) {
            case TAP:
                sendFingerPen(NetworkServer.PEN_ACTION_DOWN, x, y, timeMs);
                sendFingerPen(NetworkServer.PEN_ACTION_UP, x, y, timeMs + 1L);
                break;
            case END_INK:
                endFingerInk(x, y, timeMs, false);
                break;
            case CANCEL_INK:
                endFingerInk(x, y, timeMs, true);
                break;
            case LOWER_BUTTON:
                applyFingerButtonAnchor(chordX, chordY);
                handleLowerButtonDown();
                handleLowerButtonReleased();
                break;
            default:
                break;
        }
    }

    private void beginFingerInk(float x, float y, long timeMs) {
        FingerContactPolicy.Actions actions = FingerContactPolicy.forMode(mouseMode);
        if (actions.begin == NetworkServer.PEN_ACTION_HOVER_ENTER) {
            penInContent = sendFingerPen(actions.begin, x, y, timeMs);
            penDown = false;
        } else {
            penDown = sendFingerPen(actions.begin, x, y, timeMs);
            penInContent = penDown;
        }
    }

    private void moveFingerInk(float x, float y, long timeMs) {
        FingerContactPolicy.Actions actions = FingerContactPolicy.forMode(mouseMode);
        if (actions.move == NetworkServer.PEN_ACTION_HOVER_MOVE) {
            if (penInContent) {
                sendFingerPen(actions.move, x, y, timeMs);
            } else {
                penInContent = sendFingerPen(NetworkServer.PEN_ACTION_HOVER_ENTER, x, y, timeMs);
            }
        } else if (penDown) {
            sendFingerPen(actions.move, x, y, timeMs);
        }
    }

    private void endFingerInk(float x, float y, long timeMs, boolean cancelled) {
        if (penDown || penInContent) {
            int action;
            if (cancelled) action = NetworkServer.PEN_ACTION_CANCEL;
            else action = FingerContactPolicy.forMode(mouseMode).end;
            sendFingerPen(action, x, y, timeMs);
        }
        penDown = false;
        penInContent = false;
    }

    private boolean sendFingerPen(int action, float parentX, float parentY, long timeMs) {
        boolean clamp = penDown || penInContent;
        boolean inside = surfaceView.mapParentPointToNormalized(
                parentX, parentY, penCoordinates, clamp);
        if (!inside && !clamp) return false;
        lastPenParentX = parentX;
        lastPenParentY = parentY;
        lastPenX = Math.round(penCoordinates[0] * 65535.0f);
        lastPenY = Math.round(penCoordinates[1] * 65535.0f);
        boolean contact = action == NetworkServer.PEN_ACTION_DOWN ||
                action == NetworkServer.PEN_ACTION_MOVE;
        int pressure = contact ? Math.round(touchPressurePercent * 655.35f) : 0;
        return server.sendPenEvent(
                action,
                NetworkServer.PEN_TOOL_STYLUS,
                0,
                lastPenX,
                lastPenY,
                pressure,
                0,
                0,
                timeMs * 1000L
        );
    }

    private void applyFingerButtonAnchor(float parentX, float parentY) {
        lastPenParentX = parentX;
        lastPenParentY = parentY;
        if (surfaceView.mapParentPointToNormalized(parentX, parentY, penCoordinates, false)) {
            lastPenX = Math.round(penCoordinates[0] * 65535.0f);
            lastPenY = Math.round(penCoordinates[1] * 65535.0f);
        }
    }

    private float[] touchCentroid(MotionEvent event) {
        float x = 0.0f;
        float y = 0.0f;
        for (int index = 0; index < event.getPointerCount(); ++index) {
            x += event.getX(index);
            y += event.getY(index);
        }
        return new float[]{ x / event.getPointerCount(), y / event.getPointerCount() };
    }

    private void cancelFingerGestureForStylus() {
        if (fingerGestureState == null || !fingerGestureState.isActive()) return;
        handler.removeCallbacks(commitFingerRunnable);
        FingerGestureState.Result result = fingerGestureState.onCancel();
        if (result == FingerGestureState.Result.CANCEL_INK) {
            endFingerInk(lastPenParentX, lastPenParentY, SystemClock.uptimeMillis(), true);
        }
        fingerPrimaryPointerId = -1;
    }

    private void handleWheelTap(boolean stylusGesture) {
        WheelInteractionState.Result result = wheelInteractionState.onWheelTap(
                wheelOverlay.hasSelection()
        );
        if (result == WheelInteractionState.Result.DISMISS_WHEEL) {
            if (stylusGesture) wheelGestureGuard.onSelectionCommitted();
            wheelOverlay.dismiss();
        } else if (result == WheelInteractionState.Result.EXECUTE_SELECTION) {
            if (stylusGesture) wheelGestureGuard.onSelectionCommitted();
            executeWheelSelection(wheelOverlay.commitSelection());
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_PAGE_UP && keyCode != KeyEvent.KEYCODE_PAGE_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        InputDevice device = event.getDevice();
        String deviceName = device != null ? device.getName() : "";
        boolean smartPenKeyboard = deviceName.toLowerCase(java.util.Locale.ROOT)
                .contains("xiaomi smart pen");
        Log.d(TAG, "Pen key " + keyCode + " action=" + event.getAction()
                + " repeat=" + event.getRepeatCount() + " device=" + deviceName);
        if (!smartPenKeyboard) return super.dispatchKeyEvent(event);

        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handleUpperButtonReleased();
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            handleLowerButtonDown();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            handleLowerButtonReleased();
            return true;
        }
        return true;
    }

    private void handleUpperButtonReleased() {
        if (macroRecording) {
            server.sendInputCommand(NetworkServer.INPUT_ACTION_RECORD_CANCEL, lastPenX, lastPenY);
            macroRecording = false;
            showTemporaryMessage("已取消本次录制");
        } else {
            cancelPenForWheel();
            server.sendInputCommand(NetworkServer.INPUT_ACTION_RECORD_START, lastPenX, lastPenY);
            macroRecording = true;
            showRecordingMessage();
        }
    }

    private void handleLowerButtonDown() {
        wheelInteractionState.onLowerDown(macroRecording);
    }

    private void handleLowerButtonReleased() {
        WheelInteractionState.Result result = wheelInteractionState.onLowerUp(macroRecording);
        if (result == WheelInteractionState.Result.STOP_RECORDING) {
            server.sendInputCommand(NetworkServer.INPUT_ACTION_RECORD_STOP, lastPenX, lastPenY);
            macroRecording = false;
            showTemporaryMessage("录制已结束并保存，可从轮盘回放");
        } else if (result == WheelInteractionState.Result.SHOW_WHEEL) {
            cancelPenForWheel();
            wheelOverlay.showAt(lastPenParentX, lastPenParentY);
        }
    }

    private void cancelPenForWheel() {
        if (!penDown && !penInContent) return;
        int action = penDown
                ? NetworkServer.PEN_ACTION_CANCEL
                : NetworkServer.PEN_ACTION_HOVER_EXIT;
        server.sendPenEvent(
                action,
                NetworkServer.PEN_TOOL_STYLUS,
                0,
                lastPenX,
                lastPenY,
                0,
                0,
                0,
                android.os.SystemClock.uptimeMillis() * 1000L
        );
        penDown = false;
        penInContent = false;
    }

    private void executeWheelSelection(NetworkServer.WheelSlot slot) {
        if (slot == null) return;
        server.sendInputCommand(slot, lastPenX, lastPenY);
        if (slot.action == NetworkServer.INPUT_ACTION_RECORD_START) {
            macroRecording = true;
            showRecordingMessage();
        } else if (slot.action == NetworkServer.INPUT_ACTION_RECORD_STOP) {
            macroRecording = false;
            showTemporaryMessage("录制已结束并保存");
        } else if (slot.action == NetworkServer.INPUT_ACTION_RECORD_CANCEL) {
            macroRecording = false;
            showTemporaryMessage("已取消本次录制");
        } else if (slot.action == NetworkServer.INPUT_ACTION_ENTER_MOUSE_MODE) {
            mouseMode = true;
            eraserMode = false;
            showTemporaryMessage("鼠标模式：悬停移动 · 落笔拖动 · 轻点单击");
        } else if (slot.action == NetworkServer.INPUT_ACTION_EXIT_MOUSE_MODE) {
            mouseMode = false;
            eraserMode = false;
            showTemporaryMessage("已退出鼠标模式，恢复手写笔");
        } else if (slot.action == NetworkServer.INPUT_ACTION_TOGGLE_ERASER_MODE) {
            eraserMode = !eraserMode;
            mouseMode = false;
            showTemporaryMessage(eraserMode ? "已进入橡皮擦模式" : "已退出橡皮擦模式");
        } else if (slot.label != null && !slot.label.isEmpty()) {
            showTemporaryMessage("已执行: " + slot.label);
        }
    }

    private void showRecordingMessage() {
        handler.removeCallbacks(hideOverlayRunnable);
        statsOverlay.setText("● 正在录制操作\n按下键结束并保存 · 再按上键取消");
        statsOverlay.setTextColor(Color.rgb(255, 100, 100));
        statsOverlay.setVisibility(View.VISIBLE);
    }

    private void sendPenSample(MotionEvent event, int pointerIndex, int action) {
        int tool = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
                ? NetworkServer.PEN_TOOL_ERASER
                : NetworkServer.PEN_TOOL_STYLUS;
        int buttons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            buttons |= NetworkServer.PEN_BUTTON_BARREL;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            buttons |= NetworkServer.PEN_BUTTON_SECONDARY;
        }

        float pressure = Math.max(0.0f, Math.min(1.0f, event.getPressure(pointerIndex)));
        float tiltRadians = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex);
        float orientation = event.getOrientation(pointerIndex);
        double tiltDegrees = Math.toDegrees(tiltRadians);
        int tiltXHundredths = (int) Math.round(
                Math.sin(orientation) * tiltDegrees * 100.0
        );
        int tiltYHundredths = (int) Math.round(
                -Math.cos(orientation) * tiltDegrees * 100.0
        );

        server.sendPenEvent(
                action,
                tool,
                buttons,
                lastPenX,
                lastPenY,
                Math.round(pressure * 65535.0f),
                tiltXHundredths,
                tiltYHundredths,
                event.getEventTime() * 1000L
        );
    }

    private void applyPreferredRefreshRate(int requestedFps) {
        negotiatedFps = requestedFps > 0 ? requestedFps : 120;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Display display = getDisplay();
            if (display != null) {
                Display.Mode[] modes = display.getSupportedModes();
                Display.Mode bestMode = null;
                Display.Mode currentMode = display.getMode();
                float bestScore = Float.MAX_VALUE;
                for (Display.Mode mode : modes) {
                    float resolutionPenalty = (currentMode != null
                            && (mode.getPhysicalWidth() != currentMode.getPhysicalWidth()
                            || mode.getPhysicalHeight() != currentMode.getPhysicalHeight())) ? 1000.0f : 0.0f;
                    float score = resolutionPenalty + Math.abs(mode.getRefreshRate() - negotiatedFps);
                    if (score < bestScore) {
                        bestScore = score;
                        bestMode = mode;
                    }
                }
                if (bestMode != null) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(lp);
                    Log.i(TAG, "Matched display mode to stream: " + bestMode.getPhysicalWidth() + "x" + bestMode.getPhysicalHeight() + " @" + bestMode.getRefreshRate() + "Hz (requested " + negotiatedFps + "Hz)");
                }
            }
        }
        if (surfaceView != null) {
            Surface surface = surfaceView.getHolder().getSurface();
            if (surface != null && surface.isValid() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        surface.setFrameRate((float) negotiatedFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
                    } else {
                        surface.setFrameRate((float) negotiatedFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Unable to request surface frame rate: " + e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Display getCurrentDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getDisplay();
        }
        return getWindowManager().getDefaultDisplay();
    }

    private void applyFullscreenImmersive() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyFullscreenImmersive();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreenImmersive();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(commitFingerRunnable);
        if (fingerGestureState != null) {
            fingerGestureState.onCancel();
            fingerPrimaryPointerId = -1;
        }
        if (penDown || penInContent) {
            server.sendPenEvent(
                    NetworkServer.PEN_ACTION_CANCEL,
                    NetworkServer.PEN_TOOL_STYLUS,
                    0,
                    lastPenX,
                    lastPenY,
                    0,
                    0,
                    0,
                    android.os.SystemClock.uptimeMillis() * 1000L
            );
            penDown = false;
            penInContent = false;
        }
        super.onPause();
    }

    private void toggleStatsOverlay() {
        handler.removeCallbacks(hideOverlayRunnable);
        if (statsOverlay.getVisibility() == View.VISIBLE) {
            statsOverlay.setVisibility(View.GONE);
        } else {
            statsOverlay.setVisibility(View.VISIBLE);
            handler.postDelayed(hideOverlayRunnable, 4000);
        }
    }

    private void showTemporaryMessage(String msg) {
        handler.removeCallbacks(hideOverlayRunnable);
        statsOverlay.setTextColor(Color.GREEN);
        statsOverlay.setText(msg);
        statsOverlay.setVisibility(View.VISIBLE);
        handler.postDelayed(hideOverlayRunnable, 3000);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created, attaching to decoder");
        applyPreferredRefreshRate(negotiatedFps);
        decoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "Surface changed: " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed, detaching from decoder");
        decoder.setSurface(null);
    }

    @Override
    public void onConnected(String hostInfo) {
        handler.post(() -> {
            statsOverlay.setTextColor(Color.GREEN);
            statsOverlay.setText("已连接: " + hostInfo + "\nWindows Ink 已启用 · 小米手写笔可直接书写");
            statsOverlay.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideOverlayRunnable);
            handler.postDelayed(hideOverlayRunnable, 2500);
            if (displayService != null) {
                displayService.updateNotificationText("副屏运行中 (" + hostInfo + ")");
            }
        });
    }

    @Override
    public void onDisconnected() {
        handler.post(() -> {
            handler.removeCallbacks(commitFingerRunnable);
            if (fingerGestureState != null) fingerGestureState.onCancel();
            fingerPrimaryPointerId = -1;
            penDown = false;
            penInContent = false;
            macroRecording = false;
            mouseMode = false;
            eraserMode = false;
            wheelInteractionState.reset();
            wheelGestureGuard.reset();
            if (wheelOverlay != null) wheelOverlay.dismiss();
            statsOverlay.setTextColor(Color.GREEN);
            statsOverlay.setText("主机已断开，等待重新连接...\n端口: 27183");
            statsOverlay.setVisibility(View.VISIBLE);
            if (displayService != null) {
                displayService.updateNotificationText("等待主机连接...");
            }
        });
    }

    @Override
    public void onResolutionChanged(int width, int height, int fps) {
        handler.post(() -> {
            applyPreferredRefreshRate(fps);
            surfaceView.setVideoSize(width, height);
            if (height > width) {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            } else {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        });
    }

    @Override
    public void onTouchInputConfig(TouchInputConfig config) {
        handler.post(() -> {
            cancelFingerGestureForStylus();
            touchInputMode = config.mode;
            touchPressurePercent = config.pressurePercent;
            String modeLabel = config.mode == TouchInputConfig.MODE_STYLUS_ONLY
                    ? "仅触控笔"
                    : config.mode == TouchInputConfig.MODE_FINGER ? "手指模拟笔" : "自动识别";
            Log.i(TAG, "Touch input config: mode=" + modeLabel
                    + ", pressure=" + touchPressurePercent + "%");
        });
    }

    @Override
    public void onStatsUpdate(float fps, int bitrateKbps) {
        handler.post(() -> {
            if (statsOverlay.getVisibility() == View.VISIBLE && !macroRecording) {
                statsOverlay.setText(String.format(
                        "ReDisplay [USB 有线链路]\n帧率: %.1f FPS | 码率: %.1f Mbps\n缩放: %s | 小米笔: Windows Ink",
                        fps,
                        bitrateKbps / 1000.0f,
                        surfaceView.getScaleMode().name()
                ));
            }
        });
    }

    @Override
    public void onWheelConfig(NetworkServer.WheelConfig config) {
        handler.post(() -> {
            wheelOverlay.setConfig(config);
            Log.i(TAG, "Wheel config applied, revision=" + config.revision);
        });
    }

    @Override
    public void onMacroCatalog(NetworkServer.MacroCatalog catalog) {
        handler.post(() -> {
            wheelOverlay.setMacroCatalog(catalog);
            Log.i(TAG, "Macro catalog applied, revision=" + catalog.revision);
        });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(commitFingerRunnable);
        if (serviceConn != null) {
            unbindService(serviceConn);
        }
        if (server != null) {
            server.stop();
        }
        if (decoder != null) {
            decoder.release();
        }
        super.onDestroy();
    }
}
