package com.zuza.rodentcolonies.war;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyManager;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Управление коалициями колоний (Этап 5, rodents_mod_plan.md §7.9, §14):
 * - союз только внутри одного вида и ровно вдвоём;
 * - только против врага, который на 30%+ сильнее или уже воюет с одной из союзниц;
 * - норы союзников не ближе 24 блоков друг к другу и не дальше 64 блоков от цели;
 * - не более одной активной коалиции на измерение;
 * - совместные отряды до 8 бойцов (не более 4 с каждой стороны): одна давит на вход, вторая грабит запасы;
 * - распад через 1–2 игровых дня после исчезновения угрозы или при потере трети состава;
 * - кулдаун 5 игровых дней после распада, возврат в нейтралитет без вражды.
 */
public final class CoalitionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/coalition");

    private CoalitionManager() {
    }

    public static void tickCoalitions(ServerWorld world) {
        RodentConfig cfg = RodentConfig.get();
        if (!cfg.warEnabled || !cfg.coalitionEnabled) {
            return;
        }
        long time = world.getTime();
        int interval = Math.max(1, cfg.conflictTickInterval);

        CoalitionState coalitionState = CoalitionState.get(world);
        ColonyState colonyState = ColonyState.get(world);
        WarState warState = WarState.get(world);

        // 1. Обновление активных коалиций со сдвигом по ID пары (§7.9)
        for (Coalition coalition : coalitionState.getAllCoalitions()) {
            if (!coalition.isActive()) {
                continue;
            }
            if ((time + Math.abs(coalition.getPairKey())) % interval != 0L) {
                continue;
            }

            Colony allyA = colonyState.getColony(coalition.getAllyA());
            Colony allyB = colonyState.getColony(coalition.getAllyB());
            Colony target = colonyState.getColony(coalition.getTargetColonyId());

            if (allyA == null || allyB == null || allyA.isAbandoned() || allyB.isAbandoned()) {
                dissolveCoalition(world, coalition, "союзная колония прекратила существование");
                continue;
            }
            if (!world.isChunkLoaded(allyA.getHomePos()) || !world.isChunkLoaded(allyB.getHomePos())) {
                continue; // Выгруженные чанки не симулируются (§7.7)
            }

            // Союзники не воюют друг с другом всё время союза
            WarConflict internal = warState.getConflict(allyA.getColonyId(), allyB.getColonyId());
            if (internal != null && internal.getEscalation() != Escalation.NEUTRAL) {
                internal.setEscalation(Escalation.NEUTRAL, time);
                internal.setHostilityScore(0);
                warState.markDirty();
            }

            // Проверка потери трети объединённого состава (§7.9)
            int currentPop = allyA.getMemberCount() + allyB.getMemberCount();
            if (coalition.getInitialCombinedPopulation() > 0
                    && currentPop <= (coalition.getInitialCombinedPopulation() * 2) / 3) {
                dissolveCoalition(world, coalition, "потеря трети объединённого состава");
                continue;
            }

            // Проверка исчезновения общей угрозы (враг разбит или заключён мир) (§7.9)
            boolean threatGone = target == null || target.isAbandoned() || target.getMemberCount() == 0;
            if (!threatGone) {
                WarConflict warA = warState.getConflict(allyA.getColonyId(), target.getColonyId());
                WarConflict warB = warState.getConflict(allyB.getColonyId(), target.getColonyId());
                boolean aAtPeace = warA != null && warA.getEscalation() == Escalation.TRUCE;
                boolean bAtPeace = warB != null && warB.getEscalation() == Escalation.TRUCE;
                if (aAtPeace && bAtPeace) {
                    threatGone = true;
                }
            }

            if (threatGone) {
                if (coalition.getThreatGoneSinceTick() < 0L) {
                    coalition.setThreatGoneSinceTick(time);
                    coalitionState.markDirty();
                } else if (time - coalition.getThreatGoneSinceTick() >= 24000L) { // 1–2 игровых дня
                    dissolveCoalition(world, coalition, "общая угроза устранена");
                }
                continue;
            } else {
                coalition.setThreatGoneSinceTick(-1L);
            }

            // Координация совместного отряда (до 8 бойцов: до 4 от каждой союзницы) (§7.9):
            // Ally A давит на вход (SEAL_ENTRANCE), Ally B грабит запасы (RAID_FOOD)
            if (world.isChunkLoaded(target.getHomePos()) && !ColonyManager.isMsptThrottled()) {
                WarManager.launchCoordinatedCoalitionOperation(world, allyA, allyB, target, time);
            }
        }

        // 2. Попытка создания новой коалиции (если в измерении ещё нет активной коалиции)
        if (time % (interval * 5L) == 0L
                && !ColonyManager.isMsptThrottled()
                && coalitionState.countActiveCoalitions() < cfg.coalitionMaxPerDimension) {
            tryFormCoalition(world, colonyState, warState, coalitionState, time);
        }
    }

    private static void tryFormCoalition(
            ServerWorld world,
            ColonyState colonyState,
            WarState warState,
            CoalitionState coalitionState,
            long time
    ) {
        RodentConfig cfg = RodentConfig.get();
        List<Colony> loaded = new ArrayList<>();
        for (Colony c : colonyState.getAllColonies()) {
            if (!c.isAbandoned() && c.getMemberCount() >= 3 && world.isChunkLoaded(c.getHomePos())) {
                loaded.add(c);
            }
        }
        if (loaded.size() < 3) {
            return;
        }

        double minAllyDistSq = (double) cfg.coalitionMinDistance * cfg.coalitionMinDistance;
        double maxSearchDistSq = (double) cfg.warMaxSearchRadiusBlocks * cfg.warMaxSearchRadiusBlocks;

        for (int i = 0; i < loaded.size(); i++) {
            Colony allyA = loaded.get(i);
            for (int j = i + 1; j < loaded.size(); j++) {
                Colony allyB = loaded.get(j);
                // Только один вид (§7.9)
                if (allyA.getSpecies() != allyB.getSpecies()) {
                    continue;
                }
                // Норы союзников не ближе 24 блоков (§7.9)
                double allyDistSq = allyA.getHomePos().getSquaredDistance(allyB.getHomePos());
                if (allyDistSq < minAllyDistSq || allyDistSq > maxSearchDistSq * 2.0D) {
                    continue;
                }
                // Проверка кулдауна после прошлого распада (5 игровых дней)
                Coalition existing = coalitionState.getCoalition(allyA.getColonyId(), allyB.getColonyId());
                if (existing != null && (existing.isActive() || time < existing.getCooldownUntilTick())) {
                    continue;
                }
                // Союзники не должны воевать друг с другом
                WarConflict betweenAllies = warState.getConflict(allyA.getColonyId(), allyB.getColonyId());
                if (betweenAllies != null && betweenAllies.getEscalation().isActiveConflict()) {
                    continue;
                }

                // Ищем общего сильного врага
                for (Colony target : loaded) {
                    if (target.getColonyId() == allyA.getColonyId() || target.getColonyId() == allyB.getColonyId()) {
                        continue;
                    }
                    if (allyA.getHomePos().getSquaredDistance(target.getHomePos()) > maxSearchDistSq
                            || allyB.getHomePos().getSquaredDistance(target.getHomePos()) > maxSearchDistSq) {
                        continue;
                    }

                    WarConflict warA = warState.getConflict(allyA.getColonyId(), target.getColonyId());
                    WarConflict warB = warState.getConflict(allyB.getColonyId(), target.getColonyId());
                    // Союз невозможен, если с целью действует перемирие (§7.9)
                    if ((warA != null && warA.getEscalation() == Escalation.TRUCE && time < warA.getTruceUntilTick())
                            || (warB != null && warB.getEscalation() == Escalation.TRUCE && time < warB.getTruceUntilTick())) {
                        continue;
                    }

                    int maxAllyPop = Math.max(allyA.getMemberCount(), allyB.getMemberCount());
                    boolean strongerEnemy = target.getMemberCount() >= Math.ceil(maxAllyPop * cfg.coalitionMinThreatStrength);
                    boolean alreadyAtWar = (warA != null && warA.getEscalation().isActiveConflict())
                            || (warB != null && warB.getEscalation().isActiveConflict());

                    if (strongerEnemy || alreadyAtWar) {
                        int combinedPop = allyA.getMemberCount() + allyB.getMemberCount();
                        Coalition created = new Coalition(
                                allyA.getColonyId(),
                                allyB.getColonyId(),
                                target.getColonyId(),
                                allyA.getSpecies(),
                                combinedPop,
                                time
                        );
                        coalitionState.putCoalition(created);
                        LOGGER.info("Создана коалиция колоний #{} и #{} ({}) против общего врага #{}",
                                created.getAllyA(), created.getAllyB(), created.getSpecies().getId(), target.getColonyId());
                        return;
                    }
                }
            }
        }
    }

    /**
     * Распад коалиции с установкой 5-дневного кулдауна и возвратом союзников в нейтралитет (§7.9).
     */
    public static boolean dissolveCoalition(ServerWorld world, Coalition coalition, String reason) {
        if (coalition == null || !coalition.isActive()) {
            return false;
        }
        long time = world.getTime();
        long cooldownTicks = (long) RodentConfig.get().coalitionCooldownDays * 24000L;
        coalition.setActive(false);
        coalition.setCooldownUntilTick(time + cooldownTicks);

        // Бывшие союзники возвращаются в нейтралитет без вражды «за предательство» (§7.9)
        WarState warState = WarState.get(world);
        WarConflict between = warState.getConflict(coalition.getAllyA(), coalition.getAllyB());
        if (between != null) {
            between.setEscalation(Escalation.NEUTRAL, time);
            between.setHostilityScore(0);
            warState.markDirty();
        }

        CoalitionState.get(world).markDirty();
        LOGGER.info("Коалиция колоний #{} и #{} распалась ({}), кулдаун {} дней",
                coalition.getAllyA(), coalition.getAllyB(), reason, RodentConfig.get().coalitionCooldownDays);
        return true;
    }
}
