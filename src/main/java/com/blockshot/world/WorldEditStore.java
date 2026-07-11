package com.blockshot.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Chunk-indexed overlay for player and replicated edits to a generated world.
 * Per-chunk locking keeps edits and revision changes atomic while snapshots let
 * render and network threads consume the data without sharing mutable state.
 */
public final class WorldEditStore {

    public record ChunkSnapshot(long revision,
                                Map<BlockPos, BlockMaterial> additions,
                                Set<BlockPos> removals) {

        public ChunkSnapshot {
            if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
            additions = Map.copyOf(additions);
            removals = Set.copyOf(removals);
        }
    }

    private static final class ChunkEdits {
        final Map<BlockPos, BlockMaterial> additions = new HashMap<>();
        final Set<BlockPos> removals = new HashSet<>();
        long revision;
    }

    private final ConcurrentMap<Long, ChunkEdits> chunks = new ConcurrentHashMap<>();

    public BlockMaterial resolve(BlockPos pos,
                                 Function<BlockPos, BlockMaterial> generatedLookup) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(generatedLookup, "generatedLookup");
        ChunkEdits edits = chunks.get(pos.chunkKey());
        if (edits != null) {
            synchronized (edits) {
                BlockMaterial addition = edits.additions.get(pos);
                if (addition != null) return addition;
                if (edits.removals.contains(pos)) return null;
            }
        }
        return generatedLookup.apply(pos);
    }

    public boolean place(BlockPos pos, BlockMaterial material,
                         Function<BlockPos, BlockMaterial> generatedLookup) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(generatedLookup, "generatedLookup");
        ChunkEdits edits = chunkFor(pos);
        synchronized (edits) {
            if (edits.additions.containsKey(pos)) return false;
            boolean removed = edits.removals.contains(pos);
            if (!removed && generatedLookup.apply(pos) != null) return false;

            edits.removals.remove(pos);
            edits.additions.put(pos, material);
            edits.revision++;
            return true;
        }
    }

    public BlockMaterial breakBlock(BlockPos pos,
                                    Function<BlockPos, BlockMaterial> generatedLookup) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(generatedLookup, "generatedLookup");
        ChunkEdits edits = chunkFor(pos);
        synchronized (edits) {
            BlockMaterial addition = edits.additions.get(pos);
            if (addition != null) {
                BlockMaterial generated = generatedLookup.apply(pos);
                edits.additions.remove(pos);
                if (generated == null) edits.removals.remove(pos);
                else edits.removals.add(pos);
                edits.revision++;
                return addition;
            }
            if (edits.removals.contains(pos)) return null;

            BlockMaterial generated = generatedLookup.apply(pos);
            if (generated == null) return null;
            edits.removals.add(pos);
            edits.revision++;
            return generated;
        }
    }

    public boolean isRemoved(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        ChunkEdits edits = chunks.get(pos.chunkKey());
        if (edits == null) return false;
        synchronized (edits) {
            return edits.removals.contains(pos);
        }
    }

    public long revision(int chunkX, int chunkZ) {
        ChunkEdits edits = chunks.get(Chunk.key(chunkX, chunkZ));
        if (edits == null) return 0;
        synchronized (edits) {
            return edits.revision;
        }
    }

    public Map<BlockPos, BlockMaterial> additions(int chunkX, int chunkZ) {
        return snapshot(chunkX, chunkZ).additions();
    }

    public Map<BlockPos, BlockMaterial> additionsInChunk(int chunkX, int chunkZ) {
        return additions(chunkX, chunkZ);
    }

    public Map<BlockPos, BlockMaterial> additionsForChunk(int chunkX, int chunkZ) {
        return additions(chunkX, chunkZ);
    }

    public Set<BlockPos> removals(int chunkX, int chunkZ) {
        return snapshot(chunkX, chunkZ).removals();
    }

    public Set<BlockPos> removalsInChunk(int chunkX, int chunkZ) {
        return removals(chunkX, chunkZ);
    }

    public Set<BlockPos> removalsForChunk(int chunkX, int chunkZ) {
        return removals(chunkX, chunkZ);
    }

    public ChunkSnapshot snapshot(int chunkX, int chunkZ) {
        ChunkEdits edits = chunks.get(Chunk.key(chunkX, chunkZ));
        if (edits == null) return new ChunkSnapshot(0, Map.of(), Set.of());
        synchronized (edits) {
            return new ChunkSnapshot(edits.revision, edits.additions, edits.removals);
        }
    }

    public boolean applyReplicatedEdit(BlockPos pos, BlockMaterial material, boolean placed) {
        return placed ? applyReplicatedAddition(pos, material) : applyReplicatedRemoval(pos);
    }

    public boolean applyReplicatedEdit(BlockPos pos, boolean placed, BlockMaterial material) {
        return applyReplicatedEdit(pos, material, placed);
    }

    public boolean applyReplicatedEdit(BlockPos pos, BlockMaterial material) {
        return applyReplicatedAddition(pos, material);
    }

    public boolean applyReplicatedAddition(BlockPos pos, BlockMaterial material) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(material, "material");
        ChunkEdits edits = chunkFor(pos);
        synchronized (edits) {
            BlockMaterial previous = edits.additions.put(pos, material);
            boolean removed = edits.removals.remove(pos);
            boolean changed = previous != material || removed;
            if (changed) edits.revision++;
            return changed;
        }
    }

    public boolean applyReplicatedRemoval(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        ChunkEdits edits = chunkFor(pos);
        synchronized (edits) {
            boolean changed = edits.additions.remove(pos) != null;
            changed |= edits.removals.add(pos);
            if (changed) edits.revision++;
            return changed;
        }
    }

    private ChunkEdits chunkFor(BlockPos pos) {
        return chunks.computeIfAbsent(pos.chunkKey(), ignored -> new ChunkEdits());
    }
}