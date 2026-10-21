package com.redisplay.client;

/** Pure state machine for turning direct-touch input into ink or virtual pen buttons. */
final class FingerGestureState {
    enum Result {
        NONE,
        START_INK,
        MOVE_INK,
        TAP,
        END_INK,
        CANCEL_INK,
        LOWER_BUTTON,
        CANCELLED
    }

    private enum Mode { IDLE, PENDING, INK, CHORD, CANCELLED, IGNORE_UNTIL_CLEAR }

    private final float touchSlop;
    private final long joinWindowMs;
    private Mode mode = Mode.IDLE;
    private long downTimeMs;
    private float downX;
    private float downY;
    private float chordAnchorX;
    private float chordAnchorY;
    private int maxPointerCount;

    FingerGestureState(float touchSlop, long joinWindowMs) {
        this.touchSlop = Math.max(1.0f, touchSlop);
        this.joinWindowMs = Math.max(1L, joinWindowMs);
    }

    Result onDown(long timeMs, float x, float y) {
        reset();
        mode = Mode.PENDING;
        downTimeMs = timeMs;
        downX = x;
        downY = y;
        maxPointerCount = 1;
        return Result.NONE;
    }

    Result onAdditionalDown(int pointerCount, long timeMs, float centroidX, float centroidY) {
        if (mode == Mode.PENDING) {
            if (timeMs - downTimeMs > joinWindowMs) {
                mode = Mode.INK;
                return Result.START_INK;
            }
            if (pointerCount == 2) {
                mode = Mode.CHORD;
                maxPointerCount = pointerCount;
                chordAnchorX = centroidX;
                chordAnchorY = centroidY;
                return Result.NONE;
            }
            mode = Mode.CANCELLED;
            return Result.CANCELLED;
        }
        if (mode == Mode.CHORD) {
            if (pointerCount >= 3) {
                maxPointerCount = pointerCount;
                mode = Mode.CANCELLED;
                return Result.CANCELLED;
            }
            chordAnchorX = centroidX;
            chordAnchorY = centroidY;
        }
        return Result.NONE;
    }

    Result onPrimaryMove(long timeMs, float x, float y) {
        if (mode == Mode.PENDING) {
            if (timeMs - downTimeMs >= joinWindowMs || distanceSquared(x, y, downX, downY) >= touchSlop * touchSlop) {
                mode = Mode.INK;
                return Result.START_INK;
            }
            return Result.NONE;
        }
        return mode == Mode.INK ? Result.MOVE_INK : Result.NONE;
    }

    Result onChordMove(long timeMs, float centroidX, float centroidY) {
        if (mode != Mode.CHORD) return Result.NONE;
        // Pointer centroids jump whenever the third finger joins or one finger
        // starts lifting. Once the UI says "release to execute", movement must
        // not silently disarm the button gesture. Keep only its latest anchor.
        chordAnchorX = centroidX;
        chordAnchorY = centroidY;
        return Result.NONE;
    }

    Result commitPending() {
        if (mode != Mode.PENDING) return Result.NONE;
        mode = Mode.INK;
        return Result.START_INK;
    }

    Result onPrimaryUp(long timeMs, int remainingPointers) {
        if (mode == Mode.PENDING) {
            mode = remainingPointers > 0 ? Mode.IGNORE_UNTIL_CLEAR : Mode.IDLE;
            return Result.TAP;
        }
        if (mode == Mode.INK) {
            mode = remainingPointers > 0 ? Mode.IGNORE_UNTIL_CLEAR : Mode.IDLE;
            return Result.END_INK;
        }
        if (mode == Mode.CHORD) return finishChord(timeMs, remainingPointers);
        if ((mode == Mode.CANCELLED || mode == Mode.IGNORE_UNTIL_CLEAR) && remainingPointers == 0) {
            reset();
        }
        return Result.NONE;
    }

    Result onOtherPointerUp(long timeMs, int remainingPointers) {
        if (mode == Mode.CHORD) return finishChord(timeMs, remainingPointers);
        if ((mode == Mode.CANCELLED || mode == Mode.IGNORE_UNTIL_CLEAR) && remainingPointers == 0) {
            reset();
        }
        return Result.NONE;
    }

    Result onCancel() {
        Result result = mode == Mode.INK ? Result.CANCEL_INK : Result.CANCELLED;
        reset();
        return result;
    }

    void onAllPointersReleased() {
        reset();
    }

    boolean isActive() { return mode != Mode.IDLE; }
    boolean isInk() { return mode == Mode.INK; }
    boolean isPending() { return mode == Mode.PENDING; }
    boolean isChord() { return mode == Mode.CHORD; }
    float getDownX() { return downX; }
    float getDownY() { return downY; }
    long getDownTimeMs() { return downTimeMs; }
    float getChordAnchorX() { return chordAnchorX; }
    float getChordAnchorY() { return chordAnchorY; }
    private Result finishChord(long timeMs, int remainingPointers) {
        if (remainingPointers > 0) return Result.NONE;
        Result result = Result.CANCELLED;
        if (maxPointerCount == 2) result = Result.LOWER_BUTTON;
        reset();
        return result;
    }

    private void reset() {
        mode = Mode.IDLE;
        downTimeMs = 0L;
        downX = 0.0f;
        downY = 0.0f;
        chordAnchorX = 0.0f;
        chordAnchorY = 0.0f;
        maxPointerCount = 0;
    }

    private static float distanceSquared(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}
