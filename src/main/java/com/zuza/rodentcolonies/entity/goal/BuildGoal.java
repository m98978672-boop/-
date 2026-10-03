package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.colony.BuildPlanner;
import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ConstructionSite;
import com.zuza.rodentcolonies.colony.NestBlueprint;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentState;
import com.zuza.rodentcolonies.world.ColonyWorldEditor;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

import java.util.EnumSet;

/**
 * Стройка норы по чертежу и постановка аварийных пробок (rodents_mod_plan.md §6.2–§6.7):
 * - темп: 1 блок за 40–80 тиков;
 * - блок ставится ТОЛЬКО из запаса материалов колонии через ColonyWorldEditor;
 * - при отсутствии запаса стройка останавливается.
 */
public class BuildGoal extends Goal {
    private final RodentEntity rodent;
    private BlockPos targetPos;
    private boolean emergencyLeakTask;
    private int workTicksRemaining;
    private int checkCooldown;

    public BuildGoal(RodentEntity rodent) {
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
        if (--checkCooldown > 0) {
            return false;
        }
        checkCooldown = 40 + rodent.getRandom().nextInt(30);

        if (!(rodent.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return false;
        }

        Colony colony = rodent.getColony(world);
        if (colony == null || !colony.canModifyBlockToday(world.getTime())) {
            return false;
        }

        // Приоритет 2 (§6.2, §6.6): аварийная пробка при обнаружении течи воды/лавы (работает и днём, и ночью)
        BlockPos leakPos = BuildPlanner.findEmergencyLeakPos(world, colony);
        if (leakPos != null && colony.getStorage().getMaterialCount(CarryLoadType.DIRT) > 0) {
            this.emergencyLeakTask = true;
            this.targetPos = leakPos;
            return true;
        }

        // Плановая стройка (приоритеты 3–6) — ночная активность (§6.7)
        if (world.isDay() || !BuildPlanner.canColonyBuildPlanned(colony)) {
            return false;
        }

        ConstructionSite site = BuildPlanner.ensureNextConstructionSite(colony);
        if (site == null || site.isComplete()) {
            return false;
        }

        NestBlueprint.PlannedCell cell = site.peekNextCell();
        if (cell == null) {
            return false;
        }
        // Если ячейка требует укладки, а материала на складе нет — стройка ждёт добычи (§6)
        if (!cell.excavate() && colony.getStorage().getMaterialCount(cell.requiredMaterial()) <= 0) {
            return false;
        }

        this.emergencyLeakTask = false;
        this.targetPos = site.getNextWorldPos();
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return targetPos != null && workTicksRemaining > 0;
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

        if (rodent.getBlockPos().isWithinDistance(targetPos, 3.0D)) {
            rodent.getNavigation().stop();
            rodent.getLookControl().lookAt(targetPos.getX() + 0.5D, targetPos.getY() + 0.5D, targetPos.getZ() + 0.5D);
            if (workTicksRemaining <= 8) {
                rodent.setRodentState(RodentState.PLACE_BLOCK);
            } else {
                rodent.setRodentState(RodentState.EAT);
            }

            if (--workTicksRemaining <= 0) {
                executeBuildStep(world, colony);
                targetPos = null;
            }
            return;
        }

        if (rodent.canRecalculatePathNow()) {
            rodent.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.0D);
        }
    }

    private void executeBuildStep(ServerWorld world, Colony colony) {
        if (emergencyLeakTask) {
            ColonyWorldEditor.placeBlock(
                    world,
                    targetPos,
                    Blocks.CLAY.getDefaultState(),
                    CarryLoadType.DIRT,
                    colony,
                    "emergency_plug"
            );
            return;
        }

        ConstructionSite site = colony.getConstructionSite();
        if (site == null || site.isComplete()) {
            return;
        }
        NestBlueprint.PlannedCell cell = site.peekNextCell();
        if (cell == null) {
            return;
        }
        BlockPos worldPos = site.getNextWorldPos();

        if (cell.excavate()) {
            if (world.getBlockState(worldPos).isAir()) {
                site.advance();
                return;
            }
            CarryLoadType dug = ColonyWorldEditor.excavateBlock(world, worldPos, colony, true, cell.reason());
            if (dug != CarryLoadType.NONE) {
                site.advance();
            }
        } else {
            if (world.getBlockState(worldPos).equals(cell.targetState())) {
                site.advance();
                return;
            }
            boolean placed = ColonyWorldEditor.placeBlock(
                    world,
                    worldPos,
                    cell.targetState(),
                    cell.requiredMaterial(),
                    colony,
                    cell.reason()
            );
            if (placed) {
                site.advance();
            }
        }
    }
}
