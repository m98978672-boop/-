package com.zuza.rodentcolonies.sound;

import com.zuza.rodentcolonies.RodentColonies;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Каталог из 14 звуковых событий мода (rodents_mod_assets.md §9.2, §9.4).
 * Питч мыши — 1.45, крысы — 0.95, детёныша — x1.35 от своего вида.
 */
public final class RodentSounds {
    public static final SoundEvent SQUEAK = register("entity.rodent.squeak", 8.0F);
    public static final SoundEvent HURT = register("entity.rodent.hurt", 16.0F);
    public static final SoundEvent DEATH = register("entity.rodent.death", 16.0F);
    public static final SoundEvent STEP = register("entity.rodent.step", 8.0F);
    public static final SoundEvent GNAW = register("entity.rodent.gnaw", 8.0F);
    public static final SoundEvent EAT = register("entity.rodent.eat", 8.0F);
    public static final SoundEvent SNIFF = register("entity.rodent.sniff", 8.0F);
    public static final SoundEvent ALARM = register("entity.rodent.alarm", 16.0F);
    public static final SoundEvent FIGHT = register("entity.rodent.fight", 16.0F);
    public static final SoundEvent WARCRY = register("entity.rodent.warcry", 16.0F);
    public static final SoundEvent BREATH = register("entity.rodent.breath", 8.0F);
    public static final SoundEvent DIG = register("entity.rodent.dig", 16.0F);
    public static final SoundEvent RUSTLE = register("entity.rodent.rustle", 16.0F);
    public static final SoundEvent WARRIOR_SQUEAK = register("entity.rodent.warriorsqueak", 16.0F);

    public static final float MOUSE_BASE_PITCH = 1.45F;
    public static final float RAT_BASE_PITCH = 0.95F;
    public static final float BABY_PITCH_MULTIPLIER = 1.35F;

    private RodentSounds() {
    }

    private static SoundEvent register(String path, float distanceToTravel) {
        Identifier id = new Identifier(RodentColonies.MOD_ID, path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id, distanceToTravel));
    }

    public static void init() {
        // Вызывается в RodentColonies#onInitialize для инициализации статических полей
    }
}
