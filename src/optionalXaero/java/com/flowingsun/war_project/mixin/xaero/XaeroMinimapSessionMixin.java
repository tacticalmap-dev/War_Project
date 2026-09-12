package com.flowingsun.war_project.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.common.minimap.highlight.HighlighterRegistry;

import java.lang.reflect.Method;

@Mixin(targets = "xaero.hud.minimap.module.MinimapSession", remap = false)
public abstract class XaeroMinimapSessionMixin {
    private static volatile Method warProject$registerMinimapHighlighterMethod;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/highlight/HighlighterRegistry;end()V"), require = 0)
    private void warProject$registerHighlighter(HighlighterRegistry registry) {
        Method method = warProject$registerMinimapHighlighterMethod;
        if (method == null) {
            method = warProject$findRegisterMinimapHighlighterMethod();
            warProject$registerMinimapHighlighterMethod = method;
        }
        if (method != null) {
            try {
                method.invoke(null, registry);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        registry.end();
    }

    private static Method warProject$findRegisterMinimapHighlighterMethod() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> hooks = Class.forName("com.flowingsun.war_project.compat.xaero.XaeroMinimapOverlayHooks", false, loader);
            return hooks.getMethod("registerMinimapHighlighter", HighlighterRegistry.class);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException ignored) {
            return null;
        }
    }
}
