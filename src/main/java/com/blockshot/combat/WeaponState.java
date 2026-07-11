package com.blockshot.combat;

import java.util.Objects;

/** Mutable finite-ammunition and cooldown state for one weapon. */
public final class WeaponState {

    private final WeaponType type;
    private int ammo;
    private int reserve;
    private double cooldown;

    public WeaponState(WeaponType type) {
        this(Objects.requireNonNull(type, "type"), type.magazineSize(), type.defaultReserve());
    }

    public WeaponState(WeaponType type, int ammo, int reserve) {
        this.type = Objects.requireNonNull(type, "type");
        if (ammo < 0 || ammo > type.magazineSize()) {
            throw new IllegalArgumentException("ammo must be between zero and magazine size");
        }
        if (reserve < 0) {
            throw new IllegalArgumentException("reserve must not be negative");
        }
        this.ammo = ammo;
        this.reserve = reserve;
    }

    public WeaponType type() {
        return type;
    }

    public int ammo() {
        return ammo;
    }

    public int reserve() {
        return reserve;
    }

    public double cooldown() {
        return cooldown;
    }

    /** Fires one round when loaded and ready. */
    public boolean tryFire() {
        if (ammo == 0 || cooldown > 0) {
            return false;
        }
        ammo--;
        cooldown = type.fireInterval();
        return true;
    }

    /** Transfers as much reserve ammunition as possible into the magazine. */
    public boolean reload() {
        int transfer = Math.min(type.magazineSize() - ammo, reserve);
        if (transfer == 0) {
            return false;
        }
        ammo += transfer;
        reserve -= transfer;
        return true;
    }

    public void update(double dt) {
        if (!Double.isFinite(dt) || dt < 0) {
            throw new IllegalArgumentException("dt must be finite and not negative");
        }
        cooldown = Math.max(0, cooldown - dt);
    }
}