package com.blockshot.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InputCaptureStateTest {

    @Test
    void givenCapturedAtLaunch_whenCreated_thenPointerMotionSteersTheCamera() {
        InputCaptureState capture = new InputCaptureState(true);

        assertTrue(capture.captured());
    }

    @Test
    void givenWindowFocused_whenFocusLost_thenMouseIsReleasedForOtherApps() {
        InputCaptureState capture = new InputCaptureState(true);

        capture.onFocusChanged(false);

        assertFalse(capture.captured());
    }

    @Test
    void givenFocusWasLost_whenFocusRegained_thenLookResumesWithoutRequiringAClick() {
        InputCaptureState capture = new InputCaptureState(true);
        capture.onFocusChanged(false);

        capture.onFocusChanged(true);

        assertTrue(capture.captured());
    }

    @Test
    void givenMouseReleased_whenWindowClicked_thenClickOnlyRegrabsAndIsSwallowed() {
        InputCaptureState capture = new InputCaptureState(true);
        capture.onFocusChanged(false);

        boolean swallowed = capture.onPressShouldRecapture();

        assertTrue(swallowed);
        assertTrue(capture.captured());
    }

    @Test
    void givenMouseCaptured_whenWindowClicked_thenClickPassesThroughAsAGameAction() {
        InputCaptureState capture = new InputCaptureState(true);

        boolean swallowed = capture.onPressShouldRecapture();

        assertFalse(swallowed);
        assertTrue(capture.captured());
    }
}
