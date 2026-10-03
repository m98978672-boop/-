package com.zuza.rodentcolonies.war;

import com.zuza.rodentcolonies.entity.RodentSpecies;
import net.minecraft.nbt.NbtCompound;

/**
 * Коалиция двух колоний одного вида против общего сильного врага (Этап 5, rodents_mod_plan.md §7.9):
 * - строго две колонии одного вида;
 * - общие цель и координация отрядов (до 8 бойцов: по 4 с каждой стороны);
 * - нор, запасов и стройки общих нет.
 */
public class Coalition {
    private final int allyA;
    private final int allyB;
    private final int targetColonyId;
    private final RodentSpecies species;
    private final long formedTick;
    private int initialCombinedPopulation;
    private long threatGoneSinceTick = -1L;
    private long cooldownUntilTick;
    private boolean active = true;

    public Coalition(int c1, int c2, int targetColonyId, RodentSpecies species, int initialCombinedPop, long formedTick) {
        this.allyA = Math.min(c1, c2);
        this.allyB = Math.max(c1, c2);
        this.targetColonyId = targetColonyId;
        this.species = species;
        this.initialCombinedPopulation = initialCombinedPop;
        this.formedTick = formedTick;
    }

    public long getPairKey() {
        return WarConflict.makePairKey(allyA, allyB);
    }

    public int getAllyA() {
        return allyA;
    }

    public int getAllyB() {
        return allyB;
    }

    public int getTargetColonyId() {
        return targetColonyId;
    }

    public RodentSpecies getSpecies() {
        return species;
    }

    public long getFormedTick() {
        return formedTick;
    }

    public int getInitialCombinedPopulation() {
        return initialCombinedPopulation;
    }

    public long getThreatGoneSinceTick() {
        return threatGoneSinceTick;
    }

    public void setThreatGoneSinceTick(long threatGoneSinceTick) {
        this.threatGoneSinceTick = threatGoneSinceTick;
    }

    public long getCooldownUntilTick() {
        return cooldownUntilTick;
    }

    public void setCooldownUntilTick(long cooldownUntilTick) {
        this.cooldownUntilTick = cooldownUntilTick;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean involvesAlly(int colonyId) {
        return allyA == colonyId || allyB == colonyId;
    }

    public int getPartnerOf(int colonyId) {
        return allyA == colonyId ? allyB : allyA;
    }

    public boolean areAllies(int c1, int c2) {
        return active && ((allyA == c1 && allyB == c2) || (allyA == c2 && allyB == c1));
    }

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("AllyA", allyA);
        tag.putInt("AllyB", allyB);
        tag.putInt("Target", targetColonyId);
        tag.putString("Species", species.getId());
        tag.putLong("FormedTick", formedTick);
        tag.putInt("InitialPop", initialCombinedPopulation);
        tag.putLong("ThreatGoneSince", threatGoneSinceTick);
        tag.putLong("CooldownUntil", cooldownUntilTick);
        tag.putBoolean("Active", active);
        return tag;
    }

    public static Coalition fromNbt(NbtCompound tag) {
        Coalition c = new Coalition(
                tag.getInt("AllyA"),
                tag.getInt("AllyB"),
                tag.getInt("Target"),
                RodentSpecies.fromId(tag.getString("Species")),
                tag.getInt("InitialPop"),
                tag.getLong("FormedTick")
        );
        c.threatGoneSinceTick = tag.getLong("ThreatGoneSince");
        c.cooldownUntilTick = tag.getLong("CooldownUntil");
        c.active = tag.getBoolean("Active");
        return c;
    }
}
