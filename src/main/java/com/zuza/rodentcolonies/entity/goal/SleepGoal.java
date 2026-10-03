package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

/**
 * Дневной сон в норе (rodents_mod_plan.md §6.7, §10, §11; rodents_mod_assets.md §7):
 * днём грызуны спят в норе, навигация останавливается, что снимает половину нагрузки на сервер.
 */
public class SleepGoal extends Goal {
    private final RodentEntity rodent;

    public SleepGoal(RodentEntity rodent) {
        this.rodent = rodent;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
    }

    @Override
    public boolean canStart() {
        if (rodent.isDebugStateFrozen() || rodent.isRaidFighter() || rodent.getCarryLoadType().isCarrying()) {
            return false;
        }
        if (!(rodent.getWorld() instanceof ServerWorld world) || !world.isDay()) {
            return false;
        }
        Colony colony = rodent.getColony(world);
        if (colony != null && (colony.isAlertActive(world.getTime()) || colony.isInActiveWar())) {
            return false;
        }
        BlockPos home = rodent.getHomePos();
        return home != null && rodent.getBlockPos().isWithinDistance(home, 8.0D);
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
    }

    @Override
    public void start() {
        rodent.getNavigation().stop();
        rodent.setRodentState(RodentState.SLEEP);
    }

    @Override
    public void tick() {
        rodent.getNavigation().stop();
        rodent.setRodentState(RodentState.SLEEP);
    }

    @Override
    public void stop() {
        if (rodent.getRodentState() == RodentState.SLEEP) {
            rodent.setRodentState(RodentState.IDLE);
        }
    }
}
