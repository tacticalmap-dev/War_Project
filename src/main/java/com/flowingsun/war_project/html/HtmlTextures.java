package com.flowingsun.war_project.html;

import com.flowingsun.war_project.WarProject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anti aliased box painting for the HTML kernel.
 *
 * <p>Rounded corners, rims, gradients and shadows are rasterised from the analytic signed distance
 * field of a rounded rectangle, so the outline is a real curve with a one pixel coverage ramp
 * instead of stacked whole pixel rows. The result is uploaded as a white texture whose RGB is
 * constant and which only carries alpha, which makes the pixels independent of the channel order
 * {@link NativeImage#setPixelRGBA} expects; the colour is applied at draw time with
 * {@code GuiGraphics.setColor}. Because the shapes are drawn by ordinary {@code blit} calls the GUI
 * batch never has to be flushed around them.
 */
public final class HtmlTextures {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ResourceLocation> CACHE = new LinkedHashMap<>(64, 0.75F, true);
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_SIDE = 4096;

    private static boolean unavailable;
    private static int sequence;

    private HtmlTextures() {
    }

    /** False once a texture upload failed: the renderer then keeps its rectangle based fallback. */
    public static boolean usable() {
        return !unavailable;
    }

    /**
     * Paints the box decoration of one element. Returns false when the caller should fall back to
     * plain rectangle fills (no rounded decoration was requested, or the texture path is broken).
     */
    public static boolean paint(GuiGraphics graphics, int x, int y, int width, int height, int radius,
                                Css css, float opacity) {
        if (unavailable || width <= 0 || height <= 0) {
            return false;
        }
        double r = Math.max(0.0D, Math.min(radius, Math.min(width, height) / 2.0D));
        boolean decorated = r > 0.0D || !css.shadows.isEmpty() || css.hasGradient || css.borderWidth > 0;
        if (!decorated) {
            return false;
        }
        try {
            for (Css.Shadow shadow : css.shadows) {
                if (!shadow.inset) {
                    drawOuterShadow(graphics, x, y, width, height, r, shadow, opacity);
                }
            }
            if (css.hasBackground) {
                drawShape(graphics, x, y, width, height, r, Css.scaleAlpha(css.background, opacity), 1.0F);
            } else if (css.hasGradient) {
                // A gradient alone still needs the base silhouette to stay opaque underneath.
                drawShape(graphics, x, y, width, height, r, Css.scaleAlpha(css.gradientTop, opacity), 1.0F);
            }
            if (css.hasGradient) {
                drawGradient(graphics, x, y, width, height, r, css, opacity);
            }
            if (css.borderWidth > 0) {
                drawRim(graphics, x, y, width, height, r, css.borderWidth, Css.scaleAlpha(css.borderColor, opacity));
            }
            for (Css.Shadow shadow : css.shadows) {
                if (shadow.inset) {
                    drawInsetShadow(graphics, x, y, width, height, r, shadow, opacity);
                }
            }
            return true;
        } catch (Throwable throwable) {
            unavailable = true;
            LOGGER.warn("Anti aliased box painting unavailable; keeping the rectangle fallback", throwable);
            return false;
        }
    }

    /** Solid silhouette tinted with {@code color}. */
    public static void drawShape(GuiGraphics graphics, int x, int y, int width, int height, double radius, int color, float strength) {
        if (strength <= 0.0F || (color >>> 24) == 0) {
            return;
        }
        int ras = rasterScale(width, height);
        int texWidth = width * ras;
        int texHeight = height * ras;
        String key = "s|" + texWidth + "x" + texHeight + "|" + radius + "|" + ras;
        ResourceLocation texture = CACHE.get(key);
        if (texture == null) {
            texture = texture(key, texWidth, texHeight, coverage(texWidth, texHeight, radius * ras), ras > 1);
        }
        blit(graphics, texture, x, y, width, height, scale(color, strength), texWidth, texHeight);
    }

    /** One pixel inner rim, brighter along the top edge, which is what reads as depth on a dark pill. */
    public static void drawRim(GuiGraphics graphics, int x, int y, int width, int height, double radius, int thickness, int color) {
        if ((color >>> 24) == 0 || thickness <= 0) {
            return;
        }
        int t = Math.max(1, Math.min(thickness, Math.min(width, height) / 2));
        double innerRadius = Math.max(0.0D, radius - t);
        int ras = rasterScale(width, height);
        int texWidth = width * ras;
        int texHeight = height * ras;
        int texInset = t * ras;
        String key = "r|" + texWidth + "x" + texHeight + "|" + radius + "|" + t + "|" + ras;
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            blit(graphics, cached, x, y, width, height, color, texWidth, texHeight);
            return;
        }
        // The lip ramp is baked into the alpha: 1.0 at the top edge sliding to 0.35 at the bottom.
        float[] values = new float[texWidth * texHeight];
        for (int py = 0; py < texHeight; py++) {
            double lip = 0.35D + 0.65D * (1.0D - (texHeight <= 1 ? 0.0D : py / (double) (texHeight - 1)));
            for (int px = 0; px < texWidth; px++) {
                double outer = coverageAt(px, py, texWidth, texHeight, radius * ras);
                double inner = coverageAt(px - texInset, py - texInset, texWidth - 2 * texInset, texHeight - 2 * texInset,
                        innerRadius * ras);
                double band = Math.max(0.0D, outer - inner);
                values[py * texWidth + px] = (float) Math.min(1.0D, band * lip);
            }
        }
        blit(graphics, texture(key, texWidth, texHeight, values, ras > 1), x, y, width, height, color, texWidth, texHeight);
    }

    /** Vertical two stop gloss, clipped to the silhouette. */
    public static void drawGradient(GuiGraphics graphics, int x, int y, int width, int height, double radius, Css css, float opacity) {
        int color = Css.scaleAlpha(css.gradientTop, opacity);
        if ((color >>> 24) == 0 && (Css.scaleAlpha(css.gradientBottom, opacity) >>> 24) == 0) {
            return;
        }
        int topAlpha = color >>> 24;
        int bottomAlpha = Css.scaleAlpha(css.gradientBottom, opacity) >>> 24;
        int ras = rasterScale(width, height);
        int texWidth = width * ras;
        int texHeight = height * ras;
        String key = "g|" + texWidth + "x" + texHeight + "|" + radius + "|" + topAlpha + "|" + bottomAlpha + "|" + ras;
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            blit(graphics, cached, x, y, width, height, color, texWidth, texHeight);
            return;
        }
        // The texture carries the ramp shape only (1.0 at the top), the tint colour carries the
        // magnitude, so the alpha is applied exactly once.
        double bottomRatio = topAlpha <= 0 ? 0.0D : bottomAlpha / (double) topAlpha;
        float[] values = new float[texWidth * texHeight];
        for (int py = 0; py < texHeight; py++) {
            double t = texHeight <= 1 ? 0.0D : py / (double) (texHeight - 1);
            double ramp = 1.0D + (bottomRatio - 1.0D) * t;
            for (int px = 0; px < texWidth; px++) {
                values[py * texWidth + px] = (float) (coverageAt(px, py, texWidth, texHeight, radius * ras) * ramp);
            }
        }
        blit(graphics, texture(key, texWidth, texHeight, values, ras > 1), x, y, width, height, color, texWidth, texHeight);
    }

    /** Soft drop shadow: the distance field is blurred over {@code blur} pixels around the outline. */
    public static void drawOuterShadow(GuiGraphics graphics, int x, int y, int width, int height, double radius,
                                       Css.Shadow shadow, float opacity) {
        int color = Css.scaleAlpha(shadow.color, opacity);
        if ((color >>> 24) == 0) {
            return;
        }
        int pad = (int) Math.ceil(shadow.blur * 0.5D) + 1 + Math.max(Math.abs(shadow.offsetX), Math.abs(shadow.offsetY));
        int drawWidth = width + pad * 2;
        int drawHeight = height + pad * 2;
        int ras = rasterScale(drawWidth, drawHeight);
        int texWidth = drawWidth * ras;
        int texHeight = drawHeight * ras;
        String key = "o|" + texWidth + "x" + texHeight + "|" + radius + "|" + pad + "|"
                + shadow.offsetX + "|" + shadow.offsetY + "|" + shadow.blur + "|" + ras;
        int drawX = x - pad + shadow.offsetX;
        int drawY = y - pad + shadow.offsetY;
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            blit(graphics, cached, drawX, drawY, drawWidth, drawHeight, color, texWidth, texHeight);
            return;
        }
        double ramp = Math.max(1.0D, shadow.blur * 0.85D) * ras;
        float[] values = new float[texWidth * texHeight];
        for (int py = 0; py < texHeight; py++) {
            for (int px = 0; px < texWidth; px++) {
                double sx = px - (pad + shadow.offsetX) * ras + 0.5D;
                double sy = py - (pad + shadow.offsetY) * ras + 0.5D;
                double distance = signedDistance(sx, sy, width * ras, height * ras, radius * ras);
                values[py * texWidth + px] = (float) clamp01(0.5D - distance / ramp);
            }
        }
        blit(graphics, texture(key, texWidth, texHeight, values, ras > 1), drawX, drawY, drawWidth, drawHeight, color,
                texWidth, texHeight);
    }

    /** Inner shadow: opaque at the edge, fading towards the middle, clipped to the silhouette. */
    public static void drawInsetShadow(GuiGraphics graphics, int x, int y, int width, int height, double radius,
                                       Css.Shadow shadow, float opacity) {
        int color = Css.scaleAlpha(shadow.color, opacity);
        if ((color >>> 24) == 0) {
            return;
        }
        int ras = rasterScale(width, height);
        int texWidth = width * ras;
        int texHeight = height * ras;
        String key = "i|" + texWidth + "x" + texHeight + "|" + radius + "|"
                + shadow.offsetX + "|" + shadow.offsetY + "|" + shadow.blur + "|" + ras;
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            blit(graphics, cached, x, y, width, height, color, texWidth, texHeight);
            return;
        }
        double ramp = Math.max(1.0D, shadow.blur * 1.2D) * ras;
        float[] values = new float[texWidth * texHeight];
        for (int py = 0; py < texHeight; py++) {
            for (int px = 0; px < texWidth; px++) {
                double own = coverageAt(px, py, texWidth, texHeight, radius * ras);
                if (own <= 0.0D) {
                    continue;
                }
                double distance = signedDistance(px - shadow.offsetX * ras + 0.5D, py - shadow.offsetY * ras + 0.5D,
                        texWidth, texHeight, radius * ras);
                values[py * texWidth + px] = (float) (own * clamp01(1.0D - (-distance) / ramp));
            }
        }
        blit(graphics, texture(key, texWidth, texHeight, values, ras > 1), x, y, width, height, color, texWidth, texHeight);
    }

    // ---------------------------------------------------------------- rasterisation helpers

    /**
     * How many texels one GUI pixel is rasterised at. Minecraft scales the GUI up by the GUI scale, so
     * a shape generated at one texel per GUI pixel shows visible stair steps once it is on screen;
     * rendering it denser and letting the GPU filter it back down is what makes the HUD look smooth.
     */
    public static int supersample() {
        try {
            double scale = Minecraft.getInstance().getWindow().getGuiScale();
            return Math.max(1, Math.min(4, (int) Math.ceil(scale)));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /** Supersampling that still fits inside {@link #MAX_SIDE}. */
    public static int rasterScale(int width, int height) {
        int scale = supersample();
        if (width > 256 || height > 256) {
            // Large surfaces (the HUD bar spans the window) would otherwise cost millions of samples in
            // one frame; two texels per GUI pixel is already smooth at that size.
            scale = Math.min(scale, 2);
        }
        while (scale > 1 && (width * scale > MAX_SIDE || height * scale > MAX_SIDE)) {
            scale--;
        }
        return scale;
    }

    private static float[] coverage(int width, int height, double radius) {
        float[] values = new float[width * height];
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                values[py * width + px] = (float) coverageAt(px, py, width, height, radius);
            }
        }
        return values;
    }

    /** Exact rounded rectangle signed distance field (negative inside), sampled at the pixel centre. */
    private static double signedDistance(double px, double py, int width, int height, double radius) {
        double r = Math.max(0.0D, Math.min(radius, Math.min(width, height) / 2.0D));
        double halfWidth = width / 2.0D;
        double halfHeight = height / 2.0D;
        double qx = Math.abs(px - halfWidth) - (halfWidth - r);
        double qy = Math.abs(py - halfHeight) - (halfHeight - r);
        double outside = Math.hypot(Math.max(qx, 0.0D), Math.max(qy, 0.0D));
        double inside = Math.min(Math.max(qx, qy), 0.0D);
        return outside + inside - r;
    }

    /** One pixel wide analytic anti aliasing ramp. */
    private static double coverageAt(double px, double py, int width, int height, double radius) {
        if (width <= 0 || height <= 0) {
            return 0.0D;
        }
        return clamp01(0.5D - signedDistance(px + 0.5D, py + 0.5D, width, height, radius));
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }

    private static int scale(int color, float strength) {
        int alpha = Math.max(0, Math.min(255, Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, strength)))));
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- texture cache

    private static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                             int color, int texWidth, int texHeight) {
        if (texture == null || width <= 0 || height <= 0) {
            return;
        }
        graphics.setColor(((color >>> 16) & 0xFF) / 255.0F, ((color >>> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, ((color >>> 24) & 0xFF) / 255.0F);
        // The sampled region is the whole texture, which may be denser than the drawn rectangle.
        graphics.blit(texture, x, y, width, height, 0.0F, 0.0F, texWidth, texHeight, texWidth, texHeight);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Looks up (or uploads) a white alpha texture for the given coverage map. */
    static ResourceLocation texture(String key, int width, int height, float[] values, boolean smooth) {
        if (unavailable || width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
            return null;
        }
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            if (CACHE.size() >= MAX_ENTRIES) {
                clear();
            }
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int alpha = Math.round((float) clamp01(values[y * width + x]) * 255.0F);
                    image.setPixelRGBA(x, y, (alpha << 24) | 0xFFFFFF);
                }
            }
            DynamicTexture dynamic = new DynamicTexture(image);
            // Antialiased shapes are rasterised denser than they are drawn, so they need a smooth
            // filter; a 1:1 texture keeps nearest so hairline details stay crisp.
            dynamic.setFilter(smooth, smooth);
            ResourceLocation location = new ResourceLocation(WarProject.MODID, "html/tex_" + (sequence++));
            Minecraft.getInstance().getTextureManager().register(location, dynamic);
            CACHE.put(key, location);
            return location;
        } catch (Throwable throwable) {
            unavailable = true;
            LOGGER.warn("Could not upload a generated texture; falling back to plain fills", throwable);
            return null;
        }
    }

    private static void clear() {
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation location : CACHE.values()) {
            manager.release(location);
        }
        CACHE.clear();
    }
}
