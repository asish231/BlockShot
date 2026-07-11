package com.blockshot.world.osm;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Parses the subset of Overpass JSON needed by the voxel world generator. */
public final class OsmParser {

    private static final int DEFAULT_BUILDING_LEVELS = 3;
    private static final int MAX_BUILDING_LEVELS = 100;

    private final OsmCoordinate projection;

    public OsmParser(OsmCoordinate projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    public List<OsmElement> parse(String json) {
        Objects.requireNonNull(json, "json");
        Object parsed = new JsonReader(json).readDocument();
        Map<String, Object> root = object(parsed, "Overpass root");
        List<Object> rawElements = array(root.get("elements"), "elements");

        Map<Long, BlockPos> nodes = new LinkedHashMap<>();
        Map<Long, RawWay> ways = new LinkedHashMap<>();
        List<Map<String, Object>> relations = new ArrayList<>();

        for (Object value : rawElements) {
            Map<String, Object> element = object(value, "element");
            if (!"node".equals(string(element.get("type")))) continue;
            long id = longNumber(element.get("id"), "node id");
            double latitude = doubleNumber(element.get("lat"), "node latitude");
            double longitude = doubleNumber(element.get("lon"), "node longitude");
            nodes.put(id, projection.toVoxel(latitude, longitude));
        }

        for (Object value : rawElements) {
            Map<String, Object> element = object(value, "element");
            String type = string(element.get("type"));
            if ("way".equals(type)) {
                long id = longNumber(element.get("id"), "way id");
                List<Long> nodeIds = new ArrayList<>();
                for (Object nodeId : array(element.get("nodes"), "way nodes")) {
                    nodeIds.add(longNumber(nodeId, "way node id"));
                }
                ways.put(id, new RawWay(nodeIds, tags(element.get("tags"))));
            } else if ("relation".equals(type)) {
                relations.add(element);
            }
        }

        List<OsmElement> result = new ArrayList<>();
        for (RawWay way : ways.values()) addWayFeature(result, way, nodes);
        for (Map<String, Object> relation : relations) addBuildingRelation(result, relation, ways, nodes);
        return List.copyOf(result);
    }

    private static void addWayFeature(List<OsmElement> result, RawWay way,
                                      Map<Long, BlockPos> nodes) {
        List<BlockPos> points = resolve(way.nodeIds(), nodes);
        Map<String, String> tags = way.tags();
        if (isPresentTag(tags, "building") && points.size() >= 3) {
            result.add(building(points, tags));
        } else if (isPresentTag(tags, "highway") && points.size() >= 2) {
            result.add(new OsmElement.OsmRoad(points, roadWidth(tags)));
        } else if ("water".equalsIgnoreCase(tags.get("natural")) && points.size() >= 3) {
            result.add(new OsmElement.OsmWater(closePolygon(points)));
        } else if ("park".equalsIgnoreCase(tags.get("leisure")) && points.size() >= 3) {
            result.add(new OsmElement.OsmPark(closePolygon(points)));
        }
    }

    private static void addBuildingRelation(List<OsmElement> result,
                                            Map<String, Object> relation,
                                            Map<Long, RawWay> ways,
                                            Map<Long, BlockPos> nodes) {
        Map<String, String> relationTags = tags(relation.get("tags"));
        if (!isPresentTag(relationTags, "building")) return;

        List<List<Long>> outerSegments = new ArrayList<>();
        Object memberValue = relation.get("members");
        if (!(memberValue instanceof List<?>)) return;
        for (Object value : array(memberValue, "relation members")) {
            Map<String, Object> member = object(value, "relation member");
            if (!"way".equals(string(member.get("type")))) continue;
            String role = string(member.get("role"));
            if (role != null && !role.isBlank() && !"outer".equals(role)) continue;
            RawWay way = ways.get(longNumber(member.get("ref"), "relation member ref"));
            if (way != null && way.nodeIds().size() >= 2) {
                outerSegments.add(way.nodeIds());
            }
        }

        for (List<Long> ring : joinRings(outerSegments)) {
            List<BlockPos> points = resolve(ring, nodes);
            if (points.size() >= 3) result.add(building(points, relationTags));
        }
    }

    private static OsmElement.OsmBuilding building(List<BlockPos> points,
                                                   Map<String, String> tags) {
        return new OsmElement.OsmBuilding(closePolygon(points), buildingLevels(tags),
                wallMaterial(tags));
    }

    private static List<BlockPos> resolve(List<Long> nodeIds, Map<Long, BlockPos> nodes) {
        List<BlockPos> result = new ArrayList<>(nodeIds.size());
        for (Long nodeId : nodeIds) {
            BlockPos point = nodes.get(nodeId);
            if (point != null) result.add(point);
        }
        return result;
    }

    private static List<BlockPos> closePolygon(List<BlockPos> points) {
        if (points.isEmpty() || points.get(0).equals(points.get(points.size() - 1))) {
            return points;
        }
        List<BlockPos> closed = new ArrayList<>(points.size() + 1);
        closed.addAll(points);
        closed.add(points.get(0));
        return closed;
    }

    private static List<List<Long>> joinRings(List<List<Long>> source) {
        List<List<Long>> remaining = new ArrayList<>();
        for (List<Long> segment : source) remaining.add(new ArrayList<>(segment));
        List<List<Long>> rings = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Long> ring = remaining.remove(0);
            boolean joined = true;
            while (!isClosed(ring) && joined) {
                joined = false;
                for (int index = 0; index < remaining.size(); index++) {
                    List<Long> segment = remaining.get(index);
                    if (appendIfConnected(ring, segment)) {
                        remaining.remove(index);
                        joined = true;
                        break;
                    }
                }
            }
            if (ring.size() >= 3) rings.add(closeRing(ring));
        }
        return rings;
    }

    private static boolean appendIfConnected(List<Long> ring, List<Long> segment) {
        Long ringFirst = ring.get(0);
        Long ringLast = ring.get(ring.size() - 1);
        Long segmentFirst = segment.get(0);
        Long segmentLast = segment.get(segment.size() - 1);
        if (ringLast.equals(segmentFirst)) {
            ring.addAll(segment.subList(1, segment.size()));
            return true;
        }
        if (ringLast.equals(segmentLast)) {
            List<Long> reversed = new ArrayList<>(segment);
            Collections.reverse(reversed);
            ring.addAll(reversed.subList(1, reversed.size()));
            return true;
        }
        if (ringFirst.equals(segmentLast)) {
            ring.addAll(0, segment.subList(0, segment.size() - 1));
            return true;
        }
        if (ringFirst.equals(segmentFirst)) {
            List<Long> reversed = new ArrayList<>(segment);
            Collections.reverse(reversed);
            ring.addAll(0, reversed.subList(0, reversed.size() - 1));
            return true;
        }
        return false;
    }

    private static boolean isClosed(List<Long> ring) {
        return ring.size() > 2 && ring.get(0).equals(ring.get(ring.size() - 1));
    }

    private static List<Long> closeRing(List<Long> ring) {
        if (isClosed(ring)) return ring;
        List<Long> closed = new ArrayList<>(ring);
        closed.add(ring.get(0));
        return closed;
    }

    private static int buildingLevels(Map<String, String> tags) {
        Double levels = positiveNumber(tags.get("building:levels"));
        if (levels == null) {
            Double height = positiveNumber(tags.get("height"));
            if (height != null) levels = Math.ceil(height / 3.0);
        }
        if (levels == null) return DEFAULT_BUILDING_LEVELS;
        return Math.max(1, Math.min(MAX_BUILDING_LEVELS, (int) Math.round(levels)));
    }

    private static BlockMaterial wallMaterial(Map<String, String> tags) {
        String material = tags.getOrDefault("building:material",
                tags.getOrDefault("material", "")).toLowerCase(Locale.ROOT);
        if (material.contains("steel") || material.contains("metal")) return BlockMaterial.STEEL;
        if (material.contains("brick")) return BlockMaterial.BRICK;
        return BlockMaterial.CONCRETE;
    }

    private static float roadWidth(Map<String, String> tags) {
        Double explicit = positiveNumber(tags.get("width"));
        if (explicit != null) return explicit.floatValue();
        Double lanes = positiveNumber(tags.get("lanes"));
        if (lanes != null) return Math.max(2.0f, lanes.floatValue() * 3.0f);
        String highway = tags.getOrDefault("highway", "").toLowerCase(Locale.ROOT);
        return switch (highway) {
            case "motorway" -> 12.0f;
            case "trunk" -> 10.0f;
            case "primary" -> 9.0f;
            case "secondary" -> 8.0f;
            case "tertiary" -> 7.0f;
            case "residential", "unclassified" -> 6.0f;
            case "service" -> 4.0f;
            case "footway", "cycleway", "path", "pedestrian", "steps" -> 2.0f;
            default -> 5.0f;
        };
    }

    private static Double positiveNumber(String value) {
        if (value == null || value.isBlank()) return null;
        String first = value.split("[;|]", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (first.endsWith("meters")) first = first.substring(0, first.length() - 6).trim();
        else if (first.endsWith("meter")) first = first.substring(0, first.length() - 5).trim();
        else if (first.endsWith("m")) first = first.substring(0, first.length() - 1).trim();
        try {
            double parsed = Double.parseDouble(first);
            return Double.isFinite(parsed) && parsed > 0.0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isPresentTag(Map<String, String> tags, String name) {
        String value = tags.get(name);
        return value != null && !value.isBlank() && !"no".equalsIgnoreCase(value);
    }

    private static Map<String, String> tags(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, tagValue) -> {
            if (key instanceof String name && tagValue != null) {
                result.put(name, String.valueOf(tagValue));
            }
        });
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String name) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
        return (List<Object>) value;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static long longNumber(Object value, String name) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalArgumentException(name + " must be a number");
    }

    private static double doubleNumber(Object value, String name) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(name + " must be a finite number");
    }

    private record RawWay(List<Long> nodeIds, Map<String, String> tags) {
        private RawWay {
            nodeIds = List.copyOf(nodeIds);
            tags = Map.copyOf(tags);
        }
    }

    /** Small strict JSON reader, avoiding a runtime dependency for one API payload. */
    private static final class JsonReader {
        private final String input;
        private int position;

        private JsonReader(String input) {
            this.input = input;
        }

        private Object readDocument() {
            skipWhitespace();
            Object value = readValue();
            skipWhitespace();
            if (position != input.length()) fail("Unexpected trailing data");
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (position >= input.length()) return fail("Unexpected end of JSON");
            return switch (input.charAt(position)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    return fail("Expected object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char current = input.charAt(position++);
                if (current == '"') return result.toString();
                if (current == '\\') {
                    if (position >= input.length()) return fail("Unterminated escape");
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(readUnicode());
                        default -> fail("Invalid escape sequence");
                    }
                } else {
                    if (current < 0x20) return fail("Control character in string");
                    result.append(current);
                }
            }
            return fail("Unterminated string");
        }

        private char readUnicode() {
            if (position + 4 > input.length()) return fail("Incomplete unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) return fail("Invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object readNumber() {
            int start = position;
            if (consume('-')) { /* optional sign */ }
            if (consume('0')) {
                // A single zero is valid; another integer digit is not.
                if (position < input.length() && Character.isDigit(input.charAt(position))) {
                    return fail("Leading zero in number");
                }
            } else {
                readDigits();
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                readDigits();
            }
            if (position < input.length()
                    && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (!consume('+')) consume('-');
                readDigits();
            }
            if (start == position) return fail("Expected JSON value");
            String token = input.substring(start, position);
            try {
                return decimal ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException exception) {
                return fail("Invalid number");
            }
        }

        private void readDigits() {
            int start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
            if (start == position) fail("Expected number digit");
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, position)) return fail("Invalid JSON literal");
            position += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!consume(expected)) fail("Expected '" + expected + "'");
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
        }

        private <T> T fail(String message) {
            throw new IllegalArgumentException(message + " at JSON offset " + position);
        }
    }
}