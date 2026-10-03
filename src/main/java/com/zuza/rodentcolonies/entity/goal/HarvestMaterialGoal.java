package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.colony.BuildPlanner;
import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentState;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.world.BuildRules;
import com.zuza.rodentcolonies.world.ColonyWorldEditor;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

import java.util.EnumSet;

/**
 * Добыча стройматериалов по одному блоку в пределах 24 блоков от норы (rodents_mod_plan.md §6.1, §6.7):
 * - мягкие породы (земля, песок, гравий, глина, мох);
 * - валежник и сухая древесина;
 * - стога сена;
 * - листва, трава, папоротник.
 * Снятый блок берётся «в зубы» и относится в нору.
 */
public class HarvestMaterialGoal extends Goal {
    private final RodentEntity rodent;
    private CarryLoadType desiredType = CarryLoadType.NONE;
    private BlockPos targetPos;
    private int workTicksRemaining;
    private int searchCooldown;

    public HarvestMaterialGoal(RodentEntity rodent) {
        this.rodent = rodent;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (rodent.isDebugStateFrozen()
                || rodent.isBaby()
                || rodent.isRaidFighter()
                || rodent.getCarryLoadType().isCarrying()
                || rodent.getColonyId() <= 0) {
            return false;
        }
        if (--searchCooldown > 0) {
            return false;
        }
        searchCooldown = 50 + rodent.getRandom().nextInt(40);

        if (!(rodent.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return false;
        }
        // Стройка и заготовка материала — ночная активность (§6.7)
        if (world.isDay()) {
            return false;
        }

        Colony colony = rodent.getColony(world);
        if (colony == null || !colony.canModifyBlockToday(world.getTime())) {
            return false;
        }

        desiredType = BuildPlanner.getNeededMaterialForHarvest(world, colony);
        if (desiredType == CarryLoadType.NONE) {
            return false;
        }

        targetPos = findHarvestableBlockNear(world, colony, desiredType);
        return targetPos != null;
    }

    @Override
    public boolean shouldContinue() {
        return targetPos != null && !rodent.getCarryLoadType().isCarrying();
    }

    @Override
    public void start() {
        RodentConfig cfg = RodentConfig.get();
        int range = Math.max(1, cfg.buildMaxTicksPerBlock - cfg.buildMinTicksPerBlock);
        this.workTicksRemaining = cfg.buildMinTicksPerBlock + rodent.getRandom().nextInt(range + 1);
        if (targetPos != null) {
            rodent.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.0D);
        }
    }

    @Override
    public void tick() {
        if (!(rodent.getWorld() instanceof ServerWorld world) || targetPos == null) {
            return;
        }
        Colony colony = rodent.getColony(world);
        if (colony == null) {
            targetPos = null;
            return;
        }

        if (rodent.getBlockPos().isWithinDistance(targetPos, 2.5D)) {
            rodent.getNavigation().stop();
            rodent.getLookControl().lookAt(targetPos.getX() + 0.5D, targetPos.getY() + 0.5D, targetPos.getZ() + 0.5D);
            rodent.setRodentState(RodentState.EAT);
            if (workTicksRemaining % 15 == 0) {
                rodent.playSound(RodentSounds.DIG, 0.20F, rodent.getSpeciesSoundPitch());
            }
            if (--workTicksRemaining <= 0) {
                CarryLoadType harvested = ColonyWorldEditor.excavateBlock(world, targetPos, colony, false, "harvest");
                if (harvested.isCarrying()) {
                    rodent.setCarriedMaterial(harvested);
                }
                targetPos = null;
            }
            return;
        }

        if (rodent.canRecalculatePathNow()) {
            rodent.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.0D);
        }
    }

    private BlockPos findHarvestableBlockNear(ServerWorld world, Colony colony, CarryLoadType needed) {
        BlockPos entrance = colony.getPrimaryEntrance();
        int maxRange = RodentConfig.get().maxMaterialHarvestRange;
        for (int attempt = 0; attempt < 24; attempt++) {
            int dx = world.random.nextInt(maxRange * 2 + 1) - maxRange;
            int dy = world.random.nextInt(7) - 3;
            int dz = world.random.nextInt(maxRange * 2 + 1) - maxRange;
            // Не копаем вплотную к самой норе, чтобы не портить камеры
            if (Math.abs(dx) < 5 && Math.abs(dz) < 5) {
                continue;
            }
            BlockPos p = entrance.add(dx, dy, dz);
            if (!BuildRules.canExcavateAt(world, p)) {
                continue;
            }
            BlockState st = world.getBlockState(p);
            if (CarryLoadType.fromBlockState(st) == needed) {
                return p;
            }
        }
        return null;
    }
}
