package com.flowingsun.war_project.module;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public interface WarProjectModule {
    String id();

    default void onCommonSetup() {
    }

    default void onClientSetup(FMLClientSetupEvent event) {
    }

    default void onServerStarting(MinecraftServer server) {
    }

    default void onServerStopping(MinecraftServer server) {
    }

    default void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
    }
}
