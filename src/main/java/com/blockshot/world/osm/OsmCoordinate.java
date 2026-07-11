package com.blockshot.world.osm;

import com.blockshot.world.BlockPos;

/** Converts latitude/longitude to metre-scaled voxel coordinates using Web Mercator. */
public final class OsmCoordinate {

    public static final double EARTH_RADIUS = 6_378_137.0;
    public static final double MAX_MERCATOR_LATITUDE = 85.05112878;

    private final double referenceLatitude;
    private final double referenceLongitude;
    private final double referenceX;
    private final double referenceZ;

    public OsmCoordinate(double referenceLatitude, double referenceLongitude) {
        requireFinite(referenceLatitude, "referenceLatitude");
        requireFinite(referenceLongitude, "referenceLongitude");
        requireLatitude(referenceLatitude);
        requireLongitude(referenceLongitude);
        this.referenceLatitude = referenceLatitude;
        this.referenceLongitude = referenceLongitude;
        this.referenceX = mercatorX(referenceLongitude);
        this.referenceZ = mercatorZ(referenceLatitude);
    }

    public double referenceLatitude() {
        return referenceLatitude;
    }

    public double referenceLongitude() {
        return referenceLongitude;
    }

    /** Projects a GPS coordinate into voxel space relative to this map's reference point. */
    public BlockPos toVoxel(double latitude, double longitude) {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireLatitude(latitude);
        requireLongitude(longitude);
        long x = Math.round(mercatorX(longitude) - referenceX);
        long z = Math.round(mercatorZ(latitude) - referenceZ);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Coordinate is outside voxel integer range");
        }
        return new BlockPos((int) x, 0, (int) z);
    }

    /** Converts a voxel column back to GPS, used to build Overpass bounding boxes. */
    public GeoCoordinate toGeo(double voxelX, double voxelZ) {
        requireFinite(voxelX, "voxelX");
        requireFinite(voxelZ, "voxelZ");
        double projectedX = referenceX + voxelX;
        double projectedZ = referenceZ + voxelZ;
        double longitude = Math.toDegrees(projectedX / EARTH_RADIUS);
        double latitude = Math.toDegrees(2.0 * Math.atan(Math.exp(projectedZ / EARTH_RADIUS))
                - Math.PI / 2.0);
        return new GeoCoordinate(latitude, longitude);
    }

    /** GPS bounds for a square in voxel metres. */
    public GeoBounds boundsForVoxelSquare(int minimumX, int minimumZ, int sizeMeters) {
        if (sizeMeters <= 0) throw new IllegalArgumentException("sizeMeters must be positive");
        GeoCoordinate southWest = toGeo(minimumX, minimumZ);
        GeoCoordinate northEast = toGeo((long) minimumX + sizeMeters, (long) minimumZ + sizeMeters);
        return new GeoBounds(
                Math.min(southWest.latitude(), northEast.latitude()),
                Math.min(southWest.longitude(), northEast.longitude()),
                Math.max(southWest.latitude(), northEast.latitude()),
                Math.max(southWest.longitude(), northEast.longitude()));
    }

    private static double mercatorX(double longitude) {
        return EARTH_RADIUS * Math.toRadians(longitude);
    }

    private static double mercatorZ(double latitude) {
        double clamped = Math.max(-MAX_MERCATOR_LATITUDE,
                Math.min(MAX_MERCATOR_LATITUDE, latitude));
        double radians = Math.toRadians(clamped);
        return EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4.0 + radians / 2.0));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void requireLatitude(double latitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
    }

    private static void requireLongitude(double longitude) {
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }

    public record GeoCoordinate(double latitude, double longitude) {
        public GeoCoordinate {
            requireFinite(latitude, "latitude");
            requireFinite(longitude, "longitude");
        }
    }

    public record GeoBounds(double south, double west, double north, double east) {
        public GeoBounds {
            requireFinite(south, "south");
            requireFinite(west, "west");
            requireFinite(north, "north");
            requireFinite(east, "east");
            if (south > north || west > east) {
                throw new IllegalArgumentException("Bounds must be ordered south/west/north/east");
            }
        }
    }
}