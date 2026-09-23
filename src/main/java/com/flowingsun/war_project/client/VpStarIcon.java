package com.flowingsun.war_project.client;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.client.xaero.XaeroWarProjectMapRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The star drawn under a VP node's name on both maps. Its tint follows the node's current owner, reusing
 * the same relation colours as the region borders (neutral white, own / allied blue, enemy red), so the
 * marker reads as "who holds this objective".
 */
public final class VpStarIcon {
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(WarProject.MODID, "textures/gui/vp_star.png");
    /** Source texture size; the 11-argument blit overload needs it explicitly. */
    public static final int TEXTURE_SIZE = 64;

    private VpStarIcon() {
    }

    /** Draws the star centred on {@code centerX}, with its top edge at {@code y}. */
    public static void draw(GuiGraphics graphics, String factionId, int centerX, int y, int size) {
        int argb = XaeroWarProjectMapRenderer.relationEdgeArgb(factionId);
        RenderSystem.setShaderColor(((argb >> 16) & 0xFF) / 255.0F, ((argb >> 8) & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, 1.0F);
        // 11 arguments on purpose: the shorter overloads reuse the target size as the sampled u/v size,
        // which would slice a tiny corner out of the sheet instead of scaling the whole icon.
        graphics.blit(TEXTURE, centerX - size / 2, y, size, size, 0.0F, 0.0F, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
