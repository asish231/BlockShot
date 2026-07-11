package com.blockshot.render;

import com.blockshot.world.Chunk;
import com.blockshot.world.ChunkManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Owns the GPU buffers for the chunks currently loaded by a {@link ChunkManager}.
 * All backend operations execute synchronously on the calling thread. Once closed,
 * synchronization and draw calls are harmless no-ops and statistics remain zero.
 */
public final class ChunkRenderer implements AutoCloseable {

    /** Backend boundary used both by the LWJGL implementation and context-free tests. */
    public interface BufferBackend {
        int upload(float[] vertices);

        void draw(int buffer, int vertexCount);

        void delete(int buffer);
    }

    private static final Comparator<Chunk> CHUNK_ORDER = Comparator
            .comparingInt((Chunk chunk) -> chunk.cx)
            .thenComparingInt(chunk -> chunk.cz);

    private final BufferBackend backend;
    private final Map<Long, Mesh> meshes = new HashMap<>();
    private final Set<Integer> ownedBuffers = new HashSet<>();

    private int opaqueDrawCount;
    private int translucentDrawCount;
    private boolean closed;

    /** Creates a renderer backed by OpenGL VBOs. This does not create an OpenGL context. */
    public ChunkRenderer() {
        this(new OpenGlBufferBackend());
    }

    public ChunkRenderer(BufferBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * Reconciles GPU meshes with the manager's currently loaded chunks. Mesh building
     * and every resulting backend operation occur before this method returns.
     */
    public void sync(ChunkManager manager) {
        if (closed) return;
        Objects.requireNonNull(manager, "manager");

        List<Chunk> loaded = new ArrayList<>(manager.loadedChunks());
        loaded.sort(CHUNK_ORDER);
        Map<Long, Chunk> loadedByKey = indexLoadedChunks(loaded);

        List<Mesh> stale = new ArrayList<>();
        meshes.entrySet().removeIf(entry -> {
            if (loadedByKey.containsKey(entry.getKey())) return false;
            stale.add(entry.getValue());
            return true;
        });
        deleteMeshes(stale);

        for (Chunk chunk : loaded) {
            long key = chunk.key();
            long revision = manager.meshRevision(chunk);
            Mesh current = meshes.get(key);
            if (current != null && current.chunk == chunk && current.revision == revision) {
                continue;
            }

            ChunkMeshData data = ChunkMeshBuilder.build(
                    manager.meshBlocks(chunk), manager::blockAt);
            Mesh replacement = uploadMesh(chunk, revision, data);
            Mesh replaced = meshes.put(key, replacement);
            registerBuffers(replacement);
            if (replaced != null) deleteMesh(replaced);
        }
    }

    /** Draws visible opaque chunk buffers. The count statistic is reset for each call. */
    public void drawOpaque(ViewFrustum frustum) {
        opaqueDrawCount = 0;
        if (closed) return;
        Objects.requireNonNull(frustum, "frustum");

        for (Mesh mesh : orderedMeshes()) {
            if (mesh.opaqueBuffer == 0
                    || !frustum.intersectsChunk(mesh.chunk.cx, mesh.chunk.cz)) {
                continue;
            }
            backend.draw(mesh.opaqueBuffer, mesh.opaqueVertexCount);
            opaqueDrawCount++;
        }
    }

    /** Draws visible translucent chunk buffers from farthest to nearest. */
    public void drawTranslucent(ViewFrustum frustum, double cameraX, double cameraZ) {
        translucentDrawCount = 0;
        if (closed) return;
        Objects.requireNonNull(frustum, "frustum");
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ)) {
            throw new IllegalArgumentException("Camera coordinates must be finite");
        }

        List<Mesh> visible = new ArrayList<>();
        for (Mesh mesh : meshes.values()) {
            if (mesh.translucentBuffer != 0
                    && frustum.intersectsChunk(mesh.chunk.cx, mesh.chunk.cz)) {
                visible.add(mesh);
            }
        }
        visible.sort(Comparator
                .comparingDouble((Mesh mesh) -> distanceSquared(mesh, cameraX, cameraZ))
                .reversed()
                .thenComparingInt(mesh -> mesh.chunk.cx)
                .thenComparingInt(mesh -> mesh.chunk.cz));

        for (Mesh mesh : visible) {
            backend.draw(mesh.translucentBuffer, mesh.translucentVertexCount);
            translucentDrawCount++;
        }
    }

    /** Number of bytes occupied by buffers currently owned by this renderer. */
    public long gpuBytes() {
        long bytes = 0;
        for (Mesh mesh : meshes.values()) bytes = Math.addExact(bytes, mesh.gpuBytes);
        return bytes;
    }

    /** Number of loaded chunks for which synchronization state is retained. */
    public int meshCount() {
        return meshes.size();
    }

    /** Number of draw calls issued by the most recent opaque pass. */
    public int opaqueDrawCount() {
        return opaqueDrawCount;
    }

    /** Number of draw calls issued by the most recent translucent pass. */
    public int translucentDrawCount() {
        return translucentDrawCount;
    }

    /** Deletes all owned buffers. Repeated calls are harmless. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        opaqueDrawCount = 0;
        translucentDrawCount = 0;

        List<Mesh> discarded = new ArrayList<>(meshes.values());
        meshes.clear();
        deleteMeshes(discarded);
    }

    private static Map<Long, Chunk> indexLoadedChunks(Collection<Chunk> chunks) {
        Map<Long, Chunk> indexed = new HashMap<>();
        for (Chunk chunk : chunks) {
            Objects.requireNonNull(chunk, "loaded chunk");
            Chunk previous = indexed.put(chunk.key(), chunk);
            if (previous != null && previous != chunk) {
                throw new IllegalStateException("Multiple loaded chunks have the same coordinates");
            }
        }
        return indexed;
    }

    private Mesh uploadMesh(Chunk chunk, long revision, ChunkMeshData data) {
        float[] opaque = data.opaqueVertices();
        float[] translucent = data.translucentVertices();
        Set<Integer> pendingBuffers = new HashSet<>(2);
        int opaqueBuffer = 0;
        int translucentBuffer = 0;

        try {
            if (opaque.length != 0) {
                opaqueBuffer = upload(opaque, pendingBuffers);
            }
            if (translucent.length != 0) {
                translucentBuffer = upload(translucent, pendingBuffers);
            }
        } catch (RuntimeException | Error failure) {
            deletePendingBuffers(pendingBuffers, failure);
            throw failure;
        }

        long gpuBytes = Math.multiplyExact(
                Math.addExact((long) opaque.length, translucent.length), Float.BYTES);
        return new Mesh(chunk, revision,
                opaqueBuffer, data.opaqueVertexCount(),
                translucentBuffer, data.translucentVertexCount(), gpuBytes);
    }

    private int upload(float[] vertices, Set<Integer> pendingBuffers) {
        int buffer = backend.upload(vertices);
        if (buffer <= 0) {
            throw new IllegalStateException("Buffer backend returned a non-positive ID");
        }
        if (ownedBuffers.contains(buffer) || !pendingBuffers.add(buffer)) {
            throw new IllegalStateException("Buffer backend returned an ID that is already in use");
        }
        return buffer;
    }

    private void registerBuffers(Mesh mesh) {
        if (mesh.opaqueBuffer != 0) ownedBuffers.add(mesh.opaqueBuffer);
        if (mesh.translucentBuffer != 0) ownedBuffers.add(mesh.translucentBuffer);
    }

    private List<Mesh> orderedMeshes() {
        List<Mesh> ordered = new ArrayList<>(meshes.values());
        ordered.sort(Comparator
                .comparingInt((Mesh mesh) -> mesh.chunk.cx)
                .thenComparingInt(mesh -> mesh.chunk.cz));
        return ordered;
    }

    private static double distanceSquared(Mesh mesh, double cameraX, double cameraZ) {
        double centerX = (double) mesh.chunk.cx * Chunk.SIZE + Chunk.SIZE * 0.5;
        double centerZ = (double) mesh.chunk.cz * Chunk.SIZE + Chunk.SIZE * 0.5;
        double dx = centerX - cameraX;
        double dz = centerZ - cameraZ;
        return dx * dx + dz * dz;
    }

    private void deleteMeshes(Collection<Mesh> discarded) {
        Throwable failure = null;
        for (Mesh mesh : discarded) {
            failure = deleteBuffer(mesh.opaqueBuffer, failure);
            failure = deleteBuffer(mesh.translucentBuffer, failure);
        }
        rethrow(failure);
    }

    private void deleteMesh(Mesh mesh) {
        deleteMeshes(List.of(mesh));
    }

    private Throwable deleteBuffer(int buffer, Throwable failure) {
        if (buffer == 0 || !ownedBuffers.remove(buffer)) return failure;
        try {
            backend.delete(buffer);
        } catch (RuntimeException | Error deletionFailure) {
            if (failure == null) failure = deletionFailure;
            else failure.addSuppressed(deletionFailure);
        }
        return failure;
    }

    private void deletePendingBuffers(Set<Integer> pendingBuffers, Throwable failure) {
        for (int buffer : pendingBuffers) {
            try {
                backend.delete(buffer);
            } catch (RuntimeException | Error deletionFailure) {
                failure.addSuppressed(deletionFailure);
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) throw runtimeException;
        if (failure instanceof Error error) throw error;
    }

    private static final class Mesh {
        private final Chunk chunk;
        private final long revision;
        private final int opaqueBuffer;
        private final int opaqueVertexCount;
        private final int translucentBuffer;
        private final int translucentVertexCount;
        private final long gpuBytes;

        private Mesh(Chunk chunk, long revision,
                     int opaqueBuffer, int opaqueVertexCount,
                     int translucentBuffer, int translucentVertexCount,
                     long gpuBytes) {
            this.chunk = chunk;
            this.revision = revision;
            this.opaqueBuffer = opaqueBuffer;
            this.opaqueVertexCount = opaqueVertexCount;
            this.translucentBuffer = translucentBuffer;
            this.translucentVertexCount = translucentVertexCount;
            this.gpuBytes = gpuBytes;
        }
    }

    private static final class OpenGlBufferBackend implements BufferBackend {

        private static final int STRIDE_BYTES = ChunkMeshData.FLOATS_PER_VERTEX * Float.BYTES;
        private static final int POSITION_OFFSET = 0;
        private static final int NORMAL_OFFSET = 3 * Float.BYTES;
        private static final int COLOR_OFFSET = 6 * Float.BYTES;

        private final Map<Integer, Integer> vertexCapacities = new HashMap<>();

        @Override
        public int upload(float[] vertices) {
            validateVertices(vertices);

            int buffer = GL15.glGenBuffers();
            if (buffer <= 0) throw new IllegalStateException("OpenGL did not create a buffer");
            boolean uploaded = false;
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
                vertexCapacities.put(buffer, vertices.length / ChunkMeshData.FLOATS_PER_VERTEX);
                uploaded = true;
                return buffer;
            } finally {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                if (!uploaded) GL15.glDeleteBuffers(buffer);
            }
        }

        @Override
        public void draw(int buffer, int vertexCount) {
            int capacity = requireBuffer(buffer);
            if (vertexCount <= 0 || vertexCount % ChunkMeshData.VERTICES_PER_FACE != 0) {
                throw new IllegalArgumentException("vertexCount must contain complete quad faces");
            }
            if (vertexCount > capacity) {
                throw new IllegalArgumentException("vertexCount exceeds the uploaded buffer");
            }

            boolean vertexArrayEnabled = GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY);
            boolean normalArrayEnabled = GL11.glIsEnabled(GL11.GL_NORMAL_ARRAY);
            boolean colorArrayEnabled = GL11.glIsEnabled(GL11.GL_COLOR_ARRAY);
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE_BYTES, POSITION_OFFSET);
                GL11.glNormalPointer(GL11.GL_FLOAT, STRIDE_BYTES, NORMAL_OFFSET);
                GL11.glColorPointer(4, GL11.GL_FLOAT, STRIDE_BYTES, COLOR_OFFSET);
                GL11.glDrawArrays(GL11.GL_QUADS, 0, vertexCount);
            } finally {
                restoreClientState(GL11.GL_COLOR_ARRAY, colorArrayEnabled);
                restoreClientState(GL11.GL_NORMAL_ARRAY, normalArrayEnabled);
                restoreClientState(GL11.GL_VERTEX_ARRAY, vertexArrayEnabled);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }
        }

        @Override
        public void delete(int buffer) {
            requireBuffer(buffer);
            GL15.glDeleteBuffers(buffer);
            vertexCapacities.remove(buffer);
        }

        private int requireBuffer(int buffer) {
            if (buffer <= 0) throw new IllegalArgumentException("buffer must be positive");
            Integer capacity = vertexCapacities.get(buffer);
            if (capacity == null) throw new IllegalArgumentException("Unknown or deleted buffer: " + buffer);
            return capacity;
        }

        private static void validateVertices(float[] vertices) {
            Objects.requireNonNull(vertices, "vertices");
            int floatsPerFace = ChunkMeshData.FLOATS_PER_VERTEX
                    * ChunkMeshData.VERTICES_PER_FACE;
            if (vertices.length == 0 || vertices.length % floatsPerFace != 0) {
                throw new IllegalArgumentException("vertices must contain complete non-empty quad faces");
            }
            for (float value : vertices) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("vertices must contain only finite values");
                }
            }
        }

        private static void restoreClientState(int state, boolean enabled) {
            if (enabled) GL11.glEnableClientState(state);
            else GL11.glDisableClientState(state);
        }
    }
}