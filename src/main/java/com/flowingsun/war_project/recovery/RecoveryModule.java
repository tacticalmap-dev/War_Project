package com.flowingsun.war_project.recovery;

import com.flowingsun.war_project.module.GamePhase;
import com.flowingsun.war_project.module.GameStateService;
import com.flowingsun.war_project.module.WarProjectModule;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

/**
 * Backs the world up before a game starts and puts it back after the game ended.
 *
 * <p>The backup runs in {@link GameStateService.Listener#onBeforeGamePhaseChanged} so it sees the
 * world exactly as the outgoing phase left it, and the restore runs in the ordinary phase callback.
 * This module is registered last, so when the game ends the rollback starts after the resource and
 * node modules applied their own end-of-game resets.</p>
 */
public final class RecoveryModule implements WarProjectModule {
    private final GameStateService.Listener gamePhaseListener = new GameStateService.Listener() {
        @Override
        public void onBeforeGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to) {
            if (to == GamePhase.RUNNING) {
                RecoveryApi.captureSnapshot(server);
            }
        }

        @Override
        public void onGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to) {
            if (to == GamePhase.ENDED) {
                RecoveryApi.restoreSnapshot(server);
            }
        }
    };

    @Override
    public String id() {
        return "recovery";
    }

    @Override
    public void onServerStarting(MinecraftServer server) {
        MinecraftForge.EVENT_BUS.register(RecoveryService.active());
        GameStateService.active().addListener(gamePhaseListener);
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        GameStateService.active().removeListener(gamePhaseListener);
        MinecraftForge.EVENT_BUS.unregister(RecoveryService.active());
        RecoveryService.clearActive();
    }
}
