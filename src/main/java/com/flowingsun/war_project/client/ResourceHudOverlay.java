package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.client.web.WebRenderer;
import com.flowingsun.war_project.client.web.WebRendererService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the resource island while no screen is open. When a screen (the chat screen) is open the
 * island is drawn by ResourceTransferController through the screen render event instead, so it stays
 * visible and clickable in "chat mode".
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ResourceHudOverlay {
    private ResourceHudOverlay() {
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "war_project_resources", ResourceHudOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        if (minecraft.screen != null) {
            // A screen is open: ResourceTransferController draws the island on top of it.
            return;
        }
        // The fused top HUD (VP war bar + island) is always drawn by the built in HTML kernel; the
        // Chromium backend only carries the transfer panel, which never shows without a screen.
        ResourceIslandView.render(graphics, screenWidth);
        // While Chromium is being prepared the built in island keeps drawing and this line explains
        // why nothing has changed yet.
        String status = WebRendererService.statusText();
        if (!status.isEmpty()) {
            graphics.drawString(minecraft.font, status,
                    (screenWidth - minecraft.font.width(status)) / 2, 26, 0xFF9AA4AF, true);
        }
    }
}
