package com.blockshot.net;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class NetworkMessageCodec {

    public static final int MAX_PACKET_BYTES = 4_096;
    public static final int MAX_NAME_BYTES = 256;

    private static final int MAX_ENCODED_NAME_LENGTH = (MAX_NAME_BYTES * 4 + 2) / 3;
    private static final int MAX_DOUBLE_LENGTH = 32;
    private static final Base64.Encoder NAME_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder NAME_DECODER = Base64.getUrlDecoder();

    private NetworkMessageCodec() {
    }

    public static String encode(NetworkMessage message) {
        if (message == null) {
            throw invalid("Message must not be null");
        }

        String packet;
        if (message instanceof NetworkMessage.Hello hello) {
            packet = "HELLO|" + encodeUuid(hello.id()) + '|' + encodeName(hello.name());
        } else if (message instanceof NetworkMessage.PlayerState state) {
            requireFinite(state.x(), "x");
            requireFinite(state.y(), "y");
            requireFinite(state.z(), "z");
            requireFinite(state.yaw(), "yaw");
            packet = "STATE|" + encodeUuid(state.id()) + '|' + encodeName(state.name())
                    + '|' + Double.toString(state.x())
                    + '|' + Double.toString(state.y())
                    + '|' + Double.toString(state.z())
                    + '|' + Double.toString(state.yaw())
                    + '|' + Long.toString(state.sequence());
        } else if (message instanceof NetworkMessage.BlockEdit edit) {
            if (edit.pos() == null) {
                throw invalid("Block position must not be null");
            }
            BlockPos pos = edit.pos();
            packet = "BLOCK|" + encodeUuid(edit.actor())
                    + '|' + Long.toString(edit.sequence())
                    + '|' + Integer.toString(pos.x())
                    + '|' + Integer.toString(pos.y())
                    + '|' + Integer.toString(pos.z())
                    + '|' + materialWireId(edit.material())
                    + '|' + Boolean.toString(edit.placed());
        } else if (message instanceof NetworkMessage.Goodbye goodbye) {
            packet = "GOODBYE|" + encodeUuid(goodbye.id());
        } else {
            throw invalid("Unknown message type");
        }

        requirePacketSize(packet);
        return packet;
    }

    public static NetworkMessage decode(String packet) {
        requirePacketSize(packet);
        String[] fields = packet.split("\\|", -1);
        if (fields.length == 0) {
            throw invalid("Packet has no message type");
        }

        return switch (fields[0]) {
            case "HELLO" -> decodeHello(fields);
            case "STATE" -> decodePlayerState(fields);
            case "BLOCK" -> decodeBlockEdit(fields);
            case "GOODBYE" -> decodeGoodbye(fields);
            default -> throw invalid("Unknown message type");
        };
    }

    private static NetworkMessage.Hello decodeHello(String[] fields) {
        requireFieldCount(fields, 3);
        return new NetworkMessage.Hello(parseUuid(fields[1]), decodeName(fields[2]));
    }

    private static NetworkMessage.PlayerState decodePlayerState(String[] fields) {
        requireFieldCount(fields, 8);
        return new NetworkMessage.PlayerState(
                parseUuid(fields[1]),
                decodeName(fields[2]),
                parseFiniteDouble(fields[3], "x"),
                parseFiniteDouble(fields[4], "y"),
                parseFiniteDouble(fields[5], "z"),
                parseFiniteDouble(fields[6], "yaw"),
                parseLong(fields[7], "sequence"));
    }

    private static NetworkMessage.BlockEdit decodeBlockEdit(String[] fields) {
        requireFieldCount(fields, 8);
        return new NetworkMessage.BlockEdit(
                parseUuid(fields[1]),
                parseLong(fields[2], "sequence"),
                new BlockPos(
                        parseInteger(fields[3], "x"),
                        parseInteger(fields[4], "y"),
                        parseInteger(fields[5], "z")),
                parseMaterial(fields[6]),
                parseBoolean(fields[7]));
    }

    private static NetworkMessage.Goodbye decodeGoodbye(String[] fields) {
        requireFieldCount(fields, 2);
        return new NetworkMessage.Goodbye(parseUuid(fields[1]));
    }

    private static String encodeUuid(UUID value) {
        if (value == null) {
            throw invalid("UUID must not be null");
        }
        return value.toString();
    }

    private static UUID parseUuid(String value) {
        if (value.length() != 36) {
            throw invalid("Invalid UUID");
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw invalid("Invalid UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid UUID", exception);
        }
    }

    private static String encodeName(String name) {
        byte[] bytes = validatedNameBytes(name);
        return NAME_ENCODER.encodeToString(bytes);
    }

    private static String decodeName(String value) {
        if (value.isEmpty() || value.length() > MAX_ENCODED_NAME_LENGTH) {
            throw invalid("Invalid encoded name length");
        }

        byte[] bytes;
        try {
            bytes = NAME_DECODER.decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid encoded name", exception);
        }
        if (!NAME_ENCODER.encodeToString(bytes).equals(value)) {
            throw invalid("Encoded name is not canonical");
        }

        String name = decodeUtf8(bytes);
        validatedNameBytes(name);
        return name;
    }

    private static byte[] validatedNameBytes(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_BYTES) {
            throw invalid("Invalid name length");
        }
        byte[] bytes = encodeUtf8(name);
        if (bytes.length > MAX_NAME_BYTES) {
            throw invalid("Invalid name length");
        }
        return bytes;
    }

    private static double parseFiniteDouble(String value, String fieldName) {
        if (value.isEmpty() || value.length() > MAX_DOUBLE_LENGTH) {
            throw invalid("Invalid " + fieldName);
        }
        try {
            double parsed = Double.parseDouble(value);
            requireFinite(parsed, fieldName);
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid("Invalid " + fieldName, exception);
        }
    }

    private static void requireFinite(double value, String fieldName) {
        if (!Double.isFinite(value)) {
            throw invalid(fieldName + " must be finite");
        }
    }

    private static int parseInteger(String value, String fieldName) {
        if (value.isEmpty() || value.length() > 11) {
            throw invalid("Invalid " + fieldName);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid("Invalid " + fieldName, exception);
        }
    }

    private static long parseLong(String value, String fieldName) {
        if (value.isEmpty() || value.length() > 20) {
            throw invalid("Invalid " + fieldName);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid("Invalid " + fieldName, exception);
        }
    }

    private static boolean parseBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw invalid("Invalid placed flag");
    }

    private static String materialWireId(BlockMaterial material) {
        if (material == null) {
            throw invalid("Block material must not be null");
        }
        return Integer.toString(material.wireId());
    }

    private static BlockMaterial parseMaterial(String wireId) {
        try {
            return BlockMaterial.fromWireId(parseInteger(wireId, "material"));
        } catch (IllegalArgumentException exception) {
            throw invalid("Unknown block material", exception);
        }
    }

    private static void requireFieldCount(String[] fields, int expected) {
        if (fields.length != expected) {
            throw invalid("Invalid field count");
        }
    }

    private static void requirePacketSize(String packet) {
        if (packet == null) {
            throw invalid("Packet must not be null");
        }
        if (packet.length() > MAX_PACKET_BYTES || encodeUtf8(packet).length > MAX_PACKET_BYTES) {
            throw invalid("Packet is too large");
        }
    }

    private static byte[] encodeUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw invalid("Invalid UTF-8 text", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("Invalid UTF-8 text", exception);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}