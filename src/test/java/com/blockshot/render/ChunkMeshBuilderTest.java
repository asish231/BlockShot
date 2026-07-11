package com.blockshot.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkMeshBuilderTest {

    @Test
    void givenAdjacentBlocks_whenMeshBuilt_thenSharedFacesAreRemoved() {
        Map<BlockPos, BlockMaterial> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), BlockMaterial.STONE);
        blocks.put(new BlockPos(1, 0, 0), BlockMaterial.STONE);

        ChunkMeshData mesh = ChunkMeshBuilder.build(blocks, blocks::get);

        assertEquals(10, mesh.opaqueFaceCount());
        assertEquals(40, mesh.opaqueVertexCount());
    }

    @Test
    void givenOpaqueBlockBehindWater_whenMeshBuilt_thenPassesStaySeparate() {
        Map<BlockPos, BlockMaterial> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), BlockMaterial.STONE);
        blocks.put(new BlockPos(0, 1, 0), BlockMaterial.WATER);

        ChunkMeshData mesh = ChunkMeshBuilder.build(blocks, blocks::get);

        assertEquals(6, mesh.opaqueFaceCount());
        assertEquals(6, mesh.translucentFaceCount());
    }

    @Test
    void givenCameraFacingNorth_whenChunksTested_thenBehindChunkIsCulled() {
        ViewFrustum frustum = new ViewFrustum(0, 0, 0, 70, 16.0 / 9.0, 160);

        assertTrue(frustum.intersectsChunk(0, 3));
        assertFalse(frustum.intersectsChunk(0, -3));
        assertFalse(frustum.intersectsChunk(20, 20));
    }
}