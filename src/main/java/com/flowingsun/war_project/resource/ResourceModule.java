package com.flowingsun.war_project.resource;

import com.flowingsun.war_project.module.GamePhase;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.module.WarProjectModule;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

public final class ResourceModule implements WarProjectModule {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final GameStateService.Listener gamePhaseListener = this::onGamePhaseChanged;

    @Override
    public String id() {
        return "resource";
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        ResourceData.get(server).setDirty();
        MinecraftForge.EVENT_BUS.register(ResourceService.active());
        GameStateService.active().addListener(gamePhaseListener);
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        GameStateService.active().removeListener(gamePhaseListener);
        MinecraftForge.EVENT_BUS.unregister(ResourceService.active());
        ResourceService.clearActive();
    }

    private void onGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to) {
        if (to == GamePhase.ENDED) {
            ResourceData data = ResourceData.get(server);
            int cleared = data.size();
            data.clearAll();
            LOGGER.info("War Project game ended: cleared resources for {} team(s)", cleared);
        }
        // Every phase change refreshes the client HUD (it is only visible while RUNNING).
        ResourceApi.pushSyncAll(server);
    }
}
