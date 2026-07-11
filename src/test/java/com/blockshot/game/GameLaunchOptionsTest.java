package com.blockshot.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameLaunchOptionsTest {

    @Test
    void givenNoArguments_whenParsed_thenOfflineDefaultsAreUsed() {
        GameLaunchOptions options = GameLaunchOptions.parse(new String[0]);

        assertFalse(options.multiplayer());
        assertEquals("Player", options.playerName());
    }

    @Test
    void givenHostArguments_whenParsed_thenPortAndNameAreRetained() {
        GameLaunchOptions options = GameLaunchOptions.parse(
                new String[] {"--host=30123", "--name=City Pilot"});

        assertTrue(options.hosting());
        assertEquals(30123, options.port());
        assertEquals("City Pilot", options.playerName());
    }

    @Test
    void givenJoinArgument_whenParsed_thenRemoteEndpointIsRetained() {
        GameLaunchOptions options = GameLaunchOptions.parse(
                new String[] {"--join=127.0.0.1:30123"});

        assertTrue(options.joining());
        assertEquals("127.0.0.1", options.remoteHost());
        assertEquals(30123, options.port());
    }

    @Test
    void givenConflictingOrInvalidArguments_whenParsed_thenTheyAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GameLaunchOptions.parse(
                new String[] {"--host", "--join=localhost:30123"}));
        assertThrows(IllegalArgumentException.class, () -> GameLaunchOptions.parse(
                new String[] {"--join=localhost:not-a-port"}));
        assertThrows(IllegalArgumentException.class, () -> GameLaunchOptions.parse(
                new String[] {"--unknown"}));
    }
}