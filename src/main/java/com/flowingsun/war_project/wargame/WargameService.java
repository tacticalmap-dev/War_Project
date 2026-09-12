package com.flowingsun.war_project.wargame;

import com.flowingsun.war_project.Config;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WargameService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int INTENT_TTL_TICKS = 60;
    private static WargameService active;

    private final Map<UUID, CaptureIntent> intents = new HashMap<>();
    private int tickCounter;

    public static WargameService active() {
        if (active == null) {
            active = new WargameService();
        }
        return active;
    }

    public static void clearActive() {
        active = null;
    }

    public void submitCaptureIntent(ServerPlayer player, String nodeId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        MapData.get(server).node(nodeId).ifPresent(node -> {
            long currentChunk = player.chunkPosition().toLong();
            if (node.chunks().contains(currentChunk)) {
                intents.put(player.getUUID(), new CaptureIntent(nodeId, server.getTickCount()));
            }
        });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        tick(event.getServer());
    }

    private void tick(MinecraftServer server) {
        int now = server.getTickCount();
        intents.entrySet().removeIf(entry -> now - entry.getValue().tick() > INTENT_TTL_TICKS);

        Map<String, Map<String, Integer>> countsByNode = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CaptureIntent intent = intents.get(player.getUUID());
            if (intent == null) {
                continue;
            }
            Optional<MapData.Node> node = MapData.get(server).node(intent.nodeId());
            if (node.isEmpty() || !node.get().chunks().contains(player.chunkPosition().toLong())) {
                continue;
            }
            Optional<String> teamId = TeamApi.getPlayerTeamId(player);
            if (teamId.isEmpty()) {
                continue;
            }
            countsByNode.computeIfAbsent(intent.nodeId(), ignored -> new LinkedHashMap<>())
                    .merge(teamId.get(), 1, Integer::sum);
        }

        for (MapData.Node node : MapData.get(server).nodes()) {
            tickNode(server, node, countsByNode.getOrDefault(node.id(), Map.of()));
        }
    }

    private void tickNode(MinecraftServer server, MapData.Node node, Map<String, Integer> counts) {
        Optional<Leader> leader = uniqueLeader(counts);
        Optional<NodeOccupationService.Progress> current = NodeOccupationService.progress(node.id());
        if (leader.isEmpty() || TeamApi.areAllied(server, node.factionId(), leader.get().teamId())) {
            recover(node.id(), current);
            return;
        }

        String attacker = leader.get().teamId();
        double currentSeconds = current
                .filter(progress -> progress.attackerFactionId().equals(attacker))
                .map(NodeOccupationService.Progress::progressSeconds)
                .orElse(0.0D);
        double rate = rateForPlayers(leader.get().count());
        double next = Math.min(Config.nodeCaptureBaseSeconds, currentSeconds + rate);
        NodeOccupationService.setProgress(node.id(), attacker, next);
        WarProjectNetwork.broadcastCaptureProgress(server, node.id(), attacker, next, Config.nodeCaptureBaseSeconds);

        if (Config.captureDebugMode) {
            LOGGER.info("Node {} capture progress {} / {} by {}", node.id(), next, Config.nodeCaptureBaseSeconds, attacker);
        }
        if (shouldNeutralize(node, next)) {
            MapDivideStateApi.applyNodeCapture(server, node.id(), "neutral");
        }
        if (next >= Config.nodeCaptureBaseSeconds) {
            MapDivideStateApi.applyNodeCapture(server, node.id(), attacker);
            NodeOccupationService.clear(node.id());
            WarProjectNetwork.broadcastCaptureProgress(server, node.id(), attacker, 0.0D, Config.nodeCaptureBaseSeconds);
        }
    }

    private void recover(String nodeId, Optional<NodeOccupationService.Progress> current) {
        current.ifPresent(progress -> {
            double next = progress.progressSeconds() - Config.nodeCaptureRecoveryPerSecond;
            if (next <= 0.0D) {
                NodeOccupationService.clear(nodeId);
            } else {
                NodeOccupationService.setProgress(nodeId, progress.attackerFactionId(), next);
            }
        });
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

    private static boolean shouldNeutralize(MapData.Node node, double progressSeconds) {
        return progressSeconds >= Config.nodeCaptureBaseSeconds * 0.5D
                && MapData.isFaction(node.factionId());
    }

    private record CaptureIntent(String nodeId, int tick) {
    }

    private record Leader(String teamId, int count) {
    }
}
