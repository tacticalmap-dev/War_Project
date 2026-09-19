package com.flowingsun.war_project.client;

import com.flowingsun.war_project.net.WarProjectNetwork;

/**
 * Client mirror of the server's personal resource snapshot. Purely a display cache for the HUD and
 * the transfer panel; it never decides gameplay.
 */
public final class ResourceClientState {
    private static boolean running;
    private static boolean hasTeam;
    private static java.util.List<WarProjectNetwork.TeamMemberEntry> teammates = java.util.List.of();
    private static double ammo;
    private static double fuel;
    private static double ammoPerMinute;
    private static double fuelPerMinute;
    private static int version;

    private ResourceClientState() {
    }

    public static void replace(WarProjectNetwork.ResourceSyncPacket packet) {
        running = packet.running();
        hasTeam = packet.hasTeam();
        ammo = packet.ammo();
        fuel = packet.fuel();
        ammoPerMinute = packet.ammoPerMinute();
        fuelPerMinute = packet.fuelPerMinute();
        teammates = packet.teammates();
        version++;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean hasTeam() {
        return hasTeam;
    }

    public static double ammo() {
        return ammo;
    }

    public static double fuel() {
        return fuel;
    }

    public static double amount(com.flowingsun.war_project.resource.ResourceKind kind) {
        return kind == com.flowingsun.war_project.resource.ResourceKind.FUEL ? fuel : ammo;
    }

    public static double rate(com.flowingsun.war_project.resource.ResourceKind kind) {
        return kind == com.flowingsun.war_project.resource.ResourceKind.FUEL ? fuelPerMinute : ammoPerMinute;
    }

    public static double ammoPerMinute() {
        return ammoPerMinute;
    }

    public static double fuelPerMinute() {
        return fuelPerMinute;
    }

    /** Stockpiles of the other members of the local player's team (offline members included). */
    public static java.util.List<WarProjectNetwork.TeamMemberEntry> teammates() {
        return teammates;
    }

    public static int version() {
        return version;
    }

    public static void reset() {
        running = false;
        hasTeam = false;
        teammates = java.util.List.of();
        ammo = 0.0D;
        fuel = 0.0D;
        ammoPerMinute = 0.0D;
        fuelPerMinute = 0.0D;
        version++;
    }
}
