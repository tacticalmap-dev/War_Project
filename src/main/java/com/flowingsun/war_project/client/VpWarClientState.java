package com.flowingsun.war_project.client;

import com.flowingsun.war_project.nodeLJYS.VpWarState;
import com.flowingsun.war_project.team.TeamClientState;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Client mirror of the VP war view. Purely a display cache: it decides which allied cluster is "ours"
 * (the one containing the local player's team) and which cluster is the strongest opposing side.
 */
public final class VpWarClientState {
    private static VpWarState state = VpWarState.empty();
    private static int version;

    private VpWarClientState() {
    }

    public static void replace(VpWarState next) {
        state = next == null ? VpWarState.empty() : next;
        version++;
    }

    public static VpWarState state() {
        return state;
    }

    public static int version() {
        return version;
    }

    public static boolean isRunning() {
        return state.running();
    }

    public static List<VpWarState.Side> sides() {
        return state.sides();
    }

    public static List<VpWarState.NodeState> nodes() {
        return state.nodes();
    }

    /** Largest score on the bar; the configured start score until a snapshot says otherwise. */
    public static double maxScore() {
        return state.maxScore() > 0.0D ? state.maxScore() : 500.0D;
    }

    /** The side the local player belongs to, or null (no team, or a team outside every cluster). */
    public static VpWarState.Side mySide() {
        String teamId = localTeamId();
        if (teamId == null) {
            return null;
        }
        for (VpWarState.Side side : state.sides()) {
            if (side.teamIds().contains(teamId)) {
                return side;
            }
        }
        return null;
    }

    /** The strongest side that is not ours; the red bar shows it. */
    public static VpWarState.Side enemySide() {
        VpWarState.Side mine = mySide();
        VpWarState.Side best = null;
        for (VpWarState.Side side : state.sides()) {
            if (mine != null && side.key().equals(mine.key())) {
                continue;
            }
            if (best == null || side.score() > best.score()) {
                best = side;
            }
        }
        return best;
    }

    /** True when the given side key is the local player's side. */
    public static boolean isMySide(String sideKey) {
        VpWarState.Side mine = mySide();
        return mine != null && sideKey != null && mine.key().equals(sideKey);
    }

    private static String localTeamId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return null;
        }
        return TeamClientState.teamOf(minecraft.player.getScoreboardName()).orElse(null);
    }

    public static void reset() {
        state = VpWarState.empty();
        version++;
    }
}
