package com.flowingsun.war_project.client.cef;

import com.flowingsun.war_project.Config;
import com.mojang.logging.LogUtils;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * Switches every session gets. A browser embedded in a game must not behave like a desktop browser
     * in the background, so the network services, the extension and component updaters, the crash
     * reporter and the spare renderer are all off, and V8's heap is capped for pages that hold a few
     * numbers. See docs/CHROMIUM_BACKEND.md for how to confirm the surface still renders after a change.
     */
    private static final String[] BASE_SWITCHES = {
            "--autoplay-policy=no-user-gesture-required",
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-breakpad",
            "--disable-client-side-phishing-detection",
            "--disable-domain-reliability",
            "--disable-default-apps",
            "--disable-extensions",
            "--disable-sync",
            "--no-first-run",
            "--no-default-browser-check",
            // One renderer is all a single static overlay needs; the default keeps a spare alive.
            "--renderer-process-limit=1",
            // Features that cost RAM or timer wake-ups without ever being used here.
            "--disable-features=Translate,MediaRouter,OptimizationHints,BackForwardCache,CalculateNativeWinOcclusion",
            // The pages hold a handful of numbers and a short roster, so a small heap is plenty and an
            // unbounded growth path can never turn into a resident cost.
            "--js-flags=--max-old-space-size=64"
    };

    /**
     * Switches that keep Chromium off the graphics card: rasterisation and compositing run on the CPU
     * (Skia plus ANGLE's SwiftShader for WebGL).
     *
     * <p>Off screen rendering always ends with a CPU bitmap that has to be handed to the game, so GPU
     * mode also pays for a GPU-to-CPU read back and for a GPU process that competes with Minecraft's own
     * OpenGL traffic. Measured with a stand-alone probe on this machine (Intel UHD 630, 704x600 surface,
     * 30 frames a second, 8 second window): hardware GPU ~14% of one core, SwiftShader ~9%. That is why
     * software is the default and {@code cefUseGpu} exists to flip it on hardware where the numbers
     * differ.
     */
    private static final String[] SOFTWARE_SWITCHES = {
            "--disable-gpu",
            "--disable-gpu-compositing",
            "--disable-gpu-vsync"
    };

    /** The command line for this session; the result is what {@code CefApp.getInstance} must receive. */
    static String[] switches() {
        if (Config.cefUseGpu) {
            return BASE_SWITCHES.clone();
        }
        List<String> all = new ArrayList<>(SOFTWARE_SWITCHES.length + BASE_SWITCHES.length);
        all.addAll(Arrays.asList(SOFTWARE_SWITCHES));
        all.addAll(Arrays.asList(BASE_SWITCHES));
        return all.toArray(new String[0]);
    }

    private static boolean attempted;
    private static boolean started;
    private static boolean shutdownHookRegistered;
    private static String failure = "";
    private static String version = "";
    private static CefApp app;
    private static CefClient client;
    private static long pumpCalls;
    private static long pumpNanos;

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
            String[] commandLine = switches();
            if (!CefApp.startup(commandLine)) {
                throw new IllegalStateException("CefApp.startup returned false");
            }
            CefSettings settings = new CefSettings();
            settings.windowless_rendering_enabled = true;
            settings.background_color = settings.new ColorType(0, 0, 0, 0);
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
            settings.cache_path = cacheDirectory.toAbsolutePath().toString();
            settings.user_agent_product = "WarProject/1.0";
            // The switches have to go through getInstance, NOT only through startup: on Windows
            // startup() ignores its argument entirely (it just loads the DLLs) and the constructor
            // CefApp stores whatever getInstance is given, because CefAppHandlerAdapter.
            // onBeforeCommandLineProcessing forwards THAT array into CEF's command line. Passing an
            // empty array here (the first version of this class did) silently drops every switch: no
            // --disable-gpu, no renderer-process-limit, no js-flags. Verified with a stand-alone probe
            // that prints the CEF helper command lines: with the array passed, WebGL reports
            // SwiftShader; with it dropped, it reports the Intel UHD through ANGLE D3D11.
            app = CefApp.getInstance(commandLine, settings);
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
            if (!shutdownHookRegistered) {
                // GameShuttingDownEvent is not reliable on the client, and without an explicit disposal
                // Chromium's helpers survive the game and keep burning CPU. The JVM hook always runs.
                Runtime.getRuntime().addShutdownHook(new Thread(CefBootstrap::shutdown, "war-project-cef-shutdown"));
                shutdownHookRegistered = true;
            }
            LOGGER.info("War Project CEF ready: {} (gpu={})", version, Config.cefUseGpu);
            LOGGER.info("War Project CEF switches: {}", String.join(" ", commandLine));
            return true;
        } catch (Throwable throwable) {
            failure = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            started = false;
            LOGGER.warn("War Project CEF could not start ({})", failure, throwable);
            return false;
        }
    }

    /**
     * Kills helper processes left over from an earlier run. Chromium's helpers do not always notice that
     * the game is gone (observed with {@code --no-sandbox}), and a leftover renderer keeps burning CPU.
     * Only helpers whose command line points at THIS game directory's cache are touched, so other
     * Minecraft instances or other CEF-based mods are never affected.
     *
     * @return how many processes were terminated
     */
    public static int reapOrphanedHelpers(Path cacheDirectory) {
        if (cacheDirectory == null) {
            return 0;
        }
        String needle = cacheDirectory.toAbsolutePath().toString();
        int killed = 0;
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            try {
                String command = handle.info().commandLine().orElse("");
                if (command.contains("jcef_helper") && command.contains(needle) && handle.destroyForcibly()) {
                    killed++;
                }
            } catch (Throwable ignored) {
                // The process ended while we looked at it, or the command line is not readable.
            }
        }
        if (killed > 0) {
            LOGGER.info("War Project CEF cleaned up {} leftover helper process(es) from a previous run", killed);
        }
        return killed;
    }

    private static String shorten(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 56 ? url.substring(0, 56) + "..." : url;
    }

    /**
     * One turn of the CEF message loop. Safe to call every tick and every frame, but it should not be
     * called redundantly: the pump is what lets Chromium produce frames, so its call rate is also the
     * ceiling on how many frames the surface can cost. {@code CefWebRenderer} throttles it.
     */
    public static void pump() {
        if (!started || app == null) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            app.N_DoMessageLoopWork();
        } catch (Throwable throwable) {
            started = false;
            failure = "message loop: " + throwable;
            LOGGER.warn("War Project CEF message loop failed", throwable);
        }
        pumpCalls++;
        pumpNanos += System.nanoTime() - startedAt;
    }

    /** Message loop turns served so far; the diagnostics report how many happen per second. */
    public static long pumpCalls() {
        return pumpCalls;
    }

    /** Nanoseconds spent inside the message loop, summed over every pump. */
    public static long pumpNanos() {
        return pumpNanos;
    }

    /**
     * Shuts CEF down for this session. The browsers are closed first (by the caller), then the client
     * and the native app are disposed. Skipping that last step leaves Chromium's helper processes alive
     * after the game exits, where they keep spinning (observed as orphaned jcef_helper.exe processes
     * with hours of accumulated CPU time).
     */
    public static synchronized void shutdown() {
        if (!started && client == null && app == null) {
            return;
        }
        started = false;
        // CEF tears its helper processes down asynchronously, and that work only advances while its
        // message loop runs. Pump it a little around the disposal, otherwise the helpers can outlive the
        // game (the {@code reapOrphanedHelpers} sweep on the next launch is the safety net).
        for (int i = 0; i < 30; i++) {
            try {
                app.N_DoMessageLoopWork();
                Thread.sleep(5L);
            } catch (Throwable ignored) {
                break;
            }
        }
        try {
            if (client != null) {
                client.dispose();
            }
        } catch (Throwable throwable) {
            LOGGER.warn("War Project CEF client disposal failed", throwable);
        }
        client = null;
        try {
            if (app != null) {
                app.dispose();
            }
        } catch (Throwable throwable) {
            LOGGER.warn("War Project CEF app disposal failed", throwable);
        }
        app = null;
        LOGGER.info("War Project CEF disposed");
    }
}
