package com.blockshot.entity;

import com.blockshot.world.ChunkManager;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;

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
        boolean inWater = false;
        boolean onLadder = false;

        if (world instanceof ChunkManager) {
            ChunkManager cm = (ChunkManager) world;
            BlockMaterial matFeet = cm.blockAt(new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
            BlockMaterial matEyes = cm.blockAt(new BlockPos((int) Math.floor(x), (int) Math.floor(eyeY()), (int) Math.floor(z)));
            inWater = (matFeet == BlockMaterial.WATER || matEyes == BlockMaterial.WATER);
            onLadder = (matFeet == BlockMaterial.LADDER || matEyes == BlockMaterial.LADDER);
        }

        double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
        if (inWater) {
            speed *= 0.5;
        }

        double r = Math.toRadians(yaw);
        double fx = Math.sin(r), fz = -Math.cos(r);
        double sx = Math.cos(r), sz = Math.sin(r);

        double moveX = (fx * forward - sx * strafe);
        double moveZ = (fz * forward - sz * strafe);
        double len = Math.hypot(moveX, moveZ);
        if (len > 1e-6) {
            moveX /= len;
            moveZ /= len;
            double dx = moveX * speed * dt;
            double dz = moveZ * speed * dt;
            tryMoveAxis(dx, 0, world);
            tryMoveAxis(0, dz, world);
        }

        // Vertical: gravity + jump / climb / swim.
        if (onLadder) {
            if (forward > 0 || jump) {
                vy = 3.0;
            } else if (forward < 0) {
                vy = -3.0;
            } else {
                vy = 0;
            }
            onGround = true; // prevent fall damage and allow stable state on ladder
        } else if (inWater) {
            if (jump) {
                vy = 2.0; // Swim up
            } else {
                vy = Math.max(-1.5, vy - 6.0 * dt); // Sink slowly
            }
            onGround = false;
        } else {
            if (jump && onGround) {
                vy = JUMP_SPEED;
                onGround = false;
            }
            vy -= GRAVITY * dt;
        }

        double ny = y + vy * dt;
        double ground = world.surfaceY(x, z);
        if (ny <= ground) {
            y = ground;
            vy = 0;
            onGround = true;
        } else {
            y = ny;
            if (!onLadder) {
                onGround = false;
            }
        }
        pushOutOfBlocks(world);
    }

    private void pushOutOfBlocks(CollisionWorld world) {
        if (!(world instanceof ChunkManager)) return;
        ChunkManager cm = (ChunkManager) world;

        double rr = 0.28; // Player bounding radius (slightly smaller than 0.3)
        int y0 = (int) Math.floor(y + 0.05);
        int y1 = (int) Math.floor(y + 1.6);

        int px = (int) Math.floor(x);
        int pz = (int) Math.floor(z);

        for (int by = y0; by <= y1; by++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int bx = px + dx;
                    int bz = pz + dz;

                    if (cm.isSolidCell(bx, by, bz)) {
                        double minX = bx, maxX = bx + 1;
                        double minZ = bz, maxZ = bz + 1;

                        double pMinX = x - rr, pMaxX = x + rr;
                        double pMinZ = z - rr, pMaxZ = z + rr;

                        if (pMaxX > minX && pMinX < maxX && pMaxZ > minZ && pMinZ < maxZ) {
                            double overlapX = (x > bx + 0.5) ? (maxX - pMinX) : (pMaxX - minX);
                            double overlapZ = (z > bz + 0.5) ? (maxZ - pMinZ) : (pMaxZ - minZ);

                            if (overlapX < overlapZ) {
                                x += (x > bx + 0.5) ? overlapX + 1e-4 : -overlapX - 1e-4;
                            } else {
                                z += (z > bz + 0.5) ? overlapZ + 1e-4 : -overlapZ - 1e-4;
                            }
                        }
                    }
                }
            }
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
