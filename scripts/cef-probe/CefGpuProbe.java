import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.handler.CefScreenInfo;

import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Answers "is Chromium actually on the GPU, and what does each mode cost" with measurements. */
public final class CefGpuProbe {
    private static final String PAGE = "<!doctype html><html><head><style>"
            + "html,body{margin:0;padding:0;background:transparent}div{width:176px;height:150px;"
            + "background:#000000f0;border-radius:10px}</style></head><body><div id='x'></div>"
            + "<script>var i=0;setInterval(function(){i=(i+1)%60;"
            + "document.getElementById('x').style.opacity=(1-i/120).toFixed(3);},16);</script></body></html>";

    private static final String GL_PROBE = "(function(){var s='no-webgl';try{var c=document.createElement('canvas');"
            + "var g=c.getContext('webgl')||c.getContext('experimental-webgl');if(g){var d=g.getExtension('WEBGL_debug_renderer_info');"
            + "s=d?g.getParameter(d.UNMASKED_RENDERER_WEBGL):g.getParameter(g.RENDERER);}}catch(e){s='err:'+e;}"
            + "var p=document.createElement('pre');p.textContent='GLBACKEND='+s;document.body.appendChild(p);})()";

    private static final String[] CURRENT = {
            "--autoplay-policy=no-user-gesture-required",
            "--disable-gpu", "--disable-gpu-compositing", "--disable-gpu-vsync",
            "--disable-background-networking", "--disable-component-update", "--disable-breakpad",
            "--disable-default-apps", "--disable-extensions", "--disable-sync",
            "--no-first-run", "--no-default-browser-check", "--renderer-process-limit=1"
    };

    private static final String[] GPU = {
            "--autoplay-policy=no-user-gesture-required",
            "--disable-background-networking", "--disable-component-update", "--disable-breakpad",
            "--disable-default-apps", "--disable-extensions", "--disable-sync",
            "--no-first-run", "--no-default-browser-check", "--renderer-process-limit=1"
    };

    public static void main(String[] args) throws Exception {
        String label = args[0];
        Path natives = Paths.get(args[1]).toAbsolutePath();
        Path cache = Paths.get(args[2]).toAbsolutePath();
        boolean passArgs = Boolean.parseBoolean(args[3]);
        String[] switches = "gpu".equals(args[4]) ? GPU : CURRENT;
        long seconds = Long.parseLong(args[5]);
        int scale = args.length > 6 ? Integer.parseInt(args[6]) : 1;

        Files.createDirectories(cache);
        System.setProperty("jcef.path", natives.toString());
        CefApp.startup(switches);
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = true;
        settings.background_color = settings.new ColorType(0, 0, 0, 0);
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_ERROR;
        settings.cache_path = cache.toString();
        CefApp app = CefApp.getInstance(passArgs ? switches : new String[]{}, settings);

        long initDeadline = System.nanoTime() + 20_000_000_000L;
        while (CefApp.getState() != CefApp.CefAppState.INITIALIZED && System.nanoTime() < initDeadline) {
            app.N_DoMessageLoopWork();
            Thread.sleep(5L);
        }

        CefClient client = app.createClient();
        Surface surface = new Surface(client, "data:text/html;charset=utf-8,"
                + URLEncoder.encode(PAGE, StandardCharsets.UTF_8).replace("+", "%20"));
        surface.scale = scale;
        surface.resize(176, 150);
        surface.createImmediately();
        pumpFor(app, 2500L);
        surface.executeJavaScript(GL_PROBE, "probe", 0);
        pumpFor(app, 1500L);
        String text = readText(app, surface);
        String backend = "?";
        if (text != null) {
            int at = text.indexOf("GLBACKEND=");
            if (at >= 0) {
                backend = text.substring(at + 10).trim().split("\n")[0];
            }
        }
        int framesBefore = surface.frames;

        long cpuStart = cpuNanos();
        long wallStart = System.nanoTime();
        pumpFor(app, seconds * 1000L);
        long wall = System.nanoTime() - wallStart;
        long cpu = Math.max(0L, cpuNanos() - cpuStart);
        int frames = surface.frames - framesBefore;

        System.out.println(label + "  argsPassed=" + passArgs + "  scale=" + scale + "  backend=" + backend);
        System.out.printf("    frames=%d fps=%.1f copiedMiB=%.2f cpuMs=%d cpuPercentOfOneCore=%.1f%n",
                frames, frames / (wall / 1e9), surface.bytes / 1048576.0, cpu / 1_000_000L,
                100.0 * cpu / wall);
        dumpProcesses();
        System.exit(0);
    }

    private static void pumpFor(CefApp app, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            app.N_DoMessageLoopWork();
            Thread.sleep(30L);
        }
    }

    private static String readText(CefApp app, CefBrowser browser) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        String[] out = new String[1];
        browser.getText(value -> {
            out[0] = value;
            latch.countDown();
        });
        long deadline = System.currentTimeMillis() + 4000L;
        while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            app.N_DoMessageLoopWork();
            Thread.sleep(10L);
        }
        return out[0];
    }

    /** A CEF helper, matched by executable name as well: the command line is not always readable. */
    private static boolean isCefHelper(ProcessHandle handle) {
        try {
            if (handle.info().command().orElse("").toLowerCase().contains("jcef_helper")) {
                return true;
            }
            return handle.info().commandLine().orElse("").contains("jcef_helper");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long cpuNanos() {
        long total = 0L;
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            total += sun.getProcessCpuTime();
        }
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            try {
                if (isCefHelper(handle)) {
                    total += handle.info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
                }
            } catch (Throwable ignored) {
                // process gone
            }
        }
        return total;
    }

    private static void dumpProcesses() {
        List<String> lines = new ArrayList<>();
        int gpu = 0;
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            try {
                String command = handle.info().commandLine().orElse("");
                if (!command.contains("jcef_helper")) {
                    continue;
                }
                List<String> tokens = Arrays.asList(command.split("\\s+"));
                String type = tokens.stream().filter(t -> t.startsWith("--type=")).findFirst().orElse("--type=?");
                if (type.contains("gpu-process")) {
                    gpu++;
                }
                lines.add(String.format("    pid=%d %-26s disableGpuToken=%s swiftshader=%s cpuMs=%d",
                        handle.pid(), type, tokens.contains("--disable-gpu"),
                        command.toLowerCase().contains("swiftshader"),
                        handle.info().totalCpuDuration().orElse(Duration.ZERO).toMillis()));
            } catch (Throwable ignored) {
                // process gone
            }
        }
        System.out.println("    gpuProcesses=" + gpu);
        lines.forEach(System.out::println);
    }

    static final class Surface extends CefBrowserOsr {
        volatile int frames;
        volatile long bytes;
        volatile int scale = 1;

        Surface(CefClient client, String url) {
            super(client, url, true, null);
        }

        void resize(int width, int height) {
            browser_rect_.setBounds(0, 0, width, height);
            wasResized(width, height);
        }

        @Override
        public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
            Rectangle rect = browser_rect_.getBounds();
            info.Set(scale, 32, 8, false, rect, rect);
            return true;
        }

        @Override
        public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer,
                int width, int height) {
            frames++;
            bytes += (long) width * height * 4;
        }
    }
}
