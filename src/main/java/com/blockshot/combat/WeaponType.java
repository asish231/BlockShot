package com.blockshot.combat;

/** Immutable firing characteristics for each weapon category. */
public enum WeaponType {
    PISTOL(12, 0.3, 48),
    RIFLE(30, 0.1, 120),
    SHOTGUN(8, 0.8, 32);

    private final int magazineSize;
    private final double fireInterval;
    private final int defaultReserve;

    WeaponType(int magazineSize, double fireInterval, int defaultReserve) {
        this.magazineSize = magazineSize;
        this.fireInterval = fireInterval;
        this.defaultReserve = defaultReserve;
    }

    public int magazineSize() {
        return magazineSize;
    }

    public double fireInterval() {
        return fireInterval;
    }

    public int defaultReserve() {
        return defaultReserve;
    }
}