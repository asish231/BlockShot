package com.blockshot.entity;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/** A deterministic roaming NPC that can flee, pursue and take damage. */
public final class Npc {

    public static final int MAX_HEALTH = 100;

    private static final double ROAM_RADIUS = 7.0;
    private static final double ARRIVAL_DISTANCE = 0.2;
    private static final double FLEE_SECONDS = 8.0;

    public final UUID id;
    public final NpcRole role;
    public double x, y, z;
    public double facing;

    private final double homeX, homeZ;
    private final Random random;
    private NpcState state = NpcState.WANDER;
    private int health = MAX_HEALTH;
    private double targetX, targetZ;
    private double pauseRemaining;
    private double threatX, threatZ;
    private double fleeRemaining;

    public Npc(UUID id, NpcRole role, double x, double z, long seed) {
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.x = requireFinite(x, "x");
        this.z = requireFinite(z, "z");
        homeX = x;
        homeZ = z;
        random = new Random(seed);
        pickTarget();
    }

    public UUID id() {
        return id;
    }

    public NpcRole role() {
        return role;
    }

    public NpcState state() {
        return state;
    }

    public int health() {
        return health;
    }

    public boolean alive() {
        return health > 0;
    }

    public boolean isAlive() {
        return alive();
    }

    /** Applies non-negative damage and reports whether this hit killed the NPC. */
    public boolean damage(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount == 0 || !alive()) {
            return false;
        }
        health = Math.max(0, health - amount);
        if (health == 0) {
            state = NpcState.DEAD;
        }
        return health == 0;
    }

    public boolean takeDamage(int amount) {
        return damage(amount);
    }

    /** Makes a living civilian flee from the supplied crime location. */
    public void witnessCrime(double crimeX, double crimeZ) {
        threatX = requireFinite(crimeX, "crimeX");
        threatZ = requireFinite(crimeZ, "crimeZ");
        if (alive() && role == NpcRole.CIVILIAN) {
            state = NpcState.FLEE;
            fleeRemaining = FLEE_SECONDS;
        }
    }

    /** Advances behavior and snaps the NPC's feet to the current surface. */
    public void update(double dt, SurfaceProvider surface, double playerX,
                       double playerZ, int wantedLevel) {
        requireNonNegativeFinite(dt, "dt");
        Objects.requireNonNull(surface, "surface");
        requireFinite(playerX, "playerX");
        requireFinite(playerZ, "playerZ");
        validatePosition();

        double step = Math.min(dt, 1.0);
        if (!alive()) {
            state = NpcState.DEAD;
        } else if (role == NpcRole.POLICE && wantedLevel > 0) {
            state = NpcState.PURSUE;
            moveToward(playerX, playerZ, (2.0 + Math.min(wantedLevel, 5) * 0.2) * step);
        } else if (state == NpcState.FLEE && fleeRemaining > 0) {
            moveAwayFromThreat(2.8 * step);
            fleeRemaining = Math.max(0, fleeRemaining - dt);
            if (fleeRemaining == 0) {
                state = NpcState.WANDER;
                pickTarget();
            }
        } else {
            if (state != NpcState.WANDER) {
                state = NpcState.WANDER;
                pickTarget();
            }
            wander(step);
        }

        y = requireFinite(surface.surfaceY(x, z), "surface height");
        validatePosition();
    }

    private void wander(double dt) {
        double dx = targetX - x;
        double dz = targetZ - z;
        double distance = Math.hypot(dx, dz);
        if (distance <= ARRIVAL_DISTANCE) {
            pauseRemaining -= dt;
            if (pauseRemaining <= 0) {
                pickTarget();
            }
            return;
        }
        moveInDirection(dx, dz, Math.min(1.4 * dt, distance));
    }

    private void moveToward(double targetX, double targetZ, double distance) {
        double dx = targetX - x;
        double dz = targetZ - z;
        double remaining = Math.hypot(dx, dz);
        if (remaining > 1e-9) {
            moveInDirection(dx, dz, Math.min(distance, remaining));
        }
    }

    private void moveAwayFromThreat(double distance) {
        double dx = x - threatX;
        double dz = z - threatZ;
        if (Math.hypot(dx, dz) <= 1e-9) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            dx = Math.sin(angle);
            dz = Math.cos(angle);
        }
        moveInDirection(dx, dz, distance);
    }

    private void moveInDirection(double dx, double dz, double distance) {
        double length = Math.hypot(dx, dz);
        if (length <= 1e-9 || distance <= 0) {
            return;
        }
        x += dx / length * distance;
        z += dz / length * distance;
        facing = Math.toDegrees(Math.atan2(dx, dz));
    }

    private void pickTarget() {
        targetX = homeX + (random.nextDouble() * 2.0 - 1.0) * ROAM_RADIUS;
        targetZ = homeZ + (random.nextDouble() * 2.0 - 1.0) * ROAM_RADIUS;
        pauseRemaining = 0.5 + random.nextDouble() * 2.0;
    }

    private void validatePosition() {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(facing, "facing");
    }

    private static double requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static void requireNonNegativeFinite(double value, String name) {
        requireFinite(value, name);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}