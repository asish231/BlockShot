package com.blockshot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoxelRaycasterTest {

    @Test
    void givenMultipleBlocksOnRay_whenCast_thenNearestBlockAndAdjacentCellAreReturned() {
        Map<BlockPos, BlockMaterial> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 2), BlockMaterial.STONE);
        blocks.put(new BlockPos(0, 0, 4), BlockMaterial.GOLD);

        VoxelRaycaster.Hit hit = VoxelRaycaster.cast(
                0.5, 0.5, 0.5, 0, 0, 1, 10, blocks::get).orElseThrow();

        assertEquals(new BlockPos(0, 0, 2), hit.block());
        assertEquals(new BlockPos(0, 0, 1), hit.adjacent());
        assertEquals(BlockMaterial.STONE, hit.material());
    }

    @Test
    void givenBlockAtNegativeCoordinate_whenCast_thenFloorCoordinatesAreHandled() {
        Map<BlockPos, BlockMaterial> blocks = Map.of(
                new BlockPos(-3, 1, -1), BlockMaterial.BRICK);

        VoxelRaycaster.Hit hit = VoxelRaycaster.cast(
                -0.2, 1.5, -0.5, -1, 0, 0, 5, blocks::get).orElseThrow();

        assertEquals(new BlockPos(-3, 1, -1), hit.block());
        assertEquals(new BlockPos(-2, 1, -1), hit.adjacent());
    }

    @Test
    void givenNoSolidBlockInReach_whenCast_thenResultIsEmpty() {
        assertTrue(VoxelRaycaster.cast(
                0, 2, 0, 1, 0, 0, 4, ignored -> null).isEmpty());
    }
}