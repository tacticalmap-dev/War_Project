package com.flowingsun.war_project.html;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts one document: layout on render, animation on tick, and pointer events with CSS :hover/:active
 * state. Data binding is plain Java (mutate node text/classes, then the transitions animate them).
 */
public final class HtmlViewHost {
    private final HtmlDocument document;
    private final Map<String, Runnable> clickHandlers = new LinkedHashMap<>();
    private final Map<HtmlNode, Integer> classRevisions = new LinkedHashMap<>();
    private HtmlNode hovered;
    private HtmlNode pressed;
    private boolean layoutDirty = true;
    private int lastOriginX;
    private int lastOriginY;
    private int lastViewportWidth = -1;

    public HtmlViewHost(HtmlDocument document) {
        this.document = document;
    }

    public HtmlDocument document() {
        return document;
    }

    public HtmlNode node(String id) {
        return document.byId(id);
    }

    public void setOnClick(String id, Runnable action) {
        clickHandlers.put(id, action);
    }

    public void markLayoutDirty() {
        layoutDirty = true;
    }

    public void tick(float deltaMs) {
        List<HtmlNode> nodes = all();
        boolean stylesDirty = false;
        for (HtmlNode node : nodes) {
            Integer seen = classRevisions.get(node);
            if (seen == null || seen != node.classRevision) {
                classRevisions.put(node, node.classRevision);
                stylesDirty = true;
            }
        }
        if (stylesDirty) {
            document.refreshStyles();
        }
        for (HtmlNode node : nodes) {
            Css target = node.effectiveStyle();
            float duration = target.transitionMs;
            float step = duration <= 0.0F ? 1.0F : Math.min(1.0F, deltaMs / duration);
            node.currentOpacity += (target.opacity - node.currentOpacity) * step;
            float targetScale = target.hasScale ? target.scale : 1.0F;
            float targetTranslateX = target.hasTransform ? target.translateX : 0.0F;
            float targetTranslateY = target.hasTransform ? target.translateY : 0.0F;
            node.currentScale += (targetScale - node.currentScale) * step;
            node.currentTranslateX += (targetTranslateX - node.currentTranslateX) * step;
            node.currentTranslateY += (targetTranslateY - node.currentTranslateY) * step;
            int targetBackground = target.hasBackground ? target.background : 0;
            node.currentBackground = blend(node.currentBackground, targetBackground, step);
            int targetColor = target.hasColor ? target.color : 0xFFFFFFFF;
            node.currentColor = blend(node.currentColor, targetColor, step);
        }
    }

    public void render(GuiGraphics graphics, Font font, int originX, int originY, int viewportWidth) {
        if (layoutDirty || originX != lastOriginX || originY != lastOriginY || viewportWidth != lastViewportWidth) {
            HtmlLayout.apply(document, font, originX, originY, viewportWidth);
            layoutDirty = false;
            lastOriginX = originX;
            lastOriginY = originY;
            lastViewportWidth = viewportWidth;
        }
        HtmlRenderer.render(graphics, document.root, font, 1.0F);
    }

    /** Moves the hover state to the node under the pointer. */
    public void mouseMoved(double mouseX, double mouseY) {
        HtmlNode hit = document.hit(mouseX, mouseY);
        HtmlNode target = hit;
        while (target != null && !target.style.pointerEvents) {
            target = target.parent;
        }
        if (hovered == target) {
            return;
        }
        if (hovered != null) {
            hovered.hovered = false;
        }
        hovered = target;
        if (hovered != null) {
            hovered.hovered = true;
        }
    }

    public boolean mousePressed(double mouseX, double mouseY, int button) {
        HtmlNode hit = document.hit(mouseX, mouseY);
        if (hit == null) {
            return false;
        }
        HtmlNode node = hit;
        while (node != null) {
            if (node.disabled) {
                return true;
            }
            if (clickHandlers.containsKey(node.id)) {
                pressed = node;
                node.pressed = true;
                return true;
            }
            node = node.parent;
        }
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        HtmlNode node = pressed;
        pressed = null;
        if (node == null) {
            return false;
        }
        node.pressed = false;
        if (node.contains(mouseX, mouseY)) {
            Runnable action = clickHandlers.get(node.id);
            if (action != null) {
                action.run();
            }
        }
        return true;
    }

    /** True when the point lies inside any laid-out element (used to swallow screen events). */
    public boolean contains(double mouseX, double mouseY) {
        return document.root.hit(mouseX, mouseY) != null;
    }

    public HtmlNode hovered() {
        return hovered;
    }

    private List<HtmlNode> all() {
        List<HtmlNode> nodes = new ArrayList<>();
        collect(document.root, nodes);
        return nodes;
    }

    private static void collect(HtmlNode node, List<HtmlNode> out) {
        out.add(node);
        for (HtmlNode child : node.children) {
            collect(child, out);
        }
    }

    private static int blend(int from, int to, float step) {
        if (step >= 1.0F) {
            return to;
        }
        int a = lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, step);
        int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, step);
        int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, step);
        int b = lerp(from & 0xFF, to & 0xFF, step);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, float step) {
        return Math.round(from + (to - from) * step);
    }
}
