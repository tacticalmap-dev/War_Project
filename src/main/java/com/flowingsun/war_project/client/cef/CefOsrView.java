package com.flowingsun.war_project.client.cef;

import com.flowingsun.war_project.WarProject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.cef.browser.CefBrowser;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One off screen Chromium surface: a page, its pixels, the GL texture they land in and the input
 * forwarding for it.
 *
 * <p>CEF paints on its own thread, so a frame is copied into a direct buffer the render thread owns
 * and the upload happens during drawing. Pixels arrive as premultiplied BGRA, which is both the byte
 * order GL reads as {@code GL_BGRA} and the reason the blit switches to
 * {@code ONE, ONE_MINUS_SRC_ALPHA} blending for the duration of the draw.
 */
public final class CefOsrView extends WpCefBrowser {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BYTES_PER_PIXEL = 4;

    private final String name;
    private final ResourceLocation location;
    private final AtomicBoolean dirty = new AtomicBoolean();
    /** Guards {@link #frame}: CEF paints on its own thread while uploading/freeing happens on the render thread. */
    private final Object frameLock = new Object();

    private ByteBuffer frame;
    private int guiWidth;
    private int guiHeight;
    private int pixelWidth;
    private int pixelHeight;
    /** Minecraft GUI scale: the surface is laid out in GUI pixels and painted at this multiple. */
    private int deviceScale = 1;
    private int textureId;
    private boolean uploaded;
    private boolean registered;
    private boolean disposed;
    /** Frames CEF has painted; zero means the surface content is undefined and must not be drawn. */
    private volatile int frames;

    public CefOsrView(String name, String page) {
        super(CefBootstrap.client(), url(page), true, null);
        this.name = name;
        LOGGER.info("War Project CEF view {}: page {} chars, data url {} chars", name, page.length(), url(page).length());
        this.location = new ResourceLocation(WarProject.MODID, "cef/" + name);
        // java-cef only asks CEF to create the native browser when this is called (see
        // CefBrowserOsr#createImmediately -> CefBrowser_N#createBrowser -> N_CreateBrowser). Without it
        // the page is never loaded and the surface never paints a single frame.
        createImmediately();
        LOGGER.info("War Project CEF view {}: page {} chars, data url {} chars, browserId {}",
                name, page.length(), url(page).length(), getIdentifier());
    }

    private static String url(String page) {
        return "data:text/html;charset=utf-8," + PercentCodec.encode(page);
    }

    public String viewName() {
        return name;
    }

    public ResourceLocation location() {
        return location;
    }

    public int textureId() {
        return textureId;
    }

    public int guiWidth() {
        return guiWidth;
    }

    public int guiHeight() {
        return guiHeight;
    }

    /** The device scale factor currently reported to CEF. */
    public int deviceScale() {
        return deviceScale;
    }

    /**
     * Sets the size the page is displayed at, and the GUI scale it should be painted for.
     *
     * <p>The browser rectangle stays in GUI pixels and CEF multiplies it by the device scale factor we
     * report in {@link #getScreenInfo}, so the frame it paints is {@code gui * scale} pixels. Minecraft
     * then draws that frame into the same {@code gui} rectangle, which is one surface pixel per physical
     * screen pixel. Reporting 1 here instead (the first attempt) meant one page pixel was stretched over
     * {@code scale} screen pixels, which is what made the island and its icons look blurred.
     */
    public void setGuiSize(int width, int height, int guiScale) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int scale = Math.max(1, guiScale);
        boolean scaleChanged = scale != deviceScale;
        if (w == guiWidth && h == guiHeight && !scaleChanged) {
            return;
        }
        guiWidth = w;
        guiHeight = h;
        deviceScale = scale;
        int pixelW = w * deviceScale;
        int pixelH = h * deviceScale;
        if (pixelW != pixelWidth || pixelH != pixelHeight) {
            pixelWidth = pixelW;
            pixelHeight = pixelH;
            int bytes = pixelWidth * pixelHeight * BYTES_PER_PIXEL;
            synchronized (frameLock) {
                if (frame == null || frame.capacity() < bytes) {
                    if (frame != null) {
                        org.lwjgl.system.MemoryUtil.memFree(frame);
                    }
                    frame = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
                }
                frame.clear();
                uploaded = false;
                dirty.set(false);
            }
        }
        resize(guiWidth, guiHeight);
    }

    /** Creates the GL texture and registers the adapter so {@code blit} can resolve it. */
    private void ensureTexture() {
        if (textureId == 0) {
            textureId = GL11.glGenTextures();
            RenderSystem.bindTexture(textureId);
            // Nearest keeps a 1:1 surface pixel perfect instead of smearing the text.
            RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            RenderSystem.bindTexture(0);
            clearTexture();
        }
        // A resource pack reload closes every texture and clears the manager, so the adapter is
        // re-registered whenever it is missing.
        if (!registered || Minecraft.getInstance().getTextureManager().getTexture(location, null) == null) {
            Minecraft.getInstance().getTextureManager().register(location, new CefTexture(this));
            registered = true;
        }
    }

    /**
     * Uploads one fully transparent frame. A GL texture has undefined content until something is
     * written to it, and on real drivers that reads back as opaque black, which is exactly the black
     * rectangle that showed up before the first CEF frame.
     */
    private void clearTexture() {
        if (frame == null || pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        frame.clear();
        for (int i = 0; i < frame.capacity(); i++) {
            frame.put(i, (byte) 0);
        }
        RenderSystem.bindTexture(textureId);
        RenderSystem.pixelStore(GL11.GL_UNPACK_ALIGNMENT, BYTES_PER_PIXEL);
        RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, pixelWidth);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, pixelWidth, pixelHeight, 0,
                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, frame);
        RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        RenderSystem.bindTexture(0);
        uploaded = true;
    }

    /** Frames painted by CEF so far. */
    public int frameCount() {
        return frames;
    }

    /** Draws the current frame scaled into the given GUI rectangle. */
    public void draw(GuiGraphics graphics, int x, int y, int width, int height) {
        if (disposed || frame == null || pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        if (frames == 0) {
            // CEF has not painted yet: never draw, the texture has nothing meaningful in it.
            return;
        }
        ensureTexture();
        upload();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(location, x, y, width, height, 0.0F, 0.0F, pixelWidth, pixelHeight, pixelWidth, pixelHeight);
        graphics.flush();
        RenderSystem.defaultBlendFunc();
    }

    private void upload() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        synchronized (frameLock) {
            if (frame == null || disposed) {
                return;
            }
            int needed = pixelWidth * pixelHeight * BYTES_PER_PIXEL;
            // The driver reads width * height * 4 bytes regardless of the buffer limit, so a short
            // buffer here would be an out of bounds read inside the driver.
            if (frame.capacity() < needed || frame.limit() < needed) {
                return;
            }
            RenderSystem.bindTexture(textureId);
            RenderSystem.pixelStore(GL11.GL_UNPACK_ALIGNMENT, BYTES_PER_PIXEL);
            RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, pixelWidth);
            frame.position(0);
            if (!uploaded) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, pixelWidth, pixelHeight, 0,
                        GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, frame);
                uploaded = true;
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, pixelWidth, pixelHeight,
                        GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, frame);
            }
            RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            RenderSystem.bindTexture(0);
        }
    }

    /**
     * Reports the device scale factor to CEF. This is what makes Chromium paint the page at physical
     * resolution instead of one pixel per GUI pixel (see {@link #setGuiSize}).
     */
    @Override
    public boolean getScreenInfo(CefBrowser browser, org.cef.handler.CefScreenInfo screenInfo) {
        Rectangle rect = browser_rect_.getBounds();
        screenInfo.Set(deviceScale, 32, 8, false, rect, rect);
        return true;
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] rects, ByteBuffer buffer, int width, int height) {
        if (disposed || buffer == null || width <= 0 || height <= 0) {
            return;
        }
        if (++frames == 1) {
            LOGGER.info("War Project CEF first frame for {} ({}x{})", name, width, height);
        }
        synchronized (frameLock) {
            if (disposed) {
                return;
            }
            int bytes = width * height * BYTES_PER_PIXEL;
            if (width != pixelWidth || height != pixelHeight) {
                pixelWidth = width;
                pixelHeight = height;
                if (frame == null || frame.capacity() < bytes) {
                    if (frame != null) {
                        org.lwjgl.system.MemoryUtil.memFree(frame);
                    }
                    frame = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
                }
                uploaded = false;
            }
            if (frame == null || frame.capacity() < bytes) {
                return;
            }
            // A frame that has not been uploaded yet is dropped rather than overwritten half way.
            if (!dirty.compareAndSet(false, true)) {
                return;
            }
            ByteBuffer source = buffer.duplicate();
            source.position(0);
            int available = Math.min(source.capacity(), bytes);
            source.limit(available);
            frame.clear();
            frame.put(source);
            frame.flip();
        }
    }

    // ------------------------------------------------------------------ input

    private int toPixelX(double guiX) {
        return (int) Math.round(guiX);
    }

    private int toPixelY(double guiY) {
        return (int) Math.round(guiY);
    }

    public void pointerMoved(double guiX, double guiY, int modifiers) {
        mouseMove(toPixelX(guiX), toPixelY(guiY), modifiers);
    }

    public void pointerPressed(double guiX, double guiY, int button, int modifiers) {
        mouseDown(toPixelX(guiX), toPixelY(guiY), button, modifiers);
    }

    public void pointerReleased(double guiX, double guiY, int button, int modifiers) {
        mouseUp(toPixelX(guiX), toPixelY(guiY), button, modifiers);
    }

    public void wheel(double guiX, double guiY, double delta, int modifiers) {
        mouseWheel(toPixelX(guiX), toPixelY(guiY), modifiers, delta);
    }

    public void focus(boolean focused) {
        try {
            setFocus(focused);
        } catch (Throwable throwable) {
            LOGGER.debug("CEF focus change failed", throwable);
        }
    }

    /** Runs a snippet in the page; failures are logged once per call site and never bubble up. */
    public void run(String javascript) {
        if (disposed) {
            return;
        }
        try {
            executeJavaScript(javascript, location.toString(), 0);
        } catch (Throwable throwable) {
            LOGGER.warn("War Project CEF script failed", throwable);
        }
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            close(true);
        } catch (Throwable throwable) {
            LOGGER.debug("CEF browser close failed", throwable);
        }
        if (textureId != 0) {
            int doomed = textureId;
            textureId = 0;
            // GL calls must happen on the render thread; dispose can be triggered by chat commands.
            RenderSystem.recordRenderCall(() -> GL11.glDeleteTextures(doomed));
        }
        synchronized (frameLock) {
            if (frame != null) {
                org.lwjgl.system.MemoryUtil.memFree(frame);
                frame = null;
            }
        }
    }

    /** Percent encoding for {@code data:} URLs: space and everything outside the safe set. */
    private static final class PercentCodec {
        // '#' and '?' are excluded on purpose: inside a data: URL they start the fragment/query and
        // would truncate the page (CSS id selectors contain '#').
        private static final String SAFE = "-_.~!*'();:@&=+$,/[]";

        static String encode(String text) {
            StringBuilder builder = new StringBuilder(text.length() + 32);
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte value : bytes) {
                int unsigned = value & 0xFF;
                char character = (char) unsigned;
                if (unsigned < 0x80 && (Character.isLetterOrDigit(character) || SAFE.indexOf(character) >= 0)) {
                    builder.append(character);
                } else {
                    builder.append('%');
                    builder.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
                    builder.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
                }
            }
            return builder.toString();
        }
    }
}
