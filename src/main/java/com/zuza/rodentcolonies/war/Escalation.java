package com.zuza.rodentcolonies.war;

/**
 * Ступени лестницы эскалации конфликта колоний (rodents_mod_plan.md §7.2).
 */
public enum Escalation {
    NEUTRAL("neutral", "Нейтралитет"),
    TENSION("tension", "Напряжение"),
    RAIDS("raids", "Набеги"),
    WAR("war", "Война"),
    CAPTURE("capture", "Захват"),
    TRUCE("truce", "Перемирие");

    private final String id;
    private final String displayName;

    Escalation(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isActiveConflict() {
        return this == WAR || this == CAPTURE;
    }

    public static Escalation fromId(String id) {
        for (Escalation e : values()) {
            if (e.id.equalsIgnoreCase(id) || e.name().equalsIgnoreCase(id)) {
                return e;
            }
        }
        return NEUTRAL;
    }
}
