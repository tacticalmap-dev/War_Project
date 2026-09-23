package com.flowingsun.war_project.map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class MapDivideStateApi {
    private MapDivideStateApi() {
    }

    public static Optional<String> findNodeContainingChunk(MinecraftServer server, int chunkX, int chunkZ) {
        return MapData.get(server).findNodeAt(chunkX, chunkZ).map(MapData.Node::id);
    }

    public static Optional<String> findWarzoneContainingChunk(MinecraftServer server, int chunkX, int chunkZ) {
        return MapData.get(server).findWarzoneAt(chunkX, chunkZ).map(MapData.Warzone::id);
    }

    public static Optional<CompoundTag> getNode(MinecraftServer server, String nodeId) {
        return MapData.get(server).node(nodeId).map(MapData.Node::save);
    }

    public static Optional<CompoundTag> getWarzone(MinecraftServer server, String warzoneId) {
        return MapData.get(server).warzone(warzoneId).map(MapData.Warzone::save);
    }

    public static Optional<CompoundTag> getWarzoneForNode(MinecraftServer server, String nodeId) {
        return MapData.get(server).warzoneForNode(nodeId).map(MapData.Warzone::save);
    }

    /** The playable map rectangle, empty until /warproject map set has defined one. */
    public static Optional<MapData.Bounds> getBounds(MinecraftServer server) {
        return MapData.get(server).bounds();
    }

    /** Defines the playable map rectangle and pushes the new snapshot to every client. */
    public static MapData.SaveResult setBounds(MinecraftServer server, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        MapData.SaveResult result = MapData.get(server).setBounds(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        if (result.ok()) {
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return result;
    }

    public static Set<String> getAllNodeIds(MinecraftServer server) {
        return MapData.get(server).nodes().stream().map(MapData.Node::id).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static Set<String> getAllWarzoneIds(MinecraftServer server) {
        return MapData.get(server).warzones().stream().map(MapData.Warzone::id).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static Set<Long> getNodeChunkKeys(MinecraftServer server, String nodeId) {
        return MapData.get(server).node(nodeId).map(MapData.Node::chunks).orElse(Set.of());
    }

    public static MapData.SaveResult createNodeWithWarzone(MinecraftServer server, String nodeId, String nodeName, Set<Long> nodeChunks, Set<Long> warzoneChunks, int colorRgb, boolean vp) {
        MapData.SaveResult result = MapData.get(server).saveNodeWithWarzone(nodeId, nodeName, nodeChunks, warzoneChunks, colorRgb, vp);
        if (result.ok()) {
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return result;
    }

    /** Flags or clears a node's VP marker and pushes the new snapshot to every client. */
    public static boolean setNodeVp(MinecraftServer server, String nodeId, boolean vp) {
        boolean changed = MapData.get(server).setNodeVp(nodeId, vp);
        if (changed) {
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return changed;
    }

    public static boolean setNodeFaction(MinecraftServer server, String nodeId, String factionId) {
        boolean changed = MapData.get(server).setNodeFaction(nodeId, factionId);
        if (changed) {
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return changed;
    }

    public static boolean renameNode(MinecraftServer server, String oldNodeId, String newNodeId, String name) {
        boolean changed = MapData.get(server).renameNode(oldNodeId, newNodeId, name).ok();
        if (changed) {
            com.flowingsun.war_project.nodeLJYS.NodeOccupationService.renameNode(oldNodeId, newNodeId);
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return changed;
    }

    public static boolean setNodeResourceOutputs(MinecraftServer server, String nodeId, double ammoPerMinute, double fuelPerMinute) {
        boolean changed = MapData.get(server).setNodeResourceOutputs(nodeId, ammoPerMinute, fuelPerMinute);
        if (changed) {
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return changed;
    }

    /** Resets every owned node to neutral and notifies clients once when anything changed. */
    public static int resetAllNodeFactions(MinecraftServer server) {
        int changed = MapData.get(server).resetAllNodeFactions();
        if (changed > 0) {
            com.flowingsun.war_project.net.WarProjectNetwork.broadcastMap(server);
        }
        return changed;
    }

    public static boolean applyNodeCapture(MinecraftServer server, String nodeId, String factionId) {
        return setNodeFaction(server, nodeId, factionId);
    }

}
