package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.team.TeamData;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settles node resource output (ammo and fuel) into team stockpiles. Only ticks while the game
 * phase is RUNNING; a paused game neither gains nor accumulates catch-up time.
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
    }

    /**
     * Credits both resources of every node that belongs to an existing team. Returns the per-team
     * gains actually applied.
     */
    public Map<String, ResourceData.Stock> settle(MinecraftServer server, double elapsedSeconds) {
        Map<String, ResourceData.Stock> gains = new LinkedHashMap<>();
        if (server == null || elapsedSeconds <= 0.0D) {
            return gains;
        }
        TeamData teams = TeamData.get(server);
        for (MapData.Node node : MapData.get(server).nodes()) {
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
        if (!gains.isEmpty()) {
            ResourceData.get(server).addStocks(gains);
        }
        if (Config.resourceDebugMode && !gains.isEmpty()) {
            LOGGER.info("War Project resource settlement ({}s): {}", elapsedSeconds, gains);
        }
        return gains;
    }
}
