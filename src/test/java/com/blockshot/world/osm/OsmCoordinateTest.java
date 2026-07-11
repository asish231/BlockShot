package com.blockshot.world.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockPos;
import org.junit.jupiter.api.Test;

class OsmCoordinateTest {

    @Test
    void givenReferenceCoordinate_whenProjected_thenItMapsToVoxelOrigin() {
        OsmCoordinate projection = new OsmCoordinate(64.1466, -21.9426);

        assertEquals(new BlockPos(0, 0, 0), projection.toVoxel(64.1466, -21.9426));
    }

    @Test
    void givenVoxelCoordinate_whenRoundTripped_thenMetrePositionIsPreserved() {
        OsmCoordinate projection = new OsmCoordinate(51.5074, -0.1278);

        OsmCoordinate.GeoCoordinate gps = projection.toGeo(237, -419);

        assertEquals(new BlockPos(237, 0, -419), projection.toVoxel(gps.latitude(), gps.longitude()));
    }

    @Test
    void givenVoxelSquare_whenBoundsRequested_thenSouthWestAndNorthEastAreOrdered() {
        OsmCoordinate projection = new OsmCoordinate(0.0, 0.0);

        OsmCoordinate.GeoBounds bounds = projection.boundsForVoxelSquare(-512, 256, 512);

        assertTrue(bounds.south() < bounds.north());
        assertTrue(bounds.west() < bounds.east());
        assertEquals(projection.toGeo(-512, 256).longitude(), bounds.west(), 1.0e-10);
        assertEquals(projection.toGeo(0, 768).latitude(), bounds.north(), 1.0e-10);
    }

    @Test
    void givenNonFiniteCoordinate_whenProjected_thenItIsRejected() {
        OsmCoordinate projection = new OsmCoordinate(0.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> projection.toVoxel(Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new OsmCoordinate(0.0, Double.POSITIVE_INFINITY));
    }
}