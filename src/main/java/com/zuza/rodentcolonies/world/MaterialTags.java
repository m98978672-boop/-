package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.RodentColonies;
import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.HayBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

/**
 * Теги блоков и предметов мода (§3.2, §4, §6.1).
 */
public final class MaterialTags {
    public static final TagKey<Block> SOFT_DIGGABLE = TagKey.of(
            RegistryKeys.BLOCK, new Identifier(RodentColonies.MOD_ID, "soft_diggable"));
    public static final TagKey<Block> NEST_SUITABLE = TagKey.of(
            RegistryKeys.BLOCK, new Identifier(RodentColonies.MOD_ID, "nest_suitable"));
    public static final TagKey<Block> DEAD_WOOD = TagKey.of(
            RegistryKeys.BLOCK, new Identifier(RodentColonies.MOD_ID, "dead_wood"));
    public static final TagKey<Block> BEDDING_FOLIAGE = TagKey.of(
            RegistryKeys.BLOCK, new Identifier(RodentColonies.MOD_ID, "bedding_foliage"));

    public static final TagKey<Item> RODENT_FOOD = TagKey.of(
            RegistryKeys.ITEM, new Identifier(RodentColonies.MOD_ID, "rodent_food"));
    public static final TagKey<Item> MOUSE_FOOD = TagKey.of(
            RegistryKeys.ITEM, new Identifier(RodentColonies.MOD_ID, "mouse_food"));

    // Общие теги конвенции Fabric (#c:crops, #c:seeds, #c:foods)
    public static final TagKey<Block> CONVENTION_CROPS_BLOCK = TagKey.of(
            RegistryKeys.BLOCK, new Identifier("c", "crops"));
    public static final TagKey<Item> CONVENTION_CROPS_ITEM = TagKey.of(
            RegistryKeys.ITEM, new Identifier("c", "crops"));
    public static final TagKey<Item> CONVENTION_SEEDS_ITEM = TagKey.of(
            RegistryKeys.ITEM, new Identifier("c", "seeds"));

    private MaterialTags() {
    }

    public static void init() {
    }

    public static boolean isSoftDiggable(BlockState state) {
        return state.isIn(SOFT_DIGGABLE)
                || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.CLAY)
                || state.isOf(Blocks.SNOW_BLOCK);
    }

    public static boolean isMatureCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            int age = state.get(SweetBerryBushBlock.AGE);
            return age >= 2;
        }
        return false;
    }

    public static boolean isCropBlock(BlockState state) {
        return state.getBlock() instanceof CropBlock
                || state.isIn(BlockTags.CROPS)
                || state.isIn(CONVENTION_CROPS_BLOCK);
    }

    public static boolean isHayBlock(BlockState state) {
        return state.getBlock() instanceof HayBlock || state.isOf(Blocks.HAY_BLOCK);
    }

    public static boolean isDeadOrFallenWood(BlockState state) {
        return state.isIn(DEAD_WOOD) || state.isOf(Blocks.DEAD_BUSH) || state.isOf(Blocks.MANGROVE_ROOTS);
    }

    public static boolean isBeddingFoliage(BlockState state) {
        return state.isIn(BEDDING_FOLIAGE)
                || state.isIn(BlockTags.LEAVES)
                || state.isOf(Blocks.GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.MOSS_CARPET);
    }

    public static boolean isFoodForMouse(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.isIn(MOUSE_FOOD)
                || stack.isIn(CONVENTION_SEEDS_ITEM)
                || stack.isOf(Items.WHEAT_SEEDS)
                || stack.isOf(Items.BEETROOT_SEEDS)
                || stack.isOf(Items.MELON_SEEDS)
                || stack.isOf(Items.PUMPKIN_SEEDS)
                || stack.isOf(Items.TORCHFLOWER_SEEDS)
                || stack.isOf(Items.WHEAT)
                || stack.isOf(Items.SWEET_BERRIES)
                || stack.isOf(Items.GLOW_BERRIES)
                || stack.isOf(Items.MELON_SLICE);
    }

    public static boolean isFoodForRat(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return isFoodForMouse(stack)
                || stack.isIn(RODENT_FOOD)
                || stack.isIn(CONVENTION_CROPS_ITEM)
                || stack.getItem().isFood()
                || stack.isOf(Items.HAY_BLOCK);
    }

    /**
     * Считает ценность предмета в единицах пищи (§4):
     * семена и зёрна — 0.5, овощи и плоды — 1.0, хлеб/мясо/сено — 2.0.
     */
    public static float getFoodUnits(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0F;
        }
        RodentConfig cfg = RodentConfig.get();
        Item item = stack.getItem();
        if (item == Items.HAY_BLOCK
                || item == Items.BREAD
                || item == Items.PUMPKIN_PIE
                || item == Items.BEEF || item == Items.COOKED_BEEF
                || item == Items.PORKCHOP || item == Items.COOKED_PORKCHOP
                || item == Items.CHICKEN || item == Items.COOKED_CHICKEN
                || item == Items.MUTTON || item == Items.COOKED_MUTTON
                || item == Items.RABBIT || item == Items.COOKED_RABBIT
                || item == Items.COD || item == Items.COOKED_COD
                || item == Items.SALMON || item == Items.COOKED_SALMON) {
            return cfg.foodUnitsBreadMeatHay;
        }
        if (stack.isIn(CONVENTION_SEEDS_ITEM)
                || item == Items.WHEAT_SEEDS
                || item == Items.BEETROOT_SEEDS
                || item == Items.MELON_SEEDS
                || item == Items.PUMPKIN_SEEDS
                || item == Items.TORCHFLOWER_SEEDS
                || item == Items.PITCHER_POD
                || item == Items.WHEAT) {
            return cfg.foodUnitsSeeds;
        }
        if (isFoodForRat(stack)) {
            return cfg.foodUnitsVeggies;
        }
        return 0.0F;
    }
}
