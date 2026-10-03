package com.zuza.rodentcolonies.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import com.zuza.rodentcolonies.world.MaterialTags;

/**
 * Тип носимого в зубах груза (rodents_mod_plan.md §6.1, rodents_mod_assets.md §3, §5.12).
 */
public enum CarryLoadType {
    NONE(0, "none"),
    DIRT(1, "dirt"),       // земля, песок, гравий, глина, снег, мох
    HAY(2, "hay"),         // сено (подстилка и еда)
    STICK(3, "stick"),     // щепа, палки, валежник
    FOLIAGE(4, "foliage"), // листья, трава, папоротник
    FOOD(5, "food");       // семена, овощи, хлеб

    private final int id;
    private final String name;

    CarryLoadType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isCarrying() {
        return this != NONE;
    }

    public static CarryLoadType byId(int id) {
        for (CarryLoadType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        return NONE;
    }

    public static CarryLoadType fromBlockState(BlockState state) {
        if (state == null || state.isAir()) {
            return NONE;
        }
        if (MaterialTags.isHayBlock(state)) {
            return HAY;
        }
        if (MaterialTags.isDeadOrFallenWood(state) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
            return STICK;
        }
        if (MaterialTags.isBeddingFoliage(state)) {
            return FOLIAGE;
        }
        if (MaterialTags.isSoftDiggable(state)) {
            return DIRT;
        }
        return DIRT;
    }

    public BlockState getDefaultBlockState() {
        return switch (this) {
            case DIRT -> Blocks.COARSE_DIRT.getDefaultState();
            case HAY -> Blocks.HAY_BLOCK.getDefaultState();
            case STICK -> Blocks.OAK_FENCE.getDefaultState();
            case FOLIAGE -> Blocks.MOSS_CARPET.getDefaultState();
            default -> Blocks.AIR.getDefaultState();
        };
    }
}
