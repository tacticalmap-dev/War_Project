package com.flowingsun.war_project.client.web;

import com.flowingsun.war_project.resource.ResourceKind;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A UI backend for the resource island and the transfer panel.
 *
 * <p>Only one backend is active at a time. When {@link WebRendererService#active()} returns null the
 * built in HTML kernel draws both surfaces exactly as it always has; a non null backend (the CEF one)
 * takes over rendering and input instead. Keeping the built in path as the default means a missing or
 * broken Chromium install can never make the UI disappear.
 */
public interface WebRenderer {
    /**
     * False while the backend is still preparing (downloading the binaries, starting Chromium). The
     * service keeps handing drawing to the built in renderer until this turns true, so the UI is never
     * blank during preparation.
     */
    boolean isReady();

    /** One line of preparation status for the built in HUD, or an empty string when there is none. */
    String statusText();

    /** Main thread tick: drives the backend, pushes data and consumes pending messages. */
    void tick();

    /** Draws the island while no screen is open. Coordinates are in GUI pixels. */
    void renderIsland(GuiGraphics graphics, int screenWidth, int screenHeight);

    /** Draws the transfer panel on top of whatever screen is open. */
    void renderPanel(GuiGraphics graphics, int screenWidth, int screenHeight, int mouseX, int mouseY);

    boolean isPanelOpen();

    /** True when the island covers this point, so the caller knows the click belongs to the backend. */
    boolean islandContains(double mouseX, double mouseY);

    /** True when the open panel covers this point, so screen events can be cancelled. */
    boolean panelContains(double mouseX, double mouseY);

    void openPanel(ResourceKind kind, int mouseX, int mouseY);

    void closePanel();

    boolean mousePressed(double mouseX, double mouseY, int button);

    boolean mouseReleased(double mouseX, double mouseY, int button);

    boolean mouseScrolled(double mouseX, double mouseY, double delta);

    boolean keyPressed(int keyCode, int scanCode, int modifiers);

    boolean charTyped(char character);

    /** Verdict from the server for a transfer this backend asked for. */
    void onTransferResult(boolean ok, String message);

    /** The client left the world: release per-world state but keep the engine warm. */
    void onDisconnect();

    /** The game is shutting down. */
    void shutdown();
}
