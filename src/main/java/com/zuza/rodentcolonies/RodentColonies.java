package com.zuza.rodentcolonies;

import com.zuza.rodentcolonies.colony.ColonyManager;
import com.zuza.rodentcolonies.command.RodentCommands;
import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.MouseEntity;
import com.zuza.rodentcolonies.entity.RatEntity;
import com.zuza.rodentcolonies.entity.RodentAttributes;
import com.zuza.rodentcolonies.sound.RodentSounds;
import com.zuza.rodentcolonies.spawn.ColonySpawner;
import com.zuza.rodentcolonies.war.CoalitionManager;
import com.zuza.rodentcolonies.war.WarManager;
import com.zuza.rodentcolonies.world.MaterialTags;
import com.zuza.rodentcolonies.world.NaturalEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Главная точка входа мода rodent_colonies (rodents_mod_guide.md §3.1, §5.1).
 */
public class RodentColonies implements ModInitializer {
    public static final String MOD_ID = "rodent_colonies";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Регистрация мыши (хитбокс 0.4 x 0.5) и крысы (хитбокс 0.6 x 0.7) (rodents_mod_assets.md §8)
    public static final EntityType<MouseEntity> MOUSE_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            new Identifier(MOD_ID, "mouse"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, MouseEntity::new)
                    .dimensions(EntityDimensions.fixed(0.4F, 0.5F))
                    .trackRangeBlocks(48)
                    .build()
    );

    public static final EntityType<RatEntity> RAT_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            new Identifier(MOD_ID, "rat"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, RatEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6F, 0.7F))
                    .trackRangeBlocks(48)
                    .build()
    );

    // Спавн-яйца только для отладки (не добавляются в творческие вкладки для обычной игры, §14, §5.1)
    public static final Item MOUSE_SPAWN_EGG = Registry.register(
            Registries.ITEM,
            new Identifier(MOD_ID, "mouse_spawn_egg"),
            new SpawnEggItem(MOUSE_ENTITY_TYPE, 0xA8A296, 0xE49A9A, new Item.Settings())
    );

    public static final Item RAT_SPAWN_EGG = Registry.register(
            Registries.ITEM,
            new Identifier(MOD_ID, "rat_spawn_egg"),
            new SpawnEggItem(RAT_ENTITY_TYPE, 0x8A7A63, 0xC9A79A, new Item.Settings())
    );

    @Override
    public void onInitialize() {
        RodentConfig.load();
        MaterialTags.init();
        RodentSounds.init();

        // Атрибуты сущностей (§5.1)
        FabricDefaultAttributeRegistry.register(MOUSE_ENTITY_TYPE, RodentAttributes.createMouseAttributes());
        FabricDefaultAttributeRegistry.register(RAT_ENTITY_TYPE, RodentAttributes.createRatAttributes());

        // Команды /rodentcolonies ... (§5.6)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                RodentCommands.register(dispatcher));

        // Серверный планировщик в основном потоке (§11): никакой многопоточности, всё со сдвигом
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 100 == 0) {
                ColonyManager.updateMsptCircuitBreaker(server);
            }
            for (ServerWorld world : server.getWorlds()) {
                ColonyManager.tickWorldColonies(world);
                ColonySpawner.tickSpawner(world);
                NaturalEvents.tickWorldEvents(world);
                WarManager.tickWorldWars(world);
                CoalitionManager.tickCoalitions(world);
            }
        });

        LOGGER.info("Мод Rodent Colonies ({}) инициализирован: мир меняется, но не исчезает.", MOD_ID);
    }
}
