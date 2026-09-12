package com.flowingsun.war_project.compat.xaero;

public final class XaeroMinimapOverlayHooks {
    private XaeroMinimapOverlayHooks() {
    }

    public static void registerMinimapHighlighter(xaero.common.minimap.highlight.HighlighterRegistry registry) {
        registry.register(new WarProjectMinimapHighlighter());
    }
}
