package com.zuza.rodentcolonies.colony;

import com.zuza.rodentcolonies.entity.CarryLoadType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Чертежи нор (rodents_mod_plan.md §6.4, §6.5):
 * - Входной наклонный подкоп (до 12 блоков вглубь) и холмик над входом (1–2 блока высотой с лазом).
 * - Малая камера 3x3x3 и большая камера 5x3x3 (глубина до 15 блоков).
 * Порядок постройки: сначала стены и потолок по чертежу, затем выемка внутреннего объёма,
 * потом подстилка (сено/листва) и кладовая из сена, входы открываются последними.
 */
public final class NestBlueprint {
    public enum BlueprintType {
        ENTRANCE_AND_MOUND("entrance_mound"),
        SMALL_CHAMBER_3X3X3("chamber_3x3x3"),
        LARGE_CHAMBER_5X3X3("chamber_5x3x3"),
        SIDE_BURROW("side_burrow"),
        COZY_BEDDING("cozy_bedding");

    private final String id;

        BlueprintType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static BlueprintType fromId(String id) {
            for (BlueprintType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return SMALL_CHAMBER_3X3X3;
        }
    }

    /**
     * Одна ячейка чертежа: либо выемка (excavate = true, блок уходит в запас материалов),
     * либо укладка (excavate = false, блок ставится из запаса материалов колонии).
     */
    public record PlannedCell(
            BlockPos relativePos,
            boolean excavate,
            CarryLoadType requiredMaterial,
            BlockState targetState,
            String reason
    ) {
    }

    private NestBlueprint() {
    }

    /**
     * Генерирует чертёж входа и защитного холмика над ним (§6.4).
     */
    public static List<PlannedCell> buildEntranceAndMound(Direction direction, int depth) {
        int clampedDepth = Math.min(12, Math.max(4, depth));
        List<PlannedCell> cells = new ArrayList<>();

        // 1. Холмик над входом (1-2 блока земли вокруг лаза)
        Direction left = direction.rotateYCounterclockwise();
        Direction right = direction.rotateYClockwise();
        cells.add(new PlannedCell(new BlockPos(left.getOffsetX(), 0, left.getOffsetZ()),
                false, CarryLoadType.DIRT, Blocks.COARSE_DIRT.getDefaultState(), "mound"));
        cells.add(new PlannedCell(new BlockPos(right.getOffsetX(), 0, right.getOffsetZ()),
                false, CarryLoadType.DIRT, Blocks.COARSE_DIRT.getDefaultState(), "mound"));
        cells.add(new PlannedCell(new BlockPos(direction.getOffsetX(), 1, direction.getOffsetZ()),
                false, CarryLoadType.DIRT, Blocks.COARSE_DIRT.getDefaultState(), "mound"));
        // Маскировка входа листвой/мхом
        cells.add(new PlannedCell(new BlockPos(direction.getOffsetX(), 2, direction.getOffsetZ()),
                false, CarryLoadType.FOLIAGE, Blocks.MOSS_CARPET.getDefaultState(), "mound"));

        // 2. Наклонный подкоп до clampedDepth блоков
        for (int step = 0; step < clampedDepth; step++) {
            int dx = direction.getOffsetX() * step;
            int dz = direction.getOffsetZ() * step;
            int dy = -(step / 2);
            BlockPos tunnelPos = new BlockPos(dx, dy, dz);
            cells.add(new PlannedCell(tunnelPos, true, CarryLoadType.DIRT, Blocks.AIR.getDefaultState(), "blueprint_entrance"));
        }
        return cells;
    }

    /**
     * Генерирует чертёж камеры 3x3x3 (малая) или 5x3x3 (большая) (§6.5).
     * Порядок: стены/потолок -> внутренняя выемка -> подстилка и кладовая из сена -> устье входа.
     */
    public static List<PlannedCell> buildChamber(boolean large) {
        int halfW = large ? 2 : 1; // 5x3x3 (w=5) или 3x3x3 (w=3)
        int halfD = 1;             // глубина 3
        int height = 3;            // высота 3

        List<PlannedCell> shellCells = new ArrayList<>();
        List<PlannedCell> interiorExcavation = new ArrayList<>();
        List<PlannedCell> beddingAndPantry = new ArrayList<>();
        List<PlannedCell> entranceOpening = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = -halfW - 1; x <= halfW + 1; x++) {
                for (int z = -halfD - 1; z <= halfD + 1; z++) {
                    boolean isWall = Math.abs(x) == halfW + 1 || Math.abs(z) == halfD + 1 || y == height - 1;
                    BlockPos rel = new BlockPos(x, y, z);

                    // Входное отверстие оставляем на самый конец (§6.5)
                    if (x == 0 && z == -halfD - 1 && y == 0) {
                        entranceOpening.add(new PlannedCell(rel, true, CarryLoadType.DIRT, Blocks.AIR.getDefaultState(), "blueprint_chamber"));
                        continue;
                    }

                    if (isWall) {
                        shellCells.add(new PlannedCell(rel, false, CarryLoadType.DIRT, Blocks.COARSE_DIRT.getDefaultState(), "blueprint_chamber"));
                    } else if (y == 0) {
                        // Пол: выемка + подстилка, а в углу — видимая кладовая из сена (§6.5)
                        interiorExcavation.add(new PlannedCell(rel, true, CarryLoadType.DIRT, Blocks.AIR.getDefaultState(), "blueprint_chamber"));
                        if (x == halfW && z == halfD) {
                            beddingAndPantry.add(new PlannedCell(rel, false, CarryLoadType.HAY, Blocks.HAY_BLOCK.getDefaultState(), "blueprint_pantry"));
                        } else if (x == 0 && z == 0) {
                            beddingAndPantry.add(new PlannedCell(rel, false, CarryLoadType.FOLIAGE, Blocks.MOSS_CARPET.getDefaultState(), "blueprint_bedding"));
                        }
                    } else {
                        interiorExcavation.add(new PlannedCell(rel, true, CarryLoadType.DIRT, Blocks.AIR.getDefaultState(), "blueprint_chamber"));
                    }
                }
            }
        }

        List<PlannedCell> ordered = new ArrayList<>(shellCells.size() + interiorExcavation.size() + beddingAndPantry.size() + entranceOpening.size());
        ordered.addAll(shellCells);
        ordered.addAll(interiorExcavation);
        ordered.addAll(beddingAndPantry);
        ordered.addAll(entranceOpening);
        return ordered;
    }

    /**
     * Чертёж отнорка / дополнительного входа (§6.2 п. 5).
     */
    public static List<PlannedCell> buildSideBurrow(Direction direction) {
        List<PlannedCell> cells = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            BlockPos pos = new BlockPos(direction.getOffsetX() * i, (i >= 3 ? 1 : 0), direction.getOffsetZ() * i);
            cells.add(new PlannedCell(pos, true, CarryLoadType.DIRT, Blocks.AIR.getDefaultState(), "blueprint_side_burrow"));
        }
        return cells;
    }

    /**
     * Чертёж уюта: дополнительная подстилка и сено в камере (§6.2 п. 6).
     */
    public static List<PlannedCell> buildCozyBedding() {
        List<PlannedCell> cells = new ArrayList<>();
        cells.add(new PlannedCell(new BlockPos(-1, 0, 0), false, CarryLoadType.HAY, Blocks.HAY_BLOCK.getDefaultState(), "blueprint_pantry"));
        cells.add(new PlannedCell(new BlockPos(1, 0, 0), false, CarryLoadType.FOLIAGE, Blocks.MOSS_CARPET.getDefaultState(), "blueprint_bedding"));
        return cells;
    }
}
