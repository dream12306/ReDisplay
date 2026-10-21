package com.redisplay.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FingerContactPolicyTest {
    @Test
    public void mouseModeTouchContactProducesPressedDragSequence() {
        FingerContactPolicy.Actions actions = FingerContactPolicy.forMode(true);
        assertEquals(NetworkServer.PEN_ACTION_DOWN, actions.begin);
        assertEquals(NetworkServer.PEN_ACTION_MOVE, actions.move);
        assertEquals(NetworkServer.PEN_ACTION_UP, actions.end);
    }
}
