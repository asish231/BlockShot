package com.blockshot.physics;

import com.blockshot.world.BlockPos;
import java.util.List;

/** Result of one bounded structural analysis pass. */
public record StructuralResult(
        List<BlockPos> detached,
        double totalMass,
        double supportCapacity,
        boolean truncated) {

    public StructuralResult {
        detached = detached == null ? List.of() : List.copyOf(detached);
    }
}