package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Кольцевой буфер журнала изменений мира в SavedData (rodents_mod_plan.md §8.4, §12):
 * до 50 000 записей на измерение, хранение 30 игровых дней.
 */
public class JournalState extends PersistentState {
    public static final String KEY = "rodent_colonies_journal";
    public static final int DATA_VERSION = 1;

    public record JournalEntry(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            int colonyId,
            long tick,
            String reason,
            int previousOwnerColonyId
    ) {
        public NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putLong("Pos", pos.asLong());
            tag.put("OldState", NbtHelper.fromBlockState(oldState));
            tag.put("NewState", NbtHelper.fromBlockState(newState));
            tag.putInt("ColonyId", colonyId);
            tag.putLong("Tick", tick);
            tag.putString("Reason", reason);
            if (previousOwnerColonyId > 0) {
                tag.putInt("PrevOwner", previousOwnerColonyId);
            }
            return tag;
        }

        public static JournalEntry fromNbt(ServerWorld world, NbtCompound tag) {
            BlockPos pos = BlockPos.fromLong(tag.getLong("Pos"));
            var blockLookup = world.createCommandRegistryWrapper(RegistryKeys.BLOCK);
            BlockState oldState = NbtHelper.toBlockState(blockLookup, tag.getCompound("OldState"));
            BlockState newState = NbtHelper.toBlockState(blockLookup, tag.getCompound("NewState"));
            int colonyId = tag.getInt("ColonyId");
            long tick = tag.getLong("Tick");
            String reason = tag.getString("Reason");
            int prevOwner = tag.contains("PrevOwner") ? tag.getInt("PrevOwner") : -1;
            return new JournalEntry(pos, oldState, newState, colonyId, tick, reason, prevOwner);
        }
    }

    private int dataVersion = DATA_VERSION;
    private final Deque<JournalEntry> entries = new ArrayDeque<>();

    public JournalState() {
    }

    public static JournalState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                nbt -> fromNbt(world, nbt),
                JournalState::new,
                KEY
        );
    }

    public void append(JournalEntry entry, long currentTick) {
        RodentConfig cfg = RodentConfig.get();
        pruneOldEntries(currentTick);
        while (entries.size() >= cfg.journalMaxEntries && !entries.isEmpty()) {
            entries.removeFirst();
        }
        entries.addLast(entry);
        markDirty();
    }

    public void pruneOldEntries(long currentTick) {
        long maxAgeTicks = (long) RodentConfig.get().journalRetentionGameDays * 24000L;
        while (!entries.isEmpty()) {
            JournalEntry first = entries.peekFirst();
            if (first != null && currentTick - first.tick() > maxAgeTicks) {
                entries.removeFirst();
                markDirty();
            } else {
                break;
            }
        }
    }

    public List<JournalEntry> popRecentForColony(int colonyId, int maxCount) {
        List<JournalEntry> popped = new ArrayList<>();
        Iterator<JournalEntry> desc = entries.descendingIterator();
        while (desc.hasNext() && popped.size() < maxCount) {
            JournalEntry entry = desc.next();
            if (entry.colonyId() == colonyId) {
                popped.add(entry);
                desc.remove();
            }
        }
        if (!popped.isEmpty()) {
            markDirty();
        }
        return popped;
    }

    public Deque<JournalEntry> getEntries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public int getDataVersion() {
        return dataVersion;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt("DataVersion", dataVersion);
        NbtList list = new NbtList();
        for (JournalEntry entry : entries) {
            list.add(entry.toNbt());
        }
        nbt.put("Entries", list);
        return nbt;
    }

    public static JournalState fromNbt(ServerWorld world, NbtCompound nbt) {
        JournalState state = new JournalState();
        state.dataVersion = nbt.contains("DataVersion") ? nbt.getInt("DataVersion") : DATA_VERSION;
        NbtList list = nbt.getList("Entries", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            state.entries.addLast(JournalEntry.fromNbt(world, list.getCompound(i)));
        }
        return state;
    }
}
