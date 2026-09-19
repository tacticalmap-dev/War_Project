package com.flowingsun.war_project.client;

import com.flowingsun.war_project.net.WarProjectNetwork;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client mirror of the server resource snapshot. Purely a display cache for the HUD; it never
 * decides gameplay.
 */
public final class ResourceClientState {
    private static final Map<String, WarProjectNetwork.ResourceTeamEntry> teams = new LinkedHashMap<>();
    private static boolean running;
    private static int version;

    private ResourceClientState() {
    }

    public static void replace(WarProjectNetwork.ResourceSyncPacket packet) {
        teams.clear();
        for (WarProjectNetwork.ResourceTeamEntry entry : packet.teams()) {
            teams.put(entry.teamId(), entry);
        }
        running = packet.running();
        version++;
    }

    public static boolean isRunning() {
        return running;
    }

    public static Optional<WarProjectNetwork.ResourceTeamEntry> entry(String teamId) {
        return teamId == null ? Optional.empty() : Optional.ofNullable(teams.get(teamId));
    }

    public static int version() {
        return version;
    }

    public static void reset() {
        teams.clear();
        running = false;
        version++;
    }
}
