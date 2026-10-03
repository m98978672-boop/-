package com.zuza.rodentcolonies.war;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyManager;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.RodentCoatVariant;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.world.ChangeJournal;
import com.zuza.rodentcolonies.world.ColonyWorldEditor;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Управление войнами колоний (Этап 4, rodents_mod_plan.md §7.1–§7.8):
 * - лестница эскалации: Нейтралитет -> Напряжение (1–2 дн.) -> Набеги (1–3 дн.) -> Война (3–10 дн.) -> Захват (1 дн.) -> Перемирие (2–5 дн.);
 * - лимиты: не более 3 активных войн на измерение, не более 2 на колонию, не более 12 активных бойцов одновременно;
 * - потери: не более 2 особей с каждой стороны за столкновение;
 * - сохранность мира: война расходует население, а не мир — только запечатывание входа принесённым материалом и захват готовой норы;
 * - беглецы: выжившие 3+ особи побеждённой колонии могут основать новую колонию.
 */
public final class WarManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/war");

    private static final Map<RegistryKey<World>, List<RaidSquad>> ACTIVE_SQUADS = new HashMap<>();

    private WarManager() {
    }

    public static void tickWorldWars(ServerWorld world) {
        RodentConfig cfg = RodentConfig.get();
        if (!cfg.warEnabled) {
            return;
        }
        long time = world.getTime();

        // 1. Тик боевых отрядов раз в 20 тиков (§7.7)
        if (time % cfg.squadTickInterval == 0L) {
            tickActiveSquads(world, time);
        }

        // 2. Поиск новых соседских контактов в радиусе 64 блоков (раз в 200 тиков)
        if (time % 200L == 0L && !ColonyManager.isMsptThrottled()) {
            discoverNeighborPairs(world, time);
        }

        // 3. Тик конфликтов раз в 40 тиков со сдвигом по ключу пары (§7.7)
        int conflictInterval = Math.max(1, cfg.conflictTickInterval);
        WarState warState = WarState.get(world);
        ColonyState colonyState = ColonyState.get(world);

        for (WarConflict conflict : warState.getAllConflicts()) {
            if ((time + Math.abs(conflict.getPairKey())) % conflictInterval != 0L) {
                continue;
            }
            Colony cA = colonyState.getColony(conflict.getColonyA());
            Colony cB = colonyState.getColony(conflict.getColonyB());
            if (cA == null || cB == null || cA.isAbandoned() || cB.isAbandoned()) {
                if (conflict.getEscalation() != Escalation.NEUTRAL) {
                    conflict.setEscalation(Escalation.NEUTRAL, time);
                    warState.markDirty();
                }
                continue;
            }

            // Война не грузит чанки: если одна сторона выгружена, конфликт замирает целиком (§7.7)
            if (!world.isChunkLoaded(cA.getHomePos()) || !world.isChunkLoaded(cB.getHomePos())) {
                // Враждебность постепенно затухает при отсутствии контакта (§7.2)
                conflict.addHostility(-1);
                continue;
            }

            tickSingleConflict(world, warState, conflict, cA, cB, time);
        }
    }

    private static void discoverNeighborPairs(ServerWorld world, long time) {
        RodentConfig cfg = RodentConfig.get();
        ColonyState colonyState = ColonyState.get(world);
        WarState warState = WarState.get(world);
        CoalitionState coalitionState = CoalitionState.get(world);

        List<Colony> loaded = new ArrayList<>();
        for (Colony c : colonyState.getAllColonies()) {
            if (!c.isAbandoned() && world.isChunkLoaded(c.getHomePos())) {
                loaded.add(c);
            }
        }

        double maxDistSq = (double) cfg.warMaxSearchRadiusBlocks * cfg.warMaxSearchRadiusBlocks;
        for (int i = 0; i < loaded.size(); i++) {
            Colony a = loaded.get(i);
            for (int j = i + 1; j < loaded.size(); j++) {
                Colony b = loaded.get(j);
                if (coalitionState.areAllies(a.getColonyId(), b.getColonyId())) {
                    continue;
                }
                double distSq = a.getHomePos().getSquaredDistance(b.getHomePos());
                if (distSq <= maxDistSq) {
                    warState.getOrCreateConflict(a.getColonyId(), b.getColonyId(), time);
                }
            }
        }
    }

    private static void tickSingleConflict(
            ServerWorld world,
            WarState warState,
            WarConflict conflict,
            Colony cA,
            Colony cB,
            long time
    ) {
        RodentConfig cfg = RodentConfig.get();
        CoalitionState coalitionState = CoalitionState.get(world);
        if (coalitionState.areAllies(cA.getColonyId(), cB.getColonyId())) {
            conflict.setEscalation(Escalation.NEUTRAL, time);
            conflict.setHostilityScore(0);
            cA.setInActiveWar(false);
            cB.setInActiveWar(false);
            warState.markDirty();
            return;
        }

        double dist = Math.sqrt(cA.getHomePos().getSquaredDistance(cB.getHomePos()));
        if (dist > cfg.warMaxSearchRadiusBlocks) {
            // Дальше 64 блоков колонии воевать не могут (§7.7)
            conflict.addHostility(-5);
            if (conflict.getEscalation().isActiveConflict()) {
                enterTruce(world, warState, conflict, cA, cB, time);
            }
            return;
        }

        // Обновление счётчика враждебности по причинам §7.2:
        // 1. Норы ближе 16 блоков или пересекаются зоны добычи (<= 36 блоков)
        int hostilityDelta = 0;
        if (dist <= cfg.warProximityThresholdBlocks) {
            hostilityDelta += 4;
        } else if (dist <= cfg.maxMaterialHarvestRange * 1.5D) {
            hostilityDelta += 2;
        } else {
            hostilityDelta -= 1;
        }
        // 2. Видовая вражда: крысы против мышей (§7.1)
        if (cA.getSpecies() != cB.getSpecies()) {
            hostilityDelta += 2;
        }
        // 3. Оппортунизм: игрок убил 5+ особей одной из колоний (§7.2)
        if (cA.getPlayerKillsCount() >= 5 || cB.getPlayerKillsCount() >= 5) {
            hostilityDelta += 3;
        }
        // 4. Теснота в чанке: одна колония упёрлась в лимит чанка (§7.2)
        if (ColonyManager.countLoadedRodentsInChunk(world, new ChunkPos(cA.getHomePos())) >= cfg.maxRodentsPerChunk
                || ColonyManager.countLoadedRodentsInChunk(world, new ChunkPos(cB.getHomePos())) >= cfg.maxRodentsPerChunk) {
            hostilityDelta += 2;
        }

        conflict.addHostility(hostilityDelta);
        long stageElapsed = time - conflict.getStageStartedTick();

        switch (conflict.getEscalation()) {
            case TRUCE -> {
                cA.setInActiveWar(false);
                cB.setInActiveWar(false);
                if (time >= conflict.getTruceUntilTick()) {
                    conflict.setEscalation(Escalation.NEUTRAL, time);
                    conflict.setHostilityScore(0);
                    warState.markDirty();
                }
            }
            case NEUTRAL -> {
                cA.setInActiveWar(false);
                cB.setInActiveWar(false);
                if (time >= conflict.getCooldownUntilTick()
                        && conflict.getHostilityScore() >= 20
                        && !ColonyManager.isMsptThrottled()) {
                    conflict.setEscalation(Escalation.TENSION, time);
                    warState.markDirty();
                    LOGGER.debug("Напряжение между колониями #{} и #{}", cA.getColonyId(), cB.getColonyId());
                }
            }
            case TENSION -> {
                if (conflict.getHostilityScore() <= 5) {
                    conflict.setEscalation(Escalation.NEUTRAL, time);
                    warState.markDirty();
                } else if (stageElapsed >= 24000L && conflict.getHostilityScore() >= 45 && !ColonyManager.isMsptThrottled()) {
                    // Напряжение 1–2 игровых дня -> Набеги (§7.2)
                    conflict.setEscalation(Escalation.RAIDS, time);
                    warState.markDirty();
                    LOGGER.info("Набеги между колониями #{} и #{}", cA.getColonyId(), cB.getColonyId());
                }
            }
            case RAIDS -> {
                if (conflict.getHostilityScore() <= 10) {
                    conflict.setEscalation(Escalation.TENSION, time);
                    warState.markDirty();
                    break;
                }
                // Запуск набега за едой (отряд 2–4 особи)
                if (!ColonyManager.isMsptThrottled()) {
                    Colony raider = selectAttacker(cA, cB);
                    Colony victim = raider == cA ? cB : cA;
                    spawnSquadIfAllowed(world, raider, victim, RaidSquad.SquadObjective.RAID_FOOD, 4, time);
                }
                // Через 1–3 игровых дня при высокой враждебности и наличии слотов -> открытая Война (§7.2, §7.5)
                if (stageElapsed >= 24000L
                        && conflict.getHostilityScore() >= 80
                        && !ColonyManager.isMsptThrottled()
                        && canStartActiveWar(warState, cA.getColonyId(), cB.getColonyId())) {
                    conflict.setEscalation(Escalation.WAR, time);
                    cA.setInActiveWar(true);
                    cB.setInActiveWar(true);
                    warState.markDirty();
                    LOGGER.info("Началась война между колониями #{} ({}) и #{} ({})",
                            cA.getColonyId(), cA.getSpecies().getId(), cB.getColonyId(), cB.getSpecies().getId());
                }
            }
            case WAR -> {
                cA.setInActiveWar(true);
                cB.setInActiveWar(true);

                // Проверка мирных условий (§7.5):
                // враждебность упала до 0; у обеих запас еды < 4; обе потеряли треть состава; норы > 64 блоков; или прошло > 10 дней
                if (shouldConcludePeace(conflict, cA, cB, stageElapsed)) {
                    enterTruce(world, warState, conflict, cA, cB, time);
                    break;
                }

                // Проверка условия перехода к Захвату (после 3+ дней войны и разгрома защитников)
                Colony stronger = selectAttacker(cA, cB);
                Colony weaker = stronger == cA ? cB : cA;
                if (stageElapsed >= 72000L && weaker.getMemberCount() <= Math.max(2, stronger.getMemberCount() / 2)) {
                    conflict.setEscalation(Escalation.CAPTURE, time);
                    warState.markDirty();
                    break;
                }

                // Отправка боевого отряда на стычку или запечатывание входа (§7.3)
                if (!ColonyManager.isMsptThrottled()) {
                    RaidSquad.SquadObjective obj = stronger.getStorage().getMaterialCount(CarryLoadType.DIRT) > 0
                            && world.random.nextBoolean()
                            ? RaidSquad.SquadObjective.SEAL_ENTRANCE
                            : RaidSquad.SquadObjective.SKIRMISH;
                    spawnSquadIfAllowed(world, stronger, weaker, obj, cfg.warMaxFighters, time);
                }
            }
            case CAPTURE -> {
                Colony winner = selectAttacker(cA, cB);
                Colony loser = winner == cA ? cB : cA;
                executeBurrowCapture(world, winner, loser, time);
                enterTruce(world, warState, conflict, cA, cB, time);
            }
        }
    }

    private static boolean canStartActiveWar(WarState warState, int colonyA, int colonyB) {
        RodentConfig cfg = RodentConfig.get();
        if (warState.countActiveWars() >= cfg.warMaxActiveConflicts) {
            return false;
        }
        return warState.countActiveWarsForColony(colonyA) < cfg.warMaxConflictsPerColony
                && warState.countActiveWarsForColony(colonyB) < cfg.warMaxConflictsPerColony;
    }

    private static boolean shouldConcludePeace(WarConflict conflict, Colony cA, Colony cB, long stageElapsed) {
        if (conflict.getHostilityScore() <= 0) {
            return true;
        }
        if (cA.getStorage().getFoodUnits() < 4.0F && cB.getStorage().getFoodUnits() < 4.0F) {
            return true;
        }
        int lossThreshA = Math.max(2, (cA.getMemberCount() + conflict.getTotalLosses(cA.getColonyId())) / 3);
        int lossThreshB = Math.max(2, (cB.getMemberCount() + conflict.getTotalLosses(cB.getColonyId())) / 3);
        if (conflict.getTotalLosses(cA.getColonyId()) >= lossThreshA
                && conflict.getTotalLosses(cB.getColonyId()) >= lossThreshB) {
            return true;
        }
        // Война длится 3–10 игровых дней (§7.2)
        return stageElapsed >= 240000L;
    }

    public static void enterTruce(
            ServerWorld world,
            WarState warState,
            WarConflict conflict,
            Colony cA,
            Colony cB,
            long time
    ) {
        RodentConfig cfg = RodentConfig.get();
        int truceDays = cfg.warTruceMinDays + world.random.nextInt(Math.max(1, cfg.warTruceMaxDays - cfg.warTruceMinDays + 1));
        long truceDuration = truceDays * 24000L;
        long cooldownDuration = (long) cfg.warCooldownDays * 24000L;

        conflict.setEscalation(Escalation.TRUCE, time);
        conflict.setTruceUntilTick(time + truceDuration);
        conflict.setCooldownUntilTick(time + cooldownDuration);
        conflict.setHostilityScore(0);

        if (cA != null) {
            cA.setInActiveWar(false);
            cA.incrementWarsSurvived();
            promoteVeteransIfEligible(world, cA);
        }
        if (cB != null) {
            cB.setInActiveWar(false);
            cB.incrementWarsSurvived();
            promoteVeteransIfEligible(world, cB);
        }
        warState.markDirty();
        LOGGER.info("Заключено обязательное перемирие между колониями #{} и #{} на {} игровых дней",
                conflict.getColonyA(), conflict.getColonyB(), truceDays);
    }

    private static void promoteVeteransIfEligible(ServerWorld world, Colony colony) {
        if (colony.getWarsSurvived() < 2) {
            return;
        }
        // Повышение окраса до VETERAN после 2+ выжитых войн (rodents_mod_assets.md §6)
        for (UUID uuid : colony.getMemberUuids()) {
            Entity e = world.getEntity(uuid);
            if (e instanceof RodentEntity rodent && rodent.isAlive() && !rodent.isBaby()) {
                rodent.setCoatVariant(RodentCoatVariant.VETERAN);
            }
        }
    }

    private static Colony selectAttacker(Colony cA, Colony cB) {
        // Крысы обычно идут в наступление против мышей (§7.1)
        if (cA.getSpecies() == RodentSpecies.RAT && cB.getSpecies() == RodentSpecies.MOUSE) {
            return cA;
        }
        if (cB.getSpecies() == RodentSpecies.RAT && cA.getSpecies() == RodentSpecies.MOUSE) {
            return cB;
        }
        return cA.getMemberCount() >= cB.getMemberCount() ? cA : cB;
    }

    /**
     * Координированная операция коалиции (§7.9):
     * до 8 бойцов суммарно (не более 4 с каждой стороны):
     * одна союзница давит на вход (SEAL_ENTRANCE / SKIRMISH), вторая грабит запасы (RAID_FOOD).
     */
    public static void launchCoordinatedCoalitionOperation(
            ServerWorld world,
            Colony allyA,
            Colony allyB,
            Colony target,
            long time
    ) {
        int maxPerAlly = Math.min(4, RodentConfig.get().coalitionMaxFightersPerAlly);
        spawnSquadIfAllowed(world, allyA, target, RaidSquad.SquadObjective.SEAL_ENTRANCE, maxPerAlly, time);
        spawnSquadIfAllowed(world, allyB, target, RaidSquad.SquadObjective.RAID_FOOD, maxPerAlly, time);
    }

    public static void spawnSquadIfAllowed(
            ServerWorld world,
            Colony attacker,
            Colony defender,
            RaidSquad.SquadObjective objective,
            int maxFightersCap,
            long time
    ) {
        RodentConfig cfg = RodentConfig.get();
        List<RaidSquad> squads = ACTIVE_SQUADS.computeIfAbsent(world.getRegistryKey(), k -> new ArrayList<>());

        // Проверка: нет ли уже активного отряда от этой колонии
        int activeFightersInDimension = 0;
        for (RaidSquad sq : squads) {
            if (!sq.isCompleted()) {
                activeFightersInDimension += sq.getFighterUuids().size();
                if (sq.getAttackerColonyId() == attacker.getColonyId()) {
                    return;
                }
            }
        }
        if (activeFightersInDimension >= cfg.maxActiveFightersPerDimension) {
            return;
        }

        // Отряд: 2–6 особей, не более 30% состава (§7.3)
        int maxByFraction = Math.max(2, (int) Math.floor(attacker.getMemberCount() * cfg.warMaxSquadFraction));
        int desiredSize = Math.min(maxFightersCap, Math.min(cfg.warMaxFighters, maxByFraction));
        int remainingSlots = cfg.maxActiveFightersPerDimension - activeFightersInDimension;
        desiredSize = Math.min(desiredSize, remainingSlots);
        if (desiredSize < 2 || attacker.getMemberCount() < 4) {
            return;
        }

        List<RodentEntity> candidates = new ArrayList<>();
        RodentEntity healthiestCommander = null;
        float bestHealth = -1.0F;

        for (UUID uuid : attacker.getMemberUuids()) {
            Entity e = world.getEntity(uuid);
            if (e instanceof RodentEntity rodent && rodent.isAlive() && !rodent.isBaby()) {
                candidates.add(rodent);
                if (rodent.getHealth() > bestHealth) {
                    bestHealth = rodent.getHealth();
                    healthiestCommander = rodent;
                }
            }
        }
        if (candidates.size() < 2) {
            return;
        }

        BlockPos targetPos = defender.getPrimaryEntrance();
        RaidSquad squad = new RaidSquad(attacker.getColonyId(), defender.getColonyId(), objective, targetPos, time);
        if (healthiestCommander != null) {
            squad.setCommanderUuid(healthiestCommander.getUuid());
            squad.addFighter(healthiestCommander.getUuid());
            healthiestCommander.setRaidFighter(true, targetPos);
        }
        for (RodentEntity cand : candidates) {
            if (squad.getFighterUuids().size() >= desiredSize) {
                break;
            }
            squad.addFighter(cand.getUuid());
            cand.setRaidFighter(true, targetPos);
        }

        // Боевой клич отряда (rodents_mod_assets.md §9.2)
        if (attacker.getSpecies() == RodentSpecies.RAT) {
            world.playSound(null, attacker.getPrimaryEntrance(), RodentSounds.WARCRY, SoundCategory.NEUTRAL, 0.3F, RodentSounds.RAT_BASE_PITCH);
        } else {
            world.playSound(null, attacker.getPrimaryEntrance(), RodentSounds.WARRIOR_SQUEAK, SoundCategory.NEUTRAL, 0.25F, 1.6F);
        }

        squads.add(squad);
    }

    private static void tickActiveSquads(ServerWorld world, long time) {
        List<RaidSquad> squads = ACTIVE_SQUADS.get(world.getRegistryKey());
        if (squads == null || squads.isEmpty()) {
            return;
        }
        ColonyState colonyState = ColonyState.get(world);
        WarState warState = WarState.get(world);
        int lossCap = RodentConfig.get().warLossCapPerFight;

        Iterator<RaidSquad> it = squads.iterator();
        while (it.hasNext()) {
            RaidSquad squad = it.next();
            if (squad.isCompleted() || time - squad.getCreatedTick() > 2400L) {
                releaseSquadFighters(world, squad);
                it.remove();
                continue;
            }

            // Потери за столкновение ограничены <= 2 с каждой стороны (§7.3)
            if (squad.getLossesSustained() >= lossCap || squad.getLossesInflicted() >= lossCap) {
                squad.setCompleted(true);
                releaseSquadFighters(world, squad);
                it.remove();
                continue;
            }

            Colony attacker = colonyState.getColony(squad.getAttackerColonyId());
            Colony defender = colonyState.getColony(squad.getTargetColonyId());
            if (attacker == null || defender == null || !world.isChunkLoaded(squad.getTargetPos())) {
                squad.setCompleted(true);
                releaseSquadFighters(world, squad);
                it.remove();
                continue;
            }

            int aliveNearTarget = 0;
            for (UUID uuid : squad.getFighterUuids()) {
                Entity e = world.getEntity(uuid);
                if (e instanceof RodentEntity fighter && fighter.isAlive()) {
                    if (fighter.getBlockPos().isWithinDistance(squad.getTargetPos(), 4.5D)) {
                        aliveNearTarget++;
                    } else {
                        fighter.getNavigation().startMovingTo(
                                squad.getTargetPos().getX() + 0.5D,
                                squad.getTargetPos().getY(),
                                squad.getTargetPos().getZ() + 0.5D,
                                1.15D
                        );
                    }
                }
            }

            if (aliveNearTarget > 0) {
                performSquadObjectiveAtTarget(world, warState, squad, attacker, defender, time);
                squad.setCompleted(true);
                releaseSquadFighters(world, squad);
                it.remove();
            }
        }
    }

    private static void performSquadObjectiveAtTarget(
            ServerWorld world,
            WarState warState,
            RaidSquad squad,
            Colony attacker,
            Colony defender,
            long time
    ) {
        defender.triggerAlert(squad.getTargetPos(), "war_raid", time);
        world.playSound(null, squad.getTargetPos(), RodentSounds.FIGHT, SoundCategory.NEUTRAL, 0.35F,
                attacker.getSpecies() == RodentSpecies.MOUSE ? 1.4F : 0.9F);

        switch (squad.getObjective()) {
            case RAID_FOOD -> {
                // Ограбление чужих запасов еды с пересчётом единиц пищи без потерь (§7.3)
                float stolen = Math.min(4.0F, defender.getStorage().getFoodUnits() * 0.35F);
                if (stolen > 0.1F && defender.getStorage().consumeFood(stolen)) {
                    attacker.getStorage().addFood(stolen);
                    LOGGER.debug("Отряд колонии #{} похитил {} ед. пищи у колонии #{}",
                            attacker.getColonyId(), String.format("%.1f", stolen), defender.getColonyId());
                }
            }
            case SEAL_ENTRANCE -> {
                // Запечатывание чужого входа землёй из запаса нападающих по правилам пробки (§7.3)
                BlockPos entrance = defender.getPrimaryEntrance();
                ColonyWorldEditor.placeBlock(
                        world,
                        entrance,
                        Blocks.COARSE_DIRT.getDefaultState(),
                        CarryLoadType.DIRT,
                        attacker,
                        "war_seal"
                );
            }
            case SKIRMISH, CAPTURE_BURROW -> {
                // Открытое столкновение у норы с жёстким лимитом потерь <= 2 (§7.3)
                WarConflict conflict = warState.getConflict(attacker.getColonyId(), defender.getColonyId());
                if (conflict != null) {
                    conflict.resetCurrentFightLosses();
                }
            }
        }
    }

    private static void releaseSquadFighters(ServerWorld world, RaidSquad squad) {
        for (UUID uuid : squad.getFighterUuids()) {
            Entity e = world.getEntity(uuid);
            if (e instanceof RodentEntity fighter) {
                fighter.setRaidFighter(false, null);
            }
        }
    }

    /**
     * Захват чужой норы (§7.3, §7.4, §8.1):
     * - победитель занимает уже вырытую нору как филиал (никаких новых разрушений блоков);
     * - запасы еды и материалов переходят победителю с сохранением нулевого баланса;
     * - уцелевшие особи проигравшей колонии становятся беглецами (группа 3+ может основать новую колонию).
     */
    private static void executeBurrowCapture(ServerWorld world, Colony winner, Colony loser, long time) {
        BlockPos capturedHome = loser.getHomePos();
        winner.addBranchPosition(capturedHome);
        winner.addEntrance(capturedHome);
        for (BlockPos ch : loser.getChamberPositions()) {
            winner.addChamberPosition(ch);
        }

        // Передача запасов еды и стройматериалов с нулевым балансом (§8.1)
        float foodTransferred = loser.getStorage().getFoodUnits();
        if (foodTransferred > 0.0F) {
            loser.getStorage().setFoodUnits(0.0F);
            winner.getStorage().addFood(foodTransferred);
        }
        for (CarryLoadType type : CarryLoadType.values()) {
            if (type != CarryLoadType.NONE && type != CarryLoadType.FOOD) {
                int count = loser.getStorage().getMaterialCount(type);
                if (count > 0) {
                    loser.getStorage().recordLostMaterial(type, count);
                    winner.getStorage().addCapturedMaterial(type, count);
                }
            }
        }

        // Запись захвата в журнал изменений для поддержки отката /rodentcolonies rollback (§8.4)
        ChangeJournal.recordChange(
                world,
                capturedHome,
                world.getBlockState(capturedHome),
                world.getBlockState(capturedHome),
                winner.getColonyId(),
                "war_capture",
                loser.getColonyId()
        );

        // Уцелевшие особи проигравшей колонии становятся беглецами (§7.4)
        List<UUID> survivors = new ArrayList<>(loser.getMemberUuids());
        for (UUID uuid : survivors) {
            loser.removeMember(uuid, time);
            Entity e = world.getEntity(uuid);
            if (e instanceof RodentEntity rodent && rodent.isAlive()) {
                rodent.setColonyId(-1);
                rodent.setRefugee(true);
                rodent.setCoatVariant(RodentCoatVariant.MANGY);
            }
        }

        ColonyState.get(world).rebuildChunkCache();
        ColonyState.get(world).markDirty();
        LOGGER.info("Колония #{} захватила нору колонии #{} в {}; {} выживших стали беглецами",
                winner.getColonyId(), loser.getColonyId(), capturedHome.toShortString(), survivors.size());
    }

    /**
     * Проверяет и учитывает боевую потерю в стычке между двумя колониями (лимит <= 2 на сторону за бой, §7.3).
     * Возвращает true, если лимит потерь в текущей стычке уже исчерпан и смертельный удар нужно отменить.
     */
    public static boolean isLossCapReachedForFight(ServerWorld world, int colonyA, int colonyB, int victimColonyId) {
        if (colonyA <= 0 || colonyB <= 0 || colonyA == colonyB) {
            return false;
        }
        WarConflict conflict = WarState.get(world).getConflict(colonyA, colonyB);
        if (conflict == null) {
            return false;
        }
        return conflict.getCurrentFightLosses(victimColonyId) >= RodentConfig.get().warLossCapPerFight;
    }

    public static void recordCombatDeath(ServerWorld world, int killerColonyId, int victimColonyId) {
        if (killerColonyId <= 0 || victimColonyId <= 0 || killerColonyId == victimColonyId) {
            return;
        }
        WarState warState = WarState.get(world);
        WarConflict conflict = warState.getConflict(killerColonyId, victimColonyId);
        if (conflict != null) {
            conflict.recordLoss(victimColonyId);
            warState.markDirty();
        }
    }
}
