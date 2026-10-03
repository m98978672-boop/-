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
 * Хранилище коалиций колоний на измерение (PersistentState / SavedData) (§7.9, §12):
 * не более одной активной коалиции на измерение одновременно.
 */
public class CoalitionState extends PersistentState {
    public static final String KEY = "rodent_colonies_coalitions";
    public static final int DATA_VERSION = 1;

    private int dataVersion = DATA_VERSION;
    private final Map<Long, Coalition> coalitions = new HashMap<>();

    public CoalitionState() {
    }

    public static CoalitionState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                CoalitionState::fromNbt,
                CoalitionState::new,
                KEY
        );
    }

    public void putCoalition(Coalition coalition) {
        coalitions.put(coalition.getPairKey(), coalition);
        markDirty();
    }

    public Coalition getCoalition(int c1, int c2) {
        return coalitions.get(WarConflict.makePairKey(c1, c2));
    }

    public Coalition getActiveCoalitionForAlly(int colonyId) {
        for (Coalition c : coalitions.values()) {
            if (c.isActive() && c.involvesAlly(colonyId)) {
                return c;
            }
        }
        return null;
    }

    public int countActiveCoalitions() {
        int count = 0;
        for (Coalition c : coalitions.values()) {
            if (c.isActive()) {
                count++;
            }
        }
        return count;
    }

    public boolean areAllies(int c1, int c2) {
        Coalition c = getCoalition(c1, c2);
        return c != null && c.areAllies(c1, c2);
    }

    public Collection<Coalition> getAllCoalitions() {
        return coalitions.values();
    }

    public int getDataVersion() {
        return dataVersion;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt("DataVersion", dataVersion);
        NbtList list = new NbtList();
        for (Coalition c : coalitions.values()) {
            list.add(c.toNbt());
        }
        nbt.put("Coalitions", list);
        return nbt;
    }

    public static CoalitionState fromNbt(NbtCompound nbt) {
        CoalitionState state = new CoalitionState();
        state.dataVersion = nbt.contains("DataVersion") ? nbt.getInt("DataVersion") : DATA_VERSION;
        NbtList list = nbt.getList("Coalitions", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            Coalition c = Coalition.fromNbt(list.getCompound(i));
            state.coalitions.put(c.getPairKey(), c);
        }
        return state;
    }
}
