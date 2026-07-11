package com.blockshot.game;

/** Crimes and the number of heat bands each one adds. */
public enum CrimeType {
    THEFT(1),
    ASSAULT(2),
    VEHICLE_THEFT(2),
    ATTACK_POLICE(3);

    private final int severity;

    CrimeType(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }
}