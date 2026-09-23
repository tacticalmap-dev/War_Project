package com.flowingsun.war_project;

import com.flowingsun.war_project.map.MapDivideModule;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.module.ModuleRegistry;
import com.flowingsun.war_project.module.WarProjectModule;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.resource.ResourceModule;
import com.flowingsun.war_project.team.TeamModule;
import com.flowingsun.war_project.nodeLJYS.NodeLJYSModule;
import com.flowingsun.war_project.recovery.RecoveryModule;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.List;

@Mod(WarProject.MODID)
public class WarProject {
    public static final String MODID = "war_project";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ModuleRegistry clientModuleRegistry;

    private final ModuleRegistry moduleRegistry;

    public WarProject(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        List<WarProjectModule> modules = List.of(
                new MapDivideModule(),
                new TeamModule(),
                new ResourceModule(),
                new NodeLJYSModule(),
                // Registered last on purpose: its end-of-game restore must run after the resource
                // and node modules applied their own end-of-game resets.
                new RecoveryModule()
        );
        this.moduleRegistry = new ModuleRegistry(modules);
        clientModuleRegistry = this.moduleRegistry;

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        WarProjectNetwork.register();
        moduleRegistry.onCommonSetup();
        LOGGER.info("War Project loaded");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        moduleRegistry.onServerStarting(event.getServer());
        // The game phase is a global kernel: it is never persisted and every server start is STOPPED.
        GameStateService.active().reset();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        moduleRegistry.onServerStopping(event.getServer());
        GameStateService.clearActive();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        moduleRegistry.onRegisterCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onGameShuttingDown(net.minecraftforge.event.GameShuttingDownEvent event) {
        com.flowingsun.war_project.client.web.WebRendererService.shutdown();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            WarProjectNetwork.sendMap(player);
            WarProjectNetwork.sendTeams(player);
            WarProjectNetwork.sendVpWar(player);
            com.flowingsun.war_project.resource.ResourceApi.sendSync(player);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            if (clientModuleRegistry != null) {
                clientModuleRegistry.onClientSetup(event);
            }
            // Prepares the optional Chromium backend; it stays inactive until it is actually ready.
            com.flowingsun.war_project.client.web.WebRendererService.init();
        }
    }
}
