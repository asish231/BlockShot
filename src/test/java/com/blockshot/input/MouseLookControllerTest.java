package com.blockshot.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.blockshot.entity.MouseLookController;

class MouseLookControllerTest {

    @Test
    void givenFirstPointerSample_whenMoved_thenCameraDoesNotJump() {
        MouseLookController look = new MouseLookController(0.12);

        look.onPointer(400, 300);

        assertEquals(0, look.consumeYawDelta(), 1e-9);
        assertEquals(0, look.consumePitchDelta(), 1e-9);
    }

    @Test
    void givenPointerMovement_whenConsumed_thenDeltaIsResponsiveAndNotRepeated() {
        MouseLookController look = new MouseLookController(0.12);
        look.onPointer(400, 300);
        look.onPointer(410, 305);

        assertEquals(1.2, look.consumeYawDelta(), 1e-9);
        assertEquals(0.6, look.consumePitchDelta(), 1e-9);
        assertEquals(0, look.consumeYawDelta(), 1e-9);
    }

    @Test
    void givenFocusWasLost_whenPointerReturns_thenStalePositionIsIgnored() {
        MouseLookController look = new MouseLookController(0.12);
        look.onPointer(400, 300);
        look.onPointer(410, 305);
        look.reset();
        look.onPointer(900, 700);

        assertEquals(1.2, look.consumeYawDelta(), 1e-9);
        assertEquals(0.6, look.consumePitchDelta(), 1e-9);
    }
}