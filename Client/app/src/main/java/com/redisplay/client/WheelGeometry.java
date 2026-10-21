package com.redisplay.client;

final class WheelGeometry {
    private static final int SECTOR_COUNT = 8;

    private WheelGeometry() {}

    static int selectionIndex(
            float dx,
            float dy,
            float centerRadius,
            float innerRadius,
            float outerRadius) {
        float distance = (float) Math.hypot(dx, dy);
        if (distance < centerRadius || distance > outerRadius) return -1;

        double degrees = Math.toDegrees(Math.atan2(dy, dx)) + 90.0;
        while (degrees < 0) degrees += 360.0;
        int sector = ((int) Math.floor((degrees + 22.5) / 45.0)) % SECTOR_COUNT;
        return distance <= innerRadius ? sector : sector + SECTOR_COUNT;
    }
}

