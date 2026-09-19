package com.flowingsun.war_project.command;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.map.MapDivideStateApi;
import com.flowingsun.war_project.module.GamePhase;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.resource.ResourceApi;
import com.flowingsun.war_project.resource.ResourceData;
import com.flowingsun.war_project.resource.ResourceKind;
import com.flowingsun.war_project.team.TeamApi;
import com.flowingsun.war_project.team.TeamData;
import com.flowingsun.war_project.team.TeamModule;
import com.flowingsun.war_project.nodeLJYS.CaptureProgressQueryApi;
import com.flowingsun.war_project.recovery.RecoveryApi;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class WarProjectCommands {
    /**
     * Chunk coordinates are plain integers parsed by Brigadier's own {@link IntegerArgumentType}. A custom
     * argument type would be unknown to {@code ArgumentTypeInfos}, and the login-time command-tree packet
     * would then fail with "Couldn't place player in world" — that made every world unjoinable once.
     */
    private static final int MAX_CHUNK_COORDINATE = 30_000_000;

    private static final List<String> TEAM_MODIFY_PROPERTIES = List.of(
            "displayName",
            "color",
            "friendlyFire",
            "seeFriendlyInvisibles",
            "nametagVisibility",
            "deathMessageVisibility",
            "collisionRule",
            "prefix",
            "suffix"
    );

    private WarProjectCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("warproject")
                .requires(source -> source.hasPermission(2))
                .then(chunkCommands())
                .then(mapCommands())
                .then(nodeCommands())
                .then(warzoneCommands())
                .then(progressCommands())
                .then(gameCommands())
                .then(recoveryCommands())
                .then(resourceCommands())
                .then(teamCommands()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> chunkCommands() {
        return Commands.literal("chunk")
                .then(Commands.literal("info").executes(WarProjectCommands::chunkInfo));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> mapCommands() {
        return Commands.literal("map")
                .then(Commands.literal("set")
                        .then(Commands.argument("fromX", IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE))
                                .then(Commands.argument("fromZ", IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE))
                                        .then(Commands.argument("toX", IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE))
                                                .then(Commands.argument("toZ", IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE))
                                                        .executes(WarProjectCommands::mapSet))))))
                .then(Commands.literal("info").executes(WarProjectCommands::mapInfo));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> nodeCommands() {
        return Commands.literal("node")
                .then(Commands.literal("list").executes(WarProjectCommands::nodeList))
                .then(Commands.literal("info")
                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .executes(WarProjectCommands::nodeInfo)))
                .then(Commands.literal("setfaction")
                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .suggests(WarProjectCommands::suggestFactions)
                                        .executes(WarProjectCommands::nodeSetFaction))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("oldNodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .then(Commands.argument("newNodeId", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(WarProjectCommands::nodeRename)))))
                .then(Commands.literal("setresource")
                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .then(Commands.argument("ammoPerMinute", DoubleArgumentType.doubleArg(0.0D))
                                        .then(Commands.argument("fuelPerMinute", DoubleArgumentType.doubleArg(0.0D))
                                                .executes(WarProjectCommands::nodeSetResource)))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .executes(WarProjectCommands::nodeDelete)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> warzoneCommands() {
        return Commands.literal("warzone")
                .then(Commands.literal("list").executes(WarProjectCommands::warzoneList))
                .then(Commands.literal("info")
                        .then(Commands.argument("warzoneId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllWarzoneIds(server(context)), builder))
                                .executes(WarProjectCommands::warzoneInfo)))
                .then(Commands.literal("node")
                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .executes(WarProjectCommands::warzoneForNode)));
        // Deliberately no setfaction: a warzone's faction always follows the node it is bound to, so
        // /warproject node setfaction is the only place ownership can change.
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> progressCommands() {
        return Commands.literal("progress")
                .then(Commands.literal("node")
                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MapDivideStateApi.getAllNodeIds(server(context)), builder))
                                .executes(WarProjectCommands::progressNode)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gameCommands() {
        return Commands.literal("game")
                .then(Commands.literal("start").executes(context -> gameTransition(context, GamePhase.RUNNING)))
                .then(Commands.literal("stop").executes(context -> gameTransition(context, GamePhase.STOPPED)))
                .then(Commands.literal("end").executes(context -> gameTransition(context, GamePhase.ENDED)))
                .then(Commands.literal("status").executes(WarProjectCommands::gameStatus));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> resourceCommands() {
        return Commands.literal("resource")
                .then(Commands.literal("list").executes(WarProjectCommands::resourceList))
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestKnownPlayers)
                                .executes(WarProjectCommands::resourcePlayer)))
                .then(resourceModifyCommands("set"))
                .then(resourceModifyCommands("add"))
                .then(resourceModifyCommands("take"))
                .then(resourceModifyCommands("transfer"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> resourceModifyCommands(String action) {
        return Commands.literal(action)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(WarProjectCommands::suggestKnownPlayers)
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestResourceKinds)
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(context -> resourceModify(context, action)))));
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestKnownPlayers(CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ResourceApi.playerStocks(server(context)).keySet(), builder);
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestResourceKinds(CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        java.util.List<String> ids = new ArrayList<>();
        for (ResourceKind kind : ResourceKind.values()) {
            ids.add(kind.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> teamCommands() {
        return Commands.literal("team")
                .then(Commands.literal("add")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .executes(context -> teamAdd(context, StringArgumentType.getString(context, "team")))
                                .then(Commands.argument("displayName", StringArgumentType.greedyString())
                                        .executes(context -> teamAdd(context, StringArgumentType.getString(context, "displayName"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestTeams)
                                .executes(WarProjectCommands::teamRemove)))
                .then(Commands.literal("empty")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestTeams)
                                .executes(WarProjectCommands::teamEmpty)))
                .then(Commands.literal("join")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestTeams)
                                .executes(WarProjectCommands::teamJoinSelf)
                                .then(Commands.argument("members", EntityArgument.players())
                                        .executes(WarProjectCommands::teamJoin))))
                .then(Commands.literal("leave")
                        .then(Commands.argument("members", EntityArgument.players())
                                .executes(WarProjectCommands::teamLeave)))
                .then(Commands.literal("list")
                        .executes(WarProjectCommands::teamListAll)
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestTeams)
                                .executes(WarProjectCommands::teamListOne)))
                .then(Commands.literal("msg")
                        .then(Commands.literal("switch").executes(WarProjectCommands::teamMsgSwitch)))
                .then(Commands.literal("admin")
                        .then(Commands.literal("set")
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .suggests(WarProjectCommands::suggestTeams)
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(WarProjectCommands::teamAdminSet))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("team", StringArgumentType.word())
                                        .suggests(WarProjectCommands::suggestTeams)
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(WarProjectCommands::teamAdminRemove)))))
                .then(Commands.literal("modify")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests(WarProjectCommands::suggestTeams)
                                .then(Commands.argument("property", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(TEAM_MODIFY_PROPERTIES, builder))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(WarProjectCommands::teamModify)))))
                .then(Commands.literal("ally")
                        .then(Commands.literal("add")
                                .then(Commands.argument("teamA", StringArgumentType.word()).suggests(WarProjectCommands::suggestTeams)
                                        .then(Commands.argument("teamB", StringArgumentType.word()).suggests(WarProjectCommands::suggestTeams)
                                                .executes(WarProjectCommands::teamAllyAdd))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("teamA", StringArgumentType.word()).suggests(WarProjectCommands::suggestTeams)
                                        .then(Commands.argument("teamB", StringArgumentType.word()).suggests(WarProjectCommands::suggestTeams)
                                                .executes(WarProjectCommands::teamAllyRemove))))
                        .then(Commands.literal("list")
                                .then(Commands.argument("team", StringArgumentType.word()).suggests(WarProjectCommands::suggestTeams)
                                        .executes(WarProjectCommands::teamAllyList))));
    }

    private static int chunkInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPos.containing(source.getPosition());
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        MinecraftServer server = source.getServer();
        String node = MapDivideStateApi.findNodeContainingChunk(server, chunkX, chunkZ).orElse("none");
        String warzone = MapDivideStateApi.findWarzoneContainingChunk(server, chunkX, chunkZ).orElse("none");
        source.sendSuccess(() -> Component.literal("Chunk " + chunkX + "," + chunkZ + " node=" + node + " warzone=" + warzone), false);
        return 1;
    }

    /**
     * Defines the playable map area. The rectangle is stored by the map module; the vanilla world border
     * is never used as a hard boundary, so a leftover border is pushed back to its maximum size here.
     */
    private static int mapSet(CommandContext<CommandSourceStack> context) {
        int fromX = IntegerArgumentType.getInteger(context, "fromX");
        int fromZ = IntegerArgumentType.getInteger(context, "fromZ");
        int toX = IntegerArgumentType.getInteger(context, "toX");
        int toZ = IntegerArgumentType.getInteger(context, "toZ");
        MinecraftServer server = server(context);
        MapData.SaveResult result = MapDivideStateApi.setBounds(server, fromX, fromZ, toX, toZ);
        if (!result.ok()) {
            return fail(context, result.message());
        }
        MapData.Bounds bounds = MapDivideStateApi.getBounds(server).orElseThrow();
        boolean borderCleared = clearHardWorldBorder(server, bounds);
        context.getSource().sendSuccess(() -> Component.literal("Map area set: " + describeBounds(bounds)
                + (borderCleared ? " (vanilla world border reset to its maximum size)" : "")), true);
        return 1;
    }

    private static int mapInfo(CommandContext<CommandSourceStack> context) {
        Optional<MapData.Bounds> bounds = MapDivideStateApi.getBounds(server(context));
        if (bounds.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "No map area set. Use /warproject map set <fromX,fromZ> <toX,toZ> with chunk coordinates."), false);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Map area: " + describeBounds(bounds.get())), false);
        return 1;
    }

    private static String describeBounds(MapData.Bounds bounds) {
        return "chunks [" + bounds.minChunkX() + "," + bounds.minChunkZ() + "] -> ["
                + bounds.maxChunkX() + "," + bounds.maxChunkZ() + "], blocks X["
                + (long) bounds.minBlockX() + ", " + (long) bounds.maxBlockX() + ") Z["
                + (long) bounds.minBlockZ() + ", " + (long) bounds.maxBlockZ() + "), "
                + bounds.chunkCount() + " chunk(s)";
    }

    /**
     * Out of map is a soft boundary: players may walk out and get a grace period instead of being
     * blocked, so an old hard border from an earlier setup is removed once, at the map's centre.
     */
    private static boolean clearHardWorldBorder(MinecraftServer server, MapData.Bounds bounds) {
        WorldBorder border = server.overworld().getWorldBorder();
        if (border.getSize() >= WorldBorder.MAX_SIZE) {
            return false;
        }
        border.setCenter((bounds.minBlockX() + bounds.maxBlockX()) * 0.5D, (bounds.minBlockZ() + bounds.maxBlockZ()) * 0.5D);
        border.setSize(WorldBorder.MAX_SIZE);
        return true;
    }

    private static int nodeList(CommandContext<CommandSourceStack> context) {
        Set<String> ids = MapDivideStateApi.getAllNodeIds(server(context));
        context.getSource().sendSuccess(() -> Component.literal(ids.isEmpty() ? "No nodes." : "Nodes: " + String.join(", ", ids)), false);
        return ids.size();
    }

    private static int nodeInfo(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "nodeId");
        Optional<CompoundTag> node = MapDivideStateApi.getNode(server(context), id);
        if (node.isEmpty()) {
            return fail(context, "Node not found: " + id);
        }
        context.getSource().sendSuccess(() -> Component.literal(formatNode(node.get())), false);
        return 1;
    }

    private static int nodeSetFaction(CommandContext<CommandSourceStack> context) {
        String nodeId = StringArgumentType.getString(context, "nodeId");
        String faction = StringArgumentType.getString(context, "faction");
        if (!TeamApi.isValidFaction(server(context), faction)) {
            return fail(context, "Faction/team not found: " + faction);
        }
        if (!MapDivideStateApi.setNodeFaction(server(context), nodeId, faction)) {
            return fail(context, "Node not found: " + nodeId);
        }
        success(context, "Node faction updated: " + nodeId + " -> " + faction);
        return 1;
    }

    private static int nodeRename(CommandContext<CommandSourceStack> context) {
        String oldNodeId = StringArgumentType.getString(context, "oldNodeId");
        String newNodeId = StringArgumentType.getString(context, "newNodeId");
        String name = StringArgumentType.getString(context, "name");
        if (!MapDivideStateApi.renameNode(server(context), oldNodeId, newNodeId, name)) {
            return fail(context, "Unable to rename node.");
        }
        success(context, "Node renamed: " + oldNodeId + " -> " + newNodeId);
        return 1;
    }

    private static int nodeDelete(CommandContext<CommandSourceStack> context) {
        String nodeId = StringArgumentType.getString(context, "nodeId");
        if (!MapData.get(server(context)).deleteNode(nodeId)) {
            return fail(context, "Node not found: " + nodeId);
        }
        WarProjectNetwork.broadcastMap(server(context));
        com.flowingsun.war_project.nodeLJYS.NodeOccupationService.clear(nodeId);
        success(context, "Node deleted: " + nodeId + " (its warzone was removed too)");
        return 1;
    }

    private static int warzoneList(CommandContext<CommandSourceStack> context) {
        Set<String> ids = MapDivideStateApi.getAllWarzoneIds(server(context));
        context.getSource().sendSuccess(() -> Component.literal(ids.isEmpty() ? "No warzones." : "Warzones: " + String.join(", ", ids)), false);
        return ids.size();
    }

    private static int warzoneInfo(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "warzoneId");
        Optional<CompoundTag> warzone = MapDivideStateApi.getWarzone(server(context), id);
        if (warzone.isEmpty()) {
            return fail(context, "Warzone not found: " + id);
        }
        context.getSource().sendSuccess(() -> Component.literal(formatWarzone(warzone.get())), false);
        return 1;
    }

    private static int warzoneForNode(CommandContext<CommandSourceStack> context) {
        String nodeId = StringArgumentType.getString(context, "nodeId");
        Optional<CompoundTag> warzone = MapDivideStateApi.getWarzoneForNode(server(context), nodeId);
        if (warzone.isEmpty()) {
            return fail(context, "Warzone not found for node: " + nodeId);
        }
        context.getSource().sendSuccess(() -> Component.literal(formatWarzone(warzone.get())), false);
        return 1;
    }

    private static int progressNode(CommandContext<CommandSourceStack> context) {
        String nodeId = StringArgumentType.getString(context, "nodeId");
        if (MapData.get(server(context)).node(nodeId).isEmpty()) {
            return fail(context, "Node not found: " + nodeId);
        }
        CaptureProgressQueryApi.CaptureProgressSnapshot snapshot = CaptureProgressQueryApi.queryByNode(server(context), nodeId);
        String message = "Progress node=" + snapshot.nodeId()
                + " attacker=" + snapshot.attackerFaction() + " defender=" + snapshot.defenderFaction()
                + " seconds=" + String.format(java.util.Locale.ROOT, "%.2f", snapshot.progressSeconds())
                + " / " + String.format(java.util.Locale.ROOT, "%.2f", snapshot.requiredSeconds())
                + " neutralized=" + snapshot.neutralized();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int gameTransition(CommandContext<CommandSourceStack> context, GamePhase target) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        GameStateService.Transition transition = GameStateService.active().transition(server, target);
        if (!transition.changed()) {
            source.sendSuccess(() -> Component.literal("War Project game is already " + transition.to().id() + "."), false);
            return 0;
        }
        Component message = Component.literal(switch (transition.to()) {
            case RUNNING -> "War Project game started: node capture and resource income are now active.";
            case STOPPED -> "War Project game stopped: node capture and resource income are paused.";
            case ENDED -> "War Project game ended: team resources were cleared and all nodes were reset to neutral.";
        });
        server.getPlayerList().broadcastSystemMessage(message, false);
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendSuccess(() -> message, false);
        }
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> recoveryCommands() {
        return Commands.literal("recovery")
                .then(Commands.literal("status").executes(WarProjectCommands::recoveryStatus))
                .then(Commands.literal("snapshot").executes(WarProjectCommands::recoverySnapshot))
                .then(Commands.literal("restore").executes(WarProjectCommands::recoveryRestore));
    }

    private static int recoveryStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String message = RecoveryApi.statusLine(source.getServer());
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int recoverySnapshot(CommandContext<CommandSourceStack> context) {
        return recoveryResult(context, RecoveryApi.captureSnapshot(context.getSource().getServer()));
    }

    private static int recoveryRestore(CommandContext<CommandSourceStack> context) {
        return recoveryResult(context, RecoveryApi.restoreSnapshot(context.getSource().getServer()));
    }

    private static int recoveryResult(CommandContext<CommandSourceStack> context, RecoveryApi.Outcome outcome) {
        CommandSourceStack source = context.getSource();
        if (!outcome.ok()) {
            source.sendFailure(Component.literal(outcome.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(outcome.message()), false);
        return 1;
    }

    private static int gameStatus(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = server(context);
        GamePhase phase = GameStateService.active().phase();
        Map<String, ResourceData.Stock> stocks = ResourceApi.playerStocks(server);
        int nodesWithOutput = 0;
        for (MapData.Node node : MapData.get(server).nodes()) {
            if (MapData.isFaction(node.factionId())
                    && (node.ammoPerMinute() > 0.0D || node.fuelPerMinute() > 0.0D)) {
                nodesWithOutput++;
            }
        }
        double ammo = 0.0D;
        double fuel = 0.0D;
        for (ResourceData.Stock stock : stocks.values()) {
            ammo += stock.ammo();
            fuel += stock.fuel();
        }
        String message = String.format(java.util.Locale.ROOT,
                "Game phase=%s settleInterval=%.2fs onlinePlayers=%d trackedPlayers=%d nodesWithOutput=%d ammo=%.2f fuel=%.2f",
                phase.id(), Config.resourceSettleIntervalSeconds, server.getPlayerList().getPlayers().size(),
                stocks.size(), nodesWithOutput, ammo, fuel);
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int resourceList(CommandContext<CommandSourceStack> context) {
        Map<String, ResourceData.Stock> stocks = ResourceApi.playerStocks(server(context));
        if (stocks.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No tracked players."), false);
            return 0;
        }
        String joined = stocks.entrySet().stream()
                .map(entry -> entry.getKey() + "(ammo=" + formatAmount(entry.getValue().ammo())
                        + ", fuel=" + formatAmount(entry.getValue().fuel()) + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        context.getSource().sendSuccess(() -> Component.literal("Resources: " + joined), false);
        return stocks.size();
    }

    private static int resourcePlayer(CommandContext<CommandSourceStack> context) {
        String player = StringArgumentType.getString(context, "player");
        if (!ResourceApi.playerKnown(server(context), player)) {
            return fail(context, "Player not tracked: " + player);
        }
        ResourceData.Stock stock = ResourceApi.stock(server(context), player);
        context.getSource().sendSuccess(() -> Component.literal("Player " + player + " resources: ammo="
                + formatAmount(stock.ammo()) + ", fuel=" + formatAmount(stock.fuel())), false);
        return 1;
    }

    private static int resourceModify(CommandContext<CommandSourceStack> context, String action) {
        String player = StringArgumentType.getString(context, "player");
        String rawKind = StringArgumentType.getString(context, "kind");
        double amount = DoubleArgumentType.getDouble(context, "amount");
        Optional<ResourceKind> parsedKind = ResourceKind.parse(rawKind);
        if (parsedKind.isEmpty()) {
            return fail(context, "Unknown resource kind: " + rawKind + " (expected ammo or fuel)");
        }
        ResourceKind kind = parsedKind.get();
        MinecraftServer server = server(context);
        if (!"transfer".equals(action) && !ResourceApi.playerKnown(server, player)) {
            return fail(context, "Player not tracked: " + player);
        }
        switch (action) {
            case "set" -> {
                if (!ResourceApi.set(server, player, kind, amount)) {
                    return fail(context, "Unable to set " + kind.id() + " for player: " + player);
                }
                success(context, "Player " + player + " " + kind.id() + " set to "
                        + formatAmount(ResourceApi.amount(server, player, kind)));
            }
            case "add" -> {
                if (!ResourceApi.add(server, player, kind, amount)) {
                    return fail(context, "Unable to add " + kind.id() + " for player: " + player);
                }
                success(context, "Player " + player + " " + kind.id() + ": "
                        + formatAmount(ResourceApi.amount(server, player, kind)));
            }
            case "take" -> {
                if (!GameStateService.active().isRunning()) {
                    return fail(context, "Resource consumption is paused while the game is "
                            + GameStateService.active().phase().id() + ".");
                }
                if (!ResourceApi.spend(server, player, kind, amount)) {
                    return fail(context, "Not enough " + kind.id() + " for player: " + player);
                }
                success(context, "Player " + player + " " + kind.id() + ": "
                        + formatAmount(ResourceApi.amount(server, player, kind)));
            }
            case "transfer" -> {
                ServerPlayer sender;
                try {
                    sender = context.getSource().getPlayerOrException();
                } catch (Exception exception) {
                    return fail(context, "This command must be executed by a player.");
                }
                ResourceApi.TransferOutcome outcome = ResourceApi.transfer(server, sender, player, kind, amount);
                if (!outcome.ok()) {
                    return fail(context, outcome.message());
                }
                success(context, outcome.message());
            }
            default -> {
                return fail(context, "Unknown resource action: " + action);
            }
        }
        return 1;
    }

    private static int nodeSetResource(CommandContext<CommandSourceStack> context) {
        String nodeId = StringArgumentType.getString(context, "nodeId");
        double ammoPerMinute = DoubleArgumentType.getDouble(context, "ammoPerMinute");
        double fuelPerMinute = DoubleArgumentType.getDouble(context, "fuelPerMinute");
        if (!MapDivideStateApi.setNodeResourceOutputs(server(context), nodeId, ammoPerMinute, fuelPerMinute)) {
            return fail(context, "Node not found: " + nodeId);
        }
        success(context, "Node resource output set: " + nodeId
                + " -> ammo=" + formatAmount(ammoPerMinute) + "/60s fuel=" + formatAmount(fuelPerMinute) + "/60s");
        return 1;
    }

    private static int teamAdd(CommandContext<CommandSourceStack> context, String displayName) {
        String team = StringArgumentType.getString(context, "team");
        if (!TeamData.get(server(context)).addTeam(team, displayName)) {
            return fail(context, "Unable to add team: " + team);
        }
        TeamApi.broadcast(server(context));
        success(context, "Team added: " + team);
        return 1;
    }

    private static int teamRemove(CommandContext<CommandSourceStack> context) {
        String team = StringArgumentType.getString(context, "team");
        if (!TeamData.get(server(context)).removeTeam(team)) {
            return fail(context, "Team not found: " + team);
        }
        TeamApi.broadcast(server(context));
        success(context, "Team removed: " + team);
        return 1;
    }

    private static int teamEmpty(CommandContext<CommandSourceStack> context) {
        String team = StringArgumentType.getString(context, "team");
        if (!TeamData.get(server(context)).emptyTeam(team)) {
            return fail(context, "Team not found: " + team);
        }
        TeamApi.broadcast(server(context));
        success(context, "Team emptied: " + team);
        return 1;
    }

    private static int teamJoinSelf(CommandContext<CommandSourceStack> context) {
        try {
            return joinPlayers(context, List.of(context.getSource().getPlayerOrException()));
        } catch (Exception exception) {
            return fail(context, "A player target is required.");
        }
    }

    private static int teamJoin(CommandContext<CommandSourceStack> context) {
        try {
            return joinPlayers(context, EntityArgument.getPlayers(context, "members"));
        } catch (Exception exception) {
            return fail(context, "Invalid player target.");
        }
    }

    private static int joinPlayers(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> players) {
        String team = StringArgumentType.getString(context, "team");
        List<String> names = players.stream().map(ServerPlayer::getScoreboardName).toList();
        if (!TeamData.get(server(context)).joinTeam(team, names)) {
            return fail(context, "Unable to join team: " + team);
        }
        TeamApi.broadcast(server(context));
        success(context, "Joined " + names.size() + " player(s) to " + team);
        return names.size();
    }

    private static int teamLeave(CommandContext<CommandSourceStack> context) {
        try {
            List<String> names = EntityArgument.getPlayers(context, "members").stream().map(ServerPlayer::getScoreboardName).toList();
            if (!TeamData.get(server(context)).leave(names)) {
                return fail(context, "No members changed.");
            }
            TeamApi.broadcast(server(context));
            success(context, "Removed " + names.size() + " player(s) from teams.");
            return names.size();
        } catch (Exception exception) {
            return fail(context, "Invalid player target.");
        }
    }

    private static int teamListAll(CommandContext<CommandSourceStack> context) {
        List<String> ids = TeamData.get(server(context)).teams().stream().map(TeamData.Team::id).sorted().toList();
        context.getSource().sendSuccess(() -> Component.literal(ids.isEmpty() ? "No teams." : "Teams: " + String.join(", ", ids)), false);
        return ids.size();
    }

    private static int teamListOne(CommandContext<CommandSourceStack> context) {
        String teamId = StringArgumentType.getString(context, "team");
        Optional<TeamData.Team> team = TeamData.get(server(context)).team(teamId);
        if (team.isEmpty()) {
            return fail(context, "Team not found: " + teamId);
        }
        TeamData.Team value = team.get();
        context.getSource().sendSuccess(() -> Component.literal("Team " + value.id()
                + " displayName=" + value.displayName()
                + " members=" + String.join(",", value.members())
                + " admins=" + String.join(",", value.admins())), false);
        return 1;
    }

    private static int teamMsgSwitch(CommandContext<CommandSourceStack> context) {
        try {
            return TeamModule.chatService().switchChannel(context.getSource().getPlayerOrException());
        } catch (Exception exception) {
            return fail(context, "This command must be executed by a player.");
        }
    }

    private static int teamAdminSet(CommandContext<CommandSourceStack> context) {
        try {
            String team = StringArgumentType.getString(context, "team");
            ServerPlayer player = EntityArgument.getPlayer(context, "player");
            if (!TeamData.get(server(context)).setAdmin(team, player.getScoreboardName())) {
                return fail(context, "Player must be a member of team: " + team);
            }
            TeamApi.broadcast(server(context));
            success(context, "Team admin set: " + player.getScoreboardName());
            return 1;
        } catch (Exception exception) {
            return fail(context, "Invalid player target.");
        }
    }

    private static int teamAdminRemove(CommandContext<CommandSourceStack> context) {
        try {
            String team = StringArgumentType.getString(context, "team");
            ServerPlayer player = EntityArgument.getPlayer(context, "player");
            if (!TeamData.get(server(context)).removeAdmin(team, player.getScoreboardName())) {
                return fail(context, "Team admin not found: " + player.getScoreboardName());
            }
            TeamApi.broadcast(server(context));
            success(context, "Team admin removed: " + player.getScoreboardName());
            return 1;
        } catch (Exception exception) {
            return fail(context, "Invalid player target.");
        }
    }

    private static int teamModify(CommandContext<CommandSourceStack> context) {
        String team = StringArgumentType.getString(context, "team");
        String property = StringArgumentType.getString(context, "property");
        String value = StringArgumentType.getString(context, "value");
        if (!TeamData.get(server(context)).modify(team, property, value)) {
            return fail(context, "Unable to modify team.");
        }
        TeamApi.broadcast(server(context));
        success(context, "Team modified: " + team + " " + property);
        return 1;
    }

    private static int teamAllyAdd(CommandContext<CommandSourceStack> context) {
        String teamA = StringArgumentType.getString(context, "teamA");
        String teamB = StringArgumentType.getString(context, "teamB");
        if (!TeamData.get(server(context)).addAlly(teamA, teamB)) {
            return fail(context, "Unable to add ally relation.");
        }
        TeamApi.broadcast(server(context));
        success(context, "Ally relation added: " + teamA + " <-> " + teamB);
        return 1;
    }

    private static int teamAllyRemove(CommandContext<CommandSourceStack> context) {
        String teamA = StringArgumentType.getString(context, "teamA");
        String teamB = StringArgumentType.getString(context, "teamB");
        if (!TeamData.get(server(context)).removeAlly(teamA, teamB)) {
            return fail(context, "Unable to remove ally relation.");
        }
        TeamApi.broadcast(server(context));
        success(context, "Ally relation removed: " + teamA + " <-> " + teamB);
        return 1;
    }

    private static int teamAllyList(CommandContext<CommandSourceStack> context) {
        String teamId = StringArgumentType.getString(context, "team");
        Optional<TeamData.Team> team = TeamData.get(server(context)).team(teamId);
        if (team.isEmpty()) {
            return fail(context, "Team not found: " + teamId);
        }
        context.getSource().sendSuccess(() -> Component.literal("Allies for " + teamId + ": " + String.join(", ", team.get().allies())), false);
        return 1;
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestTeams(CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(TeamData.get(server(context)).teams().stream().map(TeamData.Team::id), builder);
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestFactions(CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        List<String> ids = new ArrayList<>();
        ids.add("neutral");
        ids.add("none");
        TeamData.get(server(context)).teams().stream().map(TeamData.Team::id).forEach(ids::add);
        return SharedSuggestionProvider.suggest(ids, builder);
    }

    private static String formatAmount(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String formatNode(CompoundTag tag) {
        return "Node " + tag.getString("id")
                + " name=" + tag.getString("name")
                + " faction=" + tag.getString("faction_id")
                + " chunks=" + tag.getList("chunks", Tag.TAG_COMPOUND).size()
                + " ammoPerMinute=" + formatAmount(tag.getDouble("ammo_per_minute"))
                + " fuelPerMinute=" + formatAmount(tag.getDouble("fuel_per_minute"));
    }

    private static String formatWarzone(CompoundTag tag) {
        return "Warzone " + tag.getString("id")
                + " node=" + tag.getString("node_id")
                + " faction=" + tag.getString("faction_id")
                + " chunks=" + tag.getList("chunks", Tag.TAG_COMPOUND).size();
    }

    private static MinecraftServer server(CommandContext<CommandSourceStack> context) {
        return context.getSource().getServer();
    }

    private static void success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), true);
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
