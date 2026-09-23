package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.team.TeamData;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settles node resource output into personal stockpiles. A node pays its owning team's output to
 * every online member in full (online N members -> N times the output). Only ticks while the game
 * phase is RUNNING, and offline members never accumulate.
 */
public final class ResourceService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ResourceService active;

    private int pendingTicks;

    public static ResourceService active() {
        if (active == null) {
            active = new ResourceService();
        }
        return active;
    }

    public static void clearActive() {
        active = null;
    }

    public static int settleIntervalTicks() {
        return Math.max(1, (int) Math.round(Config.resourceSettleIntervalSeconds * 20.0D));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!GameStateService.active().isRunning()) {
            pendingTicks = 0;
            return;
        }
        pendingTicks++;
        if (pendingTicks < settleIntervalTicks()) {
            return;
        }
        double elapsedSeconds = pendingTicks / 20.0D;
        pendingTicks = 0;
        settle(event.getServer(), elapsedSeconds);
        ResourceApi.pushSyncAll(event.getServer());
    }

    /**
     * Credits every online member of a node-owning team with the full team gain for this pass.
     * Returns the per-player gains actually applied.
     */
    public Map<String, ResourceData.Stock> settle(MinecraftServer server, double elapsedSeconds) {
        Map<String, ResourceData.Stock> playerGains = new LinkedHashMap<>();
        if (server == null || elapsedSeconds <= 0.0D) {
            return playerGains;
        }
        Map<String, ResourceData.Stock> teamGains = teamGains(server, elapsedSeconds);
        if (teamGains.isEmpty()) {
            return playerGains;
        }
        TeamData teams = TeamData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getScoreboardName();
            String teamId = teams.teamOf(name).orElse(null);
            if (teamId == null) {
                continue;
            }
            ResourceData.Stock gain = teamGains.get(teamId);
            if (gain == null) {
                continue;
            }
            ResourceData.Stock current = playerGains.getOrDefault(name, ResourceData.Stock.empty());
            playerGains.put(name, new ResourceData.Stock(current.ammo() + gain.ammo(), current.fuel() + gain.fuel()));
        }
        if (!playerGains.isEmpty()) {
            ResourceData.get(server).addStocksForPlayers(playerGains);
        }
        if (Config.resourceDebugMode && !playerGains.isEmpty()) {
            LOGGER.info("War Project resource settlement ({}s): {}", elapsedSeconds, playerGains);
        }
        return playerGains;
    }

    /** The raw per-team output of this pass, before it is copied to each online member. */
    private Map<String, ResourceData.Stock> teamGains(MinecraftServer server, double elapsedSeconds) {
        Map<String, ResourceData.Stock> gains = new LinkedHashMap<>();
        TeamData teams = TeamData.get(server);
        for (MapData.Node node : MapData.get(server).nodes()) {
            // VP nodes are objectives, not economy: they never produce, whatever their stored output says.
            if (node.vp()) {
                continue;
            }
            if (!MapData.isFaction(node.factionId())) {
                continue;
            }
            double ammoPerMinute = node.ammoPerMinute();
            double fuelPerMinute = node.fuelPerMinute();
            if (ammoPerMinute <= 0.0D && fuelPerMinute <= 0.0D) {
                continue;
            }
            String owner = MapData.normalizeFaction(node.factionId());
            if (teams.team(owner).isEmpty()) {
                continue;
            }
            ResourceData.Stock current = gains.getOrDefault(owner, ResourceData.Stock.empty());
            gains.put(owner, new ResourceData.Stock(
                    current.ammo() + Math.max(0.0D, ammoPerMinute * elapsedSeconds / 60.0D),
                    current.fuel() + Math.max(0.0D, fuelPerMinute * elapsedSeconds / 60.0D)));
        }
        return gains;
    }
}
