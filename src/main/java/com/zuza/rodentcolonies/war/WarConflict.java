package com.zuza.rodentcolonies.war;

import net.minecraft.nbt.NbtCompound;

/**
 * Симметричная запись об отношениях пары колоний (colonyA < colonyB) (rodents_mod_plan.md §7.2, §7.7, §12).
 */
public class WarConflict {
    private final int colonyA;
    private final int colonyB;
    private Escalation escalation = Escalation.NEUTRAL;
    private int hostilityScore;
    private long stageStartedTick;
    private long truceUntilTick;
    private long cooldownUntilTick;
    private int totalLossesA;
    private int totalLossesB;
    private int currentFightLossesA;
    private int currentFightLossesB;

    public WarConflict(int c1, int c2, long currentTick) {
        this.colonyA = Math.min(c1, c2);
        this.colonyB = Math.max(c1, c2);
        this.stageStartedTick = currentTick;
    }

    public static long makePairKey(int c1, int c2) {
        int a = Math.min(c1, c2);
        int b = Math.max(c1, c2);
        return (((long) a) << 32) | (b & 0xFFFFFFFFL);
    }

    public long getPairKey() {
        return makePairKey(colonyA, colonyB);
    }

    public int getColonyA() {
        return colonyA;
    }

    public int getColonyB() {
        return colonyB;
    }

    public boolean involves(int colonyId) {
        return colonyA == colonyId || colonyB == colonyId;
    }

    public int getOther(int colonyId) {
        return colonyA == colonyId ? colonyB : colonyA;
    }

    public Escalation getEscalation() {
        return escalation;
    }

    public void setEscalation(Escalation escalation, long tick) {
        this.escalation = escalation;
        this.stageStartedTick = tick;
        this.currentFightLossesA = 0;
        this.currentFightLossesB = 0;
    }

    public int getHostilityScore() {
        return hostilityScore;
    }

    public void addHostility(int delta) {
        this.hostilityScore = Math.max(0, Math.min(200, this.hostilityScore + delta));
    }

    public void setHostilityScore(int hostilityScore) {
        this.hostilityScore = Math.max(0, hostilityScore);
    }

    public long getStageStartedTick() {
        return stageStartedTick;
    }

    public long getTruceUntilTick() {
        return truceUntilTick;
    }

    public void setTruceUntilTick(long truceUntilTick) {
        this.truceUntilTick = truceUntilTick;
    }

    public long getCooldownUntilTick() {
        return cooldownUntilTick;
    }

    public void setCooldownUntilTick(long cooldownUntilTick) {
        this.cooldownUntilTick = cooldownUntilTick;
    }

    public int getTotalLosses(int colonyId) {
        return colonyId == colonyA ? totalLossesA : totalLossesB;
    }

    public int getCurrentFightLosses(int colonyId) {
        return colonyId == colonyA ? currentFightLossesA : currentFightLossesB;
    }

    public void recordLoss(int colonyId) {
        if (colonyId == colonyA) {
            totalLossesA++;
            currentFightLossesA++;
        } else if (colonyId == colonyB) {
            totalLossesB++;
            currentFightLossesB++;
        }
    }

    public void resetCurrentFightLosses() {
        this.currentFightLossesA = 0;
        this.currentFightLossesB = 0;
    }

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("ColonyA", colonyA);
        tag.putInt("ColonyB", colonyB);
        tag.putString("Escalation", escalation.getId());
        tag.putInt("Hostility", hostilityScore);
        tag.putLong("StageStarted", stageStartedTick);
        tag.putLong("TruceUntil", truceUntilTick);
        tag.putLong("CooldownUntil", cooldownUntilTick);
        tag.putInt("LossesA", totalLossesA);
        tag.putInt("LossesB", totalLossesB);
        return tag;
    }

    public static WarConflict fromNbt(NbtCompound tag) {
        WarConflict c = new WarConflict(tag.getInt("ColonyA"), tag.getInt("ColonyB"), tag.getLong("StageStarted"));
        c.escalation = Escalation.fromId(tag.getString("Escalation"));
        c.hostilityScore = tag.getInt("Hostility");
        c.truceUntilTick = tag.getLong("TruceUntil");
        c.cooldownUntilTick = tag.getLong("CooldownUntil");
        c.totalLossesA = tag.getInt("LossesA");
        c.totalLossesB = tag.getInt("LossesB");
        return c;
    }
}
