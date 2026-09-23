package com.flowingsun.war_project.html;

import net.minecraft.client.gui.Font;

/**
 * Box-model layout with a simplified flex implementation (single line, row or column), margins,
 * padding, fixed sizes, min/max width and absolute positioning. Text is measured with the vanilla
 * font and never wraps.
 */
public final class HtmlLayout {
    private HtmlLayout() {
    }

    public static void apply(HtmlDocument document, Font font, int originX, int originY, int viewportWidth) {
        HtmlNode root = document.root;
        int width = root.style.width >= 0 ? root.style.width : measureWidth(root, font);
        if (root.style.maxWidth >= 0) {
            width = Math.min(width, root.style.maxWidth);
        }
        root.x = originX;
        root.y = originY;
        root.width = Math.max(0, width);
        root.height = measureHeight(root, font);
        arrange(root, font, root.x, root.y, root.width);
        // Root is centered horizontally inside the viewport by the caller passing a matching origin.
        if (viewportWidth > 0 && root.style.width < 0 && root.style.position.equals("absolute")) {
            root.x = originX;
        }
    }

    private static void arrange(HtmlNode node, Font font, int x, int y, int width) {
        Css css = node.effectiveStyle();
        node.x = x;
        node.y = y;
        node.width = width;
        node.height = css.height >= 0 ? css.height : Math.max(0, measureHeight(node, font));
        node.contentX = x + css.paddingLeft + css.borderWidth;
        node.contentY = y + css.paddingTop + css.borderWidth;
        node.contentWidth = Math.max(0, node.width - css.paddingLeft - css.paddingRight - css.borderWidth * 2);
        node.contentHeight = Math.max(0, node.height - css.paddingTop - css.paddingBottom - css.borderWidth * 2);

        if (node.tag.equals("svg")) {
            // Vector children are drawn inside the svg box, they are not laid out as boxes.
            return;
        }
        boolean row = css.display.equals("flex") && css.flexDirection.equals("row");
        int cursor = node.contentY;
        int rowCursor = node.contentX;
        int maxCross = 0;
        for (HtmlNode child : node.children) {
            Css childCss = child.effectiveStyle();
            if (childCss.display.equals("none")) {
                child.visible = false;
                continue;
            }
            child.visible = true;
            int childWidth = childCss.width >= 0 ? childCss.width : measureWidth(child, font);
            if (childCss.maxWidth >= 0) {
                childWidth = Math.min(childWidth, childCss.maxWidth);
            }
            int childHeight = childCss.height >= 0 ? childCss.height : measureHeight(child, font);
            // Absolutely positioned children are laid out (so they get a size and lay out their own
            // children) but must not consume space in the flow: otherwise an absolutely positioned
            // overlay pushes its siblings down by its own height.
            boolean absolute = childCss.position.equals("absolute");
            int cx;
            int cy;
            if (row) {
                cx = rowCursor + childCss.marginLeft;
                cy = node.contentY + childCss.marginTop;
                if (css.alignItems.equals("center")) {
                    cy = node.contentY + (node.contentHeight - childHeight) / 2;
                }
                if (!absolute) {
                    rowCursor = cx + childWidth + childCss.marginRight + css.gap;
                    maxCross = Math.max(maxCross, childHeight + childCss.marginTop + childCss.marginBottom);
                }
            } else {
                cx = node.contentX + childCss.marginLeft;
                cy = cursor + childCss.marginTop;
                if (css.alignItems.equals("center")) {
                    cx = node.contentX + (node.contentWidth - childWidth) / 2;
                }
                if (!absolute) {
                    cursor = cy + childHeight + childCss.marginBottom + css.gap;
                }
            }
            arrange(child, font, cx, cy, childWidth);
        }
        if (css.justify.equals("center")) {
            int used = row ? rowCursor - node.contentX - css.gap : cursor - node.contentY - css.gap;
            int free = row ? node.contentWidth - used : node.contentHeight - used;
            if (free > 0) {
                // Centre the line by moving the children, never the box itself: shifting the box would
                // drag it out of whatever centred it inside its own parent, which showed up as the
                // whole bar sitting off centre with all its content pushed to one side.
                int dx = row ? free / 2 : 0;
                int dy = row ? 0 : free / 2;
                for (HtmlNode child : node.children) {
                    shift(child, dx, dy);
                }
            }
        }
        for (HtmlNode child : node.children) {
            applyAbsolute(child, node, font);
        }
    }

    private static void shift(HtmlNode node, int dx, int dy) {
        node.x += dx;
        node.y += dy;
        node.contentX += dx;
        node.contentY += dy;
        for (HtmlNode child : node.children) {
            shift(child, dx, dy);
        }
    }

    /** Absolute children resolve against the parent's content box. */
    private static void applyAbsolute(HtmlNode child, HtmlNode parent, Font font) {
        Css css = child.effectiveStyle();
        if (!css.position.equals("absolute")) {
            return;
        }
        int childWidth = css.width >= 0 ? css.width : measureWidth(child, font);
        int childHeight = css.height >= 0 ? css.height : measureHeight(child, font);
        int cx = css.left != Integer.MIN_VALUE ? parent.contentX + css.left
                : css.right != Integer.MIN_VALUE ? parent.contentX + parent.contentWidth - childWidth - css.right
                : child.x;
        int cy = css.top != Integer.MIN_VALUE ? parent.contentY + css.top
                : css.bottom != Integer.MIN_VALUE ? parent.contentY + parent.contentHeight - childHeight - css.bottom
                : child.y;
        shift(child, cx - child.x, cy - child.y);
    }

    public static int measureWidth(HtmlNode node, Font font) {
        Css css = node.effectiveStyle();
        if (css.display.equals("none")) {
            return 0;
        }
        if (css.width >= 0) {
            return css.width;
        }
        boolean row = css.display.equals("flex") && css.flexDirection.equals("row");
        int inner;
        if (node.tag.equals("img") || node.tag.equals("svg")) {
            inner = css.width >= 0 ? css.width : 16;
        } else if (!node.text.isEmpty()) {
            inner = font.width(node.text);
        } else if (row) {
            inner = 0;
            for (HtmlNode child : node.children) {
                Css childCss = child.effectiveStyle();
                if (childCss.position.equals("absolute") || childCss.display.equals("none")) {
                    continue;
                }
                inner += measureWidth(child, font) + childCss.marginLeft + childCss.marginRight + css.gap;
            }
            inner = Math.max(0, inner - css.gap);
        } else {
            inner = 0;
            for (HtmlNode child : node.children) {
                Css childCss = child.effectiveStyle();
                if (childCss.position.equals("absolute") || childCss.display.equals("none")) {
                    continue;
                }
                inner = Math.max(inner, measureWidth(child, font) + childCss.marginLeft + childCss.marginRight);
            }
        }
        int total = inner + css.paddingLeft + css.paddingRight + css.borderWidth * 2 + css.marginLeft + css.marginRight;
        if (css.minWidth >= 0) {
            total = Math.max(total, css.minWidth);
        }
        if (css.maxWidth >= 0) {
            total = Math.min(total, css.maxWidth);
        }
        return total;
    }

    public static int measureHeight(HtmlNode node, Font font) {
        Css css = node.effectiveStyle();
        if (css.display.equals("none")) {
            return 0;
        }
        if (css.height >= 0) {
            return css.height;
        }
        boolean row = css.display.equals("flex") && css.flexDirection.equals("row");
        int inner;
        if (node.tag.equals("img") || node.tag.equals("svg")) {
            inner = 16;
        } else if (!node.text.isEmpty() && node.children.isEmpty()) {
            inner = font.lineHeight;
        } else if (row) {
            inner = 0;
            for (HtmlNode child : node.children) {
                Css childCss = child.effectiveStyle();
                if (childCss.position.equals("absolute") || childCss.display.equals("none")) {
                    continue;
                }
                inner = Math.max(inner, measureHeight(child, font) + childCss.marginTop + childCss.marginBottom);
            }
        } else {
            inner = 0;
            for (HtmlNode child : node.children) {
                Css childCss = child.effectiveStyle();
                if (childCss.position.equals("absolute") || childCss.display.equals("none")) {
                    continue;
                }
                inner += measureHeight(child, font) + childCss.marginTop + childCss.marginBottom + css.gap;
            }
            inner = Math.max(0, inner - css.gap);
        }
        return Math.max(0, inner + css.paddingTop + css.paddingBottom + css.borderWidth * 2 + css.marginTop + css.marginBottom);
    }
}
