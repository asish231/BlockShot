package com.blockshot.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VillagerTest {

    private static final SurfaceProvider GROUND = (x, z) -> 5.0;

    @Test
    void givenVillager_whenUpdatedOverTime_thenItWalks() {
        Villager v = new Villager(0, 0, 0.5f, 0.5f, 0.5f, 123L);
        double startX = v.x, startZ = v.z;
        double moved = 0;
        for (int i = 0; i < 300; i++) {
            double px = v.x, pz = v.z;
            v.update(1.0 / 30, GROUND);
            moved += Math.hypot(v.x - px, v.z - pz);
        }
        assertTrue(moved > 0.5, "a villager should roam over time, moved=" + moved);
        // It also should not teleport arbitrarily far from home.
        assertTrue(Math.hypot(v.x - startX, v.z - startZ) < 12,
                "villager should stay near its home area");
    }

    @Test
    void givenVillager_whenUpdated_thenFollowsTerrainHeight() {
        Villager v = new Villager(0, 0, 0.5f, 0.5f, 0.5f, 1L);
        v.update(0.1, GROUND);
        assertEquals(5.0, v.y, 1e-9, "villager Y should follow the surface");
    }
}
