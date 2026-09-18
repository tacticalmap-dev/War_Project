package com.flowingsun.war_project.client;

import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamClientState;
import net.minecraft.client.Minecraft;

import java.util.Optional;

/**
 * Client-side capture progress state, ported from the reference mod's WargameCaptureHudState
 * with the node key switched from an int node number to the string node id.
 */
public final class WargameCaptureHudState
{
    private static final int PACKET_TTL_TICKS = 40;
    private static final int FRIENDLY_COLOR = 0xFF4FA3FF;
    private static final int ALLY_COLOR = 0xFF6EC6FF;
    private static final int ENEMY_COLOR = 0xFFFF4D4D;
    private static final int NEUTRAL_COLOR = 0xFFE5E5E5;

    private static WarProjectNetwork.CaptureProgressPacket snapshot;
    private static String currentNodeId = "";
    private static int packetAgeTicks;
    private static float smoothedProgress;

    private WargameCaptureHudState()
    {
    }

    public static void reset()
    {
        snapshot = null;
        currentNodeId = "";
        packetAgeTicks = 0;
        smoothedProgress = 0.0F;
    }

    public static void setCurrentNode(Optional<String> nodeId)
    {
        String nextNodeId = nodeId.orElse("");
        if (currentNodeId.equals(nextNodeId))
        {
            return;
        }

        currentNodeId = nextNodeId;
        snapshot = null;
        packetAgeTicks = 0;
        smoothedProgress = 0.0F;
    }

    public static void apply(WarProjectNetwork.CaptureProgressPacket packet)
    {
        snapshot = packet;
        currentNodeId = packet.nodeId() == null ? "" : packet.nodeId();
        packetAgeTicks = 0;
        float target = targetPhaseProgress(packet);
        if (packet.progressPercent() >= 100.0 || smoothedProgress <= 0.0F)
        {
            smoothedProgress = target;
        }
    }

    public static void tick()
    {
        if (snapshot == null)
        {
            return;
        }

        packetAgeTicks++;
        if (packetAgeTicks > PACKET_TTL_TICKS || currentNodeId.isBlank() || !currentNodeId.equals(snapshot.nodeId()))
        {
            snapshot = null;
            packetAgeTicks = 0;
            smoothedProgress = 0.0F;
            return;
        }

        float target = targetPhaseProgress(snapshot);
        smoothedProgress += (target - smoothedProgress) * 0.25F;
    }

    public static Optional<WarProjectNetwork.CaptureProgressPacket> visibleSnapshot()
    {
        if (snapshot == null || currentNodeId.isBlank() || !currentNodeId.equals(snapshot.nodeId()))
        {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public static boolean isNodeBeingCapturedByOtherFaction(String nodeId, String localFaction)
    {
        return snapshot != null
                && snapshot.nodeId().equals(nodeId)
                && packetAgeTicks <= PACKET_TTL_TICKS
                && snapshot.progressSeconds() > 0.0
                && isFaction(localFaction)
                && isFaction(snapshot.attackerFaction())
                && !snapshot.attackerFaction().equals(localFaction)
                && !TeamClientState.areAllied(snapshot.attackerFaction(), localFaction);
    }

    public static float smoothedProgress()
    {
        return clamp01(smoothedProgress);
    }

    public static CapturePhase phase(WarProjectNetwork.CaptureProgressPacket packet)
    {
        if (hasEnemyDefender(packet) && !packet.neutralized() && packet.progressSeconds() < packet.requiredSeconds() / 2.0)
        {
            return CapturePhase.NEUTRALIZING;
        }
        return CapturePhase.CAPTURING;
    }

    public static int activeColor(WarProjectNetwork.CaptureProgressPacket packet)
    {
        String localFaction = localFactionId();
        if (phase(packet) == CapturePhase.NEUTRALIZING)
        {
            return factionColor(packet.defenderFaction(), localFaction);
        }
        return factionColor(packet.attackerFaction(), localFaction);
    }

    public static int progressPercent(WarProjectNetwork.CaptureProgressPacket packet)
    {
        return (int) Math.round(Math.max(0.0, Math.min(100.0, packet.progressPercent())));
    }

    public static String localFactionId()
    {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
        {
            return "none";
        }
        return TeamClientState.teamOf(minecraft.player.getScoreboardName()).orElse("none");
    }

    private static float targetPhaseProgress(WarProjectNetwork.CaptureProgressPacket packet)
    {
        double required = Math.max(1.0, packet.requiredSeconds());
        double progress = Math.max(0.0, Math.min(required, packet.progressSeconds()));
        if (phase(packet) == CapturePhase.NEUTRALIZING)
        {
            return clamp01((float) (1.0 - (progress / (required / 2.0))));
        }
        if (hasEnemyDefender(packet))
        {
            return clamp01((float) ((progress - (required / 2.0)) / (required / 2.0)));
        }
        return clamp01((float) (progress / required));
    }

    private static boolean hasEnemyDefender(WarProjectNetwork.CaptureProgressPacket packet)
    {
        return isFaction(packet.defenderFaction()) && !packet.defenderFaction().equals(packet.attackerFaction());
    }

    private static boolean isFaction(String faction)
    {
        return faction != null && !faction.isBlank()
                && !"neutral".equalsIgnoreCase(faction)
                && !"none".equalsIgnoreCase(faction);
    }

    private static int factionColor(String faction, String localFaction)
    {
        if (!isFaction(faction))
        {
            return NEUTRAL_COLOR;
        }
        if (faction.equals(localFaction))
        {
            return FRIENDLY_COLOR;
        }
        if (TeamClientState.areAllied(faction, localFaction))
        {
            return ALLY_COLOR;
        }
        return ENEMY_COLOR;
    }

    private static float clamp01(float value)
    {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    public enum CapturePhase
    {
        NEUTRALIZING,
        CAPTURING
    }
}
