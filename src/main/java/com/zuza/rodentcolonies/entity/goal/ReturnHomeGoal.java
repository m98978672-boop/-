package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.entity.RodentEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

/**
 * Возврат грызуна к домашней норе колонии (homePos) перед дневным сном или при удалении > 28 блоков (§6.8).
 */
public class ReturnHomeGoal extends Goal {
    private final RodentEntity rodent;

    public ReturnHomeGoal(RodentEntity rodent) {
        this.rodent = rodent;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (rodent.isDebugStateFrozen() || rodent.isRaidFighter() || rodent.getColonyId() <= 0) {
            return false;
        }
        BlockPos home = rodent.getHomePos();
        if (home == null || home.equals(BlockPos.ORIGIN)) {
            return false;
        }
        double distSq = rodent.getBlockPos().getSquaredDistance(home);
        if (distSq > 28.0D * 28.0D) {
            return true;
        }
        if (rodent.getWorld() instanceof ServerWorld world && world.isDay() && distSq > 6.0D * 6.0D) {
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldContinue() {
        BlockPos home = rodent.getHomePos();
        return home != null && !rodent.getBlockPos().isWithinDistance(home, 4.0D) && !rodent.getNavigation().isIdle();
    }

    @Override
    public void start() {
        BlockPos home = rodent.getHomePos();
        if (home != null) {
            rodent.getNavigation().startMovingTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 1.05D);
        }
    }
}
