package com.blockshot.physics;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Performs bounded load and support checks for connected groups of blocks. */
public final class StructuralSystem {

    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt(BlockPos::x)
            .thenComparingInt(BlockPos::y)
            .thenComparingInt(BlockPos::z);

    private final int maxBlocks;

    public StructuralSystem(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("maxBlocks must be positive");
        }
        this.maxBlocks = maxBlocks;
    }

    public StructuralResult analyze(
            Collection<BlockPos> candidates,
            Function<BlockPos, BlockMaterial> lookup) {
        if (candidates == null || candidates.isEmpty() || lookup == null) {
            return new StructuralResult(List.of(), 0, 0, false);
        }

        Set<BlockPos> selected = new TreeSet<>(POSITION_ORDER);
        Iterator<BlockPos> iterator = candidates.iterator();
        int candidateWork = 0;
        while (iterator.hasNext() && candidateWork < maxBlocks) {
            BlockPos candidate = iterator.next();
            candidateWork++;
            if (candidate != null) {
                selected.add(candidate);
            }
        }
        boolean truncated = iterator.hasNext();

        Map<BlockPos, BlockMaterial> materials = new HashMap<>();
        Set<BlockPos> remaining = new TreeSet<>(POSITION_ORDER);
        for (BlockPos position : selected) {
            BlockMaterial material = lookup.apply(position);
            if (!isAir(material)) {
                materials.put(position, material);
                remaining.add(position);
            }
        }

        List<BlockPos> detached = new ArrayList<>();
        double detachedMass = 0;
        double detachedCapacity = 0;

        while (!remaining.isEmpty()) {
            List<BlockPos> component = takeComponent(remaining);
            ComponentLoad load = measure(component, materials, lookup);
            if (!load.grounded() && load.mass() > load.capacity()) {
                detached.addAll(component);
                detachedMass += load.mass();
                detachedCapacity += load.capacity();
            }
        }

        detached.sort(POSITION_ORDER);
        return new StructuralResult(detached, detachedMass, detachedCapacity, truncated);
    }

    private static List<BlockPos> takeComponent(Set<BlockPos> remaining) {
        BlockPos first = remaining.iterator().next();
        remaining.remove(first);

        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        List<BlockPos> component = new ArrayList<>();
        pending.add(first);

        while (!pending.isEmpty()) {
            BlockPos position = pending.removeFirst();
            component.add(position);
            for (BlockPos neighbor : neighbors(position)) {
                if (remaining.remove(neighbor)) {
                    pending.addLast(neighbor);
                }
            }
        }
        return component;
    }

    private static ComponentLoad measure(
            List<BlockPos> component,
            Map<BlockPos, BlockMaterial> materials,
            Function<BlockPos, BlockMaterial> lookup) {
        Set<BlockPos> componentPositions = new HashSet<>(component);
        Set<BlockPos> supports = new HashSet<>();
        double mass = 0;
        boolean grounded = false;

        for (BlockPos position : component) {
            mass += materials.get(position).mass();
            grounded |= position.y() <= 0;

            BlockPos below = position.below();
            if (!componentPositions.contains(below)) {
                supports.add(below);
            }
        }

        double capacity = 0;
        for (BlockPos support : supports) {
            BlockMaterial material = lookup.apply(support);
            if (!isAir(material)) {
                capacity += material.supportStrength();
            }
        }
        return new ComponentLoad(mass, capacity, grounded);
    }

    private static List<BlockPos> neighbors(BlockPos position) {
        int x = position.x();
        int y = position.y();
        int z = position.z();
        return List.of(
                new BlockPos(x - 1, y, z),
                new BlockPos(x + 1, y, z),
                new BlockPos(x, y - 1, z),
                new BlockPos(x, y + 1, z),
                new BlockPos(x, y, z - 1),
                new BlockPos(x, y, z + 1));
    }

    private static boolean isAir(BlockMaterial material) {
        return material == null || material == BlockMaterial.AIR;
    }

    private record ComponentLoad(double mass, double capacity, boolean grounded) {
    }
}