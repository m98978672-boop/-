package com.zuza.rodentcolonies.entity;

import net.minecraft.util.math.random.Random;

/**
 * Варианты окраса грызунов (rodents_mod_assets.md §4, §6):
 * базовые 0 и 1 (наследуются 50/50 с 10% шансом мутации),
 * а также статусные VETERAN (после 2+ выжитых войн) и MANGY (голодная/разбитая колония).
 */
public enum RodentCoatVariant {
    BASE_PRIMARY(0),   // mouse_gray / rat_agouti
    BASE_SECONDARY(1), // mouse_brown / rat_dark
    VETERAN(2),        // mouse_veteran / rat_veteran
    MANGY(3);          // mouse_mangy / rat_mangy

    private final int index;

    RodentCoatVariant(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public static RodentCoatVariant byIndex(int idx) {
        for (RodentCoatVariant v : values()) {
            if (v.index == idx) {
                return v;
            }
        }
        return BASE_PRIMARY;
    }

    /**
     * Наследование окраса: 50/50 от родителей, 10% шанс мутации в другой базовый вариант (§6).
     */
    public static RodentCoatVariant inherit(RodentCoatVariant parentA, RodentCoatVariant parentB, Random random) {
        RodentCoatVariant chosen = random.nextBoolean() ? toBase(parentA) : toBase(parentB);
        if (random.nextFloat() < 0.10F) {
            return chosen == BASE_PRIMARY ? BASE_SECONDARY : BASE_PRIMARY;
        }
        return chosen;
    }

    public static RodentCoatVariant toBase(RodentCoatVariant variant) {
        if (variant == BASE_SECONDARY) {
            return BASE_SECONDARY;
        }
        return BASE_PRIMARY;
    }
}
