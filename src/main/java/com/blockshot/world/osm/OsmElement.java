package com.blockshot.world.osm;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.List;
import java.util.Objects;

/** Lightweight vector features parsed from an Overpass response. */
public sealed interface OsmElement permits OsmElement.OsmBuilding, OsmElement.OsmRoad,
        OsmElement.OsmWater, OsmElement.OsmPark {

    record OsmBuilding(List<BlockPos> footprint, int heightLevels,
                       BlockMaterial wallMaterial) implements OsmElement {
        public OsmBuilding {
            footprint = immutableGeometry(footprint, 3, "footprint");
            if (heightLevels <= 0) throw new IllegalArgumentException("heightLevels must be positive");
            Objects.requireNonNull(wallMaterial, "wallMaterial");
        }
    }

    record OsmRoad(List<BlockPos> points, float widthMeters) implements OsmElement {
        public OsmRoad {
            points = immutableGeometry(points, 2, "points");
            if (!Float.isFinite(widthMeters) || widthMeters <= 0.0f) {
                throw new IllegalArgumentException("widthMeters must be finite and positive");
            }
        }
    }

    record OsmWater(List<BlockPos> polygon) implements OsmElement {
        public OsmWater {
            polygon = immutableGeometry(polygon, 3, "polygon");
        }
    }

    record OsmPark(List<BlockPos> polygon) implements OsmElement {
        public OsmPark {
            polygon = immutableGeometry(polygon, 3, "polygon");
        }
    }

    private static List<BlockPos> immutableGeometry(List<BlockPos> geometry, int minimumSize,
                                                     String name) {
        Objects.requireNonNull(geometry, name);
        if (geometry.size() < minimumSize) {
            throw new IllegalArgumentException(name + " must contain at least " + minimumSize + " points");
        }
        if (geometry.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null points");
        }
        return List.copyOf(geometry);
    }
}