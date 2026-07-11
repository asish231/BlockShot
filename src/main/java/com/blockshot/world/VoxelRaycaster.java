package com.blockshot.world;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Exact nearest-cell ray traversal for block interaction and weapon occlusion. */
public final class VoxelRaycaster {

    private static final double EPSILON = 1e-12;

    private VoxelRaycaster() {
    }

    public record Hit(BlockPos block, BlockPos adjacent, BlockMaterial material, double distance) {
        public Hit {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(material, "material");
            if (!Double.isFinite(distance) || distance < 0) {
                throw new IllegalArgumentException("distance must be finite and not negative");
            }
        }
    }

    public static Optional<Hit> cast(
            double originX, double originY, double originZ,
            double directionX, double directionY, double directionZ,
            double maxDistance, Function<BlockPos, BlockMaterial> materialAt) {
        requireFinite(originX, "originX");
        requireFinite(originY, "originY");
        requireFinite(originZ, "originZ");
        requireFinite(directionX, "directionX");
        requireFinite(directionY, "directionY");
        requireFinite(directionZ, "directionZ");
        if (!Double.isFinite(maxDistance) || maxDistance < 0) {
            throw new IllegalArgumentException("maxDistance must be finite and not negative");
        }
        Objects.requireNonNull(materialAt, "materialAt");

        double length = Math.sqrt(directionX * directionX
                + directionY * directionY + directionZ * directionZ);
        if (length <= EPSILON || maxDistance == 0) return Optional.empty();
        double dx = directionX / length;
        double dy = directionY / length;
        double dz = directionZ / length;

        int x = (int) Math.floor(originX);
        int y = (int) Math.floor(originY);
        int z = (int) Math.floor(originZ);
        BlockPos current = new BlockPos(x, y, z);
        BlockMaterial initial = solid(materialAt.apply(current));
        if (initial != null) return Optional.of(new Hit(current, null, initial, 0));

        int stepX = sign(dx);
        int stepY = sign(dy);
        int stepZ = sign(dz);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double nextX = boundaryDistance(originX, x, dx, stepX);
        double nextY = boundaryDistance(originY, y, dy, stepY);
        double nextZ = boundaryDistance(originZ, z, dz, stepZ);

        BlockPos previous;
        while (true) {
            previous = current;
            double distance;
            if (nextX <= nextY && nextX <= nextZ) {
                distance = nextX;
                if (distance > maxDistance) break;
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                distance = nextY;
                if (distance > maxDistance) break;
                y += stepY;
                nextY += deltaY;
            } else {
                distance = nextZ;
                if (distance > maxDistance) break;
                z += stepZ;
                nextZ += deltaZ;
            }

            current = new BlockPos(x, y, z);
            BlockMaterial material = solid(materialAt.apply(current));
            if (material != null) {
                return Optional.of(new Hit(current, previous, material, distance));
            }
        }
        return Optional.empty();
    }

    private static double boundaryDistance(double origin, int cell, double direction, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? cell + 1.0 : cell;
        double distance = (boundary - origin) / direction;
        return Math.max(0, distance);
    }

    private static int sign(double value) {
        if (value > EPSILON) return 1;
        if (value < -EPSILON) return -1;
        return 0;
    }

    private static BlockMaterial solid(BlockMaterial material) {
        return material != null && material.solid() ? material : null;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}