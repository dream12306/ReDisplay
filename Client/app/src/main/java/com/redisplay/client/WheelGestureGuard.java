package com.redisplay.client;

/** Prevents the ACTION_UP that selected a wheel item from cancelling its playback. */
final class WheelGestureGuard {
    private boolean suppressUntilTerminal;

    void onSelectionCommitted() {
        suppressUntilTerminal = true;
    }

    boolean shouldConsume(boolean terminal) {
        if (!suppressUntilTerminal) return false;
        if (terminal) suppressUntilTerminal = false;
        return true;
    }

    void reset() {
        suppressUntilTerminal = false;
    }
}
