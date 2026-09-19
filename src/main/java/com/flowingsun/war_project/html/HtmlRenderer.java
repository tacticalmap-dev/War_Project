package com.flowingsun.war_project.html;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a laid-out document. Rounded corners are emulated row by row because GuiGraphics only fills
 * whole rectangles, and images use the 11-argument blit overload so the sampled region is the whole
 * texture (the shorter overloads reuse the draw size as the sample size).
 */
public final class HtmlRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEXTURE_SIZE = 128;
    /** Set -Dwarproject.html.corners=stepped to force the rectangle-based fallback. */
    private static boolean smoothCornersFailed;

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
        if (node.tag.equals("img")) {
            drawImage(graphics, node, opacity);
        } else {
            drawText(graphics, node, css, font, opacity);
        }
        for (HtmlNode child : node.children) {
            render(graphics, child, font, opacity);
        }
        if (transformed) {
            graphics.pose().popPose();
        }
    }

    private static void drawBackground(GuiGraphics graphics, HtmlNode node, Css css, float opacity) {
        int radius = css.pillRadius ? Math.max(0, node.height / 2) : css.borderRadius;
        if (css.shadowSize > 0) {
            int color = Css.scaleAlpha(css.shadowColor, opacity);
            fillRoundRect(graphics, node.x - css.shadowSize / 2, node.y + css.shadowSize / 2, node.width, node.height, radius, color);
        }
        if (css.hasBackground) {
            fillRoundRect(graphics, node.x, node.y, node.width, node.height, radius, Css.scaleAlpha(css.background, opacity));
        }
        if (css.borderWidth > 0) {
            strokeRoundRect(graphics, node.x, node.y, node.width, node.height, radius, css.borderWidth, Css.scaleAlpha(css.borderColor, opacity));
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
     * Smooth rounded rectangle: the corners are real circular arcs traced as a triangle fan instead
     * of stacked 1px rows, which removes the stepped edges of the approximating version.
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
        // Always draw the rectangle version first: if the arc pass is unavailable (shared Tesselator
        // state, driver quirks) the shape is still visible instead of vanishing. The arc pass then
        // paints over the stepped edges, so a working arc path yields smooth corners.
        fillRoundRectStepped(graphics, x, y, width, height, r, color);
        if (useSmoothCorners()) {
            try {
                graphics.flush();
                fillRoundRectSmooth(graphics, x, y, width, height, r, color);
                graphics.flush();
            } catch (Throwable throwable) {
                smoothCornersFailed = true;
                LOGGER.warn("Smooth corners unavailable; keeping the stepped fills", throwable);
            }
        }
    }

    private static boolean useSmoothCorners() {
        return !smoothCornersFailed && !"stepped".equalsIgnoreCase(System.getProperty("warproject.html.corners", ""));
    }

    /**
     * Arc-traced rounded rectangle. The flush calls around it matter: GuiGraphics and this code share
     * Tesselator's single BufferBuilder, so an unflushed GUI batch would be discarded by our begin().
     */
    private static void fillRoundRectSmooth(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        List<float[]> outline = outline(x, y, width, height, radius);
        Matrix4f matrix = graphics.pose().last().pose();
        int alpha = (color >>> 24) & 0xFF;
        int red = (color >>> 16) & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = color & 0xFF;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        for (float[] point : outline) {
            buffer.vertex(matrix, point[0], point[1], 0.0F).color(red, green, blue, alpha).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }

    /** Rectangle fallback with a one-pixel translucent edge so the corner steps read as soft. */
    private static void fillRoundRectStepped(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        for (int row = 0; row < height; row++) {
            double inset = insetForRow(row, height, radius);
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

    /** Smooth rounded outline of {@code thickness}px, built as a triangle strip ring. */
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
        List<float[]> outer = outline(x, y, width, height, r);
        List<float[]> inner = outline(x + t, y + t, width - t * 2, height - t * 2, Math.max(0, r - t));
        int count = Math.min(outer.size(), inner.size());
        graphics.flush();
        Matrix4f matrix = graphics.pose().last().pose();
        int alpha = (color >>> 24) & 0xFF;
        int red = (color >>> 16) & 0xFF;
        int green = (color >>> 8) & 0xFF;
        int blue = color & 0xFF;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= count; i++) {
            float[] out = outer.get(i % count);
            float[] in = inner.get(i % count);
            buffer.vertex(matrix, out[0], out[1], 0.0F).color(red, green, blue, alpha).endVertex();
            buffer.vertex(matrix, in[0], in[1], 0.0F).color(red, green, blue, alpha).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
        graphics.flush();
    }

    /** Clockwise outline of a rounded rectangle (arcs sampled per corner). */
    private static List<float[]> outline(int x, int y, int width, int height, int radius) {
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        int segments = Math.max(3, Math.min(16, Math.max(r, 3)));
        List<float[]> points = new ArrayList<>();
        if (r <= 0) {
            points.add(new float[]{x, y});
            points.add(new float[]{x + width, y});
            points.add(new float[]{x + width, y + height});
            points.add(new float[]{x, y + height});
            return points;
        }
        arc(points, x + r, y + r, r, 180.0F, 270.0F, segments);
        arc(points, x + width - r, y + r, r, 270.0F, 360.0F, segments);
        arc(points, x + width - r, y + height - r, r, 0.0F, 90.0F, segments);
        arc(points, x + r, y + height - r, r, 90.0F, 180.0F, segments);
        return points;
    }

    private static void arc(List<float[]> points, int centerX, int centerY, int radius,
                            float startDegrees, float endDegrees, int segments) {
        for (int i = 0; i <= segments; i++) {
            float angle = (float) Math.toRadians(startDegrees + (endDegrees - startDegrees) * i / segments);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            points.add(new float[]{centerX + cos * radius, centerY + sin * radius});
        }
    }
}
