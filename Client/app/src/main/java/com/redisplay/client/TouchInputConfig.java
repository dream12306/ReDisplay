package com.redisplay.client;

final class TouchInputConfig {
    static final int MODE_AUTO = 0;
    static final int MODE_STYLUS_ONLY = 1;
    static final int MODE_FINGER = 2;
    static final int DEFAULT_PRESSURE_PERCENT = 55;

    final int mode;
    final int pressurePercent;

    TouchInputConfig(int mode, int pressurePercent) {
        this.mode = mode >= MODE_AUTO && mode <= MODE_FINGER ? mode : MODE_AUTO;
        this.pressurePercent = Math.max(10, Math.min(100,
                pressurePercent == 0 ? DEFAULT_PRESSURE_PERCENT : pressurePercent));
    }

    static TouchInputConfig fromConfigPayload(byte[] payload, int size) {
        if (payload == null || size < 16 || payload.length < 16) {
            return new TouchInputConfig(MODE_AUTO, DEFAULT_PRESSURE_PERCENT);
        }
        return new TouchInputConfig(payload[12] & 0xFF, payload[13] & 0xFF);
    }
}
