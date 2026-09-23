package com.flowingsun.war_project.client;

import com.flowingsun.war_project.html.HtmlDocument;
import com.flowingsun.war_project.html.HtmlNode;
import com.flowingsun.war_project.html.HtmlViewHost;
import com.flowingsun.war_project.nodeLJYS.VpWarState;
import com.flowingsun.war_project.resource.ResourceKind;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * The fused top HUD, rendered through the HTML kernel: one long bar hugging the top edge of the window
 * (VP war: blue track for our side, red track for the strongest opposing side, one star per VP node in
 * between) with the resource island attached to its lower edge and a concave fillet where the two meet.
 *
 * <p>Both parts share one document and therefore one visibility rule: the whole HUD shows while the
 * game runs, the player has a team and no Superb Warfare gun sight is up. The bar and the island are
 * ordinary rounded boxes (their textures never change); only the two small fillet patches move with the
 * island, so the surface is rasterised once instead of every frame.
 */
public final class ResourceIslandView {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Flush against the window's top edge, as asked: the bar is the first thing on screen. */
    private static final int TOP_MARGIN = 0;
    /** Geometry shared with html/top_hud.html. */
    private static final int BAR_HEIGHT = 14;
    private static final int ISLAND_HEIGHT = 18;
    private static final int BAR_GAP = 6;
    /** The bar's inner padding, which the row's contents must stay inside of. */
    private static final int BAR_PADDING = 8;
    /** Horizontal offset of a joint patch from the island's content box: -(island padding + radius).
     * The stylesheet uses the same number; this is only the fallback when that rule does not apply. */
    private static final int JOINT_OFFSET = -15;
    /** Both tracks together always take this fraction of the bar, so they scale with it and can
     * never grow past the backdrop. */
    private static final double TRACK_RATIO = 0.62D;
    /** Below this a track stops being readable, so the ratio gives way instead. */
    private static final int TRACK_MIN_HALF = 8;
    /** The bar is a window-width fraction, clamped, so it reads as a HUD strip rather than a full band. */
    private static final double BAR_WIDTH_RATIO = 0.54D;
    private static final int BAR_MIN_WIDTH = 200;
    /** Kept well under the window so the bar never reaches the corner minimap. */
    private static final int BAR_MAX_WIDTH = 360;
    /** Stars are pre-built in the page; more VP nodes than slots only draws the first slots. */
    private static final int STAR_SLOTS = 16;
    private static final float FLASH_MS = 200.0F;
    private static final int ARC_STEPS = 24;
    private static final String PATH = "html/top_hud.html";
    private static final String FALLBACK = "<div id=\"hud\" class=\"hud\">"
            + "<div id=\"content\" class=\"content\">"
            + "<div id=\"topbar\" class=\"topbar\"><span id=\"mineScore\" class=\"score\">500</span>"
            + "<span id=\"foeScore\" class=\"score\">500</span></div>"
            + "<div id=\"island\" class=\"island\">"
            + "<img id=\"ammoIcon\" class=\"icon\" src=\"war_project:textures/gui/ammo.png\"/>"
            + "<span id=\"ammoAmount\" class=\"amount\">0</span>"
            + "<span id=\"ammoRate\" class=\"rate\">+0</span>"
            + "<img id=\"fuelIcon\" class=\"icon\" src=\"war_project:textures/gui/fuel.png\"/>"
            + "<span id=\"fuelAmount\" class=\"amount\">0</span>"
            + "<span id=\"fuelRate\" class=\"rate\">+0</span></div></div></div>"
            + "<style>.hud{position:relative;height:40px;}.hudhidden{display:none;}"
            + ".content{display:flex;flex-direction:column;align-items:center;}"
            + ".topbar{display:flex;flex-direction:row;align-items:center;justify-content:center;gap:6px;height:22px;"
            + "background-color:#000000;border-radius:11px;}"
            + ".island{display:flex;flex-direction:row;align-items:center;gap:4px;height:18px;padding:0 10px;"
            + "background-color:#000000;border-radius:9px;}.icon{width:12px;height:12px;}"
            + ".amount{color:#ffffff;}.rate{color:#7ce38b;}.score{color:#ffffff;}</style>";

    private static final String COLOR_NEUTRAL = "#e5e7eb";
    private static final String COLOR_MINE = "#4fa3ff";
    private static final String COLOR_FOE = "#ff4d4d";
    private static final String COLOR_NONE = "#00000000";

    private static HtmlViewHost host;
    private static boolean broken;
    private static final String[] starOwner = new String[STAR_SLOTS];
    private static final float[] starFlash = new float[STAR_SLOTS];
    private static final String[] arcCache = new String[ARC_STEPS + 1];
    private static int debugFrames;

    private ResourceIslandView() {
    }

    public static HtmlViewHost host() {
        if (host == null) {
            host = new HtmlViewHost(HtmlDocument.parse(HtmlResources.load(PATH, FALLBACK)));
        }
        return host;
    }

    /**
     * The single visibility rule of the whole HUD: running, the player has a team, and no Superb
     * Warfare gun sight is up (first person or the held gun-sight key in third person).
     */
    public static boolean shouldShow() {
        return !broken
                && ResourceClientState.isRunning()
                && ResourceClientState.hasTeam()
                && !SuperbWarfareCompat.isVehicleGunSight();
    }

    public static void tick(float deltaMs) {
        if (broken) {
            return;
        }
        try {
            HtmlViewHost view = host();
            boolean show = shouldShow();
            HtmlNode hud = view.node("hud");
            if (hud != null && show == hud.hasClass("hudhidden")) {
                hud.setClass("hudhidden", !show);
            }
            if (show) {
                update(view);
            }
            for (int slot = 0; slot < STAR_SLOTS; slot++) {
                if (starFlash[slot] <= 0.0F) {
                    continue;
                }
                starFlash[slot] = Math.max(0.0F, starFlash[slot] - deltaMs);
                if (starFlash[slot] <= 0.0F) {
                    HtmlNode star = view.node("star" + slot);
                    if (star != null) {
                        star.setClass("flash", false);
                    }
                }
            }
            view.tick(deltaMs);
        } catch (Throwable throwable) {
            broken = true;
            LOGGER.warn("War Project top HUD disabled after a failure", throwable);
        }
    }

    public static void render(GuiGraphics graphics, int screenWidth) {
        if (!shouldShow()) {
            return;
        }
        try {
            HtmlViewHost view = host();
            HtmlNode content = view.node("content");
            HtmlNode topbar = view.node("topbar");
            int barWidth = barWidthFor(screenWidth);
            if (content != null && content.inlineStyle.width != screenWidth) {
                // A block child would shrink to its content, which would leave the whole HUD off centre;
                // pinning the column to the viewport is what puts the bar in the middle of the screen.
                content.inlineStyle.width = screenWidth;
                content.style.width = screenWidth;
                view.markLayoutDirty();
            }
            if (topbar != null && topbar.inlineStyle.width != barWidth) {
                topbar.inlineStyle.width = barWidth;
                topbar.style.width = barWidth;
                view.markLayoutDirty();
            }
            ensureJoints(view);
            Font font = Minecraft.getInstance().font;
            // The two joint patches are absolutely positioned children of the island itself, so the
            // layout engine welds them to it on every frame. Nothing here tracks the island's pixels:
            // doing that by hand is what used to strand the patches in the top left corner while the
            // layout was still settling.
            view.render(graphics, font, 0, TOP_MARGIN, screenWidth);
            if (debugFrames <= 600 && ++debugFrames % 100 == 0) {
                HtmlNode island = view.node("island");
                HtmlNode joint = view.node("filletL");
                LOGGER.info("War Project top HUD: bar={}x{} at ({},{}) island={}x{} at ({},{}) jointL={},{} screen={}",
                        topbar == null ? -1 : topbar.width, topbar == null ? -1 : topbar.height,
                        topbar == null ? -1 : topbar.x, topbar == null ? -1 : topbar.y,
                        island == null ? -1 : island.width, island == null ? -1 : island.height,
                        island == null ? -1 : island.x, island == null ? -1 : island.y,
                        joint == null ? -1 : joint.x, joint == null ? -1 : joint.y,
                        screenWidth);
            }
        } catch (Throwable throwable) {
            broken = true;
            LOGGER.warn("War Project top HUD disabled after a draw failure", throwable);
        }
    }

    /**
     * Safety net for the two joint patches. They are positioned purely in CSS so the layout engine
     * welds them to the island every frame, but that also means one bad rule - a stray comment, a
     * typo, a selector that never matches - silently drops them and the island looks like it lost its
     * shoulders. If either patch came out of style resolution with no offset at all, pin it here.
     */
    private static void ensureJoints(HtmlViewHost view) {
        HtmlNode left = view.node("filletL");
        HtmlNode right = view.node("filletR");
        boolean leftMissing = left != null && left.style.left == Integer.MIN_VALUE;
        boolean rightMissing = right != null && right.style.right == Integer.MIN_VALUE;
        if (!leftMissing && !rightMissing) {
            return;
        }
        LOGGER.warn("War Project top HUD: joint patch offset missing from CSS, using the built in fallback");
        if (leftMissing) {
            left.inlineStyle.left = JOINT_OFFSET;
            left.style.left = JOINT_OFFSET;
        }
        if (rightMissing && right != null) {
            right.inlineStyle.right = JOINT_OFFSET;
            right.style.right = JOINT_OFFSET;
        }
        view.markLayoutDirty();
    }

    /** Which icon (if any) sits under the given screen coordinates. */
    public static ResourceKind iconAt(double mouseX, double mouseY) {
        if (!shouldShow()) {
            return null;
        }
        HtmlViewHost view = host();
        if (hits(view.node("ammoIcon"), mouseX, mouseY)) {
            return ResourceKind.AMMO;
        }
        if (hits(view.node("fuelIcon"), mouseX, mouseY)) {
            return ResourceKind.FUEL;
        }
        return null;
    }

    private static boolean hits(HtmlNode node, double mouseX, double mouseY) {
        return node != null && node.contains(mouseX, mouseY);
    }

    private static void update(HtmlViewHost view) {
        setText(view, "ammoAmount", amountText(ResourceClientState.ammo()));
        setText(view, "ammoRate", rateText(ResourceClientState.ammoPerMinute()));
        setText(view, "fuelAmount", amountText(ResourceClientState.fuel()));
        setText(view, "fuelRate", rateText(ResourceClientState.fuelPerMinute()));

        VpWarState.Side mine = VpWarClientState.mySide();
        VpWarState.Side foe = VpWarClientState.enemySide();
        double max = VpWarClientState.maxScore();
        List<VpWarState.NodeState> nodes = VpWarClientState.nodes();

        int size = starSize(nodes.size());
        int starArea = Math.min(nodes.size(), STAR_SLOTS) * (size + 2);
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        Font font = Minecraft.getInstance().font;
        // Both score boxes get the same width, and each one's text hugs the edge that faces its own
        // track, so the two halves read as a mirror image even when the numbers differ in length.
        String mineText = scoreText(mine);
        String foeText = scoreText(foe);
        int scoreWidth = Math.max(font.width(mineText), font.width(foeText));
        setScore(view, "mineScore", mineText, scoreWidth, font);
        setScore(view, "foeScore", foeText, scoreWidth, font);
        // Everything the row carries besides the two tracks: the two score boxes, the four gaps and
        // the bar's own padding. What is left over is the room the tracks may share.
        int fixed = scoreWidth * 2 + BAR_GAP * 4 + BAR_PADDING * 2;
        int barWidth = barWidthFor(screenWidth);
        int budget = Math.max(TRACK_MIN_HALF * 2, barWidth - starArea - fixed);
        // Scaled off the bar rather than clamped to a fixed pixel range, so a smaller backdrop gets
        // proportionally smaller tracks and the fill always ends exactly on the track's own edge.
        int half = (int) Math.round(barWidth * TRACK_RATIO / 2.0D);
        half = Math.max(TRACK_MIN_HALF, Math.min(half, budget / 2));
        setWidth(view, view.node("mineTrack"), half);
        setWidth(view, view.node("foeTrack"), half);
        setWidth(view, view.node("mineFill"), (int) Math.round(half * ratio(mine, max)));
        setWidth(view, view.node("foeFill"), (int) Math.round(half * ratio(foe, max)));

        for (int slot = 0; slot < STAR_SLOTS; slot++) {
            HtmlNode star = view.node("star" + slot);
            if (star == null) {
                continue;
            }
            boolean used = slot < nodes.size();
            star.setClass("staroff", !used);
            if (!used) {
                starOwner[slot] = null;
                continue;
            }
            setSize(view, star, size);
            VpWarState.NodeState node = nodes.get(slot);
            String ownerKey = node.ownerSideKey();
            String ownerColor = ownerKey.isEmpty() ? COLOR_NEUTRAL
                    : (VpWarClientState.isMySide(ownerKey) ? COLOR_MINE : COLOR_FOE);
            HtmlNode shape = view.node("starShape" + slot);
            if (shape != null) {
                shape.attributes.put("fill", ownerColor);
            }
            String attackerKey = node.attackerSideKey();
            HtmlNode arc = view.node("starArc" + slot);
            if (arc != null) {
                arc.attributes.put("fill", attackerKey.isEmpty() ? COLOR_NONE
                        : (VpWarClientState.isMySide(attackerKey) ? COLOR_MINE : COLOR_FOE));
                arc.attributes.put("d", attackerKey.isEmpty() ? "" : arcPath(node.capturePercent()));
            }
            if (!ownerKey.equals(starOwner[slot])) {
                starOwner[slot] = ownerKey;
                star.setClass("flash", true);
                starFlash[slot] = FLASH_MS;
            }
        }
    }

    /** Bar width for this window, quantised to 4px so its rounded texture is not rebuilt constantly. */
    private static int barWidthFor(int screenWidth) {
        int width = (int) Math.round(screenWidth * BAR_WIDTH_RATIO);
        width = Math.max(BAR_MIN_WIDTH, Math.min(BAR_MAX_WIDTH, width));
        width = Math.min(width, Math.max(120, screenWidth - 16));
        return (width + 3) / 4 * 4;
    }

    private static String scoreText(VpWarState.Side side) {
        return side == null ? "--" : String.valueOf((long) Math.floor(Math.max(0.0D, side.score())));
    }

    private static double ratio(VpWarState.Side side, double max) {
        if (side == null || max <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, side.score() / max));
    }

    /**
     * Side of one star slot, in GUI pixels. The star sits in a 38 unit {@code viewBox}, so this is also
     * the scale of its ring. The slot grew with the star (10/9/8 -> 14/13/12, and 14 is exactly the war
     * bar's height) while the ring grew more, which is what opened the gap between ring and star.
     */
    private static int starSize(int count) {
        if (count <= 8) {
            return 14;
        }
        if (count <= 12) {
            return 13;
        }
        return 12;
    }

    /**
     * Clockwise ring sector covering {@code percent} of the circle, quantised to 24 steps.
     *
     * <p>Geometry shared with {@code html/top_hud.html}: centre (12,12), the ring's inner edge clear of
     * the star's points (star radius 11.2, ring inner radius 13.8) and its outer edge at 18, which is
     * what the {@code viewBox} of -7..31 was sized for.
     */
    private static String arcPath(double percent) {
        int steps = Math.max(0, Math.min(ARC_STEPS, (int) Math.round(percent * ARC_STEPS)));
        if (steps == 0) {
            return "";
        }
        String cached = arcCache[steps];
        if (cached != null) {
            return cached;
        }
        double sweep = 2.0D * Math.PI * steps / ARC_STEPS;
        double start = -Math.PI / 2.0D;
        int segments = Math.max(2, steps * 2);
        StringBuilder builder = new StringBuilder(segments * 26);
        for (int i = 0; i <= segments; i++) {
            double angle = start + sweep * i / segments;
            builder.append(i == 0 ? "M" : "L")
                    .append(fmt(12.0D + 18.0D * Math.cos(angle))).append(' ')
                    .append(fmt(12.0D + 18.0D * Math.sin(angle)));
        }
        for (int i = segments; i >= 0; i--) {
            double angle = start + sweep * i / segments;
            builder.append('L')
                    .append(fmt(12.0D + 13.8D * Math.cos(angle))).append(' ')
                    .append(fmt(12.0D + 13.8D * Math.sin(angle)));
        }
        builder.append('Z');
        cached = builder.toString();
        arcCache[steps] = cached;
        return cached;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void setText(HtmlViewHost view, String id, String text) {
        HtmlNode node = view.node(id);
        if (node != null && !text.equals(node.text)) {
            node.text = text;
            view.markLayoutDirty();
        }
    }

    /**
     * Writes one score box: fixed width, text pinned to the edge that faces its own track (the left
     * box is right aligned, the right box is left aligned), which is what makes the two halves
     * symmetric regardless of how many digits each side has.
     */
    private static void setScore(HtmlViewHost view, String id, String text, int width, Font font) {
        HtmlNode node = view.node(id);
        if (node == null) {
            return;
        }
        boolean rightAligned = id.startsWith("mine");
        int pad = rightAligned ? Math.max(0, width - font.width(text)) : 0;
        boolean dirty = false;
        if (!text.equals(node.text)) {
            node.text = text;
            dirty = true;
        }
        if (node.inlineStyle.width != width) {
            node.inlineStyle.width = width;
            node.style.width = width;
            dirty = true;
        }
        if (node.style.paddingLeft != pad) {
            node.inlineStyle.paddingLeft = pad;
            node.style.paddingLeft = pad;
            dirty = true;
        }
        if (dirty) {
            view.markLayoutDirty();
        }
    }

    private static void setWidth(HtmlViewHost view, HtmlNode node, int width) {
        if (node == null || node.inlineStyle.width == width) {
            return;
        }
        node.inlineStyle.width = width;
        node.style.width = width;
        view.markLayoutDirty();
    }

    private static void setSize(HtmlViewHost view, HtmlNode node, int size) {
        if (node.inlineStyle.width == size && node.inlineStyle.height == size) {
            return;
        }
        node.inlineStyle.width = size;
        node.inlineStyle.height = size;
        node.style.width = size;
        node.style.height = size;
        view.markLayoutDirty();
    }

    public static String amountText(double value) {
        return String.valueOf((long) Math.floor(Math.max(0.0D, value)));
    }

    public static String rateText(double perMinute) {
        return "+" + (long) Math.floor(Math.max(0.0D, perMinute));
    }

    public static void reset() {
        host = null;
        broken = false;
        debugFrames = 0;
        for (int slot = 0; slot < STAR_SLOTS; slot++) {
            starOwner[slot] = null;
            starFlash[slot] = 0.0F;
        }
    }
}
