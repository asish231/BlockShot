package com.blockshot.world;

/** Immutable integer position of a block in world space. */
public record BlockPos(int x, int y, int z) {

    public int chunkX() {
        return Math.floorDiv(x, Chunk.SIZE);
    }

    public int chunkZ() {
        return Math.floorDiv(z, Chunk.SIZE);
    }

    public long chunkKey() {
        return Chunk.key(chunkX(), chunkZ());
    }

    public BlockPos below() {
        return offset(0, -1, 0);
    }

    public BlockPos above() {
        return offset(0, 1, 0);
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }
}