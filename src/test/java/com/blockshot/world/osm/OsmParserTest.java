package com.blockshot.world.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.blockshot.world.BlockMaterial;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class OsmParserTest {

    private final OsmCoordinate projection = new OsmCoordinate(0.0, 0.0);
    private final OsmParser parser = new OsmParser(projection);

    @Test
    void givenOverpassWays_whenParsed_thenTypedFeaturesRetainTagsAndGeometry() {
        String json = "{\"elements\":["
                + node(1, 0, 0) + "," + node(2, 8, 0) + ","
                + node(3, 8, 8) + "," + node(4, 0, 8) + ","
                + node(5, 20, 0) + "," + node(6, 30, 0) + ","
                + node(7, 40, 0) + "," + node(8, 46, 0) + ","
                + node(9, 46, 6) + "," + node(10, 40, 6) + ","
                + node(11, 50, 0) + "," + node(12, 56, 0) + ","
                + node(13, 56, 6) + "," + node(14, 50, 6) + ","
                + way(101, "1,2,3,4,1", "\"building\":\"office\",\"building:levels\":\"5\",\"building:material\":\"steel\"") + ","
                + way(102, "5,6", "\"highway\":\"primary\"") + ","
                + way(103, "7,8,9,10,7", "\"natural\":\"water\"") + ","
                + way(104, "11,12,13,14,11", "\"leisure\":\"park\"")
                + "]}";

        List<OsmElement> elements = parser.parse(json);

        assertEquals(4, elements.size());
        OsmElement.OsmBuilding building = assertInstanceOf(OsmElement.OsmBuilding.class, elements.get(0));
        assertEquals(5, building.heightLevels());
        assertEquals(BlockMaterial.STEEL, building.wallMaterial());
        OsmElement.OsmRoad road = assertInstanceOf(OsmElement.OsmRoad.class, elements.get(1));
        assertEquals(9.0f, road.widthMeters());
        assertInstanceOf(OsmElement.OsmWater.class, elements.get(2));
        assertInstanceOf(OsmElement.OsmPark.class, elements.get(3));
    }

    @Test
    void givenBuildingRelation_whenParsed_thenOuterMemberBecomesDefaultBuilding() {
        String json = "{\"elements\":["
                + node(1, -8, -8) + "," + node(2, -2, -8) + ","
                + node(3, -2, -2) + "," + node(4, -8, -2) + ","
                + "{\"type\":\"way\",\"id\":200,\"nodes\":[1,2,3,4,1]},"
                + "{\"type\":\"relation\",\"id\":300,"
                + "\"members\":[{\"type\":\"way\",\"ref\":200,\"role\":\"outer\"}],"
                + "\"tags\":{\"type\":\"multipolygon\",\"building\":\"yes\"}}]}";

        List<OsmElement> elements = parser.parse(json);

        assertEquals(1, elements.size());
        OsmElement.OsmBuilding building = assertInstanceOf(OsmElement.OsmBuilding.class, elements.get(0));
        assertEquals(3, building.heightLevels());
        assertEquals(BlockMaterial.CONCRETE, building.wallMaterial());
    }

    @Test
    void givenMalformedJson_whenParsed_thenItIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"elements\":["));
    }

    private String node(long id, int x, int z) {
        OsmCoordinate.GeoCoordinate coordinate = projection.toGeo(x, z);
        return String.format(Locale.ROOT,
                "{\"type\":\"node\",\"id\":%d,\"lat\":%.12f,\"lon\":%.12f}",
                id, coordinate.latitude(), coordinate.longitude());
    }

    private static String way(long id, String nodes, String tags) {
        return "{\"type\":\"way\",\"id\":" + id + ",\"nodes\":[" + nodes
                + "],\"tags\":{" + tags + "}}";
    }
}