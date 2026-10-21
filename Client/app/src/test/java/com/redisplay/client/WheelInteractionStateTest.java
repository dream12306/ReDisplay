package com.redisplay.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WheelInteractionStateTest {
    @Test
    public void shortLowerClickShowsWheelOnlyOnRelease() {
        WheelInteractionState state = new WheelInteractionState();
        assertEquals(WheelInteractionState.Result.NONE, state.onLowerDown(false));
        assertEquals(WheelInteractionState.Result.NONE, state.onLowerDown(false));
        assertEquals(WheelInteractionState.Result.SHOW_WHEEL, state.onLowerUp(false));
    }

    @Test
    public void lowerClickStopsRecordingWithoutShowingWheel() {
        WheelInteractionState state = new WheelInteractionState();
        assertEquals(WheelInteractionState.Result.NONE, state.onLowerDown(true));
        assertEquals(WheelInteractionState.Result.STOP_RECORDING, state.onLowerUp(true));
    }

    @Test
    public void tappingCenterOrOutsideDismissesWheel() {
        WheelInteractionState state = new WheelInteractionState();
        assertEquals(WheelInteractionState.Result.DISMISS_WHEEL, state.onWheelTap(false));
        assertEquals(WheelInteractionState.Result.EXECUTE_SELECTION, state.onWheelTap(true));
    }
}
