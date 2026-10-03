package com.zuza.rodentcolonies.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.world.World;

/**
 * Мышь (rodents_mod_plan.md §1):
 * - здоровье: 6 (3 сердца), хитбокс 0.4 x 0.5;
 * - быстрая, уклоняется от крыс (жертва крыс), берёт числом, таскает семена и мелкие плоды.
 */
public class MouseEntity extends RodentEntity {
    public MouseEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        // Мышь — жертва крыс (§1): вне боевого отряда избегает одиночных крыс
        this.goalSelector.add(1, new FleeEntityGoal<>(
                this,
                RatEntity.class,
                8.0F,
                1.1D,
                1.35D,
                living -> !this.isRaidFighter()
        ));
    }

    @Override
    public RodentSpecies getSpecies() {
        return RodentSpecies.MOUSE;
    }
}
