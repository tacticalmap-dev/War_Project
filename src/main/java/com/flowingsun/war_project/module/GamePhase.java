package com.flowingsun.war_project.module;

/**
 * War Project game lifecycle phase. The phase is intentionally not persisted: every server start
 * begins at {@link #STOPPED} and an operator has to run /warproject game start.
 */
public enum GamePhase {
    STOPPED("stopped"),
    RUNNING("running"),
    ENDED("ended");

    private final String id;

    GamePhase(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static GamePhase fromId(String value) {
        if (value != null) {
            String clean = value.trim();
            for (GamePhase phase : values()) {
                if (phase.id.equalsIgnoreCase(clean)) {
                    return phase;
                }
            }
        }
        return STOPPED;
    }
}