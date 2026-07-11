package com.blockshot.entity;

/** A normalized control sample for a vehicle. */
public record VehicleControl(double throttle, double steering, double lift, double pitch) {

    public VehicleControl {
        requireFinite(throttle, "throttle");
        requireFinite(steering, "steering");
        requireFinite(lift, "lift");
        requireFinite(pitch, "pitch");
        throttle = clamp(throttle);
        steering = clamp(steering);
        lift = clamp(lift);
        pitch = clamp(pitch);
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}