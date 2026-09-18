package com.flowingsun.war_project.compat.xaero;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.xaero.XaeroWarProjectMapRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Mod.EventBusSubscriber(modid = WarProject.MODID, value = Dist.CLIENT)
public final class XaeroWorldMapScreenOverlay {
    private static final String XAERO_WORLD_MAP_SCREEN = "xaero.map.gui.GuiMap";
    private static final int EDGE_PRIORITY_WARZONE = 0;
    private static final int EDGE_PRIORITY_NODE = 1;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int LABEL_MAX_LENGTH = 16;
    private static Field cameraXField;
    private static Field cameraZField;
    private static Field scaleField;
    private static Field screenScaleField;

    private XaeroWorldMapScreenOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!XAERO_WORLD_MAP_SCREEN.equals(event.getScreen().getClass().getName())) {
            return;
        }
        Optional<MapProjection> projectionOpt = readProjection(event.getScreen(), event.getScreen().width, event.getScreen().height);
        if (projectionOpt.isEmpty() || projectionOpt.get().guiScale() < 0.015D) {
            return;
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        drawWarzoneFills(event.getGuiGraphics(), projectionOpt.get(), event.getScreen().width, event.getScreen().height);
        drawEdges(event.getGuiGraphics(), projectionOpt.get(), event.getScreen().width, event.getScreen().height);
        drawLabels(event.getGuiGraphics(), projectionOpt.get(), event.getScreen().width, event.getScreen().height);
    }

    /**
     * Node names are drawn at the geometric centre of the node's chunks.
     */
    private static void drawLabels(GuiGraphics graphics, MapProjection projection, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            String label = XaeroWarProjectMapRenderer.label(node.name(), node.id(), LABEL_MAX_LENGTH);
            if (label.isEmpty()) {
                continue;
            }
            double[] center = XaeroWarProjectMapRenderer.geometricCenter(node.chunks());
            if (center == null) {
                continue;
            }
            ChunkPos chunk = new ChunkPos((int) Math.floor(center[0]) >> 4, (int) Math.floor(center[1]) >> 4);
            Rect rect = chunkRect(projection, chunk.x, chunk.z, chunk.x + 1, chunk.z + 1);
            if (!rect.intersects(screenWidth, screenHeight)) {
                continue;
            }
            int centerX = (rect.x1() + rect.x2()) / 2;
            int centerY = (rect.y1() + rect.y2()) / 2;
            graphics.drawCenteredString(minecraft.font, label, centerX, centerY - 4, LABEL_COLOR);
        }
    }

    private static void drawWarzoneFills(GuiGraphics graphics, MapProjection projection, int screenWidth, int screenHeight) {
        Set<Long> drawn = new HashSet<>();
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationFillArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                if (drawn.contains(key)) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(key);
                int endChunkX = pos.x + 1;
                drawn.add(key);
                while (warzone.chunks().contains(ChunkPos.asLong(endChunkX, pos.z)) && !drawn.contains(ChunkPos.asLong(endChunkX, pos.z))) {
                    drawn.add(ChunkPos.asLong(endChunkX, pos.z));
                    endChunkX++;
                }
                drawChunkRun(graphics, projection, pos.x, endChunkX, pos.z, color, screenWidth, screenHeight);
            }
        }
    }

    private static void drawEdges(GuiGraphics graphics, MapProjection projection, int screenWidth, int screenHeight) {
        int chunkPixels = chunkPixelSize(projection);
        XaeroWarProjectMapRenderer.DashPattern dashPattern = XaeroWarProjectMapRenderer.dashPattern(chunkPixels);
        int thickness = XaeroWarProjectMapRenderer.edgeThickness(chunkPixels);
        EdgeCollector collector = new EdgeCollector(dashPattern);
        // Node edges are solid and outrank warzone edges; warzone edges are dashed.
        collectWarzoneEdges(collector, projection, thickness, screenWidth, screenHeight);
        collectNodeEdges(collector, projection, thickness, screenWidth, screenHeight);
        collector.draw(graphics);
    }

    private static void collectWarzoneEdges(EdgeCollector collector, MapProjection projection, int thickness, int screenWidth, int screenHeight) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                Rect rect = chunkRect(projection, pos.x, pos.z, pos.x + 1, pos.z + 1);
                if (!rect.intersects(screenWidth, screenHeight)) {
                    continue;
                }
                collectChunkEdges(collector, rect, color, true, thickness,
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x - 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x + 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z - 1),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z + 1),
                        EDGE_PRIORITY_WARZONE);
            }
        }
    }

    private static void collectNodeEdges(EdgeCollector collector, MapProjection projection, int thickness, int screenWidth, int screenHeight) {
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(node.factionId());
            for (long key : node.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                Rect rect = chunkRect(projection, pos.x, pos.z, pos.x + 1, pos.z + 1);
                if (!rect.intersects(screenWidth, screenHeight)) {
                    continue;
                }
                collectChunkEdges(collector, rect, color, false, thickness,
                        !node.chunks().contains(ChunkPos.asLong(pos.x - 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x + 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z - 1)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z + 1)),
                        EDGE_PRIORITY_NODE);
            }
        }
    }

    private static void drawChunkRun(GuiGraphics graphics, MapProjection projection, int startChunkX, int endChunkX, int chunkZ, int color, int screenWidth, int screenHeight) {
        Rect rect = fillRect(projection, startChunkX, chunkZ, endChunkX, chunkZ + 1);
        if (rect.intersects(screenWidth, screenHeight)) {
            graphics.fill(rect.x1(), rect.y1(), rect.x2(), rect.y2(), color);
        }
    }

    /**
     * Adds the requested edge of one chunk as a band of {@code thickness} pixels that always stays inside the chunk rect.
     */
    private static void collectChunkEdges(EdgeCollector collector, Rect rect, int color, boolean dashed, int thickness, boolean west, boolean east, boolean north, boolean south, int priority) {
        if (west) {
            collector.addVertical(rect.x1(), rect.x1() + thickness, rect.y1(), rect.y2(), color, dashed, priority);
        }
        if (east) {
            collector.addVertical(rect.x2() - thickness, rect.x2(), rect.y1(), rect.y2(), color, dashed, priority);
        }
        if (north) {
            collector.addHorizontal(rect.y1(), rect.y1() + thickness, rect.x1(), rect.x2(), color, dashed, priority);
        }
        if (south) {
            collector.addHorizontal(rect.y2() - thickness, rect.y2(), rect.x1(), rect.x2(), color, dashed, priority);
        }
    }

    private static void drawVerticalLineAvoiding(GuiGraphics graphics, int bandStart, int bandEnd, int y1, int y2, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern, Set<Long> reservedPixels) {
        int runStart = Integer.MIN_VALUE;
        for (int y = y1; y < y2; y++) {
            boolean drawPixel = !isBandReserved(reservedPixels, true, bandStart, bandEnd, y)
                    && (!dashed || y2 - y1 < dashPattern.minimumDashedLength() || dashPattern.on(y));
            if (drawPixel) {
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
        reserveBand(reservedPixels, true, bandStart, bandEnd, y1, y2);
    }

    private static void drawHorizontalLineAvoiding(GuiGraphics graphics, int bandStart, int bandEnd, int x1, int x2, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern, Set<Long> reservedPixels) {
        int runStart = Integer.MIN_VALUE;
        for (int x = x1; x < x2; x++) {
            boolean drawPixel = !isBandReserved(reservedPixels, false, bandStart, bandEnd, x)
                    && (!dashed || x2 - x1 < dashPattern.minimumDashedLength() || dashPattern.on(x));
            if (drawPixel) {
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
        reserveBand(reservedPixels, false, bandStart, bandEnd, x1, x2);
    }

    /**
     * Vertical bands span x = [bandStart, bandEnd) and vary with y; horizontal bands span y and vary with x.
     * The reserved pixel is always keyed as (x, y).
     */
    private static boolean isBandReserved(Set<Long> reservedPixels, boolean vertical, int bandStart, int bandEnd, int parallelCoordinate) {
        for (int offset = bandStart; offset < bandEnd; offset++) {
            long pixel = vertical ? packedPixel(offset, parallelCoordinate) : packedPixel(parallelCoordinate, offset);
            if (reservedPixels.contains(pixel)) {
                return true;
            }
        }
        return false;
    }

    private static void reserveBand(Set<Long> reservedPixels, boolean vertical, int bandStart, int bandEnd, int start, int end) {
        for (int parallelCoordinate = start; parallelCoordinate < end; parallelCoordinate++) {
            for (int offset = bandStart; offset < bandEnd; offset++) {
                reservedPixels.add(vertical ? packedPixel(offset, parallelCoordinate) : packedPixel(parallelCoordinate, offset));
            }
        }
    }

    private static int chunkPixelSize(MapProjection projection) {
        Rect sample = chunkRect(projection, 0, 0, 1, 1);
        return Math.max(1, Math.max(sample.x2() - sample.x1(), sample.y2() - sample.y1()));
    }

    private static long packedPixel(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }

    private static final class EdgeCollector {
        private final Map<EdgeKey, List<EdgeSegment>> segments = new HashMap<>();
        private final XaeroWarProjectMapRenderer.DashPattern dashPattern;

        private EdgeCollector(XaeroWarProjectMapRenderer.DashPattern dashPattern) {
            this.dashPattern = dashPattern;
        }

        void addVertical(int bandStart, int bandEnd, int y1, int y2, int color, boolean dashed, int priority) {
            add(new EdgeKey(true, bandStart, Math.max(bandStart + 1, bandEnd), color, dashed, priority), y1, y2);
        }

        void addHorizontal(int bandStart, int bandEnd, int x1, int x2, int color, boolean dashed, int priority) {
            add(new EdgeKey(false, bandStart, Math.max(bandStart + 1, bandEnd), color, dashed, priority), x1, x2);
        }

        private void add(EdgeKey key, int start, int end) {
            if (end <= start) {
                return;
            }
            segments.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new EdgeSegment(start, end));
        }

        void draw(GuiGraphics graphics) {
            Set<Long> reservedPixels = new HashSet<>();
            List<Map.Entry<EdgeKey, List<EdgeSegment>>> entries = new ArrayList<>(segments.entrySet());
            entries.sort(Comparator
                    .<Map.Entry<EdgeKey, List<EdgeSegment>>>comparingInt(entry -> entry.getKey().priority())
                    .reversed()
                    .thenComparing(entry -> entry.getKey().vertical())
                    .thenComparingInt(entry -> entry.getKey().bandStart()));
            for (Map.Entry<EdgeKey, List<EdgeSegment>> entry : entries) {
                EdgeKey key = entry.getKey();
                List<EdgeSegment> lineSegments = entry.getValue();
                lineSegments.sort(Comparator.comparingInt(EdgeSegment::start));
                int mergedStart = Integer.MIN_VALUE;
                int mergedEnd = Integer.MIN_VALUE;
                for (EdgeSegment segment : lineSegments) {
                    if (mergedStart == Integer.MIN_VALUE) {
                        mergedStart = segment.start();
                        mergedEnd = segment.end();
                    } else if (segment.start() <= mergedEnd + 1) {
                        mergedEnd = Math.max(mergedEnd, segment.end());
                    } else {
                        drawMerged(graphics, key, mergedStart, mergedEnd, reservedPixels);
                        mergedStart = segment.start();
                        mergedEnd = segment.end();
                    }
                }
                if (mergedStart != Integer.MIN_VALUE) {
                    drawMerged(graphics, key, mergedStart, mergedEnd, reservedPixels);
                }
            }
        }

        private void drawMerged(GuiGraphics graphics, EdgeKey key, int start, int end, Set<Long> reservedPixels) {
            if (key.vertical()) {
                drawVerticalLineAvoiding(graphics, key.bandStart(), key.bandEnd(), start, end, key.color(), key.dashed(), dashPattern, reservedPixels);
            } else {
                drawHorizontalLineAvoiding(graphics, key.bandStart(), key.bandEnd(), start, end, key.color(), key.dashed(), dashPattern, reservedPixels);
            }
        }
    }

    private record EdgeKey(boolean vertical, int bandStart, int bandEnd, int color, boolean dashed, int priority) {
    }

    private record EdgeSegment(int start, int end) {
    }

    private static Rect chunkRect(MapProjection projection, int startChunkX, int startChunkZ, int endChunkX, int endChunkZ) {
        int x1 = (int) Math.floor(projection.screenCenterX() + (startChunkX * 16.0D - projection.cameraX()) * projection.guiScale());
        int y1 = (int) Math.floor(projection.screenCenterY() + (startChunkZ * 16.0D - projection.cameraZ()) * projection.guiScale());
        int x2 = (int) Math.ceil(projection.screenCenterX() + (endChunkX * 16.0D - projection.cameraX()) * projection.guiScale());
        int y2 = (int) Math.ceil(projection.screenCenterY() + (endChunkZ * 16.0D - projection.cameraZ()) * projection.guiScale());
        if (x2 <= x1) {
            x2 = x1 + 1;
        }
        if (y2 <= y1) {
            y2 = y1 + 1;
        }
        return new Rect(x1, y1, x2, y2);
    }

    private static Rect fillRect(MapProjection projection, int startChunkX, int startChunkZ, int endChunkX, int endChunkZ) {
        int x1 = (int) Math.floor(projection.screenCenterX() + (startChunkX * 16.0D - projection.cameraX()) * projection.guiScale());
        int y1 = (int) Math.floor(projection.screenCenterY() + (startChunkZ * 16.0D - projection.cameraZ()) * projection.guiScale());
        int x2 = (int) Math.floor(projection.screenCenterX() + (endChunkX * 16.0D - projection.cameraX()) * projection.guiScale());
        int y2 = (int) Math.floor(projection.screenCenterY() + (endChunkZ * 16.0D - projection.cameraZ()) * projection.guiScale());
        if (x2 <= x1) {
            x2 = x1 + 1;
        }
        if (y2 <= y1) {
            y2 = y1 + 1;
        }
        return new Rect(x1, y1, x2, y2);
    }

    private static Optional<MapProjection> readProjection(Object screen, int screenWidth, int screenHeight) {
        try {
            if (cameraXField == null) {
                cameraXField = declaredField(screen.getClass(), "cameraX");
                cameraZField = declaredField(screen.getClass(), "cameraZ");
                scaleField = declaredField(screen.getClass(), "scale");
                screenScaleField = declaredField(screen.getClass(), "screenScale");
            }
            double screenScale = screenScaleField.getDouble(screen);
            if (screenScale <= 0.0D) {
                return Optional.empty();
            }
            double mapScale = scaleField.getDouble(screen);
            return Optional.of(new MapProjection(
                    cameraXField.getDouble(screen),
                    cameraZField.getDouble(screen),
                    mapScale / screenScale,
                    screenWidth / 2.0D,
                    screenHeight / 2.0D));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
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

    private record MapProjection(double cameraX, double cameraZ, double guiScale, double screenCenterX, double screenCenterY) {
    }

    private record Rect(int x1, int y1, int x2, int y2) {
        boolean intersects(int width, int height) {
            return x2 > 0 && y2 > 0 && x1 < width && y1 < height;
        }
    }
}
