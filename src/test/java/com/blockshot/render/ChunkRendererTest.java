package com.blockshot.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockPos;
import com.blockshot.world.ChunkManager;
import com.blockshot.world.TerrainGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkRendererTest {

    @Test
    void givenLoadedEditedAndUnloadedChunks_whenSynced_thenBuffersHaveBoundedLifecycle() {
        FakeBufferBackend backend = new FakeBufferBackend();
        ChunkRenderer renderer = new ChunkRenderer(backend);
        ChunkManager chunks = new ChunkManager(new TerrainGenerator(42L), 0);
        chunks.update(0, 0);

        renderer.sync(chunks);
        int firstCreateCount = backend.created;
        assertTrue(firstCreateCount > 0);
        assertTrue(renderer.gpuBytes() > 0);

        int surface = chunks.columnHeight(0, 0) - 1;
        chunks.breakBlock(new BlockPos(0, surface, 0));
        renderer.sync(chunks);
        assertTrue(backend.created > firstCreateCount);
        assertTrue(backend.deleted > 0, "replaced mesh buffers must be deleted");

        renderer.drawOpaque(new ViewFrustum(0, 0, 0, 70, 16.0 / 9.0, 160));
        assertTrue(backend.drawn > 0);

        chunks.update(1000, 1000);
        renderer.sync(chunks);
        assertTrue(backend.live.size() <= 2, "only the currently loaded chunk may own buffers");

        renderer.close();
        assertEquals(0, backend.live.size());
    }

    private static final class FakeBufferBackend implements ChunkRenderer.BufferBackend {
        private final Set<Integer> live = new HashSet<>();
        private int nextId = 1;
        private int created;
        private int deleted;
        private int drawn;

        @Override
        public int upload(float[] vertices) {
            int id = nextId++;
            live.add(id);
            created++;
            return id;
        }

        @Override
        public void draw(int buffer, int vertexCount) {
            assertTrue(live.contains(buffer));
            assertTrue(vertexCount > 0);
            drawn++;
        }

        @Override
        public void delete(int buffer) {
            if (live.remove(buffer)) deleted++;
        }
    }
}