package com.flowingsun.war_project.recovery;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Server-side facade of the recovery module: the command tree and the module itself only talk to
 * this class, never to the backup internals.
 */
public final class RecoveryApi {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RecoveryApi() {
    }

    /** Backs the block world up; used right before a game starts and by the manual command. */
    public static Outcome captureSnapshot(MinecraftServer server) {
        try {
            if (RecoveryService.active().capture(server)) {
                return new Outcome(true, "War Project recovery: world backup taken.");
            }
            return new Outcome(false, "War Project recovery: world backup failed, see the log.");
        } catch (RuntimeException exception) {
            LOGGER.warn("War Project recovery: taking the world backup failed", exception);
            return new Outcome(false, "War Project recovery: world backup failed: " + exception);
        }
    }

    /** Queues the rollback of every chunk the round changed; it is replayed over the next ticks. */
    public static Outcome restoreSnapshot(MinecraftServer server) {
        try {
            if (RecoveryService.active().isRestoring()) {
                return new Outcome(false, "War Project recovery: a restore is already running.");
            }
            if (RecoveryService.active().beginRestore(server)) {
                return new Outcome(true, "War Project recovery: rolling the map back to the pre-game backup.");
            }
            return new Outcome(false, "War Project recovery: no world backup to restore.");
        } catch (RuntimeException exception) {
            LOGGER.warn("War Project recovery: restoring the world failed", exception);
            return new Outcome(false, "War Project recovery: restore failed: " + exception);
        }
    }

    public static String statusLine(MinecraftServer server) {
        try {
            return RecoveryService.active().status(server);
        } catch (RuntimeException exception) {
            return "Recovery status unavailable: " + exception;
        }
    }

    public record Outcome(boolean ok, String message) {
    }
}
