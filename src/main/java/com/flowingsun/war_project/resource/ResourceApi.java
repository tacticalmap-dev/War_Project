package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.Config;

import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamData;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side facade for personal resources. Income, spending and transfers all live behind the
 * game phase gate; the admin set/add helpers are out-of-band and work in any phase.
 */
public final class ResourceApi {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ResourceApi() {
    }

    /** A player is addressable when they are online or already have a record. */
    public static boolean playerKnown(MinecraftServer server, String playerName) {
        String clean = TeamData.cleanPlayer(playerName);
        return !clean.isBlank()
                && (server.getPlayerList().getPlayerByName(clean) != null || ResourceData.get(server).playerNames().contains(clean));
    }

    public static ResourceData.Stock stock(MinecraftServer server, String playerName) {
        return ResourceData.get(server).stock(playerName);
    }

    public static double amount(MinecraftServer server, String playerName, ResourceKind kind) {
        return ResourceData.get(server).amount(playerName, kind);
    }

    /** Stocks of every online player plus every player that still has a record, sorted by name. */
    public static Map<String, ResourceData.Stock> playerStocks(MinecraftServer server) {
        ResourceData data = ResourceData.get(server);
        java.util.TreeSet<String> names = new java.util.TreeSet<>(data.playerNames());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getScoreboardName());
        }
        Map<String, ResourceData.Stock> values = new LinkedHashMap<>();
        for (String name : names) {
            values.put(name, data.stock(name));
        }
        return values;
    }

    public static boolean set(MinecraftServer server, String playerName, ResourceKind kind, double value) {
        if (!playerKnown(server, playerName) || kind == null || !Double.isFinite(value) || value < 0.0D) {
            return false;
        }
        ResourceData.get(server).setAmount(playerName, kind, value);
        return true;
    }

    public static boolean add(MinecraftServer server, String playerName, ResourceKind kind, double delta) {
        if (!playerKnown(server, playerName) || kind == null || !Double.isFinite(delta) || delta <= 0.0D) {
            return false;
        }
        ResourceData.get(server).addAmount(playerName, kind, delta);
        return true;
    }

    /** Consumption path: only allowed while the game is RUNNING and the player can afford it. */
    public static boolean spend(MinecraftServer server, String playerName, ResourceKind kind, double cost) {
        if (!playerKnown(server, playerName) || kind == null || !GameStateService.active().isRunning()) {
            return false;
        }
        return ResourceData.get(server).spend(playerName, kind, cost);
    }

    /** Total per-60s output of the nodes owned by {@code teamId}. */
    public static ResourceData.Stock teamRate(MinecraftServer server, String teamId) {
        if (teamId == null || TeamData.get(server).team(teamId).isEmpty()) {
            return ResourceData.Stock.empty();
        }
        double ammo = 0.0D;
        double fuel = 0.0D;
        for (MapData.Node node : MapData.get(server).nodes()) {
            if (!MapData.isFaction(node.factionId())) {
                continue;
            }
            if (!teamId.equals(MapData.normalizeFaction(node.factionId()))) {
                continue;
            }
            ammo += Math.max(0.0D, node.ammoPerMinute());
            fuel += Math.max(0.0D, node.fuelPerMinute());
        }
        return new ResourceData.Stock(ammo, fuel);
    }

    /** Per-player HUD snapshot: own stockpile plus the income rate of the player's team. */
    public static WarProjectNetwork.ResourceSyncPacket snapshotFor(MinecraftServer server, ServerPlayer player) {
        String name = player.getScoreboardName();
        TeamData teams = TeamData.get(server);
        String teamId = teams.teamOf(name).orElse(null);
        ResourceData data = ResourceData.get(server);
        ResourceData.Stock stock = data.stock(name);
        ResourceData.Stock rate = teamRate(server, teamId);
        java.util.List<WarProjectNetwork.TeamMemberEntry> teammates = new java.util.ArrayList<>();
        if (teamId != null) {
            TeamData.Team team = teams.team(teamId).orElse(null);
            if (team != null) {
                for (String member : team.members()) {
                    if (member.equals(name)) {
                        continue;
                    }
                    ResourceData.Stock memberStock = data.stock(member);
                    teammates.add(new WarProjectNetwork.TeamMemberEntry(member, memberStock.ammo(), memberStock.fuel()));
                }
            }
        }
        return new WarProjectNetwork.ResourceSyncPacket(GameStateService.active().isRunning(), teamId != null,
                stock.ammo(), stock.fuel(), rate.ammo(), rate.fuel(), java.util.List.copyOf(teammates));
    }

    public static void sendSync(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            WarProjectNetwork.sendResources(player, snapshotFor(server, player));
        }
    }

    /** Refreshes every online player's own snapshot (after settlement, phase changes, transfers). */
    public static void pushSyncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            WarProjectNetwork.sendResources(player, snapshotFor(server, player));
        }
    }

    /** Outcome of a player-to-player transfer. */
    public record TransferOutcome(boolean ok, String message) {
    }

    /**
     * Timestamp of the last accepted transfer per sender. Deliberately in memory only: it is a pacing
     * rule for the current session, exactly like the game phase, and a restart resets it.
     */
    private static final java.util.Map<java.util.UUID, Long> LAST_TRANSFER_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Largest amount one transfer may send for the given kind. */
    public static double transferLimit(ResourceKind kind) {
        return kind == ResourceKind.FUEL ? Config.transferMaxFuelPerRequest : Config.transferMaxAmmoPerRequest;
    }

    /** Configured cooldown between transfers, in seconds. */
    public static double transferCooldownSeconds() {
        return Math.max(0.0D, Config.transferCooldownSeconds);
    }

    /** Seconds the player still has to wait, or 0 when the next transfer is allowed. */
    public static double cooldownRemaining(ServerPlayer player) {
        double cooldown = transferCooldownSeconds();
        if (cooldown <= 0.0D || player == null) {
            return 0.0D;
        }
        Long last = LAST_TRANSFER_AT.get(player.getUUID());
        if (last == null) {
            return 0.0D;
        }
        double elapsed = (System.currentTimeMillis() - last) / 1000.0D;
        return Math.max(0.0D, cooldown - elapsed);
    }

    /**
     * Moves resources from {@code sender} to the online player {@code targetName}. Every gameplay
     * rule lives here so the command and the UI packet cannot diverge: RUNNING phase, same team,
     * sender balance, and the receiver's 999 ceiling (the whole transfer is rejected on overflow).
     * Both sides get a chat receipt and a fresh HUD snapshot.
     */
    public static TransferOutcome transfer(MinecraftServer server, ServerPlayer sender, String targetName, ResourceKind kind, double amount) {
        String senderName = sender.getScoreboardName();
        if (kind == null) {
            return new TransferOutcome(false, "Unknown resource kind.");
        }
        if (!GameStateService.active().isRunning()) {
            return new TransferOutcome(false, "Resource transfer is paused while the game is "
                    + GameStateService.active().phase().id() + ".");
        }
        if (!Double.isFinite(amount) || amount <= 0.0D) {
            return new TransferOutcome(false, "Transfer amount must be greater than 0.");
        }
        String target = TeamData.cleanPlayer(targetName);
        if (target.isBlank() || target.equals(senderName)) {
            return new TransferOutcome(false, "Pick a teammate to transfer to.");
        }
        ServerPlayer receiver = server.getPlayerList().getPlayerByName(target);
        if (receiver == null) {
            return new TransferOutcome(false, target + " is not online.");
        }
        TeamData teams = TeamData.get(server);
        String senderTeam = teams.teamOf(senderName).orElse(null);
        String receiverTeam = teams.teamOf(target).orElse(null);
        if (senderTeam == null || !senderTeam.equals(receiverTeam)) {
            return new TransferOutcome(false, target + " is not in your team.");
        }
        double limit = transferLimit(kind);
        if (amount > limit) {
            return new TransferOutcome(false, "You can send at most " + format(limit) + " " + kind.id()
                    + " in one transfer.");
        }
        double remaining = cooldownRemaining(sender);
        if (remaining > 0.0D) {
            return new TransferOutcome(false, "Transfer is cooling down (" + (long) Math.ceil(remaining) + " s left).");
        }
        ResourceData data = ResourceData.get(server);
        double balance = data.amount(senderName, kind);
        if (balance < amount) {
            return new TransferOutcome(false, "Not enough " + kind.id() + " (you have " + format(balance) + ").");
        }
        double capacity = ResourceData.MAX_AMOUNT - data.amount(target, kind);
        if (capacity < amount) {
            return new TransferOutcome(false, target + " can only receive " + format(Math.max(0.0D, capacity))
                    + " more " + kind.id() + ".");
        }
        if (!data.spend(senderName, kind, amount)) {
            return new TransferOutcome(false, "Transfer failed: your balance changed.");
        }
        data.addAmount(target, kind, amount);
        LAST_TRANSFER_AT.put(sender.getUUID(), System.currentTimeMillis());
        sendSync(sender);
        sendSync(receiver);
        String text = format(amount) + " " + kind.id();
        String sentMessage = "Transferred " + text + " to " + target + ".";
        sender.sendSystemMessage(Component.literal(sentMessage));
        receiver.sendSystemMessage(Component.literal("Received " + text + " from " + senderName + "."));
        LOGGER.info("Resource transfer: {} -> {} ({})", senderName, target, text);
        return new TransferOutcome(true, sentMessage);
    }

    public static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
