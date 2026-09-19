package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Red "return to the map area" warning right below the crosshair, driven by {@link OutOfMapClientState}.
 * Drawn as flat red text: no drop shadow and no outline passes.
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OutOfMapHudOverlay {
    private static final int TEXT_COLOR = 0xFFFF4444;
    private static final int CROSSHAIR_GAP = 12;

    private OutOfMapHudOverlay() {
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "war_project_out_of_map", OutOfMapHudOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (!OutOfMapClientState.isActive()) {
            return;
        }
        String text = "请在 " + OutOfMapClientState.remainingSeconds() + "s 内返回地图区域";
        // Centring is done by hand because drawCenteredString always forwards shadow = true (bytecode
        // iconst_1), and the boolean overload is the only way to draw the text without a shadow.
        int x = screenWidth / 2 - minecraft.font.width(text) / 2;
        int y = screenHeight / 2 + CROSSHAIR_GAP;
        graphics.drawString(minecraft.font, text, x, y, TEXT_COLOR, false);
    }
}
