package com.flowingsun.war_project.module;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;

public final class ModuleRegistry {
    private final List<WarProjectModule> modules;

    public ModuleRegistry(List<WarProjectModule> modules) {
        this.modules = List.copyOf(modules);
    }

    public void onCommonSetup() {
        modules.forEach(WarProjectModule::onCommonSetup);
    }

    public void onClientSetup(FMLClientSetupEvent event) {
        modules.forEach(module -> module.onClientSetup(event));
    }

    public void onServerStarting(MinecraftServer server) {
        modules.forEach(module -> module.onServerStarting(server));
    }

    public void onServerStopping(MinecraftServer server) {
        modules.forEach(module -> module.onServerStopping(server));
    }

    public void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        modules.forEach(module -> module.onRegisterCommands(dispatcher));
    }
}
