package com.flowingsun.war_project.wargame;

import com.flowingsun.war_project.module.WarProjectModule;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

public final class WargameModule implements WarProjectModule {
    private WargameService service;

    @Override
    public String id() {
        return "wargame";
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        service = WargameService.active();
        MinecraftForge.EVENT_BUS.register(service);
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        if (service != null) {
            MinecraftForge.EVENT_BUS.unregister(service);
            service = null;
        }
        WargameService.clearActive();
    }
}
