package com.flowingsun.war_project.compat.xaero;

public final class XaeroWorldMapOverlayHooks {
    private XaeroWorldMapOverlayHooks() {
    }

    public static void registerWorldMapHighlighter(xaero.map.highlight.HighlighterRegistry registry) {
        registry.register(new WarProjectWorldMapHighlighter());
    }
}
