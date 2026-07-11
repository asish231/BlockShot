package com.blockshot.entity;

import java.util.Random;

/**
 * A person who wanders around a small home area. Villagers walk between random
 * targets so the world feels alive; their vertical position follows the terrain.
 */
public final class Villager {

    public double x, z, y;
    public double facing;
    public final float r, g, b;

    private final double homeX, homeZ;
    private final double roam;
    private double targetX, targetZ;
    private double pauseTimer;
    private final Random rng;

    public Villager(double x, double z, float r, float g, float b, long seed) {
        this.x = x;
        this.z = z;
        this.homeX = x;
        this.homeZ = z;
        this.r = r;
        this.g = g;
        this.b = b;
        this.roam = 6.0;
        this.rng = new Random(seed);
        pickTarget();
    }

    private void pickTarget() {
        targetX = homeX + (rng.nextDouble() * 2 - 1) * roam;
        targetZ = homeZ + (rng.nextDouble() * 2 - 1) * roam;
        pauseTimer = 0.5 + rng.nextDouble() * 2.5;
    }

    /** Advance the villager by dt seconds; {@code surface} maps (x,z) to feet Y. */
    public void update(double dt, SurfaceProvider surface) {
        double dx = targetX - x, dz = targetZ - z;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.25) {
            pauseTimer -= dt;
            if (pauseTimer <= 0) pickTarget();
        } else {
            double speed = 1.4;
            double step = Math.min(speed * dt, dist);
            x += dx / dist * step;
            z += dz / dist * step;
            facing = Math.toDegrees(Math.atan2(dx, dz));
        }
        y = surface.surfaceY(x, z);
    }
}
