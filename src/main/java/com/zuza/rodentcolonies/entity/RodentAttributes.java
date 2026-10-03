package com.zuza.rodentcolonies.entity;

import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;

/**
 * Фабрики атрибутов мыши и крысы (rodents_mod_plan.md §1, rodents_mod_guide.md §5.1).
 * Мышь: 6 HP (3 сердца), высокая скорость.
 * Крыса: 10 HP (5 сердец), ниже скорость, но выше урон и упорство.
 */
public final class RodentAttributes {
    private RodentAttributes() {
    }

    public static DefaultAttributeContainer.Builder createMouseAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 6.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.34D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.5D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0D);
    }

    public static DefaultAttributeContainer.Builder createRatAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 10.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.29D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.5D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 28.0D);
    }
}
