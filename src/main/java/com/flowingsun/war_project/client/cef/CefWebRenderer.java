package com.flowingsun.war_project.client.cef;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.client.ResourceClientState;
import com.flowingsun.war_project.client.ResourceIslandView;
import com.flowingsun.war_project.client.VpWarClientState;
import com.flowingsun.war_project.client.web.CefAvailability;
import com.flowingsun.war_project.client.web.WebJson;
import com.flowingsun.war_project.client.web.WebPages;
import com.flowingsun.war_project.client.web.WebRenderer;
import com.flowingsun.war_project.client.web.WebRendererService;
import com.flowingsun.war_project.client.web.WebSnapshot;
import com.flowingsun.war_project.client.web.WebSnapshots;
import com.flowingsun.war_project.net.WarProjectNetwork;
import com.flowingsun.war_project.resource.ResourceKind;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Chromium backend for the resource island and the transfer panel.
 *
 * <p>Both live in a single off screen page: the island is the collapsed state of one black slab, and
 * opening the transfer panel grows that slab downwards (CSS transition), so the panel always sits
 * directly under the island at a fixed place on screen instead of following the mouse.
 *
 * <p>Preparation is staged so the interface never goes blank: binaries are fetched on a background
 * thread, Chromium starts and the surface is created on the client thread, and only then does
 * {@link #isReady()} turn true. Any failure leaves the built in renderer in charge.
 */
public final class CefWebRenderer implements WebRenderer, CefAvailability {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Size of the Chromium surface, in GUI pixels (mirrors the page's CSS). The surface is the transfer
     * panel alone, so its bounds are the panel's bounds and every pixel of the texture is content.
     */
    private static final int SURFACE_WIDTH = 136;
    private static final int SURFACE_HEIGHT = 110;
    /**
     * The panel is the island grown downwards, so it takes the island's own reported width; these are
     * the bounds the roster and the amount field still fit into.
     */
    private static final int PANEL_MIN_WIDTH = 132;
    /**
     * The fused top HUD (VP war bar + resource island) is drawn by the built in HTML kernel
     * (client/ResourceIslandView), so the Chromium surface only carries the transfer panel and is
     * pushed below that HUD.
     */
    private static final int TOPHUD_HEIGHT = 30;
    /** One pixel of breathing room between the island's lower edge and the panel's top edge. */
    private static final int TOPHUD_GAP = 1;
    /**
     * Collapsed height of the island bar inside that surface. Its width follows the content and is
     * reported by the page through the {@code size} message.
     */
    private static final int BAR_HEIGHT = 18;
    private static final int BAR_WIDTH_FALLBACK = 120;
    private static final int TOP_MARGIN = 2;
    /**
     * Message loop interval while the surface is hidden: often enough to keep Chromium responsive and to
     * pick up its own messages, slow enough that an idle game pays nothing measurable for the browser.
     */
    private static final long IDLE_PUMP_INTERVAL_NANOS = 500_000_000L;
    /**
     * How long Chromium keeps painting after the panel closes. The collapse is a 220 ms CSS transition
     * and nothing is drawn meanwhile, so without this the page would freeze mid-collapse and its texture
     * would still hold that half grown picture the next time the panel opens.
     */
    private static final long SETTLE_NANOS = 400_000_000L;
    /** How long the first frame after opening may be waited for before the stale texture is shown. */
    private static final long FIRST_FRAME_TIMEOUT_NANOS = 1_500_000_000L;
    /**
     * How long whole frames are uploaded after opening. The panel grows and fades for about 400 ms; while
     * that happens nearly every pixel changes, so trusting Chromium's damage list there is what would show
     * as flicker if it under-reports.
     */
    private static final long FULL_FRAME_ANIMATION_NANOS = 600_000_000L;

    private final Path gameDirectory;
    private final String mirror;
    private final boolean diagnostics;
    private final String unsupported;
    /**
     * Upper bound on how often CEF's message loop is pumped while the surface is on screen. Chromium
     * only produces a frame while its loop runs, so this is the surface's frame rate: the pages are
     * static apart from short CSS transitions and a caret blink, and pumping once per rendered game
     * frame (60 to 240 times a second) buys nothing but software compositing work.
     */
    private final int maxFrameRate;
    /** When true Chromium is only started once the interface actually has to be shown. */
    private final boolean lazyStart;

    private final AtomicReference<String> stage = new AtomicReference<>("");
    private final Queue<String> inbound = new ConcurrentLinkedQueue<>();

    private volatile float progress;
    private volatile boolean nativesReady;
    private volatile boolean startAttempted;
    private volatile boolean ready;
    private volatile String failure = "";
    /** WebGL renderer string reported by the page: the hard evidence of GPU vs SwiftShader. */
    private volatile String glBackend = "unknown";

    private CefOsrView surface;
    private CefMessageRouter router;
    private WebSnapshot lastPushed;
    private long lastStatsNanos;
    private long lastPumpNanos;
    private long startedAtNanos;
    private long lastDiagnosticMs = -4000L;
    private long lastForcedPushMs = -4000L;
    /** While now is before this, the page is still playing the collapse animation. */
    private long settleUntilNanos;
    private boolean pageReady;
    private boolean framesSeen;
    /** Last logged visibility, so one line explains on or off (and why) exactly when it changes. */
    private Boolean lastVisibleLogged;

    // Sampled for the diagnostics line so it can report "since the previous report" rates.
    private int statsFrames;
    private long statsUploads;
    private long statsUploadedPixels;
    private long statsCopiedPixels;
    private long statsPumpCalls;

    private boolean panelOpen;
    private volatile int barWidth = BAR_WIDTH_FALLBACK;
    private int surfaceX;
    private int surfaceY;
    private ResourceKind panelKind = ResourceKind.AMMO;

    public CefWebRenderer() {
        this.gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
        this.mirror = Config.cefMirror;
        this.diagnostics = Config.webDiagnostics;
        this.maxFrameRate = Config.cefMaxFrameRate > 0 ? Config.cefMaxFrameRate : 30;
        this.lazyStart = Config.cefLazyStart;
        this.unsupported = CefNatives.isSupported() ? null : "no Chromium Embedded Framework build for "
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        if (unsupported == null) {
            Thread worker = new Thread(this::prepare, "war-project-cef-download");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public boolean available() {
        return unsupported == null;
    }

    @Override
    public String unavailableReason() {
        return unsupported == null ? failure : unsupported;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String statusText() {
        if (unsupported != null || ready) {
            return "";
        }
        String current = stage.get();
        if (current.isEmpty()) {
            return "";
        }
        return current + " " + Math.round(progress * 100.0F) + "%";
    }

    /** Background: makes sure the binaries are in the game directory. */
    private void prepare() {
        try {
            CefNatives.ensure(gameDirectory, mirror, (text, fraction) -> {
                stage.set(text);
                progress = fraction;
            });
            nativesReady = true;
        } catch (Throwable throwable) {
            failure = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            LOGGER.warn("War Project could not prepare the Chromium Embedded Framework ({})", failure, throwable);
            stage.set("Chromium unavailable");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void tick() {
        if (ready) {
            boolean visible = surfaceVisible();
            if (lastVisibleLogged == null || lastVisibleLogged != visible) {
                lastVisibleLogged = visible;
                Minecraft minecraft = Minecraft.getInstance();
                LOGGER.info("War Project CEF surface {}: running={} hasTeam={} island={} war={} screen={}",
                        visible ? "shown" : "hidden", ResourceClientState.isRunning(), ResourceClientState.hasTeam(),
                        ResourceIslandView.shouldShow(), VpWarClientState.isRunning(),
                        minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName());
            }
            pumpIfDue(visible);
            drainMessages();
            if (visible || panelOpen) {
                // Nothing on screen consumes the snapshot, and a page that is not shown should not be
                // asked to re-layout either: the data is pushed again the moment it becomes visible.
                pushSnapshot();
            }
            serviceStartup(visible);
            logStats();
            // The transfer panel belongs to the chat screen: leaving it collapses the slab again.
            if (panelOpen && Minecraft.getInstance().screen == null) {
                closePanel();
            }
            return;
        }
        if (nativesReady && !startAttempted && (shouldStartNow())) {
            startAttempted = true;
            startOnClientThread();
        }
    }

    /**
     * Chromium is only started once the player is actually in a world, unless {@code cefLazyStart} is
     * off. A session that never leaves the main menu then runs with no browser process at all, while
     * joining a world pays the one-off startup while the game is still busy loading the terrain instead
     * of the first time the island would appear mid-game. The built in renderer draws until it is up.
     */
    private boolean shouldStartNow() {
        if (!lazyStart) {
            return true;
        }
        return Minecraft.getInstance().player != null;
    }

    /** True when the island or the panel has a reason to exist for this player right now. */
    private boolean wantsSurface() {
        return ResourceClientState.isRunning() && ResourceClientState.hasTeam();
    }

    /**
     * True when the surface is actually on screen. Every draw call site agrees with this: the island is
     * drawn with no screen open and on the chat screen, the panel is drawn on top of the chat screen.
     */
    private boolean surfaceVisible() {
        if (!ready || surface == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return false;
        }
        // The surface is the transfer panel now: it is on screen exactly while that panel is open.
        return panelOpen;
    }

    /**
     * Runs one turn of CEF's message loop when enough time has passed. A hidden surface is pumped a few
     * times a second so it still receives messages (and can be shown instantly), but it is never allowed
     * to composite frames nobody is looking at.
     */
    private void pumpIfDue(boolean visible) {
        long now = System.nanoTime();
        // While the panel is open, and for a moment after it closes, the page has an animation to play.
        // The rest of the time the loop is only kept warm enough to receive its own messages.
        boolean animating = visible || now < settleUntilNanos;
        long interval = animating
                ? Math.max(1_000_000L, 1_000_000_000L / Math.max(1, maxFrameRate))
                : IDLE_PUMP_INTERVAL_NANOS;
        if (lastPumpNanos != 0L && now - lastPumpNanos < interval) {
            return;
        }
        lastPumpNanos = now;
        CefBootstrap.pump();
    }

    /** Starts CEF and creates the surface; must run on the client thread. */
    private void startOnClientThread() {
        Path natives = CefNatives.platformDirectory(gameDirectory);
        LOGGER.info("War Project CEF startup: natives={} mirror={}", natives,
                mirror == null || mirror.isBlank() ? "default" : mirror);
        Path cache = CefNatives.root(gameDirectory).resolve("cache");
        CefBootstrap.reapOrphanedHelpers(cache);
        if (!CefBootstrap.start(natives, cache)) {
            failure = CefBootstrap.failure();
            WebRendererService.disable("Chromium could not start: " + failure);
            return;
        }
        try {
            router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig(), new Bridge());
            CefBootstrap.client().addMessageRouter(router);
            surface = new CefOsrView("overlay", WebPages.overlay());
            surface.setGuiSize(SURFACE_WIDTH, SURFACE_HEIGHT, guiScale());
            startedAtNanos = System.nanoTime();
            ready = true;
            LOGGER.info("War Project CEF surface online ({})", CefBootstrap.version());
        } catch (Throwable throwable) {
            failure = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            LOGGER.warn("War Project could not create the CEF surface", throwable);
            WebRendererService.disable("Chromium surface failed: " + failure);
        }
    }

    @Override
    public void shutdown() {
        if (router != null) {
            try {
                CefBootstrap.client().removeMessageRouter(router);
            } catch (Throwable throwable) {
                LOGGER.debug("CEF message router removal failed", throwable);
            }
            router = null;
        }
        if (surface != null) {
            surface.dispose();
            surface = null;
        }
        ready = false;
    }

    @Override
    public void onDisconnect() {
        closePanel();
        lastPushed = null;
    }

    // ------------------------------------------------------------------ data

    private void drainMessages() {
        String message;
        int guard = 0;
        while ((message = inbound.poll()) != null && guard++ < 64) {
            String op = WebJson.stringField(message, "op", "");
            switch (op) {
                case "ready" -> {
                    pageReady = true;
                    lastPushed = null;
                    LOGGER.info("War Project CEF page online: {}", WebJson.stringField(message, "ua", "unknown"));
                    probeBackend();
                }
                case "gpu" -> {
                    // The page has an OpenGL implementation either way; the renderer string is what says
                    // whether it is the graphics card (ANGLE d3d11 naming the adapter) or SwiftShader.
                    glBackend = WebJson.stringField(message, "backend", "unknown");
                    LOGGER.info("War Project CEF backend: {} (gpu requested={}, switches={})",
                            glBackend, Config.cefUseGpu, Config.cefUseGpu ? "gpu" : "software");
                }
                case "transfer" -> {
                    String target = WebJson.stringField(message, "target", "");
                    String kindId = WebJson.stringField(message, "kind", panelKind.id());
                    double amount = WebJson.numberField(message, "amount", 0.0D);
                    if (!target.isEmpty() && amount > 0.0D) {
                        WarProjectNetwork.sendTransfer(target, kindId, amount);
                    }
                }
                case "size" -> {
                    double reported = WebJson.numberField(message, "bar", barWidth);
                    if (reported > 20.0D && reported < SURFACE_WIDTH) {
                        barWidth = (int) Math.round(reported);
                    }
                }
                case "cancel", "close" -> closePanel();
                default -> {
                }
            }
        }
    }

    /**
     * Asks the page which OpenGL implementation Chromium gave it. ANGLE names the adapter when it runs
     * on the card ("ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 ...)"), and reports SwiftShader when
     * the GPU is disabled, so a single log line settles the GPU-versus-CPU question for the running
     * game instead of leaving it to the command line being read correctly.
     */
    private void probeBackend() {
        if (surface == null) {
            return;
        }
        surface.run("(function(){var s='unavailable';try{var c=document.createElement('canvas');"
                + "var g=c.getContext('webgl')||c.getContext('experimental-webgl');if(g){"
                + "var d=g.getExtension('WEBGL_debug_renderer_info');"
                + "s=d?g.getParameter(d.UNMASKED_RENDERER_WEBGL):g.getParameter(g.RENDERER);}}"
                + "catch(e){s='error:'+e;}if(typeof cefQuery==='function'){cefQuery({request:"
                + "JSON.stringify({op:'gpu',backend:s}),onSuccess:function(){},onFailure:function(){}});}})()");
    }

    private void pushSnapshot() {
        WebSnapshot snapshot = WebSnapshots.current();
        if (snapshot.equals(lastPushed)) {
            return;
        }
        lastPushed = snapshot;        if (surface != null) {
            surface.run("window.wp&&window.wp.apply(" + WebJson.write(snapshot) + ")");
        }
    }

    /**
     * Startup watchdog and diagnostics: reports what Chromium is doing for the first 30 seconds,
     * re-pushes the snapshot so a late loading page still receives it, and hands the island back to the
     * built in renderer if Chromium never paints.
     */
    private void serviceStartup(boolean visible) {
        if (startedAtNanos == 0L) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        if (surface != null && surface.frameCount() > 0) {
            framesSeen = true;
        }
        if (!framesSeen && elapsedMs > 10_000L) {
            WebRendererService.disable("Chromium painted no frames within 10s");
            return;
        }
        if (elapsedMs > 30_000L || (framesSeen && pageReady)) {
            return;
        }
        if (visible && elapsedMs - lastForcedPushMs >= 2000L) {
            lastForcedPushMs = elapsedMs;
            lastPushed = null;
        }
        if (elapsedMs - lastDiagnosticMs >= 2000L) {
            lastDiagnosticMs = elapsedMs;
            LOGGER.info("War Project CEF status after {}ms: frames={} loading={} document={} url={}",
                    elapsedMs,
                    surface == null ? -1 : surface.frameCount(), surface != null && surface.isLoading(),
                    surface != null && surface.hasDocument(), surface == null ? "" : shorten(surface.getURL()));
        }
    }

    private static String shorten(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 56 ? url.substring(0, 56) + "..." : url;
    }

    /**
     * Reports the cost of the surface once a minute: how many frames Chromium produced, how much pixel
     * data was copied and uploaded for them, and how often the message loop was pumped. The upload and
     * copy counters are what show the incremental painting at work: a caret blink or a changed digit
     * should appear as a few thousand pixels rather than the several hundred thousand a full frame holds.
     */
    private void logStats() {
        if (!diagnostics) {
            return;
        }
        long now = System.nanoTime();
        if (lastStatsNanos == 0L) {
            lastStatsNanos = now;
            return;
        }
        long elapsed = now - lastStatsNanos;
        if (elapsed < 60_000_000_000L) {
            return;
        }
        lastStatsNanos = now;
        double seconds = elapsed / 1_000_000_000.0D;
        CefOsrView view = surface;
        int frames = view == null ? 0 : view.frameCount();
        long uploads = view == null ? 0L : view.uploadCount();
        long uploaded = view == null ? 0L : view.uploadedPixels();
        long copied = view == null ? 0L : view.copiedPixels();
        long pumps = CefBootstrap.pumpCalls();
        long framesDelta = frames - statsFrames;
        long uploadsDelta = uploads - statsUploads;
        long uploadedDelta = uploaded - statsUploadedPixels;
        long copiedDelta = copied - statsCopiedPixels;
        long pumpsDelta = pumps - statsPumpCalls;
        LOGGER.info("War Project CEF diagnostics: frames=+{} ({}/s) uploads=+{} uploaded={} KiB copied={} KiB"
                        + " pumps=+{} ({}/s) pumpAvg={} us uploadAvg={} us visible={} open={} backend={}",
                framesDelta, perSecond(framesDelta, seconds),
                uploadsDelta, uploadedDelta / 1024L, copiedDelta / 1024L,
                pumpsDelta, perSecond(pumpsDelta, seconds),
                averageMicros(CefBootstrap.pumpNanos(), pumps),
                averageMicros(view == null ? 0L : view.uploadNanos(), uploads),
                surfaceVisible(), panelOpen, glBackend);
        statsFrames = frames;
        statsUploads = uploads;
        statsUploadedPixels = uploaded;
        statsCopiedPixels = copied;
        statsPumpCalls = pumps;
    }

    private static long perSecond(long count, double seconds) {
        return seconds <= 0.0D ? 0L : Math.round(count / seconds);
    }

    private static long averageMicros(long nanos, long count) {
        return count <= 0L ? 0L : nanos / 1000L / count;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The resource island is drawn by the built in HTML kernel (client/ResourceIslandView) together
     * with the VP war bar, so this surface no longer has an idle state at all: it exists only while the
     * transfer panel is open.
     */
    @Override
    public void renderIsland(GuiGraphics graphics, int screenWidth, int screenHeight) {
        // Nothing to draw: the surface only appears with the panel.
    }

    /** Same surface, expanded; drawn on top of the screen it belongs to. */
    @Override
    public void renderPanel(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (!panelOpen) {
            return;
        }
        pumpIfDue(true);
        drawSurface(graphics, screenWidth);
    }

    private void drawSurface(GuiGraphics graphics, int screenWidth) {
        if (!ready || surface == null || !panelOpen) {
            return;
        }
        surface.setGuiSize(SURFACE_WIDTH, SURFACE_HEIGHT, guiScale());
        updateSurfaceBounds(screenWidth);
        surface.draw(graphics, surfaceX, surfaceY, SURFACE_WIDTH, SURFACE_HEIGHT);
    }

    private void updateSurfaceBounds(int screenWidth) {
        surfaceX = Math.max(0, (screenWidth - SURFACE_WIDTH) / 2);
        // Flush under the built in top HUD: the island's lower edge ends at TOPHUD_HEIGHT, so the panel
        // reads as that island unfolding downwards instead of a window floating below the HUD.
        surfaceY = TOP_MARGIN + TOPHUD_HEIGHT + TOPHUD_GAP;
    }

    /** Left edge of the panel inside the surface: the panel is centred and may be narrower than it. */
    private int panelX() {
        return surfaceX + (SURFACE_WIDTH - panelWidth()) / 2;
    }

    /** Width the panel occupies: the island's reported width, clamped to what its contents need. */
    private int panelWidth() {
        return Math.max(PANEL_MIN_WIDTH, Math.min(SURFACE_WIDTH, barWidth));
    }

    private static int guiScale() {
        // Window#getGuiScale returns a double; the surface only cares about the whole multiple.
        return (int) Math.max(1.0D, Minecraft.getInstance().getWindow().getGuiScale());
    }

    // ------------------------------------------------------------------ hit testing and input

    @Override
    public boolean isPanelOpen() {
        return panelOpen;
    }

    /** The collapsed bar in the middle of the surface: the click target that expands it. */
    @Override
    public boolean islandContains(double mouseX, double mouseY) {
        if (!ready) {
            return false;
        }
        updateSurfaceBounds(Minecraft.getInstance().getWindow().getGuiScaledWidth());
        int barX = surfaceX + (SURFACE_WIDTH - barWidth) / 2;
        return mouseX >= barX && mouseX < barX + barWidth
                && mouseY >= surfaceY && mouseY < surfaceY + BAR_HEIGHT;
    }

    @Override
    public boolean panelContains(double mouseX, double mouseY) {
        if (!ready || !panelOpen) {
            return false;
        }
        updateSurfaceBounds(Minecraft.getInstance().getWindow().getGuiScaledWidth());
        int left = panelX();
        return mouseX >= left && mouseX < left + panelWidth()
                && mouseY >= surfaceY && mouseY < surfaceY + SURFACE_HEIGHT;
    }

    /** Expands the slab. The panel is fixed under the island, so the mouse position is irrelevant. */
    @Override
    public void openPanel(ResourceKind kind, int mouseX, int mouseY) {
        if (!ready || surface == null) {
            return;
        }
        panelKind = kind;
        panelOpen = true;
        // Nothing may be drawn until Chromium paints this session's first frame: the texture still holds
        // the previous one, which would flash on screen before the page starts animating.
        surface.skipUntilNextFrame(FIRST_FRAME_TIMEOUT_NANOS);
        surface.forceFullFrames(FULL_FRAME_ANIMATION_NANOS);
        lastPushed = null;
        // The snapshot is pushed here as well as on the next tick: the page needs the roster before the
        // panel finishes growing, otherwise it opens showing nothing.
        pushSnapshot();
        long held = Math.max(0L, (long) Math.floor(ResourceClientState.amount(kind)));
        surface.run("window.wp&&window.wp.openPanel({kind:" + WebJson.string(kind.id()) + ",available:" + held
                + ",cooldown:" + Config.transferCooldownSeconds + ",width:" + panelWidth() + "})");
        surface.focus(true);
    }

    @Override
    public void closePanel() {
        if (!panelOpen) {
            return;
        }
        panelOpen = false;
        // The page keeps animating for a moment so the collapse finishes while nothing is on screen.
        settleUntilNanos = System.nanoTime() + SETTLE_NANOS;
        if (surface != null) {
            surface.run("window.wp&&window.wp.closePanel()");
            surface.focus(false);
        }
    }

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (!ready || surface == null) {
            return false;
        }
        if (panelOpen && panelContains(mouseX, mouseY)) {
            surface.pointerPressed(mouseX - surfaceX, mouseY - surfaceY, button, 0);
            return true;
        }
        if (panelOpen) {
            closePanel();
            return true;
        }
        // The island (and therefore its click target) belongs to the built in kernel now.
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!ready || surface == null || !panelOpen) {
            return false;
        }
        if (panelContains(mouseX, mouseY)) {
            surface.pointerReleased(mouseX - surfaceX, mouseY - surfaceY, button, 0);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!ready || surface == null || !panelOpen || !panelContains(mouseX, mouseY)) {
            return false;
        }
        surface.wheel(mouseX - surfaceX, mouseY - surfaceY, delta, 0);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!ready || surface == null || !panelOpen) {
            return false;
        }
        if (keyCode == 256) {
            closePanel();
            return true;
        }
        int cefKey = CefKeyMap.toCefKey(keyCode);
        if (cefKey != 0) {
            surface.keyDown(cefKey, '\0', 0);
            surface.keyUp(cefKey, '\0', 0);
        }
        return true;
    }

    @Override
    public boolean charTyped(char character) {
        if (!ready || surface == null || !panelOpen) {
            return false;
        }
        surface.keyTyped(character, 0);
        return true;
    }

    @Override
    public void onTransferResult(boolean ok, String message) {
        if (surface == null) {
            return;
        }
        surface.run("window.wp&&window.wp.result({ok:" + ok + ",message:" + WebJson.string(message == null ? "" : message) + "})");
    }

    /** Receives {@code cefQuery} calls from the page; always answers so the page promise settles. */
    private final class Bridge extends CefMessageRouterHandlerAdapter {
        @Override
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                              boolean persistent, CefQueryCallback callback) {
            if (request != null && !request.isBlank()) {
                inbound.add(request);
            }
            if (callback != null) {
                callback.success("{}");
            }
            return true;
        }
    }
}
