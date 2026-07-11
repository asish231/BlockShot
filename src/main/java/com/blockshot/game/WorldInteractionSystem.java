package com.blockshot.game;

import com.blockshot.physics.FallingBlockEntity;
import com.blockshot.physics.StructuralResult;
import com.blockshot.physics.StructuralSystem;
import com.blockshot.world.BlockInventory;
import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import com.blockshot.world.ChunkManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Coordinates finite inventory, voxel edits, structural collapse and falling debris. */
public final class WorldInteractionSystem {

    private static final int MAX_COLLAPSE_BLOCKS = 128;
    private static final int MAX_SETTLE_HEIGHT = 16;
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt(BlockPos::x)
            .thenComparingInt(BlockPos::y)
            .thenComparingInt(BlockPos::z);
    private static final int[][] NEIGHBOR_OFFSETS = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0},
        {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    private final ChunkManager chunks;
    private final BlockInventory inventory;
    private final StructuralSystem structures = new StructuralSystem(MAX_COLLAPSE_BLOCKS);
    private final List<FallingBlockEntity> fallingBlocks = new ArrayList<>();
    private final ArrayDeque<BlockChange> pendingChanges = new ArrayDeque<>();
    private final Map<BlockPos, Double> accumulatedDamage = new HashMap<>();

    public WorldInteractionSystem(ChunkManager chunks, BlockInventory inventory) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public boolean placeBlock(
            BlockPos position, BlockMaterial material, Predicate<BlockPos> occupiedByEntity) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(occupiedByEntity, "occupiedByEntity");
        if (!material.solid() || material == BlockMaterial.AIR || occupiedByEntity.test(position)) {
            return false;
        }
        if (!inventory.take(material, 1)) return false;
        if (!chunks.placeBlock(position, material)) {
            inventory.add(material, 1);
            return false;
        }

        pendingChanges.addLast(new BlockChange(position, material, true));
        collapseFromSeeds(List.of(position));
        return true;
    }

    public BreakResult breakBlock(BlockPos position) {
        Objects.requireNonNull(position, "position");
        BlockMaterial removed = chunks.breakBlock(position);
        if (removed == null || !removed.solid()) return BreakResult.unchanged(position);

        accumulatedDamage.remove(position);
        if (removed.collectible()) inventory.add(removed, 1);
        List<BlockChange> changes = new ArrayList<>();
        BlockChange primary = new BlockChange(position, removed, false);
        pendingChanges.addLast(primary);
        changes.add(primary);
        changes.addAll(collapseFromSeeds(neighbors(position)));
        return new BreakResult(true, position, removed, List.copyOf(changes));
    }

    /** Applies damage and breaks the block only after its material hardness is exhausted. */
    public BreakResult damageBlock(BlockPos position, double damage) {
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(damage) || damage <= 0) {
            throw new IllegalArgumentException("damage must be finite and positive");
        }
        BlockMaterial material = chunks.blockAt(position);
        if (material == null || !material.solid()) return BreakResult.unchanged(position);
        double total = accumulatedDamage.merge(position, damage, Double::sum);
        if (total + 1e-9 < Math.max(1.0, material.hardness() * 10.0)) {
            return BreakResult.unchanged(position);
        }
        return breakBlock(position);
    }

    /** Advances debris and returns impact events generated during this update. */
    public List<Impact> updateFalling(double dt) {
        List<Impact> impacts = new ArrayList<>();
        for (int index = fallingBlocks.size() - 1; index >= 0; index--) {
            FallingBlockEntity block = fallingBlocks.get(index);
            if (!block.update(dt, chunks)) continue;

            int damage = block.consumeImpactDamage();
            impacts.add(new Impact(block.x() + 0.5, block.y(), block.z() + 0.5,
                    damage, block.material()));
            BlockPos landing = firstFreeLandingCell(block.landingCell());
            if (landing != null && chunks.placeBlock(landing, block.material())) {
                pendingChanges.addLast(new BlockChange(landing, block.material(), true));
            }
            fallingBlocks.remove(index);
        }
        return List.copyOf(impacts);
    }

    public List<FallingBlockEntity> fallingBlocks() {
        return List.copyOf(fallingBlocks);
    }

    public List<BlockChange> drainChanges() {
        if (pendingChanges.isEmpty()) return List.of();
        List<BlockChange> changes = List.copyOf(pendingChanges);
        pendingChanges.clear();
        return changes;
    }

    private List<BlockChange> collapseFromSeeds(Collection<BlockPos> seeds) {
        Set<BlockPos> candidates = collectStructuralCandidates(seeds);
        if (candidates.isEmpty()) return List.of();
        StructuralResult result = structures.analyze(candidates, chunks::blockAt);
        if (result.detached().isEmpty()) return List.of();

        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos position : result.detached()) {
            BlockMaterial material = chunks.breakBlock(position);
            if (material == null || !material.solid()) continue;
            accumulatedDamage.remove(position);
            fallingBlocks.add(new FallingBlockEntity(position, material));
            BlockChange change = new BlockChange(position, material, false);
            pendingChanges.addLast(change);
            changes.add(change);
        }
        return List.copyOf(changes);
    }

    private Set<BlockPos> collectStructuralCandidates(Collection<BlockPos> seeds) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seeds.stream().filter(Objects::nonNull).sorted(POSITION_ORDER).forEach(queue::addLast);
        Set<BlockPos> visited = new LinkedHashSet<>();
        Set<BlockPos> candidates = new LinkedHashSet<>();
        while (!queue.isEmpty() && visited.size() < MAX_COLLAPSE_BLOCKS) {
            BlockPos position = queue.removeFirst();
            if (!visited.add(position)) continue;
            BlockMaterial material = chunks.blockAt(position);
            if (!isStructuralCandidate(position, material)) continue;
            candidates.add(position);
            for (BlockPos neighbor : neighbors(position)) {
                if (!visited.contains(neighbor)) queue.addLast(neighbor);
            }
        }
        return candidates;
    }

    private boolean isStructuralCandidate(BlockPos position, BlockMaterial material) {
        if (material == null || !material.solid()) return false;
        int terrainSurface = chunks.columnHeight(position.x(), position.z());
        return position.y() >= terrainSurface || material.fallsWithGravity();
    }

    private BlockPos firstFreeLandingCell(BlockPos preferred) {
        if (preferred == null) return null;
        for (int offset = 0; offset < MAX_SETTLE_HEIGHT; offset++) {
            BlockPos candidate = preferred.offset(0, offset, 0);
            if (chunks.blockAt(candidate) == null) return candidate;
        }
        return null;
    }

    private static List<BlockPos> neighbors(BlockPos position) {
        List<BlockPos> result = new ArrayList<>(NEIGHBOR_OFFSETS.length);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            result.add(position.offset(offset[0], offset[1], offset[2]));
        }
        return result;
    }

    public record BlockChange(BlockPos position, BlockMaterial material, boolean placed) {
        public BlockChange {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(material, "material");
        }
    }

    public record BreakResult(
            boolean changed, BlockPos position, BlockMaterial material, List<BlockChange> changes) {
        public BreakResult {
            Objects.requireNonNull(position, "position");
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        private static BreakResult unchanged(BlockPos position) {
            return new BreakResult(false, position, null, List.of());
        }
    }

    public record Impact(double x, double y, double z, int damage, BlockMaterial material) {
        public Impact {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || damage < 0) {
                throw new IllegalArgumentException("Impact values are invalid");
            }
            Objects.requireNonNull(material, "material");
        }
    }
}