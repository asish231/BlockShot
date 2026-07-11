package com.blockshot.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcTest {

    private static final SurfaceProvider GROUND = (x, z) -> 2;

    @Test
    void givenWantedPlayer_whenPoliceUpdated_thenPolicePursuesPlayer() {
        Npc police = new Npc(UUID.randomUUID(), NpcRole.POLICE, 0, 0, 3L);

        police.update(1, GROUND, 10, 0, 2);

        assertEquals(NpcState.PURSUE, police.state());
        assertTrue(police.x > 0);
        assertEquals(2, police.y, 1e-9);
    }

    @Test
    void givenCrimeNearby_whenCivilianUpdated_thenCivilianFlees() {
        Npc civilian = new Npc(UUID.randomUUID(), NpcRole.CIVILIAN, 1, 0, 7L);
        civilian.witnessCrime(0, 0);

        civilian.update(1, GROUND, 0, 0, 1);

        assertEquals(NpcState.FLEE, civilian.state());
        assertTrue(civilian.x > 1);
    }
}