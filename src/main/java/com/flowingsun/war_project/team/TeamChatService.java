package com.flowingsun.war_project.team;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class TeamChatService {
    private final Set<UUID> teamChatPlayers = new HashSet<>();

    public int switchChannel(ServerPlayer player) {
        Optional<String> teamId = TeamApi.getPlayerTeamId(player);
        if (teamId.isEmpty()) {
            teamChatPlayers.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("You are not in a team; current chat channel: public"));
            return 0;
        }
        boolean teamChat = !teamChatPlayers.remove(player.getUUID());
        if (teamChat) {
            teamChatPlayers.add(player.getUUID());
        }
        player.sendSystemMessage(Component.literal("Current chat channel: " + (teamChat ? "team" : "public")));
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        if (!teamChatPlayers.contains(sender.getUUID())) {
            return;
        }
        MinecraftServer server = sender.server;
        Optional<String> teamId = TeamApi.getPlayerTeamId(sender);
        if (teamId.isEmpty()) {
            teamChatPlayers.remove(sender.getUUID());
            sender.sendSystemMessage(Component.literal("You are not in a team; switched to public chat."));
            return;
        }
        event.setCanceled(true);
        Component formatted = Component.literal("[Team] " + sender.getScoreboardName() + ": ").append(event.getMessage());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TeamApi.getPlayerTeamId(player).filter(teamId.get()::equals).isPresent()) {
                player.sendSystemMessage(formatted);
            }
        }
    }
}
