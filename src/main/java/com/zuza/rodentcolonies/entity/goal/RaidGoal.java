package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.entity.RodentState;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.war.CoalitionState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Поведение бойца отряда во время набега или войны (rodents_mod_plan.md §1, §7.3):
 * - крысы держат строй и бьют в группе;
 * - мыши кусают и отскакивают назад.
 */
public class RaidGoal extends Goal {
    private final RodentEntity rodent;
    private int attackCooldown;

    public RaidGoal(RodentEntity rodent) {
        this.rodent = rodent;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return !rodent.isDebugStateFrozen()
                && !rodent.isBaby()
                && rodent.isRaidFighter()
                && rodent.getRaidTargetPos() != null;
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
        BlockPos targetPos = rodent.getRaidTargetPos();
        if (targetPos == null) {
            return;
        }

        if (attackCooldown > 0) {
            attackCooldown--;
        }

        // Ищем вражеских грызунов у цели набега
        Box searchBox = rodent.getBoundingBox().expand(6.0D);
        List<RodentEntity> enemies = world.getEntitiesByClass(
                RodentEntity.class,
                searchBox,
                other -> other.isAlive()
                        && !other.isBaby()
                        && other.getColonyId() > 0
                        && other.getColonyId() != rodent.getColonyId()
                        && !CoalitionState.get(world).areAllies(rodent.getColonyId(), other.getColonyId())
        );

        if (!enemies.isEmpty()) {
            RodentEntity enemy = enemies.get(0);
            rodent.getLookControl().lookAt(enemy, 30.0F, 30.0F);
            double distSq = rodent.squaredDistanceTo(enemy);
            if (distSq <= 2.25D && attackCooldown <= 0) {
                attackCooldown = 20;
                rodent.triggerTimedState(RodentState.ATTACK, 6);
                rodent.tryAttack(enemy);
                rodent.playSound(RodentSounds.FIGHT, 0.35F, rodent.getSpeciesSoundPitch());

                // Мышь в бою: кусает и отскакивает (§1, §7.3)
                if (rodent.getSpecies() == RodentSpecies.MOUSE) {
                    Vec3d away = rodent.getPos().subtract(enemy.getPos()).normalize().multiply(0.35D);
                    rodent.addVelocity(away.x, 0.2D, away.z);
                }
            } else if (rodent.canRecalculatePathNow()) {
                rodent.getNavigation().startMovingTo(enemy, 1.2D);
            }
            return;
        }

        if (!rodent.getBlockPos().isWithinDistance(targetPos, 3.0D) && rodent.canRecalculatePathNow()) {
            rodent.getNavigation().startMovingTo(
                    targetPos.getX() + 0.5D,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5D,
                    1.2D
            );
        }
    }
}
