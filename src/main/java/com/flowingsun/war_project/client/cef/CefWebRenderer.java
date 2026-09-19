package com.flowingsun.war_project.client.cef;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.client.ResourceClientState;
import com.flowingsun.war_project.client.ResourceIslandView;
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

    /** Size of the whole collapsed-to-expanded surface, in GUI pixels (mirrors the page's CSS). */
    private static final int SURFACE_WIDTH = 206;
    private static final int SURFACE_HEIGHT = 196;
    /**
     * Collapsed height of the island bar inside that surface. Its width follows the content and is
     * reported by the page through the {@code size} message.
     */
    private static final int BAR_HEIGHT = 18;
    private static final int BAR_WIDTH_FALLBACK = 120;
    private static final int TOP_MARGIN = 2;

    private final Path gameDirectory;
    private final String mirror;
    private final boolean diagnostics;
    private final String unsupported;

    private final AtomicReference<String> stage = new AtomicReference<>("");
    private final Queue<String> inbound = new ConcurrentLinkedQueue<>();

    private volatile float progress;
    private volatile boolean nativesReady;
    private volatile boolean startAttempted;
    private volatile boolean ready;
    private volatile String failure = "";

    private CefOsrView surface;
    private CefMessageRouter router;
    private WebSnapshot lastPushed;
    private long lastStatsNanos;
    private long startedAtNanos;
    private long lastDiagnosticMs = -4000L;
    private long lastForcedPushMs = -4000L;
    private boolean pageReady;
    private boolean framesSeen;

    private boolean panelOpen;
    private volatile int barWidth = BAR_WIDTH_FALLBACK;
    private int surfaceX;
    private int surfaceY;
    private ResourceKind panelKind = ResourceKind.AMMO;

    public CefWebRenderer() {
        this.gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
        this.mirror = Config.cefMirror;
        this.diagnostics = Config.webDiagnostics;
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
            CefBootstrap.pump();
            drainMessages();
            pushSnapshot();
            serviceStartup();
            logStats();
            // The transfer panel belongs to the chat screen: leaving it collapses the slab again.
            if (panelOpen && Minecraft.getInstance().screen == null) {
                closePanel();
            }
            return;
        }
        if (nativesReady && !startAttempted) {
            startAttempted = true;
            startOnClientThread();
        }
    }

    /** Starts CEF and creates the surface; must run on the client thread. */
    private void startOnClientThread() {
        Path natives = CefNatives.platformDirectory(gameDirectory);
        LOGGER.info("War Project CEF startup: natives={} mirror={}", natives,
                mirror == null || mirror.isBlank() ? "default" : mirror);
        if (!CefBootstrap.start(natives, CefNatives.root(gameDirectory).resolve("cache"))) {
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

    private void pushSnapshot() {
        WebSnapshot snapshot = WebSnapshots.current();
        if (snapshot.equals(lastPushed)) {
            return;
        }
        lastPushed = snapshot;
        if (surface != null) {
            surface.run("window.wp&&window.wp.apply(" + WebJson.write(snapshot) + ")");
        }
    }

    /**
     * Startup watchdog and diagnostics: reports what Chromium is doing for the first 30 seconds,
     * re-pushes the snapshot so a late loading page still receives it, and hands the island back to the
     * built in renderer if Chromium never paints.
     */
    private void serviceStartup() {
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
        if (elapsedMs - lastForcedPushMs >= 2000L) {
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

    private void logStats() {
        if (!diagnostics) {
            return;
        }
        long now = System.nanoTime();
        if (lastStatsNanos == 0L) {
            lastStatsNanos = now;
            return;
        }
        if (now - lastStatsNanos < 60_000_000_000L) {
            return;
        }
        lastStatsNanos = now;
        LOGGER.info("War Project CEF diagnostics: surface={}x{} open={}", SURFACE_WIDTH, SURFACE_HEIGHT, panelOpen);
    }

    // ------------------------------------------------------------------ drawing

    /** The island while no screen is open; the panel takes over drawing once it is expanded. */
    @Override
    public void renderIsland(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (panelOpen) {
            return;
        }
        drawSurface(graphics, screenWidth);
    }

    /** Same surface, expanded; drawn on top of the screen it belongs to. */
    @Override
    public void renderPanel(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (!panelOpen) {
            return;
        }
        CefBootstrap.pump();
        drawSurface(graphics, screenWidth);
    }

    private void drawSurface(GuiGraphics graphics, int screenWidth) {
        if (!ready || surface == null || !ResourceIslandView.shouldShow()) {
            return;
        }
        surface.setGuiSize(SURFACE_WIDTH, SURFACE_HEIGHT, guiScale());
        updateSurfaceBounds(screenWidth);
        surface.draw(graphics, surfaceX, surfaceY, SURFACE_WIDTH, SURFACE_HEIGHT);
    }

    private void updateSurfaceBounds(int screenWidth) {
        surfaceX = Math.max(0, (screenWidth - SURFACE_WIDTH) / 2);
        surfaceY = TOP_MARGIN;
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
        return mouseX >= surfaceX && mouseX < surfaceX + SURFACE_WIDTH
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
        lastPushed = null;
        long held = Math.max(0L, (long) Math.floor(ResourceClientState.amount(kind)));
        surface.run("window.wp&&window.wp.openPanel({kind:" + WebJson.string(kind.id()) + ",available:" + held
                + ",cooldown:" + Config.transferCooldownSeconds + "})");
        surface.focus(true);
    }

    @Override
    public void closePanel() {
        if (!panelOpen) {
            return;
        }
        panelOpen = false;
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
        if (islandContains(mouseX, mouseY)) {
            if (button == 0) {
                // Left click on an icon expands the slab into the transfer panel.
                ResourceKind kind = mouseX < surfaceX + SURFACE_WIDTH / 2.0D ? ResourceKind.AMMO : ResourceKind.FUEL;
                openPanel(kind, (int) mouseX, (int) mouseY);
                return true;
            }
            surface.pointerPressed(mouseX - surfaceX, mouseY - surfaceY, button, 0);
            return true;
        }
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
