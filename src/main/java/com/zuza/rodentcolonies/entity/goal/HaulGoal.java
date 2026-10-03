package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.world.NaturalEvents;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

/**
 * Перенос добытой пищи или стройматериала пешком в нору (rodents_mod_plan.md §4, §6.1):
 * - телепорта предметов нет: грызун несёт груз в зубах до входа норы;
 * - часть семян при переноске может теряться и прорастать позже (§4).
 */
public class HaulGoal extends Goal {
    private final RodentEntity rodent;

    public HaulGoal(RodentEntity rodent) {
        this.rodent = rodent;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        return !rodent.isDebugStateFrozen()
                && !rodent.isBaby()
                && rodent.getCarryLoadType().isCarrying()
                && rodent.getColonyId() > 0;
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
    }

    @Override
    public void tick() {
        if (!(rodent.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Colony colony = rodent.getColony(world);
        if (colony == null) {
            rodent.clearCarriedLoad();
            return;
        }

        BlockPos dropoffPos = colony.getPrimaryEntrance();
        if (rodent.age % 60 == 0) {
            rodent.playSound(RodentSounds.RUSTLE, 0.12F, rodent.getSpeciesSoundPitch());
        }

        // Часть семян теряется при переноске и даёт всходы (§4)
        if (rodent.getCarryLoadType() == CarryLoadType.FOOD && rodent.age % 100 == 0) {
            if (world.random.nextFloat() < RodentConfig.get().seedDropChanceDuringHaul) {
                NaturalEvents.queueDroppedSeedAt(world, rodent.getBlockPos());
            }
        }

        if (rodent.getBlockPos().isWithinDistance(dropoffPos, 2.5D)) {
            CarryLoadType load = rodent.getCarryLoadType();
            if (load == CarryLoadType.FOOD) {
                colony.getStorage().addFood(Math.max(0.5F, rodent.getCarriedFoodUnits()));
            } else {
                colony.getStorage().depositHarvestedMaterial(load, 1);
            }
            rodent.clearCarriedLoad();
            return;
        }

        if (rodent.canRecalculatePathNow() || rodent.getNavigation().isIdle()) {
            rodent.getNavigation().startMovingTo(
                    dropoffPos.getX() + 0.5D,
                    dropoffPos.getY(),
                    dropoffPos.getZ() + 0.5D,
                    1.05D
            );
        }
    }
}
