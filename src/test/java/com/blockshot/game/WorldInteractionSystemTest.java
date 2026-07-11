package com.blockshot.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockInventory;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import com.blockshot.world.ChunkManager;
import com.blockshot.world.TerrainGenerator;
import org.junit.jupiter.api.Test;

class WorldInteractionSystemTest {

    @Test
    void givenGeneratedSurfaceBlock_whenBroken_thenMaterialIsCollected() {
        ChunkManager chunks = chunks();
        BlockInventory inventory = new BlockInventory();
        WorldInteractionSystem interactions = new WorldInteractionSystem(chunks, inventory);
        BlockPos surface = new BlockPos(0, chunks.columnHeight(0, 0) - 1, 0);
        BlockMaterial material = chunks.blockAt(surface);

        WorldInteractionSystem.BreakResult result = interactions.breakBlock(surface);

        assertTrue(result.changed());
        assertEquals(material, result.material());
        assertEquals(1, inventory.count(material));
        assertNull(chunks.blockAt(surface));
    }

    @Test
    void givenOccupiedTarget_whenPlacementAttempted_thenInventoryIsNotConsumed() {
        ChunkManager chunks = chunks();
        BlockInventory inventory = new BlockInventory();
        inventory.add(BlockMaterial.GLASS, 1);
        WorldInteractionSystem interactions = new WorldInteractionSystem(chunks, inventory);
        int y = (int) chunks.surfaceY(8.5, 8.5);
        BlockPos target = new BlockPos(8, y, 8);

        boolean placed = interactions.placeBlock(target, BlockMaterial.GLASS, ignored -> true);

        assertFalse(placed);
        assertEquals(1, inventory.count(BlockMaterial.GLASS));
        assertNull(chunks.blockAt(target));
    }

    @Test
    void givenUnsupportedSand_whenPlaced_thenItBecomesFallingDebris() {
        ChunkManager chunks = chunks();
        BlockInventory inventory = new BlockInventory();
        inventory.add(BlockMaterial.SAND, 1);
        WorldInteractionSystem interactions = new WorldInteractionSystem(chunks, inventory);
        int ground = (int) chunks.surfaceY(8.5, 8.5);
        BlockPos target = new BlockPos(8, ground + 3, 8);

        assertTrue(interactions.placeBlock(target, BlockMaterial.SAND, ignored -> false));

        assertEquals(0, inventory.count(BlockMaterial.SAND));
        assertNull(chunks.blockAt(target));
        assertEquals(1, interactions.fallingBlocks().size());
    }

    private static ChunkManager chunks() {
        ChunkManager chunks = new ChunkManager(new TerrainGenerator(42L), 0);
        chunks.update(0, 0);
        return chunks;
    }
}