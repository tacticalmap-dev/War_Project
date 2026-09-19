package com.flowingsun.war_project.client.web;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the page markup that is handed to Chromium. Pages live in
 * {@code assets/war_project/web/} and are self contained, so no custom protocol handler is needed:
 * the text is inlined into a {@code data:} URL. A missing file falls back to a minimal page instead
 * of leaving the surface blank.
 */
public final class WebPages {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "assets/war_project/web/";

    private static final String ISLAND_FALLBACK = "<html><body style=\"margin:0;background:transparent;"
            + "font:12px 'Segoe UI',sans-serif;color:#fff\"><div id=\"island\" style=\"margin:6px auto;width:max-content;"
            + "padding:3px 12px;border-radius:999px;background:#05070a;box-shadow:0 6px 18px #0008\">"
            + "<span id=\"ammo\">0</span> <span id=\"ammoRate\" style=\"color:#7ce38b\">+0</span></div>"
            + "<script>window.wp={apply:function(s){document.getElementById('ammo').textContent=s.ammo;"
            + "document.getElementById('ammoRate').textContent='+'+s.ammoRate;}};</script></body></html>";

    private static final String PANEL_FALLBACK = "<html><body style=\"margin:0;background:transparent\">"
            + "<div id=\"panel\" style=\"width:200px;padding:12px;border-radius:12px;background:#131822;color:#fff;"
            + "font:12px 'Segoe UI',sans-serif\">Transfer panel unavailable. Edit the page resource and reload.</div>"
            + "<script>window.wp={apply:function(){},openPanel:function(){},closePanel:function(){},result:function(){}};</script></body></html>";

    private WebPages() {
    }

    /** Game texture used for the ammo icon, inlined into the page. */
    private static final String AMMO_TEXTURE = "assets/war_project/textures/gui/ammo.png";
    /** Game texture used for the fuel icon, inlined into the page. */
    private static final String FUEL_TEXTURE = "assets/war_project/textures/gui/fuel.png";

    private static String ammoIcon;
    private static String fuelIcon;

    /**
     * The combined surface: the resource island on top, which expands downwards into the transfer panel.
     * Keeping it in one document is what makes the growth animation possible.
     */
    public static String overlay() {
        return load("overlay.html", ISLAND_FALLBACK)
                .replace("__AMMO_ICON__", ammoIcon())
                .replace("__FUEL_ICON__", fuelIcon());
    }

    public static String panel() {
        return load("panel.html", PANEL_FALLBACK);
    }

    /**
     * The pages are loaded as {@code data:} URLs, so they cannot reference textures inside the mod jar.
     * The PNGs are therefore inlined as base64 data URIs, which is what keeps the original game art.
     */
    private static String ammoIcon() {
        if (ammoIcon == null) {
            ammoIcon = dataUri(AMMO_TEXTURE);
        }
        return ammoIcon;
    }

    private static String fuelIcon() {
        if (fuelIcon == null) {
            fuelIcon = dataUri(FUEL_TEXTURE);
        }
        return fuelIcon;
    }

    /**
     * Inlines one game texture exactly as shipped: no re-encoding, no pre-scaling, nothing. The page
     * displays it at its CSS size (14 px), which is the same single mapping the built in renderer does,
     * and any earlier downscale here only added a second, lossy resample on top.
     */
    private static String dataUri(String resource) {
        try (InputStream in = WebPages.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("War Project web: texture {} is missing from the jar", resource);
                return "";
            }
            byte[] raw = in.readAllBytes();
            LOGGER.info("War Project web: icon {} inlined raw ({} bytes)", resource, raw.length);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(raw);
        } catch (IOException exception) {
            LOGGER.warn("War Project web: could not inline {}", resource, exception);
            return "";
        }
    }

    private static String load(String file, String fallback) {
        try (InputStream in = WebPages.class.getClassLoader().getResourceAsStream(ROOT + file)) {
            if (in == null) {
                return fallback;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return fallback;
        }
    }
}
