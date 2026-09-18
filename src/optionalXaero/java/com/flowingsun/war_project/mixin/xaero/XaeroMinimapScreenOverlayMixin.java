package com.flowingsun.war_project.mixin.xaero;

import com.flowingsun.war_project.compat.xaero.XaeroMinimapOverlay;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

/**
 * Injects the War Project minimap overlay right after Xaero finishes its over-map element pass.
 *
 * <p>Two details matter here. First, the target is the abstract element handler that actually declares
 * {@code render} - the concrete over-map handler inherits it and does not declare its own. Second, the
 * method is matched by name only: the previous attempt redirected the handler call inside
 * {@code MinimapRenderer.renderMinimap}, which requires an eleven-parameter descriptor spelled out
 * byte for byte and is easy to get subtly wrong, in which case {@code require = 0} hides the failure.
 *
 * <p>The handler is shared by the framebuffer-side and world-side element handlers too, so an
 * {@code instanceof} check keeps this overlay on the on-screen over-map pass only. At {@code RETURN}
 * the pose is still the one Xaero prepared for over-map elements (origin at the minimap centre, its own
 * push/pop already balanced), which is the space {@link XaeroMinimapOverlay} draws in.
 */
@Mixin(targets = "xaero.hud.minimap.element.render.MinimapElementRendererHandler", remap = false)
public abstract class XaeroMinimapScreenOverlayMixin {
    private static final Logger WAR_PROJECT_LOGGER = LogUtils.getLogger();
    private static boolean warProject$loggedInjection;

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void warProject$renderMinimapOverlay(GuiGraphics graphics, Vec3 renderPos, float partialTick, RenderTarget renderTarget, double mapDimensionScale, ResourceKey<Level> dimension, CallbackInfo ci) {
        Object self = (Object) this;
        if (!(self instanceof MinimapElementOverMapRendererHandler handler)) {
            return;
        }
        if (!warProject$loggedInjection) {
            warProject$loggedInjection = true;
            WAR_PROJECT_LOGGER.info("War Project minimap overlay injected into the Xaero over-map element handler");
        }
        XaeroMinimapOverlay.renderWarProjectOverlay(graphics, handler, renderPos);
    }
}
