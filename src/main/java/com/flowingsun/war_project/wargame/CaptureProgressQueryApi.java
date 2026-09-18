package com.flowingsun.war_project.wargame;

import com.flowingsun.war_project.map.MapData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class CaptureProgressQueryApi {
    private CaptureProgressQueryApi() {
    }

    public static Optional<CaptureProgressSnapshot> queryByChunk(MinecraftServer server, int chunkX, int chunkZ) {
        return MapData.get(server).findNodeAt(chunkX, chunkZ).map(node -> queryByNode(server, node.id()));
    }

    public static CaptureProgressSnapshot queryByNode(MinecraftServer server, String nodeId) {
        return queryByNode(server, nodeId, playersInNode(server, nodeId));
    }

    public static CaptureProgressSnapshot queryByNode(MinecraftServer server, String nodeId, List<ServerPlayer> playersInNode) {
        String currentFaction = resolveCurrentFaction(server, nodeId);
        String pendingFaction = NodeOccupationService.getPendingFaction(nodeId);
        String previousFaction = NodeOccupationService.getPreviousFaction(nodeId);
        String attackerFaction = pendingFaction;
        String defenderFaction = resolveDefenderFaction(currentFaction, previousFaction, pendingFaction);
        return build(nodeId, currentFaction, pendingFaction, previousFaction, attackerFaction, defenderFaction,
                countPlayersBySide(playersInNode, attackerFaction, defenderFaction));
    }

    public static List<ServerPlayer> playersInNode(MinecraftServer server, String nodeId) {
        Optional<MapData.Node> node = MapData.get(server).node(nodeId);
        if (node.isEmpty()) {
            return List.of();
        }
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos chunk = player.chunkPosition();
            if (node.get().chunks().contains(chunk.toLong())) {
                players.add(player);
            }
        }
        return players;
    }

    private static CaptureProgressSnapshot build(String nodeId, String currentFaction, String pendingFaction, String previousFaction,
                                                 String attackerFaction, String defenderFaction, TeamCount teamCount) {
        boolean neutralized = NodeOccupationService.isNeutralized(nodeId);
        double required = NodeOccupationService.getRequiredProgress();
        double clampedProgress = Math.max(0.0D, Math.min(required, NodeOccupationService.getProgress(nodeId)));
        double remaining = Math.max(0.0D, required - clampedProgress);
        double progressPercent = required <= 0.0D ? 0.0D : (clampedProgress / required) * 100.0D;
        return new CaptureProgressSnapshot(
                nodeId,
                currentFaction,
                pendingFaction,
                previousFaction,
                attackerFaction,
                defenderFaction,
                neutralized,
                clampedProgress,
                required,
                round2(progressPercent),
                round2(remaining),
                teamCount.attackers(),
                teamCount.defenders(),
                teamCount.others(),
                teamCount.total(),
                teamCount.factions()
        );
    }

    private static String resolveCurrentFaction(MinecraftServer server, String nodeId) {
        return MapData.get(server).node(nodeId)
                .map(node -> MapData.normalizeFaction(node.factionId()))
                .orElse("neutral");
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static String resolveDefenderFaction(String currentFaction, String previousFaction, String pendingFaction) {
        if (isFaction(currentFaction) && !currentFaction.equals(pendingFaction)) {
            return currentFaction;
        }
        if (isFaction(previousFaction) && !previousFaction.equals(pendingFaction)) {
            return previousFaction;
        }
        return "neutral";
    }

    private static boolean isFaction(String faction) {
        return MapData.isFaction(faction);
    }

    private static TeamCount countPlayersBySide(List<ServerPlayer> playersInNode, String attackerFaction, String defenderFaction) {
        int attackers = 0;
        int defenders = 0;
        int others = 0;
        int total = 0;
        TreeMap<String, Integer> factionCounts = new TreeMap<>();
        for (ServerPlayer player : playersInNode) {
            total++;
            String teamId = com.flowingsun.war_project.team.TeamApi.getPlayerTeamId(player).orElse("none");
            if (isFaction(teamId)) {
                factionCounts.merge(teamId, 1, Integer::sum);
            }
            if (isFaction(attackerFaction) && attackerFaction.equals(teamId)) {
                attackers++;
                continue;
            }
            if (isFaction(defenderFaction) && defenderFaction.equals(teamId)) {
                defenders++;
                continue;
            }
            others++;
        }
        List<FactionPresence> factions = factionCounts.entrySet().stream()
                .map(entry -> new FactionPresence(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(FactionPresence::playerCount).reversed()
                        .thenComparing(FactionPresence::factionId))
                .collect(Collectors.toList());
        return new TeamCount(attackers, defenders, others, total, factions);
    }

    private record TeamCount(int attackers, int defenders, int others, int total, List<FactionPresence> factions) {
    }

    public record FactionPresence(String factionId, int playerCount) {
    }

    public record CaptureProgressSnapshot(
            String nodeId,
            String currentFaction,
            String pendingFaction,
            String previousFaction,
            String attackerFaction,
            String defenderFaction,
            boolean neutralized,
            double progressSeconds,
            double requiredSeconds,
            double progressPercent,
            double remainingSecondsAtBaseRate,
            int attackersInNode,
            int defendersInNode,
            int othersInNode,
            int totalPlayersInNode,
            List<FactionPresence> factionsInNode
    ) {
    }
}
