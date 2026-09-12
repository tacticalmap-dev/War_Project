package com.flowingsun.war_project.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.map.highlight.HighlighterRegistry;

import java.lang.reflect.Method;

@Mixin(targets = "xaero.map.WorldMapSession", remap = false)
public abstract class XaeroWorldMapSessionMixin {
    private static volatile Method warProject$registerWorldMapHighlighterMethod;

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V"), require = 0)
    private void warProject$registerHighlighter(HighlighterRegistry registry) {
        Method method = warProject$registerWorldMapHighlighterMethod;
        if (method == null) {
            method = warProject$findRegisterWorldMapHighlighterMethod();
            warProject$registerWorldMapHighlighterMethod = method;
        }
        if (method != null) {
            try {
                method.invoke(null, registry);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        registry.end();
    }

    private static Method warProject$findRegisterWorldMapHighlighterMethod() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> hooks = Class.forName("com.flowingsun.war_project.compat.xaero.XaeroWorldMapOverlayHooks", false, loader);
            return hooks.getMethod("registerWorldMapHighlighter", HighlighterRegistry.class);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException ignored) {
            return null;
        }
    }
}
