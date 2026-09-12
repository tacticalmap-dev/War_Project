package com.flowingsun.war_project.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public final class TeamApi {
    private TeamApi() {
    }

    public static Optional<String> getPlayerTeamId(ServerPlayer player) {
        return TeamData.get(player.getServer()).teamOf(player.getScoreboardName());
    }

    public static boolean isValidFaction(MinecraftServer server, String factionId) {
        String clean = TeamData.cleanId(factionId);
        return "neutral".equalsIgnoreCase(clean)
                || "none".equalsIgnoreCase(clean)
                || TeamData.get(server).team(clean).isPresent();
    }

    public static boolean areAllied(MinecraftServer server, String teamA, String teamB) {
        return TeamData.get(server).areAllied(teamA, teamB);
    }

    public static void broadcast(MinecraftServer server) {
        com.flowingsun.war_project.net.WarProjectNetwork.broadcastTeams(server);
    }
}
