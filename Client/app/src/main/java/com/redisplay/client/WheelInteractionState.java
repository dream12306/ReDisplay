package com.redisplay.client;

final class WheelInteractionState {
    enum Result {
        NONE,
        SHOW_WHEEL,
        STOP_RECORDING,
        EXECUTE_SELECTION,
        DISMISS_WHEEL
    }

    private boolean lowerPressed;

    Result onLowerDown(boolean recording) {
        if (lowerPressed) return Result.NONE;
        lowerPressed = true;
        return Result.NONE;
    }

    Result onLowerUp(boolean recording) {
        if (!lowerPressed) return Result.NONE;
        lowerPressed = false;
        return recording ? Result.STOP_RECORDING : Result.SHOW_WHEEL;
    }

    Result onWheelTap(boolean hasSelection) {
        return hasSelection ? Result.EXECUTE_SELECTION : Result.DISMISS_WHEEL;
    }

    void reset() {
        lowerPressed = false;
    }
}

