package com.blockshot.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerTest {

    /** Flat ground at y=0 with no walls. */
    private static final CollisionWorld FLAT = new CollisionWorld() {
        @Override public double surfaceY(double x, double z) { return 0; }
        @Override public boolean isBlocked(double x, double z, double feetY) { return false; }
    };

    /** Flat ground but every horizontal move is blocked. */
    private static final CollisionWorld WALLED = new CollisionWorld() {
        @Override public double surfaceY(double x, double z) { return 0; }
        @Override public boolean isBlocked(double x, double z, double feetY) { return true; }
    };

    @Test
    void givenPlayerAboveGround_whenUpdated_thenFallsAndLands() {
        Player p = new Player(0, 5, 0);
        for (int i = 0; i < 120; i++) {
            p.update(1.0 / 60, 0, 0, false, false, FLAT);
        }
        assertEquals(0, p.y, 1e-6, "player should settle on the ground");
        assertTrue(p.onGround);
    }

    @Test
    void givenGroundedPlayer_whenJumping_thenRises() {
        Player p = new Player(0, 0, 0);
        p.update(1.0 / 60, 0, 0, false, false, FLAT); // settle to ground
        assertTrue(p.onGround);
        p.update(1.0 / 60, 0, 0, true, false, FLAT);   // jump
        assertTrue(p.y > 0, "jumping should lift the player off the ground");
    }

    @Test
    void givenForwardInput_whenUpdated_thenMovesForward() {
        Player p = new Player(0, 0, 0);
        p.yaw = 0; // facing +z
        p.update(0.1, 1, 0, false, false, FLAT);
        assertTrue(p.z > 0, "forward with yaw 0 should increase z");
        assertEquals(0, p.x, 1e-6);
    }

    @Test
    void givenWallEverywhere_whenMoving_thenPositionUnchanged() {
        Player p = new Player(0, 0, 0);
        double startX = p.x, startZ = p.z;
        p.update(0.1, 1, 1, false, false, WALLED);
        assertEquals(startX, p.x, 1e-9);
        assertEquals(startZ, p.z, 1e-9);
    }
}
