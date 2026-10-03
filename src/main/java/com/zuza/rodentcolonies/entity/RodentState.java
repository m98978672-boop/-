package com.zuza.rodentcolonies.entity;

import java.util.Locale;

/**
 * 13 состояний особи и её анимации (rodents_mod_assets.md §7, rodents_mod_guide.md §5.5).
 * Приоритет состояний: сон -> паника -> тревога -> атака -> еда/грызение/укладка -> ходьба -> покой.
 */
public enum RodentState {
    IDLE(0, "idle"),
    SNIFF(1, "sniff"),
    GROOM(2, "groom"),
    WALK(3, "walk"),
    RUN(4, "run"),
    EAT(5, "eat"),
    PLACE_BLOCK(6, "place"),
    ALARM(7, "alarm"),
    SLEEP(8, "sleep"),
    ATTACK(9, "attack"),
    HURT(10, "hurt"),
    DEATH(11, "death"),
    BABY(12, "baby");

    private final int id;
    private final String key;

    RodentState(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public int getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public static RodentState byId(int id) {
        for (RodentState s : values()) {
            if (s.id == id) {
                return s;
            }
        }
        return IDLE;
    }

    public static RodentState fromKey(String name) {
        if (name == null) {
            return null;
        }
        String norm = name.toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "idle", "rest", "покой" -> IDLE;
            case "sniff", "нюхает" -> SNIFF;
            case "groom", "wash", "умывается" -> GROOM;
            case "walk", "ходьба" -> WALK;
            case "run", "panic", "бег", "паника" -> RUN;
            case "eat", "gnaw", "еда", "грызение" -> EAT;
            case "place", "place_block", "build", "укладка" -> PLACE_BLOCK;
            case "alarm", "alert", "тревога" -> ALARM;
            case "sleep", "сон" -> SLEEP;
            case "attack", "fight", "выпад", "драка" -> ATTACK;
            case "hurt", "damage", "урон" -> HURT;
            case "death", "dead", "смерть" -> DEATH;
            case "baby", "детёныш" -> BABY;
            default -> null;
        };
    }
}
