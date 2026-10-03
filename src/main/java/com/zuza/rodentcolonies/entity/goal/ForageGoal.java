package com.zuza.rodentcolonies.entity.goal;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.entity.RodentState;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.world.MaterialTags;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameRules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Поиск и сбор пищи (rodents_mod_plan.md §4):
 * - упавшие предметы еды;
 * - зрелые культуры по тегам (CropBlock#isMature): с грядки берётся не более 30% зрелых растений,
 *   последнее зрелое растение НЕ трогается, farmland и сам блок культуры не ломаются (возраст сбрасывается);
 * - кусты ягод, стога сена, компостеры;
 * - кража из сундуков (1 предмет из стека еды) при включённом mobGriefing.
 */
public class ForageGoal extends Goal {
    private final RodentEntity rodent;
    private BlockPos targetPos;
    private ItemEntity targetItemEntity;
    private int searchCooldown;

    public ForageGoal(RodentEntity rodent) {
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
        searchCooldown = 40 + rodent.getRandom().nextInt(40);

        if (!(rodent.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        Colony colony = rodent.getColony(world);
        if (colony == null || colony.getStorage().getFoodUnits() > 48.0F) {
            return false;
        }

        // 1. Проверяем упавшие предметы еды поблизости
        Box itemBox = rodent.getBoundingBox().expand(12.0D, 3.0D, 12.0D);
        List<ItemEntity> items = world.getEntitiesByClass(
                ItemEntity.class,
                itemBox,
                ie -> ie.isAlive() && isEdibleForRodent(ie.getStack())
        );
        if (!items.isEmpty()) {
            targetItemEntity = items.get(0);
            targetPos = targetItemEntity.getBlockPos();
            return true;
        }

        // 2. Ищем блок-источник пищи (только при включённом mobGriefing) (§4)
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return false;
        }
        targetItemEntity = null;
        targetPos = findFoodBlockNear(world, colony);
        return targetPos != null;
    }

    @Override
    public boolean shouldContinue() {
        if (rodent.getCarryLoadType().isCarrying() || targetPos == null) {
            return false;
        }
        if (targetItemEntity != null && !targetItemEntity.isAlive()) {
            return false;
        }
        return !rodent.getNavigation().isIdle();
    }

    @Override
    public void start() {
        if (targetPos != null) {
            rodent.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.05D);
        }
    }

    @Override
    public void tick() {
        if (!(rodent.getWorld() instanceof ServerWorld world) || targetPos == null) {
            return;
        }
        if (targetItemEntity != null && targetItemEntity.isAlive()) {
            targetPos = targetItemEntity.getBlockPos();
            if (rodent.squaredDistanceTo(targetItemEntity) <= 2.25D) {
                ItemStack stack = targetItemEntity.getStack();
                ItemStack taken = stack.split(1);
                float units = Math.max(0.5F, MaterialTags.getFoodUnits(taken));
                rodent.triggerTimedState(RodentState.EAT, 12);
                rodent.playSound(RodentSounds.EAT, 0.15F, rodent.getSpeciesSoundPitch());
                rodent.setCarriedFood(units, taken);
                targetPos = null;
                return;
            }
        } else if (rodent.getBlockPos().isWithinDistance(targetPos, 2.25D)) {
            harvestFoodAtPos(world, targetPos);
            targetPos = null;
            return;
        }

        if (rodent.canRecalculatePathNow()) {
            rodent.getNavigation().startMovingTo(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D, 1.05D);
        }
    }

    private boolean isEdibleForRodent(ItemStack stack) {
        return rodent.getSpecies() == RodentSpecies.MOUSE
                ? MaterialTags.isFoodForMouse(stack)
                : MaterialTags.isFoodForRat(stack);
    }

    private BlockPos findFoodBlockNear(ServerWorld world, Colony colony) {
        BlockPos origin = rodent.getBlockPos();
        int radius = 16;
        for (int attempt = 0; attempt < 18; attempt++) {
            int dx = world.random.nextInt(radius * 2 + 1) - radius;
            int dy = world.random.nextInt(5) - 2;
            int dz = world.random.nextInt(radius * 2 + 1) - radius;
            BlockPos p = origin.add(dx, dy, dz);
            if (!world.isChunkLoaded(p) || colony.getThreatMemory().isDangerousNear(p, 6.0D, world.getTime())) {
                continue;
            }
            BlockState state = world.getBlockState(p);
            if (MaterialTags.isMatureCrop(state) && canHarvestCropRespectfully(world, p)) {
                return p;
            }
            if (state.getBlock() instanceof ComposterBlock && state.get(ComposterBlock.LEVEL) > 0) {
                return p;
            }
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof Inventory inv && hasStealableFood(inv)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Грядка не разоряется (§4, §8.3):
     * - берётся не более 30% зрелых растений с одной грядки;
     * - последнее зрелое растение никогда не трогается.
     */
    public static boolean canHarvestCropRespectfully(ServerWorld world, BlockPos cropPos) {
        List<BlockPos> totalCrops = new ArrayList<>();
        List<BlockPos> matureCrops = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos p = cropPos.add(dx, 0, dz);
                if (!world.isChunkLoaded(p)) {
                    continue;
                }
                BlockState st = world.getBlockState(p);
                if (st.getBlock() instanceof CropBlock crop) {
                    totalCrops.add(p);
                    if (crop.isMature(st)) {
                        matureCrops.add(p);
                    }
                } else if (st.getBlock() instanceof SweetBerryBushBlock) {
                    totalCrops.add(p);
                    if (st.get(SweetBerryBushBlock.AGE) >= 2) {
                        matureCrops.add(p);
                    }
                }
            }
        }
        // Последнее зрелое растение никогда не трогается (§4, §14)
        if (matureCrops.size() <= 1) {
            return false;
        }
        // Сохраняем минимум 70% зрелого урожая грядки (снимается <= 30%)
        int minRemainingMature = Math.max(1, (int) Math.ceil(totalCrops.size() * (1.0F - RodentConfig.get().maxCropHarvestRatioPerPatch)));
        return matureCrops.size() - 1 >= minRemainingMature;
    }

    private boolean hasStealableFood(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (isEdibleForRodent(inv.getStack(i))) {
                return true;
            }
        }
        return false;
    }

    private void harvestFoodAtPos(ServerWorld world, BlockPos pos) {
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return;
        }
        BlockState state = world.getBlockState(pos);

        // 1. Зрелые культуры: снимаем только урожай (возраст -> 0), сам блок культуры и farmland остаются (§4)
        if (state.getBlock() instanceof CropBlock crop && crop.isMature(state)) {
            if (!canHarvestCropRespectfully(world, pos)) {
                return;
            }
            world.setBlockState(pos, crop.withAge(0), Block.NOTIFY_LISTENERS);
            rodent.triggerTimedState(RodentState.EAT, 15);
            rodent.playSound(RodentSounds.EAT, 0.15F, rodent.getSpeciesSoundPitch());
            float units = rodent.getSpecies() == RodentSpecies.MOUSE
                    ? RodentConfig.get().foodUnitsSeeds
                    : RodentConfig.get().foodUnitsVeggies;
            rodent.setCarriedFood(units, new ItemStack(Items.WHEAT_SEEDS, 1));
            return;
        }

        // 2. Ягодный куст
        if (state.getBlock() instanceof SweetBerryBushBlock && state.get(SweetBerryBushBlock.AGE) >= 2) {
            if (!canHarvestCropRespectfully(world, pos)) {
                return;
            }
            world.setBlockState(pos, state.with(SweetBerryBushBlock.AGE, 1), Block.NOTIFY_LISTENERS);
            rodent.triggerTimedState(RodentState.EAT, 15);
            rodent.playSound(RodentSounds.EAT, 0.15F, rodent.getSpeciesSoundPitch());
            rodent.setCarriedFood(RodentConfig.get().foodUnitsVeggies, new ItemStack(Items.SWEET_BERRIES, 1));
            return;
        }

        // 3. Компостер
        if (state.getBlock() instanceof ComposterBlock) {
            int level = state.get(ComposterBlock.LEVEL);
            if (level > 0) {
                world.setBlockState(pos, state.with(ComposterBlock.LEVEL, level - 1), Block.NOTIFY_LISTENERS);
                rodent.triggerTimedState(RodentState.EAT, 15);
                rodent.playSound(RodentSounds.EAT, 0.15F, rodent.getSpeciesSoundPitch());
                rodent.setCarriedFood(RodentConfig.get().foodUnitsSeeds, new ItemStack(Items.WHEAT_SEEDS, 1));
                return;
            }
        }

        // 4. Сундук / бочка: крадём строго 1 предмет из стека еды (§4, §15), блок сундука не трогается
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof Inventory inv) {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (isEdibleForRodent(stack)) {
                    int stealCount = Math.min(stack.getCount(), Math.max(1, RodentConfig.get().maxItemsStolenFromChest));
                    ItemStack stolen = stack.split(stealCount);
                    inv.markDirty();
                    float units = Math.max(0.5F, MaterialTags.getFoodUnits(stolen));
                    rodent.triggerTimedState(RodentState.EAT, 15);
                    rodent.playSound(RodentSounds.GNAW, 0.18F, rodent.getSpeciesSoundPitch());
                    rodent.setCarriedFood(units, stolen);
                    return;
                }
            }
        }
    }
}
