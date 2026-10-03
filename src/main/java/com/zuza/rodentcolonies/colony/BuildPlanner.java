package com.zuza.rodentcolonies.colony;

import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Планировщик работ колонии со строгим порядком приоритетов (rodents_mod_plan.md §6.2):
 * 1. Еда (при запасе < 8 колония не строит вообще).
 * 2. Авария: пробка в туннеле, куда идёт вода или лава (§6.6).
 * 3. Вход и холмик (§6.4).
 * 4. Камера (§6.5, 1 на каждые 10 особей, до 6).
 * 5. Отнорок / дополнительный вход.
 * 6. Уют: подстилка и видимая кладовая из сена.
 * При активной войне приоритеты 3–6 приостанавливаются (§6.2).
 */
public final class BuildPlanner {
    private BuildPlanner() {
    }

    /**
     * Может ли колония сейчас вести плановую стройку (приоритеты 3–6).
     */
    public static boolean canColonyBuildPlanned(Colony colony) {
        RodentConfig cfg = RodentConfig.get();
        if (colony.getStorage().getFoodUnits() < cfg.reproductionFoodThreshold) {
            return false; // Приоритет 1: голодная колония не строит вообще
        }
        if (colony.isInActiveWar()) {
            return false; // Во время войны работы 3–6 приостановлены
        }
        return true;
    }

    /**
     * Проверяет наличие аварийной течи воды или лавы рядом с домом/камерами колонии (§6.6).
     * Возвращает позицию в направлении течи, куда нужно немедленно поставить пробку.
     */
    public static BlockPos findEmergencyLeakPos(ServerWorld world, Colony colony) {
        BlockPos center = colony.getHomePos();
        if (!world.isChunkLoaded(center)) {
            return null;
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(p);
                    if (state.getFluidState().isIn(FluidTags.WATER) || state.getFluidState().isIn(FluidTags.LAVA)) {
                        // Ставим пробку только если рядом есть воздушный карман норы
                        for (Direction dir : Direction.values()) {
                            if (world.getBlockState(p.offset(dir)).isAir()) {
                                return p;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Обновляет текущую стройплощадку колонии по приоритетам 3–6 (§6.2).
     */
    public static ConstructionSite ensureNextConstructionSite(Colony colony) {
        if (!canColonyBuildPlanned(colony)) {
            return null;
        }
        ConstructionSite current = colony.getConstructionSite();
        if (current != null && !current.isComplete()) {
            return current;
        }

        if (current != null && current.isComplete()) {
            onSiteCompleted(colony, current);
            colony.setConstructionSite(null);
        }

        RodentConfig cfg = RodentConfig.get();
        Direction dir = Direction.fromHorizontal(Math.abs(colony.getColonyId()) % 4);

        // Приоритет 3: Вход и холмик
        if (!colony.isEntranceBuilt()) {
            ConstructionSite entranceSite = new ConstructionSite(
                    NestBlueprint.BlueprintType.ENTRANCE_AND_MOUND,
                    colony.getPrimaryEntrance(),
                    dir
            );
            colony.setConstructionSite(entranceSite);
            return entranceSite;
        }

        // Приоритет 4: Камера (1 на каждые 10 особей, минимум 1 стартовая, максимум 6)
        int desiredChambers = Math.min(
                cfg.maxChambersPerColony,
                Math.max(1, (colony.getMemberCount() + cfg.rodentsPerChamber - 1) / cfg.rodentsPerChamber)
        );
        int builtChambers = colony.getChamberPositions().size();
        if (builtChambers < desiredChambers) {
            boolean large = colony.getSpecies() == RodentSpecies.RAT || builtChambers == 0;
            int depthOffset = Math.min(cfg.maxChamberDepth, 4 + builtChambers * 2);
            BlockPos chamberOrigin = colony.getHomePos()
                    .offset(dir, 4 + builtChambers * 4)
                    .down(Math.min(6, depthOffset / 2));
            ConstructionSite chamberSite = new ConstructionSite(
                    large ? NestBlueprint.BlueprintType.LARGE_CHAMBER_5X3X3 : NestBlueprint.BlueprintType.SMALL_CHAMBER_3X3X3,
                    chamberOrigin,
                    dir
            );
            colony.setConstructionSite(chamberSite);
            return chamberSite;
        }

        // Приоритет 5: Отнорок / дополнительный вход (если у колонии < 2 входов и >= 8 особей)
        if (colony.getEntrances().size() < 2 && colony.getMemberCount() >= 8) {
            Direction sideDir = dir.rotateYClockwise();
            BlockPos sideOrigin = colony.getHomePos().offset(sideDir, 3);
            ConstructionSite sideSite = new ConstructionSite(
                    NestBlueprint.BlueprintType.SIDE_BURROW,
                    sideOrigin,
                    sideDir
            );
            colony.setConstructionSite(sideSite);
            return sideSite;
        }

        // Приоритет 6: Уют (подстилка и видимая кладовая из сена)
        if (colony.getStorage().getMaterialCount(CarryLoadType.HAY) > 0
                && colony.getStorage().getMaterialCount(CarryLoadType.FOLIAGE) > 0
                && !colony.getChamberPositions().isEmpty()) {
            BlockPos chamberPos = colony.getChamberPositions().get(0);
            ConstructionSite cozySite = new ConstructionSite(
                    NestBlueprint.BlueprintType.COZY_BEDDING,
                    chamberPos,
                    dir
            );
            colony.setConstructionSite(cozySite);
            return cozySite;
        }

        return null;
    }

    /**
     * Определяет, какой стройматериал колонии сейчас нужнее всего для добычи.
     */
    public static CarryLoadType getNeededMaterialForHarvest(ServerWorld world, Colony colony) {
        if (findEmergencyLeakPos(world, colony) != null) {
            if (colony.getStorage().getMaterialCount(CarryLoadType.DIRT) <= 0) {
                return CarryLoadType.DIRT;
            }
        }
        ConstructionSite site = ensureNextConstructionSite(colony);
        if (site != null) {
            NestBlueprint.PlannedCell cell = site.peekNextCell();
            if (cell != null && !cell.excavate()) {
                CarryLoadType req = cell.requiredMaterial();
                if (colony.getStorage().getMaterialCount(req) <= 2) {
                    return req;
                }
            }
        }
        // Пополняем базовый запас по очереди
        if (colony.getStorage().getMaterialCount(CarryLoadType.DIRT) < 12) {
            return CarryLoadType.DIRT;
        }
        if (colony.getStorage().getMaterialCount(CarryLoadType.HAY) < 4) {
            return CarryLoadType.HAY;
        }
        if (colony.getStorage().getMaterialCount(CarryLoadType.FOLIAGE) < 4) {
            return CarryLoadType.FOLIAGE;
        }
        if (colony.getStorage().getMaterialCount(CarryLoadType.STICK) < 4) {
            return CarryLoadType.STICK;
        }
        return CarryLoadType.NONE;
    }

    private static void onSiteCompleted(Colony colony, ConstructionSite site) {
        switch (site.getType()) {
            case ENTRANCE_AND_MOUND -> colony.setEntranceBuilt(true);
            case SMALL_CHAMBER_3X3X3, LARGE_CHAMBER_5X3X3 -> colony.addChamberPosition(site.getOrigin());
            case SIDE_BURROW -> colony.addEntrance(site.getOrigin().offset(site.getFacing(), 5));
            case COZY_BEDDING -> {
            }
        }
    }
}
