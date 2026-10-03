package com.zuza.rodentcolonies.colony;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Активная стройплощадка колонии и её прогресс (§6.2, §6.5).
 */
public class ConstructionSite {
    private NestBlueprint.BlueprintType type;
    private BlockPos origin;
    private Direction facing;
    private int currentIndex;
    private List<NestBlueprint.PlannedCell> cells;

    public ConstructionSite(NestBlueprint.BlueprintType type, BlockPos origin, Direction facing) {
        this.type = type;
        this.origin = origin.toImmutable();
        this.facing = facing;
        this.currentIndex = 0;
        this.cells = generateCells(type, facing);
    }

    private static List<NestBlueprint.PlannedCell> generateCells(NestBlueprint.BlueprintType type, Direction facing) {
        return switch (type) {
            case ENTRANCE_AND_MOUND -> NestBlueprint.buildEntranceAndMound(facing, 8);
            case SMALL_CHAMBER_3X3X3 -> NestBlueprint.buildChamber(false);
            case LARGE_CHAMBER_5X3X3 -> NestBlueprint.buildChamber(true);
            case SIDE_BURROW -> NestBlueprint.buildSideBurrow(facing);
            case COZY_BEDDING -> NestBlueprint.buildCozyBedding();
        };
    }

    public boolean isComplete() {
        return cells == null || currentIndex >= cells.size();
    }

    public NestBlueprint.PlannedCell peekNextCell() {
        if (isComplete()) {
            return null;
        }
        return cells.get(currentIndex);
    }

    public BlockPos getNextWorldPos() {
        NestBlueprint.PlannedCell cell = peekNextCell();
        if (cell == null) {
            return origin;
        }
        return origin.add(cell.relativePos());
    }

    public void advance() {
        if (!isComplete()) {
            currentIndex++;
        }
    }

    public NestBlueprint.BlueprintType getType() {
        return type;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public Direction getFacing() {
        return facing;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getTotalCells() {
        return cells == null ? 0 : cells.size();
    }

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putString("Type", type.getId());
        tag.putLong("Origin", origin.asLong());
        tag.putString("Facing", facing.getName());
        tag.putInt("Index", currentIndex);
        return tag;
    }

    public static ConstructionSite fromNbt(NbtCompound tag) {
        NestBlueprint.BlueprintType type = NestBlueprint.BlueprintType.fromId(tag.getString("Type"));
        BlockPos origin = BlockPos.fromLong(tag.getLong("Origin"));
        Direction dir = Direction.byName(tag.getString("Facing"));
        if (dir == null || dir.getAxis().isVertical()) {
            dir = Direction.NORTH;
        }
        ConstructionSite site = new ConstructionSite(type, origin, dir);
        site.currentIndex = Math.max(0, tag.getInt("Index"));
        return site;
    }
}
