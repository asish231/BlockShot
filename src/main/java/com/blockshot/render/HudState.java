package com.blockshot.render;

/** Immutable presentation data for gameplay systems that do not belong in the HUD renderer. */
public record HudState(
        int health,
        int wantedLevel,
        String mode,
        String weapon,
        int ammo,
        int reserve,
        int selectedBlockCount,
        String vehicle,
        int occupants,
        int vehicleCapacity,
        int remotePlayers,
        String network,
        long gpuBytes,
        int drawCalls,
        String message) {

    public HudState {
        health = Math.max(0, Math.min(100, health));
        wantedLevel = Math.max(0, Math.min(5, wantedLevel));
        ammo = Math.max(0, ammo);
        reserve = Math.max(0, reserve);
        selectedBlockCount = Math.max(0, selectedBlockCount);
        occupants = Math.max(0, occupants);
        vehicleCapacity = Math.max(0, vehicleCapacity);
        remotePlayers = Math.max(0, remotePlayers);
        gpuBytes = Math.max(0, gpuBytes);
        drawCalls = Math.max(0, drawCalls);
        mode = safe(mode, "BUILD");
        weapon = safe(weapon, "PISTOL");
        vehicle = safe(vehicle, "ON FOOT");
        network = safe(network, "OFFLINE");
        message = safe(message, "");
    }

    public static HudState exploration() {
        return new HudState(100, 0, "BUILD", "PISTOL", 0, 0, 0,
                "ON FOOT", 0, 0, 0, "OFFLINE", 0, 0, "");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}