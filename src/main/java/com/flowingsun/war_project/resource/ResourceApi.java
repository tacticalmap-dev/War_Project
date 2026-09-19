package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.team.TeamData;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
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
}
