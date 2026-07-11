package com.blockshot.game;

import java.util.Objects;

/** Tracks bounded wanted heat and decays it at a deterministic fixed rate. */
public final class CrimeSystem {

    public static final int MAX_WANTED_LEVEL = 5;
    public static final double SECONDS_PER_LEVEL = 30.0;

    private static final double MAX_HEAT = MAX_WANTED_LEVEL * SECONDS_PER_LEVEL;
    private static final double LEVEL_EPSILON = 1e-9;

    private double heat;

    public int wantedLevel() {
        if (heat <= LEVEL_EPSILON) {
            return 0;
        }
        return Math.min(MAX_WANTED_LEVEL,
                (int) Math.ceil((heat - LEVEL_EPSILON) / SECONDS_PER_LEVEL));
    }

    public double heat() {
        return heat;
    }

    public void report(CrimeType type) {
        Objects.requireNonNull(type, "type");
        heat = Math.min(MAX_HEAT, heat + type.severity() * SECONDS_PER_LEVEL);
    }

    public void update(double dt) {
        requireNonNegativeFinite(dt, "dt");
        heat = Math.max(0, heat - dt);
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and not negative");
        }
    }
}