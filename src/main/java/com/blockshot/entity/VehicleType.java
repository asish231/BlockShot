package com.blockshot.entity;

/** The supported arcade vehicle categories and their seating capacities. */
public enum VehicleType {
    CAR(4),
    HELICOPTER(4),
    PLANE(32);

    private final int capacity;

    VehicleType(int capacity) {
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }
}