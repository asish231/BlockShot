package com.blockshot.world;

/**
 * Deterministic, seed-based terrain generator. Given the same seed it always
 * produces the same heights and biomes for any world coordinate, which makes the
 * infinite world reproducible and testable.
 */
public final class TerrainGenerator {

    /** Blocks at or below this height are considered underwater. */
    public static final int WATER_LEVEL = 3;
    public static final int MAX_HEIGHT = 24;

    public enum Biome { OCEAN, PLAINS, FOREST, HILLS }

    private final long seed;

    public TerrainGenerator(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /** Smooth 2D value noise in the range roughly [-1, 1]. */
    private double valueNoise(double x, double z, double frequency, int salt) {
        double sx = x * frequency, sz = z * frequency;
        int x0 = (int) Math.floor(sx), z0 = (int) Math.floor(sz);
        double fx = sx - x0, fz = sz - z0;
        double v00 = hash(x0, z0, salt);
        double v10 = hash(x0 + 1, z0, salt);
        double v01 = hash(x0, z0 + 1, salt);
        double v11 = hash(x0 + 1, z0 + 1, salt);
        double ux = smooth(fx), uz = smooth(fz);
        double a = v00 + (v10 - v00) * ux;
        double b = v01 + (v11 - v01) * ux;
        return a + (b - a) * uz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Deterministic hash of an integer grid point to [-1, 1]. */
    private double hash(int x, int z, int salt) {
        long h = seed;
        h = h * 6364136223846793005L + (x * 0x9E3779B97F4A7C15L);
        h = h * 6364136223846793005L + (z * 0xC2B2AE3D27D4EB4FL);
        h = h * 6364136223846793005L + (salt * 0x165667B19E3779F9L);
        h ^= (h >>> 33);
        return ((h & 0xFFFFFF) / (double) 0xFFFFFF) * 2.0 - 1.0;
    }

    /** Surface height (number of stacked blocks) at a world column. */
    public int heightAt(int gx, int gz) {
        double h = 0;
        h += valueNoise(gx, gz, 0.012, 1) * 10.0;
        h += valueNoise(gx, gz, 0.035, 2) * 4.0;
        h += valueNoise(gx, gz, 0.080, 3) * 1.8;
        int height = (int) Math.round(WATER_LEVEL + 3 + h);
        if (height < 0) height = 0;
        if (height > MAX_HEIGHT) height = MAX_HEIGHT;
        return height;
    }

    public Biome biomeAt(int gx, int gz) {
        int height = heightAt(gx, gz);
        if (height <= WATER_LEVEL) return Biome.OCEAN;
        double t = valueNoise(gx, gz, 0.006, 7);
        if (height >= WATER_LEVEL + 9) return Biome.HILLS;
        if (t > 0.15) return Biome.FOREST;
        return Biome.PLAINS;
    }

    /** Top walkable Y (feet level) at a column, accounting for water surface. */
    public double surfaceY(int gx, int gz) {
        int height = heightAt(gx, gz);
        return Math.max(height, WATER_LEVEL);
    }
}
