package com.flowingsun.war_project.team;

import com.flowingsun.war_project.command.WarProjectCommands;
import com.flowingsun.war_project.module.WarProjectModule;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class TeamModule implements WarProjectModule {
    private static final TeamChatService CHAT_SERVICE = new TeamChatService();
    private static boolean chatServiceRegistered;
    private static boolean loginSyncRegistered;

    public static TeamChatService chatService() {
        return CHAT_SERVICE;
    }

    @Override
    public String id() {
        return "team";
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        TeamData.get(server).setDirty();
        if (!chatServiceRegistered) {
            MinecraftForge.EVENT_BUS.register(CHAT_SERVICE);
            chatServiceRegistered = true;
        }
        if (!loginSyncRegistered) {
            MinecraftForge.EVENT_BUS.register(this);
            loginSyncRegistered = true;
        }
        TeamE33ChatBridge.syncAll(server);
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        if (chatServiceRegistered) {
            MinecraftForge.EVENT_BUS.unregister(CHAT_SERVICE);
            chatServiceRegistered = false;
        }
        if (loginSyncRegistered) {
            MinecraftForge.EVENT_BUS.unregister(this);
            loginSyncRegistered = false;
        }
    }

    /**
     * Re-declares the group set on login. e33chat already adds declared members by
     * name through its own login hook, so this is not member bookkeeping: it heals
     * drift such as a group dissolved from e33chat's browser or removed by an op.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TeamE33ChatBridge.syncAll(player.getServer());
        }
    }

    @Override
    public void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        WarProjectCommands.register(dispatcher);
    }
}
