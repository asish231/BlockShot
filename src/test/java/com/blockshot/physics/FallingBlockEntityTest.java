package com.blockshot.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import org.junit.jupiter.api.Test;

class FallingBlockEntityTest {

    @Test
    void givenUnsupportedBlock_whenUpdated_thenItFallsAndSettlesOnSurface() {
        FallingBlockEntity block = new FallingBlockEntity(
                new BlockPos(2, 8, -3), BlockMaterial.BRICK);

        for (int i = 0; i < 240 && !block.settled(); i++) {
            block.update(1.0 / 60, (x, z) -> 2);
        }

        assertTrue(block.settled());
        assertEquals(2, block.y(), 1e-9);
        assertEquals(new BlockPos(2, 2, -3), block.landingCell());
        assertTrue(block.impactSpeed() > 0);
    }

    @Test
    void givenEqualImpactSpeed_whenMaterialsDiffer_thenHeavierBlockDealsMoreDamage() {
        FallingBlockEntity wood = new FallingBlockEntity(
                new BlockPos(0, 10, 0), BlockMaterial.WOOD);
        FallingBlockEntity steel = new FallingBlockEntity(
                new BlockPos(0, 10, 0), BlockMaterial.STEEL);

        for (int i = 0; i < 240; i++) {
            wood.update(1.0 / 60, (x, z) -> 0);
            steel.update(1.0 / 60, (x, z) -> 0);
        }

        assertTrue(steel.impactDamage() > wood.impactDamage());
    }
}