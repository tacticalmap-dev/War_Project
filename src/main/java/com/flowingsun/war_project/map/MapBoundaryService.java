package com.flowingsun.war_project.map;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Out-of-map enforcement. The map area is a soft boundary: nothing blocks the player, they are simply
 * given {@link Config#outOfMapReturnSeconds} to walk back, after which they are killed like {@code /kill}.
 *
 * <p>Only the overworld is judged (that is where the map area lives), and creative/spectator players are
 * exempt. Leaving the tracked state always tells the client to clear its warning, so the HUD can never
 * get stuck. The server is the only authority here; the client merely counts down.
 */
public final class MapBoundaryService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CLEARED = -1;
    private static MapBoundaryService active;

    private final Map<UUID, Integer> outSinceTick = new HashMap<>();

    public static MapBoundaryService active() {
        if (active == null) {
            active = new MapBoundaryService();
        }
        return active;
    }

    public static void clearActive() {
        active = null;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick(event.getServer());
    }

    private void tick(MinecraftServer server) {
        Optional<MapData.Bounds> bounds = MapDivideStateApi.getBounds(server);
        int now = server.getTickCount();
        int graceTicks = graceTicks();
        Set<UUID> online = new HashSet<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (!isOutsideMap(player, bounds)) {
                clear(player);
                continue;
            }
            Integer since = outSinceTick.get(player.getUUID());
            if (since == null) {
                outSinceTick.put(player.getUUID(), now);
                WarProjectNetwork.sendOutOfMapWarning(player, graceTicks);
                continue;
            }
            if (now - since >= graceTicks) {
                LOGGER.info("War Project killed {} for staying outside the map area for {} tick(s)",
                        player.getName().getString(), graceTicks);
                player.kill();
                // The death clears the tracking; a respawn inside the area starts fresh, and one outside
                // starts a new countdown instead of killing again on the same tick.
                clear(player);
            }
        }
        // Drop players who are gone without a logged-out event reaching us.
        outSinceTick.keySet().retainAll(online);
    }

    /** True only while the player is alive, in the overworld, not exempt, and beyond the map area. */
    private static boolean isOutsideMap(ServerPlayer player, Optional<MapData.Bounds> bounds) {
        if (bounds.isEmpty()
                || player.isDeadOrDying()
                || player.isCreative()
                || player.isSpectator()
                || !Level.OVERWORLD.equals(player.level().dimension())) {
            return false;
        }
        return !bounds.get().containsBlock(player.getX(), player.getZ());
    }

    private void clear(ServerPlayer player) {
        if (outSinceTick.remove(player.getUUID()) != null) {
            WarProjectNetwork.sendOutOfMapWarning(player, CLEARED);
        }
    }

    private static int graceTicks() {
        return Math.max(1, (int) Math.round(Config.outOfMapReturnSeconds * 20.0D));
    }
}
