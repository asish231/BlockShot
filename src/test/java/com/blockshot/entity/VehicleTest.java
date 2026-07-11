package com.blockshot.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleTest {

    private static final SurfaceProvider GROUND = (x, z) -> 4;

    @Test
    void givenCarWithDriver_whenAccelerating_thenItMovesAndFollowsGround() {
        Vehicle car = new Vehicle(UUID.randomUUID(), VehicleType.CAR, 0, 4, 0);
        UUID driver = UUID.randomUUID();
        assertTrue(car.board(driver));

        for (int i = 0; i < 120; i++) {
            car.update(1.0 / 60, driver, new VehicleControl(1, 0.25, 0, 0), GROUND);
        }

        assertTrue(Math.hypot(car.x, car.z) > 2);
        assertTrue(Math.abs(car.y - 4) < 1e-6);
    }

    @Test
    void givenHelicopter_whenLiftApplied_thenItClimbs() {
        Vehicle helicopter = new Vehicle(UUID.randomUUID(), VehicleType.HELICOPTER, 0, 4, 0);
        UUID driver = UUID.randomUUID();
        helicopter.board(driver);

        for (int i = 0; i < 120; i++) {
            helicopter.update(1.0 / 60, driver, new VehicleControl(0.4, 0, 1, 0), GROUND);
        }

        assertTrue(helicopter.y > 6);
    }

    @Test
    void givenPlaneAtLowSpeed_whenNoThrottle_thenItDoesNotGainAltitude() {
        Vehicle plane = new Vehicle(UUID.randomUUID(), VehicleType.PLANE, 0, 4, 0);
        UUID driver = UUID.randomUUID();
        plane.board(driver);

        for (int i = 0; i < 120; i++) {
            plane.update(1.0 / 60, driver, new VehicleControl(0, 0, 0, -1), GROUND);
        }

        assertTrue(plane.y <= 4.01);
    }

    @Test
    void givenFullVehicle_whenAnotherPassengerBoards_thenCapacityIsEnforced() {
        Vehicle car = new Vehicle(UUID.randomUUID(), VehicleType.CAR, 0, 0, 0);
        for (int i = 0; i < VehicleType.CAR.capacity(); i++) {
            assertTrue(car.board(UUID.randomUUID()));
        }

        assertFalse(car.board(UUID.randomUUID()));
    }
}