package com.zuza.rodentcolonies.entity;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.goal.AlertGoal;
import com.zuza.rodentcolonies.entity.goal.BuildGoal;
import com.zuza.rodentcolonies.entity.goal.ForageGoal;
import com.zuza.rodentcolonies.entity.goal.HarvestMaterialGoal;
import com.zuza.rodentcolonies.entity.goal.HaulGoal;
import com.zuza.rodentcolonies.entity.goal.RaidGoal;
import com.zuza.rodentcolonies.entity.goal.ReturnHomeGoal;
import com.zuza.rodentcolonies.entity.goal.SleepGoal;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.war.CoalitionState;
import com.zuza.rodentcolonies.war.WarManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.entity.passive.OcelotEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Общий базовый класс мыши и крысы (rodents_mod_plan.md §1, §5, §6, §11, §12).
 */
public abstract class RodentEntity extends AnimalEntity {
    private static final TrackedData<Integer> COLONY_ID = DataTracker.registerData(RodentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> RODENT_STATE = DataTracker.registerData(RodentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FROZEN_DEBUG_STATE = DataTracker.registerData(RodentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> CARRY_LOAD_TYPE = DataTracker.registerData(RodentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> COAT_VARIANT = DataTracker.registerData(RodentEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> REFUGEE = DataTracker.registerData(RodentEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private BlockPos homePos = BlockPos.ORIGIN;
    private float carriedFoodUnits;
    private ItemStack carriedStack = ItemStack.EMPTY;
    private boolean raidFighter;
    private BlockPos raidTargetPos;

    private long lastPathRecalcTick;
    private int actionAnimTicks;
    private int nextSniffCountdown = 120;
    private int nextGroomCountdown = 400;

    protected RodentEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
    }

    public abstract RodentSpecies getSpecies();

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(COLONY_ID, -1);
        this.dataTracker.startTracking(RODENT_STATE, RodentState.IDLE.getId());
        this.dataTracker.startTracking(FROZEN_DEBUG_STATE, -1);
        this.dataTracker.startTracking(CARRY_LOAD_TYPE, CarryLoadType.NONE.getId());
        this.dataTracker.startTracking(COAT_VARIANT, RodentCoatVariant.BASE_PRIMARY.getIndex());
        this.dataTracker.startTracking(REFUGEE, false);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        // Хищники: коты, оцелоты, лисы — естественный регулятор популяции (§6.8)
        this.goalSelector.add(1, new FleeEntityGoal<>(this, CatEntity.class, 12.0F, 1.15D, 1.35D));
        this.goalSelector.add(1, new FleeEntityGoal<>(this, OcelotEntity.class, 12.0F, 1.15D, 1.35D));
        this.goalSelector.add(1, new FleeEntityGoal<>(this, FoxEntity.class, 12.0F, 1.15D, 1.35D));
        this.goalSelector.add(2, new SleepGoal(this));
        this.goalSelector.add(3, new AlertGoal(this));
        this.goalSelector.add(4, new RaidGoal(this));
        this.goalSelector.add(5, new HaulGoal(this));
        this.goalSelector.add(6, new ForageGoal(this));
        this.goalSelector.add(7, new BuildGoal(this));
        this.goalSelector.add(8, new HarvestMaterialGoal(this));
        this.goalSelector.add(9, new ReturnHomeGoal(this));
        this.goalSelector.add(10, new WanderAroundFarGoal(this, 0.9D));
        this.goalSelector.add(11, new LookAroundGoal(this));
    }

    @Override
    public boolean cannotDespawn() {
        // Грызуны никогда не деспавнятся принудительно (§2, принцип 3)
        return true;
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) {
        // Разводить вручную нельзя: размножение только внутри колонии при достатке еды (§1, §5)
        return false;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        // Игровой цикл приручения/кормления отсутствует (§1)
        return ActionResult.PASS;
    }

    @Override
    public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
        return null;
    }

    /**
     * Ограничение частоты пересчёта пути ИИ: не чаще раза в 40 тиков (§11).
     */
    public boolean canRecalculatePathNow() {
        long now = this.getWorld().getTime();
        if (now - lastPathRecalcTick >= 40L) {
            lastPathRecalcTick = now + (this.getId() % 7);
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();

        // Отладочная заморозка анимации (/rodentcolonies debug anim <mouse|rat> <state>) (§7 ассетов)
        int frozen = this.dataTracker.get(FROZEN_DEBUG_STATE);
        if (frozen >= 0) {
            RodentState debugState = RodentState.byId(frozen);
            this.setRodentState(debugState);
            this.getNavigation().stop();
            if (debugState == RodentState.BABY && !this.isBaby()) {
                this.setBreedingAge(-24000);
            }
            if (debugState == RodentState.PLACE_BLOCK && !this.getCarryLoadType().isCarrying()) {
                this.setCarryLoadType(CarryLoadType.DIRT);
            }
            return;
        }

        if (!this.getWorld().isClient() && this.getWorld() instanceof ServerWorld serverWorld) {
            tickServerState(serverWorld);
        }
    }

    private void tickServerState(ServerWorld world) {
        if (this.deathTime > 0) {
            setRodentState(RodentState.DEATH);
            return;
        }
        if (this.hurtTime > 0) {
            setRodentState(RodentState.HURT);
            return;
        }
        if (actionAnimTicks > 0) {
            actionAnimTicks--;
            return;
        }

        Colony colony = getColony(world);
        if (colony != null && colony.isAlertActive(world.getTime())) {
            if (this.getSpecies() == RodentSpecies.MOUSE) {
                setRodentState(RodentState.RUN);
            } else {
                setRodentState(RodentState.ALARM);
            }
            return;
        }

        if (getRodentState() == RodentState.SLEEP) {
            if (this.age % 60 == 0) {
                this.playSound(RodentSounds.BREATH, 0.06F, getSpeciesSoundPitch() * 0.85F);
            }
            return;
        }

        double speedSq = this.getVelocity().horizontalLengthSquared();
        if (speedSq > 0.015D || this.isRaidFighter()) {
            setRodentState(RodentState.RUN);
            return;
        }
        if (speedSq > 0.0008D) {
            setRodentState(RodentState.WALK);
            return;
        }

        // Спокойные микроанимации: нюхает (каждые 80-200 т на 10 т) и умывается (каждые 300-600 т на 25 т) (§7 ассетов)
        if (--nextSniffCountdown <= 0) {
            nextSniffCountdown = 80 + this.random.nextInt(121);
            triggerTimedState(RodentState.SNIFF, 10);
            this.playSound(RodentSounds.SNIFF, 0.10F, getSpeciesSoundPitch());
            return;
        }
        if (--nextGroomCountdown <= 0) {
            nextGroomCountdown = 300 + this.random.nextInt(301);
            triggerTimedState(RodentState.GROOM, 25);
            return;
        }

        setRodentState(RodentState.IDLE);
    }

    public void triggerTimedState(RodentState state, int ticks) {
        this.setRodentState(state);
        this.actionAnimTicks = ticks;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (!this.getWorld().isClient() && this.getWorld() instanceof ServerWorld serverWorld) {
            Entity attacker = source.getAttacker();
            Colony myColony = getColony(serverWorld);

            if (myColony != null && attacker != null) {
                myColony.triggerAlert(this.getBlockPos(), attacker.getType().getTranslationKey(), serverWorld.getTime());
                this.playSound(RodentSounds.ALARM, 0.30F, getSpeciesSoundPitch());
            }

            // Ограничение потерь в стычке грызунов: не более 2 особей с каждой стороны (§7.3, §15)
            if (attacker instanceof RodentEntity enemyRodent && myColony != null) {
                int enemyColonyId = enemyRodent.getColonyId();
                if (CoalitionState.get(serverWorld).areAllies(myColony.getColonyId(), enemyColonyId)) {
                    // Союзники по коалиции никогда не наносят урона друг другу (§7.9)
                    return false;
                }
                if (this.getHealth() - amount <= 0.0F) {
                    if (WarManager.isLossCapReachedForFight(serverWorld, enemyColonyId, myColony.getColonyId(), myColony.getColonyId())) {
                        this.setHealth(1.0F);
                        this.setRaidFighter(false, null);
                        this.setRodentState(RodentState.RUN);
                        if (this.homePos != null) {
                            this.getNavigation().startMovingTo(homePos.getX() + 0.5D, homePos.getY(), homePos.getZ() + 0.5D, 1.3D);
                        }
                        return false;
                    }
                }
            }
        }
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        if (!this.getWorld().isClient() && this.getWorld() instanceof ServerWorld serverWorld) {
            Colony myColony = getColony(serverWorld);
            Entity attacker = damageSource.getAttacker();
            if (myColony != null) {
                if (attacker instanceof PlayerEntity) {
                    myColony.incrementPlayerKills(); // Для оппортунизма соседей (§7.2)
                } else if (attacker instanceof RodentEntity enemyRodent && enemyRodent.getColonyId() > 0) {
                    WarManager.recordCombatDeath(serverWorld, enemyRodent.getColonyId(), myColony.getColonyId());
                }
                myColony.removeMember(this.getUuid(), serverWorld.getTime());
                ColonyState.get(serverWorld).markDirty();
            }
            dropRodentLoot(serverWorld);
        }
        super.onDeath(damageSource);
    }

    /**
     * Дроп при смерти (§1):
     * - мышь: 0–1 семена + несомый предмет/блок;
     * - крыса: 0–1 шкурка + несомый предмет/блок.
     */
    private void dropRodentLoot(ServerWorld world) {
        if (this.random.nextBoolean()) {
            ItemStack trophy = getSpecies() == RodentSpecies.MOUSE
                    ? new ItemStack(Items.WHEAT_SEEDS, 1)
                    : new ItemStack(Items.RABBIT_HIDE, 1);
            this.dropStack(trophy);
        }

        if (!carriedStack.isEmpty()) {
            this.dropStack(carriedStack.copy());
            carriedStack = ItemStack.EMPTY;
        } else if (getCarryLoadType().isCarrying() && getCarryLoadType() != CarryLoadType.FOOD) {
            BlockState carriedBlock = getCarryLoadType().getDefaultBlockState();
            if (!carriedBlock.isAir()) {
                this.dropStack(new ItemStack(carriedBlock.getBlock().asItem(), 1));
            }
        }
        setCarryLoadType(CarryLoadType.NONE);
    }

    public float getSpeciesSoundPitch() {
        float base = getSpecies() == RodentSpecies.MOUSE
                ? RodentSounds.MOUSE_BASE_PITCH
                : RodentSounds.RAT_BASE_PITCH;
        if (this.isBaby()) {
            base *= RodentSounds.BABY_PITCH_MULTIPLIER;
        }
        return base;
    }

    @Override
    public float getSoundPitch() {
        return getSpeciesSoundPitch() + (this.random.nextFloat() - 0.5F) * 0.08F;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        if (getRodentState() == RodentState.SLEEP) {
            return RodentSounds.BREATH;
        }
        return RodentSounds.SQUEAK;
    }

    @Override
    protected float getSoundVolume() {
        return getRodentState() == RodentState.SLEEP ? 0.06F : 0.12F;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return RodentSounds.HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return RodentSounds.DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // Материальный звук шага от блока + наш step слоем (rodents_mod_assets.md §9.3)
        BlockSoundGroup soundGroup = state.getSoundGroup();
        this.playSound(soundGroup.getStepSound(), 0.05F, getSpeciesSoundPitch());
        this.playSound(RodentSounds.STEP, 0.08F, getSpecies() == RodentSpecies.MOUSE ? 1.5F : 1.0F);
    }

    public Colony getColony(ServerWorld world) {
        int id = getColonyId();
        if (id <= 0) {
            return null;
        }
        return ColonyState.get(world).getColony(id);
    }

    public int getColonyId() {
        return this.dataTracker.get(COLONY_ID);
    }

    public void setColonyId(int colonyId) {
        this.dataTracker.set(COLONY_ID, colonyId);
    }

    public BlockPos getHomePos() {
        return homePos;
    }

    public void setHomePos(BlockPos pos) {
        this.homePos = pos != null ? pos.toImmutable() : BlockPos.ORIGIN;
    }

    public RodentState getRodentState() {
        int frozen = this.dataTracker.get(FROZEN_DEBUG_STATE);
        if (frozen >= 0) {
            return RodentState.byId(frozen);
        }
        return RodentState.byId(this.dataTracker.get(RODENT_STATE));
    }

    public void setRodentState(RodentState state) {
        if (state != null) {
            this.dataTracker.set(RODENT_STATE, state.getId());
        }
    }

    public void setFrozenDebugState(RodentState state) {
        this.dataTracker.set(FROZEN_DEBUG_STATE, state == null ? -1 : state.getId());
        if (state != null) {
            this.dataTracker.set(RODENT_STATE, state.getId());
        }
    }

    public boolean isDebugStateFrozen() {
        return this.dataTracker.get(FROZEN_DEBUG_STATE) >= 0;
    }

    public CarryLoadType getCarryLoadType() {
        return CarryLoadType.byId(this.dataTracker.get(CARRY_LOAD_TYPE));
    }

    public void setCarryLoadType(CarryLoadType type) {
        this.dataTracker.set(CARRY_LOAD_TYPE, type == null ? CarryLoadType.NONE.getId() : type.getId());
    }

    public float getCarriedFoodUnits() {
        return carriedFoodUnits;
    }

    public void setCarriedFood(float foodUnits, ItemStack visualStack) {
        this.carriedFoodUnits = Math.max(0.0F, foodUnits);
        this.carriedStack = visualStack == null ? ItemStack.EMPTY : visualStack.copy();
        this.setCarryLoadType(this.carriedFoodUnits > 0.0F ? CarryLoadType.FOOD : CarryLoadType.NONE);
    }

    public void setCarriedMaterial(CarryLoadType materialType) {
        this.carriedFoodUnits = 0.0F;
        this.carriedStack = ItemStack.EMPTY;
        this.setCarryLoadType(materialType);
    }

    public void clearCarriedLoad() {
        this.carriedFoodUnits = 0.0F;
        this.carriedStack = ItemStack.EMPTY;
        this.setCarryLoadType(CarryLoadType.NONE);
    }

    public RodentCoatVariant getCoatVariant() {
        return RodentCoatVariant.byIndex(this.dataTracker.get(COAT_VARIANT));
    }

    public void setCoatVariant(RodentCoatVariant variant) {
        this.dataTracker.set(COAT_VARIANT, variant == null ? 0 : variant.getIndex());
    }

    public boolean isRefugee() {
        return this.dataTracker.get(REFUGEE);
    }

    public void setRefugee(boolean refugee) {
        this.dataTracker.set(REFUGEE, refugee);
    }

    public boolean isRaidFighter() {
        return raidFighter;
    }

    public BlockPos getRaidTargetPos() {
        return raidTargetPos;
    }

    public void setRaidFighter(boolean raidFighter, BlockPos targetPos) {
        this.raidFighter = raidFighter;
        this.raidTargetPos = targetPos != null ? targetPos.toImmutable() : null;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("ColonyId", getColonyId());
        nbt.putLong("HomePos", homePos.asLong());
        nbt.putInt("RodentState", getRodentState().getId());
        nbt.putInt("FrozenDebugState", this.dataTracker.get(FROZEN_DEBUG_STATE));
        nbt.putInt("CarryLoadType", getCarryLoadType().getId());
        nbt.putFloat("CarriedFoodUnits", carriedFoodUnits);
        if (!carriedStack.isEmpty()) {
            nbt.put("CarriedItem", carriedStack.writeNbt(new NbtCompound()));
        }
        nbt.putInt("CoatVariant", getCoatVariant().getIndex());
        nbt.putBoolean("Refugee", isRefugee());
        nbt.putBoolean("RaidFighter", raidFighter);
        if (raidTargetPos != null) {
            nbt.putLong("RaidTargetPos", raidTargetPos.asLong());
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        setColonyId(nbt.getInt("ColonyId"));
        if (nbt.contains("HomePos")) {
            this.homePos = BlockPos.fromLong(nbt.getLong("HomePos"));
        }
        setRodentState(RodentState.byId(nbt.getInt("RodentState")));
        if (nbt.contains("FrozenDebugState")) {
            this.dataTracker.set(FROZEN_DEBUG_STATE, nbt.getInt("FrozenDebugState"));
        }
        setCarryLoadType(CarryLoadType.byId(nbt.getInt("CarryLoadType")));
        this.carriedFoodUnits = nbt.getFloat("CarriedFoodUnits");
        if (nbt.contains("CarriedItem")) {
            this.carriedStack = ItemStack.fromNbt(nbt.getCompound("CarriedItem"));
        } else {
            this.carriedStack = ItemStack.EMPTY;
        }
        setCoatVariant(RodentCoatVariant.byIndex(nbt.getInt("CoatVariant")));
        setRefugee(nbt.getBoolean("Refugee"));
        this.raidFighter = nbt.getBoolean("RaidFighter");
        if (nbt.contains("RaidTargetPos")) {
            this.raidTargetPos = BlockPos.fromLong(nbt.getLong("RaidTargetPos"));
        }
    }
}
