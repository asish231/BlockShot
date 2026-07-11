package com.blockshot.world;

/**
 * Deterministic large-scale settlement plan for the infinite world. The map is
 * split into coarse square regions; every region receives one city whose
 * position inside the region, and whose size, are jittered from the world seed.
 * That keeps the layout reproducible per seed while breaking the symmetric
 * lattice look. Neighbouring cities are linked by winding countryside roads.
 *
 * <p>All queries are pure functions of coordinates, so chunks can be generated,
 * unloaded and regenerated in any order without changing the world.
 */
public final class WorldLayout {

    /** Width/height of one city region in chunks. */
    public static final int REGION_SIZE = 12;
    /** City centres keep at least this many chunks from their region border. */
    private static final int REGION_MARGIN = 3;
    /** Half-width of a countryside road corridor in blocks. */
    private static final double ROAD_HALF_WIDTH = 2.2;
    /** Maximum sideways bend of a road's midpoint in blocks. */
    private static final int ROAD_BEND = 24;

    private final long seed;

    public WorldLayout(long seed) {
        this.seed = seed;
    }

    // ------------------------------------------------------------------
    // Cities
    // ------------------------------------------------------------------

    /** The single city seeded inside a region, jittered off the lattice. */
    public City cityForRegion(int rx, int rz) {
        int range = REGION_SIZE - REGION_MARGIN * 2;
        int offsetX = REGION_MARGIN + Math.floorMod((int) mix(rx, rz, 17), range);
        int offsetZ = REGION_MARGIN + Math.floorMod((int) mix(rx, rz, 31), range);
        int radius = 1 + Math.floorMod((int) mix(rx, rz, 53), 2);
        return new City(rx * REGION_SIZE + offsetX, rz * REGION_SIZE + offsetZ, radius);
    }

    /** Nearest city to a chunk; ties break towards the lowest coordinates. */
    public City nearestCity(int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_SIZE);
        int rz = Math.floorDiv(cz, REGION_SIZE);
        City best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                City city = cityForRegion(rx + dx, rz + dz);
                long distance = distanceSquared(cx, cz, city);
                if (distance < bestDistance || (distance == bestDistance && orderedBefore(city, best))) {
                    best = city;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    public boolean isUrbanChunk(int cx, int cz) {
        City city = nearestCity(cx, cz);
        return Math.max(Math.abs(cx - city.chunkX()), Math.abs(cz - city.chunkZ())) <= city.radiusChunks();
    }

    /** True for the exact centre chunk of a city, home of its landmark. */
    public boolean isCityCenter(int cx, int cz) {
        City city = nearestCity(cx, cz);
        return city.chunkX() == cx && city.chunkZ() == cz;
    }

    // ------------------------------------------------------------------
    // Roads
    // ------------------------------------------------------------------

    /** True when a world block column lies on a countryside road corridor. */
    public boolean isRoad(int wx, int wz) {
        return roadAt(wx, wz) != null;
    }

    /**
     * Closest road corridor covering a world block, or {@code null} off-road.
     * Every region's city is linked to the cities of its +x and +z neighbour
     * regions, and each link bends around a jittered midpoint so highways wind
     * through the countryside instead of forming a straight grid.
     */
    public RoadSample roadAt(int wx, int wz) {
        int rx = Math.floorDiv(Math.floorDiv(wx, Chunk.SIZE), REGION_SIZE);
        int rz = Math.floorDiv(Math.floorDiv(wz, Chunk.SIZE), REGION_SIZE);
        RoadSample best = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int axis = 0; axis < 2; axis++) {
                    RoadSample sample = sampleRoad(rx + dx, rz + dz, axis == 0, wx, wz);
                    if (sample != null && (best == null || sample.distance() < best.distance())) {
                        best = sample;
                    }
                }
            }
        }
        return best;
    }

    private RoadSample sampleRoad(int rx, int rz, boolean towardX, int wx, int wz) {
        City from = cityForRegion(rx, rz);
        City to = cityForRegion(rx + (towardX ? 1 : 0), rz + (towardX ? 0 : 1));
        double ax = blockCenter(from.chunkX());
        double az = blockCenter(from.chunkZ());
        double bx = blockCenter(to.chunkX());
        double bz = blockCenter(to.chunkZ());

        long bend = mix(rx * 2 + (towardX ? 0 : 1), rz, 71);
        double mx = (ax + bx) / 2.0 + signedRange(bend, ROAD_BEND);
        double mz = (az + bz) / 2.0 + signedRange(bend >>> 17, ROAD_BEND);

        double[] first = projectToSegment(wx + 0.5, wz + 0.5, ax, az, mx, mz);
        double[] second = projectToSegment(wx + 0.5, wz + 0.5, mx, mz, bx, bz);
        double distance;
        double t;
        if (first[0] <= second[0]) {
            distance = first[0];
            t = first[1] * 0.5;
        } else {
            distance = second[0];
            t = 0.5 + second[1] * 0.5;
        }
        if (distance > ROAD_HALF_WIDTH) return null;
        return new RoadSample(distance, t, from, to);
    }

    private static double blockCenter(int chunkCoord) {
        return chunkCoord * (double) Chunk.SIZE + Chunk.SIZE / 2.0;
    }

    /** Distance from a point to a segment plus the clamped projection parameter. */
    private static double[] projectToSegment(double px, double pz, double ax, double az,
                                             double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double qx = ax + dx * t;
        double qz = az + dz * t;
        return new double[] {Math.hypot(px - qx, pz - qz), t};
    }

    private static double signedRange(long hash, int bound) {
        return Math.floorMod((int) hash, bound * 2 + 1) - bound;
    }

    private static boolean orderedBefore(City city, City other) {
        if (other == null) return true;
        if (city.chunkX() != other.chunkX()) return city.chunkX() < other.chunkX();
        return city.chunkZ() < other.chunkZ();
    }

    private static long distanceSquared(int cx, int cz, City city) {
        long dx = (long) cx - city.chunkX();
        long dz = (long) cz - city.chunkZ();
        return dx * dx + dz * dz;
    }

    private long mix(int x, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ salt;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** A city: its centre chunk plus how many chunks it extends in each direction. */
    public record City(int chunkX, int chunkZ, int radiusChunks) { }

    /** Where a block sits on a road: distance to the centreline and progress 0..1. */
    public record RoadSample(double distance, double t, City from, City to) { }
}
