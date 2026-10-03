package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.sound.RodentSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * ЕДИНСТВЕННАЯ точка изменения мира в моде (rodents_mod_guide.md §3.3, §5.2).
 * Строго соблюдает:
 * 1. safeMode и аварийный тормоз /rodentcolonies stop
 * 2. ванильное правило mobGriefing
 * 3. бюджет изменений (колония: 400/день, чанк: 100 всего и <=15% мягких пород, измерение: 8/сек)
 * 4. запреты BuildRules (структуры, постройки игрока, block entities, жидкости)
 * 5. списание/начисление материалов на складе колонии (нулевой баланс блоков)
 * 6. установку блока с минимальными флагами без лишних обновлений соседей
 * 7. запись каждой правки в ChangeJournal
 */
public final class ColonyWorldEditor {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/world_editor");

    private static final Map<RegistryKey<World>, Long> DIMENSION_SECOND_BUCKET = new HashMap<>();
    private static final Map<RegistryKey<World>, Integer> DIMENSION_CHANGES_THIS_SECOND = new HashMap<>();

    private ColonyWorldEditor() {
    }

    private static boolean checkAndConsumeDimensionSecondBudget(ServerWorld world) {
        long secondBucket = world.getTime() / 20L;
        RegistryKey<World> key = world.getRegistryKey();
        Long prevBucket = DIMENSION_SECOND_BUCKET.get(key);
        int count = (prevBucket != null && prevBucket == secondBucket)
                ? DIMENSION_CHANGES_THIS_SECOND.getOrDefault(key, 0)
                : 0;
        if (count >= RodentConfig.get().maxBlockChangesPerDimensionPerSecond) {
            return false;
        }
        DIMENSION_SECOND_BUCKET.put(key, secondBucket);
        DIMENSION_CHANGES_THIS_SECOND.put(key, count + 1);
        return true;
    }

    /**
     * Установка блока колонией со списанием материала со склада (§5.2).
     */
    public static boolean placeBlock(
            ServerWorld world,
            BlockPos pos,
            BlockState newState,
            CarryLoadType materialType,
            Colony colony,
            String reason
    ) {
        RodentConfig cfg = RodentConfig.get();
        // 1. safeMode или /stop
        if (cfg.safeMode || cfg.worldChangesStopped) {
            LOGGER.info("[safeMode/stop] Намерение поставить блок {} в {} (колония #{}, материал {}, причина {})",
                    newState.getBlock(), pos.toShortString(),
                    colony != null ? colony.getColonyId() : 0, materialType.getName(), reason);
            return false;
        }
        // 2. mobGriefing
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return false;
        }
        // 3. Бюджет изменений (колония / чанк / измерение)
        if (colony != null && !colony.canModifyBlockToday(world.getTime())) {
            return false;
        }
        if (!BuildRules.canPlaceAt(world, pos, newState, reason)) {
            return false;
        }
        if (!checkAndConsumeDimensionSecondBudget(world)) {
            return false;
        }
        // 5. Проверка и списание материала со склада колонии
        if (colony != null) {
            if (!colony.getStorage().consumeMaterialForPlacement(materialType)) {
                return false;
            }
        }

        BlockState oldState = world.getBlockState(pos);
        // 7. Установка блока с минимальными флагами (§11)
        boolean changed = world.setBlockState(pos, newState, Block.NOTIFY_LISTENERS);
        if (!changed) {
            if (colony != null) {
                colony.getStorage().refundPlacedMaterial(materialType);
            }
            return false;
        }

        // Ванильный звук материала блока (§9.3 ассетов: питч 1.3, громкость 0.25)
        BlockSoundGroup soundGroup = newState.getSoundGroup();
        world.playSound(null, pos, soundGroup.getPlaceSound(), SoundCategory.NEUTRAL, 0.25F, 1.3F);

        // 8. Запись в журнал
        int colonyId = colony != null ? colony.getColonyId() : 0;
        ChangeJournal.recordChange(world, pos, oldState, newState, colonyId, reason);

        // 9. Учёт в счётчиках бюджета
        if (colony != null) {
            colony.recordBlockChangeToday(world.getTime());
        }
        ColonyState.get(world).recordChunkModification(new ChunkPos(pos), false, 1);
        return true;
    }

    /**
     * Выемка блока грызуном или колонией (§5.2):
     * снятый блок НЕ исчезает — он либо зачисляется на склад колонии (depositImmediately = true),
     * либо передаётся в зубы грызуну для переноски в нору.
     */
    public static CarryLoadType excavateBlock(
            ServerWorld world,
            BlockPos pos,
            Colony colony,
            boolean depositToStorageImmediately,
            String reason
    ) {
        RodentConfig cfg = RodentConfig.get();
        BlockState oldState = world.getBlockState(pos);
        CarryLoadType materialType = CarryLoadType.fromBlockState(oldState);
        if (materialType == CarryLoadType.NONE) {
            return CarryLoadType.NONE;
        }

        // 1. safeMode или /stop
        if (cfg.safeMode || cfg.worldChangesStopped) {
            LOGGER.info("[safeMode/stop] Намерение выгрызть блок {} в {} (колония #{}, материал {}, причина {})",
                    oldState.getBlock(), pos.toShortString(),
                    colony != null ? colony.getColonyId() : 0, materialType.getName(), reason);
            return CarryLoadType.NONE;
        }
        // 2. mobGriefing
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return CarryLoadType.NONE;
        }
        // 3 & 4. Бюджет и запреты BuildRules
        if (colony != null && !colony.canModifyBlockToday(world.getTime())) {
            return CarryLoadType.NONE;
        }
        if (!BuildRules.canExcavateAt(world, pos)) {
            return CarryLoadType.NONE;
        }
        if (!checkAndConsumeDimensionSecondBudget(world)) {
            return CarryLoadType.NONE;
        }

        BlockState airState = Blocks.AIR.getDefaultState();
        boolean changed = world.setBlockState(pos, airState, Block.NOTIFY_LISTENERS);
        if (!changed) {
            return CarryLoadType.NONE;
        }

        // Звук выгрызания блока (§9.3 ассетов): ванильный breakSound (питч 1.2, громкость 0.2) + gnaw слоем на 0.1
        BlockSoundGroup soundGroup = oldState.getSoundGroup();
        world.playSound(null, pos, soundGroup.getBreakSound(), SoundCategory.NEUTRAL, 0.2F, 1.2F);
        world.playSound(null, pos, RodentSounds.GNAW, SoundCategory.NEUTRAL, 0.1F, 1.2F);

        if (colony != null && depositToStorageImmediately) {
            colony.getStorage().depositHarvestedMaterial(materialType, 1);
        }

        int colonyId = colony != null ? colony.getColonyId() : 0;
        ChangeJournal.recordChange(world, pos, oldState, airState, colonyId, reason);

        if (colony != null) {
            colony.recordBlockChangeToday(world.getTime());
        }
        boolean isSoft = MaterialTags.isSoftDiggable(oldState);
        ColonyState.get(world).recordChunkModification(new ChunkPos(pos), isSoft, 1);
        return materialType;
    }

    /**
     * Изменение блока природным событием (Этап 3, §9) с обязательной записью в журнал и проверкой бюджета.
     */
    public static boolean applyNaturalEventChange(
            ServerWorld world,
            BlockPos pos,
            BlockState newState,
            String reason
    ) {
        RodentConfig cfg = RodentConfig.get();
        if (cfg.safeMode || cfg.worldChangesStopped) {
            LOGGER.info("[safeMode/stop] Намерение природного события {} в {} -> {}",
                    reason, pos.toShortString(), newState.getBlock());
            return false;
        }
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        BlockState oldState = world.getBlockState(pos);
        if (oldState.equals(newState) || BuildRules.isForbiddenBlock(oldState)) {
            return false;
        }
        if (!cfg.eventsCanAffectPlayerBuildings
                && (BuildRules.isLikelyPlayerBuildingBlock(oldState)
                || PlayerBlockTracker.isPlayerPlaced(world, pos)
                || BuildRules.isInsideVanillaStructure(world, pos))) {
            return false;
        }
        if (!checkAndConsumeDimensionSecondBudget(world)) {
            return false;
        }
        if (!world.setBlockState(pos, newState, Block.NOTIFY_LISTENERS)) {
            return false;
        }
        ChangeJournal.recordChange(world, pos, oldState, newState, 0, reason);
        return true;
    }
}
