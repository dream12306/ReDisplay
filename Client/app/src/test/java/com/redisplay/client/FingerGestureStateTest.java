package com.redisplay.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FingerGestureStateTest {
    @Test
    public void oneFingerTapProducesCompleteTap() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        assertEquals(FingerGestureState.Result.NONE, state.onDown(0L, 20.0f, 30.0f));
        assertEquals(FingerGestureState.Result.TAP, state.onPrimaryUp(70L, 0));
        assertFalse(state.isActive());
    }

    @Test
    public void movementCommitsInkAndExtraFingerCannotBecomeButton() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        state.onDown(0L, 0.0f, 0.0f);
        assertEquals(FingerGestureState.Result.START_INK,
                state.onPrimaryMove(20L, 15.0f, 0.0f));
        assertEquals(FingerGestureState.Result.NONE,
                state.onAdditionalDown(2, 30L, 20.0f, 0.0f));
        assertEquals(FingerGestureState.Result.END_INK, state.onPrimaryUp(40L, 1));
        assertTrue(state.isActive());
        state.onAllPointersReleased();
        assertFalse(state.isActive());
    }

    @Test
    public void twoFingerChordEmitsOnlyLowerButtonOnFinalRelease() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        state.onDown(0L, 0.0f, 0.0f);
        state.onAdditionalDown(2, 40L, 50.0f, 50.0f);
        assertEquals(FingerGestureState.Result.NONE, state.onOtherPointerUp(80L, 1));
        assertEquals(FingerGestureState.Result.LOWER_BUTTON, state.onPrimaryUp(100L, 0));
    }

    @Test
    public void thirdFingerCancelsChordWithoutEmittingAnyButton() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        state.onDown(0L, 0.0f, 0.0f);
        state.onAdditionalDown(2, 30L, 40.0f, 40.0f);
        assertEquals(FingerGestureState.Result.CANCELLED,
                state.onAdditionalDown(3, 55L, 42.0f, 42.0f));
        assertEquals(FingerGestureState.Result.NONE, state.onOtherPointerUp(80L, 2));
        assertEquals(FingerGestureState.Result.NONE, state.onOtherPointerUp(95L, 1));
        assertEquals(FingerGestureState.Result.NONE, state.onPrimaryUp(110L, 0));
    }

    @Test
    public void additionalFingersCannotRearmCancelledChord() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        state.onDown(0L, 0.0f, 0.0f);
        state.onAdditionalDown(2, 20L, 10.0f, 10.0f);
        assertEquals(FingerGestureState.Result.CANCELLED,
                state.onAdditionalDown(3, 30L, 10.0f, 10.0f));
        assertEquals(FingerGestureState.Result.NONE,
                state.onAdditionalDown(4, 40L, 10.0f, 10.0f));
        assertEquals(FingerGestureState.Result.NONE, state.onPrimaryUp(60L, 0));
    }

    @Test
    public void lateSecondFingerCommitsFirstFingerAsInk() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        state.onDown(0L, 5.0f, 6.0f);
        assertEquals(FingerGestureState.Result.START_INK,
                state.onAdditionalDown(2, 150L, 20.0f, 20.0f));
        assertTrue(state.isInk());
    }

    @Test
    public void movingChordRemainsArmedAfterUserWasToldToRelease() {
        FingerGestureState state = new FingerGestureState(10.0f, 110L);
        state.onDown(0L, 0.0f, 0.0f);
        state.onAdditionalDown(2, 20L, 50.0f, 50.0f);
        assertEquals(FingerGestureState.Result.NONE,
                state.onChordMove(40L, 75.0f, 50.0f));
        assertEquals(FingerGestureState.Result.LOWER_BUTTON, state.onPrimaryUp(60L, 0));
    }
}
