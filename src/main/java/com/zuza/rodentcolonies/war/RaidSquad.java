package com.zuza.rodentcolonies.war;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Боевой отряд колонии (rodents_mod_plan.md §7.3, §7.7):
 * 2–6 особей (не более 30% состава), командир — самая здоровая особь,
 * тикает раз в 20 тиков, потери за столкновение <= 2 особей с каждой стороны.
 */
public class RaidSquad {
    public enum SquadObjective {
        RAID_FOOD,      // грабёж чужих запасов еды и грядок
        SEAL_ENTRANCE,  // запечатывание чужого входа принесённым материалом
        SKIRMISH,       // открытая стычка у норы
        CAPTURE_BURROW  // захват уже вырытой норы противника
    }

    private final int attackerColonyId;
    private final int targetColonyId;
    private final SquadObjective objective;
    private final BlockPos targetPos;
    private UUID commanderUuid;
    private final List<UUID> fighterUuids = new ArrayList<>();
    private final long createdTick;
    private int lossesSustained;
    private int lossesInflicted;
    private boolean completed;

    public RaidSquad(int attackerColonyId, int targetColonyId, SquadObjective objective, BlockPos targetPos, long createdTick) {
        this.attackerColonyId = attackerColonyId;
        this.targetColonyId = targetColonyId;
        this.objective = objective;
        this.targetPos = targetPos.toImmutable();
        this.createdTick = createdTick;
    }

    public int getAttackerColonyId() {
        return attackerColonyId;
    }

    public int getTargetColonyId() {
        return targetColonyId;
    }

    public SquadObjective getObjective() {
        return objective;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    public UUID getCommanderUuid() {
        return commanderUuid;
    }

    public void setCommanderUuid(UUID commanderUuid) {
        this.commanderUuid = commanderUuid;
    }

    public List<UUID> getFighterUuids() {
        return fighterUuids;
    }

    public void addFighter(UUID uuid) {
        if (!fighterUuids.contains(uuid)) {
            fighterUuids.add(uuid);
        }
        if (commanderUuid == null) {
            commanderUuid = uuid;
        }
    }

    public long getCreatedTick() {
        return createdTick;
    }

    public int getLossesSustained() {
        return lossesSustained;
    }

    public void recordSustainedLoss() {
        this.lossesSustained++;
    }

    public int getLossesInflicted() {
        return lossesInflicted;
    }

    public void recordInflictedLoss() {
        this.lossesInflicted++;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }
}
