package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
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

@Mod.EventBusSubscriber(modid = WarProject.MODID, value = Dist.CLIENT)
public final class WargameCaptureClient {
    private static final int INTENT_INTERVAL_TICKS = 20;

    private static int tickCounter;

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

        WargameCaptureHudState.tick();
        WargameCaptureNoticeHudState.tick();

        ChunkPos chunk = player.chunkPosition();
        Optional<ClientMapState.ClientNode> node = ClientMapState.nodeAt(chunk.x, chunk.z);
        WargameCaptureHudState.setCurrentNode(node.map(ClientMapState.ClientNode::id));

        if (node.isEmpty()) {
            tickCounter = 0;
            return;
        }
        if (++tickCounter < INTENT_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        Optional<String> team = TeamClientState.teamOf(player.getScoreboardName());
        if (team.isEmpty()) {
            return;
        }
        if (isFriendlyNode(node.get().factionId(), team.get())) {
            return;
        }
        WarProjectNetwork.sendCaptureIntent(node.get().id());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        tickCounter = 0;
        WargameCaptureHudState.reset();
        WargameCaptureNoticeHudState.reset();
        ResourceClientState.reset();
    }

    private static boolean isFriendlyNode(String factionId, String teamId) {
        if (factionId == null || factionId.isBlank()) {
            return false;
        }
        return factionId.equals(teamId) || TeamClientState.areAllied(factionId, teamId);
    }
}
