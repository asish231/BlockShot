package com.blockshot.world;

import com.blockshot.entity.Fish;
import com.blockshot.entity.Npc;
import com.blockshot.entity.Villager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A square region of the world that owns all of its own blocks and entities.
 * Because a chunk owns its data, unloading a distant chunk simply drops the
 * chunk and everything in it, keeping memory bounded on an infinite map.
 */
public final class Chunk {

    public static final int SIZE = 16;

    public final int cx, cz;
    public final Map<BlockPos, BlockMaterial> blocks = new HashMap<>();
    public final List<Box> opaqueBlocks = new ArrayList<>();
    public final List<Box> waterBlocks = new ArrayList<>();
    public final List<Villager> villagers = new ArrayList<>();
    public final List<Fish> fish = new ArrayList<>();
    public final List<Npc> npcs = new ArrayList<>();

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public long key() {
        return key(cx, cz);
    }
}
