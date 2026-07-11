package com.blockshot.entity;

/**
 * First-person player with delta-time movement, gravity, jumping, terrain
 * following and wall collision. Position (x, y, z) is the player's feet; the
 * camera sits at feet + {@link #EYE_HEIGHT}.
 */
public final class Player {

    public static final double EYE_HEIGHT = 1.62;
    private static final double GRAVITY = 22.0;
    private static final double JUMP_SPEED = 7.5;
    private static final double WALK_SPEED = 4.5;
    private static final double SPRINT_SPEED = 8.0;
    private static final double STEP_UP = 1.1; // max slope the player can walk up

    public double x, y, z;
    public double yaw, pitch;
    public double vy;
    public boolean onGround;

    public Player(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double eyeY() {
        return y + EYE_HEIGHT;
    }

    /**
     * @param forward  -1..1 (W/S)
     * @param strafe   -1..1 (A/D)
     */
    public void update(double dt, double forward, double strafe, boolean jump,
                       boolean sprint, CollisionWorld world) {
        double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
        double r = Math.toRadians(yaw);
        double fx = Math.sin(r), fz = Math.cos(r);
        double sx = Math.cos(r), sz = -Math.sin(r);

        double moveX = (fx * forward + sx * strafe);
        double moveZ = (fz * forward + sz * strafe);
        double len = Math.hypot(moveX, moveZ);
        if (len > 1e-6) {
            moveX /= len;
            moveZ /= len;
            double dx = moveX * speed * dt;
            double dz = moveZ * speed * dt;
            tryMoveAxis(dx, 0, world);
            tryMoveAxis(0, dz, world);
        }

        // Vertical: gravity + jump.
        if (jump && onGround) {
            vy = JUMP_SPEED;
            onGround = false;
        }
        vy -= GRAVITY * dt;
        double ny = y + vy * dt;
        double ground = world.surfaceY(x, z);
        if (ny <= ground) {
            y = ground;
            vy = 0;
            onGround = true;
        } else {
            y = ny;
            onGround = false;
        }
    }

    private void tryMoveAxis(double dx, double dz, CollisionWorld world) {
        double nx = x + dx, nz = z + dz;
        double ground = world.surfaceY(nx, nz);
        boolean tooSteep = onGround && ground - y > STEP_UP;
        if (!tooSteep && !world.isBlocked(nx, nz, Math.max(y, ground))) {
            x = nx;
            z = nz;
        }
    }
}
