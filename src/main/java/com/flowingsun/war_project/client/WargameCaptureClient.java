package com.flowingsun.war_project.client;

import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class WargameCaptureClient {
    private static int tickCounter;
    private static String lastNodeId = "";
    private static double lastProgress;

    private WargameCaptureClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        ChunkPos chunk = player.chunkPosition();
        Optional<ClientMapState.ClientNode> node = ClientMapState.nodeAt(chunk.x, chunk.z);
        if (node.isEmpty()) {
            return;
        }
        Optional<String> team = TeamClientState.teamOf(player.getScoreboardName());
        if (team.isEmpty()) {
            return;
        }
        String owner = node.get().factionId();
        if (team.get().equals(owner) || TeamClientState.alliesOf(team.get()).contains(owner)) {
            return;
        }
        WarProjectNetwork.sendCaptureIntent(node.get().id());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        lastNodeId = "";
        lastProgress = 0.0D;
    }

    public static void updateProgress(String nodeId, double progress) {
        lastNodeId = nodeId;
        lastProgress = progress;
    }

    public static String lastNodeId() {
        return lastNodeId;
    }

    public static double lastProgress() {
        return lastProgress;
    }
}
