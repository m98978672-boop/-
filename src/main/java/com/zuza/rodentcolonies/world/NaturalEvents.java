package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.colony.ColonyManager;
import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Планировщик природных событий (Этап 3, rodents_mod_plan.md §9):
 * - Проверка каждые 100 тиков (5 с реального времени).
 * - Выбирается один случайный уже загруженный чанк рядом с игроком.
 * - Вероятность события p = 1 / (10 * T), где T — средний интервал в игровых часах:
 *   1. Семена, саженцы, рост растений (в т.ч. восстановление грядок и выгоревших площадок): T = 1 ч (p = 10%)
 *   2. Рыхление почвы и перенос осадка у воды: T = 3 ч (p = 3.3%)
 *   3. Локальное затопление (временное, отводится через 1–2 дня): T = 24 ч (p = 0.42%, по умолчанию выкл.)
 *   4. Выгорание подлеска (только листва/трава/папоротник/сухостой, брёвна НЕ сгорают, <= 24 блока): T = 120 ч (p = 0.083%, по умолчанию выкл.)
 * - Не чаще одного разрушительного события на измерение за игровой день.
 */
public final class NaturalEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/events");

    private record TemporaryFloodCell(BlockPos waterPos, BlockState previousState, long drainAtTick) {
    }

    private static final Map<RegistryKey<World>, Long> LAST_DESTRUCTIVE_EVENT_DAY = new HashMap<>();
    private static final Map<RegistryKey<World>, Deque<BlockPos>> DROPPED_SEED_QUEUE = new HashMap<>();
    private static final Map<RegistryKey<World>, Deque<BlockPos>> BURNED_PATCH_RECOVERY_QUEUE = new HashMap<>();
    private static final Map<RegistryKey<World>, List<TemporaryFloodCell>> ACTIVE_FLOODS = new HashMap<>();

    private NaturalEvents() {
    }

    /**
     * Записывает точку, где грызун обронил семечко при переноске (§4, §9).
     */
    public static void queueDroppedSeedAt(ServerWorld world, BlockPos pos) {
        Deque<BlockPos> queue = DROPPED_SEED_QUEUE.computeIfAbsent(world.getRegistryKey(), k -> new ArrayDeque<>());
        if (queue.size() < 512) {
            queue.addLast(pos.toImmutable());
        }
    }

    public static void tickWorldEvents(ServerWorld world) {
        RodentConfig cfg = RodentConfig.get();
        if (!cfg.naturalEventsEnabled || ColonyManager.isMsptThrottled()) {
            return;
        }
        long time = world.getTime();

        // Своевременный отвод временных затоплений через 1–2 игровых дня (§9)
        if (time % 200L == 0L) {
            drainExpiredFloods(world, time);
        }

        if (time % cfg.naturalEventTickInterval != 0L) {
            return;
        }

        ChunkPos targetChunk = pickLoadedChunkNearPlayer(world);
        if (targetChunk == null) {
            return;
        }

        // Формула §9: p = 1 / (10 * T)
        float pGrowth = probabilityForHours(cfg.eventSeedGrowthHours);
        float pSoil = probabilityForHours(cfg.eventSoilLooseningHours);
        float pFlood = cfg.enableDestructiveFlooding ? probabilityForHours(cfg.eventFloodingHours) : 0.0F;
        float pWildfire = cfg.enableDestructiveWildfire ? probabilityForHours(cfg.eventWildfireHours) : 0.0F;

        float roll = world.random.nextFloat();
        if (roll < pWildfire && canRunDestructiveEventToday(world, time)) {
            if (runUnderstoryWildfire(world, targetChunk, time)) {
                markDestructiveEventToday(world, time);
            }
        } else if (roll < pWildfire + pFlood && canRunDestructiveEventToday(world, time)) {
            if (runTemporaryFlooding(world, targetChunk, time)) {
                markDestructiveEventToday(world, time);
            }
        } else if (roll < pWildfire + pFlood + pSoil) {
            runSoilLooseningAndSediment(world, targetChunk);
        } else if (roll < pWildfire + pFlood + pSoil + pGrowth) {
            runSeedAndPlantGrowth(world, targetChunk);
        }
    }

    private static float probabilityForHours(float gameHours) {
        if (gameHours <= 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, 1.0F / (10.0F * gameHours));
    }

    private static boolean canRunDestructiveEventToday(ServerWorld world, long time) {
        long day = time / 24000L;
        Long prevDay = LAST_DESTRUCTIVE_EVENT_DAY.get(world.getRegistryKey());
        return prevDay == null || prevDay != day;
    }

    private static void markDestructiveEventToday(ServerWorld world, long time) {
        LAST_DESTRUCTIVE_EVENT_DAY.put(world.getRegistryKey(), time / 24000L);
    }

    private static ChunkPos pickLoadedChunkNearPlayer(ServerWorld world) {
        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        ServerPlayerEntity player = players.get(world.random.nextInt(players.size()));
        ChunkPos center = player.getChunkPos();
        int dx = world.random.nextInt(7) - 3;
        int dz = world.random.nextInt(7) - 3;
        ChunkPos chosen = new ChunkPos(center.x + dx, center.z + dz);
        return world.isChunkLoaded(chosen.x, chosen.z) ? chosen : null;
    }

    /**
     * Событие 1: Семена, саженцы, рост растений, восстановление грядок и выгоревших площадок (§9).
     */
    private static void runSeedAndPlantGrowth(ServerWorld world, ChunkPos chunk) {
        // 1. Восстановление выгоревших площадок из очереди (§9)
        Deque<BlockPos> burnedQueue = BURNED_PATCH_RECOVERY_QUEUE.get(world.getRegistryKey());
        if (burnedQueue != null && !burnedQueue.isEmpty()) {
            BlockPos restorePos = burnedQueue.pollFirst();
            if (restorePos != null && world.isChunkLoaded(restorePos)) {
                if (world.getBlockState(restorePos).isAir() && world.getBlockState(restorePos.down()).isIn(BlockTags.DIRT)) {
                    ColonyWorldEditor.applyNaturalEventChange(world, restorePos.down(), Blocks.GRASS_BLOCK.getDefaultState(), "natural_recovery");
                    ColonyWorldEditor.applyNaturalEventChange(world, restorePos, Blocks.GRASS.getDefaultState(), "natural_recovery");
                    return;
                }
            }
        }

        // 2. Прорастание оброненных грызунами семян (§4, §9)
        Deque<BlockPos> seedQueue = DROPPED_SEED_QUEUE.get(world.getRegistryKey());
        if (seedQueue != null && !seedQueue.isEmpty()) {
            BlockPos seedPos = seedQueue.pollFirst();
            if (seedPos != null && world.isChunkLoaded(seedPos)) {
                BlockState below = world.getBlockState(seedPos.down());
                if (world.getBlockState(seedPos).isAir()) {
                    if (below.isOf(Blocks.FARMLAND)) {
                        ColonyWorldEditor.applyNaturalEventChange(world, seedPos, Blocks.WHEAT.getDefaultState(), "natural_seed_sprout");
                        return;
                    } else if (below.isIn(BlockTags.DIRT)) {
                        ColonyWorldEditor.applyNaturalEventChange(world, seedPos, Blocks.GRASS.getDefaultState(), "natural_seed_sprout");
                        return;
                    }
                }
            }
        }

        // 3. Естественный подсев / ускорение роста на случайной точке чанка
        int x = chunk.getStartX() + world.random.nextInt(16);
        int z = chunk.getStartZ() + world.random.nextInt(16);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);
        BlockState below = world.getBlockState(pos.down());

        if (state.getBlock() instanceof CropBlock crop && !crop.isMature(state)) {
            BlockState grown = crop.withAge(Math.min(crop.getMaxAge(), crop.getAge(state) + 1));
            ColonyWorldEditor.applyNaturalEventChange(world, pos, grown, "natural_crop_growth");
        } else if (state.isAir() && below.isOf(Blocks.FARMLAND)) {
            ColonyWorldEditor.applyNaturalEventChange(world, pos, Blocks.WHEAT.getDefaultState(), "natural_farmland_restore");
        } else if (state.isAir() && below.isOf(Blocks.GRASS_BLOCK)) {
            BlockState plant = world.random.nextBoolean() ? Blocks.GRASS.getDefaultState() : Blocks.FERN.getDefaultState();
            ColonyWorldEditor.applyNaturalEventChange(world, pos, plant, "natural_plant_growth");
        }
    }

    /**
     * Событие 2: Рыхление почвы и перенос осадка у воды (§9).
     */
    private static void runSoilLooseningAndSediment(ServerWorld world, ChunkPos chunk) {
        int x = chunk.getStartX() + world.random.nextInt(16);
        int z = chunk.getStartZ() + world.random.nextInt(16);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos groundPos = new BlockPos(x, y, z);
        if (!world.isChunkLoaded(groundPos)) {
            return;
        }
        BlockState ground = world.getBlockState(groundPos);
        boolean nearWater = false;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (world.getFluidState(groundPos.offset(dir)).isIn(FluidTags.WATER)) {
                nearWater = true;
                break;
            }
        }
        if (nearWater && (ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT))) {
            BlockState sediment = world.random.nextBoolean() ? Blocks.CLAY.getDefaultState() : Blocks.SAND.getDefaultState();
            ColonyWorldEditor.applyNaturalEventChange(world, groundPos, sediment, "natural_sediment");
        } else if (ground.isOf(Blocks.COARSE_DIRT)) {
            ColonyWorldEditor.applyNaturalEventChange(world, groundPos, Blocks.DIRT.getDefaultState(), "natural_soil_loosening");
        }
    }

    /**
     * Событие 3: Локальное временное затопление у воды (§9):
     * глубина 1–2 блока, только в природном рельефе, площадь <= 32 блока, отводится через 1–2 игровых дня.
     */
    private static boolean runTemporaryFlooding(ServerWorld world, ChunkPos chunk, long time) {
        RodentConfig cfg = RodentConfig.get();
        int x = chunk.getStartX() + world.random.nextInt(16);
        int z = chunk.getStartZ() + world.random.nextInt(16);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos shore = new BlockPos(x, y, z);
        if (!world.isChunkLoaded(shore) || BuildRules.isInsideVanillaStructure(world, shore)) {
            return false;
        }

        boolean adjacentWater = false;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (world.getFluidState(shore.offset(dir)).isIn(FluidTags.WATER)) {
                adjacentWater = true;
                break;
            }
        }
        if (!adjacentWater) {
            return false;
        }

        long drainDelay = (cfg.floodDrainMinDays + world.random.nextInt(Math.max(1, cfg.floodDrainMaxDays - cfg.floodDrainMinDays + 1))) * 24000L;
        long drainAt = time + drainDelay;
        int maxCells = cfg.maxFloodingBlocksPerEvent;
        int flooded = 0;

        List<TemporaryFloodCell> floodList = ACTIVE_FLOODS.computeIfAbsent(world.getRegistryKey(), k -> new ArrayList<>());

        for (int dx = -2; dx <= 2 && flooded < maxCells; dx++) {
            for (int dz = -2; dz <= 2 && flooded < maxCells; dz++) {
                BlockPos targetGround = shore.add(dx, 0, dz);
                BlockPos above = targetGround.up();
                if (!world.isChunkLoaded(above)) {
                    continue;
                }
                BlockState groundState = world.getBlockState(targetGround);
                BlockState aboveState = world.getBlockState(above);
                if (groundState.getBlock() instanceof FarmlandBlock) {
                    // farmland портится до земли, но не исчезает (§9)
                    ColonyWorldEditor.applyNaturalEventChange(world, targetGround, Blocks.DIRT.getDefaultState(), "natural_flood_soil");
                }
                if (aboveState.isAir() || MaterialTags.isBeddingFoliage(aboveState)) {
                    if (ColonyWorldEditor.applyNaturalEventChange(world, above, Blocks.WATER.getDefaultState(), "natural_flood")) {
                        floodList.add(new TemporaryFloodCell(above.toImmutable(), aboveState, drainAt));
                        flooded++;
                    }
                }
            }
        }
        if (flooded > 0) {
            LOGGER.info("Локальное затопление в {}: {} блоков (отвод через {} тиков)", shore.toShortString(), flooded, drainDelay);
        }
        return flooded > 0;
    }

    private static void drainExpiredFloods(ServerWorld world, long time) {
        List<TemporaryFloodCell> list = ACTIVE_FLOODS.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) {
            return;
        }
        Iterator<TemporaryFloodCell> it = list.iterator();
        while (it.hasNext()) {
            TemporaryFloodCell cell = it.next();
            if (time >= cell.drainAtTick() && world.isChunkLoaded(cell.waterPos())) {
                if (world.getBlockState(cell.waterPos()).isOf(Blocks.WATER)) {
                    BlockState restore = cell.previousState().isAir() ? Blocks.AIR.getDefaultState() : cell.previousState();
                    ColonyWorldEditor.applyNaturalEventChange(world, cell.waterPos(), restore, "natural_flood_drain");
                }
                it.remove();
            }
        }
    }

    /**
     * Событие 4: Выгорание подлеска (§9):
     * - только сухие/жаркие биомы без дождя;
     * - только по листве, траве, папоротнику, цветам и сухостою;
     * - брёвна и стволы НЕ сгорают — дерево остаётся деревом;
     * - площадь не более 24 блоков за событие;
     * - выгоревшая площадка встаёт в очередь на восстановление через событие «рост».
     */
    private static boolean runUnderstoryWildfire(ServerWorld world, ChunkPos chunk, long time) {
        if (world.isRaining() || world.isThundering()) {
            return false;
        }
        int x = chunk.getStartX() + world.random.nextInt(16);
        int z = chunk.getStartZ() + world.random.nextInt(16);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos center = new BlockPos(x, y, z);
        if (!world.isChunkLoaded(center) || BuildRules.isInsideVanillaStructure(world, center)) {
            return false;
        }
        // Только тёплые/сухие биомы
        if (world.getBiome(center).value().getTemperature() < 0.7F) {
            return false;
        }

        int maxBlocks = RodentConfig.get().maxWildfireBlocksPerEvent;
        int burned = 0;
        Deque<BlockPos> recoveryQueue = BURNED_PATCH_RECOVERY_QUEUE.computeIfAbsent(world.getRegistryKey(), k -> new ArrayDeque<>());

        for (int dx = -3; dx <= 3 && burned < maxBlocks; dx++) {
            for (int dy = -1; dy <= 2 && burned < maxBlocks; dy++) {
                for (int dz = -3; dz <= 3 && burned < maxBlocks; dz++) {
                    BlockPos p = center.add(dx, dy, dz);
                    if (!world.isChunkLoaded(p)) {
                        continue;
                    }
                    BlockState st = world.getBlockState(p);
                    // Брёвна и стволы НЕ сгорают никогда (§8.3, §9)
                    if (st.isIn(BlockTags.LOGS) || st.isIn(BlockTags.PLANKS)) {
                        continue;
                    }
                    boolean isUnderstory = MaterialTags.isBeddingFoliage(st)
                            || st.isIn(BlockTags.FLOWERS)
                            || st.isOf(Blocks.DEAD_BUSH);
                    if (isUnderstory) {
                        if (ColonyWorldEditor.applyNaturalEventChange(world, p, Blocks.AIR.getDefaultState(), "natural_wildfire")) {
                            if (world.getBlockState(p.down()).isOf(Blocks.GRASS_BLOCK)) {
                                ColonyWorldEditor.applyNaturalEventChange(world, p.down(), Blocks.COARSE_DIRT.getDefaultState(), "natural_wildfire");
                                recoveryQueue.addLast(p.toImmutable());
                            }
                            burned++;
                        }
                    }
                }
            }
        }
        if (burned > 0) {
            LOGGER.info("Выгорание подлеска в {}: {} блоков травы/листвы (стволы не затронуты, поставлено в очередь на восстановление)",
                    center.toShortString(), burned);
        }
        return burned > 0;
    }
}
