package com.flowingsun.war_project.client;

import net.minecraft.Util;

/**
 * Client side mirror of the server's out-of-map grace period. The server only sends the remaining ticks
 * when the state changes (entered or left the area), after which the deadline is ticked down locally —
 * one packet per tick would be wasteful and the HUD would stutter over a laggy connection.
 */
public final class OutOfMapClientState {
    private static final long MILLIS_PER_TICK = 50L;
    private static long deadlineMillis;

    private OutOfMapClientState() {
    }

    /** {@code remainingTicks} &gt; 0 (re)starts the countdown, anything else clears it. */
    public static void apply(int remainingTicks) {
        deadlineMillis = remainingTicks > 0 ? Util.getMillis() + remainingTicks * MILLIS_PER_TICK : 0L;
    }

    public static boolean isActive() {
        return remainingMillis() > 0L;
    }

    /**
     * Whole seconds shown by the HUD, rounded up and never below one while the countdown runs, so the
     * text only disappears when the player is back inside or the server has already killed them.
     */
    public static int remainingSeconds() {
        long left = remainingMillis();
        if (left <= 0L) {
            return 0;
        }
        return Math.max(1, (int) ((left + 999L) / 1000L));
    }

    private static long remainingMillis() {
        long left = deadlineMillis - Util.getMillis();
        return Math.max(0L, left);
    }
}
