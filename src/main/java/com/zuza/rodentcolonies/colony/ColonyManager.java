package com.zuza.rodentcolonies.colony;

import com.zuza.rodentcolonies.RodentColonies;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.MouseEntity;
import com.zuza.rodentcolonies.entity.RatEntity;
import com.zuza.rodentcolonies.entity.RodentCoatVariant;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Управление жизненным циклом колоний (rodents_mod_plan.md §2, §5, §8.1, §11):
 * - создание колоний и заселение заброшенных нор;
 * - тик со сдвигом (раз в 40 тиков, offset = colonyId % 40);
 * - политика выгруженных чанков (колония в выгруженном чанке полностью замирает);
 * - размножение, голод, порча запаса;
 * - расселение пешком в соседний чанк с созданием филиала при переполнении чанка;
 * - предохранитель по MSPT (> 40 мс — стоп роста, < 25 мс — возврат);
 * - ежедневная сверка нулевого баланса блоков.
 */
public final class ColonyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/colony");
    private static final long ABANDONED_CLEANUP_TICKS = 72000L; // 3 игровых дня (§2)

    private static boolean msptThrottled = false;

    private ColonyManager() {
    }

    public static boolean isMsptThrottled() {
        return msptThrottled;
    }

    /**
     * Обновляет предохранитель производительности по среднему времени тика сервера (§11).
     */
    public static void updateMsptCircuitBreaker(MinecraftServer server) {
        RodentConfig cfg = RodentConfig.get();
        float avgMspt = server.getTickTime();
        if (!msptThrottled && avgMspt > cfg.msptThrottleStopThreshold) {
            msptThrottled = true;
            LOGGER.warn("MSPT сервера ({} мс) превысил порог {} мс: рост колоний, спавн, стройка и новые войны приостановлены",
                    String.format("%.2f", avgMspt), cfg.msptThrottleStopThreshold);
        } else if (msptThrottled && avgMspt < cfg.msptThrottleResumeThreshold) {
            msptThrottled = false;
            LOGGER.info("MSPT сервера ({} мс) вернулся в норму (< {} мс): симуляция колоний возобновлена",
                    String.format("%.2f", avgMspt), cfg.msptThrottleResumeThreshold);
        }
    }

    /**
     * Создаёт новую колонию или занимает подходящую заброшенную нору поблизости (§2).
     */
    public static Colony createOrAdoptColony(ServerWorld world, RodentSpecies species, BlockPos pos) {
        ColonyState state = ColonyState.get(world);
        // Проверяем, нет ли рядом заброшенной норы, которую может занять новая колония (§2, §7.4)
        for (Colony existing : state.getAllColonies()) {
            if (existing.isAbandoned() && existing.getHomePos().isWithinDistance(pos, 24.0D)) {
                Colony adopted = new Colony(state.allocateColonyId(), species, existing.getHomePos(), world.getTime());
                for (BlockPos ch : existing.getChamberPositions()) {
                    adopted.addChamberPosition(ch);
                }
                for (BlockPos en : existing.getEntrances()) {
                    adopted.addEntrance(en);
                }
                adopted.setEntranceBuilt(existing.isEntranceBuilt());
                state.removeColony(existing.getColonyId());
                state.putColony(adopted);
                LOGGER.info("Новая колония #{} ({}) заняла заброшенную нору в {}",
                        adopted.getColonyId(), species.getId(), adopted.getHomePos().toShortString());
                return adopted;
            }
        }

        int id = state.allocateColonyId();
        Colony colony = new Colony(id, species, pos, world.getTime());
        state.putColony(colony);
        return colony;
    }

    /**
     * Плановый тик колоний со сдвигом: раз в 40 тиков, offset = colonyId % 40 (§11).
     */
    public static void tickWorldColonies(ServerWorld world) {
        RodentConfig cfg = RodentConfig.get();
        int interval = Math.max(1, cfg.colonyTickInterval);
        long time = world.getTime();
        ColonyState state = ColonyState.get(world);

        List<Integer> toRemove = new ArrayList<>();
        for (Colony colony : state.getAllColonies()) {
            // Очистка заброшенных нор через 3 игровых дня (§2)
            if (colony.isAbandoned()) {
                if (time - colony.getAbandonedTick() >= ABANDONED_CLEANUP_TICKS) {
                    toRemove.add(colony.getColonyId());
                }
                continue;
            }

            // Тик со сдвигом: offset = colonyId % 40 (§11)
            if ((time + colony.getColonyId()) % interval != 0L) {
                continue;
            }

            // Политика выгруженных чанков (§2): если чанк дома выгружен, колония полностью замирает
            if (!world.isChunkLoaded(colony.getHomePos())) {
                continue;
            }

            tickSingleColony(world, state, colony, time);
        }

        for (int id : toRemove) {
            state.removeColony(id);
            LOGGER.debug("Заброшенная колония #{} вычищена из SavedData по истечении 3 игровых дней", id);
        }

        // Сверка нулевого баланса раз в игровой день (§8.1)
        if (time > 0 && time % 24000L == 0L) {
            verifyDailyZeroSumBalance(world, state);
        }
    }

    private static void tickSingleColony(ServerWorld world, ColonyState state, Colony colony, long time) {
        RodentConfig cfg = RodentConfig.get();
        colony.getThreatMemory().pruneExpired(time);
        colony.isAlertActive(time);

        // 1. Почасовая порча запаса еды (-1% за игровой час = 1000 тиков, §4)
        long gameHour = time / 1000L;
        if (colony.getLastSpoilageHour() != gameHour) {
            colony.setLastSpoilageHour(gameHour);
            colony.getStorage().applyHourlySpoilage();

            // Текущее потребление: детёныши и бойцы едят из запаса (§5)
            float hourlyConsumption = Math.max(0.05F, colony.getMemberCount() * 0.03F);
            if (colony.isInActiveWar()) {
                hourlyConsumption *= 1.5F; // Воюющая колония тратит запас быстрее обычного (§5)
            }
            colony.getStorage().consumeFood(hourlyConsumption);
        }

        // 2. Голод: если запас еды равен нулю, колония теряет 1 особь за 2 игровых дня (§5)
        if (colony.getStorage().getFoodUnits() <= 0.001F) {
            if (time - colony.getLastStarvationTick() >= cfg.starvationLossIntervalTicks) {
                colony.setLastStarvationTick(time);
                applyStarvationLoss(world, colony, time);
            }
        } else {
            colony.setLastStarvationTick(time);
        }

        // 3. Проверка переполнения чанка и расселение пешком (§2)
        int chunkRodents = countLoadedRodentsInChunk(world, new ChunkPos(colony.getHomePos()));
        if (chunkRodents >= cfg.maxRodentsPerChunk && !msptThrottled) {
            tryInitiateBranchMigration(world, state, colony);
        }

        // 4. Автоматическое размножение при выполнении всех условий (§5)
        if (!msptThrottled) {
            tryBreedColony(world, state, colony, chunkRodents, time);
        }

        // 5. Планирование стройки по приоритетам (§6.2)
        if (!msptThrottled) {
            BuildPlanner.ensureNextConstructionSite(colony);
        }

        state.markDirty();
    }

    private static void applyStarvationLoss(ServerWorld world, Colony colony, long time) {
        Iterator<UUID> it = colony.getMemberUuids().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Entity entity = world.getEntity(uuid);
            if (entity instanceof RodentEntity rodent && rodent.isAlive()) {
                // Облезлый окрас для голодной колонии (§6 ассетов)
                rodent.setCoatVariant(RodentCoatVariant.MANGY);
                rodent.damage(world.getDamageSources().starve(), 100.0F);
                LOGGER.debug("Колония #{} потеряла 1 особь от голода", colony.getColonyId());
                return;
            }
        }
    }

    /**
     * Размножение внутри колонии (§5):
     * - в хранилище >= 8 единиц пищи;
     * - в колонии < 60 особей;
     * - в чанке норы < 10 грызунов;
     * - глобальные лимиты (< 800 грызунов и < 24 колоний) не достигнуты;
     * - прошёл cooldown (1 игровой день = 24 000 тиков).
     */
    private static void tryBreedColony(ServerWorld world, ColonyState state, Colony colony, int chunkRodents, long time) {
        RodentConfig cfg = RodentConfig.get();
        if (colony.getStorage().getFoodUnits() < cfg.reproductionFoodThreshold) {
            return;
        }
        if (colony.getMemberCount() >= cfg.maxRodentsPerColony) {
            return;
        }
        if (chunkRodents >= cfg.maxRodentsPerChunk) {
            return;
        }
        if (countTotalRodentsInDimension(world) >= cfg.maxRodentsPerDimension) {
            return;
        }
        if (time - colony.getLastBreedingTick() < cfg.pairBreedingCooldownTicks) {
            return;
        }

        // Нужно минимум 2 взрослых живых члена колонии поблизости
        List<RodentEntity> adults = new ArrayList<>();
        for (UUID uuid : colony.getMemberUuids()) {
            Entity e = world.getEntity(uuid);
            if (e instanceof RodentEntity rodent && rodent.isAlive() && !rodent.isBaby()) {
                adults.add(rodent);
                if (adults.size() >= 2) {
                    break;
                }
            }
        }
        if (adults.size() < 2) {
            return;
        }

        if (!colony.getStorage().consumeFood(cfg.reproductionFoodCost)) {
            return;
        }
        colony.setLastBreedingTick(time);

        RodentEntity parentA = adults.get(0);
        RodentEntity parentB = adults.get(1);
        int range = Math.max(0, cfg.maxLitterSize - cfg.minLitterSize);
        int litter = cfg.minLitterSize + (range > 0 ? world.random.nextInt(range + 1) : 0);
        // Обычно 2 детёныша (§5)
        int allowed = Math.min(litter, cfg.maxRodentsPerColony - colony.getMemberCount());

        for (int i = 0; i < allowed; i++) {
            RodentEntity baby = spawnRodentForColony(world, colony, colony.getHomePos(), true);
            if (baby != null) {
                RodentCoatVariant coat = RodentCoatVariant.inherit(
                        parentA.getCoatVariant(), parentB.getCoatVariant(), world.random);
                baby.setCoatVariant(coat);
            }
        }
    }

    /**
     * При переполнении чанка 1–2 особи физически уходят пешком в соседний подходящий чанк
     * (не телепортируются), роют там вход, строят камеру из общего запаса материалов
     * и остаются частью колонии — так появляется филиал (§2).
     */
    private static void tryInitiateBranchMigration(ServerWorld world, ColonyState state, Colony colony) {
        RodentConfig cfg = RodentConfig.get();
        ChunkPos homeChunk = new ChunkPos(colony.getHomePos());
        Direction dir = Direction.fromHorizontal(world.random.nextInt(4));
        ChunkPos targetChunk = new ChunkPos(homeChunk.x + dir.getOffsetX(), homeChunk.z + dir.getOffsetZ());
        BlockPos candidatePos = new BlockPos(
                targetChunk.getStartX() + 8,
                colony.getHomePos().getY(),
                targetChunk.getStartZ() + 8
        );
        if (!world.isChunkLoaded(candidatePos)) {
            return;
        }
        if (countLoadedRodentsInChunk(world, targetChunk) >= cfg.maxRodentsPerChunk - 2) {
            return;
        }

        BlockPos surfacePos = findWalkableSurfaceNear(world, candidatePos);
        if (surfacePos == null) {
            return;
        }

        colony.addBranchPosition(surfacePos);
        colony.addEntrance(surfacePos);
        state.rebuildChunkCache();

        int assigned = 0;
        for (UUID uuid : colony.getMemberUuids()) {
            Entity e = world.getEntity(uuid);
            if (e instanceof RodentEntity rodent && rodent.isAlive() && !rodent.isBaby()) {
                rodent.setHomePos(surfacePos);
                rodent.getNavigation().startMovingTo(
                        surfacePos.getX() + 0.5D, surfacePos.getY(), surfacePos.getZ() + 0.5D, 1.0D);
                assigned++;
                if (assigned >= 2) {
                    break;
                }
            }
        }
    }

    public static BlockPos findWalkableSurfaceNear(ServerWorld world, BlockPos center) {
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos p = center.up(dy);
            if (world.getBlockState(p).isAir() && world.getBlockState(p.down()).isSolidBlock(world, p.down())) {
                return p;
            }
        }
        return null;
    }

    public static RodentEntity spawnRodentForColony(ServerWorld world, Colony colony, BlockPos pos, boolean baby) {
        RodentEntity entity = colony.getSpecies() == RodentSpecies.RAT
                ? new RatEntity(RodentColonies.RAT_ENTITY_TYPE, world)
                : new MouseEntity(RodentColonies.MOUSE_ENTITY_TYPE, world);

        entity.refreshPositionAndAngles(
                pos.getX() + 0.5D,
                pos.getY(),
                pos.getZ() + 0.5D,
                world.random.nextFloat() * 360.0F,
                0.0F
        );
        entity.setColonyId(colony.getColonyId());
        entity.setHomePos(colony.getHomePos());
        entity.setCoatVariant(world.random.nextBoolean()
                ? RodentCoatVariant.BASE_PRIMARY
                : RodentCoatVariant.BASE_SECONDARY);
        if (baby) {
            entity.setBreedingAge(-RodentConfig.get().babyMaturationTicks);
        }
        if (world.spawnEntity(entity)) {
            colony.addMember(entity.getUuid());
            ColonyState.get(world).markDirty();
            return entity;
        }
        return null;
    }

    public static int countLoadedRodentsInChunk(ServerWorld world, ChunkPos chunkPos) {
        if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return 0;
        }
        Box chunkBox = new Box(
                chunkPos.getStartX(), world.getBottomY(), chunkPos.getStartZ(),
                chunkPos.getEndX() + 1, world.getTopY(), chunkPos.getEndZ() + 1
        );
        return world.getEntitiesByClass(RodentEntity.class, chunkBox, Entity::isAlive).size();
    }

    public static int countTotalRodentsInDimension(ServerWorld world) {
        ColonyState state = ColonyState.get(world);
        int sum = 0;
        for (Colony c : state.getAllColonies()) {
            sum += c.getMemberCount();
        }
        return sum;
    }

    /**
     * Ежедневная сверка нулевого баланса блоков (§8.1):
     * сумма «добыто + захвачено == поставлено + потеряно + остаток в запасе» совпадает до единицы.
     */
    public static void verifyDailyZeroSumBalance(ServerWorld world, ColonyState state) {
        for (Colony colony : state.getAllColonies()) {
            ColonyStorage st = colony.getStorage();
            boolean balanced = st.verifyZeroSumBalance();
            if (!balanced) {
                LOGGER.warn("Нарушение нулевого баланса блоков в колонии #{}: добыто={}, захвачено={}, поставлено={}, потеряно={}, склад={}",
                        colony.getColonyId(),
                        st.getTotalHarvestedBlocks(),
                        st.getTotalCapturedBlocks(),
                        st.getTotalPlacedBlocks(),
                        st.getTotalLostBlocks(),
                        st.getTotalMaterialsInStock());
            } else {
                LOGGER.debug("Сверка нулевого баланса колонии #{}: OK (добыто={}, поставлено={}, склад={})",
                        colony.getColonyId(),
                        st.getTotalHarvestedBlocks(),
                        st.getTotalPlacedBlocks(),
                        st.getTotalMaterialsInStock());
            }
        }
    }
}
