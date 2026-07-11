package com.blockshot.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuralSystemTest {

    @Test
    void givenOnlyDiagonalSupport_whenStructureChecked_thenBlockDetaches() {
        Map<BlockPos, BlockMaterial> world = new HashMap<>();
        BlockPos block = new BlockPos(1, 1, 1);
        world.put(block, BlockMaterial.BRICK);
        world.put(new BlockPos(0, 0, 0), BlockMaterial.STEEL);

        StructuralResult result = new StructuralSystem(128)
                .analyze(List.of(block), world::get);

        assertEquals(List.of(block), result.detached());
    }

    @Test
    void givenStrongSupportBelow_whenStructureChecked_thenItStaysAttached() {
        Map<BlockPos, BlockMaterial> world = new HashMap<>();
        BlockPos block = new BlockPos(0, 1, 0);
        world.put(block, BlockMaterial.BRICK);
        world.put(block.below(), BlockMaterial.STEEL);

        StructuralResult result = new StructuralSystem(128)
                .analyze(List.of(block), world::get);

        assertTrue(result.detached().isEmpty());
    }

    @Test
    void givenLoadExceedingSupportStrength_whenChecked_thenConnectedBlocksDetach() {
        Map<BlockPos, BlockMaterial> world = new HashMap<>();
        List<BlockPos> load = List.of(
                new BlockPos(0, 1, 0),
                new BlockPos(0, 2, 0),
                new BlockPos(0, 3, 0),
                new BlockPos(0, 4, 0));
        load.forEach(pos -> world.put(pos, BlockMaterial.CONCRETE));
        world.put(new BlockPos(0, 0, 0), BlockMaterial.WOOD);

        StructuralResult result = new StructuralSystem(128).analyze(load, world::get);

        assertEquals(4, result.detached().size());
        assertTrue(result.totalMass() > result.supportCapacity());
    }

    @Test
    void givenOversizedCollapse_whenChecked_thenWorkIsBounded() {
        Map<BlockPos, BlockMaterial> world = new HashMap<>();
        for (int x = 0; x < 500; x++) {
            world.put(new BlockPos(x, 10, 0), BlockMaterial.BRICK);
        }

        StructuralResult result = new StructuralSystem(64)
                .analyze(world.keySet(), world::get);

        assertTrue(result.detached().size() <= 64);
        assertTrue(result.truncated());
    }
}