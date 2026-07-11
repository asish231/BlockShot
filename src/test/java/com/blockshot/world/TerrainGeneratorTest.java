package com.blockshot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainGeneratorTest {

    @Test
    void givenSameSeed_whenHeightQueried_thenDeterministic() {
        TerrainGenerator a = new TerrainGenerator(99L);
        TerrainGenerator b = new TerrainGenerator(99L);
        for (int i = -50; i < 50; i += 7) {
            assertEquals(a.heightAt(i, i * 2), b.heightAt(i, i * 2),
                    "same seed must produce identical heights");
        }
    }

    @Test
    void givenAnyColumn_whenHeightQueried_thenWithinBounds() {
        TerrainGenerator gen = new TerrainGenerator(7L);
        for (int x = -200; x <= 200; x += 13) {
            for (int z = -200; z <= 200; z += 17) {
                int h = gen.heightAt(x, z);
                assertTrue(h >= 0 && h <= TerrainGenerator.MAX_HEIGHT,
                        "height out of range at " + x + "," + z + ": " + h);
            }
        }
    }

    @Test
    void givenNegativeCoordinates_whenHeightQueried_thenStable() {
        TerrainGenerator gen = new TerrainGenerator(3L);
        assertEquals(gen.heightAt(-1234, -5678), gen.heightAt(-1234, -5678));
    }

    @Test
    void givenOceanColumn_whenBiomeQueried_thenOcean() {
        TerrainGenerator gen = new TerrainGenerator(3L);
        // Find a column at or below water level and confirm it reports OCEAN.
        boolean checkedOne = false;
        for (int x = -300; x <= 300 && !checkedOne; x += 3) {
            for (int z = -300; z <= 300; z += 3) {
                if (gen.heightAt(x, z) <= TerrainGenerator.WATER_LEVEL) {
                    assertEquals(TerrainGenerator.Biome.OCEAN, gen.biomeAt(x, z));
                    checkedOne = true;
                    break;
                }
            }
        }
        assertTrue(checkedOne, "expected at least one ocean column somewhere");
    }
}
