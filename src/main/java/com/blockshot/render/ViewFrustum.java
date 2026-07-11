package com.blockshot.render;

import com.blockshot.world.Chunk;

/** A horizontal projection of the camera frustum used for chunk culling. */
public final class ViewFrustum {

    private final double cameraX;
    private final double cameraZ;
    private final double forwardX;
    private final double forwardZ;
    private final double rightX;
    private final double rightZ;
    private final double sideSlope;
    private final double farDistance;

    public ViewFrustum(double cameraX, double cameraZ, double yaw, double verticalFovDegrees,
                       double aspect, double farDistance) {
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ) || !Double.isFinite(yaw)) {
            throw new IllegalArgumentException("Camera position and yaw must be finite");
        }
        if (!Double.isFinite(verticalFovDegrees)
                || verticalFovDegrees <= 0 || verticalFovDegrees >= 180) {
            throw new IllegalArgumentException("Vertical field of view must be between 0 and 180 degrees");
        }
        if (!Double.isFinite(aspect) || aspect <= 0) {
            throw new IllegalArgumentException("Aspect ratio must be positive and finite");
        }
        if (!Double.isFinite(farDistance) || farDistance <= 0) {
            throw new IllegalArgumentException("Far distance must be positive and finite");
        }

        this.cameraX = cameraX;
        this.cameraZ = cameraZ;
        double yawRadians = Math.toRadians(yaw);
        forwardX = Math.sin(yawRadians);
        forwardZ = Math.cos(yawRadians);
        rightX = Math.cos(yawRadians);
        rightZ = -Math.sin(yawRadians);
        sideSlope = Math.tan(Math.toRadians(verticalFovDegrees) * 0.5) * aspect;
        this.farDistance = farDistance;
    }

    public boolean intersectsChunk(int chunkX, int chunkZ) {
        double minX = (double) chunkX * Chunk.SIZE;
        double maxX = minX + Chunk.SIZE;
        double minZ = (double) chunkZ * Chunk.SIZE;
        double maxZ = minZ + Chunk.SIZE;

        double minForward = Double.POSITIVE_INFINITY;
        double maxForward = Double.NEGATIVE_INFINITY;
        double maxLeftPlane = Double.NEGATIVE_INFINITY;
        double maxRightPlane = Double.NEGATIVE_INFINITY;

        double[] xs = {minX, maxX};
        double[] zs = {minZ, maxZ};
        for (double x : xs) {
            for (double z : zs) {
                double relativeX = x - cameraX;
                double relativeZ = z - cameraZ;
                double forward = relativeX * forwardX + relativeZ * forwardZ;
                double side = relativeX * rightX + relativeZ * rightZ;

                minForward = Math.min(minForward, forward);
                maxForward = Math.max(maxForward, forward);
                maxLeftPlane = Math.max(maxLeftPlane, forward * sideSlope + side);
                maxRightPlane = Math.max(maxRightPlane, forward * sideSlope - side);
            }
        }

        if (maxForward < 0 || minForward > farDistance) return false;
        return maxLeftPlane >= 0 && maxRightPlane >= 0;
    }
}