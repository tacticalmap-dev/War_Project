package com.flowingsun.war_project.nodeLJYS;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.module.GamePhase;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamData;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The VP war. Every allied cluster (a team plus its transitive allies) owns a war score that starts at
 * {@link Config#vpWarScoreStart}. Every VP node a side holds drains that side's configured points per
 * minute from each opposing side, so objectives decide the war; a side reaching zero ends the game
 * through the global phase kernel, exactly like /warproject game end does.
 *
 * <p>Only the RUNNING phase settles. STOPPED freezes the scores, ENDED wipes them (and the existing
 * ENDED handling still resets node ownership and capture progress).
 */
public final class VpWarService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Settlement period in ticks: one second. */
    private static final int SETTLE_TICKS = 20;
    private static VpWarService active;

    /** Side key (sorted team ids joined with '|') -> war score. */
    private final Map<String, Double> scores = new LinkedHashMap<>();
    private Map<String, List<String>> clusters = new LinkedHashMap<>();
    private VpWarState lastBroadcast;
    private boolean ending;
    private int tickCounter;

    public static VpWarService active() {
        if (active == null) {
            active = new VpWarService();
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
        if (!GameStateService.active().isRunning()) {
            tickCounter = 0;
            return;
        }
        if (++tickCounter < SETTLE_TICKS) {
            return;
        }
        tickCounter = 0;
        settle(event.getServer(), SETTLE_TICKS / 20.0D);
    }

    /**
     * RUNNING starts a fresh war (every side back at the configured score) and ENDED wipes it, keeping
     * the phase kernel the single owner of the game lifecycle.
     */
    public void onGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to) {
        if (to == GamePhase.RUNNING) {
            ending = false;
            tickCounter = 0;
            lastBroadcast = null;
            scores.clear();
            clusters = computeClusters(server);
            for (String key : clusters.keySet()) {
                scores.put(key, Config.vpWarScoreStart);
            }
            LOGGER.info("War Project VP war started: {} side(s), start score {}", clusters.size(), Config.vpWarScoreStart);
        } else if (to == GamePhase.ENDED) {
            ending = false;
            tickCounter = 0;
            lastBroadcast = null;
            scores.clear();
            clusters = new LinkedHashMap<>();
        }
        broadcastIfChanged(server);
    }

    private void settle(MinecraftServer server, double elapsedSeconds) {
        if (server == null || elapsedSeconds <= 0.0D) {
            return;
        }
        clusters = computeClusters(server);
        // Sides that disappeared leave the war (a later re-formation starts from scratch instead of
        // inheriting a stale score); sides that appeared enter at the configured score.
        scores.keySet().retainAll(clusters.keySet());
        for (String key : clusters.keySet()) {
            if (scores.putIfAbsent(key, Config.vpWarScoreStart) == null) {
                LOGGER.info("War Project VP war: side {} entered the war with {} points", key, Config.vpWarScoreStart);
            }
        }

        double drainPerNode = Math.max(0.0D, Config.vpWarDrainPerMinutePerNode) / 60.0D * elapsedSeconds;
        if (drainPerNode > 0.0D && clusters.size() > 1) {
            Map<String, Double> drains = new LinkedHashMap<>();
            for (MapData.Node node : MapData.get(server).nodes()) {
                if (!node.vp()) {
                    continue;
                }
                String owner = MapData.normalizeFaction(node.factionId());
                if (!MapData.isFaction(owner)) {
                    continue;
                }
                String ownerSide = sideOf(owner);
                if (ownerSide == null) {
                    continue;
                }
                for (String key : clusters.keySet()) {
                    if (!key.equals(ownerSide)) {
                        drains.merge(key, drainPerNode, Double::sum);
                    }
                }
            }
            for (Map.Entry<String, Double> entry : drains.entrySet()) {
                scores.computeIfPresent(entry.getKey(), (key, value) -> Math.max(0.0D, value - entry.getValue()));
            }
        }

        if (!ending && scores.values().stream().anyMatch(score -> score <= 0.0D)) {
            finish(server);
            return;
        }
        broadcastIfChanged(server);
    }

    /** A side reached zero: announce the winner and let the phase kernel end the game. */
    private void finish(MinecraftServer server) {
        ending = true;
        String winner = bestSide();
        LOGGER.info("War Project VP war finished: winner={}", winner == null ? "draw" : winner);
        if (server != null) {
            Component message = winner == null
                    ? Component.literal("War Project: the war ended in a draw - both sides reached zero.")
                    : Component.literal("War Project: side " + winner + " wins - the opposing war score reached zero.");
            server.getPlayerList().broadcastSystemMessage(message, false);
            GameStateService.active().transition(server, GamePhase.ENDED);
        }
        broadcastIfChanged(server);
    }

    /** The side with the highest remaining score, or null when nobody is left above zero. */
    private String bestSide() {
        String best = null;
        double bestScore = 0.0D;
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (best == null || entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return bestScore > 0.0D ? best : null;
    }

    /** Side key that holds the given team, or null when the team is not part of any side. */
    private String sideOf(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : clusters.entrySet()) {
            if (entry.getValue().contains(teamId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Allied clusters: the transitive closure of the ally relation, keyed by the sorted team ids so a
     * side keeps a stable key as long as its membership does not change.
     */
    private static Map<String, List<String>> computeClusters(MinecraftServer server) {
        Map<String, List<String>> clusters = new LinkedHashMap<>();
        if (server == null) {
            return clusters;
        }
        TeamData data = TeamData.get(server);
        Map<String, Set<String>> allies = new LinkedHashMap<>();
        for (TeamData.Team team : data.teams()) {
            Set<String> linked = new LinkedHashSet<>(team.allies());
            linked.remove(team.id());
            allies.put(team.id(), linked);
        }
        // The relation is stored on both sides, but treat it as undirected anyway so an asymmetric edit
        // cannot split a side that the players consider allied.
        for (Map.Entry<String, Set<String>> entry : allies.entrySet()) {
            for (String ally : entry.getValue()) {
                allies.computeIfAbsent(ally, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        Set<String> visited = new LinkedHashSet<>();
        List<String> starts = new ArrayList<>(allies.keySet());
        starts.sort(Comparator.naturalOrder());
        for (String start : starts) {
            if (!visited.add(start)) {
                continue;
            }
            List<String> members = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (data.team(current).isEmpty()) {
                    continue;
                }
                members.add(current);
                for (String neighbour : allies.getOrDefault(current, Set.of())) {
                    if (data.team(neighbour).isPresent() && visited.add(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            members.sort(Comparator.naturalOrder());
            clusters.put(String.join("|", members), List.copyOf(members));
        }
        return clusters;
    }

    /** The current view: fresh scores and VP node states, ready to be sent or printed. */
    public VpWarState state(MinecraftServer server) {
        List<VpWarState.Side> sides = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : clusters.entrySet()) {
            sides.add(new VpWarState.Side(entry.getKey(), entry.getValue(), String.join("+", entry.getValue()),
                    round1(scores.getOrDefault(entry.getKey(), 0.0D))));
        }
        List<VpWarState.NodeState> nodes = new ArrayList<>();
        if (server != null) {
            List<MapData.Node> vpNodes = new ArrayList<>();
            for (MapData.Node node : MapData.get(server).nodes()) {
                if (node.vp()) {
                    vpNodes.add(node);
                }
            }
            vpNodes.sort(Comparator.comparing(MapData.Node::id));
            for (MapData.Node node : vpNodes) {
                String owner = MapData.normalizeFaction(node.factionId());
                String attacker = NodeOccupationService.getPendingFaction(node.id());
                String attackerSide = attacker == null || "none".equalsIgnoreCase(attacker)
                        ? "" : nullToEmpty(sideOf(attacker));
                double required = NodeOccupationService.getRequiredProgress();
                double progress = NodeOccupationService.getProgress(node.id());
                double percent = required <= 0.0D ? 0.0D : Math.min(1.0D, Math.max(0.0D, progress / required));
                String name = node.name() == null || node.name().isBlank() ? node.id() : node.name();
                nodes.add(new VpWarState.NodeState(node.id(), name, owner,
                        nullToEmpty(sideOf(owner)), attackerSide, round2(percent)));
            }
        }
        return new VpWarState(GameStateService.active().isRunning(), round1(Config.vpWarScoreStart),
                List.copyOf(sides), List.copyOf(nodes));
    }

    /** Broadcasts only when the view actually changed, so an idle war costs nothing. */
    private void broadcastIfChanged(MinecraftServer server) {
        if (server == null) {
            return;
        }
        VpWarState state = state(server);
        if (state.equals(lastBroadcast)) {
            return;
        }
        lastBroadcast = state;
        WarProjectNetwork.broadcastVpWar(server);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
