package com.flowingsun.war_project.net;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.WargameCaptureClient;
import com.flowingsun.war_project.map.MapDivideStateApi;
import com.flowingsun.war_project.map.MapData;
import com.flowingsun.war_project.team.TeamClientState;
import com.flowingsun.war_project.team.TeamData;
import com.flowingsun.war_project.wargame.WargameService;
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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

public final class WarProjectNetwork {
    private static final String PROTOCOL = "1";
    private static int packetId;
    private static SimpleChannel channel;

    private WarProjectNetwork() {
    }

    public static void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(WarProject.MODID, "main"),
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
        channel.messageBuilder(CreateNodeWarzonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(CreateNodeWarzonePacket::encode)
                .decoder(CreateNodeWarzonePacket::decode)
                .consumerMainThread(CreateNodeWarzonePacket::handle)
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

    public static void broadcastCaptureProgress(MinecraftServer server, String nodeId, String attackerFactionId, double progressSeconds, double targetSeconds) {
        if (channel != null) {
            channel.send(PacketDistributor.ALL.noArg(), new CaptureProgressPacket(nodeId, attackerFactionId, progressSeconds, targetSeconds));
        }
    }

    public static void sendCreateNodeWarzone(String nodeId, String nodeName, Set<Long> nodeChunks, Set<Long> warzoneChunks, int colorRgb) {
        if (channel != null) {
            channel.sendToServer(new CreateNodeWarzonePacket(nodeId, nodeName, nodeChunks, warzoneChunks, colorRgb));
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
                    WargameService.active().submitCaptureIntent(sender, packet.nodeId);
                }
            });
            context.get().setPacketHandled(true);
        }
    }

    public record CaptureProgressPacket(String nodeId, String attackerFactionId, double progressSeconds, double targetSeconds) {
        static void encode(CaptureProgressPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.nodeId);
            buffer.writeUtf(packet.attackerFactionId);
            buffer.writeDouble(packet.progressSeconds);
            buffer.writeDouble(packet.targetSeconds);
        }

        static CaptureProgressPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new CaptureProgressPacket(buffer.readUtf(64), buffer.readUtf(64), buffer.readDouble(), buffer.readDouble());
        }

        static void handle(CaptureProgressPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                double normalized = packet.targetSeconds <= 0.0D ? 0.0D : packet.progressSeconds / packet.targetSeconds;
                WargameCaptureClient.updateProgress(packet.nodeId, normalized);
            }));
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
}
