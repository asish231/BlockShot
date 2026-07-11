package com.blockshot.physics;

import com.blockshot.entity.SurfaceProvider;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.Objects;

/** A single detached block with bounded gravity integration and one-shot impact damage. */
public final class FallingBlockEntity {

    private static final double GRAVITY = 22.0;
    private static final double MAX_STEP = 1.0 / 60.0;
    private static final double MAX_UPDATE = 0.25;

    private final int blockX;
    private final int blockZ;
    private final BlockMaterial material;
    private double y;
    private double verticalSpeed;
    private double impactSpeed;
    private boolean settled;
    private boolean impactPending;
    private BlockPos landingCell;

    public FallingBlockEntity(BlockPos position, BlockMaterial material) {
        Objects.requireNonNull(position, "position");
        this.material = Objects.requireNonNull(material, "material");
        if (material == BlockMaterial.AIR || material == BlockMaterial.WATER) {
            throw new IllegalArgumentException("Only solid blocks can become falling entities");
        }
        blockX = position.x();
        blockZ = position.z();
        y = position.y();
    }

    /**
     * Advances the block and returns {@code true} only on the update in which it lands.
     * Large frame deltas are capped and split so a block cannot tunnel through the surface.
     */
    public boolean update(double dt, SurfaceProvider surface) {
        if (!Double.isFinite(dt) || dt < 0) {
            throw new IllegalArgumentException("dt must be finite and not negative");
        }
        Objects.requireNonNull(surface, "surface");
        if (settled || dt == 0) return false;

        double remaining = Math.min(dt, MAX_UPDATE);
        while (remaining > 1e-12) {
            double step = Math.min(remaining, MAX_STEP);
            remaining -= step;
            verticalSpeed -= GRAVITY * step;
            double nextY = y + verticalSpeed * step;
            double surfaceY = surface.surfaceY(blockX + 0.5, blockZ + 0.5);
            if (!Double.isFinite(surfaceY)) {
                throw new IllegalArgumentException("surface height must be finite");
            }
            if (nextY <= surfaceY) {
                impactSpeed = Math.max(0, -verticalSpeed);
                y = surfaceY;
                verticalSpeed = 0;
                settled = true;
                impactPending = true;
                landingCell = new BlockPos(blockX, (int) Math.floor(surfaceY), blockZ);
                return true;
            }
            y = nextY;
        }
        return false;
    }

    public int x() {
        return blockX;
    }

    public double y() {
        return y;
    }

    public int z() {
        return blockZ;
    }

    public BlockMaterial material() {
        return material;
    }

    public double verticalSpeed() {
        return verticalSpeed;
    }

    public boolean settled() {
        return settled;
    }

    public double impactSpeed() {
        return impactSpeed;
    }

    public BlockPos landingCell() {
        return landingCell;
    }

    /** Damage scales with both velocity and material mass and is capped for stable gameplay. */
    public int impactDamage() {
        return (int) Math.min(500, Math.ceil(material.mass() * impactSpeed * 2.0));
    }

    /** Returns impact damage once, preventing repeated damage while the block is settled. */
    public int consumeImpactDamage() {
        if (!impactPending) return 0;
        impactPending = false;
        return impactDamage();
    }
}