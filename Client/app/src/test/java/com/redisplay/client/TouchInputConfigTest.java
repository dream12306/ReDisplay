package com.redisplay.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TouchInputConfigTest {
    @Test
    public void legacyConfigUsesSafeDefaults() {
        TouchInputConfig config = TouchInputConfig.fromConfigPayload(new byte[12], 12);
        assertEquals(TouchInputConfig.MODE_AUTO, config.mode);
        assertEquals(55, config.pressurePercent);
    }

    @Test
    public void extendedConfigReadsAndClampsTouchSettings() {
        byte[] payload = new byte[16];
        payload[12] = (byte) TouchInputConfig.MODE_FINGER;
        payload[13] = (byte) 120;
        TouchInputConfig config = TouchInputConfig.fromConfigPayload(payload, payload.length);
        assertEquals(TouchInputConfig.MODE_FINGER, config.mode);
        assertEquals(100, config.pressurePercent);
    }

    @Test
    public void invalidModeFallsBackToAuto() {
        byte[] payload = new byte[16];
        payload[12] = (byte) 99;
        payload[13] = (byte) 45;
        TouchInputConfig config = TouchInputConfig.fromConfigPayload(payload, payload.length);
        assertEquals(TouchInputConfig.MODE_AUTO, config.mode);
        assertEquals(45, config.pressurePercent);
    }
}
