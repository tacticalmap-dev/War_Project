package com.flowingsun.war_project.wargame;

import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.module.WarProjectModule;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

public final class WargameModule implements WarProjectModule {
    private WargameService service;
    private GameStateService.Listener gamePhaseListener;

    @Override
    public String id() {
        return "wargame";
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        service = WargameService.active();
        MinecraftForge.EVENT_BUS.register(service);
        gamePhaseListener = service::onGamePhaseChanged;
        GameStateService.active().addListener(gamePhaseListener);
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        if (gamePhaseListener != null) {
            GameStateService.active().removeListener(gamePhaseListener);
            gamePhaseListener = null;
        }
        if (service != null) {
            MinecraftForge.EVENT_BUS.unregister(service);
            service = null;
        }
        WargameService.clearActive();
    }
}
