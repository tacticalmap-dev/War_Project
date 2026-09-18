package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamClientState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Node capture HUD, ported from the reference mod's WargameCaptureHudOverlay.
 * The reference wartime gate is dropped because this mod always allows capturing.
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WargameCaptureHudOverlay
{
    private static final int RING_SIZE = 22;
    private static final int RING_THICKNESS = 3;
    private static final int RING_SEGMENTS = 72;
    private static final int FORCE_BAR_WIDTH = 44;
    private static final int FORCE_BAR_HEIGHT = 4;
    private static final int FORCE_BAR_GAP = 6;
    private static final int FRIENDLY_COLOR = 0xFF4FA3FF;
    private static final int ENEMY_COLOR = 0xFFFF4D4D;
    private static final int MAX_LABEL_LENGTH = 14;

    private WargameCaptureHudOverlay()
    {
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event)
    {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "war_project_capture_progress", WargameCaptureHudOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null)
        {
            return;
        }

        WargameCaptureHudState.visibleSnapshot()
                .ifPresent(snapshot -> renderCaptureProgress(graphics, minecraft, snapshot, screenWidth, screenHeight));
        WargameCaptureNoticeHudState.visibleNotice()
                .ifPresent(notice -> renderCaptureNotice(graphics, minecraft, notice, screenWidth, screenHeight));
    }

    private static void renderCaptureProgress(GuiGraphics graphics, Minecraft minecraft, WarProjectNetwork.CaptureProgressPacket snapshot, int screenWidth, int screenHeight)
    {
        int centerX = screenWidth / 2;
        int centerY = screenHeight - 58;
        int ringColor = WargameCaptureHudState.activeColor(snapshot);
        float progress = WargameCaptureHudState.smoothedProgress();

        drawForceRatioBar(graphics, centerX, centerY, snapshot);
        drawRingSegment(graphics, centerX, centerY, RING_SIZE + 3, RING_THICKNESS + 2, 1.0F, 0x99000000);
        drawRingSegment(graphics, centerX, centerY, RING_SIZE, RING_THICKNESS, 1.0F, 0x88E5E5E5);
        drawRingSegment(graphics, centerX, centerY, RING_SIZE, RING_THICKNESS, progress, ringColor);

        graphics.drawCenteredString(minecraft.font, nodeLabel(snapshot.nodeId()), centerX, centerY - 4, 0xFFFFFFFF);

        String percentText = WargameCaptureHudState.progressPercent(snapshot) + "%";
        graphics.drawCenteredString(minecraft.font, percentText, centerX, centerY + (RING_SIZE / 2) + 3, 0xDDEDEDED);
    }

    private static void renderCaptureNotice(GuiGraphics graphics, Minecraft minecraft, WargameCaptureNoticeHudState.CaptureNotice notice, int screenWidth, int screenHeight)
    {
        int centerX = screenWidth / 2;
        int y = screenHeight / 2 + 16;
        graphics.drawCenteredString(minecraft.font, notice.text(), centerX, y, notice.color());
    }

    private static String nodeLabel(String nodeId)
    {
        if (nodeId == null || nodeId.isBlank())
        {
            return "";
        }
        ClientMapState.ClientNode node = ClientMapState.nodes().get(nodeId);
        String label = node == null || node.name() == null || node.name().isBlank() ? nodeId : node.name();
        return label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH) : label;
    }

    private static void drawForceRatioBar(GuiGraphics graphics, int centerX, int centerY, WarProjectNetwork.CaptureProgressPacket snapshot)
    {
        String localFaction = WargameCaptureHudState.localFactionId();
        List<ForceSide> sides = forceSides(snapshot, localFaction);
        if (sides.size() < 2)
        {
            return;
        }

        int x = centerX - FORCE_BAR_WIDTH / 2;
        int y = centerY - RING_SIZE / 2 - FORCE_BAR_GAP - FORCE_BAR_HEIGHT;
        graphics.fill(x - 1, y - 1, x + FORCE_BAR_WIDTH + 1, y + FORCE_BAR_HEIGHT + 1, 0x99000000);

        int[] widths = forceBarWidths(sides);
        int cursorX = x;
        for (int i = 0; i < sides.size(); i++)
        {
            ForceSide side = sides.get(i);
            int width = widths[i];
            if (width <= 0)
            {
                continue;
            }
            graphics.fill(cursorX, y, cursorX + width, y + FORCE_BAR_HEIGHT, sideColor(side, sides, i));
            cursorX += width;
            if (i < sides.size() - 1)
            {
                graphics.fill(cursorX, y - 1, cursorX + 1, y + FORCE_BAR_HEIGHT + 1, 0xCC111111);
            }
        }
    }

    private static List<ForceSide> forceSides(WarProjectNetwork.CaptureProgressPacket snapshot, String localFaction)
    {
        List<ForceSide> sides = new ArrayList<>();
        for (int i = 0; i < snapshot.factionsInNode().size(); i++)
        {
            WarProjectNetwork.FactionPresence faction = snapshot.factionsInNode().get(i);
            List<ForceSide> matchingSides = matchingSides(sides, faction.factionId());
            ForceSide side;
            if (matchingSides.isEmpty())
            {
                side = new ForceSide();
                sides.add(side);
            }
            else
            {
                side = matchingSides.get(0);
                for (int sideIndex = 1; sideIndex < matchingSides.size(); sideIndex++)
                {
                    ForceSide merged = matchingSides.get(sideIndex);
                    side.mergeFrom(merged);
                    sides.remove(merged);
                }
            }
            side.addFaction(faction.factionId(), faction.playerCount(), snapshot.attackerFaction(), snapshot.defenderFaction(), localFaction);
        }

        List<ForceSide> ordered = new ArrayList<>(sides.stream()
                .filter(ForceSide::localSide)
                .toList());
        sides.stream()
                .filter(side -> !side.localSide())
                .forEach(ordered::add);
        return ordered;
    }

    private static List<ForceSide> matchingSides(List<ForceSide> sides, String factionId)
    {
        List<ForceSide> matches = new ArrayList<>();
        for (ForceSide side : sides)
        {
            if (side.isSameSide(factionId))
            {
                matches.add(side);
            }
        }
        return matches;
    }

    private static int[] forceBarWidths(List<ForceSide> sides)
    {
        int count = sides.size();
        int[] widths = new int[count];
        int totalPlayers = sides.stream()
                .mapToInt(ForceSide::playerCount)
                .sum();
        if (totalPlayers <= 0)
        {
            return widths;
        }

        int used = 0;
        int largestIndex = 0;
        for (int i = 0; i < count; i++)
        {
            int width = Math.round((float) FORCE_BAR_WIDTH * sides.get(i).playerCount() / totalPlayers);
            widths[i] = Math.max(2, width);
            used += widths[i];
            if (widths[i] > widths[largestIndex])
            {
                largestIndex = i;
            }
        }

        while (used > FORCE_BAR_WIDTH && widths[largestIndex] > 2)
        {
            widths[largestIndex]--;
            used--;
            for (int i = 0; i < count; i++)
            {
                if (widths[i] > widths[largestIndex])
                {
                    largestIndex = i;
                }
            }
        }
        if (used < FORCE_BAR_WIDTH)
        {
            widths[largestIndex] += FORCE_BAR_WIDTH - used;
        }
        return widths;
    }

    private static int sideColor(ForceSide side, List<ForceSide> sides, int index)
    {
        if (side.localSide())
        {
            return FRIENDLY_COLOR;
        }

        if (!hasMultipleAttackingSides(sides))
        {
            return ENEMY_COLOR;
        }

        OptionalInt teamColor = TeamClientState.teamColor(side.representativeFaction());
        if (teamColor.isPresent())
        {
            return teamColor.getAsInt();
        }
        return ENEMY_COLOR;
    }

    private static boolean hasMultipleAttackingSides(List<ForceSide> sides)
    {
        int attackingSides = 0;
        for (ForceSide side : sides)
        {
            if (!side.localSide() && !side.defenderSide())
            {
                attackingSides++;
            }
        }
        return attackingSides > 1;
    }

    private static final class ForceSide
    {
        private final Set<String> factionIds = new LinkedHashSet<>();
        private String representativeFaction = "";
        private int playerCount;
        private boolean localSide;
        private boolean attackerSide;
        private boolean defenderSide;

        private void addFaction(String factionId, int count, String attackerFaction, String defenderFaction, String localFaction)
        {
            factionIds.add(factionId);
            playerCount += count;
            if (representativeFaction.isBlank() || factionId.equals(attackerFaction))
            {
                representativeFaction = factionId;
            }
            localSide |= factionId.equals(localFaction) || TeamClientState.areAllied(factionId, localFaction);
            attackerSide |= factionId.equals(attackerFaction) || TeamClientState.areAllied(factionId, attackerFaction);
            defenderSide |= factionId.equals(defenderFaction) || TeamClientState.areAllied(factionId, defenderFaction);
        }

        private void mergeFrom(ForceSide other)
        {
            factionIds.addAll(other.factionIds);
            playerCount += other.playerCount;
            if (representativeFaction.isBlank() || other.attackerSide)
            {
                representativeFaction = other.representativeFaction;
            }
            localSide |= other.localSide;
            attackerSide |= other.attackerSide;
            defenderSide |= other.defenderSide;
        }

        private boolean isSameSide(String factionId)
        {
            for (String existingFaction : factionIds)
            {
                if (TeamClientState.areAllied(existingFaction, factionId))
                {
                    return true;
                }
            }
            return false;
        }

        private int playerCount()
        {
            return playerCount;
        }

        private boolean localSide()
        {
            return localSide;
        }

        private boolean defenderSide()
        {
            return defenderSide;
        }

        private String representativeFaction()
        {
            return representativeFaction;
        }
    }

    private static void drawRingSegment(GuiGraphics graphics, int centerX, int centerY, int size, int thickness, float progress, int argb)
    {
        float clampedProgress = Math.max(0.0F, Math.min(1.0F, progress));
        if (clampedProgress <= 0.0F)
        {
            return;
        }

        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        float outerRadius = size / 2.0F;
        float innerRadius = Math.max(0.0F, outerRadius - thickness);
        Matrix4f matrix = graphics.pose().last().pose();
        int steps = Math.max(2, (int) Math.ceil(RING_SEGMENTS * clampedProgress));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= steps; i++)
        {
            float angle = (float) Math.toRadians(-90.0F + (360.0F * clampedProgress * i / steps));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            buffer.vertex(matrix, centerX + cos * outerRadius, centerY + sin * outerRadius, 0.0F).color(red, green, blue, alpha).endVertex();
            buffer.vertex(matrix, centerX + cos * innerRadius, centerY + sin * innerRadius, 0.0F).color(red, green, blue, alpha).endVertex();
        }

        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }
}
