package com.flowingsun.war_project;

import com.flowingsun.war_project.map.MapDivideModule;
import com.flowingsun.war_project.module.ModuleRegistry;
import com.flowingsun.war_project.module.WarProjectModule;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamModule;
import com.flowingsun.war_project.wargame.WargameModule;
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
                new WargameModule()
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
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        moduleRegistry.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        moduleRegistry.onRegisterCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            WarProjectNetwork.sendMap(player);
            WarProjectNetwork.sendTeams(player);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            if (clientModuleRegistry != null) {
                clientModuleRegistry.onClientSetup(event);
            }
        }
    }
}
