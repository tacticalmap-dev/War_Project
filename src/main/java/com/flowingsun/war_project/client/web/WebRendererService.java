package com.flowingsun.war_project.client.web;

import com.flowingsun.war_project.Config;
import com.flowingsun.war_project.resource.ResourceKind;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Chooses and owns the active {@link WebRenderer}.
 *
 * <p>The Chromium backend lives in {@code client.cef} and loads the {@code org.cef} bindings that are
 * compiled into this mod; it is reached reflectively so that a missing binary or a broken install can
 * never fail class loading for the rest of the mod. Any failure leaves {@link #active()} null, which
 * means "draw with the built in HTML kernel".
 */
public final class WebRendererService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CEF_BACKEND = "com.flowingsun.war_project.client.cef.CefWebRenderer";

    private static WebRenderer active;
    private static boolean attempted;

    private WebRendererService() {
    }

    /**
     * The backend that should draw, or null to keep using the built in renderer. A backend that is
     * still downloading or starting up reports null, so the interface keeps working while it prepares.
     */
    public static WebRenderer active() {
        if (active == null) {
            return null;
        }
        try {
            return active.isReady() ? active : null;
        } catch (Throwable throwable) {
            disable("backend failed: " + throwable);
            return null;
        }
    }

    /** Preparation status for the built in HUD, or an empty string when there is nothing to say. */
    public static String statusText() {
        if (active == null) {
            return "";
        }
        try {
            return active.isReady() ? "" : active.statusText();
        } catch (Throwable throwable) {
            return "";
        }
    }

    /** Tells the backend the player left the world. */
    public static void onDisconnect() {
        if (active != null) {
            active.onDisconnect();
        }
    }

    /** Game shutdown: releases the surfaces and then CEF itself. */
    public static void shutdown() {
        if (active != null) {
            try {
                active.shutdown();
            } catch (Throwable throwable) {
                LOGGER.warn("War Project web backend shutdown failed", throwable);
            }
        }
        active = null;
        try {
            Class<?> bootstrap = Class.forName("com.flowingsun.war_project.client.cef.CefBootstrap");
            bootstrap.getMethod("shutdown").invoke(null);
        } catch (Throwable ignored) {
            // Chromium was never started in this session.
        }
    }

    /** Called once from client setup. Never throws. */
    public static void init() {
        if (attempted) {
            return;
        }
        attempted = true;
        String mode = Config.webRenderer == null ? "auto" : Config.webRenderer.toLowerCase(Locale.ROOT);
        if (mode.equals("native")) {
            LOGGER.info("War Project web backend: built in renderer (configured)");
            return;
        }
        if (!cefSourcesPresent()) {
            LOGGER.info("War Project web backend: built in renderer (org.cef is not compiled in this build)");
            return;
        }
        try {
            Class<?> backend = Class.forName(CEF_BACKEND);
            WebRenderer created = (WebRenderer) backend.getDeclaredConstructor().newInstance();
            if (created instanceof CefAvailability availability && !availability.available()) {
                LOGGER.info("War Project web backend: built in renderer ({})", availability.unavailableReason());
                return;
            }
            active = created;
            LOGGER.info("War Project web backend: Chromium requested");
        } catch (Throwable throwable) {
            LOGGER.warn("War Project web backend: built in renderer (Chromium backend could not start)", throwable);
            active = null;
        }
    }

    private static boolean cefSourcesPresent() {
        try {
            Class.forName("org.cef.CefApp");
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /** Game directory used for the CEF binaries; exposed so the backend and diagnostics agree. */
    public static Path gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    /** Lets the backend step aside at runtime, e.g. after a failed initialisation. */
    public static void disable(String reason) {
        if (active != null) {
            try {
                active.shutdown();
            } catch (Throwable throwable) {
                LOGGER.warn("War Project web backend failed while shutting down", throwable);
            }
        }
        active = null;
        LOGGER.info("War Project web backend: built in renderer ({})", reason);
    }

    // ------------------------------------------------------------------ event plumbing

    public static void tick() {
        if (active != null) {
            active.tick();
        }
    }

    public static void renderIsland(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (active != null) {
            active.renderIsland(graphics, screenWidth, screenHeight);
        }
    }

    public static void renderPanel(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (active != null) {
            active.renderPanel(graphics, screenWidth, screenHeight, mouseX, mouseY);
        }
    }

    public static boolean isPanelOpen() {
        return active != null && active.isPanelOpen();
    }

    public static boolean islandContains(double mouseX, double mouseY) {
        return active != null && active.islandContains(mouseX, mouseY);
    }

    public static boolean panelContains(double mouseX, double mouseY) {
        return active != null && active.panelContains(mouseX, mouseY);
    }

    public static boolean openPanel(ResourceKind kind, int mouseX, int mouseY) {
        if (active == null) {
            return false;
        }
        active.openPanel(kind, mouseX, mouseY);
        return true;
    }

    public static void closePanel() {
        if (active != null) {
            active.closePanel();
        }
    }

    public static boolean mousePressed(double mouseX, double mouseY, int button) {
        return active != null && active.mousePressed(mouseX, mouseY, button);
    }

    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        return active != null && active.mouseReleased(mouseX, mouseY, button);
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return active != null && active.mouseScrolled(mouseX, mouseY, delta);
    }

    public static boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return active != null && active.keyPressed(keyCode, scanCode, modifiers);
    }

    public static boolean charTyped(char character) {
        return active != null && active.charTyped(character);
    }

    public static void onTransferResult(boolean ok, String message) {
        if (active != null) {
            active.onTransferResult(ok, message);
        }
    }
}
