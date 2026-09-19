package com.flowingsun.war_project.resource;

import java.util.Optional;

/** The two resource kinds a node can produce. Ids are the command tokens. */
public enum ResourceKind {
    AMMO("ammo"),
    FUEL("fuel");

    private final String id;

    ResourceKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<ResourceKind> parse(String value) {
        if (value != null) {
            String clean = value.trim();
            for (ResourceKind kind : values()) {
                if (kind.id.equalsIgnoreCase(clean)) {
                    return Optional.of(kind);
                }
            }
        }
        return Optional.empty();
    }
}
