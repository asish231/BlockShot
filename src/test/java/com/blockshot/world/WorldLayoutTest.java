package com.blockshot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldLayoutTest {

    @Test
    void givenSameSeed_whenCitiesQueried_thenLayoutIsReproducible() {
        WorldLayout first = new WorldLayout(42L);
        WorldLayout second = new WorldLayout(42L);

        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) assertEquals(first.nearestCity(x, z), second.nearestCity(x, z));
        }
    }

    @Test
    void givenSameSeed_whenRoadsQueried_thenRoadsAreReproducible() {
        WorldLayout first = new WorldLayout(42L);
        WorldLayout second = new WorldLayout(42L);

        for (int x = -400; x <= 400; x += 7) {
            for (int z = -400; z <= 400; z += 7) assertEquals(first.isRoad(x, z), second.isRoad(x, z));
        }
    }

    @Test
    void givenDifferentSeeds_whenCitiesQueried_thenLayoutsDiffer() {
        WorldLayout first = new WorldLayout(42L);
        WorldLayout second = new WorldLayout(43L);

        assertNotEquals(citySet(first), citySet(second));
    }

    @Test
    void givenGeneratedCities_whenOffsetsMeasured_thenPlacementIsNotSymmetricLattice() {
        Set<Integer> xOffsets = new HashSet<>();
        Set<Integer> zOffsets = new HashSet<>();
        WorldLayout layout = new WorldLayout(42L);
        for (WorldLayout.City city : citySet(layout)) {
            xOffsets.add(Math.floorMod(city.chunkX(), WorldLayout.REGION_SIZE));
            zOffsets.add(Math.floorMod(city.chunkZ(), WorldLayout.REGION_SIZE));
        }

        assertTrue(xOffsets.size() > 2);
        assertTrue(zOffsets.size() > 2);
    }

    @Test
    void givenGeneratedCities_whenSizesMeasured_thenCitySizesVary() {
        Set<Integer> radii = new HashSet<>();
        for (WorldLayout.City city : citySet(new WorldLayout(42L))) radii.add(city.radiusChunks());

        assertTrue(radii.size() > 1, "expected mixed city sizes, got " + radii);
    }

    @Test
    void givenNeighbouringCities_whenRoadScanned_thenCorridorConnectsThem() {
        WorldLayout layout = new WorldLayout(42L);
        WorldLayout.City from = layout.cityForRegion(0, 0);
        WorldLayout.City to = layout.cityForRegion(1, 0);

        assertTrue(roadNear(layout, from), "road should reach city " + from);
        assertTrue(roadNear(layout, to), "road should reach city " + to);

        int cells = 0;
        int minX = Math.min(from.chunkX(), to.chunkX()) * Chunk.SIZE - 32;
        int maxX = Math.max(from.chunkX(), to.chunkX()) * Chunk.SIZE + 32;
        int minZ = Math.min(from.chunkZ(), to.chunkZ()) * Chunk.SIZE - 32;
        int maxZ = Math.max(from.chunkZ(), to.chunkZ()) * Chunk.SIZE + 32;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (layout.isRoad(x, z)) cells++;
            }
        }
        assertTrue(cells > 100, "expected a real road corridor, found " + cells + " road cells");
    }

    @Test
    void givenCity_whenCenterAndEdgesQueried_thenUrbanAreaMatchesRadius() {
        WorldLayout layout = new WorldLayout(42L);
        WorldLayout.City city = layout.cityForRegion(0, 0);

        assertTrue(layout.isCityCenter(city.chunkX(), city.chunkZ()));
        assertFalse(layout.isCityCenter(city.chunkX() + 1, city.chunkZ()));
        assertTrue(layout.isUrbanChunk(city.chunkX() + city.radiusChunks(), city.chunkZ()));
        assertFalse(layout.isUrbanChunk(city.chunkX() + city.radiusChunks() + 1, city.chunkZ()));
    }

    private boolean roadNear(WorldLayout layout, WorldLayout.City city) {
        int bx = city.chunkX() * Chunk.SIZE + Chunk.SIZE / 2;
        int bz = city.chunkZ() * Chunk.SIZE + Chunk.SIZE / 2;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (layout.isRoad(bx + dx, bz + dz)) return true;
            }
        }
        return false;
    }

    private static Set<WorldLayout.City> citySet(WorldLayout layout) {
        Set<WorldLayout.City> cities = new HashSet<>();
        for (int x = -30; x <= 30; x += 3) {
            for (int z = -30; z <= 30; z += 3) cities.add(layout.nearestCity(x, z));
        }
        return cities;
    }
}
