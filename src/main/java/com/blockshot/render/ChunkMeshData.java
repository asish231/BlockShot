package com.blockshot.render;

import java.util.Arrays;
import java.util.Objects;

/**
 * Interleaved vertex data for a chunk's opaque and translucent render passes.
 * Each vertex contains position, normal, and color values in that order.
 */
public final class ChunkMeshData {

    public static final int FLOATS_PER_VERTEX = 10;
    public static final int VERTICES_PER_FACE = 4;

    private final float[] opaqueVertices;
    private final float[] translucentVertices;

    public ChunkMeshData(float[] opaqueVertices, float[] translucentVertices) {
        this.opaqueVertices = validateAndCopy(opaqueVertices, "opaqueVertices");
        this.translucentVertices = validateAndCopy(translucentVertices, "translucentVertices");
    }

    public float[] opaqueVertices() {
        return Arrays.copyOf(opaqueVertices, opaqueVertices.length);
    }

    public float[] translucentVertices() {
        return Arrays.copyOf(translucentVertices, translucentVertices.length);
    }

    public float[] opaqueData() {
        return opaqueVertices();
    }

    public float[] translucentData() {
        return translucentVertices();
    }

    public int opaqueVertexCount() {
        return opaqueVertices.length / FLOATS_PER_VERTEX;
    }

    public int translucentVertexCount() {
        return translucentVertices.length / FLOATS_PER_VERTEX;
    }

    public int opaqueFaceCount() {
        return opaqueVertexCount() / VERTICES_PER_FACE;
    }

    public int translucentFaceCount() {
        return translucentVertexCount() / VERTICES_PER_FACE;
    }

    private static float[] validateAndCopy(float[] vertices, String name) {
        Objects.requireNonNull(vertices, name);
        if (vertices.length % (FLOATS_PER_VERTEX * VERTICES_PER_FACE) != 0) {
            throw new IllegalArgumentException(name + " must contain complete quad faces");
        }
        return Arrays.copyOf(vertices, vertices.length);
    }
}