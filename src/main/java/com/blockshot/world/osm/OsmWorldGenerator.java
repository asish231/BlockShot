package com.blockshot.world.osm;

import com.blockshot.entity.Npc;
import com.blockshot.entity.NpcRole;
import com.blockshot.entity.Villager;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import com.blockshot.world.Chunk;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Spatially indexes OSM vectors and turns them into solid, queryable voxel chunks. */
public final class OsmWorldGenerator {

    /** Number of base blocks; the walkable ground surface is at {@code GROUND_HEIGHT - 1}. */
    public static final int GROUND_HEIGHT = 4;
    private static final int FLOOR_HEIGHT = 3;
    private static final double WALL_HALF_WIDTH = 0.55;
    private static final double CORNER_RADIUS = 0.8;

    private final OsmCoordinate projection;
    private final OsmHttpClient httpClient;
    private final OsmParser parser;
    private final ConcurrentMap<Sector, CompletableFuture<Void>> sectorLoads = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, List<OsmElement.OsmBuilding>> chunkBuildings = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, List<OsmElement.OsmRoad>> chunkRoads = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, List<OsmElement.OsmWater>> chunkWater = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, List<OsmElement.OsmPark>> chunkParks = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    private volatile String lastLoadError;

    /** Creates the default Reykjavik-centred loader, configurable through world.osm properties. */
    public OsmWorldGenerator() {
        this(defaultProjection(), new OsmHttpClient());
    }

    public OsmWorldGenerator(OsmCoordinate projection, OsmHttpClient httpClient) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.parser = new OsmParser(projection);
    }

    /** Creates an already-loaded generator, primarily useful for deterministic tests and tools. */
    public OsmWorldGenerator(OsmCoordinate projection, Collection<? extends OsmElement> elements) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.httpClient = null;
        this.parser = new OsmParser(projection);
        indexElements(Objects.requireNonNull(elements, "elements"));
        revision.set(1L);
    }

    public OsmCoordinate projection() {
        return projection;
    }

    public long revision() {
        return revision.get();
    }

    public String lastLoadError() {
        return lastLoadError;
    }

    /** Starts loading the 512-metre sector containing a chunk without blocking the caller. */
    public CompletableFuture<Void> loadChunk(int chunkX, int chunkZ) {
        if (httpClient == null) return CompletableFuture.completedFuture(null);
        int worldX;
        int worldZ;
        try {
            worldX = Math.multiplyExact(chunkX, Chunk.SIZE);
            worldZ = Math.multiplyExact(chunkZ, Chunk.SIZE);
        } catch (ArithmeticException exception) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Chunk is outside voxel integer range", exception));
        }
        Sector sector = new Sector(
                Math.floorDiv(worldX, OsmHttpClient.SECTOR_SIZE_METERS),
                Math.floorDiv(worldZ, OsmHttpClient.SECTOR_SIZE_METERS));
        return sectorLoads.computeIfAbsent(sector, this::loadSector);
    }

    private CompletableFuture<Void> loadSector(Sector sector) {
        return httpClient.fetchSector(sector.x(), sector.z(), projection)
                .thenApply(parser::parse)
                .thenAccept(elements -> {
                    indexElements(elements);
                    lastLoadError = null;
                    revision.incrementAndGet();
                })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        lastLoadError = rootMessage(failure);
                        System.err.println("OSM sector " + sector.x() + "," + sector.z()
                                + " could not be loaded: " + lastLoadError);
                    }
                });
    }

    /** Material at an unedited world cell, or {@code null} for air. */
    public BlockMaterial getBlockAt(BlockPos position) {
        Objects.requireNonNull(position, "position");
        loadChunk(position.chunkX(), position.chunkZ());
        return indexedBlockAt(position);
    }

    /** Generates the currently available representation of a chunk and starts its sector load. */
    public Chunk generateChunk(int chunkX, int chunkZ) {
        loadChunk(chunkX, chunkZ);
        Chunk chunk = new Chunk(chunkX, chunkZ);
        int baseX = chunkX * Chunk.SIZE;
        int baseZ = chunkZ * Chunk.SIZE;
        for (int localX = 0; localX < Chunk.SIZE; localX++) {
            for (int localZ = 0; localZ < Chunk.SIZE; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                int upperBound = indexedUpperBoundAt(x, z);
                for (int y = 0; y <= upperBound; y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockMaterial material = indexedBlockAt(position);
                    if (material != null) chunk.blocks.put(position, material);
                }
            }
        }

        // Spawn citizens/NPCs along roads dynamically in OSM mode
        long chunkKey = Chunk.key(chunkX, chunkZ);
        List<OsmElement.OsmRoad> roads = chunkRoads.getOrDefault(chunkKey, List.of());
        if (!roads.isEmpty()) {
            Random rng = new Random(chunkKey ^ 0x5DEECE66DL);
            // Spawn a few villagers
            for (int i = 0; i < Math.min(5, roads.size() * 2); i++) {
                OsmElement.OsmRoad road = roads.get(rng.nextInt(roads.size()));
                if (!road.points().isEmpty()) {
                    BlockPos pt = road.points().get(rng.nextInt(road.points().size()));
                    double rx = pt.x() + 0.5 + (rng.nextDouble() - 0.5) * 2;
                    double rz = pt.z() + 0.5 + (rng.nextDouble() - 0.5) * 2;
                    chunk.villagers.add(new Villager(rx, rz,
                            0.35f + rng.nextFloat() * 0.6f,
                            0.35f + rng.nextFloat() * 0.6f,
                            0.35f + rng.nextFloat() * 0.6f,
                            rng.nextLong()));
                }
            }
            // Spawn some patrolling civilian and police NPCs
            for (int i = 0; i < Math.min(3, roads.size()); i++) {
                OsmElement.OsmRoad road = roads.get(rng.nextInt(roads.size()));
                if (!road.points().isEmpty()) {
                    BlockPos pt = road.points().get(rng.nextInt(road.points().size()));
                    double rx = pt.x() + 0.5;
                    double rz = pt.z() + 0.5;
                    NpcRole role = (i == 0) ? NpcRole.POLICE : NpcRole.CIVILIAN;
                    Npc npc = new Npc(UUID.randomUUID(), role, rx, rz, rng.nextLong());
                    npc.y = GROUND_HEIGHT;
                    chunk.npcs.add(npc);
                }
            }
        }

        return chunk;
    }

    public int columnHeight(int x, int z) {
        loadChunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        return isInsideWater(x, z) ? GROUND_HEIGHT - 1 : GROUND_HEIGHT;
    }

    public int upperBoundAt(int x, int z) {
        loadChunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        return indexedUpperBoundAt(x, z);
    }

    public boolean hasBuildingsInChunk(int chunkX, int chunkZ) {
        loadChunk(chunkX, chunkZ);
        return !chunkBuildings.getOrDefault(Chunk.key(chunkX, chunkZ), List.of()).isEmpty();
    }

    public boolean isRoadAt(int x, int z) {
        loadChunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        return isOnRoad(x, z);
    }

    public boolean isWaterAt(int x, int z) {
        loadChunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        return isInsideWater(x, z);
    }

    private BlockMaterial indexedBlockAt(BlockPos position) {
        if (position.y() < 0) return null;
        if (position.y() < GROUND_HEIGHT) {
            if (position.y() == GROUND_HEIGHT - 1) {
                if (isInsideWater(position.x(), position.z())) return BlockMaterial.WATER;
                if (isOnRoad(position.x(), position.z())) return BlockMaterial.ASPHALT;
                if (isInsidePark(position.x(), position.z())) return BlockMaterial.GRASS;
                return BlockMaterial.DIRT;
            }
            return position.y() == 0 ? BlockMaterial.STONE : BlockMaterial.DIRT;
        }

        for (OsmElement.OsmBuilding building : buildingsAt(position.x(), position.z())) {
            BlockMaterial material = buildingMaterialAt(building, position);
            if (material != null) return material;
        }
        return null;
    }

    private BlockMaterial buildingMaterialAt(OsmElement.OsmBuilding building, BlockPos position) {
        double px = position.x() + 0.5;
        double pz = position.z() + 0.5;
        if (!insidePolygon(px, pz, building.footprint())) return null;

        int floor = GROUND_HEIGHT;
        int roof = floor + building.heightLevels() * FLOOR_HEIGHT;
        if (position.y() < floor || position.y() > roof) return null;
        if (position.y() == floor || position.y() == roof) return BlockMaterial.CONCRETE;

        BlockPos ladder = ladderCell(building.footprint());
        if (position.x() == ladder.x() && position.z() == ladder.z()) {
            return BlockMaterial.LADDER;
        }

        double edgeDistance = distanceToEdges(px, pz, building.footprint());
        if (edgeDistance > WALL_HALF_WIDTH) return null;
        boolean corner = distanceToVertices(px, pz, building.footprint()) <= CORNER_RADIUS;
        if (!corner && Math.floorMod(position.y() - floor, FLOOR_HEIGHT) == 1) {
            return BlockMaterial.GLASS;
        }
        return building.wallMaterial();
    }

    private int indexedUpperBoundAt(int x, int z) {
        int upperBound = GROUND_HEIGHT - 1;
        double px = x + 0.5;
        double pz = z + 0.5;
        for (OsmElement.OsmBuilding building : buildingsAt(x, z)) {
            if (insidePolygon(px, pz, building.footprint())) {
                upperBound = Math.max(upperBound,
                        GROUND_HEIGHT + building.heightLevels() * FLOOR_HEIGHT);
            }
        }
        return upperBound;
    }

    private List<OsmElement.OsmBuilding> buildingsAt(int x, int z) {
        return chunkBuildings.getOrDefault(chunkKeyAt(x, z), List.of());
    }

    private boolean isOnRoad(int x, int z) {
        double px = x + 0.5;
        double pz = z + 0.5;
        for (OsmElement.OsmRoad road : chunkRoads.getOrDefault(chunkKeyAt(x, z), List.of())) {
            double radius = road.widthMeters() / 2.0;
            List<BlockPos> points = road.points();
            for (int index = 0; index + 1 < points.size(); index++) {
                BlockPos first = points.get(index);
                BlockPos second = points.get(index + 1);
                if (distanceToSegment(px, pz, first.x(), first.z(), second.x(), second.z()) <= radius) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInsideWater(int x, int z) {
        double px = x + 0.5;
        double pz = z + 0.5;
        for (OsmElement.OsmWater water : chunkWater.getOrDefault(chunkKeyAt(x, z), List.of())) {
            if (insidePolygon(px, pz, water.polygon())) return true;
        }
        return false;
    }

    private boolean isInsidePark(int x, int z) {
        double px = x + 0.5;
        double pz = z + 0.5;
        for (OsmElement.OsmPark park : chunkParks.getOrDefault(chunkKeyAt(x, z), List.of())) {
            if (insidePolygon(px, pz, park.polygon())) return true;
        }
        return false;
    }

    private void indexElements(Collection<? extends OsmElement> elements) {
        for (OsmElement element : elements) {
            if (element instanceof OsmElement.OsmBuilding building) {
                indexPolygon(chunkBuildings, building, building.footprint(), 1);
            } else if (element instanceof OsmElement.OsmRoad road) {
                int padding = Math.max(1, (int) Math.ceil(road.widthMeters() / 2.0));
                indexGeometry(chunkRoads, road, road.points(), padding);
            } else if (element instanceof OsmElement.OsmWater water) {
                indexPolygon(chunkWater, water, water.polygon(), 0);
            } else if (element instanceof OsmElement.OsmPark park) {
                indexPolygon(chunkParks, park, park.polygon(), 0);
            }
        }
    }

    private static <T> void indexPolygon(ConcurrentMap<Long, List<T>> index, T element,
                                         List<BlockPos> points, int padding) {
        indexGeometry(index, element, points, padding);
    }

    private static <T> void indexGeometry(ConcurrentMap<Long, List<T>> index, T element,
                                          List<BlockPos> points, int padding) {
        Bounds bounds = bounds(points, padding);
        int minimumChunkX = Math.floorDiv(bounds.minimumX(), Chunk.SIZE);
        int maximumChunkX = Math.floorDiv(bounds.maximumX(), Chunk.SIZE);
        int minimumChunkZ = Math.floorDiv(bounds.minimumZ(), Chunk.SIZE);
        int maximumChunkZ = Math.floorDiv(bounds.maximumZ(), Chunk.SIZE);
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                long key = Chunk.key(chunkX, chunkZ);
                index.compute(key, (ignored, existing) -> appendDistinct(existing, element));
            }
        }
    }

    private static <T> List<T> appendDistinct(List<T> existing, T element) {
        if (existing != null && existing.contains(element)) return existing;
        List<T> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        updated.add(element);
        return List.copyOf(updated);
    }

    private static Bounds bounds(List<BlockPos> points, int padding) {
        int minimumX = Integer.MAX_VALUE;
        int maximumX = Integer.MIN_VALUE;
        int minimumZ = Integer.MAX_VALUE;
        int maximumZ = Integer.MIN_VALUE;
        for (BlockPos point : points) {
            minimumX = Math.min(minimumX, point.x());
            maximumX = Math.max(maximumX, point.x());
            minimumZ = Math.min(minimumZ, point.z());
            maximumZ = Math.max(maximumZ, point.z());
        }
        return new Bounds(saturatingAdd(minimumX, -padding), saturatingAdd(maximumX, padding),
                saturatingAdd(minimumZ, -padding), saturatingAdd(maximumZ, padding));
    }

    private static int saturatingAdd(int value, int amount) {
        long result = (long) value + amount;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, result));
    }

    private static boolean insidePolygon(double x, double z, List<BlockPos> polygon) {
        boolean inside = false;
        for (int current = 0, previous = polygon.size() - 1;
             current < polygon.size(); previous = current++) {
            BlockPos a = polygon.get(current);
            BlockPos b = polygon.get(previous);
            if (distanceToSegment(x, z, a.x(), a.z(), b.x(), b.z()) < 1.0e-9) return true;
            boolean crosses = (a.z() > z) != (b.z() > z)
                    && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x();
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static double distanceToEdges(double x, double z, List<BlockPos> polygon) {
        double best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < polygon.size(); index++) {
            BlockPos first = polygon.get(index);
            BlockPos second = polygon.get((index + 1) % polygon.size());
            best = Math.min(best, distanceToSegment(
                    x, z, first.x(), first.z(), second.x(), second.z()));
        }
        return best;
    }

    private static double distanceToVertices(double x, double z, List<BlockPos> polygon) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos point : polygon) {
            best = Math.min(best, Math.hypot(x - point.x(), z - point.z()));
        }
        return best;
    }

    private static double distanceToSegment(double px, double pz, double ax, double az,
                                            double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0) return Math.hypot(px - ax, pz - az);
        double progress = ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        progress = Math.max(0.0, Math.min(1.0, progress));
        return Math.hypot(px - (ax + progress * dx), pz - (az + progress * dz));
    }

    private static BlockPos ladderCell(List<BlockPos> polygon) {
        BlockPos corner = polygon.get(0);
        double centerX = 0.0;
        double centerZ = 0.0;
        int count = polygon.size();
        if (count > 1 && polygon.get(0).equals(polygon.get(count - 1))) count--;
        for (int index = 0; index < count; index++) {
            centerX += polygon.get(index).x();
            centerZ += polygon.get(index).z();
        }
        centerX /= count;
        centerZ /= count;
        int x = corner.x() + Integer.signum((int) Math.round(centerX - corner.x()));
        int z = corner.z() + Integer.signum((int) Math.round(centerZ - corner.z()));
        return new BlockPos(x, GROUND_HEIGHT + 1, z);
    }

    private static long chunkKeyAt(int x, int z) {
        return Chunk.key(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static OsmCoordinate defaultProjection() {
        double latitude = propertyDouble("world.osm.latitude", 64.1466);
        double longitude = propertyDouble("world.osm.longitude", -21.9426);
        return new OsmCoordinate(latitude, longitude);
    }

    private static double propertyDouble(String name, double defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number: " + value, exception);
        }
    }

    private record Sector(int x, int z) {
    }

    private record Bounds(int minimumX, int maximumX, int minimumZ, int maximumZ) {
    }
}