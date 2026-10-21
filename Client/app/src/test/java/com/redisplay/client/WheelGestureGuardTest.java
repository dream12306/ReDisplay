package com.redisplay.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WheelGestureGuardTest {
    @Test
    public void suppressesRemainderOfStylusGestureAfterWheelCommand() {
        WheelGestureGuard guard = new WheelGestureGuard();
        guard.onSelectionCommitted();

        assertTrue(guard.shouldConsume(false));
        assertTrue(guard.shouldConsume(true));
        assertFalse(guard.shouldConsume(false));
    }
}
