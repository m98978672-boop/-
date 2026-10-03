package com.zuza.rodentcolonies.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Единая конфигурация мода rodent_colonies (§1.5, §7.8, §8.2, §9, §11).
 * Хранится в config/rodent_colonies.json на сервере без клиентских зависимостей.
 */
public final class RodentConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("rodent_colonies/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "rodent_colonies.json";

    private static RodentConfig INSTANCE = new RodentConfig();

    // --- Режимы безопасности (§8.4) ---
    public boolean safeMode = false;
    public boolean worldChangesStopped = false;
    public boolean trackPlayerPlacedBlocks = false;
    public boolean allowGnawPlayerWood = false;

    // --- Лимиты (§1.5, §2, §11) ---
    public int maxRodentsPerChunk = 10;
    public int maxRodentsPerColony = 60;
    public int maxRodentsPerDimension = 800;
    public int maxColoniesPerDimension = 24;
    public int maxActiveFightersPerDimension = 12;

    // --- Естественный спавн (§3) ---
    public int initialColonyMinSize = 3;
    public int initialColonyMaxSize = 4;
    public int spawnCheckIntervalTicks = 400;
    public int spawnAttemptsPerCheck = 8;
    public int spawnMaxLightLevel = 7;
    public int spawnFoodSearchRadius = 12;
    public int minColonyDistanceChunks = 4;

    // --- Пища и размножение (§4, §5) ---
    public float foodUnitsSeeds = 0.5F;
    public float foodUnitsVeggies = 1.0F;
    public float foodUnitsBreadMeatHay = 2.0F;
    public float foodSpoilagePercentPerGameHour = 1.0F;
    public boolean enableFoodSpoilage = true;
    public float maxCropHarvestRatioPerPatch = 0.30F;
    public float seedDropChanceDuringHaul = 0.08F;
    public int maxItemsStolenFromChest = 1;
    public float reproductionFoodThreshold = 8.0F;
    public float reproductionFoodCost = 8.0F;
    public int minLitterSize = 1;
    public int maxLitterSize = 3;
    public int pairBreedingCooldownTicks = 24000; // 1 игровой день (20 мин)
    public int babyMaturationTicks = 24000;       // 1 игровой день (20 мин)
    public int starvationLossIntervalTicks = 48000; // 1 особь за 2 игровых дня

    // --- Норы и стройка (§6) ---
    public int buildMinTicksPerBlock = 40;
    public int buildMaxTicksPerBlock = 80;
    public int maxEntranceDepth = 12;
    public int maxChamberDepth = 15;
    public int rodentsPerChamber = 10;
    public int maxChambersPerColony = 6;
    public int maxMaterialHarvestRange = 24;
    public int alertRadius = 16;
    public int maxThreatMemoryEntries = 16;
    public int threatMemoryDurationTicks = 48000; // 2 игровых дня

    // --- Бюджет изменений мира и журнал (§8.2, §8.4) ---
    public int maxBlockChangesPerColonyPerDay = 400;
    public int maxBlockChangesPerChunkTotal = 100;
    public float maxSoftBlockExcavationRatioPerChunk = 0.15F;
    public int maxBlockChangesPerDimensionPerSecond = 8;
    public int journalMaxEntries = 50000;
    public int journalRetentionGameDays = 30;
    public List<String> blacklistedBlocks = new ArrayList<>(List.of(
            "minecraft:bedrock",
            "minecraft:obsidian",
            "minecraft:crying_obsidian",
            "minecraft:reinforced_deepslate",
            "minecraft:barrier",
            "minecraft:spawner",
            "minecraft:end_portal_frame"
    ));

    // --- Природные события (§9) ---
    public boolean naturalEventsEnabled = true;
    public int naturalEventTickInterval = 100;
    public float eventSeedGrowthHours = 1.0F;
    public float eventSoilLooseningHours = 3.0F;
    public float eventFloodingHours = 24.0F;       // 1 игровой день
    public float eventWildfireHours = 120.0F;      // 5 игровых дней
    public boolean enableDestructiveFlooding = false; // Выключено по умолчанию (§9)
    public boolean enableDestructiveWildfire = false; // Выключено по умолчанию (§9)
    public boolean eventsCanAffectPlayerBuildings = false;
    public int maxWildfireBlocksPerEvent = 24;
    public int maxFloodingBlocksPerEvent = 32;
    public int floodDrainMinDays = 1;
    public int floodDrainMaxDays = 2;

    // --- Войны колоний (§7.8) ---
    public boolean warEnabled = true;
    public int warMaxActiveConflicts = 3;
    public int warMaxConflictsPerColony = 2;
    public int warMaxFighters = 6;
    public float warMaxSquadFraction = 0.30F;
    public int warProximityThresholdBlocks = 16;
    public int warMaxSearchRadiusBlocks = 64;
    public int warCooldownDays = 5;
    public int warTruceMinDays = 2;
    public int warTruceMaxDays = 5;
    public int warLossCapPerFight = 2;
    public boolean warTakesPlayerBuildings = false;

    // --- Коалиции (§7.9, Этап 5) ---
    public boolean coalitionEnabled = true;
    public int coalitionMaxColonies = 2;
    public int coalitionMaxPerDimension = 1;
    public int coalitionMaxFightersTotal = 8;
    public int coalitionMaxFightersPerAlly = 4;
    public float coalitionMinThreatStrength = 1.3F;
    public int coalitionMinDistance = 24;
    public int coalitionCooldownDays = 5;

    // --- Производительность (§11) ---
    public int colonyTickInterval = 40;
    public int conflictTickInterval = 40;
    public int squadTickInterval = 20;
    public float msptThrottleStopThreshold = 40.0F;
    public float msptThrottleResumeThreshold = 25.0F;

    public static RodentConfig get() {
        return INSTANCE;
    }

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                RodentConfig loaded = GSON.fromJson(reader, RodentConfig.class);
                if (loaded != null) {
                    loaded.validate();
                    INSTANCE = loaded;
                    LOGGER.info("Конфигурация rodent_colonies загружена из {}", path);
                }
            } catch (Exception e) {
                LOGGER.error("Ошибка чтения конфигурации {}, используются значения по умолчанию", path, e);
            }
        } else {
            save();
        }
    }

    public static void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Не удалось сохранить конфигурацию {}", path, e);
        }
    }

    private void validate() {
        maxRodentsPerChunk = Math.max(1, maxRodentsPerChunk);
        maxRodentsPerColony = Math.max(4, maxRodentsPerColony);
        maxRodentsPerDimension = Math.max(maxRodentsPerColony, maxRodentsPerDimension);
        maxColoniesPerDimension = Math.max(1, maxColoniesPerDimension);
        maxActiveFightersPerDimension = Math.max(2, maxActiveFightersPerDimension);
        colonyTickInterval = Math.max(1, colonyTickInterval);
        conflictTickInterval = Math.max(1, conflictTickInterval);
        squadTickInterval = Math.max(1, squadTickInterval);
        naturalEventTickInterval = Math.max(20, naturalEventTickInterval);
        journalMaxEntries = Math.max(100, journalMaxEntries);
        coalitionMaxColonies = 2; // Жёсткое правило §7.9: ровно 2 колонии в союзе
        if (blacklistedBlocks == null) {
            blacklistedBlocks = new ArrayList<>();
        }
    }
}
