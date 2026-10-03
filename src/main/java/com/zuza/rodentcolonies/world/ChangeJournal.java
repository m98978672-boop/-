package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Работа с журналом изменений мира и откатом правок (/rodentcolonies rollback и /rodentcolonies journal) (§8.4).
 */
public final class ChangeJournal {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/journal");

    private ChangeJournal() {
    }

    public static void recordChange(
            ServerWorld world,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            int colonyId,
            String reason
    ) {
        recordChange(world, pos, oldState, newState, colonyId, reason, -1);
    }

    public static void recordChange(
            ServerWorld world,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            int colonyId,
            String reason,
            int previousOwnerColonyId
    ) {
        JournalState state = JournalState.get(world);
        JournalState.JournalEntry entry = new JournalState.JournalEntry(
                pos.toImmutable(),
                oldState,
                newState,
                colonyId,
                world.getTime(),
                reason,
                previousOwnerColonyId
        );
        state.append(entry, world.getTime());
    }

    /**
     * Откатывает последние maxCount правок указанной колонии (§8.4):
     * норы засыпаются, участок возвращается к исходному виду, а также отменяется захват чужой норы.
     */
    public static int rollbackColony(ServerWorld world, int colonyId, int maxCount) {
        JournalState journal = JournalState.get(world);
        ColonyState colonyState = ColonyState.get(world);
        Colony colony = colonyState.getColony(colonyId);

        List<JournalState.JournalEntry> entries = journal.popRecentForColony(colonyId, maxCount);
        int restored = 0;

        for (JournalState.JournalEntry entry : entries) {
            // Если это запись о захвате чужой норы (§8.4) — возвращаем филиал/вход прежней колонии
            if ("war_capture".equals(entry.reason()) && entry.previousOwnerColonyId() > 0) {
                if (colony != null) {
                    colony.getBranchPositions().remove(entry.pos());
                    colony.getEntrances().remove(entry.pos());
                }
                Colony prevOwner = colonyState.getColony(entry.previousOwnerColonyId());
                if (prevOwner != null) {
                    prevOwner.setHomePos(entry.pos());
                    prevOwner.addEntrance(entry.pos());
                }
                colonyState.rebuildChunkCache();
                restored++;
                continue;
            }

            if (world.isChunkLoaded(entry.pos())) {
                world.setBlockState(entry.pos(), entry.oldState(), Block.NOTIFY_LISTENERS);
                boolean wasSoftExcavation = entry.newState().isAir() && MaterialTags.isSoftDiggable(entry.oldState());
                colonyState.recordChunkModification(new ChunkPos(entry.pos()), wasSoftExcavation, -1);

                if (colony != null) {
                    if (!entry.newState().isAir() && entry.oldState().isAir()) {
                        CarryLoadType type = CarryLoadType.fromBlockState(entry.newState());
                        colony.getStorage().refundPlacedMaterial(type);
                    }
                }
                restored++;
            }
        }

        LOGGER.info("Откат колонии #{}: восстановлено {} записей", colonyId, restored);
        return restored;
    }

    /**
     * Сводка по журналу для команды /rodentcolonies journal (§8.4).
     */
    public static Map<Integer, Integer> summarizeByColony(ServerWorld world) {
        JournalState journal = JournalState.get(world);
        Map<Integer, Integer> counts = new HashMap<>();
        for (JournalState.JournalEntry entry : journal.getEntries()) {
            counts.merge(entry.colonyId(), 1, Integer::sum);
        }
        return counts;
    }
}
