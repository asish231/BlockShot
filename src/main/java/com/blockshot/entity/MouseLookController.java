package com.blockshot.entity;

/** Accumulates focus-safe pointer movement for first-person camera look. */
public final class MouseLookController {

    private final double sensitivity;
    private boolean sampleValid;
    private double previousX, previousY;
    private double yawDelta, pitchDelta;

    public MouseLookController(double sensitivity) {
        if (!Double.isFinite(sensitivity) || sensitivity < 0) {
            throw new IllegalArgumentException("sensitivity must be finite and not negative");
        }
        this.sensitivity = sensitivity;
    }

    public void onPointer(double x, double y) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        if (sampleValid) {
            double nextYaw = yawDelta + (x - previousX) * sensitivity;
            double nextPitch = pitchDelta + (y - previousY) * sensitivity;
            requireFinite(nextYaw, "yaw delta");
            requireFinite(nextPitch, "pitch delta");
            yawDelta = nextYaw;
            pitchDelta = nextPitch;
        }
        previousX = x;
        previousY = y;
        sampleValid = true;
    }

    public double consumeYawDelta() {
        double result = yawDelta;
        yawDelta = 0;
        return result;
    }

    public double consumePitchDelta() {
        double result = pitchDelta;
        pitchDelta = 0;
        return result;
    }

    /** Invalidates only the pointer baseline, preserving unconsumed movement. */
    public void reset() {
        sampleValid = false;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}