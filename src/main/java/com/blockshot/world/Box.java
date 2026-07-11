package com.blockshot.world;

/**
 * Immutable renderable axis-aligned box. Coordinates are the minimum corner,
 * so the box occupies [x, x+w] x [y, y+h] x [z, z+d]. The alpha channel allows
 * translucent blocks such as water.
 */
public record Box(double x, double y, double z, double w, double h, double d,
                  float r, float g, float b, float a) {

    public Box(double x, double y, double z, double w, double h, double d, float r, float g, float b) {
        this(x, y, z, w, h, d, r, g, b, 1f);
    }

    public boolean opaque() {
        return a >= 0.999f;
    }
}
