package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side facade for team resources. Gameplay income and consumption go through the game phase
 * gate; the admin set/add helpers are out-of-band and work in any phase.
 */
public final class ResourceApi {
    private ResourceApi() {
    }

    public static boolean teamExists(MinecraftServer server, String teamId) {
        return TeamData.get(server).team(teamId).isPresent();
    }

    public static ResourceData.Stock stock(MinecraftServer server, String teamId) {
        return ResourceData.get(server).stock(teamId);
    }

    public static double amount(MinecraftServer server, String teamId, ResourceKind kind) {
        return ResourceData.get(server).amount(teamId, kind);
    }

    /** Stocks of every team that still exists, keyed by team id and sorted by id. */
    public static Map<String, ResourceData.Stock> stocks(MinecraftServer server) {
        ResourceData data = ResourceData.get(server);
        Map<String, ResourceData.Stock> values = new LinkedHashMap<>();
        TeamData.get(server).teams().stream()
                .map(TeamData.Team::id)
                .sorted()
                .forEach(teamId -> values.put(teamId, data.stock(teamId)));
        return values;
    }

    public static boolean set(MinecraftServer server, String teamId, ResourceKind kind, double value) {
        if (!teamExists(server, teamId) || kind == null || !Double.isFinite(value) || value < 0.0D) {
            return false;
        }
        ResourceData.get(server).setAmount(teamId, kind, value);
        return true;
    }

    public static boolean add(MinecraftServer server, String teamId, ResourceKind kind, double delta) {
        if (!teamExists(server, teamId) || kind == null || !Double.isFinite(delta) || delta <= 0.0D) {
            return false;
        }
        ResourceData.get(server).addAmount(teamId, kind, delta);
        return true;
    }

    /** Consumption path: only allowed while the game is RUNNING and the team can afford it. */
    public static boolean spend(MinecraftServer server, String teamId, ResourceKind kind, double cost) {
        if (!teamExists(server, teamId) || kind == null || !GameStateService.active().isRunning()) {
            return false;
        }
        return ResourceData.get(server).spend(teamId, kind, cost);
    }

    /**
     * Builds the client snapshot: per-team stockpiles plus the per-60s income contributed by that
     * team's nodes (the HUD shows the latter as "+xx").
     */
    public static WarProjectNetwork.ResourceSyncPacket snapshot(MinecraftServer server) {
        Map<String, double[]> rates = new LinkedHashMap<>();
        for (MapData.Node node : MapData.get(server).nodes()) {
            if (!MapData.isFaction(node.factionId())) {
                continue;
            }
            String owner = MapData.normalizeFaction(node.factionId());
            if (!teamExists(server, owner)) {
                continue;
            }
            double[] slot = rates.computeIfAbsent(owner, ignored -> new double[2]);
            slot[0] += Math.max(0.0D, node.ammoPerMinute());
            slot[1] += Math.max(0.0D, node.fuelPerMinute());
        }
        List<WarProjectNetwork.ResourceTeamEntry> entries = new ArrayList<>();
        stocks(server).forEach((teamId, stock) -> {
            double[] slot = rates.getOrDefault(teamId, new double[2]);
            entries.add(new WarProjectNetwork.ResourceTeamEntry(teamId, stock.ammo(), stock.fuel(), slot[0], slot[1]));
        });
        return new WarProjectNetwork.ResourceSyncPacket(GameStateService.active().isRunning(), List.copyOf(entries));
    }

    public static void broadcastSync(MinecraftServer server) {
        WarProjectNetwork.broadcastResources(snapshot(server));
    }

    public static void sendSync(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            WarProjectNetwork.sendResources(player, snapshot(server));
        }
    }
}
