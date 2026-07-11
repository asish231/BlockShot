package com.blockshot.entity;

/** Supplies the walkable/swim surface height for a world position. */
@FunctionalInterface
public interface SurfaceProvider {
    double surfaceY(double x, double z);
}
