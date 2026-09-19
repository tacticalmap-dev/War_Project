package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.team.TeamClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Top-center ammo/fuel strip. It appears only while the game is RUNNING and the local player
 * belongs to a team; each entry draws the icon, the team's current amount and the "+per 60s"
 * income summed over that team's nodes.
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ResourceHudOverlay {
    private static final ResourceLocation AMMO_ICON = new ResourceLocation(WarProject.MODID, "textures/gui/ammo.png");
    private static final ResourceLocation FUEL_ICON = new ResourceLocation(WarProject.MODID, "textures/gui/fuel.png");
    private static final int TEXTURE_SIZE = 128;
    private static final int ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 4;
    private static final int AMOUNT_RATE_GAP = 5;
    private static final int ENTRY_GAP = 14;
    private static final int TOP_MARGIN = 4;
    private static final int PANEL_PADDING_X = 8;
    private static final int PANEL_PADDING_Y = 3;
    private static final int PANEL_COLOR = 0xFF000000;
    private static final int AMOUNT_COLOR = 0xFFFFFFFF;
    private static final int RATE_COLOR = 0xFF7CE38B;
    private static final int RATE_IDLE_COLOR = 0xFF9AA4AF;

    private ResourceHudOverlay() {
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "war_project_resources", ResourceHudOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || !ResourceClientState.isRunning()) {
            return;
        }
        Optional<String> teamId = TeamClientState.teamOf(minecraft.player.getScoreboardName());
        if (teamId.isEmpty()) {
            return;
        }
        Optional<WarProjectNetwork.ResourceTeamEntry> maybeEntry = ResourceClientState.entry(teamId.get());
        if (maybeEntry.isEmpty()) {
            return;
        }
        WarProjectNetwork.ResourceTeamEntry entry = maybeEntry.get();

        Font font = minecraft.font;
        String ammoAmount = amountText(entry.ammo());
        String ammoRate = rateText(entry.ammoPerMinute());
        String fuelAmount = amountText(entry.fuel());
        String fuelRate = rateText(entry.fuelPerMinute());

        int ammoWidth = entryWidth(font, ammoAmount, ammoRate);
        int fuelWidth = entryWidth(font, fuelAmount, fuelRate);
        int boxWidth = ammoWidth + ENTRY_GAP + fuelWidth + PANEL_PADDING_X * 2;
        int boxHeight = ICON_SIZE + PANEL_PADDING_Y * 2;
        int boxX = Math.max(2, (screenWidth - boxWidth) / 2);
        int boxY = TOP_MARGIN;

        drawCapsule(graphics, boxX, boxY, boxWidth, boxHeight, PANEL_COLOR);

        int contentX = boxX + PANEL_PADDING_X;
        int contentY = boxY + PANEL_PADDING_Y;
        drawEntry(graphics, font, AMMO_ICON, contentX, contentY, ammoAmount, ammoRate, entry.ammoPerMinute() > 0.0D);
        drawEntry(graphics, font, FUEL_ICON, contentX + ammoWidth + ENTRY_GAP, contentY, fuelAmount, fuelRate, entry.fuelPerMinute() > 0.0D);
    }

    /**
     * Dynamic-Island style black capsule: one fill per row whose inset follows a circle of radius
     * height/2, so the corners are true round caps instead of a plain rectangle.
     */
    private static void drawCapsule(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        double radius = height / 2.0D;
        for (int row = 0; row < height; row++) {
            double offset = row + 0.5D - radius;
            double half = Math.sqrt(Math.max(0.0D, radius * radius - offset * offset));
            int inset = (int) Math.round(radius - half);
            int left = x + inset;
            int right = x + width - inset;
            if (right > left) {
                graphics.fill(left, y + row, right, y + row + 1, color);
            }
        }
    }

    private static int entryWidth(Font font, String amount, String rate) {
        return ICON_SIZE + ICON_TEXT_GAP + font.width(amount) + AMOUNT_RATE_GAP + font.width(rate);
    }

    private static void drawEntry(GuiGraphics graphics, Font font, ResourceLocation icon, int x, int y,
                                  String amount, String rate, boolean gaining) {
        // The 11-argument overload is required: the shorter ones reuse the target width/height as the
        // sampled u/v size, so a 16x16 request would only sample the transparent top-left corner.
        graphics.blit(icon, x, y, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        int textY = y + (ICON_SIZE - font.lineHeight) / 2;
        int textX = x + ICON_SIZE + ICON_TEXT_GAP;
        graphics.drawString(font, amount, textX, textY, AMOUNT_COLOR, true);
        graphics.drawString(font, rate, textX + font.width(amount) + AMOUNT_RATE_GAP, textY,
                gaining ? RATE_COLOR : RATE_IDLE_COLOR, true);
    }

    private static String amountText(double value) {
        return String.valueOf((long) Math.floor(Math.max(0.0D, value)));
    }

    private static String rateText(double perMinute) {
        return "+" + (long) Math.floor(Math.max(0.0D, perMinute));
    }
}
