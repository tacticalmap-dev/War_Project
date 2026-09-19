package com.flowingsun.war_project.module;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for the game phase. Modules register a listener and react to phase changes
 * inside their own package, so the kernel never references resource or nodeLJYS code.
 */
public final class GameStateService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static GameStateService active;

    private final List<Listener> listeners = new ArrayList<>();
    private GamePhase phase = GamePhase.STOPPED;

    public static GameStateService active() {
        if (active == null) {
            active = new GameStateService();
        }
        return active;
    }

    public static void clearActive() {
        active = null;
    }

    public GamePhase phase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == GamePhase.RUNNING;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Resets the phase to {@link GamePhase#STOPPED} without notifying listeners, so module
     * registration order never matters.
     */
    public GamePhase reset() {
        GamePhase previous = phase;
        phase = GamePhase.STOPPED;
        return previous;
    }

    public Transition transition(MinecraftServer server, GamePhase target) {
        GamePhase current = phase;
        if (target == null || target == current) {
            return new Transition(false, current, current);
        }
        // Pre-listeners run while the old phase is still current, so they can capture the state the
        // outgoing phase is about to invalidate: the recovery module backs the map up here.
        for (Listener listener : List.copyOf(listeners)) {
            try {
                listener.onBeforeGamePhaseChanged(server, current, target);
            } catch (RuntimeException exception) {
                LOGGER.warn("War Project game phase pre-listener failed: {} -> {}", current.id(), target.id(), exception);
            }
        }
        phase = target;
        for (Listener listener : List.copyOf(listeners)) {
            try {
                listener.onGamePhaseChanged(server, current, target);
            } catch (RuntimeException exception) {
                LOGGER.warn("War Project game phase listener failed: {} -> {}", current.id(), target.id(), exception);
            }
        }
        return new Transition(true, current, target);
    }

    public interface Listener {
        /**
         * Called before the phase actually changes. A listener that needs the outgoing phase's
         * state (the recovery module snapshots the map here) must use this hook instead of
         * {@link #onGamePhaseChanged}, which already runs under the new phase.
         */
        default void onBeforeGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to) {
        }

        void onGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to);
    }

    public record Transition(boolean changed, GamePhase from, GamePhase to) {
    }
}