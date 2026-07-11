package com.blockshot.world;

import com.blockshot.entity.CollisionWorld;
import com.blockshot.entity.Fish;
import com.blockshot.entity.Npc;
import com.blockshot.entity.NpcRole;
import com.blockshot.entity.Villager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Streams an effectively infinite world in and out around the player. Chunks are
 * generated on demand and unloaded once they fall outside the render distance, so
 * memory and per-frame work stay bounded no matter how far the player roams.
 *
 * <p>Responsibilities are deliberately narrow: terrain heights come from
 * {@link TerrainGenerator}, cities from the deterministic world layout, and
 * collision from the same edit-aware material cells used for rendering.
 */
public final class ChunkManager implements CollisionWorld {

    private static final int ROAD_MIN = 6;
    private static final int ROAD_MAX = 9;
    private static final int FLOOR_HEIGHT = 3;
    private static final int MAX_BUILDING_HEIGHT = 12;
    private static final int HAMLET_YARD_MIN = 3;   // first local cell of a farmstead yard
    private static final int HAMLET_YARD_MAX = 12;  // last local cell of a farmstead yard

    public enum DistrictType {
        BEACH,
        GARDEN_FARM,
        COURTYARD,
        MALL_HOSPITAL,
        LANDMARK,
        RESIDENTIAL
    }

    private final TerrainGenerator terrain;
    private final WorldLayout layout;
    private final int renderDistance;
    private final Map<Long, Chunk> loaded = new HashMap<>();
    private final WorldEditStore edits = new WorldEditStore();

    public ChunkManager(TerrainGenerator terrain, int renderDistance) {
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.layout = new WorldLayout(terrain.seed());
        this.renderDistance = renderDistance;
    }

    public TerrainGenerator terrain() {
        return terrain;
    }

    public Collection<Chunk> loadedChunks() {
        return loaded.values();
    }

    public int loadedCount() {
        return loaded.size();
    }

    public Chunk chunkAt(int cx, int cz) {
        return loaded.get(Chunk.key(cx, cz));
    }

    public WorldEditStore edits() {
        return edits;
    }

    /** Seeded large-scale plan of cities and roads backing this world. */
    public WorldLayout layout() {
        return layout;
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    /** Ensure chunks around (px,pz) are loaded and distant ones are unloaded. */
    public void update(double px, double pz) {
        updateIncremental(px, pz, Integer.MAX_VALUE);
    }

    /**
     * Streams chunks around the player while bounding generation work for this
     * call. Missing chunks are ordered from the player's current chunk outward.
     */
    public int updateIncremental(double px, double pz, int maxLoads) {
        if (maxLoads < 0) throw new IllegalArgumentException("maxLoads must not be negative");
        int pcx = Math.floorDiv((int) Math.floor(px), Chunk.SIZE);
        int pcz = Math.floorDiv((int) Math.floor(pz), Chunk.SIZE);

        loaded.entrySet().removeIf(entry -> {
            Chunk chunk = entry.getValue();
            return Math.abs((long) chunk.cx - pcx) > renderDistance
                    || Math.abs((long) chunk.cz - pcz) > renderDistance;
        });

        List<ChunkLoad> missing = new ArrayList<>();
        for (int dx = -renderDistance; dx <= renderDistance; dx++) {
            for (int dz = -renderDistance; dz <= renderDistance; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (!loaded.containsKey(Chunk.key(cx, cz))) {
                    missing.add(new ChunkLoad(cx, cz, (long) dx * dx + (long) dz * dz));
                }
            }
        }
        missing.sort(Comparator.comparingLong(ChunkLoad::distanceSquared)
                .thenComparingInt(ChunkLoad::cx)
                .thenComparingInt(ChunkLoad::cz));

        int generated = Math.min(maxLoads, missing.size());
        for (int i = 0; i < generated; i++) {
            ChunkLoad load = missing.get(i);
            loaded.put(Chunk.key(load.cx(), load.cz()), generate(load.cx(), load.cz()));
        }
        return generated;
    }

    // ------------------------------------------------------------------
    // World layout
    // ------------------------------------------------------------------

    private boolean isUrbanChunk(int cx, int cz) {
        return layout.isUrbanChunk(cx, cz);
    }

    /** True for chunks that belong to a city, exposed for HUD map rendering. */
    public boolean isCityChunk(int cx, int cz) {
        return isUrbanChunk(cx, cz);
    }

    /** True when a column's surface is road asphalt, urban street or highway. */
    public boolean isRoadSurface(int wx, int wz) {
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        if (isUrbanChunk(cx, cz)) return isRoadCell(wx, wz);
        return layout.isRoad(wx, wz);
    }

    /** Flat plaza height of the city that a chunk belongs to (above water). */
    private int cityPlazaHeight(int cx, int cz) {
        return plazaHeightOf(layout.nearestCity(cx, cz));
    }

    private int plazaHeightOf(WorldLayout.City city) {
        int centerBlockX = city.chunkX() * Chunk.SIZE + Chunk.SIZE / 2;
        int centerBlockZ = city.chunkZ() * Chunk.SIZE + Chunk.SIZE / 2;
        return Math.max(TerrainGenerator.WATER_LEVEL + 1, terrain.heightAt(centerBlockX, centerBlockZ));
    }

    /** Ground height of a world column, honouring city, hamlet and road grading. */
    public int columnHeight(int gx, int gz) {
        int cx = Math.floorDiv(gx, Chunk.SIZE);
        int cz = Math.floorDiv(gz, Chunk.SIZE);
        if (isUrbanChunk(cx, cz)) return cityPlazaHeight(cx, cz);
        if (hasHamlet(cx, cz) && inHamletYard(gx, gz)) return hamletGroundHeight(cx, cz);
        WorldLayout.RoadSample road = layout.roadAt(gx, gz);
        if (road != null) return roadHeight(road);
        return terrain.heightAt(gx, gz);
    }

    /** Roads ramp smoothly from one city plaza to the next and causeway water. */
    private int roadHeight(WorldLayout.RoadSample road) {
        double fromHeight = plazaHeightOf(road.from());
        double toHeight = plazaHeightOf(road.to());
        int height = (int) Math.round(fromHeight + (toHeight - fromHeight) * road.t());
        return Math.max(TerrainGenerator.WATER_LEVEL + 1, height);
    }

    // ------------------------------------------------------------------
    // Block, collision and surface queries
    // ------------------------------------------------------------------

    /** Edit-aware material at a world cell, or {@code null} for air. */
    public BlockMaterial blockAt(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        return nonAir(edits.resolve(pos, this::generatedBlockAt));
    }

    /** Deterministic unedited material at a world cell, loaded or not. */
    public BlockMaterial generatedBlockAt(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        if (pos.y() < 0) return null;

        int cx = pos.chunkX();
        int cz = pos.chunkZ();
        boolean urban = isUrbanChunk(cx, cz);
        int height = columnHeight(pos.x(), pos.z());
        if (pos.y() < height) {
            TerrainGenerator.Biome biome = urban
                    ? TerrainGenerator.Biome.PLAINS : terrain.biomeAt(pos.x(), pos.z());
            return terrainMaterial(pos.x(), pos.y(), pos.z(), height, biome, urban);
        }
        if (urban) {
            return cityBlockAt(pos, cx, cz, height);
        }
        if (hasHamlet(cx, cz)) {
            BlockMaterial hamlet = hamletBlockAt(pos.x(), pos.y(), pos.z(), cx, cz);
            if (hamlet != null) return hamlet;
        }
        if (pos.y() < TerrainGenerator.WATER_LEVEL) {
            return BlockMaterial.WATER;
        }
        return null;
    }

    /** Immutable generated blocks plus the current edit overlay for a chunk. */
    public Map<BlockPos, BlockMaterial> meshBlocks(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Map<BlockPos, BlockMaterial> result = new HashMap<>(chunk.blocks);
        WorldEditStore.ChunkSnapshot snapshot = edits.snapshot(chunk.cx, chunk.cz);
        for (BlockPos removal : snapshot.removals()) result.remove(removal);
        for (Map.Entry<BlockPos, BlockMaterial> addition : snapshot.additions().entrySet()) {
            BlockMaterial material = nonAir(addition.getValue());
            if (material == null) result.remove(addition.getKey());
            else result.put(addition.getKey(), material);
        }
        return Map.copyOf(result);
    }

    /**
     * Revision affecting a chunk mesh, including horizontal neighbors whose
     * boundary edits can expose or hide a seam face.
     */
    public long meshRevision(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return edits.revision(chunk.cx, chunk.cz)
                + edits.revision(chunk.cx - 1, chunk.cz)
                + edits.revision(chunk.cx + 1, chunk.cz)
                + edits.revision(chunk.cx, chunk.cz - 1)
                + edits.revision(chunk.cx, chunk.cz + 1);
    }

    public boolean placeBlock(BlockPos pos, BlockMaterial material) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(material, "material");
        if (material == BlockMaterial.AIR) return false;
        return edits.place(pos, material, this::generatedBlockAt);
    }

    public BlockMaterial breakBlock(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        return nonAir(edits.breakBlock(pos, this::generatedBlockAt));
    }

    public void applyReplicatedEdit(BlockPos pos, BlockMaterial material, boolean placed) {
        Objects.requireNonNull(pos, "pos");
        if (placed) {
            Objects.requireNonNull(material, "material");
            if (material == BlockMaterial.AIR) {
                throw new IllegalArgumentException("AIR cannot be placed");
            }
            edits.applyReplicatedAddition(pos, material);
        } else {
            edits.applyReplicatedRemoval(pos);
        }
    }

    public boolean isSolidCell(int x, int y, int z) {
        BlockMaterial material = blockAt(new BlockPos(x, y, z));
        return material != null && material.solid();
    }

    /** True if a player standing at (x,z) with feet at feetY would clip a wall. */
    public boolean isBlocked(double x, double z, double feetY) {
        int y0 = (int) Math.floor(feetY + 0.05);
        int y1 = (int) Math.floor(feetY + 1.6);
        double rr = 0.3;
        double[] xs = {x - rr, x + rr};
        double[] zs = {z - rr, z + rr};
        for (double cx : xs) {
            for (double cz : zs) {
                int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
                for (int by = y0; by <= y1; by++) {
                    if (isSolidCell(bx, by, bz)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public double surfaceY(double x, double z) {
        int gx = (int) Math.floor(x), gz = (int) Math.floor(z);
        WorldEditStore.ChunkSnapshot snapshot = edits.snapshot(
                Math.floorDiv(gx, Chunk.SIZE), Math.floorDiv(gz, Chunk.SIZE));
        int highest = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, BlockMaterial> addition : snapshot.additions().entrySet()) {
            BlockPos pos = addition.getKey();
            if (pos.x() == gx && pos.z() == gz && addition.getValue() != null && addition.getValue().solid()) {
                highest = Math.max(highest, pos.y());
            }
        }

        int generatedUpperBound = generatedColumnUpperBound(gx, gz);
        for (int y = generatedUpperBound; y >= 0 && y > highest; y--) {
            BlockMaterial material = blockAt(new BlockPos(gx, y, gz));
            if (material != null && material.solid()) {
                highest = y;
                break;
            }
        }
        return highest == Integer.MIN_VALUE ? 0.0 : (double) highest + 1.0;
    }

    public int waterLevel() {
        return TerrainGenerator.WATER_LEVEL;
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    private Chunk generate(int cx, int cz) {
        Chunk chunk = new Chunk(cx, cz);
        Random rng = new Random(mix(cx, cz, terrain.seed()));
        boolean urban = isUrbanChunk(cx, cz);
        boolean hamlet = !urban && hasHamlet(cx, cz);
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = baseX + lx, wz = baseZ + lz;
                int height = columnHeight(wx, wz);
                TerrainGenerator.Biome biome = urban ? TerrainGenerator.Biome.PLAINS : terrain.biomeAt(wx, wz);
                addColumn(chunk, wx, wz, height, biome, urban);

                if (!urban) {
                    addWater(chunk, wx, wz, height);
                    boolean clearGround = !(hamlet && inHamletYard(wx, wz)) && !layout.isRoad(wx, wz);
                    if (clearGround) {
                        addVegetation(chunk, wx, wz, height, biome, rng);
                    }
                    maybeSpawnFish(chunk, wx, wz, height, rng);
                }
            }
        }

        if (urban) {
            int plaza = columnHeight(baseX + Chunk.SIZE / 2, baseZ + Chunk.SIZE / 2);
            buildCity(chunk, cx, cz, plaza, rng);
            addCrowd(chunk, baseX, baseZ, plaza);
        } else if (hamlet) {
            buildHamlet(chunk, cx, cz, rng);
        }
        return chunk;
    }

    private void addColumn(Chunk chunk, int wx, int wz, int height,
                           TerrainGenerator.Biome biome, boolean urban) {
        for (int by = 0; by < height; by++) {
            chunk.blocks.put(new BlockPos(wx, by, wz),
                    terrainMaterial(wx, by, wz, height, biome, urban));
        }
    }

    private void addWater(Chunk chunk, int wx, int wz, int height) {
        for (int by = height; by < TerrainGenerator.WATER_LEVEL; by++) {
            chunk.blocks.put(new BlockPos(wx, by, wz), BlockMaterial.WATER);
        }
    }

    private void addVegetation(Chunk chunk, int wx, int wz, int height,
                               TerrainGenerator.Biome biome, Random rng) {
        if (height <= TerrainGenerator.WATER_LEVEL) return;
        double treeChance = biome == TerrainGenerator.Biome.FOREST ? 0.12
                : biome == TerrainGenerator.Biome.PLAINS ? 0.03 : 0.0;
        if (rng.nextDouble() < treeChance) {
            addTree(chunk, wx + 0.5, height, wz + 0.5, rng);
        } else if (rng.nextDouble() < 0.03) {
            // small flower
            chunk.opaqueBlocks.add(new Box(wx + 0.4, height, wz + 0.4, 0.14, 0.3, 0.14,
                    0.9f, 0.3f + rng.nextFloat() * 0.5f, 0.3f));
        }
    }

    private void addTree(Chunk chunk, double x, int groundTop, double z, Random rng) {
        int trunk = 3 + rng.nextInt(2);
        for (int i = 0; i < trunk; i++) {
            chunk.opaqueBlocks.add(new Box(x - 0.15, groundTop + i, z - 0.15, 0.3, 1, 0.3,
                    0.42f, 0.28f, 0.15f));
        }
        double leafY = groundTop + trunk - 1;
        chunk.opaqueBlocks.add(new Box(x - 1.1, leafY, z - 1.1, 2.2, 1.4, 2.2, 0.16f, 0.44f, 0.18f));
        chunk.opaqueBlocks.add(new Box(x - 0.7, leafY + 1.2, z - 0.7, 1.4, 1.0, 1.4, 0.20f, 0.50f, 0.22f));
    }

    private void maybeSpawnFish(Chunk chunk, int wx, int wz, int height, Random rng) {
        if (height < TerrainGenerator.WATER_LEVEL - 1 && rng.nextDouble() < 0.06) {
            float[] cols = {0.95f, 0.55f, 0.2f};
            chunk.fish.add(new Fish(wx + 0.5, wz + 0.5, TerrainGenerator.WATER_LEVEL,
                    cols[0], cols[1], cols[2], mix(wx, wz, terrain.seed())));
        }
    }

    // ------------------------------------------------------------------
    // Countryside hamlets
    // ------------------------------------------------------------------

    /** Roughly one countryside chunk in eighteen hosts a small farmstead. */
    boolean hasHamlet(int cx, int cz) {
        if (isUrbanChunk(cx, cz)) return false;
        if (Math.floorMod(mix(cx, cz, terrain.seed() ^ 0x5DEECE66DL), 18) != 0) return false;
        int centerX = cx * Chunk.SIZE + Chunk.SIZE / 2;
        int centerZ = cz * Chunk.SIZE + Chunk.SIZE / 2;
        if (layout.isRoad(centerX, centerZ)) return false;
        return terrain.heightAt(centerX, centerZ) > TerrainGenerator.WATER_LEVEL + 1;
    }

    private boolean inHamletYard(int gx, int gz) {
        int lx = Math.floorMod(gx, Chunk.SIZE);
        int lz = Math.floorMod(gz, Chunk.SIZE);
        return lx >= HAMLET_YARD_MIN && lx <= HAMLET_YARD_MAX
                && lz >= HAMLET_YARD_MIN && lz <= HAMLET_YARD_MAX;
    }

    private int hamletGroundHeight(int cx, int cz) {
        int centerX = cx * Chunk.SIZE + Chunk.SIZE / 2;
        int centerZ = cz * Chunk.SIZE + Chunk.SIZE / 2;
        return Math.max(TerrainGenerator.WATER_LEVEL + 2, terrain.heightAt(centerX, centerZ));
    }

    /**
     * Structure cell of a farmstead above its flattened yard: a brick cottage
     * with door, windows and a wooden roof, crop rows and a fenced yard border
     * with a gate. Used identically during generation and coordinate queries so
     * collision always matches the rendered world.
     */
    private BlockMaterial hamletBlockAt(int x, int y, int z, int cx, int cz) {
        if (!inHamletYard(x, z)) return null;
        int ground = hamletGroundHeight(cx, cz);
        int relY = y - ground;
        if (relY < 0 || relY > 3) return null;
        int lx = Math.floorMod(x, Chunk.SIZE);
        int lz = Math.floorMod(z, Chunk.SIZE);

        boolean cottageWall = (lx == 5 || lx == 9 || lz == 6 || lz == 10)
                && lx >= 5 && lx <= 9 && lz >= 6 && lz <= 10;
        if (cottageWall && relY <= 2) {
            if (lx == 7 && lz == 6 && relY <= 1) return null; // door
            if (relY == 1 && (lx == 5 || lx == 9) && lz >= 7 && lz <= 9) return BlockMaterial.GLASS;
            return BlockMaterial.BRICK;
        }
        if (relY == 3 && lx >= 4 && lx <= 10 && lz >= 5 && lz <= 11) {
            return BlockMaterial.WOOD; // roof with a one-block overhang
        }
        if (relY == 0) {
            boolean border = lx == HAMLET_YARD_MIN || lx == HAMLET_YARD_MAX
                    || lz == HAMLET_YARD_MIN || lz == HAMLET_YARD_MAX;
            if (border) {
                if (lz == HAMLET_YARD_MIN && (lx == 7 || lx == 8)) return null; // gate
                return BlockMaterial.WOOD; // fence
            }
            if (lz == 4 && lx % 2 == 0) return BlockMaterial.GRASS; // crop rows
        }
        return null;
    }

    private void buildHamlet(Chunk chunk, int cx, int cz, Random rng) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int ground = hamletGroundHeight(cx, cz);
        for (int lx = HAMLET_YARD_MIN; lx <= HAMLET_YARD_MAX; lx++) {
            for (int lz = HAMLET_YARD_MIN; lz <= HAMLET_YARD_MAX; lz++) {
                for (int y = ground; y <= ground + 3; y++) {
                    BlockMaterial material = hamletBlockAt(baseX + lx, y, baseZ + lz, cx, cz);
                    if (material != null) {
                        chunk.blocks.put(new BlockPos(baseX + lx, y, baseZ + lz), material);
                    }
                }
            }
        }
        chunk.villagers.add(new Villager(baseX + 7.5, baseZ + 4.5,
                randColor(rng), randColor(rng), randColor(rng),
                mix(baseX, baseZ, terrain.seed())));
    }

    // ------------------------------------------------------------------
    // City buildings
    // ------------------------------------------------------------------

    public DistrictType districtType(int cx, int cz, int height) {
        if (layout.isCityCenter(cx, cz)) {
            return DistrictType.LANDMARK;
        }
        if (height <= TerrainGenerator.WATER_LEVEL + 2) {
            return DistrictType.BEACH;
        }
        long hash = mix(cx, cz, terrain.seed() ^ 0x9E3779B97F4A7C15L);
        int mod = Math.floorMod(hash, 5);
        return switch (mod) {
            case 0 -> DistrictType.GARDEN_FARM;
            case 1 -> DistrictType.COURTYARD;
            case 2 -> DistrictType.MALL_HOSPITAL;
            case 3 -> DistrictType.LANDMARK;
            default -> DistrictType.RESIDENTIAL;
        };
    }

    private void buildCity(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        DistrictType district = districtType(cx, cz, plaza);
        switch (district) {
            case BEACH -> buildBeachDistrict(chunk, cx, cz, plaza, rng);
            case GARDEN_FARM -> buildGardenFarmDistrict(chunk, cx, cz, plaza, rng);
            case COURTYARD -> buildCourtyardDistrict(chunk, cx, cz, plaza, rng);
            case MALL_HOSPITAL -> buildMallHospitalDistrict(chunk, cx, cz, plaza, rng);
            case LANDMARK -> buildLandmark(chunk, cx, cz, plaza, rng);
            case RESIDENTIAL -> buildResidentialDistrict(chunk, cx, cz, plaza, rng);
        }
    }

    private void buildBeachDistrict(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                chunk.blocks.put(new BlockPos(baseX + lx, plaza, baseZ + lz), BlockMaterial.SAND);
            }
        }
        // Beach lifeguard tower with ladders
        int tx = baseX + 3, tz = baseZ + 3;
        for (int y = plaza + 1; y <= plaza + 5; y++) {
            chunk.blocks.put(new BlockPos(tx, y, tz), BlockMaterial.LADDER);
        }
        for (int x = tx - 1; x <= tx + 1; x++) {
            for (int z = tz - 1; z <= tz + 1; z++) {
                chunk.blocks.put(new BlockPos(x, plaza + 6, z), BlockMaterial.WOOD);
                chunk.blocks.put(new BlockPos(x, plaza + 7, z), BlockMaterial.GLASS);
                chunk.blocks.put(new BlockPos(x, plaza + 8, z), BlockMaterial.WOOD);
            }
        }
        // Sun umbrellas
        int ux = baseX + 10, uz = baseZ + 10;
        chunk.blocks.put(new BlockPos(ux, plaza + 1, uz), BlockMaterial.WOOD);
        chunk.blocks.put(new BlockPos(ux, plaza + 2, uz), BlockMaterial.WOOD);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunk.blocks.put(new BlockPos(ux + dx, plaza + 3, uz + dz), BlockMaterial.BRICK);
            }
        }
        // Beachgoers
        chunk.villagers.add(new Villager(baseX + 11.5, baseZ + 8.5,
                randColor(rng), randColor(rng), randColor(rng),
                mix(baseX, baseZ, terrain.seed())));
    }

    private void buildGardenFarmDistrict(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int[][] centers = { {3, 3}, {12, 3}, {3, 12}, {12, 12} };
        for (int[] center : centers) {
            int cxPlot = baseX + center[0];
            int czPlot = baseZ + center[1];
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean border = (dx == -2 || dx == 2 || dz == -2 || dz == 2);
                    if (border) {
                        chunk.blocks.put(new BlockPos(cxPlot + dx, plaza + 1, czPlot + dz), BlockMaterial.WOOD);
                    } else {
                        BlockMaterial crop = (dx % 2 == 0) ? BlockMaterial.GRASS : BlockMaterial.DIRT;
                        chunk.blocks.put(new BlockPos(cxPlot + dx, plaza + 1, czPlot + dz), crop);
                    }
                }
            }
        }
        // Cottage
        int tx = baseX + 7, tz = baseZ + 7;
        for (int y = plaza + 1; y <= plaza + 4; y++) {
            for (int x = tx - 1; x <= tx + 1; x++) {
                for (int z = tz - 1; z <= tz + 1; z++) {
                    boolean outer = (x == tx - 1 || x == tx + 1 || z == tz - 1 || z == tz + 1);
                    if (outer) {
                        if (x == tx && z == tz - 1 && y <= plaza + 2) continue; // door
                        chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.BRICK);
                    } else {
                        chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.LADDER);
                    }
                }
            }
        }
        for (int x = tx - 2; x <= tx + 2; x++) {
            for (int z = tz - 2; z <= tz + 2; z++) {
                chunk.blocks.put(new BlockPos(x, plaza + 5, z), BlockMaterial.WOOD);
            }
        }
        // Farmer
        chunk.villagers.add(new Villager(tx + 0.5, tz - 2.5,
                randColor(rng), randColor(rng), randColor(rng),
                mix(baseX, baseZ, terrain.seed())));
    }

    private void buildCourtyardDistrict(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int fx = baseX + 8;
        int fz = baseZ + 8;
        for (int x = fx - 3; x <= fx + 2; x++) {
            for (int z = fz - 3; z <= fz + 2; z++) {
                boolean border = (x == fx - 3 || x == fx + 2 || z == fz - 3 || z == fz + 2);
                if (border) {
                    chunk.blocks.put(new BlockPos(x, plaza + 1, z), BlockMaterial.CONCRETE);
                } else {
                    chunk.blocks.put(new BlockPos(x, plaza + 1, z), BlockMaterial.WATER);
                }
            }
        }
        chunk.blocks.put(new BlockPos(fx - 1, plaza + 2, fz - 1), BlockMaterial.CONCRETE);
        chunk.blocks.put(new BlockPos(fx - 1, plaza + 3, fz - 1), BlockMaterial.WATER);

        for (int lx = 1; lx < Chunk.SIZE - 1; lx++) {
            chunk.blocks.put(new BlockPos(baseX + lx, plaza + 1, baseZ + 1), BlockMaterial.GRASS);
            chunk.blocks.put(new BlockPos(baseX + lx, plaza + 1, baseZ + 14), BlockMaterial.GRASS);
        }
        chunk.blocks.put(new BlockPos(baseX + 3, plaza + 1, baseZ + 4), BlockMaterial.WOOD);
        chunk.blocks.put(new BlockPos(baseX + 12, plaza + 1, baseZ + 4), BlockMaterial.WOOD);

        chunk.villagers.add(new Villager(baseX + 4.5, baseZ + 4.5,
                randColor(rng), randColor(rng), randColor(rng),
                mix(baseX, baseZ, terrain.seed())));
    }

    private void buildMallHospitalDistrict(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int leftX = baseX + 2, rightX = baseX + 13;
        int backZ = baseZ + 2, frontZ = baseZ + 13;
        int height = 18;

        for (int y = plaza; y < plaza + height; y++) {
            for (int x = leftX; x <= rightX; x++) {
                for (int z = backZ; z <= frontZ; z++) {
                    int relX = x - leftX;
                    int relZ = z - backZ;
                    int relY = y - plaza;

                    boolean isOuter = (relX == 0 || relX == 11 || relZ == 0 || relZ == 11);
                    if (isOuter) {
                        if (relZ == 0) {
                            if (relX == 0 || relX == 11) {
                                chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.STEEL);
                            } else if (relX == 5 || relX == 6) {
                                if (relY <= 2) continue; // lobby door
                                chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.GLASS);
                            } else {
                                chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.GLASS);
                            }
                            continue;
                        }

                        if (relY > 0 && relY % 6 == 3) {
                            chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.GLASS);
                        } else {
                            chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.CONCRETE);
                        }
                    } else {
                        if (relY > 0 && relY % 6 == 0) {
                            boolean inAtrium = (relX >= 4 && relX <= 7 && relZ >= 4 && relZ <= 7);
                            if (inAtrium) {
                                // empty
                            } else if (relX == 2 && relZ == 2) {
                                // hole
                            } else {
                                chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.CONCRETE);
                            }
                        }
                        if (relY > 0 && relY % 6 != 0) {
                            boolean inAtrium = (relX >= 4 && relX <= 7 && relZ >= 4 && relZ <= 7);
                            if (!inAtrium) {
                                if (relX == 6 || relZ == 6) {
                                    if (relX == 6 && relZ == 3) continue;
                                    if (relX == 6 && relZ == 9) continue;
                                    if (relZ == 6 && relX == 3) continue;
                                    if (relZ == 6 && relX == 9) continue;
                                    chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.BRICK);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (int y = plaza; y < plaza + height - 1; y++) {
            chunk.blocks.put(new BlockPos(leftX + 2, y, backZ + 2), BlockMaterial.LADDER);
        }

        for (int x = leftX + 4; x <= leftX + 7; x++) {
            for (int z = backZ + 4; z <= backZ + 7; z++) {
                chunk.blocks.put(new BlockPos(x, plaza + height, z), BlockMaterial.GLASS);
            }
        }

        chunk.villagers.add(new Villager(leftX + 3.5, backZ + 4.5,
                randColor(rng), randColor(rng), randColor(rng),
                mix(baseX, baseZ, terrain.seed())));
    }

    private void buildResidentialDistrict(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        for (int index = 0; index < 4; index++) {
            Building building = buildingFor(cx, cz, index);
            long hash = mix(cx * 31 + index, cz * 31 - index, terrain.seed());
            int buildingHeight = building.height() + (int) Math.floorMod(hash, 3) * FLOOR_HEIGHT;

            for (int x = building.x(); x < building.x() + building.width(); x++) {
                for (int z = building.z(); z < building.z() + building.depth(); z++) {
                    for (int y = plaza; y <= plaza + buildingHeight; y++) {
                        BlockMaterial material = buildingCellMaterial(building, plaza, x, y, z);
                        if (y - plaza > buildingHeight) continue;
                        if (material != null) {
                            chunk.blocks.put(new BlockPos(x, y, z), material);
                        }
                    }
                }
            }

            double residentX = building.x() + building.width() / 2.0;
            double residentZ = building.z() - 0.5;
            chunk.villagers.add(new Villager(residentX, residentZ,
                    randColor(rng), randColor(rng), randColor(rng),
                    mix(building.x(), building.z(), terrain.seed())));
        }
    }

    private float randColor(Random rng) {
        return 0.35f + rng.nextFloat() * 0.6f;
    }

    private void addCrowd(Chunk chunk, int baseX, int baseZ, int plaza) {
        int[][] roadCells = {
                {7, 2}, {8, 4}, {7, 7}, {8, 11}, {2, 7}, {13, 8}
        };
        for (int index = 0; index < roadCells.length; index++) {
            int wx = baseX + roadCells[index][0];
            int wz = baseZ + roadCells[index][1];
            NpcRole role = index == roadCells.length - 1
                    ? NpcRole.POLICE : NpcRole.CIVILIAN;
            long npcSeed = mix(wx, wz, terrain.seed() ^ (index * 0x9E3779B97F4A7C15L));
            Npc npc = new Npc(npcId(chunk.cx, chunk.cz, index), role,
                    wx + 0.5, wz + 0.5, npcSeed);
            npc.y = plaza;
            chunk.npcs.add(npc);
        }
    }

    private UUID npcId(int cx, int cz, int index) {
        long most = mix(cx, cz,
                terrain.seed() ^ ((index + 1L) * 0xD6E8FEB86659FD93L));
        long least = mix(cz, index,
                terrain.seed() ^ ((long) cx * 0xA5A3564E27F8862BL));
        return new UUID(most, least);
    }

    private BlockMaterial terrainMaterial(int wx, int y, int wz, int height,
                                          TerrainGenerator.Biome biome, boolean urban) {
        if (y == height - 1) {
            if (urban) return isRoadCell(wx, wz) ? BlockMaterial.ASPHALT : BlockMaterial.CONCRETE;
            if (layout.isRoad(wx, wz)) return BlockMaterial.ASPHALT;
            return height <= TerrainGenerator.WATER_LEVEL + 1
                    || biome == TerrainGenerator.Biome.OCEAN
                    ? BlockMaterial.SAND : BlockMaterial.GRASS;
        }
        if (y >= Math.max(0, height - 3)) return BlockMaterial.DIRT;
        return BlockMaterial.STONE;
    }

    private boolean isRoadCell(int wx, int wz) {
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);
        return lx >= ROAD_MIN && lx <= ROAD_MAX || lz >= ROAD_MIN && lz <= ROAD_MAX;
    }

    private BlockMaterial cityBlockAt(BlockPos pos, int cx, int cz, int plaza) {
        DistrictType district = districtType(cx, cz, plaza);
        return switch (district) {
            case BEACH -> beachBlockAt(pos, cx, cz, plaza);
            case GARDEN_FARM -> farmBlockAt(pos, cx, cz, plaza);
            case COURTYARD -> courtyardBlockAt(pos, cx, cz, plaza);
            case MALL_HOSPITAL -> mallBlockAt(pos, cx, cz, plaza);
            case LANDMARK -> landmarkBlockAt(pos, cx, cz, plaza);
            case RESIDENTIAL -> {
                for (int index = 0; index < 4; index++) {
                    Building building = buildingFor(cx, cz, index);
                    long hash = mix(cx * 31 + index, cz * 31 - index, terrain.seed());
                    int buildingHeight = building.height() + (int) Math.floorMod(hash, 3) * FLOOR_HEIGHT;
                    if (pos.y() - plaza <= buildingHeight) {
                        BlockMaterial material = buildingCellMaterial(building, plaza, pos.x(), pos.y(), pos.z());
                        if (material != null) yield material;
                    }
                }
                yield null;
            }
        };
    }

    private BlockMaterial beachBlockAt(BlockPos pos, int cx, int cz, int plaza) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        if (pos.y() == plaza) return BlockMaterial.SAND;
        int tx = baseX + 3, tz = baseZ + 3;
        if (pos.x() == tx && pos.z() == tz && pos.y() >= plaza + 1 && pos.y() <= plaza + 5) {
            return BlockMaterial.LADDER;
        }
        if (pos.x() >= tx - 1 && pos.x() <= tx + 1 && pos.z() >= tz - 1 && pos.z() <= tz + 1) {
            if (pos.y() == plaza + 6 || pos.y() == plaza + 8) return BlockMaterial.WOOD;
            if (pos.y() == plaza + 7) return BlockMaterial.GLASS;
        }
        int ux = baseX + 10, uz = baseZ + 10;
        if (pos.x() == ux && pos.z() == uz && (pos.y() == plaza + 1 || pos.y() == plaza + 2)) {
            return BlockMaterial.WOOD;
        }
        if (pos.x() >= ux - 1 && pos.x() <= ux + 1 && pos.z() >= uz - 1 && pos.z() <= uz + 1 && pos.y() == plaza + 3) {
            return BlockMaterial.BRICK;
        }
        return null;
    }

    private BlockMaterial farmBlockAt(BlockPos pos, int cx, int cz, int plaza) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int[][] centers = { {3, 3}, {12, 3}, {3, 12}, {12, 12} };
        for (int[] center : centers) {
            int cxPlot = baseX + center[0];
            int czPlot = baseZ + center[1];
            int dx = pos.x() - cxPlot;
            int dz = pos.z() - czPlot;
            if (pos.y() == plaza + 1 && dx >= -2 && dx <= 2 && dz >= -2 && dz <= 2) {
                boolean border = (dx == -2 || dx == 2 || dz == -2 || dz == 2);
                if (border) return BlockMaterial.WOOD;
                return (dx % 2 == 0) ? BlockMaterial.GRASS : BlockMaterial.DIRT;
            }
        }
        int tx = baseX + 7, tz = baseZ + 7;
        int relX = pos.x() - tx;
        int relZ = pos.z() - tz;
        int relY = pos.y() - plaza;
        if (relX >= -1 && relX <= 1 && relZ >= -1 && relZ <= 1) {
            if (relY >= 1 && relY <= 4) {
                boolean outer = (relX == -1 || relX == 1 || relZ == -1 || relZ == 1);
                if (outer) {
                    if (relX == 0 && relZ == -1 && relY <= 2) return null;
                    return BlockMaterial.BRICK;
                } else {
                    return BlockMaterial.LADDER;
                }
            }
        }
        if (relY == 5 && relX >= -2 && relX <= 2 && relZ >= -2 && relZ <= 2) {
            return BlockMaterial.WOOD;
        }
        return null;
    }

    private BlockMaterial courtyardBlockAt(BlockPos pos, int cx, int cz, int plaza) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int fx = baseX + 8;
        int fz = baseZ + 8;
        int dx = pos.x() - fx;
        int dz = pos.z() - fz;
        if (pos.y() == plaza + 1 && dx >= -3 && dx <= 2 && dz >= -3 && dz <= 2) {
            boolean border = (dx == -3 || dx == 2 || dz == -3 || dz == 2);
            return border ? BlockMaterial.CONCRETE : BlockMaterial.WATER;
        }
        if (dx == -1 && dz == -1) {
            if (pos.y() == plaza + 2) return BlockMaterial.CONCRETE;
            if (pos.y() == plaza + 3) return BlockMaterial.WATER;
        }
        int lz = Math.floorMod(pos.z(), Chunk.SIZE);
        int lx = Math.floorMod(pos.x(), Chunk.SIZE);
        if (pos.y() == plaza + 1) {
            if (lx >= 1 && lx < Chunk.SIZE - 1 && (lz == 1 || lz == 14)) {
                return BlockMaterial.GRASS;
            }
            if ((lx == 3 || lx == 12) && lz == 4) return BlockMaterial.WOOD;
        }
        return null;
    }

    private BlockMaterial mallBlockAt(BlockPos pos, int cx, int cz, int plaza) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int leftX = baseX + 2, rightX = baseX + 13;
        int backZ = baseZ + 2, frontZ = baseZ + 13;
        int height = 18;

        if (pos.x() < leftX || pos.x() > rightX || pos.z() < backZ || pos.z() > frontZ || pos.y() < plaza || pos.y() > plaza + height) {
            return null;
        }

        int relX = pos.x() - leftX;
        int relZ = pos.z() - backZ;
        int relY = pos.y() - plaza;

        boolean isOuter = (relX == 0 || relX == 11 || relZ == 0 || relZ == 11);
        if (isOuter) {
            if (relZ == 0) {
                if (relX == 0 || relX == 11) return BlockMaterial.STEEL;
                if (relX == 5 || relX == 6) {
                    if (relY <= 2) return null;
                    return BlockMaterial.GLASS;
                }
                return BlockMaterial.GLASS;
            }
            if (relY > 0 && relY % 6 == 3) return BlockMaterial.GLASS;
            return BlockMaterial.CONCRETE;
        } else {
            if (relY == height && relX >= 4 && relX <= 7 && relZ >= 4 && relZ <= 7) {
                return BlockMaterial.GLASS;
            }
            if (relY > 0 && relY % 6 == 0) {
                boolean inAtrium = (relX >= 4 && relX <= 7 && relZ >= 4 && relZ <= 7);
                if (inAtrium) return null;
                if (relX == 2 && relZ == 2) return null;
                return BlockMaterial.CONCRETE;
            }
            if (relX == 2 && relZ == 2 && relY >= 0 && relY < height) {
                return BlockMaterial.LADDER;
            }
            if (relY > 0 && relY % 6 != 0) {
                boolean inAtrium = (relX >= 4 && relX <= 7 && relZ >= 4 && relZ <= 7);
                if (!inAtrium) {
                    if (relX == 6 || relZ == 6) {
                        if (relX == 6 && relZ == 3) return null;
                        if (relX == 6 && relZ == 9) return null;
                        if (relZ == 6 && relX == 3) return null;
                        if (relZ == 6 && relX == 9) return null;
                        return BlockMaterial.BRICK;
                    }
                }
            }
        }
        return null;
    }

    private BlockMaterial landmarkBlockAt(BlockPos pos, int cx, int cz, int plaza) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        long hash = mix(cx, cz, terrain.seed() ^ 0xDEADBEEFL);
        int type = Math.floorMod(hash, 2);

        if (type == 0) {
            int t1LeftX = baseX + 2, t1RightX = baseX + 5;
            int t1BackZ = baseZ + 6, t1FrontZ = baseZ + 9;
            int t2LeftX = baseX + 10, t2RightX = baseX + 13;
            int t2BackZ = baseZ + 6, t2FrontZ = baseZ + 9;
            int height = 32;
            int bridgeY = plaza + 20;

            if (pos.y() < plaza || pos.y() > plaza + height) return null;

            boolean inT1 = (pos.x() >= t1LeftX && pos.x() <= t1RightX && pos.z() >= t1BackZ && pos.z() <= t1FrontZ);
            boolean inT2 = (pos.x() >= t2LeftX && pos.x() <= t2RightX && pos.z() >= t2BackZ && pos.z() <= t2FrontZ);

            if (inT1 || inT2) {
                int relX = inT1 ? (pos.x() - t1LeftX) : (pos.x() - t2LeftX);
                int relZ = pos.z() - t1BackZ;
                int relY = pos.y() - plaza;

                if (relX == 1 && relZ == 1 && relY < height - 1) return BlockMaterial.LADDER;

                boolean isOuter = (relX == 0 || relX == 3 || relZ == 0 || relZ == 3);
                if (isOuter) {
                    if (relY <= 1 && relZ == 2 && relX == 0 && inT1) return null;
                    if (relY <= 1 && relZ == 2 && relX == 3 && inT2) return null;

                    if ((relY == bridgeY - plaza || relY == bridgeY - plaza + 1) && relZ == 2) {
                        if (relX == 3 && inT1) return null;
                        if (relX == 0 && inT2) return null;
                    }

                    if (relY > 0 && relY % 4 == 2 && relY != height - 1) return BlockMaterial.GLASS;
                    return BlockMaterial.STEEL;
                } else {
                    if (relY > 0 && relY % 4 == 0) return BlockMaterial.CONCRETE;
                }
            }

            if (pos.x() > t1RightX && pos.x() < t2LeftX && pos.z() >= t1BackZ + 1 && pos.z() <= t1FrontZ - 1) {
                if (pos.y() == bridgeY) return BlockMaterial.GLASS;
                if (pos.y() == bridgeY + 1 && (pos.z() == t1BackZ + 1 || pos.z() == t1FrontZ - 1)) return BlockMaterial.STEEL;
            }
        } else {
            int pLeftX = baseX + 1, pRightX = baseX + 14;
            int pBackZ = baseZ + 1, pFrontZ = baseZ + 14;
            int height = 15;

            if (pos.x() < pLeftX || pos.x() > pRightX || pos.z() < pBackZ || pos.z() > pFrontZ || pos.y() < plaza || pos.y() > plaza + height) {
                return null;
            }

            int relX = pos.x() - pLeftX;
            int relZ = pos.z() - pBackZ;
            int relY = pos.y() - plaza;

            if (relX == 6 && relZ == 6 && relY < height - 1) return BlockMaterial.LADDER;

            boolean isOuter = (relX == 0 || relX == 13 || relZ == 0 || relZ == 13);
            if (isOuter) {
                if (relZ == 0) {
                    if (relX == 0 || relX == 3 || relX == 6 || relX == 7 || relX == 10 || relX == 13) return BlockMaterial.GOLD;
                    return null;
                }
                if (relZ == 13 && relX == 6 && relY <= 1) return null;
                if (relY > 0 && relY % 4 == 2) return BlockMaterial.GLASS;
                return BlockMaterial.BRICK;
            } else {
                if (relY > 0 && relY % 4 == 0) return BlockMaterial.CONCRETE;
            }
        }
        return null;
    }

    private Building buildingFor(int cx, int cz, int index) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        int lotX = index & 1;
        int lotZ = index >>> 1;
        long hash = mix(cx * 31 + index, cz * 31 - index, terrain.seed());
        int width = 3 + (int) Math.floorMod(hash, 2L);
        int depth = 3 + (int) Math.floorMod(hash >>> 7, 2L);
        int height = 6 + (int) Math.floorMod(hash >>> 13, 3L) * FLOOR_HEIGHT;
        int materialIndex = Math.floorMod(index
                + (int) Math.floorMod(mix(cx, cz, terrain.seed()), 3L), 3);
        BlockMaterial material = switch (materialIndex) {
            case 0 -> BlockMaterial.BRICK;
            case 1 -> BlockMaterial.CONCRETE;
            default -> BlockMaterial.STEEL;
        };
        return new Building(baseX + (lotX == 0 ? 1 : 11),
                baseZ + (lotZ == 0 ? 1 : 11), width, depth, height, material);
    }

    private BlockMaterial buildingCellMaterial(Building building, int plaza,
                                               int x, int y, int z) {
        int maxX = building.x() + building.width() - 1;
        int maxZ = building.z() + building.depth() - 1;
        if (x < building.x() || x > maxX || z < building.z() || z > maxZ
                || y < plaza || y > plaza + building.height()) {
            return null;
        }

        int relativeY = y - plaza;
        if (relativeY == building.height()) return building.material();
        if (relativeY > 0 && relativeY % FLOOR_HEIGHT == 0) return building.material();

        boolean boundary = x == building.x() || x == maxX
                || z == building.z() || z == maxZ;
        if (!boundary) return null;

        int doorX = building.x() + building.width() / 2;
        if (z == building.z() && x == doorX && relativeY <= 1) return null;

        boolean windowHeight = relativeY > 0 && relativeY % FLOOR_HEIGHT == 1;
        boolean windowOnZWall = (z == building.z() || z == maxZ)
                && x > building.x() && x < maxX;
        boolean windowOnXWall = (x == building.x() || x == maxX)
                && z > building.z() && z < maxZ;
        if (windowHeight && (windowOnZWall || windowOnXWall)) return BlockMaterial.GLASS;
        return building.material();
    }

    public boolean isLandmarkChunk(int cx, int cz) {
        int plaza = cityPlazaHeight(cx, cz);
        return districtType(cx, cz, plaza) == DistrictType.LANDMARK;
    }

    private void buildLandmark(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;
        long hash = mix(cx, cz, terrain.seed() ^ 0xDEADBEEFL);
        int type = Math.floorMod(hash, 2); // 0 = Twin Towers, 1 = Palace

        if (type == 0) {
            // --- TWIN TOWERS ---
            int t1LeftX = baseX + 2;
            int t1RightX = baseX + 5;
            int t1BackZ = baseZ + 6;
            int t1FrontZ = baseZ + 9;

            int t2LeftX = baseX + 10;
            int t2RightX = baseX + 13;
            int t2BackZ = baseZ + 6;
            int t2FrontZ = baseZ + 9;

            int height = 32;
            int bridgeY = plaza + 20;

            for (int y = plaza; y < plaza + height; y++) {
                for (int x = baseX; x < baseX + Chunk.SIZE; x++) {
                    for (int z = baseZ; z < baseZ + Chunk.SIZE; z++) {
                        boolean inT1 = (x >= t1LeftX && x <= t1RightX && z >= t1BackZ && z <= t1FrontZ);
                        boolean inT2 = (x >= t2LeftX && x <= t2RightX && z >= t2BackZ && z <= t2FrontZ);

                        if (inT1 || inT2) {
                            int relX = inT1 ? (x - t1LeftX) : (x - t2LeftX);
                            int relZ = z - t1BackZ;
                            int relY = y - plaza;

                            boolean isOuter = (relX == 0 || relX == 3 || relZ == 0 || relZ == 3);

                            if (isOuter) {
                                // Door opening
                                if (relY <= 1 && relZ == 2 && relX == 0 && inT1) continue;
                                if (relY <= 1 && relZ == 2 && relX == 3 && inT2) continue;

                                // Bridge doors opening
                                if ((relY == bridgeY - plaza || relY == bridgeY - plaza + 1) && relZ == 2) {
                                    if (relX == 3 && inT1) continue;
                                    if (relX == 0 && inT2) continue;
                                }

                                // Windows
                                if (relY > 0 && relY % 4 == 2 && relY != height - 1) {
                                    chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.GLASS);
                                } else {
                                    chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.STEEL);
                                }
                            } else {
                                // Inner floors
                                if (relY > 0 && relY % 4 == 0) {
                                    if (relX == 1 && relZ == 1) {
                                        // ladder shaft hole
                                    } else {
                                        chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.CONCRETE);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Skybridge
            for (int x = t1RightX + 1; x < t2LeftX; x++) {
                for (int z = t1BackZ + 1; z < t1FrontZ; z++) {
                    chunk.blocks.put(new BlockPos(x, bridgeY, z), BlockMaterial.GLASS);
                    if (z == t1BackZ + 1 || z == t1FrontZ - 1) {
                        chunk.blocks.put(new BlockPos(x, bridgeY + 1, z), BlockMaterial.STEEL);
                    }
                }
            }

            // Ladders
            for (int y = plaza; y < plaza + height - 1; y++) {
                chunk.blocks.put(new BlockPos(t1LeftX + 1, y, t1BackZ + 1), BlockMaterial.LADDER);
                chunk.blocks.put(new BlockPos(t2LeftX + 1, y, t2BackZ + 1), BlockMaterial.LADDER);
            }
        } else {
            // --- PALACE ---
            int pLeftX = baseX + 1;
            int pRightX = baseX + 14;
            int pBackZ = baseZ + 1;
            int pFrontZ = baseZ + 14;
            int height = 15;

            for (int y = plaza; y < plaza + height; y++) {
                for (int x = pLeftX; x <= pRightX; x++) {
                    for (int z = pBackZ; z <= pFrontZ; z++) {
                        int relX = x - pLeftX;
                        int relZ = z - pBackZ;
                        int relY = y - plaza;

                        boolean isOuter = (relX == 0 || relX == 13 || relZ == 0 || relZ == 13);
                        if (isOuter) {
                            if (relZ == 0) {
                                if (relX == 0 || relX == 3 || relX == 6 || relX == 7 || relX == 10 || relX == 13) {
                                    chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.GOLD);
                                }
                                continue;
                            }

                            if (relZ == 13 && relX == 6 && relY <= 1) continue;

                            if (relY > 0 && relY % 4 == 2) {
                                chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.GLASS);
                            } else {
                                chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.BRICK);
                            }
                        } else {
                            if (relY > 0 && relY % 4 == 0) {
                                if (relX == 6 && relZ == 6) {
                                    // ladder shaft hole
                                } else {
                                    chunk.blocks.put(new BlockPos(x, y, z), BlockMaterial.CONCRETE);
                                }
                            }
                        }
                    }
                }
            }

            // Ladders
            for (int y = plaza; y < plaza + height - 1; y++) {
                chunk.blocks.put(new BlockPos(pLeftX + 6, y, pBackZ + 6), BlockMaterial.LADDER);
            }
        }
    }

    private int generatedColumnUpperBound(int gx, int gz) {
        int height = columnHeight(gx, gz);
        int upperBound = height - 1;
        int cx = Math.floorDiv(gx, Chunk.SIZE);
        int cz = Math.floorDiv(gz, Chunk.SIZE);
        if (isUrbanChunk(cx, cz)) {
            int plaza = cityPlazaHeight(cx, cz);
            DistrictType type = districtType(cx, cz, plaza);
            int extra = (type == DistrictType.LANDMARK) ? 45 : (type == DistrictType.MALL_HOSPITAL ? 25 : MAX_BUILDING_HEIGHT);
            upperBound = Math.max(upperBound, height + extra);
        } else if (hasHamlet(cx, cz)) {
            upperBound = Math.max(upperBound, hamletGroundHeight(cx, cz) + 4);
        } else if (height < TerrainGenerator.WATER_LEVEL) {
            upperBound = TerrainGenerator.WATER_LEVEL - 1;
        }
        return upperBound;
    }

    private static BlockMaterial nonAir(BlockMaterial material) {
        return material == BlockMaterial.AIR ? null : material;
    }

    private record ChunkLoad(int cx, int cz, long distanceSquared) {
    }

    private record Building(int x, int z, int width, int depth, int height,
                            BlockMaterial material) {
    }

    private static long mix(int a, int b, long seed) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ (a * 0xC2B2AE3D27D4EB4FL)) * 0x100000001B3L;
        h = (h ^ (b * 0x165667B19E3779F9L)) * 0x100000001B3L;
        return h ^ (h >>> 29);
    }
}
