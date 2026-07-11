package com.blockshot.world;

import java.util.HashMap;
import java.util.Map;

/** Stable block metadata shared by rendering, physics, persistence and networking. */
public enum BlockMaterial {

    AIR(0, 0.00f, 0.00f, 0.00f, 0.00f, false, false, false,
            0.0, 0.0, 0.0, GravityBehavior.NONE),
    DIRT(1, 0.60f, 0.40f, 0.20f, 1.00f, true, true, true,
            1.0, 1.2, 3.0, GravityBehavior.NONE),
    STONE(2, 0.50f, 0.50f, 0.50f, 1.00f, true, true, true,
            4.0, 2.5, 12.0, GravityBehavior.NONE),
    GRASS(3, 0.27f, 0.59f, 0.27f, 1.00f, true, true, true,
            0.8, 1.1, 2.5, GravityBehavior.NONE),
    WOOD(4, 0.55f, 0.35f, 0.15f, 1.00f, true, true, true,
            2.0, 0.8, 5.0, GravityBehavior.NONE),
    BRICK(5, 0.70f, 0.30f, 0.20f, 1.00f, true, true, true,
            3.5, 2.0, 9.0, GravityBehavior.NONE),
    GOLD(6, 0.90f, 0.80f, 0.10f, 1.00f, true, true, true,
            3.0, 5.0, 7.0, GravityBehavior.NONE),
    GLASS(7, 0.72f, 0.86f, 0.92f, 0.35f, true, false, true,
            0.3, 0.6, 1.0, GravityBehavior.NONE),
    CONCRETE(8, 0.55f, 0.55f, 0.58f, 1.00f, true, true, true,
            5.0, 2.4, 14.0, GravityBehavior.NONE),
    STEEL(9, 0.38f, 0.42f, 0.48f, 1.00f, true, true, true,
            8.0, 3.8, 30.0, GravityBehavior.NONE),
    SAND(10, 0.76f, 0.70f, 0.45f, 1.00f, true, true, true,
            0.5, 1.4, 0.5, GravityBehavior.FALLING),
    WATER(11, 0.18f, 0.38f, 0.62f, 0.55f, false, false, false,
            0.0, 1.0, 0.0, GravityBehavior.FLUID),
    ASPHALT(12, 0.20f, 0.21f, 0.23f, 1.00f, true, true, true,
            4.5, 2.2, 11.0, GravityBehavior.NONE);

    public enum GravityBehavior {
        NONE,
        FALLING,
        FLUID
    }

    private static final Map<Integer, BlockMaterial> BY_WIRE_ID;

    static {
        Map<Integer, BlockMaterial> byWireId = new HashMap<>();
        for (BlockMaterial material : values()) {
            BlockMaterial previous = byWireId.put(material.wireId, material);
            if (previous != null) {
                throw new IllegalStateException("Duplicate block material wire ID: " + material.wireId);
            }
        }
        BY_WIRE_ID = Map.copyOf(byWireId);
    }

    private final int wireId;
    private final float r;
    private final float g;
    private final float b;
    private final float a;
    private final boolean solid;
    private final boolean opaque;
    private final boolean collectible;
    private final double hardness;
    private final double mass;
    private final double supportStrength;
    private final GravityBehavior gravityBehavior;

    BlockMaterial(int wireId, float r, float g, float b, float a,
                  boolean solid, boolean opaque, boolean collectible,
                  double hardness, double mass, double supportStrength,
                  GravityBehavior gravityBehavior) {
        if (wireId < 0) throw new IllegalArgumentException("wireId must not be negative");
        if (r < 0 || r > 1 || g < 0 || g > 1 || b < 0 || b > 1 || a < 0 || a > 1) {
            throw new IllegalArgumentException("RGBA components must be between zero and one");
        }
        if (hardness < 0 || mass < 0 || supportStrength < 0) {
            throw new IllegalArgumentException("Physical properties must not be negative");
        }
        this.wireId = wireId;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.solid = solid;
        this.opaque = opaque;
        this.collectible = collectible;
        this.hardness = hardness;
        this.mass = mass;
        this.supportStrength = supportStrength;
        this.gravityBehavior = gravityBehavior;
    }

    public int wireId() {
        return wireId;
    }

    public float r() {
        return r;
    }

    public float g() {
        return g;
    }

    public float b() {
        return b;
    }

    public float a() {
        return a;
    }

    public float[] rgba() {
        return new float[] {r, g, b, a};
    }

    public boolean solid() {
        return solid;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean opaque() {
        return opaque;
    }

    public boolean isOpaque() {
        return opaque;
    }

    public boolean collectible() {
        return collectible;
    }

    public boolean isCollectible() {
        return collectible;
    }

    public double hardness() {
        return hardness;
    }

    public double mass() {
        return mass;
    }

    public double supportStrength() {
        return supportStrength;
    }

    public GravityBehavior gravityBehavior() {
        return gravityBehavior;
    }

    public boolean affectedByGravity() {
        return gravityBehavior != GravityBehavior.NONE;
    }

    public boolean gravityAffected() {
        return affectedByGravity();
    }

    public boolean fallsWithGravity() {
        return gravityBehavior == GravityBehavior.FALLING;
    }

    public static BlockMaterial fromWireId(int wireId) {
        BlockMaterial material = BY_WIRE_ID.get(wireId);
        if (material == null) {
            throw new IllegalArgumentException("Unknown block material wire ID: " + wireId);
        }
        return material;
    }
}