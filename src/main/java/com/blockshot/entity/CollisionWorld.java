package com.blockshot.entity;

/** World queries the player needs for movement: ground height and wall collision. */
public interface CollisionWorld extends SurfaceProvider {
    /** True if a body standing at (x,z) with feet at feetY would clip a solid wall. */
    boolean isBlocked(double x, double z, double feetY);
}
