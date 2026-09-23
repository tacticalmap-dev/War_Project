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
import org.lwjgl.system.MemoryUtil;
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
 *
 * <h2>Incremental frames</h2>
 *
 * <p>Chromium reports which parts of the frame actually changed, and this class copies and uploads
 * only those parts. A caret blink or one changed digit touches a few hundred pixels out of the several
 * hundred thousand a 4x GUI scale surface holds, so the previous whole-frame copy plus full texture
 * upload for every paint was the dominant cost of this backend. Two collections keep that correct:
 * {@link #copyNow} is what the current paint call must copy into the buffer, and {@link #toUpload} is
 * everything the buffer holds that the texture has not seen yet (damage that arrives while the render
 * thread is busy is carried over rather than dropped, so the texture can never keep stale pixels).
 * Both are guarded by {@link #frameLock}, which is also what makes the CEF paint thread and the render
 * thread safe against each other.
 */
public final class CefOsrView extends WpCefBrowser {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BYTES_PER_PIXEL = 4;

    private final String name;
    private final ResourceLocation location;
    private final AtomicBoolean dirty = new AtomicBoolean();
    /** Guards {@link #frame} and both region collections: CEF paints while the render thread uploads. */
    private final Object frameLock = new Object();
    /** Regions the current {@code onPaint} call has to copy into the buffer. */
    private final CefPaintRegions copyNow = new CefPaintRegions();
    /** Regions the buffer holds that have not reached the texture yet. */
    private final CefPaintRegions toUpload = new CefPaintRegions();

    private ByteBuffer frame;
    /** Scratch buffer a single dirty region is packed into before it is handed to the driver. */
    private ByteBuffer region;
    private boolean warnedAboutRegion;
    /**
     * Frame counter above which drawing is allowed again, plus the deadline for that gate. Set when the
     * panel opens: the texture still holds the picture from the previous time it was on screen, and
     * blitting that would flash the old state until Chromium repaints.
     */
    private int drawFromFrame;
    private long drawFromDeadlineNanos;
    /** While now is before this, every upload covers the whole frame instead of the damaged regions. */
    private long forceFullUntilNanos;
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

    // Diagnostics: how much pixel traffic the incremental path actually saved.
    private long copyCalls;
    private long copiedPixels;
    private long uploads;
    private long uploadedPixels;
    private long lastUploadNanos;
    private long totalUploadNanos;

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
                        MemoryUtil.memFree(frame);
                    }
                    frame = MemoryUtil.memAlloc(bytes);
                }
                frame.clear();
                uploaded = false;
                dirty.set(false);
                copyNow.clear();
                toUpload.clear();
                toUpload.setFrameSize(pixelWidth, pixelHeight);
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
            // No placeholder upload: a texture with no storage yet reads back as opaque black on real
            // drivers, which is why the surface used to be cleared to transparent first. That clear ran
            // after CEF's first frame had already been copied into the buffer and wiped it, leaving the
            // texture blank until Chromium happened to paint again. It is not needed either: the first
            // upload below always goes through glTexImage2D with a complete frame, and nothing is blitted
            // before that (draw() refuses to touch a surface CEF has never painted).
        }
        // A resource pack reload closes every texture and clears the manager, so the adapter is
        // re-registered whenever it is missing.
        if (!registered || Minecraft.getInstance().getTextureManager().getTexture(location, null) == null) {
            Minecraft.getInstance().getTextureManager().register(location, new CefTexture(this));
            registered = true;
        }
    }

    /** Frames painted by CEF so far. */
    public int frameCount() {
        return frames;
    }

    /**
     * Refuses to draw what the texture currently holds until Chromium paints a frame newer than it. The
     * timeout is a safety net: a page that never repaints must not leave the surface permanently blank.
     */
    public void skipUntilNextFrame(long timeoutNanos) {
        synchronized (frameLock) {
            drawFromFrame = frames + 1;
            drawFromDeadlineNanos = System.nanoTime() + Math.max(0L, timeoutNanos);
            // Nothing was uploaded while the panel was closed, so the texture no longer matches the frame
            // Chromium damaged against. Dropping the "uploaded" flag forces the next upload to be a whole
            // frame, which makes the two agree again; incremental uploads after that cannot leave pieces
            // of the old picture behind. It also keeps draw() from blitting anything until that happens.
            uploaded = false;
        }
    }

    /**
     * Uploads whole frames for the given duration. Used while the panel animates: during a layout or
     * opacity transition almost every pixel changes, and a Chromium damage list that under-reports would
     * leave stale pixels on screen, which reads as flicker.
     */
    public void forceFullFrames(long durationNanos) {
        forceFullUntilNanos = System.nanoTime() + Math.max(0L, durationNanos);
    }

    /** Texture uploads performed so far (at most one per drawn frame). */
    public long uploadCount() {
        return uploads;
    }

    /** Pixels that reached the texture, summed over every upload. */
    public long uploadedPixels() {
        return uploadedPixels;
    }

    /** Pixels copied out of CEF's frame buffer, summed over every paint. */
    public long copiedPixels() {
        return copiedPixels;
    }

    /** Copy operations performed, summed over every paint that had damage. */
    public long copyCount() {
        return copyCalls;
    }

    /** Nanoseconds spent inside the most recent texture upload. */
    public long lastUploadNanos() {
        return lastUploadNanos;
    }

    /** Nanoseconds spent inside texture uploads, summed. */
    public long uploadNanos() {
        return totalUploadNanos;
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
        if (frames < drawFromFrame && System.nanoTime() < drawFromDeadlineNanos) {
            // Waiting for a frame newer than the one the texture holds (see skipUntilNextFrame).
            return;
        }
        ensureTexture();
        upload();
        if (!uploaded) {
            // Nothing reached the texture (the buffer was not in a state that could be uploaded): drawing
            // now would show whatever an uninitialised texture reads back as.
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(location, x, y, width, height, 0.0F, 0.0F, pixelWidth, pixelHeight, pixelWidth, pixelHeight);
        graphics.flush();
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Pushes everything the buffer changed since the last upload into the texture. Only the tracked
     * regions are uploaded; a first frame or a size change falls back to the whole surface.
     *
     * <p>Each region is packed into {@link #region} row by row and uploaded from there with
     * {@code GL_UNPACK_ROW_LENGTH} left at 0. Uploading a region straight out of the full frame with a
     * non-zero row length saved one copy, but it hands the driver a buffer whose readable size does not
     * match what it reads: with a row length of {@code frameWidth} the driver walks {@code regionHeight}
     * full rows, so a region touching the right or bottom edge makes it read past the end of the buffer.
     * That is what faulted inside nvoglv64.dll on 2026-09-23 (crash dump hs_err_pid21068.log: render
     * thread, {@code CefOsrView.upload} -> {@code glTexSubImage2D}, EXCEPTION_ACCESS_VIOLATION). The
     * packed copy costs a few memcpy calls per region and makes the readable size exactly the size this
     * method verified.
     */
    private void upload() {
        if (!dirty.get()) {
            return;
        }
        long startedAt = System.nanoTime();
        synchronized (frameLock) {
            if (frame == null || disposed || textureId == 0) {
                return;
            }
            int needed = pixelWidth * pixelHeight * BYTES_PER_PIXEL;
            // The driver reads width * height * 4 bytes regardless of the buffer limit, so a short
            // buffer here would be an out of bounds read inside the driver.
            if (frame.capacity() < needed || frame.limit() < needed) {
                return;
            }
            // Anything the driver should not see (a region outside the frame, a full-frame damage set)
            // goes down the whole-surface path, which is always in bounds.
            boolean full = !uploaded || toUpload.isFull() || !regionsFitFrame()
                    || System.nanoTime() < forceFullUntilNanos;
            RenderSystem.bindTexture(textureId);
            RenderSystem.pixelStore(GL11.GL_UNPACK_ALIGNMENT, BYTES_PER_PIXEL);
            // Row pitch comes from the width argument of each call, never from a row length.
            RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            boolean pushed = false;
            try {
                if (full) {
                    frame.position(0);
                    if (!uploaded) {
                        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, pixelWidth, pixelHeight, 0,
                                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, frame);
                        uploaded = true;
                    } else {
                        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, pixelWidth, pixelHeight,
                                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, frame);
                    }
                    uploadedPixels += (long) pixelWidth * pixelHeight;
                } else {
                    uploadRegions();
                }
                pushed = true;
            } finally {
                frame.position(0);
                if (pushed) {
                    toUpload.clear();
                    dirty.set(false);
                }
                // On failure the tracked regions and the dirty flag are kept, so the next draw retries
                // with the same damage instead of leaving the texture permanently incomplete.
            }
            uploads++;
            lastUploadNanos = System.nanoTime() - startedAt;
            totalUploadNanos += lastUploadNanos;
            RenderSystem.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            RenderSystem.bindTexture(0);
        }
    }

    /**
     * True when every tracked region lies inside the current frame. A region that does not is uploaded
     * as part of a full frame instead: the driver must never be asked for pixels outside the buffer.
     */
    private boolean regionsFitFrame() {
        for (int i = 0; i < toUpload.size(); i++) {
            int x = toUpload.x(i);
            int y = toUpload.y(i);
            int w = toUpload.regionWidth(i);
            int h = toUpload.regionHeight(i);
            if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > pixelWidth || y + h > pixelHeight) {
                if (!warnedAboutRegion) {
                    warnedAboutRegion = true;
                    LOGGER.warn("War Project CEF upload: region {}x{} at ({},{}) does not fit the {}x{} frame,"
                            + " uploading the whole surface instead", w, h, x, y, pixelWidth, pixelHeight);
                }
                return false;
            }
        }
        return true;
    }

    /** Uploads each tracked region from a tightly packed copy of it, one GL call per region. */
    private void uploadRegions() {
        int stride = pixelWidth * BYTES_PER_PIXEL;
        long sourceAddress = MemoryUtil.memAddress(frame);
        for (int i = 0; i < toUpload.size(); i++) {
            int x = toUpload.x(i);
            int y = toUpload.y(i);
            int w = toUpload.regionWidth(i);
            int h = toUpload.regionHeight(i);
            int bytes = w * h * BYTES_PER_PIXEL;
            if (region == null || region.capacity() < bytes) {
                if (region != null) {
                    MemoryUtil.memFree(region);
                }
                region = MemoryUtil.memAlloc(bytes);
            }
            long rowBytes = (long) w * BYTES_PER_PIXEL;
            long targetAddress = MemoryUtil.memAddress(region);
            for (int row = 0; row < h; row++) {
                long offset = (long) (y + row) * stride + (long) x * BYTES_PER_PIXEL;
                MemoryUtil.memCopy(sourceAddress + offset, targetAddress + (long) row * rowBytes, rowBytes);
            }
            region.clear();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, region);
            uploadedPixels += (long) w * h;
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
                        MemoryUtil.memFree(frame);
                    }
                    frame = MemoryUtil.memAlloc(bytes);
                }
                uploaded = false;
                toUpload.clear();
            }
            if (frame == null || frame.capacity() < bytes) {
                return;
            }
            toUpload.setFrameSize(pixelWidth, pixelHeight);
            copyNow.setFrameSize(pixelWidth, pixelHeight);
            // A frame that arrives before the texture holds a complete picture has to fill the whole
            // buffer; after that only the damaged regions are touched.
            if (!uploaded) {
                copyNow.markFull();
            } else {
                copyNow.clear();
                if (rects != null) {
                    for (Rectangle rect : rects) {
                        if (rect != null) {
                            copyNow.add(rect.x, rect.y, rect.width, rect.height);
                        }
                    }
                }
                if (copyNow.isEmpty()) {
                    // No usable damage information: the texture is already complete, so there is
                    // nothing to do.
                    return;
                }
            }
            copyInto(copyNow, buffer, bytes);
            toUpload.mergeWith(copyNow);
            dirty.set(true);
        }
    }

    /** Copies the tracked regions out of CEF's frame buffer into the buffer the render thread owns. */
    private void copyInto(CefPaintRegions regions, ByteBuffer source, int frameBytes) {
        long sourceAddress = MemoryUtil.memAddress(source);
        long targetAddress = MemoryUtil.memAddress(frame);
        int available = Math.min(source.capacity(), frameBytes);
        int stride = pixelWidth * BYTES_PER_PIXEL;
        copyCalls++;
        if (regions.isFull()) {
            long length = Math.min(available, (long) pixelWidth * pixelHeight * BYTES_PER_PIXEL);
            MemoryUtil.memCopy(sourceAddress, targetAddress, length);
            copiedPixels += length / BYTES_PER_PIXEL;
            return;
        }
        for (int i = 0; i < regions.size(); i++) {
            int regionX = regions.x(i);
            int regionY = regions.y(i);
            int regionW = regions.regionWidth(i);
            int regionH = regions.regionHeight(i);
            long rowBytes = (long) regionW * BYTES_PER_PIXEL;
            for (int row = 0; row < regionH; row++) {
                long offset = (long) (regionY + row) * stride + (long) regionX * BYTES_PER_PIXEL;
                if (offset + rowBytes > available) {
                    break;
                }
                MemoryUtil.memCopy(sourceAddress + offset, targetAddress + offset, rowBytes);
                copiedPixels += regionW;
            }
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
                MemoryUtil.memFree(frame);
                frame = null;
            }
            if (region != null) {
                MemoryUtil.memFree(region);
                region = null;
            }
            copyNow.clear();
            toUpload.clear();
            dirty.set(false);
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
