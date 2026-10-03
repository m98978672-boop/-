package com.zuza.rodentcolonies.colony;

import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Память колонии об угрозах (rodents_mod_plan.md §6.8):
 * до 16 записей (позиция, тип, время) со сроком жизни 2 игровых дня (48 000 тиков).
 * Хранится в данных колонии, а не в NBT отдельной особи.
 */
public class ThreatMemory {
    public record ThreatEntry(BlockPos pos, String threatType, long tickRecorded) {
        public NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putLong("Pos", pos.asLong());
            tag.putString("Type", threatType);
            tag.putLong("Tick", tickRecorded);
            return tag;
        }

        public static ThreatEntry fromNbt(NbtCompound tag) {
            return new ThreatEntry(
                    BlockPos.fromLong(tag.getLong("Pos")),
                    tag.getString("Type"),
                    tag.getLong("Tick")
            );
        }
    }

    private final List<ThreatEntry> entries = new ArrayList<>();

    public void recordThreat(BlockPos pos, String threatType, long currentTick) {
        pruneExpired(currentTick);
        entries.removeIf(e -> e.pos().isWithinDistance(pos, 4.0D) && e.threatType().equals(threatType));
        int maxEntries = RodentConfig.get().maxThreatMemoryEntries;
        while (entries.size() >= maxEntries && !entries.isEmpty()) {
            entries.remove(0);
        }
        entries.add(new ThreatEntry(pos.toImmutable(), threatType, currentTick));
    }

    public void pruneExpired(long currentTick) {
        long duration = RodentConfig.get().threatMemoryDurationTicks;
        Iterator<ThreatEntry> it = entries.iterator();
        while (it.hasNext()) {
            ThreatEntry entry = it.next();
            if (currentTick - entry.tickRecorded() > duration) {
                it.remove();
            }
        }
    }

    public boolean isDangerousNear(BlockPos pos, double radius, long currentTick) {
        pruneExpired(currentTick);
        for (ThreatEntry e : entries) {
            if (e.pos().isWithinDistance(pos, radius)) {
                return true;
            }
        }
        return false;
    }

    public ThreatEntry getNearestThreat(BlockPos pos, double maxRadius, long currentTick) {
        pruneExpired(currentTick);
        ThreatEntry nearest = null;
        double bestDistSq = maxRadius * maxRadius;
        for (ThreatEntry e : entries) {
            double d = e.pos().getSquaredDistance(pos);
            if (d <= bestDistSq) {
                bestDistSq = d;
                nearest = e;
            }
        }
        return nearest;
    }

    public List<ThreatEntry> getEntries() {
        return List.copyOf(entries);
    }

    public NbtList toNbt() {
        NbtList list = new NbtList();
        for (ThreatEntry entry : entries) {
            list.add(entry.toNbt());
        }
        return list;
    }

    public void readNbt(NbtList list) {
        entries.clear();
        for (int i = 0; i < list.size(); i++) {
            entries.add(ThreatEntry.fromNbt(list.getCompound(i)));
        }
    }
}
