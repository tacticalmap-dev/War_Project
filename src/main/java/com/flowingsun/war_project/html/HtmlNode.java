package com.flowingsun.war_project.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One element of the tiny DOM. Layout results and animation runtime values live here too. */
public final class HtmlNode {
    public final String tag;
    public final HtmlNode parent;
    public final List<HtmlNode> children = new ArrayList<>();
    public final Map<String, String> attributes = new LinkedHashMap<>();
    public final Set<String> classes = new LinkedHashSet<>();

    /** Inline style attribute; the source of truth when styles are re-evaluated. */
    public final Css inlineStyle = new Css();
    /** Base style (element selector + .class rules + inline style), rebuilt by refreshStyles(). */
    public Css style = new Css();
    /** Bumped whenever a class toggles, so the host can re-evaluate CSS rules. */
    public int classRevision;
    /** Style applied while hovered (:hover rules). */
    public Css hoverStyle;
    /** Style applied while pressed (:active rules). */
    public Css activeStyle;

    public String id = "";
    public String text = "";

    public int x;
    public int y;
    public int width;
    public int height;
    public int contentX;
    public int contentY;
    public int contentWidth;
    public int contentHeight;

    // interaction
    public boolean hovered;
    public boolean pressed;
    public boolean disabled;
    public boolean visible = true;

    // animation runtime (current values, eased towards the target style)
    public float currentOpacity = 1.0F;
    public float currentScale = 1.0F;
    public float currentTranslateX;
    public float currentTranslateY;
    public int currentBackground;
    public int currentColor;
    public boolean animating;

    public HtmlNode(String tag, HtmlNode parent) {
        this.tag = tag;
        this.parent = parent;
        if (parent != null) {
            parent.children.add(this);
        }
    }

    public Css effectiveStyle() {
        Css base = style;
        Css hover = hovered ? hoverStyle : null;
        Css active = pressed ? activeStyle : null;
        if (hover == null && active == null) {
            return base;
        }
        Css merged = base.copy();
        if (hover != null) {
            overlay(merged, hover);
        }
        if (active != null) {
            overlay(merged, active);
        }
        return merged;
    }

    /** Applies the non-default fields of {@code from} on top of {@code into}. */
    private static void overlay(Css into, Css from) {
        if (from.hasBackground) {
            into.background = from.background;
            into.hasBackground = true;
        }
        if (from.hasColor) {
            into.color = from.color;
            into.hasColor = true;
        }
        if (from.shadowSize >= 0) {
            into.shadowSize = from.shadowSize;
            into.shadowColor = from.shadowColor;
        }
        if (from.borderWidth > 0) {
            into.borderWidth = from.borderWidth;
            into.borderColor = from.borderColor;
        }
        if (from.hasOpacity) {
            into.opacity = from.opacity;
            into.hasOpacity = true;
        }
        if (from.hasScale) {
            into.scale = from.scale;
            into.hasScale = true;
        }
        if (from.hasTransform) {
            into.translateX = from.translateX;
            into.translateY = from.translateY;
            into.hasTransform = true;
        }
    }

    /** Resolves the {@code src} attribute into a texture, defaulting to this mod's namespace. */
    public net.minecraft.resources.ResourceLocation texture() {
        String src = attributes.get("src");
        if (src == null || src.isBlank()) {
            return null;
        }
        try {
            return src.contains(":")
                    ? new net.minecraft.resources.ResourceLocation(src)
                    : new net.minecraft.resources.ResourceLocation(com.flowingsun.war_project.WarProject.MODID, src);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void setClass(String name, boolean enabled) {
        boolean changed = enabled ? classes.add(name) : classes.remove(name);
        if (changed) {
            classRevision++;
        }
    }

    public boolean hasClass(String name) {
        return classes.contains(name);
    }

    public HtmlNode byId(String wantedId) {
        if (wantedId.equals(id)) {
            return this;
        }
        for (HtmlNode child : children) {
            HtmlNode found = child.byId(wantedId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public boolean contains(double px, double py) {
        return visible && currentOpacity >= 0.05F && px >= x && px < x + width && py >= y && py < y + height;
    }

    /** Deepest visible hit node, or null. */
    public HtmlNode hit(double px, double py) {
        if (!contains(px, py)) {
            return null;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            HtmlNode found = children.get(i).hit(px, py);
            if (found != null) {
                return found;
            }
        }
        return this;
    }
}
