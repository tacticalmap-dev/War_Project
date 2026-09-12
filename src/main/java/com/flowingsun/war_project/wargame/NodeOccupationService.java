package com.flowingsun.war_project.wargame;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class NodeOccupationService {
    private static final Map<String, Progress> PROGRESS = new LinkedHashMap<>();

    private NodeOccupationService() {
    }

    public static Optional<Progress> progress(String nodeId) {
        return Optional.ofNullable(PROGRESS.get(nodeId));
    }

    public static void setProgress(String nodeId, String attackerFactionId, double value) {
        PROGRESS.put(nodeId, new Progress(nodeId, attackerFactionId, Math.max(0.0D, value)));
    }

    public static void clear(String nodeId) {
        PROGRESS.remove(nodeId);
    }

    public static void renameNode(String oldNodeId, String newNodeId) {
        Progress old = PROGRESS.remove(oldNodeId);
        if (old != null) {
            PROGRESS.put(newNodeId, new Progress(newNodeId, old.attackerFactionId(), old.progressSeconds()));
        }
    }

    public record Progress(String nodeId, String attackerFactionId, double progressSeconds) {
    }
}
