package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

/**
 * Все правила запретов на установку и выемку блоков (rodents_mod_plan.md §6.3, §6.4, §8.2, §8.3).
 */
public final class BuildRules {
    // Оценка среднего числа мягких блоков в верхнем слое чанка (16x16x4 = 1024 блока -> 15% = ~153 блока)
    private static final int ESTIMATED_SOFT_BLOCKS_PER_CHUNK = 640;

    private BuildRules() {
    }

    /**
     * Проверяет, находится ли блок в чёрном списке или среди неприкосновенных пород (§6.3, §6.4, §8.3).
     */
    public static boolean isForbiddenBlock(BlockState state) {
        if (state == null) {
            return true;
        }
        Block block = state.getBlock();
        if (block == Blocks.BEDROCK
                || block == Blocks.OBSIDIAN
                || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.BARRIER
                || block == Blocks.SPAWNER
                || block == Blocks.END_PORTAL_FRAME
                || block == Blocks.END_PORTAL
                || block == Blocks.NETHER_PORTAL) {
            return true;
        }
        if (state.hasBlockEntity()) {
            return true; // Сундуки, бочки, печи, кровати и др. (§6.3)
        }
        // Руды неприкосновенны (§6.4, §8.3)
        if (state.isIn(BlockTags.COAL_ORES)
                || state.isIn(BlockTags.IRON_ORES)
                || state.isIn(BlockTags.COPPER_ORES)
                || state.isIn(BlockTags.GOLD_ORES)
                || state.isIn(BlockTags.REDSTONE_ORES)
                || state.isIn(BlockTags.LAPIS_ORES)
                || state.isIn(BlockTags.DIAMOND_ORES)
                || state.isIn(BlockTags.EMERALD_ORES)) {
            return true;
        }
        Identifier id = Registries.BLOCK.getId(block);
        if (id != null && RodentConfig.get().blacklistedBlocks.contains(id.toString())) {
            return true;
        }
        return false;
    }

    /**
     * Эвристика «блок постройки игрока» (§6.3): обработанный камень, доски, кирпич, стекло, двери и т.д.
     */
    public static boolean isLikelyPlayerBuildingBlock(BlockState state) {
        if (state.isIn(BlockTags.PLANKS) && !RodentConfig.get().allowGnawPlayerWood) {
            return true;
        }
        return state.isIn(BlockTags.STONE_BRICKS)
                || state.isIn(BlockTags.DOORS)
                || state.isIn(BlockTags.TRAPDOORS)
                || state.isIn(BlockTags.FENCES)
                || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.WALLS)
                || state.isIn(BlockTags.STAIRS)
                || state.isIn(BlockTags.SLABS)
                || state.isIn(BlockTags.WOOL)
                || state.isIn(BlockTags.TERRACOTTA)
                || state.isIn(BlockTags.BEDS)
                || state.isIn(BlockTags.RAILS)
                || state.isOf(Blocks.BRICKS)
                || state.isOf(Blocks.GLASS)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.CRAFTING_TABLE)
                || state.isOf(Blocks.FARMLAND); // Грядка никогда не ломается (§4, §8.3)
    }

    /**
     * Находится ли позиция внутри ванильной структуры (деревня, шахта, крепость и т.д.) (§6.3, §8.3).
     */
    public static boolean isInsideVanillaStructure(ServerWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) {
            return true;
        }
        return world.getStructureAccessor().hasStructureReferences(pos);
    }

    /**
     * Проверка: есть ли жидкость в самой точке или вплотную к ней (§6.4).
     */
    public static boolean hasAdjacentFluid(ServerWorld world, BlockPos pos) {
        if (!world.getFluidState(pos).isEmpty()) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            if (!world.getFluidState(pos.offset(dir)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка лимитов чанка (§8.2):
     * - не более 100 изменённых колониями блоков за всё время;
     * - выгрызено не более 15% мягких пород чанка.
     */
    public static boolean canModifyChunk(ServerWorld world, BlockPos pos, boolean isSoftExcavation) {
        RodentConfig cfg = RodentConfig.get();
        ColonyState state = ColonyState.get(world);
        ChunkPos chunkPos = new ChunkPos(pos);
        if (state.getChunkModifiedBlocks(chunkPos) >= cfg.maxBlockChangesPerChunkTotal) {
            return false;
        }
        if (isSoftExcavation) {
            int maxSoft = Math.max(1, (int) (ESTIMATED_SOFT_BLOCKS_PER_CHUNK * cfg.maxSoftBlockExcavationRatioPerChunk));
            if (state.getChunkSoftExcavatedBlocks(chunkPos) >= maxSoft) {
                return false;
            }
        }
        return true;
    }

    /**
     * Полная проверка разрешения на выемку/добычу блока (§6.3, §6.4).
     */
    public static boolean canExcavateAt(ServerWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        if (pos.getY() <= world.getBottomY() + 2 || pos.getY() >= world.getTopY() - 2) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || isForbiddenBlock(state) || isLikelyPlayerBuildingBlock(state)) {
            return false;
        }
        if (PlayerBlockTracker.isPlayerPlaced(world, pos)) {
            return false;
        }
        if (isInsideVanillaStructure(world, pos)) {
            return false;
        }
        if (hasAdjacentFluid(world, pos)) {
            return false;
        }
        boolean diggable = MaterialTags.isSoftDiggable(state)
                || MaterialTags.isBeddingFoliage(state)
                || MaterialTags.isDeadOrFallenWood(state)
                || MaterialTags.isHayBlock(state);
        if (!diggable) {
            return false;
        }
        return canModifyChunk(world, pos, MaterialTags.isSoftDiggable(state));
    }

    /**
     * Полная проверка разрешения на укладку блока (§6.3).
     */
    public static boolean canPlaceAt(ServerWorld world, BlockPos pos, BlockState newState, String reason) {
        if (!world.isChunkLoaded(pos) || newState == null || newState.isAir()) {
            return false;
        }
        if (pos.getY() <= world.getBottomY() + 2 || pos.getY() >= world.getTopY() - 2) {
            return false;
        }
        BlockState existing = world.getBlockState(pos);
        if (isForbiddenBlock(existing) || isLikelyPlayerBuildingBlock(existing)) {
            return false;
        }
        if (PlayerBlockTracker.isPlayerPlaced(world, pos)) {
            return false;
        }
        if (isInsideVanillaStructure(world, pos)) {
            return false;
        }
        boolean isEmergencyPlug = "emergency_plug".equals(reason);
        boolean isTempFlood = "natural_flood".equals(reason);
        if (!existing.getFluidState().isEmpty() && !isEmergencyPlug && !isTempFlood) {
            // Запрещено поверх жидкостей, кроме аварийной пробки (§6.3)
            return false;
        }
        if (!existing.isAir() && !isEmergencyPlug && !MaterialTags.isBeddingFoliage(existing)) {
            return false;
        }
        // Запрещено ставить поверх грызунов, мобов и игрока (§6.3)
        Box blockBox = new Box(pos);
        if (!world.getOtherEntities(null, blockBox, Entity::isAlive).isEmpty()) {
            return false;
        }
        return canModifyChunk(world, pos, false);
    }
}
