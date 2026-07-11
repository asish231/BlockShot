package com.blockshot.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeaponStateTest {

    @Test
    void givenLoadedPistol_whenFired_thenAmmoAndCooldownAreApplied() {
        WeaponState weapon = new WeaponState(WeaponType.PISTOL);

        assertTrue(weapon.tryFire());
        assertEquals(WeaponType.PISTOL.magazineSize() - 1, weapon.ammo());
        assertFalse(weapon.tryFire());
        weapon.update(WeaponType.PISTOL.fireInterval());
        assertTrue(weapon.tryFire());
    }

    @Test
    void givenEmptyMagazineAndReserve_whenReloaded_thenAmmoTransfers() {
        WeaponState weapon = new WeaponState(WeaponType.PISTOL, 0, 5);

        assertTrue(weapon.reload());

        assertEquals(5, weapon.ammo());
        assertEquals(0, weapon.reserve());
    }
}