package com.flowingsun.war_project.client;

import com.flowingsun.war_project.html.HtmlDocument;
import com.flowingsun.war_project.html.HtmlNode;
import com.flowingsun.war_project.html.HtmlViewHost;
import com.flowingsun.war_project.resource.ResourceKind;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

/**
 * The resource "dynamic island", rendered through the HTML kernel. Horizontal centering is done by
 * the document itself: the {@code #bar} wrapper is a flex row with {@code justify-content:center}
 * whose width is set to the viewport each frame, so the island stays centered without the Java side
 * measuring and re-laying out. The icon rectangles are exposed for the transfer panel.
 */
public final class ResourceIslandView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean DEBUG = System.getProperty("warproject.hud.debug") != null;
    private static final int TOP_MARGIN = 4;
    private static final String PATH = "html/resource_island.html";
    private static final String FALLBACK = "<div id=\"bar\" class=\"bar\"><div id=\"island\" class=\"island\">"
            + "<img id=\"ammo-icon\" class=\"icon\" src=\"war_project:textures/gui/ammo.png\" style=\"width:14px;height:14px\"/>"
            + "<span id=\"ammo-amount\" class=\"amount\">0</span>"
            + "<span id=\"ammo-rate\" class=\"rate\">+0</span>"
            + "<img id=\"fuel-icon\" class=\"icon fuel\" src=\"war_project:textures/gui/fuel.png\" style=\"width:14px;height:14px\"/>"
            + "<span id=\"fuel-amount\" class=\"amount\">0</span>"
            + "<span id=\"fuel-rate\" class=\"rate\">+0</span>"
            + "</div></div><style>.bar{display:flex;flex-direction:row;justify-content:center;}"
            + ".island{display:flex;flex-direction:row;align-items:center;gap:4px;background-color:#000000f0;"
            + "border-radius:999px;padding:2px 8px;opacity:1;transition:150ms ease-out;}.island.hidden{opacity:0;}"
            + ".icon{width:14px;height:14px;}.icon.fuel{margin-left:10px;}.amount{color:#ffffff;}.rate{color:#7ce38b;}</style>";

    private static HtmlViewHost host;
    private static int debugFrames;

    private ResourceIslandView() {
    }

    public static HtmlViewHost host() {
        if (host == null) {
            host = new HtmlViewHost(HtmlDocument.parse(HtmlResources.load(PATH, FALLBACK)));
        }
        return host;
    }

    public static boolean shouldShow() {
        return ResourceClientState.isRunning()
                && ResourceClientState.hasTeam()
                && !SuperbWarfareCompat.isVehicleFirstPerson();
    }

    public static void tick(float deltaMs) {
        HtmlViewHost view = host();
        boolean show = shouldShow();
        HtmlNode island = view.node("island");
        if (island != null) {
            island.setClass("hidden", !show);
        }
        if (show) {
            setText(view, "ammo-amount", amountText(ResourceClientState.ammo()));
            setText(view, "ammo-rate", rateText(ResourceClientState.ammoPerMinute()));
            setText(view, "fuel-amount", amountText(ResourceClientState.fuel()));
            setText(view, "fuel-rate", rateText(ResourceClientState.fuelPerMinute()));
        }
        view.tick(deltaMs);
    }

    public static void render(GuiGraphics graphics, int screenWidth) {
        if (!shouldShow()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HtmlViewHost view = host();
        HtmlNode bar = view.node("bar");
        if (bar != null && bar.inlineStyle.width != screenWidth) {
            // The bar spans the viewport so the document can center the island by itself.
            bar.inlineStyle.width = screenWidth;
            bar.style.width = screenWidth;
            view.markLayoutDirty();
        }
        view.render(graphics, font, 0, TOP_MARGIN, screenWidth);
        if (DEBUG && ++debugFrames % 40 == 0) {
            HtmlNode island = view.node("island");
            LOGGER.info("island debug: screenWidth={} bar={} island x={} w={} h={}", screenWidth,
                    bar == null ? -1 : bar.width, island == null ? -1 : island.x,
                    island == null ? -1 : island.width, island == null ? -1 : island.height);
        }
    }

    /** Which icon (if any) sits under the given screen coordinates. */
    public static ResourceKind iconAt(double mouseX, double mouseY) {
        if (!shouldShow()) {
            return null;
        }
        HtmlViewHost view = host();
        if (hits(view.node("ammo-icon"), mouseX, mouseY)) {
            return ResourceKind.AMMO;
        }
        if (hits(view.node("fuel-icon"), mouseX, mouseY)) {
            return ResourceKind.FUEL;
        }
        return null;
    }

    private static boolean hits(HtmlNode node, double mouseX, double mouseY) {
        return node != null && node.contains(mouseX, mouseY);
    }

    private static void setText(HtmlViewHost view, String id, String text) {
        HtmlNode node = view.node(id);
        if (node != null && !text.equals(node.text)) {
            node.text = text;
            view.markLayoutDirty();
        }
    }

    public static String amountText(double value) {
        return String.valueOf((long) Math.floor(Math.max(0.0D, value)));
    }

    public static String rateText(double perMinute) {
        return "+" + (long) Math.floor(Math.max(0.0D, perMinute));
    }

    public static void reset() {
        host = null;
    }
}
