package com.flowingsun.war_project.client.cef;

import com.mojang.logging.LogUtils;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Brings the Chromium Embedded Framework up and keeps its message pump running.
 *
 * <p>CEF is a process singleton: it is started once per game session, driven from the client thread
 * with {@code N_DoMessageLoopWork} (the same thing MCEF injects into the render loop, done here with
 * an ordinary tick instead of a mixin) and disposed on shutdown. Every entry point swallows failures
 * into {@link #failure()}: the caller then falls back to the built in renderer.
 */
public final class CefBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * CEF composites off screen with its own GPU process by default, which fights Minecraft (and its
     * shader mods) for the graphics device and is the only new GPU consumer this backend introduces.
     * Our pages are tiny, so software compositing is plenty and removes that whole class of conflict.
     * Verified: frames still arrive with these switches (see docs/CHROMIUM_BACKEND.md).
     */
    private static final String[] COMMAND_LINE = {
            "--autoplay-policy=no-user-gesture-required",
            "--disable-gpu",
            "--disable-gpu-compositing",
            "--disable-gpu-vsync"
    };

    private static boolean attempted;
    private static boolean started;
    private static String failure = "";
    private static String version = "";
    private static CefApp app;
    private static CefClient client;

    private CefBootstrap() {
    }

    public static boolean isStarted() {
        return started;
    }

    public static String failure() {
        return failure;
    }

    public static String version() {
        return version;
    }

    public static CefClient client() {
        return client;
    }

    /** Starts CEF if it has not been attempted yet. Returns true when it is usable. */
    public static synchronized boolean start(Path nativesDirectory, Path cacheDirectory) {
        if (attempted) {
            return started;
        }
        attempted = true;
        try {
            java.nio.file.Files.createDirectories(cacheDirectory);
            System.setProperty("jcef.path", nativesDirectory.toAbsolutePath().toString());
            if (!CefApp.startup(COMMAND_LINE)) {
                throw new IllegalStateException("CefApp.startup returned false");
            }
            CefSettings settings = new CefSettings();
            settings.windowless_rendering_enabled = true;
            settings.background_color = settings.new ColorType(0, 0, 0, 0);
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            settings.cache_path = cacheDirectory.toAbsolutePath().toString();
            settings.user_agent_product = "WarProject/1.0";
            app = CefApp.getInstance(new String[]{}, settings);
            // Chromium finishes initialising on its own schedule; give it a short window while pumping
            // its loop, then continue anyway (a browser can be created while the state is still NEW).
            long deadline = System.currentTimeMillis() + 1500L;
            while (CefApp.getState() != CefApp.CefAppState.INITIALIZED && System.currentTimeMillis() < deadline) {
                app.N_DoMessageLoopWork();
                Thread.sleep(5L);
            }
            client = app.createClient();
            // Load diagnostics: without these a page that fails to load is completely silent.
            client.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
                    if (frame == null || frame.isMain()) {
                        LOGGER.info("War Project CEF loaded {} (http {})", shorten(browser.getURL()), httpStatusCode);
                    }
                }

                @Override
                public void onLoadError(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame,
                                        org.cef.handler.CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
                    LOGGER.warn("War Project CEF load error {} on {}: {}", errorCode, shorten(failedUrl), errorText);
                }
            });
            CefApp.CefVersion info = app.getVersion();
            version = "Chromium " + info.CHROME_VERSION_MAJOR + "." + info.CHROME_VERSION_MINOR + "."
                    + info.CHROME_VERSION_BUILD + "." + info.CHROME_VERSION_PATCH
                    + " (CEF " + info.CEF_VERSION_MAJOR + "." + info.CEF_VERSION_MINOR + ", jcef " + info.getJcefVersion() + ")";
            started = true;
            LOGGER.info("War Project CEF ready: {}", version);
            return true;
        } catch (Throwable throwable) {
            failure = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            started = false;
            LOGGER.warn("War Project CEF could not start ({})", failure, throwable);
            return false;
        }
    }

    private static String shorten(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 56 ? url.substring(0, 56) + "..." : url;
    }

    /** One turn of the CEF message loop; safe to call every tick and every frame. */
    public static void pump() {
        if (!started) {
            return;
        }
        try {
            app.N_DoMessageLoopWork();
        } catch (Throwable throwable) {
            started = false;
            failure = "message loop: " + throwable;
            LOGGER.warn("War Project CEF message loop failed", throwable);
        }
    }

    /**
     * Stops driving CEF. The process itself is left to exit with the game: disposing the native app
     * while Minecraft tears down its own subsystems has crashed before, and there is nothing to leak
     * that outlives the process.
     */
    public static synchronized void shutdown() {
        if (!started) {
            return;
        }
        started = false;
        LOGGER.info("War Project CEF message loop stopped");
    }
}
