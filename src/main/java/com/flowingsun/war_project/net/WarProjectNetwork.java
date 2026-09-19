package com.flowingsun.war_project.net;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.OutOfMapClientState;
import com.flowingsun.war_project.client.NodeLJYSCaptureHudState;
import com.flowingsun.war_project.client.NodeLJYSCaptureNoticeHudState;
import com.flowingsun.war_project.map.MapDivideStateApi;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.team.TeamClientState;
import com.flowingsun.war_project.team.TeamData;
import com.flowingsun.war_project.nodeLJYS.CaptureProgressQueryApi;
import com.flowingsun.war_project.nodeLJYS.NodeOccupationService;
import com.flowingsun.war_project.nodeLJYS.NodeLJYSService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class WarProjectNetwork {
    private static final String PROTOCOL = "7";
    private static int packetId;
    private static SimpleChannel channel;

    private WarProjectNetwork() {
    }

    public static void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                ResourceLocation.fromNamespaceAndPath(WarProject.MODID, "main"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals
        );
        channel.messageBuilder(MapSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapSyncPacket::encode)
                .decoder(MapSyncPacket::decode)
                .consumerMainThread(MapSyncPacket::handle)
                .add();
        channel.messageBuilder(TeamSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TeamSyncPacket::encode)
                .decoder(TeamSyncPacket::decode)
                .consumerMainThread(TeamSyncPacket::handle)
                .add();
        channel.messageBuilder(CaptureIntentPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(CaptureIntentPacket::encode)
                .decoder(CaptureIntentPacket::decode)
                .consumerMainThread(CaptureIntentPacket::handle)
                .add();
        channel.messageBuilder(CaptureProgressPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CaptureProgressPacket::encode)
                .decoder(CaptureProgressPacket::decode)
                .consumerMainThread(CaptureProgressPacket::handle)
                .add();
        channel.messageBuilder(CaptureNoticePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CaptureNoticePacket::encode)
                .decoder(CaptureNoticePacket::decode)
                .consumerMainThread(CaptureNoticePacket::handle)
                .add();
        channel.messageBuilder(CreateNodeWarzonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(CreateNodeWarzonePacket::encode)
                .decoder(CreateNodeWarzonePacket::decode)
                .consumerMainThread(CreateNodeWarzonePacket::handle)
                .add();
        channel.messageBuilder(EditMapObjectPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(EditMapObjectPacket::encode)
                .decoder(EditMapObjectPacket::decode)
                .consumerMainThread(EditMapObjectPacket::handle)
                .add();
        channel.messageBuilder(SetNodeResourcePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetNodeResourcePacket::encode)
                .decoder(SetNodeResourcePacket::decode)
                .consumerMainThread(SetNodeResourcePacket::handle)
                .add();
        channel.messageBuilder(TransferResourcePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(TransferResourcePacket::encode)
                .decoder(TransferResourcePacket::decode)
                .consumerMainThread(TransferResourcePacket::handle)
                .add();
        channel.messageBuilder(TransferResultPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TransferResultPacket::encode)
                .decoder(TransferResultPacket::decode)
                .consumerMainThread(TransferResultPacket::handle)
                .add();
        channel.messageBuilder(ResourceSyncPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ResourceSyncPacket::encode)
                .decoder(ResourceSyncPacket::decode)
                .consumerMainThread(ResourceSyncPacket::handle)
                .add();
        channel.messageBuilder(OutOfMapWarningPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OutOfMapWarningPacket::encode)
                .decoder(OutOfMapWarningPacket::decode)
                .consumerMainThread(OutOfMapWarningPacket::handle)
                .add();
    }

    public static void sendMap(ServerPlayer player) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), new MapSyncPacket(MapData.get(player.getServer()).clientSnapshot()));
        }
    }

    public static void broadcastMap(MinecraftServer server) {
        if (channel != null) {
            channel.send(PacketDistributor.ALL.noArg(), new MapSyncPacket(MapData.get(server).clientSnapshot()));
        }
    }

    public static void sendTeams(ServerPlayer player) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), new TeamSyncPacket(TeamData.get(player.getServer()).clientSnapshot()));
        }
    }

    public static void broadcastTeams(MinecraftServer server) {
        if (channel != null) {
            channel.send(PacketDistributor.ALL.noArg(), new TeamSyncPacket(TeamData.get(server).clientSnapshot()));
        }
    }

    public static void sendCaptureIntent(String nodeId) {
        if (channel != null) {
            channel.sendToServer(new CaptureIntentPacket(nodeId));
        }
    }

    public static void sendCaptureProgress(ServerPlayer player, CaptureProgressQueryApi.CaptureProgressSnapshot snapshot) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), CaptureProgressPacket.fromSnapshot(snapshot));
        }
    }

    public static void sendCaptureProgressDisplay(ServerPlayer player, String nodeId, String factionId, double requiredSeconds) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), CaptureProgressPacket.display(nodeId, factionId, requiredSeconds));
        }
    }

    public static void sendCaptureNotice(ServerPlayer player, String text, int color) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), new CaptureNoticePacket(text, color));
        }
    }

    public static void sendCreateNodeWarzone(String nodeId, String nodeName, Set<Long> nodeChunks, Set<Long> warzoneChunks, int colorRgb) {
        if (channel != null) {
            channel.sendToServer(new CreateNodeWarzonePacket(nodeId, nodeName, nodeChunks, warzoneChunks, colorRgb));
        }
    }

    public static void sendRenameNode(String oldNodeId, String newNodeId, String name) {
        if (channel != null) {
            channel.sendToServer(new EditMapObjectPacket(EditMapObjectPacket.RENAME_NODE, oldNodeId, newNodeId, name));
        }
    }

    public static void sendDeleteNode(String nodeId) {
        if (channel != null) {
            channel.sendToServer(new EditMapObjectPacket(EditMapObjectPacket.DELETE_NODE, nodeId, "", ""));
        }
    }

    public static void sendResources(ServerPlayer player, ResourceSyncPacket packet) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public static void sendTransfer(String targetName, String kindId, double amount) {
        if (channel != null) {
            channel.sendToServer(new TransferResourcePacket(targetName, kindId, amount));
        }
    }

    public static void sendTransferResult(ServerPlayer player, boolean ok, String message) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), new TransferResultPacket(ok, message));
        }
    }

    public static void sendNodeResources(String nodeId, double ammoPerMinute, double fuelPerMinute) {
        if (channel != null) {
            channel.sendToServer(new SetNodeResourcePacket(nodeId, ammoPerMinute, fuelPerMinute));
        }
    }

    /**
     * Tells one client that it left the map area ({@code remainingTicks} > 0) or returned (-1). The
     * countdown itself runs client side; the server remains the authority that kills the player.
     */
    public static void sendOutOfMapWarning(ServerPlayer player, int remainingTicks) {
        if (channel != null) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), new OutOfMapWarningPacket(remainingTicks));
        }
    }

    private static int nextId() {
        return packetId++;
    }

    public record MapSyncPacket(CompoundTag snapshot) {
        static void encode(MapSyncPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeNbt(packet.snapshot);
        }

        static MapSyncPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            CompoundTag tag = buffer.readNbt();
            return new MapSyncPacket(tag == null ? new CompoundTag() : tag);
        }

        static void handle(MapSyncPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientMapState.replace(packet.snapshot)));
            context.get().setPacketHandled(true);
        }
    }

    public record TeamSyncPacket(CompoundTag snapshot) {
        static void encode(TeamSyncPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeNbt(packet.snapshot);
        }

        static TeamSyncPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            CompoundTag tag = buffer.readNbt();
            return new TeamSyncPacket(tag == null ? new CompoundTag() : tag);
        }

        static void handle(TeamSyncPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TeamClientState.replace(packet.snapshot)));
            context.get().setPacketHandled(true);
        }
    }

    /**
     * S→C personal resource snapshot: whether the game is RUNNING, whether the player has a team,
     * the player's own stockpile and the per-60s income of that player's team.
     */
    public record ResourceSyncPacket(boolean running, boolean hasTeam, double ammo, double fuel,
                                     double ammoPerMinute, double fuelPerMinute, List<TeamMemberEntry> teammates) {
        static void encode(ResourceSyncPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.running);
            buffer.writeBoolean(packet.hasTeam);
            buffer.writeDouble(packet.ammo);
            buffer.writeDouble(packet.fuel);
            buffer.writeDouble(packet.ammoPerMinute);
            buffer.writeDouble(packet.fuelPerMinute);
            buffer.writeVarInt(packet.teammates.size());
            for (TeamMemberEntry entry : packet.teammates) {
                buffer.writeUtf(entry.name(), 64);
                buffer.writeDouble(entry.ammo());
                buffer.writeDouble(entry.fuel());
            }
        }

        static ResourceSyncPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            boolean running = buffer.readBoolean();
            boolean hasTeam = buffer.readBoolean();
            double ammo = buffer.readDouble();
            double fuel = buffer.readDouble();
            double ammoPerMinute = buffer.readDouble();
            double fuelPerMinute = buffer.readDouble();
            int count = buffer.readVarInt();
            List<TeamMemberEntry> teammates = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                teammates.add(new TeamMemberEntry(buffer.readUtf(64), buffer.readDouble(), buffer.readDouble()));
            }
            return new ResourceSyncPacket(running, hasTeam, ammo, fuel, ammoPerMinute, fuelPerMinute, List.copyOf(teammates));
        }

        static void handle(ResourceSyncPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.flowingsun.war_project.client.ResourceClientState.replace(packet)));
            context.get().setPacketHandled(true);
        }
    }

    /** One teammate's stockpile, shipped alongside the local player's own snapshot. */
    public record TeamMemberEntry(String name, double ammo, double fuel) {
    }

    /** C→S: move resources from the sender to another online member of the same team. */
    public record TransferResourcePacket(String targetName, String kindId, double amount) {
        static void encode(TransferResourcePacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.targetName == null ? "" : packet.targetName, 64);
            buffer.writeUtf(packet.kindId == null ? "" : packet.kindId, 16);
            buffer.writeDouble(packet.amount);
        }

        static TransferResourcePacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new TransferResourcePacket(buffer.readUtf(64), buffer.readUtf(16), buffer.readDouble());
        }

        static void handle(TransferResourcePacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer sender = context.get().getSender();
                if (sender == null) {
                    return;
                }
                MinecraftServer server = sender.getServer();
                if (server == null) {
                    return;
                }
                java.util.Optional<com.flowingsun.war_project.resource.ResourceKind> kind =
                        com.flowingsun.war_project.resource.ResourceKind.parse(packet.kindId);
                if (kind.isEmpty()) {
                    sendTransferResult(sender, false, "Unknown resource kind: " + packet.kindId);
                    return;
                }
                com.flowingsun.war_project.resource.ResourceApi.TransferOutcome outcome =
                        com.flowingsun.war_project.resource.ResourceApi.transfer(server, sender, packet.targetName, kind.get(), packet.amount);
                sendTransferResult(sender, outcome.ok(), outcome.message());
            });
            context.get().setPacketHandled(true);
        }
    }

    /** S→C: transfer verdict, drives the panel's success/failure state. */
    public record TransferResultPacket(boolean ok, String message) {
        static void encode(TransferResultPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.ok);
            buffer.writeUtf(packet.message == null ? "" : packet.message, 256);
        }

        static TransferResultPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new TransferResultPacket(buffer.readBoolean(), buffer.readUtf(256));
        }

        static void handle(TransferResultPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.flowingsun.war_project.client.ResourceTransferController.onResult(packet.ok(), packet.message())));
            context.get().setPacketHandled(true);
        }
    }

    public record CaptureIntentPacket(String nodeId) {
        static void encode(CaptureIntentPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.nodeId);
        }

        static CaptureIntentPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new CaptureIntentPacket(buffer.readUtf(64));
        }

        static void handle(CaptureIntentPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer sender = context.get().getSender();
                if (sender != null) {
                    NodeLJYSService.active().submitCaptureIntent(sender, packet.nodeId);
                }
            });
            context.get().setPacketHandled(true);
        }
    }

    public record CaptureProgressPacket(
            String nodeId,
            String currentFaction,
            String pendingFaction,
            String previousFaction,
            String attackerFaction,
            String defenderFaction,
            boolean neutralized,
            double progressSeconds,
            double requiredSeconds,
            double progressPercent,
            int attackersInNode,
            int defendersInNode,
            int totalPlayersInNode,
            List<FactionPresence> factionsInNode
    ) {
        static CaptureProgressPacket fromSnapshot(CaptureProgressQueryApi.CaptureProgressSnapshot snapshot) {
            return new CaptureProgressPacket(
                    snapshot.nodeId(),
                    snapshot.currentFaction(),
                    snapshot.pendingFaction(),
                    snapshot.previousFaction(),
                    snapshot.attackerFaction(),
                    snapshot.defenderFaction(),
                    snapshot.neutralized(),
                    snapshot.progressSeconds(),
                    snapshot.requiredSeconds(),
                    snapshot.progressPercent(),
                    snapshot.attackersInNode(),
                    snapshot.defendersInNode(),
                    snapshot.totalPlayersInNode(),
                    snapshot.factionsInNode().stream()
                            .map(faction -> new FactionPresence(faction.factionId(), faction.playerCount()))
                            .toList());
        }

        static CaptureProgressPacket display(String nodeId, String factionId, double requiredSeconds) {
            double required = Math.max(1.0D, requiredSeconds);
            return new CaptureProgressPacket(
                    nodeId,
                    factionId,
                    factionId,
                    "neutral",
                    factionId,
                    "neutral",
                    true,
                    required,
                    required,
                    100.0D,
                    0,
                    0,
                    0,
                    List.of());
        }

        static void encode(CaptureProgressPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.nodeId, 64);
            buffer.writeUtf(packet.currentFaction);
            buffer.writeUtf(packet.pendingFaction);
            buffer.writeUtf(packet.previousFaction);
            buffer.writeUtf(packet.attackerFaction);
            buffer.writeUtf(packet.defenderFaction);
            buffer.writeBoolean(packet.neutralized);
            buffer.writeDouble(packet.progressSeconds);
            buffer.writeDouble(packet.requiredSeconds);
            buffer.writeDouble(packet.progressPercent);
            buffer.writeVarInt(packet.attackersInNode);
            buffer.writeVarInt(packet.defendersInNode);
            buffer.writeVarInt(packet.totalPlayersInNode);
            buffer.writeVarInt(packet.factionsInNode.size());
            for (FactionPresence faction : packet.factionsInNode) {
                buffer.writeUtf(faction.factionId());
                buffer.writeVarInt(faction.playerCount());
            }
        }

        static CaptureProgressPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            String nodeId = buffer.readUtf(64);
            String currentFaction = buffer.readUtf();
            String pendingFaction = buffer.readUtf();
            String previousFaction = buffer.readUtf();
            String attackerFaction = buffer.readUtf();
            String defenderFaction = buffer.readUtf();
            boolean neutralized = buffer.readBoolean();
            double progressSeconds = buffer.readDouble();
            double requiredSeconds = buffer.readDouble();
            double progressPercent = buffer.readDouble();
            int attackersInNode = buffer.readVarInt();
            int defendersInNode = buffer.readVarInt();
            int totalPlayersInNode = buffer.readVarInt();
            int factionCount = buffer.readVarInt();
            List<FactionPresence> factions = new ArrayList<>();
            for (int i = 0; i < factionCount; i++) {
                factions.add(new FactionPresence(buffer.readUtf(), buffer.readVarInt()));
            }
            return new CaptureProgressPacket(
                    nodeId,
                    currentFaction,
                    pendingFaction,
                    previousFaction,
                    attackerFaction,
                    defenderFaction,
                    neutralized,
                    progressSeconds,
                    requiredSeconds,
                    progressPercent,
                    attackersInNode,
                    defendersInNode,
                    totalPlayersInNode,
                    List.copyOf(factions));
        }

        static void handle(CaptureProgressPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> NodeLJYSCaptureHudState.apply(packet)));
            context.get().setPacketHandled(true);
        }
    }

    public record FactionPresence(String factionId, int playerCount) {
    }

    public record CaptureNoticePacket(String text, int color) {
        static void encode(CaptureNoticePacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.text);
            buffer.writeInt(packet.color);
        }

        static CaptureNoticePacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new CaptureNoticePacket(buffer.readUtf(), buffer.readInt());
        }

        static void handle(CaptureNoticePacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> NodeLJYSCaptureNoticeHudState.apply(packet.text, packet.color)));
            context.get().setPacketHandled(true);
        }
    }

    /**
     * {@code remainingTicks} &gt; 0 starts (or refreshes) the "return to the map area" countdown; -1 clears
     * it. The countdown ticker runs client side, while the server stays the authority that kills.
     */
    public record OutOfMapWarningPacket(int remainingTicks) {
        static void encode(OutOfMapWarningPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeInt(packet.remainingTicks);
        }

        static OutOfMapWarningPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new OutOfMapWarningPacket(buffer.readInt());
        }

        static void handle(OutOfMapWarningPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> OutOfMapClientState.apply(packet.remainingTicks)));
            context.get().setPacketHandled(true);
        }
    }

    public record CreateNodeWarzonePacket(String nodeId, String nodeName, Set<Long> nodeChunks, Set<Long> warzoneChunks, int colorRgb) {
        static void encode(CreateNodeWarzonePacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.nodeId, 64);
            buffer.writeUtf(packet.nodeName, 64);
            writeLongSet(buffer, packet.nodeChunks);
            writeLongSet(buffer, packet.warzoneChunks);
            buffer.writeInt(packet.colorRgb);
        }

        static CreateNodeWarzonePacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new CreateNodeWarzonePacket(buffer.readUtf(64), buffer.readUtf(64), readLongSet(buffer), readLongSet(buffer), buffer.readInt());
        }

        static void handle(CreateNodeWarzonePacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer sender = context.get().getSender();
                if (sender == null) {
                    return;
                }
                if (!sender.hasPermissions(2)) {
                    sender.sendSystemMessage(Component.literal("War Project map editing requires operator permission."));
                    return;
                }
                MapData.SaveResult result = MapDivideStateApi.createNodeWithWarzone(sender.getServer(), packet.nodeId, packet.nodeName,
                        packet.nodeChunks, packet.warzoneChunks, packet.colorRgb);
                if (result.ok()) {
                    sender.sendSystemMessage(Component.literal("Created node and warzone: " + packet.nodeId));
                } else {
                    sender.sendSystemMessage(Component.literal("Unable to create node: " + result.message()));
                }
            });
            context.get().setPacketHandled(true);
        }
    }

    private static void writeLongSet(net.minecraft.network.FriendlyByteBuf buffer, Set<Long> values) {
        buffer.writeVarInt(values.size());
        for (Long value : values) {
            buffer.writeLong(value);
        }
    }

    private static Set<Long> readLongSet(net.minecraft.network.FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        Set<Long> values = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            values.add(buffer.readLong());
        }
        return values;
    }

    /**
     * Node ammo/fuel output configured from the FTB map editor context menu. The map module stores
     * the two numbers; the resource module is the only consumer.
     */
    public record SetNodeResourcePacket(String nodeId, double ammoPerMinute, double fuelPerMinute) {
        static void encode(SetNodeResourcePacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.nodeId == null ? "" : packet.nodeId, 64);
            buffer.writeDouble(packet.ammoPerMinute);
            buffer.writeDouble(packet.fuelPerMinute);
        }

        static SetNodeResourcePacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new SetNodeResourcePacket(buffer.readUtf(64), buffer.readDouble(), buffer.readDouble());
        }

        static void handle(SetNodeResourcePacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer sender = context.get().getSender();
                if (sender == null) {
                    return;
                }
                if (!sender.hasPermissions(2)) {
                    sender.sendSystemMessage(Component.literal("War Project node editing requires operator permission."));
                    return;
                }
                MinecraftServer server = sender.getServer();
                if (MapDivideStateApi.setNodeResourceOutputs(server, packet.nodeId, packet.ammoPerMinute, packet.fuelPerMinute)) {
                    sender.sendSystemMessage(Component.literal("Node resource output set: " + packet.nodeId
                            + " -> ammo=" + String.format(java.util.Locale.ROOT, "%.2f", packet.ammoPerMinute) + "/60s"
                            + " fuel=" + String.format(java.util.Locale.ROOT, "%.2f", packet.fuelPerMinute) + "/60s"));
                } else {
                    sender.sendSystemMessage(Component.literal("Unable to set node resource output: " + packet.nodeId));
                }
            });
            context.get().setPacketHandled(true);
        }
    }

    /**
     * Map editing actions sent from the FTB map editor: rename a node or delete a node. A node and
     * its warzone only ever exist together, so deleting the node removes its warzone with it.
     */
    public record EditMapObjectPacket(String action, String id, String newId, String name) {
        public static final String RENAME_NODE = "rename_node";
        public static final String DELETE_NODE = "delete_node";

        static void encode(EditMapObjectPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.action, 32);
            buffer.writeUtf(packet.id, 64);
            buffer.writeUtf(packet.newId == null ? "" : packet.newId, 64);
            buffer.writeUtf(packet.name == null ? "" : packet.name, 128);
        }

        static EditMapObjectPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new EditMapObjectPacket(buffer.readUtf(32), buffer.readUtf(64), buffer.readUtf(64), buffer.readUtf(128));
        }

        static void handle(EditMapObjectPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer sender = context.get().getSender();
                if (sender == null) {
                    return;
                }
                if (!sender.hasPermissions(2)) {
                    sender.sendSystemMessage(Component.literal("War Project map editing requires operator permission."));
                    return;
                }
                MinecraftServer server = sender.getServer();
                switch (packet.action) {
                    case RENAME_NODE -> {
                        if (com.flowingsun.war_project.map.MapDivideStateApi.renameNode(server, packet.id, packet.newId, packet.name)) {
                            sender.sendSystemMessage(Component.literal("Renamed node " + packet.id + " to " + packet.newId));
                        } else {
                            sender.sendSystemMessage(Component.literal("Unable to rename node: " + packet.id));
                        }
                    }
                    case DELETE_NODE -> {
                        if (MapData.get(server).deleteNode(packet.id)) {
                            NodeOccupationService.clear(packet.id);
                            WarProjectNetwork.broadcastMap(server);
                            sender.sendSystemMessage(Component.literal("Deleted node: " + packet.id + " (its warzone was removed too)"));
                        } else {
                            sender.sendSystemMessage(Component.literal("Node not found: " + packet.id));
                        }
                    }
                    default -> {
                    }
                }
            });
            context.get().setPacketHandled(true);
        }
    }
}
