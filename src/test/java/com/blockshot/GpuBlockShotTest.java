package com.blockshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class GpuBlockShotTest {
    @Test
    void normalizeAngleKeepsValuesInRange() {
        GpuBlockShot game = new GpuBlockShot();
        assertEquals(0.0, game.normalizeAngle(0.0), 1e-9);
        assertEquals(Math.PI, game.normalizeAngle(Math.PI), 1e-9);
        assertEquals(-Math.PI, game.normalizeAngle(-Math.PI), 1e-9);
        assertEquals(-0.5, game.normalizeAngle(5.783185307179586), 1e-9);
    }
}
