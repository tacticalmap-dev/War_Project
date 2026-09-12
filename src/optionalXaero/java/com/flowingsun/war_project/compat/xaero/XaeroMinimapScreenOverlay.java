package com.flowingsun.war_project.compat.xaero;

import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.xaero.XaeroWarProjectMapRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

import java.lang.reflect.Field;

public final class XaeroMinimapScreenOverlay {
    private static Field psField;
    private static Field pcField;
    private static Field zoomField;
    private static Field halfViewWField;
    private static Field halfViewHField;
    private static Field specWField;
    private static Field specHField;
    private static Field circleField;

    private XaeroMinimapScreenOverlay() {
    }

    public static void renderWarProjectOverlay(GuiGraphics graphics, MinimapElementOverMapRendererHandler handler, Vec3 renderPos) {
        PreparedMinimapTransform transform = readTransform(handler);
        if (transform == null || transform.zoom() <= 0.0D) {
            return;
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        drawWarzoneFills(graphics, renderPos, transform);
        drawWarzoneEdges(graphics, renderPos, transform);
        drawNodeEdges(graphics, renderPos, transform);
    }

    private static void drawWarzoneFills(GuiGraphics graphics, Vec3 renderPos, PreparedMinimapTransform transform) {
        int half = chunkHalfSize(transform);
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationFillArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                graphics.pose().pushPose();
                if (translateToChunkCenter(graphics, renderPos, transform, pos.x, pos.z)) {
                    graphics.fill(-half, -half, half, half, color);
                }
                graphics.pose().popPose();
            }
        }
    }

    private static void drawWarzoneEdges(GuiGraphics graphics, Vec3 renderPos, PreparedMinimapTransform transform) {
        int half = chunkHalfSize(transform);
        DashPattern dashPattern = dashPattern(half * 2);
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                graphics.pose().pushPose();
                if (translateToChunkCenter(graphics, renderPos, transform, pos.x, pos.z)) {
                    drawChunkEdges(graphics, half, pos.x, pos.z, color, false, dashPattern,
                            XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x - 1, pos.z),
                            XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x + 1, pos.z),
                            XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z - 1),
                            XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z + 1));
                }
                graphics.pose().popPose();
            }
        }
    }

    private static void drawNodeEdges(GuiGraphics graphics, Vec3 renderPos, PreparedMinimapTransform transform) {
        int half = chunkHalfSize(transform);
        DashPattern dashPattern = dashPattern(half * 2);
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(node.factionId());
            for (long key : node.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                graphics.pose().pushPose();
                if (translateToChunkCenter(graphics, renderPos, transform, pos.x, pos.z)) {
                    drawChunkEdges(graphics, half, pos.x, pos.z, color, true, dashPattern,
                            !node.chunks().contains(ChunkPos.asLong(pos.x - 1, pos.z)),
                            !node.chunks().contains(ChunkPos.asLong(pos.x + 1, pos.z)),
                            !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z - 1)),
                            !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z + 1)));
                }
                graphics.pose().popPose();
            }
        }
    }

    private static void drawChunkEdges(GuiGraphics graphics, int half, int chunkX, int chunkZ, int color, boolean dashed, DashPattern dashPattern, boolean west, boolean east, boolean north, boolean south) {
        int westX = -half;
        int eastX = half - 1;
        int northY = -half;
        int southY = half - 1;
        if (west) {
            drawVerticalLine(graphics, westX, -half, half, chunkZ, half, color, dashed, dashPattern);
        }
        if (east) {
            drawVerticalLine(graphics, eastX, -half, half, chunkZ, half, color, dashed, dashPattern);
        }
        if (north) {
            drawHorizontalLine(graphics, -half, half, northY, chunkX, half, color, dashed, dashPattern);
        }
        if (south) {
            drawHorizontalLine(graphics, -half, half, southY, chunkX, half, color, dashed, dashPattern);
        }
    }

    private static void drawVerticalLine(GuiGraphics graphics, int x, int y1, int y2, int chunkZ, int half, int color, boolean dashed, DashPattern dashPattern) {
        int runStart = Integer.MIN_VALUE;
        for (int y = y1; y < y2; y++) {
            int worldPixel = (chunkZ * half * 2) + (y + half);
            boolean drawPixel = !dashed || y2 - y1 < dashPattern.minimumDashedLength() || dashPattern.on(worldPixel);
            if (drawPixel) {
                if (runStart == Integer.MIN_VALUE) {
                    runStart = y;
                }
            } else if (runStart != Integer.MIN_VALUE) {
                graphics.fill(x, runStart, x + 1, y, color);
                runStart = Integer.MIN_VALUE;
            }
        }
        if (runStart != Integer.MIN_VALUE) {
            graphics.fill(x, runStart, x + 1, y2, color);
        }
    }

    private static void drawHorizontalLine(GuiGraphics graphics, int x1, int x2, int y, int chunkX, int half, int color, boolean dashed, DashPattern dashPattern) {
        int runStart = Integer.MIN_VALUE;
        for (int x = x1; x < x2; x++) {
            int worldPixel = (chunkX * half * 2) + (x + half);
            boolean drawPixel = !dashed || x2 - x1 < dashPattern.minimumDashedLength() || dashPattern.on(worldPixel);
            if (drawPixel) {
                if (runStart == Integer.MIN_VALUE) {
                    runStart = x;
                }
            } else if (runStart != Integer.MIN_VALUE) {
                graphics.fill(runStart, y, x, y + 1, color);
                runStart = Integer.MIN_VALUE;
            }
        }
        if (runStart != Integer.MIN_VALUE) {
            graphics.fill(runStart, y, x2, y + 1, color);
        }
    }

    private static boolean translateToChunkCenter(GuiGraphics graphics, Vec3 renderPos, PreparedMinimapTransform transform, int chunkX, int chunkZ) {
        double centerBlockX = (chunkX + 0.5D) * 16.0D;
        double centerBlockZ = (chunkZ + 0.5D) * 16.0D;
        double[] partialTranslate = {0.0D, 0.0D};
        return !MinimapElementOverMapRendererHandler.translatePosition(
                graphics.pose(),
                transform.specW(),
                transform.specH(),
                transform.halfViewW(),
                transform.halfViewH(),
                transform.ps(),
                transform.pc(),
                centerBlockX - renderPos.x,
                centerBlockZ - renderPos.z,
                transform.zoom(),
                transform.circle(),
                partialTranslate);
    }

    private static int chunkHalfSize(PreparedMinimapTransform transform) {
        return Math.max(1, (int) Math.ceil(8.0D * transform.zoom()));
    }

    private static DashPattern dashPattern(int chunkPixels) {
        int dash = clamp((int) Math.round(chunkPixels * 0.35D), 4, 28);
        int gap = clamp((int) Math.round(chunkPixels * 0.22D), 3, 18);
        return new DashPattern(dash, gap);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static PreparedMinimapTransform readTransform(MinimapElementOverMapRendererHandler handler) {
        try {
            if (psField == null) {
                Class<?> type = handler.getClass();
                psField = declaredField(type, "ps");
                pcField = declaredField(type, "pc");
                zoomField = declaredField(type, "zoom");
                halfViewWField = declaredField(type, "halfViewW");
                halfViewHField = declaredField(type, "halfViewH");
                specWField = declaredField(type, "specW");
                specHField = declaredField(type, "specH");
                circleField = declaredField(type, "circle");
            }
            return new PreparedMinimapTransform(
                    psField.getDouble(handler),
                    pcField.getDouble(handler),
                    zoomField.getDouble(handler),
                    halfViewWField.getInt(handler),
                    halfViewHField.getInt(handler),
                    specWField.getInt(handler),
                    specHField.getInt(handler),
                    circleField.getBoolean(handler));
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

    private record PreparedMinimapTransform(double ps, double pc, double zoom, int halfViewW, int halfViewH, int specW, int specH, boolean circle) {
    }

    private record DashPattern(int dash, int gap) {
        boolean on(int coordinate) {
            return Math.floorMod(coordinate, dash + gap) < dash;
        }

        int minimumDashedLength() {
            return (dash + gap) * 2;
        }
    }
}
