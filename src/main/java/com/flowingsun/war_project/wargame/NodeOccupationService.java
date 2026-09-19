package com.flowingsun.war_project.wargame;

import com.flowingsun.war_project.Config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NodeOccupationService {
    private static final Map<String, Progress> PROGRESS = new LinkedHashMap<>();

    private NodeOccupationService() {
    }

    public static Optional<Progress> progress(String nodeId) {
        return nodeId == null ? Optional.empty() : Optional.ofNullable(PROGRESS.get(nodeId));
    }

    public static void setProgress(String nodeId, String attackerFactionId, String previousFactionId, boolean neutralized, double value) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        PROGRESS.put(nodeId, new Progress(nodeId, attackerFactionId, previousFactionId, neutralized, Math.max(0.0D, value)));
    }

    public static void clear(String nodeId) {
        if (nodeId != null) {
            PROGRESS.remove(nodeId);
        }
    }

    public static void renameNode(String oldNodeId, String newNodeId) {
        Progress old = PROGRESS.remove(oldNodeId);
        if (old != null) {
            PROGRESS.put(newNodeId, new Progress(newNodeId, old.attackerFactionId(), old.previousFactionId(), old.neutralized(), old.progressSeconds()));
        }
    }

    /** Drops every tracked capture progress entry; returns how many were removed. */
    public static int clearAll() {
        int tracked = PROGRESS.size();
        PROGRESS.clear();
        return tracked;
    }

    public static Set<String> getTrackedNodeIds() {
        return new LinkedHashSet<>(PROGRESS.keySet());
    }

    public static String getPendingFaction(String nodeId) {
        return progress(nodeId).map(Progress::attackerFactionId).orElse("none");
    }

    public static String getPreviousFaction(String nodeId) {
        return progress(nodeId).map(Progress::previousFactionId).orElse("none");
    }

    public static boolean isNeutralized(String nodeId) {
        return progress(nodeId).map(Progress::neutralized).orElse(false);
    }

    public static double getProgress(String nodeId) {
        return progress(nodeId).map(Progress::progressSeconds).orElse(0.0D);
    }

    public static double getRequiredProgress() {
        return Math.max(1.0D, Config.nodeCaptureBaseSeconds);
    }

    public record Progress(String nodeId, String attackerFactionId, String previousFactionId, boolean neutralized, double progressSeconds) {
    }
}
