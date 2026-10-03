package com.zuza.rodentcolonies.war;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Хранилище конфликтов колоний на измерение (PersistentState / SavedData) (§7.7, §12):
 * хранит строго одну симметричную запись на пару колоний.
 */
public class WarState extends PersistentState {
    public static final String KEY = "rodent_colonies_wars";
    public static final int DATA_VERSION = 1;

    private int dataVersion = DATA_VERSION;
    private final Map<Long, WarConflict> conflicts = new HashMap<>();

    public WarState() {
    }

    public static WarState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                WarState::fromNbt,
                WarState::new,
                KEY
        );
    }

    public WarConflict getOrCreateConflict(int c1, int c2, long currentTick) {
        long key = WarConflict.makePairKey(c1, c2);
        WarConflict conflict = conflicts.get(key);
        if (conflict == null) {
            conflict = new WarConflict(c1, c2, currentTick);
            conflicts.put(key, conflict);
            markDirty();
        }
        return conflict;
    }

    public WarConflict getConflict(int c1, int c2) {
        return conflicts.get(WarConflict.makePairKey(c1, c2));
    }

    public Collection<WarConflict> getAllConflicts() {
        return conflicts.values();
    }

    public int countActiveWars() {
        int count = 0;
        for (WarConflict c : conflicts.values()) {
            if (c.getEscalation().isActiveConflict()) {
                count++;
            }
        }
        return count;
    }

    public int countActiveWarsForColony(int colonyId) {
        int count = 0;
        for (WarConflict c : conflicts.values()) {
            if (c.involves(colonyId) && c.getEscalation().isActiveConflict()) {
                count++;
            }
        }
        return count;
    }

    public int getDataVersion() {
        return dataVersion;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt("DataVersion", dataVersion);
        NbtList list = new NbtList();
        for (WarConflict c : conflicts.values()) {
            list.add(c.toNbt());
        }
        nbt.put("Conflicts", list);
        return nbt;
    }

    public static WarState fromNbt(NbtCompound nbt) {
        WarState state = new WarState();
        state.dataVersion = nbt.contains("DataVersion") ? nbt.getInt("DataVersion") : DATA_VERSION;
        NbtList list = nbt.getList("Conflicts", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            WarConflict c = WarConflict.fromNbt(list.getCompound(i));
            state.conflicts.put(c.getPairKey(), c);
        }
        return state;
    }
}
