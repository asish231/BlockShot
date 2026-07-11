package com.blockshot.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldEditStoreTest {

    @Test
    void givenGeneratedBlock_whenBroken_thenRemovalTombstoneOverridesGeneration() {
        WorldEditStore edits = new WorldEditStore();
        BlockPos pos = new BlockPos(-17, 4, 33);

        assertEquals(BlockMaterial.BRICK, edits.breakBlock(pos, ignored -> BlockMaterial.BRICK));
        assertNull(edits.resolve(pos, ignored -> BlockMaterial.BRICK));
        assertTrue(edits.isRemoved(pos));
    }

    @Test
    void givenInventory_whenPlacedAndCollected_thenCountsAreEnforced() {
        BlockInventory inventory = new BlockInventory();
        inventory.add(BlockMaterial.GLASS, 2);

        assertTrue(inventory.take(BlockMaterial.GLASS, 1));
        assertEquals(1, inventory.count(BlockMaterial.GLASS));
        assertFalse(inventory.take(BlockMaterial.GLASS, 2));
        assertEquals(1, inventory.count(BlockMaterial.GLASS));
    }

    @Test
    void givenEditInNegativeChunk_whenChanged_thenOnlyThatChunkRevisionAdvances() {
        WorldEditStore edits = new WorldEditStore();
        BlockPos pos = new BlockPos(-17, 5, 33);
        long before = edits.revision(-2, 2);

        assertTrue(edits.place(pos, BlockMaterial.STEEL, ignored -> null));

        assertTrue(edits.revision(-2, 2) > before);
        assertEquals(0, edits.revision(-1, 2));
        assertEquals(BlockMaterial.STEEL, edits.resolve(pos, ignored -> null));
    }
}