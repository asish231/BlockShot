package com.blockshot.render;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Builds immutable, OpenGL-independent vertex data for chunk blocks. */
public final class ChunkMeshBuilder {

    private static final Direction[] DIRECTIONS = {
            new Direction(0, 0, 1, 0, 0, 1, new int[][]{
                    {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}),
            new Direction(0, 0, -1, 0, 0, -1, new int[][]{
                    {1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}),
            new Direction(1, 0, 0, 1, 0, 0, new int[][]{
                    {1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}),
            new Direction(-1, 0, 0, -1, 0, 0, new int[][]{
                    {0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}),
            new Direction(0, 1, 0, 0, 1, 0, new int[][]{
                    {0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}}),
            new Direction(0, -1, 0, 0, -1, 0, new int[][]{
                    {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}})
    };

    private ChunkMeshBuilder() {
    }

    public static ChunkMeshData build(Map<BlockPos, BlockMaterial> blocks,
                                      Function<BlockPos, BlockMaterial> materialAt) {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(materialAt, "materialAt");

        List<Block> orderedBlocks = new ArrayList<>(blocks.size());
        for (Map.Entry<BlockPos, BlockMaterial> entry : blocks.entrySet()) {
            orderedBlocks.add(new Block(
                    Objects.requireNonNull(entry.getKey(), "block position"),
                    Objects.requireNonNull(entry.getValue(), "block material")));
        }
        orderedBlocks.sort((left, right) -> {
            int byX = Integer.compare(left.position().x(), right.position().x());
            if (byX != 0) return byX;
            int byY = Integer.compare(left.position().y(), right.position().y());
            if (byY != 0) return byY;
            return Integer.compare(left.position().z(), right.position().z());
        });

        FloatArrayBuilder opaque = new FloatArrayBuilder();
        FloatArrayBuilder translucent = new FloatArrayBuilder();

        for (Block block : orderedBlocks) {
            BlockPos position = block.position();
            BlockMaterial material = block.material();
            FloatArrayBuilder destination = material.opaque() ? opaque : translucent;

            for (Direction direction : DIRECTIONS) {
                BlockPos neighborPosition = position.offset(
                        direction.dx(), direction.dy(), direction.dz());
                BlockMaterial neighbor = materialAt.apply(neighborPosition);
                if (neighbor != null && neighbor.opaque() == material.opaque()) continue;
                appendFace(destination, position, direction, material);
            }
        }

        return new ChunkMeshData(opaque.toArray(), translucent.toArray());
    }

    private static void appendFace(FloatArrayBuilder destination, BlockPos position,
                                   Direction direction, BlockMaterial material) {
        for (int[] vertex : direction.vertices()) {
            destination.add((float) position.x() + vertex[0]);
            destination.add((float) position.y() + vertex[1]);
            destination.add((float) position.z() + vertex[2]);
            destination.add(direction.nx());
            destination.add(direction.ny());
            destination.add(direction.nz());
            destination.add(material.r());
            destination.add(material.g());
            destination.add(material.b());
            destination.add(material.a());
        }
    }

    private record Block(BlockPos position, BlockMaterial material) {
    }

    private record Direction(int dx, int dy, int dz, int nx, int ny, int nz, int[][] vertices) {
    }

    private static final class FloatArrayBuilder {

        private float[] values = new float[ChunkMeshData.FLOATS_PER_VERTEX
                * ChunkMeshData.VERTICES_PER_FACE];
        private int size;

        void add(float value) {
            if (size == values.length) values = Arrays.copyOf(values, Math.multiplyExact(size, 2));
            values[size++] = value;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}