package com.flowingsun.war_project.client.cef;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Adapts the GL texture CEF paints into so it can be drawn through {@code GuiGraphics.blit}.
 *
 * <p>The texture id belongs to the view, not to Minecraft, so {@link #close()} must not release it:
 * {@code TextureManager.release} and re-registration would otherwise delete a live CEF surface.
 */
public final class CefTexture extends AbstractTexture {
    private final CefOsrView view;

    public CefTexture(CefOsrView view) {
        this.view = view;
    }

    @Override
    public int getId() {
        return view.textureId();
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // The pixels arrive from CEF, not from a resource pack.
    }

    @Override
    public void close() {
        // Owned by the view.
    }
}
