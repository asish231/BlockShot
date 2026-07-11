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
 * {@link TerrainGenerator}, cities from the deterministic city lattice, and
 * collision from the same edit-aware material cells used for rendering.
 */
public final class ChunkManager implements CollisionWorld {

    private static final int CITY_SPACING = 5;      // cities sit on a lattice every N chunks
    private static final int CITY_RADIUS_CHUNKS = 1; // 3x3 chunks of urban area
    private static final int ROAD_MIN = 6;
    private static final int ROAD_MAX = 9;
    private static final int FLOOR_HEIGHT = 3;
    private static final int MAX_BUILDING_HEIGHT = 12;

    private final TerrainGenerator terrain;
    private final int renderDistance;
    private final Map<Long, Chunk> loaded = new HashMap<>();
    private final WorldEditStore edits = new WorldEditStore();

    public ChunkManager(TerrainGenerator terrain, int renderDistance) {
        this.terrain = Objects.requireNonNull(terrain, "terrain");
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
    // City lattice
    // ------------------------------------------------------------------

    private int nearestCityCenter(int chunkCoord) {
        return Math.round(chunkCoord / (float) CITY_SPACING) * CITY_SPACING;
    }

    private boolean isUrbanChunk(int cx, int cz) {
        int ccx = nearestCityCenter(cx), ccz = nearestCityCenter(cz);
        return Math.max(Math.abs(cx - ccx), Math.abs(cz - ccz)) <= CITY_RADIUS_CHUNKS;
    }

    /** Flat plaza height of the city that a chunk belongs to (above water). */
    private int cityPlazaHeight(int cx, int cz) {
        int ccx = nearestCityCenter(cx), ccz = nearestCityCenter(cz);
        int centerBlockX = ccx * Chunk.SIZE + Chunk.SIZE / 2;
        int centerBlockZ = ccz * Chunk.SIZE + Chunk.SIZE / 2;
        return Math.max(TerrainGenerator.WATER_LEVEL + 1, terrain.heightAt(centerBlockX, centerBlockZ));
    }

    /** Ground height of a world column, honouring city flattening. */
    public int columnHeight(int gx, int gz) {
        int cx = Math.floorDiv(gx, Chunk.SIZE);
        int cz = Math.floorDiv(gz, Chunk.SIZE);
        if (isUrbanChunk(cx, cz)) return cityPlazaHeight(cx, cz);
        return terrain.heightAt(gx, gz);
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
        if (!urban && pos.y() < TerrainGenerator.WATER_LEVEL) {
            return BlockMaterial.WATER;
        }
        if (urban) {
            return cityBlockAt(pos, cx, cz, height);
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
            if (pos.x() == gx && pos.z() == gz && nonAir(addition.getValue()) != null) {
                highest = Math.max(highest, pos.y());
            }
        }

        int generatedUpperBound = generatedColumnUpperBound(gx, gz);
        for (int y = generatedUpperBound; y >= 0 && y > highest; y--) {
            if (blockAt(new BlockPos(gx, y, gz)) != null) {
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
                    addVegetation(chunk, wx, wz, height, biome, rng);
                    maybeSpawnFish(chunk, wx, wz, height, rng);
                }
            }
        }

        if (urban) {
            int plaza = columnHeight(baseX + Chunk.SIZE / 2, baseZ + Chunk.SIZE / 2);
            buildCity(chunk, cx, cz, plaza, rng);
            addCrowd(chunk, baseX, baseZ, plaza);
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
    // City buildings
    // ------------------------------------------------------------------

    private void buildCity(Chunk chunk, int cx, int cz, int plaza, Random rng) {
        for (int index = 0; index < 4; index++) {
            Building building = buildingFor(cx, cz, index);
            for (int x = building.x(); x < building.x() + building.width(); x++) {
                for (int z = building.z(); z < building.z() + building.depth(); z++) {
                    for (int y = plaza; y <= plaza + building.height(); y++) {
                        BlockMaterial material = buildingCellMaterial(building, plaza, x, y, z);
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
        for (int index = 0; index < 4; index++) {
            BlockMaterial material = buildingCellMaterial(
                    buildingFor(cx, cz, index), plaza, pos.x(), pos.y(), pos.z());
            if (material != null) return material;
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

    private int generatedColumnUpperBound(int gx, int gz) {
        int height = columnHeight(gx, gz);
        int upperBound = height - 1;
        int cx = Math.floorDiv(gx, Chunk.SIZE);
        int cz = Math.floorDiv(gz, Chunk.SIZE);
        if (isUrbanChunk(cx, cz)) {
            upperBound = Math.max(upperBound, height + MAX_BUILDING_HEIGHT);
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
