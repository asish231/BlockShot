package com.blockshot.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrimeSystemTest {

    @Test
    void givenViolentCrime_whenReported_thenWantedLevelRisesAndIsCapped() {
        CrimeSystem crime = new CrimeSystem();

        crime.report(CrimeType.ASSAULT);
        assertTrue(crime.wantedLevel() >= 1);
        for (int i = 0; i < 20; i++) crime.report(CrimeType.ATTACK_POLICE);

        assertEquals(5, crime.wantedLevel());
    }

    @Test
    void givenNoFurtherCrime_whenEnoughTimePasses_thenWantedLevelDecays() {
        CrimeSystem crime = new CrimeSystem();
        crime.report(CrimeType.ATTACK_POLICE);
        int initial = crime.wantedLevel();

        crime.update(180);

        assertTrue(crime.wantedLevel() < initial);
    }
}