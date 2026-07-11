package com.blockshot.entity;

import java.util.Random;

/**
 * A fish that swims within a body of water, staying below the water surface and
 * turning back when it drifts too far from its spawn pool.
 */
public final class Fish {

    public double x, y, z;
    public double facing;
    public final float r, g, b;

    private final double homeX, homeZ;
    private final double waterTop;
    private double vx, vz;
    private double turnTimer;
    private final Random rng;

    public Fish(double x, double z, double waterTop, float r, float g, float b, long seed) {
        this.x = x;
        this.z = z;
        this.homeX = x;
        this.homeZ = z;
        this.waterTop = waterTop;
        this.r = r;
        this.g = g;
        this.b = b;
        this.rng = new Random(seed);
        this.y = waterTop - 0.6;
        newHeading();
    }

    private void newHeading() {
        double a = rng.nextDouble() * Math.PI * 2;
        vx = Math.cos(a);
        vz = Math.sin(a);
        turnTimer = 1.0 + rng.nextDouble() * 3.0;
    }

    public void update(double dt) {
        turnTimer -= dt;
        if (turnTimer <= 0) newHeading();
        // Steer back toward the home pool if it wanders too far.
        double dx = homeX - x, dz = homeZ - z;
        if (Math.hypot(dx, dz) > 4.0) {
            double d = Math.hypot(dx, dz);
            vx = dx / d;
            vz = dz / d;
        }
        double speed = 0.9;
        x += vx * speed * dt;
        z += vz * speed * dt;
        y = waterTop - 0.6 + Math.sin((x + z) * 0.5) * 0.15;
        facing = Math.toDegrees(Math.atan2(vx, vz));
    }
}
