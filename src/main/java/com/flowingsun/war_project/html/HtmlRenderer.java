package com.flowingsun.war_project.html;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a laid out document.
 *
 * <p>Decoration (rounded corners, rims, gloss and shadows) is painted through {@link HtmlTextures},
 * which rasterises the analytic outline of the shape: the corners are true curves with a one pixel
 * anti aliasing ramp rather than stacked whole pixel rows. Images use the 11-argument blit overload
 * so the sampled region is the whole texture (the shorter overloads reuse the draw size as the
 * sample size), and vector elements are rasterised by {@link HtmlVector}. If the generated texture
 * path ever fails, the renderer degrades to stepped rectangle fills so the UI never disappears.
 */
public final class HtmlRenderer {
    private static final int TEXTURE_SIZE = 128;

    private HtmlRenderer() {
    }

    public static void render(GuiGraphics graphics, HtmlNode node, Font font, float inheritedOpacity) {
        if (!node.visible) {
            return;
        }
        Css css = node.effectiveStyle();
        if (css.display.equals("none")) {
            return;
        }
        boolean vector = node.tag.equals("svg");
        float opacity = inheritedOpacity * Math.max(0.0F, Math.min(1.0F, node.currentOpacity));
        boolean transformed = node.currentScale != 1.0F || node.currentTranslateX != 0.0F || node.currentTranslateY != 0.0F;
        if (transformed) {
            graphics.pose().pushPose();
            float centerX = node.x + node.width / 2.0F;
            float centerY = node.y + node.height / 2.0F;
            graphics.pose().translate(node.currentTranslateX, node.currentTranslateY, 0.0F);
            graphics.pose().translate(centerX, centerY, 0.0F);
            graphics.pose().scale(node.currentScale, node.currentScale, 1.0F);
            graphics.pose().translate(-centerX, -centerY, 0.0F);
        }
        drawBackground(graphics, node, css, opacity);
        if (vector) {
            HtmlVector.draw(graphics, node, opacity);
        } else if (node.tag.equals("img")) {
            drawImage(graphics, node, opacity);
        } else {
            drawText(graphics, node, css, font, opacity);
        }
        if (!vector) {
            for (HtmlNode child : node.children) {
                render(graphics, child, font, opacity);
            }
        }
        if (transformed) {
            graphics.pose().popPose();
        }
    }

    private static void drawBackground(GuiGraphics graphics, HtmlNode node, Css css, float opacity) {
        int radius = css.pillRadius ? Math.max(0, node.height / 2) : css.borderRadius;
        if (HtmlTextures.paint(graphics, node.x, node.y, node.width, node.height, radius, css, opacity)) {
            return;
        }
        for (Css.Shadow shadow : css.shadows) {
            if (!shadow.inset) {
                fillRoundRect(graphics, node.x + shadow.offsetX, node.y + shadow.offsetY + Math.max(1, shadow.blur / 3),
                        node.width, node.height, radius, Css.scaleAlpha(shadow.color, opacity));
            }
        }
        if (css.hasBackground) {
            fillRoundRect(graphics, node.x, node.y, node.width, node.height, radius, Css.scaleAlpha(css.background, opacity));
        }
        if (css.borderWidth > 0) {
            strokeRoundRect(graphics, node.x, node.y, node.width, node.height, radius, css.borderWidth,
                    Css.scaleAlpha(css.borderColor, opacity));
        }
    }

    private static void drawText(GuiGraphics graphics, HtmlNode node, Css css, Font font, float opacity) {
        if (node.text.isEmpty()) {
            return;
        }
        int base = css.hasColor ? css.color : node.currentColor;
        int color = Css.scaleAlpha(base == 0 ? 0xFFFFFFFF : base, opacity);
        if ((color >>> 24) == 0) {
            return;
        }
        int textX = node.x + css.paddingLeft + css.borderWidth;
        int textY = node.y + css.paddingTop + css.borderWidth + Math.max(0, (node.height - css.paddingTop - css.paddingBottom - font.lineHeight) / 2);
        graphics.drawString(font, node.text, textX, textY, color, true);
    }

    private static void drawImage(GuiGraphics graphics, HtmlNode node, float opacity) {
        ResourceLocation texture = node.texture();
        if (texture == null) {
            return;
        }
        int width = Math.max(1, node.width - node.style.paddingLeft - node.style.paddingRight);
        int height = Math.max(1, node.height - node.style.paddingTop - node.style.paddingBottom);
        int color = Css.scaleAlpha(0xFFFFFFFF, opacity);
        graphics.setColor(1.0F, 1.0F, 1.0F, (color >>> 24) / 255.0F);
        graphics.blit(texture, node.x + node.style.paddingLeft, node.y + node.style.paddingTop,
                width, height, 0.0F, 0.0F, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Rectangle fallback with a one-pixel translucent edge so the corner steps read as soft. Used
     * only when the anti aliased texture path is unavailable.
     */
    public static void fillRoundRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (r <= 0) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        for (int row = 0; row < height; row++) {
            double inset = insetForRow(row, height, r);
            int floor = (int) Math.floor(inset);
            int left = x + floor;
            int right = x + width - floor;
            if (right > left) {
                graphics.fill(left, y + row, right, y + row + 1, color);
            }
            double fraction = inset - floor;
            if (fraction > 0.08D) {
                int edge = Css.scaleAlpha(color, (float) (1.0D - fraction));
                graphics.fill(left - 1, y + row, left, y + row + 1, edge);
                graphics.fill(right, y + row, right + 1, y + row + 1, edge);
            }
        }
    }

    /** Row based ring fallback: the outline minus the outline inset by {@code thickness}. */
    public static void strokeRoundRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int thickness, int color) {
        if (width <= 0 || height <= 0 || thickness <= 0 || (color >>> 24) == 0) {
            return;
        }
        int t = Math.min(thickness, Math.max(1, Math.min(width, height) / 2));
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (r <= 0) {
            int size = t;
            graphics.fill(x, y, x + width, y + size, color);
            graphics.fill(x, y + height - size, x + width, y + height, color);
            graphics.fill(x, y + size, x + size, y + height - size, color);
            graphics.fill(x + width - size, y + size, x + width, y + height - size, color);
            return;
        }
        int outerRadius = r;
        int innerRadius = Math.max(0, r - t);
        for (int row = 0; row < height; row++) {
            int outerLeft = (int) Math.floor(insetForRow(row, height, outerRadius));
            int outerRight = x + width - outerLeft;
            int innerRow = row - t;
            if (innerRow < 0 || innerRow >= height - t * 2) {
                graphics.fill(x + outerLeft, y + row, outerRight, y + row + 1, color);
                continue;
            }
            int innerLeft = (int) Math.floor(insetForRow(innerRow, height - t * 2, innerRadius));
            int leftEnd = x + t + innerLeft;
            int rightStart = x + width - t - innerLeft;
            if (leftEnd > x + outerLeft) {
                graphics.fill(x + outerLeft, y + row, leftEnd, y + row + 1, color);
            }
            if (outerRight > rightStart) {
                graphics.fill(rightStart, y + row, outerRight, y + row + 1, color);
            }
        }
    }

    private static double insetForRow(int row, int height, int radius) {
        if (radius <= 0) {
            return 0.0D;
        }
        double fromTop = row + 0.5D;
        double fromBottom = height - 0.5D - row;
        double distance = Math.min(fromTop, fromBottom);
        if (distance >= radius) {
            return 0.0D;
        }
        double delta = radius - distance;
        return radius - Math.sqrt(Math.max(0.0D, (double) radius * radius - delta * delta));
    }
}
