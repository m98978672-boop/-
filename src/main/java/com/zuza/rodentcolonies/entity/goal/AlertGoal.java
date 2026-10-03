package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.entity.RodentState;
import com.zuza.rodentcolonies.sound.RodentSounds;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.entity.passive.OcelotEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.EnumSet;
import java.util.List;

/**
 * Обнаружение хищников (котов, оцелотов, лис) и реакция на тревогу колонии (§6.8):
 * мыши разбегаются по отноркам, крысы защищают вход.
 */
public class AlertGoal extends Goal {
    private final RodentEntity rodent;
    private int scanCooldown;

    public AlertGoal(RodentEntity rodent) {
        this.rodent = rodent;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (rodent.isDebugStateFrozen() || !(rodent.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        Colony colony = rodent.getColony(world);
        if (colony == null) {
            return false;
        }
        if (--scanCooldown <= 0) {
            scanCooldown = 30 + rodent.getRandom().nextInt(20);
            scanForPredators(world, colony);
        }
        return colony.isAlertActive(world.getTime())
                && rodent.getBlockPos().isWithinDistance(colony.getHomePos(), RodentConfig.get().alertRadius);
    }

    private void scanForPredators(ServerWorld world, Colony colony) {
        Box box = rodent.getBoundingBox().expand(12.0D);
        List<LivingEntity> predators = world.getEntitiesByClass(
                LivingEntity.class,
                box,
                e -> e.isAlive() && (e instanceof CatEntity || e instanceof OcelotEntity || e instanceof FoxEntity)
        );
        if (!predators.isEmpty()) {
            LivingEntity predator = predators.get(0);
            colony.triggerAlert(predator.getBlockPos(), predator.getType().getTranslationKey(), world.getTime());
            rodent.playSound(RodentSounds.ALARM, 0.30F, rodent.getSpeciesSoundPitch());
        }
    }

    @Override
    public boolean shouldContinue() {
        if (!(rodent.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        Colony colony = rodent.getColony(world);
        return colony != null && colony.isAlertActive(world.getTime());
    }

    @Override
    public void tick() {
        if (!(rodent.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Colony colony = rodent.getColony(world);
        if (colony == null) {
            return;
        }

        if (rodent.getSpecies() == RodentSpecies.MOUSE) {
            // Мыши разбегаются по отноркам (§6.8)
            rodent.setRodentState(RodentState.RUN);
            BlockPos refuge = colony.getPrimaryEntrance();
            if (rodent.canRecalculatePathNow()) {
                rodent.getNavigation().startMovingTo(refuge.getX() + 0.5D, refuge.getY(), refuge.getZ() + 0.5D, 1.3D);
            }
        } else {
            // Крысы занимают оборону у входа (§6.8)
            rodent.setRodentState(RodentState.ALARM);
            BlockPos entrance = colony.getPrimaryEntrance();
            if (!rodent.getBlockPos().isWithinDistance(entrance, 3.0D) && rodent.canRecalculatePathNow()) {
                rodent.getNavigation().startMovingTo(entrance.getX() + 0.5D, entrance.getY(), entrance.getZ() + 0.5D, 1.15D);
            }
        }
    }
}
