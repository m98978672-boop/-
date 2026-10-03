package com.zuza.rodentcolonies.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zuza.rodentcolonies.RodentColonies;
import com.zuza.rodentcolonies.colony.Colony;
import com.zuza.rodentcolonies.colony.ColonyManager;
import com.zuza.rodentcolonies.colony.ColonyState;
import com.zuza.rodentcolonies.colony.ColonyStorage;
import com.zuza.rodentcolonies.colony.ConstructionSite;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.MouseEntity;
import com.zuza.rodentcolonies.entity.RatEntity;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.entity.RodentState;
import com.zuza.rodentcolonies.war.Coalition;
import com.zuza.rodentcolonies.war.CoalitionManager;
import com.zuza.rodentcolonies.war.CoalitionState;
import com.zuza.rodentcolonies.war.WarConflict;
import com.zuza.rodentcolonies.war.WarManager;
import com.zuza.rodentcolonies.war.WarState;
import com.zuza.rodentcolonies.world.ChangeJournal;
import com.zuza.rodentcolonies.world.JournalState;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/**
 * Полный набор команд /rodentcolonies ... (rodents_mod_guide.md §5.6).
 */
public final class RodentCommands {
    private RodentCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("rodentcolonies")
                .requires(source -> source.hasPermissionLevel(2))
                // /rodentcolonies info [id]
                .then(CommandManager.literal("info")
                        .executes(RodentCommands::executeGeneralInfo)
                        .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeColonyInfo(ctx, IntegerArgumentType.getInteger(ctx, "id")))))
                // /rodentcolonies reload
                .then(CommandManager.literal("reload")
                        .executes(RodentCommands::executeReload))
                // /rodentcolonies debug anim <mouse|rat> <state> | debug spawn <mouse|rat> [n]
                .then(CommandManager.literal("debug")
                        .then(CommandManager.literal("anim")
                                .then(CommandManager.argument("species", StringArgumentType.word())
                                        .then(CommandManager.argument("state", StringArgumentType.word())
                                                .executes(ctx -> executeDebugAnim(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "species"),
                                                        StringArgumentType.getString(ctx, "state")
                                                )))))
                        .then(CommandManager.literal("spawn")
                                .then(CommandManager.argument("species", StringArgumentType.word())
                                        .executes(ctx -> executeDebugSpawn(ctx, StringArgumentType.getString(ctx, "species"), 4))
                                        .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 60))
                                                .executes(ctx -> executeDebugSpawn(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "species"),
                                                        IntegerArgumentType.getInteger(ctx, "n")
                                                ))))))
                // /rodentcolonies journal
                .then(CommandManager.literal("journal")
                        .executes(RodentCommands::executeJournalSummary))
                // /rodentcolonies rollback <colonyId> [n]
                .then(CommandManager.literal("rollback")
                        .then(CommandManager.argument("colonyId", IntegerArgumentType.integer(0))
                                .executes(ctx -> executeRollback(ctx, IntegerArgumentType.getInteger(ctx, "colonyId"), 500))
                                .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 50000))
                                        .executes(ctx -> executeRollback(
                                                ctx,
                                                IntegerArgumentType.getInteger(ctx, "colonyId"),
                                                IntegerArgumentType.getInteger(ctx, "n")
                                        )))))
                // /rodentcolonies stop
                .then(CommandManager.literal("stop")
                        .executes(RodentCommands::executeStopToggle))
                // /rodentcolonies war list | war end <a> <b>
                .then(CommandManager.literal("war")
                        .then(CommandManager.literal("list")
                                .executes(RodentCommands::executeWarList))
                        .then(CommandManager.literal("end")
                                .then(CommandManager.argument("a", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("b", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeWarEnd(
                                                        ctx,
                                                        IntegerArgumentType.getInteger(ctx, "a"),
                                                        IntegerArgumentType.getInteger(ctx, "b")
                                                ))))))
                // /rodentcolonies coalition list | coalition end <a> <b>
                .then(CommandManager.literal("coalition")
                        .then(CommandManager.literal("list")
                                .executes(RodentCommands::executeCoalitionList))
                        .then(CommandManager.literal("end")
                                .then(CommandManager.argument("a", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("b", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeCoalitionEnd(
                                                        ctx,
                                                        IntegerArgumentType.getInteger(ctx, "a"),
                                                        IntegerArgumentType.getInteger(ctx, "b")
                                                ))))))
        );
    }

    private static int executeGeneralInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        RodentConfig cfg = RodentConfig.get();
        ColonyState state = ColonyState.get(world);
        WarState warState = WarState.get(world);
        CoalitionState coalitionState = CoalitionState.get(world);
        int totalRodents = ColonyManager.countTotalRodentsInDimension(world);

        source.sendFeedback(() -> Text.literal(String.format(
                "=== Rodent Colonies (%s) ===\n"
                        + "Колоний: %d / %d (всего записей: %d)\n"
                        + "Грызунов в измерении: %d / %d (лимит чанка: %d, колонии: %d)\n"
                        + "Активных войн: %d / %d | Коалиций: %d / %d\n"
                        + "Режимы: safeMode=%s, stop=%s, MSPT-тормоз=%s (%.2f мс)",
                world.getRegistryKey().getValue(),
                state.getActiveColonyCount(), cfg.maxColoniesPerDimension, state.getAllColonies().size(),
                totalRodents, cfg.maxRodentsPerDimension, cfg.maxRodentsPerChunk, cfg.maxRodentsPerColony,
                warState.countActiveWars(), cfg.warMaxActiveConflicts,
                coalitionState.countActiveCoalitions(), cfg.coalitionMaxPerDimension,
                cfg.safeMode, cfg.worldChangesStopped, ColonyManager.isMsptThrottled(),
                source.getServer().getTickTime()
        )), false);
        return 1;
    }

    private static int executeColonyInfo(CommandContext<ServerCommandSource> ctx, int colonyId) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Colony colony = ColonyState.get(world).getColony(colonyId);
        if (colony == null) {
            source.sendError(Text.literal("Колония #" + colonyId + " не найдена."));
            return 0;
        }
        ColonyStorage st = colony.getStorage();
        ConstructionSite site = colony.getConstructionSite();
        String siteInfo = site == null
                ? "нет активной стройки"
                : String.format("%s (%d/%d)", site.getType().getId(), site.getCurrentIndex(), site.getTotalCells());

        source.sendFeedback(() -> Text.literal(String.format(
                "=== Колония #%d (%s) ===\n"
                        + "Дом: %s | Входов: %d | Камер: %d | Филиалов: %d\n"
                        + "Особей: %d | Заброшена: %s | В войне: %s | Тревога: %s\n"
                        + "Еда: %.1f ед. | Склад: земля=%d, сено=%d, палки=%d, листва=%d\n"
                        + "Баланс блоков: добыто=%d, захвачено=%d, поставлено=%d, потеряно=%d (OK=%s)\n"
                        + "Стройка: %s | Дневной бюджет: %d/%d",
                colony.getColonyId(),
                colony.getSpecies().getId(),
                colony.getHomePos().toShortString(),
                colony.getEntrances().size(),
                colony.getChamberPositions().size(),
                colony.getBranchPositions().size(),
                colony.getMemberCount(),
                colony.isAbandoned(),
                colony.isInActiveWar(),
                colony.isAlertActive(world.getTime()),
                st.getFoodUnits(),
                st.getMaterialCount(CarryLoadType.DIRT),
                st.getMaterialCount(CarryLoadType.HAY),
                st.getMaterialCount(CarryLoadType.STICK),
                st.getMaterialCount(CarryLoadType.FOLIAGE),
                st.getTotalHarvestedBlocks(),
                st.getTotalCapturedBlocks(),
                st.getTotalPlacedBlocks(),
                st.getTotalLostBlocks(),
                st.verifyZeroSumBalance(),
                siteInfo,
                colony.getDailyBlockChanges(),
                RodentConfig.get().maxBlockChangesPerColonyPerDay
        )), false);
        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> ctx) {
        RodentConfig.load();
        ctx.getSource().sendFeedback(() -> Text.literal("Конфигурация rodent_colonies перезагружена."), true);
        return 1;
    }

    private static int executeDebugAnim(CommandContext<ServerCommandSource> ctx, String speciesStr, String stateStr) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        RodentSpecies species = RodentSpecies.fromId(speciesStr);
        RodentState state = RodentState.fromKey(stateStr);
        if (state == null) {
            source.sendError(Text.literal("Неизвестное состояние анимации: " + stateStr
                    + ". Допустимые: idle, sniff, groom, walk, run, eat, place, alarm, sleep, attack, hurt, death, baby"));
            return 0;
        }

        BlockPos pos = BlockPos.ofFloored(source.getPosition());
        RodentEntity entity = species == RodentSpecies.RAT
                ? new RatEntity(RodentColonies.RAT_ENTITY_TYPE, world)
                : new MouseEntity(RodentColonies.MOUSE_ENTITY_TYPE, world);
        entity.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setFrozenDebugState(state);
        if (state == RodentState.BABY) {
            entity.setBreedingAge(-24000);
        } else if (state == RodentState.PLACE_BLOCK) {
            entity.setCarryLoadType(CarryLoadType.DIRT);
        }
        world.spawnEntity(entity);

        source.sendFeedback(() -> Text.literal(String.format(
                "Создана отладочная особь (%s) с замороженной анимацией '%s' в %s",
                species.getId(), state.getKey(), pos.toShortString()
        )), true);
        return 1;
    }

    private static int executeDebugSpawn(CommandContext<ServerCommandSource> ctx, String speciesStr, int count) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        RodentSpecies species = RodentSpecies.fromId(speciesStr);
        BlockPos pos = BlockPos.ofFloored(source.getPosition());

        Colony colony = ColonyManager.createOrAdoptColony(world, species, pos);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (ColonyManager.spawnRodentForColony(world, colony, pos, false) != null) {
                spawned++;
            }
        }
        int finalSpawned = spawned;
        source.sendFeedback(() -> Text.literal(String.format(
                "Создана колония #%d (%s, %d особей) в %s",
                colony.getColonyId(), species.getId(), finalSpawned, pos.toShortString()
        )), true);
        return 1;
    }

    private static int executeJournalSummary(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        JournalState journal = JournalState.get(world);
        Map<Integer, Integer> byColony = ChangeJournal.summarizeByColony(world);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Журнал изменений мира (всего записей: ").append(journal.size()).append(") ===\n");
        if (byColony.isEmpty()) {
            sb.append("Записей пока нет.");
        } else {
            for (Map.Entry<Integer, Integer> entry : byColony.entrySet()) {
                String owner = entry.getKey() == 0 ? "Природные события" : ("Колония #" + entry.getKey());
                sb.append(" - ").append(owner).append(": ").append(entry.getValue()).append(" изм.\n");
            }
        }
        source.sendFeedback(() -> Text.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int executeRollback(CommandContext<ServerCommandSource> ctx, int colonyId, int count) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        int restored = ChangeJournal.rollbackColony(world, colonyId, count);
        source.sendFeedback(() -> Text.literal(String.format(
                "Откат колонии #%d завершён: возвращено %d правок.", colonyId, restored
        )), true);
        return restored;
    }

    private static int executeStopToggle(CommandContext<ServerCommandSource> ctx) {
        RodentConfig cfg = RodentConfig.get();
        cfg.worldChangesStopped = !cfg.worldChangesStopped;
        RodentConfig.save();
        boolean stopped = cfg.worldChangesStopped;
        ctx.getSource().sendFeedback(() -> Text.literal(stopped
                ? "АВАРИЙНЫЙ ТОРМОЗ ВКЛЮЧЁН: любые изменения мира модом rodent_colonies мгновенно остановлены."
                : "Аварийный тормоз выключен: изменения мира снова разрешены."), true);
        return 1;
    }

    private static int executeWarList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        WarState warState = WarState.get(world);

        StringBuilder sb = new StringBuilder("=== Конфликты колоний ===\n");
        int shown = 0;
        for (WarConflict c : warState.getAllConflicts()) {
            sb.append(String.format(" - #%d <-> #%d : %s (враждебность=%d, потери=%d/%d)\n",
                    c.getColonyA(), c.getColonyB(), c.getEscalation().getDisplayName(),
                    c.getHostilityScore(), c.getTotalLosses(c.getColonyA()), c.getTotalLosses(c.getColonyB())));
            shown++;
        }
        if (shown == 0) {
            sb.append("Конфликтов нет.");
        }
        source.sendFeedback(() -> Text.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int executeWarEnd(CommandContext<ServerCommandSource> ctx, int a, int b) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        WarState warState = WarState.get(world);
        ColonyState colonyState = ColonyState.get(world);
        WarConflict conflict = warState.getConflict(a, b);
        if (conflict == null) {
            source.sendError(Text.literal("Между колониями #" + a + " и #" + b + " нет активного конфликта."));
            return 0;
        }
        WarManager.enterTruce(world, warState, conflict, colonyState.getColony(a), colonyState.getColony(b), world.getTime());
        source.sendFeedback(() -> Text.literal(
                "Установлено принудительное перемирие между колониями #" + a + " и #" + b + "."
        ), true);
        return 1;
    }

    private static int executeCoalitionList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        CoalitionState state = CoalitionState.get(world);

        StringBuilder sb = new StringBuilder("=== Коалиции колоний ===\n");
        int count = 0;
        for (Coalition c : state.getAllCoalitions()) {
            sb.append(String.format(" - Союз #%d + #%d (%s) против #%d : активен=%s\n",
                    c.getAllyA(), c.getAllyB(), c.getSpecies().getId(), c.getTargetColonyId(), c.isActive()));
            count++;
        }
        if (count == 0) {
            sb.append("Коалиций нет.");
        }
        source.sendFeedback(() -> Text.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int executeCoalitionEnd(CommandContext<ServerCommandSource> ctx, int a, int b) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        CoalitionState state = CoalitionState.get(world);
        Coalition coalition = state.getCoalition(a, b);
        if (coalition == null || !coalition.isActive()) {
            source.sendError(Text.literal("Активная коалиция между #" + a + " и #" + b + " не найдена."));
            return 0;
        }
        CoalitionManager.dissolveCoalition(world, coalition, "принудительно распущена командой");
        source.sendFeedback(() -> Text.literal(
                "Коалиция между колониями #" + a + " и #" + b + " распущена (установлен кулдаун 5 дней)."
        ), true);
        return 1;
    }
}
