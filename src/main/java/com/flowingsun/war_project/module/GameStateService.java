package com.flowingsun.war_project.module;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for the game phase. Modules register a listener and react to phase changes
 * inside their own package, so the kernel never references resource or wargame code.
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
        void onGamePhaseChanged(MinecraftServer server, GamePhase from, GamePhase to);
    }

    public record Transition(boolean changed, GamePhase from, GamePhase to) {
    }
}