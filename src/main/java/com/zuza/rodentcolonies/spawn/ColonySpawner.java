package com.zuza.rodentcolonies.spawn;

import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyManager;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.RodentCoatVariant;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.world.MaterialTags;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ComposterBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Естественный спавн первых колоний и основание новых колоний группами беглецов (rodents_mod_plan.md §3, §7.4).
 */
public final class ColonySpawner {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/spawner");

    private ColonySpawner() {
    }

    public static void tickSpawner(ServerWorld world) {
        RodentConfig cfg = RodentConfig.get();
        if (ColonyManager.isMsptThrottled()) {
            return;
        }
        if (!world.getGameRules().getBoolean(GameRules.DO_MOB_SPAWNING)) {
            return;
        }
        long time = world.getTime();
        if (time % cfg.spawnCheckIntervalTicks != 0L) {
            return;
        }

        ColonyState colonyState = ColonyState.get(world);
        if (colonyState.getActiveColonyCount() >= cfg.maxColoniesPerDimension) {
            return;
        }
        if (ColonyManager.countTotalRodentsInDimension(world) >= cfg.maxRodentsPerDimension) {
            return;
        }

        // Сначала проверяем, не собралась ли группа из 3+ беглецов одного вида на подходящем месте (§3, §7.4)
        if (trySettleRefugeeGroup(world, colonyState)) {
            return;
        }

        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayerEntity player = players.get(world.random.nextInt(players.size()));
        ChunkPos playerChunk = player.getChunkPos();
        int dx = world.random.nextInt(9) - 4;
        int dz = world.random.nextInt(9) - 4;
        ChunkPos targetChunk = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
        if (!world.isChunkLoaded(targetChunk.x, targetChunk.z)) {
            return;
        }

        if (ColonyManager.countLoadedRodentsInChunk(world, targetChunk) >= cfg.maxRodentsPerChunk) {
            return;
        }

        for (int attempt = 0; attempt < cfg.spawnAttemptsPerCheck; attempt++) {
            int x = targetChunk.getStartX() + world.random.nextInt(16);
            int z = targetChunk.getStartZ() + world.random.nextInt(16);
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, topY, z);

            if (isValidColonySpawnPos(world, colonyState, candidate)) {
                RodentSpecies species = world.random.nextBoolean() ? RodentSpecies.MOUSE : RodentSpecies.RAT;
                int sizeRange = Math.max(0, cfg.initialColonyMaxSize - cfg.initialColonyMinSize);
                int groupSize = cfg.initialColonyMinSize + (sizeRange > 0 ? world.random.nextInt(sizeRange + 1) : 0);
                Colony colony = ColonyManager.createOrAdoptColony(world, species, candidate);
                for (int i = 0; i < groupSize; i++) {
                    ColonyManager.spawnRodentForColony(world, colony, candidate, false);
                }
                LOGGER.info("Естественный спавн: основана колония #{} ({}, {} особей) в {}",
                        colony.getColonyId(), species.getId(), groupSize, candidate.toShortString());
                return;
            }
        }
    }

    /**
     * Группа из 3+ беглецов одного вида основывает новую колонию при наличии еды и места (§7.4).
     */
    private static boolean trySettleRefugeeGroup(ServerWorld world, ColonyState colonyState) {
        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            return false;
        }
        for (ServerPlayerEntity player : players) {
            Box searchBox = player.getBoundingBox().expand(64.0D);
            List<RodentEntity> refugees = world.getEntitiesByClass(
                    RodentEntity.class,
                    searchBox,
                    r -> r.isAlive() && r.isRefugee() && r.getColonyId() <= 0
            );
            if (refugees.size() < 3) {
                continue;
            }

            for (RodentSpecies species : RodentSpecies.values()) {
                List<RodentEntity> sameSpecies = new ArrayList<>();
                for (RodentEntity r : refugees) {
                    if (r.getSpecies() == species) {
                        sameSpecies.add(r);
                    }
                }
                if (sameSpecies.size() >= 3) {
                    RodentEntity leader = sameSpecies.get(0);
                    BlockPos pos = leader.getBlockPos();
                    if (isTooCloseToOtherColonies(colonyState, pos)) {
                        continue;
                    }
                    if (!hasNearbyFoodSource(world, pos, RodentConfig.get().spawnFoodSearchRadius)) {
                        continue;
                    }
                    Colony newColony = ColonyManager.createOrAdoptColony(world, species, pos);
                    for (RodentEntity member : sameSpecies) {
                        if (member.getBlockPos().isWithinDistance(pos, 24.0D)) {
                            member.setRefugee(false);
                            member.setColonyId(newColony.getColonyId());
                            member.setHomePos(pos);
                            member.setCoatVariant(RodentCoatVariant.toBase(member.getCoatVariant()));
                            newColony.addMember(member.getUuid());
                        }
                    }
                    colonyState.markDirty();
                    LOGGER.info("Группа из {} беглецов ({}) основала новую колонию #{} в {}",
                            newColony.getMemberCount(), species.getId(), newColony.getColonyId(), pos.toShortString());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Проверка условий естественного спавна (§3):
     * - ночь или тёмное помещение, освещение <= 7, небо перекрыто (или ночь);
     * - твёрдая земля под ногами, свободное место над головой;
     * - пища поблизости в радиусе 12 блоков;
     * - расстояние до ближайшей колонии >= 4 чанков.
     */
    public static boolean isValidColonySpawnPos(ServerWorld world, ColonyState colonyState, BlockPos pos) {
        RodentConfig cfg = RodentConfig.get();
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        if (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()) {
            return false;
        }
        BlockState ground = world.getBlockState(pos.down());
        if (!ground.isSolidBlock(world, pos.down()) || !MaterialTags.isSoftDiggable(ground)) {
            return false;
        }

        int blockLight = world.getLightLevel(LightType.BLOCK, pos);
        boolean isNight = world.isNight();
        boolean skyCovered = !world.isSkyVisible(pos);
        if (blockLight > cfg.spawnMaxLightLevel || (!isNight && !skyCovered)) {
            return false;
        }

        if (isTooCloseToOtherColonies(colonyState, pos)) {
            return false;
        }
        return hasNearbyFoodSource(world, pos, cfg.spawnFoodSearchRadius);
    }

    public static boolean isTooCloseToOtherColonies(ColonyState colonyState, BlockPos pos) {
        int minBlocks = RodentConfig.get().minColonyDistanceChunks * 16;
        double minSq = (double) minBlocks * minBlocks;
        for (Colony c : colonyState.getAllColonies()) {
            if (!c.isAbandoned() && c.getHomePos().getSquaredDistance(pos) < minSq) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasNearbyFoodSource(ServerWorld world, BlockPos center, int radius) {
        int r = Math.min(16, Math.max(4, radius));
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz += 2) {
                    BlockPos p = center.add(dx, dy, dz);
                    if (!world.isChunkLoaded(p)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(p);
                    if (MaterialTags.isMatureCrop(state)
                            || MaterialTags.isCropBlock(state)
                            || MaterialTags.isHayBlock(state)
                            || state.isOf(Blocks.FARMLAND)
                            || state.isOf(Blocks.SWEET_BERRY_BUSH)
                            || state.getBlock() instanceof ComposterBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
