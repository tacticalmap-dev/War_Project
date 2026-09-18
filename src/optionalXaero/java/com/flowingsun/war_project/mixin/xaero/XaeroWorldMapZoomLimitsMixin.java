package com.flowingsun.war_project.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces a 1x floor on the Xaero world map zoom.
 *
 * <p>Xaero clamps its target zoom in {@code GuiMap.applyZoomLimits()} (0.0625 by default, or
 * 0.001953125 when the "unlimited zoom out" option is on). That method is the single choke point:
 * it runs at the end of every zoom action and again on every frame render, so clamping
 * {@code destScale} here also covers zoom set from anywhere else.
 *
 * <p>{@code require = 0} follows this module's optional-compat convention: if a future Xaero build
 * renames the method, the floor silently stops applying instead of crashing the game.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroWorldMapZoomLimitsMixin {
    @Shadow
    private static double destScale;

    @Inject(method = "applyZoomLimits", at = @At("TAIL"), require = 0)
    private void warProject$enforceMinimumZoom(CallbackInfo ci) {
        if (destScale < 1.0D) {
            // War Project: never let the world map zoom out past 1x.
            destScale = 1.0D;
        }
    }
}
