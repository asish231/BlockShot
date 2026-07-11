package com.blockshot.entity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A bounded, arcade-style vehicle with ordered seating. */
public final class Vehicle {

    private static final double MAX_PHYSICS_STEP = 0.1;
    private static final VehicleControl IDLE_CONTROL = new VehicleControl(0, 0, 0, 0);

    public final UUID id;
    public final VehicleType type;
    public double x, y, z;
    public double yaw, pitch, roll;
    public double speed, verticalSpeed;

    private final LinkedHashSet<UUID> occupants = new LinkedHashSet<>();

    public Vehicle(UUID id, VehicleType type, double x, double y, double z) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.x = requireFinite(x, "x");
        this.y = requireFinite(y, "y");
        this.z = requireFinite(z, "z");
    }

    public UUID id() {
        return id;
    }

    public VehicleType type() {
        return type;
    }

    /** Boards an occupant once, provided a seat is available. */
    public boolean board(UUID occupantId) {
        Objects.requireNonNull(occupantId, "occupantId");
        if (occupants.contains(occupantId) || occupants.size() >= type.capacity()) {
            return false;
        }
        return occupants.add(occupantId);
    }

    /** Removes an occupant. The next longest-seated occupant becomes driver. */
    public boolean disembark(UUID occupantId) {
        Objects.requireNonNull(occupantId, "occupantId");
        return occupants.remove(occupantId);
    }

    public UUID driver() {
        return occupants.isEmpty() ? null : occupants.iterator().next();
    }

    public int occupantCount() {
        return occupants.size();
    }

    public boolean contains(UUID occupantId) {
        return occupantId != null && occupants.contains(occupantId);
    }

    /** Returns an immutable snapshot in boarding order. */
    public Set<UUID> occupants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(occupants));
    }

    public double speed() {
        return speed;
    }

    public double verticalSpeed() {
        return verticalSpeed;
    }

    /** Advances physics; only the first occupant's controls are accepted. */
    public void update(double dt, UUID controllerId, VehicleControl control,
                       SurfaceProvider surface) {
        requireNonNegativeFinite(dt, "dt");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(surface, "surface");
        validateState();

        double step = Math.min(dt, MAX_PHYSICS_STEP);
        UUID currentDriver = driver();
        VehicleControl activeControl = currentDriver != null && currentDriver.equals(controllerId)
                ? control : IDLE_CONTROL;
        switch (type) {
            case CAR -> updateCar(step, activeControl, surface);
            case HELICOPTER -> updateHelicopter(step, activeControl, surface);
            case PLANE -> updatePlane(step, activeControl, surface);
        }
        validateState();
    }

    private void updateCar(double dt, VehicleControl control, SurfaceProvider surface) {
        double targetSpeed = control.throttle() >= 0
                ? control.throttle() * 22.0 : control.throttle() * 8.0;
        double acceleration = control.throttle() == 0 ? 16.0 : 12.0;
        speed = clamp(approach(speed, targetSpeed, acceleration * dt), -8.0, 22.0);

        double turnScale = Math.min(1.0, 0.2 + Math.abs(speed) / 8.0);
        yaw = normalizeAngle(yaw + control.steering() * 95.0 * turnScale * dt);
        roll = approach(roll, -control.steering() * 8.0, 30.0 * dt);
        pitch = approach(pitch, 0, 30.0 * dt);
        moveHorizontally(dt);

        y = surfaceHeight(surface);
        verticalSpeed = 0;
    }

    private void updateHelicopter(double dt, VehicleControl control,
                                  SurfaceProvider surface) {
        speed = clamp(approach(speed, control.throttle() * 18.0, 9.0 * dt), -18.0, 18.0);
        yaw = normalizeAngle(yaw + control.steering() * 80.0 * dt);
        pitch = approach(pitch, -control.pitch() * 20.0, 45.0 * dt);
        roll = approach(roll, -control.steering() * 18.0, 45.0 * dt);

        verticalSpeed = clamp(verticalSpeed + (control.lift() * 10.0 - 3.5) * dt,
                -8.0, 8.0);
        moveHorizontally(dt);
        y += verticalSpeed * dt;
        clampToGround(surface);
    }

    private void updatePlane(double dt, VehicleControl control, SurfaceProvider surface) {
        double targetSpeed = control.throttle() >= 0
                ? control.throttle() * 42.0 : control.throttle() * 6.0;
        double acceleration = Math.abs(targetSpeed) > Math.abs(speed) ? 14.0 : 5.0;
        speed = clamp(approach(speed, targetSpeed, acceleration * dt), -6.0, 42.0);

        double steeringAuthority = Math.max(0.15, Math.min(1.0, Math.abs(speed) / 42.0));
        yaw = normalizeAngle(yaw + control.steering() * 35.0 * steeringAuthority * dt);
        pitch = approach(pitch, -control.pitch() * 25.0, 35.0 * dt);
        roll = approach(roll, -control.steering() * 30.0, 50.0 * dt);

        double liftFactor = clamp((Math.abs(speed) - 12.0) / 30.0, 0, 1);
        double verticalAcceleration = -5.0 * (1.0 - liftFactor)
                - control.pitch() * 7.0 * liftFactor;
        verticalSpeed = clamp(verticalSpeed + verticalAcceleration * dt, -14.0, 10.0);
        moveHorizontally(dt);
        y += verticalSpeed * dt;
        clampToGround(surface);
    }

    private void moveHorizontally(double dt) {
        double heading = Math.toRadians(yaw);
        x += Math.sin(heading) * speed * dt;
        z += Math.cos(heading) * speed * dt;
    }

    private void clampToGround(SurfaceProvider surface) {
        double ground = surfaceHeight(surface);
        if (y <= ground) {
            y = ground;
            if (verticalSpeed < 0) {
                verticalSpeed = 0;
            }
        }
    }

    private double surfaceHeight(SurfaceProvider surface) {
        return requireFinite(surface.surfaceY(x, z), "surface height");
    }

    private void validateState() {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        requireFinite(roll, "roll");
        requireFinite(speed, "speed");
        requireFinite(verticalSpeed, "verticalSpeed");
    }

    private static double approach(double value, double target, double maximumChange) {
        return value < target
                ? Math.min(value + maximumChange, target)
                : Math.max(value - maximumChange, target);
    }

    private static double normalizeAngle(double angle) {
        double normalized = angle % 360.0;
        if (normalized > 180.0) return normalized - 360.0;
        if (normalized < -180.0) return normalized + 360.0;
        return normalized;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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