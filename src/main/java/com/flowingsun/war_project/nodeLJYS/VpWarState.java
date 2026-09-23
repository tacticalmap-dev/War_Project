package com.flowingsun.war_project.nodeLJYS;

import java.util.List;

/**
 * Immutable view of the VP war: every allied cluster (side) with its war score and every VP node with
 * its current owner and capture progress. Serialised to clients and reused by /warproject vpwar status.
 */
public record VpWarState(boolean running, double maxScore, List<Side> sides, List<NodeState> nodes) {

    /** One side of the war: an allied cluster of teams. */
    public record Side(String key, List<String> teamIds, String name, double score) {
    }

    /**
     * One VP node. {@code ownerSideKey} is empty while the node is neutral; {@code attackerSideKey} is
     * empty unless a capture is in progress.
     */
    public record NodeState(String nodeId, String name, String ownerFaction, String ownerSideKey,
                            String attackerSideKey, double capturePercent) {
    }

    public static VpWarState empty() {
        return new VpWarState(false, 0.0D, List.of(), List.of());
    }
}
