package com.flowingsun.war_project.mixin.xaero;

import com.flowingsun.war_project.compat.xaero.XaeroMinimapScreenOverlay;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

@Mixin(targets = "xaero.common.minimap.render.MinimapRenderer", remap = false)
public abstract class XaeroCommonMinimapRendererMixin {
    @Redirect(method = "renderMinimap(Lxaero/hud/minimap/module/MinimapSession;Lnet/minecraft/client/gui/GuiGraphics;Lxaero/common/minimap/MinimapProcessor;IIIIDIFLxaero/common/graphics/CustomVertexConsumers;)V", at = @At(value = "INVOKE", target = "Lxaero/hud/minimap/element/render/over/MinimapElementOverMapRendererHandler;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/phys/Vec3;FLcom/mojang/blaze3d/pipeline/RenderTarget;DLnet/minecraft/resources/ResourceKey;)V"), require = 0)
    private void warProject$renderMinimapOverlay(MinimapElementOverMapRendererHandler handler, GuiGraphics graphics, Vec3 renderPos, float partialTick, RenderTarget renderTarget, double mapDimensionScale, ResourceKey<Level> dimension) {
        XaeroMinimapScreenOverlay.renderWarProjectOverlay(graphics, handler, renderPos);
        handler.render(graphics, renderPos, partialTick, renderTarget, mapDimensionScale, dimension);
    }
}
