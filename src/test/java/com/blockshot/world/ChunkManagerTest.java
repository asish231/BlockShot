package com.blockshot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkManagerTest {

    private ChunkManager manager(int renderDistance) {
        return new ChunkManager(new TerrainGenerator(42L), renderDistance);
    }

    @Test
    void givenPlayerAtOrigin_whenUpdated_thenExpectedChunkCountLoaded() {
        ChunkManager cm = manager(2);
        cm.update(0, 0);
        int expected = (2 * 2 + 1) * (2 * 2 + 1); // 5x5 grid
        assertEquals(expected, cm.loadedCount());
    }

    @Test
    void givenPlayerMovesFarAway_whenUpdated_thenMemoryStaysBounded() {
        ChunkManager cm = manager(2);
        cm.update(0, 0);
        int before = cm.loadedCount();
        cm.update(2000, -2000);
        // Distant chunks unloaded, so the count stays bounded rather than growing.
        assertTrue(cm.loadedCount() <= before + 2,
                "loaded chunks should not accumulate: " + cm.loadedCount());
    }

    @Test
    void givenNegativeChunkCoords_whenUpdated_thenChunksLoadWithoutError() {
        ChunkManager cm = manager(1);
        cm.update(-500, -500);
        assertEquals(9, cm.loadedCount());
    }

    @Test
    void givenAnyColumn_whenSurfaceQueried_thenNeverBelowWater() {
        ChunkManager cm = manager(1);
        for (int x = -100; x <= 100; x += 11) {
            for (int z = -100; z <= 100; z += 11) {
                assertTrue(cm.surfaceY(x, z) >= cm.waterLevel(),
                        "player must not sink below the water surface");
            }
        }
    }

    @Test
    void givenCityChunk_whenColumnHeightsCompared_thenPlazaIsFlat() {
        ChunkManager cm = manager(1);
        // Chunk (0,0) sits at the city lattice centre and is flattened to a plaza.
        int reference = cm.columnHeight(2, 2);
        assertEquals(reference, cm.columnHeight(5, 9));
        assertEquals(reference, cm.columnHeight(13, 3));
    }

    @Test
    void givenOpenGround_whenBlockedQueried_thenNotBlocked() {
        ChunkManager cm = manager(1);
        // Chunk (18,18) is outside the city lattice, so it has no building walls.
        cm.update(300, 300);
        double feet = cm.surfaceY(300.5, 300.5);
        assertTrue(!cm.isBlocked(300.5, 300.5, feet),
                "open plains should have no solid walls to collide with");
    }

    @Test
    void givenLargeRenderDistance_whenIncrementallyUpdated_thenPerFrameGenerationIsBounded() {
        ChunkManager cm = manager(3);

        int generated = cm.updateIncremental(0, 0, 2);

        assertEquals(2, generated);
        assertEquals(2, cm.loadedCount());
    }

    @Test
    void givenRepeatedIncrementalUpdates_whenBudgetApplied_thenWholeAreaEventuallyLoads() {
        ChunkManager cm = manager(2);

        for (int i = 0; i < 20; i++) cm.updateIncremental(0, 0, 3);

        assertEquals(25, cm.loadedCount());
    }
}
