package com.flowingsun.war_project.nodeLJYS;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.module.GamePhase;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.map.MapDivideStateApi;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamApi;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class NodeLJYSService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int INTENT_TTL_TICKS = 60;
    private static final int CAPTURE_NOTICE_CAPTURED_COLOR = 0xFF4FA3FF;
    private static final int CAPTURE_NOTICE_LOST_COLOR = 0xFFFF4D4D;
    private static NodeLJYSService active;

    private final Map<UUID, CaptureIntent> intents = new HashMap<>();
    private int tickCounter;

    public static NodeLJYSService active() {
        if (active == null) {
            active = new NodeLJYSService();
        }
        return active;
    }

    public static void clearActive() {
        active = null;
    }

    public void submitCaptureIntent(ServerPlayer player, String nodeId) {
        MinecraftServer server = player.getServer();
        if (server == null || nodeId == null || nodeId.isBlank() || !GameStateService.active().isRunning()) {
            return;
        }
        MapData.get(server).node(nodeId).ifPresent(node -> {
            if (node.chunks().contains(player.chunkPosition().toLong())) {
                intents.put(player.getUUID(), new CaptureIntent(nodeId, server.getTickCount()));
            }
        });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!GameStateService.active().isRunning()) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        tick(event.getServer());
    }

    /**
     * ENDED clears the whole board: every node goes back to neutral and all capture progress is
     * dropped. STOPPED only freezes the loop above, keeping progress and ownership.
     */
    public void onGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to) {
        if (to != GamePhase.ENDED) {
            return;
        }
        int resetNodes = MapDivideStateApi.resetAllNodeFactions(server);
        int clearedProgress = NodeOccupationService.clearAll();
        intents.clear();
        LOGGER.info("War Project game ended: reset {} node(s), cleared {} capture progress entry(ies)", resetNodes, clearedProgress);
    }

    private void tick(MinecraftServer server) {
        int now = server.getTickCount();
        intents.entrySet().removeIf(entry -> now - entry.getValue().tick() > INTENT_TTL_TICKS);

        Map<String, List<ServerPlayer>> playersByNode = playersByNode(server);
        Map<String, List<ServerPlayer>> requestedByNode = requestedPlayersByNode(server, now);

        Set<String> candidateNodeIds = new LinkedHashSet<>(requestedByNode.keySet());
        candidateNodeIds.addAll(NodeOccupationService.getTrackedNodeIds());

        for (String nodeId : candidateNodeIds) {
            Optional<MapData.Node> node = MapData.get(server).node(nodeId);
            if (node.isEmpty()) {
                NodeOccupationService.clear(nodeId);
                continue;
            }
            tickNode(server, node.get(), countFactions(requestedByNode.getOrDefault(nodeId, List.of())), playersByNode);
        }

        sendProgressUpdates(server, playersByNode);
    }

    private void tickNode(MinecraftServer server, MapData.Node node, Map<String, Integer> counts, Map<String, List<ServerPlayer>> playersByNode) {
        String nodeId = node.id();
        Optional<NodeOccupationService.Progress> tracked = NodeOccupationService.progress(nodeId);
        Optional<Leader> leader = uniqueLeader(counts);
        String owner = MapData.normalizeFaction(node.factionId());

        if (leader.isEmpty() || TeamApi.areAllied(server, owner, leader.get().teamId())) {
            recover(nodeId, tracked);
            return;
        }

        String attacker = leader.get().teamId();
        boolean sameAttacker = tracked.map(progress -> progress.attackerFactionId().equals(attacker)).orElse(false);
        String previousFaction = sameAttacker
                ? tracked.get().previousFactionId()
                : (MapData.isFaction(owner) ? owner : "neutral");
        boolean neutralized = sameAttacker && tracked.get().neutralized();
        double currentSeconds = sameAttacker ? tracked.get().progressSeconds() : 0.0D;

        double required = NodeOccupationService.getRequiredProgress();
        double rate = rateForPlayers(leader.get().count());
        double next = Math.min(required, currentSeconds + rate);

        if (!neutralized && MapData.isFaction(owner) && next >= required / 2.0D) {
            MapDivideStateApi.applyNodeCapture(server, nodeId, "neutral");
            neutralized = true;
        }
        NodeOccupationService.setProgress(nodeId, attacker, previousFaction, neutralized, next);

        if (Config.captureDebugMode) {
            LOGGER.info("Node {} capture progress {} / {} by {}", nodeId, next, required, attacker);
        }

        if (next >= required) {
            MapDivideStateApi.applyNodeCapture(server, nodeId, attacker);
            List<ServerPlayer> players = playersByNode.getOrDefault(nodeId, List.of());
            sendNodeDisplay(players, nodeId, attacker, required);
            sendCaptureNotice(server, node, players, attacker, previousFaction);
            NodeOccupationService.clear(nodeId);
            intents.entrySet().removeIf(entry -> entry.getValue().nodeId().equals(nodeId));
        }
    }

    private void recover(String nodeId, Optional<NodeOccupationService.Progress> tracked) {
        tracked.ifPresent(progress -> {
            double next = progress.progressSeconds() - Config.nodeCaptureRecoveryPerSecond;
            if (next <= 0.0D) {
                NodeOccupationService.clear(nodeId);
            } else {
                NodeOccupationService.setProgress(nodeId, progress.attackerFactionId(), progress.previousFactionId(), progress.neutralized(), next);
            }
        });
    }

    private void sendProgressUpdates(MinecraftServer server, Map<String, List<ServerPlayer>> playersByNode) {
        for (String nodeId : NodeOccupationService.getTrackedNodeIds()) {
            List<ServerPlayer> players = playersByNode.getOrDefault(nodeId, List.of());
            if (players.isEmpty() || NodeOccupationService.getProgress(nodeId) <= 0.0D) {
                continue;
            }
            CaptureProgressQueryApi.CaptureProgressSnapshot snapshot = CaptureProgressQueryApi.queryByNode(server, nodeId, players);
            for (ServerPlayer player : players) {
                WarProjectNetwork.sendCaptureProgress(player, snapshot);
            }
        }
    }

    private void sendNodeDisplay(List<ServerPlayer> players, String nodeId, String factionId, double requiredSeconds) {
        for (ServerPlayer player : players) {
            WarProjectNetwork.sendCaptureProgressDisplay(player, nodeId, factionId, requiredSeconds);
        }
    }

    private void sendCaptureNotice(MinecraftServer server, MapData.Node node, List<ServerPlayer> players, String attackerFaction, String defenderFaction) {
        boolean hasDefender = MapData.isFaction(defenderFaction) && !TeamApi.areAllied(server, attackerFaction, defenderFaction);
        String nodeName = node.name() == null || node.name().isBlank() ? node.id() : node.name();
        for (ServerPlayer player : players) {
            String playerFaction = TeamApi.getPlayerTeamId(player).orElse("none");
            if (TeamApi.areAllied(server, playerFaction, attackerFaction)) {
                WarProjectNetwork.sendCaptureNotice(player, "Captured " + nodeName, CAPTURE_NOTICE_CAPTURED_COLOR);
                continue;
            }
            if (hasDefender && TeamApi.areAllied(server, playerFaction, defenderFaction)) {
                WarProjectNetwork.sendCaptureNotice(player, "Lost " + nodeName, CAPTURE_NOTICE_LOST_COLOR);
            }
        }
    }

    private Map<String, List<ServerPlayer>> playersByNode(MinecraftServer server) {
        Map<String, List<ServerPlayer>> playersByNode = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MapData.get(server).findNodeAt(player.chunkPosition().x, player.chunkPosition().z)
                    .ifPresent(node -> playersByNode
                            .computeIfAbsent(node.id(), ignored -> new ArrayList<>())
                            .add(player));
        }
        return playersByNode;
    }

    private Map<String, List<ServerPlayer>> requestedPlayersByNode(MinecraftServer server, int now) {
        Map<String, List<ServerPlayer>> playersByNode = new LinkedHashMap<>();
        for (Map.Entry<UUID, CaptureIntent> entry : intents.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            String nodeId = entry.getValue().nodeId();
            Optional<MapData.Node> node = MapData.get(server).node(nodeId);
            if (node.isEmpty() || !node.get().chunks().contains(player.chunkPosition().toLong())) {
                continue;
            }
            playersByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(player);
        }
        return playersByNode;
    }

    private Map<String, Integer> countFactions(List<ServerPlayer> players) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ServerPlayer player : players) {
            TeamApi.getPlayerTeamId(player).ifPresent(teamId -> counts.merge(teamId, 1, Integer::sum));
        }
        return counts;
    }

    private static Optional<Leader> uniqueLeader(Map<String, Integer> counts) {
        Leader leader = null;
        boolean tied = false;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (leader == null || entry.getValue() > leader.count()) {
                leader = new Leader(entry.getKey(), entry.getValue());
                tied = false;
            } else if (entry.getValue() == leader.count()) {
                tied = true;
            }
        }
        return leader == null || tied ? Optional.empty() : Optional.of(leader);
    }

    private static double rateForPlayers(int count) {
        double multiplier = 1.0D + Math.max(0, count - 1) * Config.capturePlayerCountRateMultiplier;
        return Math.min(multiplier, Config.capturePlayerCountRateMultiplierCap);
    }

    private record CaptureIntent(String nodeId, int tick) {
    }

    private record Leader(String teamId, int count) {
    }
}
