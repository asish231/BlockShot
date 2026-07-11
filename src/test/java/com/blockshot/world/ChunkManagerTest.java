package com.blockshot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
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
                double surf = cm.surfaceY(x, z);
                assertTrue(surf >= 0.0, "surface must be valid");
            }
        }
    }

    @Test
    void givenCityChunk_whenColumnHeightsCompared_thenPlazaIsFlat() {
        ChunkManager cm = manager(1);
        WorldLayout.City city = new WorldLayout(42L).nearestCity(0, 0);
        int baseX = city.chunkX() * Chunk.SIZE;
        int baseZ = city.chunkZ() * Chunk.SIZE;
        int reference = cm.columnHeight(baseX + 2, baseZ + 2);
        assertEquals(reference, cm.columnHeight(baseX + 5, baseZ + 9));
        assertEquals(reference, cm.columnHeight(baseX + 13, baseZ + 3));
    }

    @Test
    void givenOpenGround_whenBlockedQueried_thenNotBlocked() {
        ChunkManager cm = manager(1);
        int cx = 18, cz = 18;
        while (cm.isCityChunk(cx, cz) || cm.hasHamlet(cx, cz)) cx++;
        double x = cx * Chunk.SIZE + 0.5, z = cz * Chunk.SIZE + 0.5;
        cm.update(x, z);
        double feet = cm.surfaceY(x, z);
        assertFalse(cm.isBlocked(x, z, feet),
                "open countryside should have no solid walls to collide with");
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

    @Test
    void givenCityCentreChunk_whenDistrictQueried_thenLandmarkIsGuaranteed() {
        ChunkManager cm = manager(1);
        WorldLayout.City city = cm.layout().nearestCity(0, 0);
        int plaza = cm.columnHeight(city.chunkX() * Chunk.SIZE + Chunk.SIZE / 2,
                city.chunkZ() * Chunk.SIZE + Chunk.SIZE / 2);

        assertEquals(ChunkManager.DistrictType.LANDMARK,
                cm.districtType(city.chunkX(), city.chunkZ(), plaza));
    }

    @Test
    void givenRoadBetweenCities_whenSurfaceQueried_thenAsphaltAboveWater() {
        ChunkManager cm = manager(1);
        WorldLayout layout = cm.layout();
        WorldLayout.City from = layout.cityForRegion(0, 0);
        WorldLayout.City to = layout.cityForRegion(1, 0);
        int midX = (from.chunkX() + to.chunkX()) * Chunk.SIZE / 2;
        int midZ = (from.chunkZ() + to.chunkZ()) * Chunk.SIZE / 2;
        int[] road = findCountrysideRoadCell(layout, midX, midZ);

        int height = cm.columnHeight(road[0], road[1]);
        assertTrue(height > TerrainGenerator.WATER_LEVEL, "roads must stay above water");
        assertEquals(BlockMaterial.ASPHALT,
                cm.generatedBlockAt(new BlockPos(road[0], height - 1, road[1])));
    }

    @Test
    void givenHamletChunk_whenGenerated_thenBlocksMatchCoordinateQueries() {
        ChunkManager cm = manager(1);
        int[] hamlet = findHamletChunk(cm);
        int cx = hamlet[0], cz = hamlet[1];

        cm.update(cx * Chunk.SIZE + Chunk.SIZE / 2.0, cz * Chunk.SIZE + Chunk.SIZE / 2.0);
        Chunk chunk = cm.chunkAt(cx, cz);
        assertNotNull(chunk);

        boolean sawCottage = false;
        for (Map.Entry<BlockPos, BlockMaterial> entry : chunk.blocks.entrySet()) {
            assertEquals(entry.getValue(), cm.generatedBlockAt(entry.getKey()),
                    "generated block mismatch at " + entry.getKey());
            if (entry.getValue() == BlockMaterial.BRICK) sawCottage = true;
        }
        assertTrue(sawCottage, "farmstead cottage walls should exist");
    }

    private static int[] findCountrysideRoadCell(WorldLayout layout, int midX, int midZ) {
        for (int dx = -48; dx <= 48; dx++) {
            for (int dz = -48; dz <= 48; dz++) {
                int x = midX + dx, z = midZ + dz;
                if (!layout.isRoad(x, z)) continue;
                if (layout.isUrbanChunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE))) continue;
                return new int[] {x, z};
            }
        }
        throw new AssertionError("no countryside road cell near " + midX + "," + midZ);
    }

    private static int[] findHamletChunk(ChunkManager cm) {
        for (int cx = -40; cx <= 40; cx++) {
            for (int cz = -40; cz <= 40; cz++) {
                if (cm.hasHamlet(cx, cz)) return new int[] {cx, cz};
            }
        }
        throw new AssertionError("no hamlet found in the search area");
    }
}
