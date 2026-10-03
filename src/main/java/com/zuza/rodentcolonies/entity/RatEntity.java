package com.zuza.rodentcolonies.entity;

import com.zuza.rodentcolonies.war.CoalitionState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

/**
 * Крыса (rodents_mod_plan.md §1):
 * - здоровье: 10 (5 сердец), хитбокс 0.6 x 0.7;
 * - крупнее, держит строй, идёт на помощь сородичам, охотится на мышей.
 */
public class RatEntity extends RodentEntity {
    public RatEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        this.goalSelector.add(4, new MeleeAttackGoal(this, 1.15D, false));
        // Крыса идёт на помощь сородичам при атаке (§1)
        this.targetSelector.add(1, new RevengeGoal(this).setGroupRevenge());
        // Крыса — охотник на мышей (§1, §7.1)
        this.targetSelector.add(2, new ActiveTargetGoal<>(
                this,
                MouseEntity.class,
                40,
                true,
                false,
                target -> {
                    if (!(target instanceof MouseEntity mouse)) {
                        return false;
                    }
                    if (this.getWorld() instanceof ServerWorld sw) {
                        return !CoalitionState.get(sw).areAllies(this.getColonyId(), mouse.getColonyId());
                    }
                    return true;
                }
        ));
    }

    @Override
    public RodentSpecies getSpecies() {
        return RodentSpecies.RAT;
    }
}
