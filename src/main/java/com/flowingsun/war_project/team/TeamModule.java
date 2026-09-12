package com.flowingsun.war_project.team;

import com.flowingsun.war_project.command.WarProjectCommands;
import com.flowingsun.war_project.module.WarProjectModule;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

public final class TeamModule implements WarProjectModule {
    private static final TeamChatService CHAT_SERVICE = new TeamChatService();
    private static boolean chatServiceRegistered;

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
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        if (chatServiceRegistered) {
            MinecraftForge.EVENT_BUS.unregister(CHAT_SERVICE);
            chatServiceRegistered = false;
        }
    }

    @Override
    public void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        WarProjectCommands.register(dispatcher);
    }
}
