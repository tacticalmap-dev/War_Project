package com.flowingsun.war_project.compat.xaero;

import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.xaero.XaeroWarProjectMapRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

import java.lang.reflect.Field;

/**
 * Draws the node / warzone overlay into the Xaero minimap framebuffer, i.e. as part of the map itself.
 *
 * <p>Path verified against the Xaero bytecode: {@code MinimapFBORenderer.renderChunksToFBO} binds the
 * rotation framebuffer, calls {@code mapHandler.prepareRender(ps, pc, zoom, halfWView)} and then
 * {@code mapHandler.render(...)} while that framebuffer is still bound (it is unbound right after). The
 * map handler positions elements with {@code px = ps*(dx*zoom) - pc*(dz*zoom)} around the map centre,
 * the same projection the on-screen handler uses, so drawing there lands in the map texture and Xaero's
 * own square / ellipse mask clips it exactly like the map itself.
 *
 * <p>This overlay is injected at {@code beforeRender}'s RETURN. That method is an empty body in both
 * handler subclasses, and it runs before the base {@code render} applies its per-element
 * {@code pose.translate(0, 0, depth)} - which is never undone, and depth testing is enabled inside the
 * framebuffer, so drawing after it risks being depth-rejected.
 */
public final class XaeroMinimapFramebufferOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long ACTIVE_WINDOW_MILLIS = 1000L;
    private static Field psField;
    private static Field pcField;
    private static Field zoomField;
    private static volatile long lastActiveMillis;
    private static boolean loggedFirstFrame;

    private XaeroMinimapFramebufferOverlay() {
    }

    /**
     * True while the framebuffer path has drawn recently. The on-screen overlay uses this to skip its
     * own geometry, so the two paths never draw the same shapes twice.
     */
    public static boolean isActiveRecently() {
        return System.currentTimeMillis() - lastActiveMillis < ACTIVE_WINDOW_MILLIS;
    }

    public static void renderWarProjectOverlay(GuiGraphics graphics, MinimapElementMapRendererHandler handler, Vec3 renderPos, RenderTarget renderTarget) {
        FboTransform transform = readTransform(handler);
        if (transform == null || transform.zoom() <= 0.0D) {
            return;
        }

        lastActiveMillis = System.currentTimeMillis();
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            LOGGER.info("War Project minimap overlay is drawing inside the Xaero framebuffer (target {}x{}, zoom {})",
                    renderTarget == null ? -1 : renderTarget.width,
                    renderTarget == null ? -1 : renderTarget.height,
                    transform.zoom());
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int half = Math.max(1, (int) Math.ceil(8.0D * transform.zoom()));
        XaeroWarProjectMapRenderer.DashPattern dashPattern = XaeroWarProjectMapRenderer.dashPattern(half * 2);
        int thickness = XaeroWarProjectMapRenderer.edgeThickness(half * 2);
        double reach = maxReach(renderTarget);

        drawWarzoneFills(graphics, renderPos, transform, half, reach);
        drawWarzoneEdges(graphics, renderPos, transform, half, thickness, dashPattern, reach);
        drawNodeEdges(graphics, renderPos, transform, half, thickness, dashPattern, reach);
    }

    private static void drawWarzoneFills(GuiGraphics graphics, Vec3 renderPos, FboTransform transform, int half, double reach) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationFillArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                if (!beginChunk(graphics, renderPos, transform, half, reach, pos.x, pos.z)) {
                    continue;
                }
                graphics.fill(-half, -half, half, half, color);
                graphics.pose().popPose();
            }
        }
    }

    private static void drawWarzoneEdges(GuiGraphics graphics, Vec3 renderPos, FboTransform transform, int half, int thickness, XaeroWarProjectMapRenderer.DashPattern dashPattern, double reach) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                if (!beginChunk(graphics, renderPos, transform, half, reach, pos.x, pos.z)) {
                    continue;
                }
                drawChunkEdges(graphics, half, thickness, pos.x, pos.z, color, true, dashPattern,
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x - 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x + 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z - 1),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z + 1));
                graphics.pose().popPose();
            }
        }
    }

    private static void drawNodeEdges(GuiGraphics graphics, Vec3 renderPos, FboTransform transform, int half, int thickness, XaeroWarProjectMapRenderer.DashPattern dashPattern, double reach) {
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(node.factionId());
            for (long key : node.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                if (!beginChunk(graphics, renderPos, transform, half, reach, pos.x, pos.z)) {
                    continue;
                }
                drawChunkEdges(graphics, half, thickness, pos.x, pos.z, color, false, dashPattern,
                        !node.chunks().contains(ChunkPos.asLong(pos.x - 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x + 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z - 1)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z + 1)));
                graphics.pose().popPose();
            }
        }
    }

    /**
     * Pushes a pose translated to the chunk centre using Xaero's framebuffer element projection. No
     * viewport test beyond a deliberately generous bound: the framebuffer and its mask do the clipping.
     */
    private static boolean beginChunk(GuiGraphics graphics, Vec3 renderPos, FboTransform transform, int half, double reach, int chunkX, int chunkZ) {
        double dx = (chunkX + 0.5D) * 16.0D - renderPos.x;
        double dz = (chunkZ + 0.5D) * 16.0D - renderPos.z;
        double px = (transform.ps() * dx - transform.pc() * dz) * transform.zoom();
        double py = (transform.pc() * dx + transform.ps() * dz) * transform.zoom();
        if (Math.abs(px) - half > reach || Math.abs(py) - half > reach) {
            return false;
        }
        graphics.pose().pushPose();
        graphics.pose().translate((float) px, (float) py, 0.0F);
        return true;
    }

    /**
     * Each edge becomes a band of {@code thickness} pixels that stays inside the chunk square.
     */
    private static void drawChunkEdges(GuiGraphics graphics, int half, int thickness, int chunkX, int chunkZ, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern, boolean west, boolean east, boolean north, boolean south) {
        if (west) {
            drawVerticalEdge(graphics, -half, -half + thickness, -half, half, chunkZ, half, color, dashed, dashPattern);
        }
        if (east) {
            drawVerticalEdge(graphics, half - thickness, half, -half, half, chunkZ, half, color, dashed, dashPattern);
        }
        if (north) {
            drawHorizontalEdge(graphics, -half, -half + thickness, -half, half, chunkX, half, color, dashed, dashPattern);
        }
        if (south) {
            drawHorizontalEdge(graphics, half - thickness, half, -half, half, chunkX, half, color, dashed, dashPattern);
        }
    }

    private static void drawVerticalEdge(GuiGraphics graphics, int bandStart, int bandEnd, int y1, int y2, int chunkZ, int half, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        boolean phased = dashed && y2 - y1 >= dashPattern.minimumDashedLength();
        int phase = chunkZ * half * 2;
        int runStart = Integer.MIN_VALUE;
        for (int y = y1; y < y2; y++) {
            if (!phased || dashPattern.on(phase + (y + half))) {
                if (runStart == Integer.MIN_VALUE) {
                    runStart = y;
                }
            } else if (runStart != Integer.MIN_VALUE) {
                graphics.fill(bandStart, runStart, bandEnd, y, color);
                runStart = Integer.MIN_VALUE;
            }
        }
        if (runStart != Integer.MIN_VALUE) {
            graphics.fill(bandStart, runStart, bandEnd, y2, color);
        }
    }

    private static void drawHorizontalEdge(GuiGraphics graphics, int bandStart, int bandEnd, int x1, int x2, int chunkX, int half, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        boolean phased = dashed && x2 - x1 >= dashPattern.minimumDashedLength();
        int phase = chunkX * half * 2;
        int runStart = Integer.MIN_VALUE;
        for (int x = x1; x < x2; x++) {
            if (!phased || dashPattern.on(phase + (x + half))) {
                if (runStart == Integer.MIN_VALUE) {
                    runStart = x;
                }
            } else if (runStart != Integer.MIN_VALUE) {
                graphics.fill(runStart, bandStart, x, bandEnd, color);
                runStart = Integer.MIN_VALUE;
            }
        }
        if (runStart != Integer.MIN_VALUE) {
            graphics.fill(runStart, bandStart, x2, bandEnd, color);
        }
    }

    private static double maxReach(RenderTarget renderTarget) {
        if (renderTarget == null || renderTarget.width <= 0 || renderTarget.height <= 0) {
            return Double.MAX_VALUE;
        }
        return Math.max(renderTarget.width, renderTarget.height);
    }

    private static FboTransform readTransform(MinimapElementMapRendererHandler handler) {
        try {
            if (psField == null) {
                Class<?> type = handler.getClass();
                psField = declaredField(type, "ps");
                pcField = declaredField(type, "pc");
                zoomField = declaredField(type, "zoom");
            }
            return new FboTransform(psField.getDouble(handler), pcField.getDouble(handler), zoomField.getDouble(handler));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private record FboTransform(double ps, double pc, double zoom) {
    }
}
