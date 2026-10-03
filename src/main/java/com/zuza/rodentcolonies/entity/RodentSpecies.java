package com.zuza.rodentcolonies.entity;

import java.util.Locale;

/**
 * Вид грызуна: мышь или крыса (§1).
 */
public enum RodentSpecies {
    MOUSE("mouse"),
    RAT("rat");

    private final String id;

    RodentSpecies(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static RodentSpecies fromId(String id) {
        if (id == null) {
            return MOUSE;
        }
        String norm = id.toLowerCase(Locale.ROOT);
        for (RodentSpecies s : values()) {
            if (s.id.equals(norm) || s.name().toLowerCase(Locale.ROOT).equals(norm)) {
                return s;
            }
        }
        return MOUSE;
    }
}
