package com.zuza.rodentcolonies.colony;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Хранилище колоний на измерение (PersistentState / SavedData) (§11, §12).
 * Включает поле dataVersion для миграций и кэш «колония -> нора -> чанк» по ChunkPos.
 */
public class ColonyState extends PersistentState {
    public static final String KEY = "rodent_colonies_colonies";
    public static final int DATA_VERSION = 1;

    private int dataVersion = DATA_VERSION;
    private int nextColonyId = 1;

    private final Map<Integer, Colony> colonies = new HashMap<>();
    private final Map<Long, List<Integer>> chunkToColonyIds = new HashMap<>();

    // Учёт бюджета изменений по чанкам (§8.2):
    // не более 100 изменённых блоков на чанк за всё время и не более 15% мягких пород чанка
    private final Map<Long, Integer> chunkTotalModifiedBlocks = new HashMap<>();
    private final Map<Long, Integer> chunkSoftExcavatedBlocks = new HashMap<>();

    public ColonyState() {
    }

    public static ColonyState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                ColonyState::fromNbt,
                ColonyState::new,
                KEY
        );
    }

    public int allocateColonyId() {
        int id = nextColonyId++;
        markDirty();
        return id;
    }

    public void putColony(Colony colony) {
        colonies.put(colony.getColonyId(), colony);
        rebuildChunkCache();
        markDirty();
    }

    public void removeColony(int colonyId) {
        if (colonies.remove(colonyId) != null) {
            rebuildChunkCache();
            markDirty();
        }
    }

    public Colony getColony(int colonyId) {
        return colonies.get(colonyId);
    }

    public Collection<Colony> getAllColonies() {
        return colonies.values();
    }

    public int getActiveColonyCount() {
        int count = 0;
        for (Colony c : colonies.values()) {
            if (!c.isAbandoned()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Быстрый поиск колоний в чанке через кэш ChunkPos (§11).
     */
    public List<Colony> getColoniesInChunk(ChunkPos chunkPos) {
        List<Integer> ids = chunkToColonyIds.get(chunkPos.toLong());
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Colony> result = new ArrayList<>(ids.size());
        for (int id : ids) {
            Colony c = colonies.get(id);
            if (c != null) {
                result.add(c);
            }
        }
        return result;
    }

    public void rebuildChunkCache() {
        chunkToColonyIds.clear();
        for (Colony colony : colonies.values()) {
            indexPosForColony(colony.getHomePos(), colony.getColonyId());
            for (BlockPos entrance : colony.getEntrances()) {
                indexPosForColony(entrance, colony.getColonyId());
            }
            for (BlockPos branch : colony.getBranchPositions()) {
                indexPosForColony(branch, colony.getColonyId());
            }
        }
    }

    private void indexPosForColony(BlockPos pos, int colonyId) {
        long chunkKey = new ChunkPos(pos).toLong();
        List<Integer> list = chunkToColonyIds.computeIfAbsent(chunkKey, k -> new ArrayList<>());
        if (!list.contains(colonyId)) {
            list.add(colonyId);
        }
    }

    public int getChunkModifiedBlocks(ChunkPos chunkPos) {
        return chunkTotalModifiedBlocks.getOrDefault(chunkPos.toLong(), 0);
    }

    public int getChunkSoftExcavatedBlocks(ChunkPos chunkPos) {
        return chunkSoftExcavatedBlocks.getOrDefault(chunkPos.toLong(), 0);
    }

    public void recordChunkModification(ChunkPos chunkPos, boolean softExcavation, int delta) {
        long key = chunkPos.toLong();
        int nextTotal = Math.max(0, getChunkModifiedBlocks(chunkPos) + delta);
        chunkTotalModifiedBlocks.put(key, nextTotal);
        if (softExcavation) {
            int nextSoft = Math.max(0, getChunkSoftExcavatedBlocks(chunkPos) + delta);
            chunkSoftExcavatedBlocks.put(key, nextSoft);
        }
        markDirty();
    }

    public int getDataVersion() {
        return dataVersion;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt("DataVersion", dataVersion);
        nbt.putInt("NextColonyId", nextColonyId);

        NbtList colonyList = new NbtList();
        for (Colony colony : colonies.values()) {
            colonyList.add(colony.toNbt());
        }
        nbt.put("Colonies", colonyList);

        NbtList chunkStats = new NbtList();
        for (Map.Entry<Long, Integer> entry : chunkTotalModifiedBlocks.entrySet()) {
            NbtCompound cTag = new NbtCompound();
            cTag.putLong("Chunk", entry.getKey());
            cTag.putInt("Modified", entry.getValue());
            cTag.putInt("SoftExcavated", chunkSoftExcavatedBlocks.getOrDefault(entry.getKey(), 0));
            chunkStats.add(cTag);
        }
        nbt.put("ChunkStats", chunkStats);
        return nbt;
    }

    public static ColonyState fromNbt(NbtCompound nbt) {
        ColonyState state = new ColonyState();
        state.dataVersion = nbt.contains("DataVersion") ? nbt.getInt("DataVersion") : DATA_VERSION;
        state.nextColonyId = Math.max(1, nbt.getInt("NextColonyId"));

        NbtList colonyList = nbt.getList("Colonies", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < colonyList.size(); i++) {
            Colony colony = Colony.fromNbt(colonyList.getCompound(i));
            state.colonies.put(colony.getColonyId(), colony);
            if (colony.getColonyId() >= state.nextColonyId) {
                state.nextColonyId = colony.getColonyId() + 1;
            }
        }

        NbtList chunkStats = nbt.getList("ChunkStats", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < chunkStats.size(); i++) {
            NbtCompound cTag = chunkStats.getCompound(i);
            long chunkKey = cTag.getLong("Chunk");
            state.chunkTotalModifiedBlocks.put(chunkKey, cTag.getInt("Modified"));
            state.chunkSoftExcavatedBlocks.put(chunkKey, cTag.getInt("SoftExcavated"));
        }

        state.rebuildChunkCache();
        return state;
    }
}
