package com.flowingsun.war_project.mixin.xaero;

import com.flowingsun.war_project.compat.xaero.XaeroMinimapFramebufferOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

/**
 * Injects the War Project overlay into the Xaero minimap framebuffer.
 *
 * <p>Targets the map element handler's {@code beforeRender}, which is an empty body and runs inside
 * {@code MinimapFBORenderer.renderChunksToFBO} while the rotation framebuffer is bound. Choosing that
 * point matters: the base {@code render} later applies a {@code pose.translate(0, 0, depth)} that is
 * never undone, and depth testing is enabled inside the framebuffer, so anything drawn after it can be
 * depth-rejected.
 *
 * <p>{@code require = 0} keeps this optional-compat safe: if a future Xaero build changes the method,
 * the overlay stops drawing instead of crashing.
 */
@Mixin(targets = "xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler", remap = false)
public abstract class XaeroMinimapFramebufferOverlayMixin {
    @Inject(method = "beforeRender", at = @At("RETURN"), require = 0)
    private void warProject$renderFramebufferOverlay(GuiGraphics graphics, MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers, CallbackInfo ci) {
        XaeroMinimapFramebufferOverlay.renderWarProjectOverlay(graphics, (MinimapElementMapRendererHandler) (Object) this, info.renderPos, info.framebuffer);
    }
}
