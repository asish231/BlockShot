package com.blockshot.world;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe counts of block materials carried by a player. */
public final class BlockInventory {

    private final EnumMap<BlockMaterial, Integer> counts = new EnumMap<>(BlockMaterial.class);

    public synchronized void add(BlockMaterial material, int amount) {
        Objects.requireNonNull(material, "material");
        requirePositive(amount);
        int current = counts.getOrDefault(material, 0);
        if (amount > Integer.MAX_VALUE - current) {
            throw new IllegalArgumentException("Inventory count exceeds the supported range");
        }
        counts.put(material, current + amount);
    }

    public synchronized boolean take(BlockMaterial material, int amount) {
        Objects.requireNonNull(material, "material");
        requirePositive(amount);
        int current = counts.getOrDefault(material, 0);
        if (current < amount) return false;

        int remaining = current - amount;
        if (remaining == 0) counts.remove(material);
        else counts.put(material, remaining);
        return true;
    }

    public synchronized int count(BlockMaterial material) {
        Objects.requireNonNull(material, "material");
        return counts.getOrDefault(material, 0);
    }

    public synchronized Map<BlockMaterial, Integer> snapshot() {
        return Map.copyOf(counts);
    }

    private static void requirePositive(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    }
}