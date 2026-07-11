package com.blockshot.game;

import java.util.Objects;

/** Validated command-line options for offline or LAN play. */
public record GameLaunchOptions(Mode mode, String remoteHost, int port, String playerName) {

    public static final int DEFAULT_PORT = 29_555;

    public enum Mode { OFFLINE, HOST, JOIN }

    public GameLaunchOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(playerName, "playerName");
        if (playerName.isBlank() || playerName.length() > 32) {
            throw new IllegalArgumentException("Player name must contain 1 to 32 characters");
        }
        if (mode == Mode.OFFLINE) {
            remoteHost = null;
            port = 0;
        } else if (mode == Mode.HOST) {
            remoteHost = "127.0.0.1";
            requirePort(port, true);
        } else {
            if (remoteHost == null || remoteHost.isBlank() || remoteHost.length() > 253) {
                throw new IllegalArgumentException("Remote host is invalid");
            }
            requirePort(port, false);
        }
    }

    public static GameLaunchOptions parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        Mode mode = Mode.OFFLINE;
        String host = null;
        int port = 0;
        String name = "Player";
        boolean modeSpecified = false;
        boolean nameSpecified = false;

        for (String argument : arguments) {
            if (argument == null) throw new IllegalArgumentException("Arguments must not be null");
            if (argument.equals("--host") || argument.startsWith("--host=")) {
                if (modeSpecified) throw new IllegalArgumentException("Choose only one host/join mode");
                modeSpecified = true;
                mode = Mode.HOST;
                port = argument.equals("--host")
                        ? DEFAULT_PORT : parsePort(argument.substring("--host=".length()), true);
            } else if (argument.startsWith("--join=")) {
                if (modeSpecified) throw new IllegalArgumentException("Choose only one host/join mode");
                modeSpecified = true;
                mode = Mode.JOIN;
                Endpoint endpoint = parseEndpoint(argument.substring("--join=".length()));
                host = endpoint.host();
                port = endpoint.port();
            } else if (argument.startsWith("--name=")) {
                if (nameSpecified) throw new IllegalArgumentException("Player name was specified twice");
                nameSpecified = true;
                name = argument.substring("--name=".length()).trim();
            } else {
                throw new IllegalArgumentException("Unknown launch argument: " + argument);
            }
        }
        return new GameLaunchOptions(mode, host, port, name);
    }

    public boolean multiplayer() {
        return mode != Mode.OFFLINE;
    }

    public boolean hosting() {
        return mode == Mode.HOST;
    }

    public boolean joining() {
        return mode == Mode.JOIN;
    }

    private static Endpoint parseEndpoint(String value) {
        String host;
        String portText;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close <= 1 || close + 2 > value.length() || value.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Join endpoint must be [host]:port");
            }
            host = value.substring(1, close);
            portText = value.substring(close + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Join endpoint must be host:port");
            }
            host = value.substring(0, separator);
            portText = value.substring(separator + 1);
        }
        if (host.isBlank()) throw new IllegalArgumentException("Join host must not be blank");
        return new Endpoint(host, parsePort(portText, false));
    }

    private static int parsePort(String text, boolean allowZero) {
        try {
            int parsed = Integer.parseInt(text);
            requirePort(parsed, allowZero);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port is not a number: " + text, exception);
        }
    }

    private static void requirePort(int port, boolean allowZero) {
        int minimum = allowZero ? 0 : 1;
        if (port < minimum || port > 65_535) {
            throw new IllegalArgumentException("Port must be between " + minimum + " and 65535");
        }
    }

    private record Endpoint(String host, int port) {
    }
}