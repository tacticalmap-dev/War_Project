package com.flowingsun.war_project.compat.xaero;

import com.flowingsun.war_project.WarProject;
import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.xaero.XaeroWarProjectMapRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * War Project overlay for Xaero's world map.
 *
 * <p>Everything is emitted as raw {@code POSITION_COLOR} quads at float coordinates instead of using
 * {@link GuiGraphics#fill(int, int, int, int, int)}. That method takes ints, so it can never draw a
 * line thinner than one logical pixel, and one logical pixel becomes {@code guiScale} physical
 * pixels on screen. Building the quads directly allows a sub-pixel {@link #EDGE_WIDTH} hairline and
 * keeps fills aligned to the very same coordinates as the edges.
 *
 * <p>All geometry stays in float space end to end. Rounding any axis to whole pixels makes a
 * horizontal edge start up to one pixel away from the vertical edge it should meet, and rounding the
 * overlap test to whole pixels can drop that same pixel, so corners end up open or spiked.
 */
@Mod.EventBusSubscriber(modid = WarProject.MODID, value = Dist.CLIENT)
public final class XaeroWorldMapScreenOverlay {
    private static final String XAERO_WORLD_MAP_SCREEN = "xaero.map.gui.GuiMap";
    private static final int EDGE_PRIORITY_WARZONE = 0;
    private static final int EDGE_PRIORITY_NODE = 1;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int LABEL_MAX_LENGTH = 16;
    private static final double MIN_GUI_SCALE = 0.015D;

    /**
     * Resource income row drawn under a node label: an icon followed by "+per 60 seconds", once for
     * ammo and once for fuel. Texture locations use the vanilla convention of a full
     * {@code textures/....png} path, the same shape as {@code AbstractWidget.WIDGETS_LOCATION}.
     */
    private static final ResourceLocation AMMO_ICON = ResourceLocation.fromNamespaceAndPath(WarProject.MODID, "textures/gui/ammo.png");
    private static final ResourceLocation FUEL_ICON = ResourceLocation.fromNamespaceAndPath(WarProject.MODID, "textures/gui/fuel.png");
    private static final int GAIN_ICON_TEXTURE_SIZE = 128;
    private static final int GAIN_COLOR = 0xFF7CE38B;

    /**
     * The income row is hidden below this map zoom, using Xaero's {@code scale} field, which is the
     * same figure the map UI prints as "5.16x". Base sizes are given for {@link #GAIN_ZOOM_BASE} and
     * then follow the zoom linearly, clamped so the row stays readable when zoomed out and does not
     * take over the map when zoomed in.
     */
    private static final double GAIN_MIN_ZOOM = 2.0D;
    private static final double GAIN_ZOOM_BASE = 5.0D;
    private static final double GAIN_MIN_SCALE_FACTOR = 0.7D;
    private static final double GAIN_MAX_SCALE_FACTOR = 2.2D;
    private static final int GAIN_ICON_SIZE_BASE = 14;
    private static final int GAIN_ICON_TEXT_GAP_BASE = 2;
    private static final int GAIN_ENTRY_GAP_BASE = 6;
    private static final int GAIN_ROW_GAP_BASE = 2;

    /**
     * Edge thickness in logical pixels. Values below 1.0 rely on the rasteriser: at {@code guiScale}
     * 1 a 0.5 px line covers half a pixel and therefore renders dimmer, and at higher GUI scales it
     * stays visibly narrower than the one-pixel floor {@code GuiGraphics#fill} is stuck with.
     */
    private static final float EDGE_WIDTH = 0.5F;

    /**
     * Band positions are snapped to 1/8 of a pixel so that float noise cannot split what is really
     * one edge into two different bands, which would break merging and overlap detection.
     */
    private static final float BAND_QUANTUM = 8.0F;

    /**
     * Touching segments merge. Neighbouring chunks compute a shared border from the same world
     * coordinate, so they meet exactly; the tolerance only absorbs float noise, and is far below the
     * smallest real dash gap.
     */
    private static final float MERGE_EPSILON = 0.01F;

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
        int screenWidth = event.getScreen().width;
        int screenHeight = event.getScreen().height;
        Optional<MapProjection> projectionOpt = readProjection(event.getScreen(), screenWidth, screenHeight);
        if (projectionOpt.isEmpty() || projectionOpt.get().guiScale() < MIN_GUI_SCALE) {
            return;
        }
        MapProjection projection = projectionOpt.get();
        GuiGraphics graphics = event.getGuiGraphics();
        Matrix4f matrix = graphics.pose().last().pose();

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        // The map screen may still hold an open batch on the shared tesselator builder; submit it so
        // the overlay lands on top and the builder is free for the overlay's own vertices.
        graphics.flush();

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drawWarzoneFills(buffer, matrix, projection, screenWidth, screenHeight);
        drawEdges(buffer, matrix, projection, screenWidth, screenHeight);
        BufferUploader.drawWithShader(buffer.end());

        drawLabels(graphics, projection, screenWidth, screenHeight);
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
            FloatRect rect = chunkRect(projection, chunk.x, chunk.z, chunk.x + 1, chunk.z + 1);
            if (!rect.intersects(screenWidth, screenHeight)) {
                continue;
            }
            int centerX = Math.round((rect.x1() + rect.x2()) * 0.5F);
            int centerY = Math.round((rect.y1() + rect.y2()) * 0.5F);
            drawNodeLabel(graphics, minecraft.font, node, projection, centerX, centerY);
        }
    }

    /**
     * Draws the node's name and, when the map is zoomed in far enough, its resource income, as one
     * block centred on the node. The icon row follows the map zoom so it keeps its visual weight
     * against the terrain; below {@link #GAIN_MIN_ZOOM} only the name is drawn.
     */
    private static void drawNodeLabel(GuiGraphics graphics, Font font, ClientMapState.ClientNode node, MapProjection projection, int centerX, int centerY) {
        String label = XaeroWarProjectMapRenderer.label(node.name(), node.id(), LABEL_MAX_LENGTH);
        if (label.isEmpty()) {
            return;
        }
        long ammoGain = gainAmount(node.ammoPerMinute());
        long fuelGain = gainAmount(node.fuelPerMinute());
        boolean showGains = projection.zoom() >= GAIN_MIN_ZOOM && (ammoGain > 0L || fuelGain > 0L);
        int labelHeight = font.lineHeight;
        int iconSize = gainSize(GAIN_ICON_SIZE_BASE, projection.zoom());
        int iconTextGap = gainSize(GAIN_ICON_TEXT_GAP_BASE, projection.zoom());
        int entryGap = gainSize(GAIN_ENTRY_GAP_BASE, projection.zoom());
        int rowGap = gainSize(GAIN_ROW_GAP_BASE, projection.zoom());
        int blockHeight = labelHeight + (showGains ? rowGap + Math.max(iconSize, labelHeight) : 0);
        int labelTop = Math.round(centerY - blockHeight * 0.5F);
        graphics.drawCenteredString(font, label, centerX, labelTop, LABEL_COLOR);
        if (showGains) {
            drawResourceGains(graphics, font, ammoGain, fuelGain, centerX, labelTop + labelHeight + rowGap, iconSize, iconTextGap, entryGap);
        }
    }

    /**
     * Scales one base size with the map zoom, clamped to the configured factors.
     */
    private static int gainSize(int base, double zoom) {
        double factor = Math.max(GAIN_MIN_SCALE_FACTOR, Math.min(GAIN_MAX_SCALE_FACTOR, zoom / GAIN_ZOOM_BASE));
        return Math.max(1, (int) Math.round(base * factor));
    }

    /**
     * Draws the node's income per 60 seconds under its name: each resource shows its icon followed by
     * "+amount". A resource the node does not produce is left out.
     */
    private static void drawResourceGains(GuiGraphics graphics, Font font, long ammoGain, long fuelGain, int centerX, int rowY, int iconSize, int iconTextGap, int entryGap) {
        boolean showAmmo = ammoGain > 0L;
        boolean showFuel = fuelGain > 0L;
        String ammoText = gainText(ammoGain);
        String fuelText = gainText(fuelGain);
        int ammoWidth = showAmmo ? iconSize + iconTextGap + font.width(ammoText) : 0;
        int fuelWidth = showFuel ? iconSize + iconTextGap + font.width(fuelText) : 0;
        int rowWidth = ammoWidth + fuelWidth + (showAmmo && showFuel ? entryGap : 0);
        int x = centerX - rowWidth / 2;
        if (showAmmo) {
            drawGain(graphics, font, AMMO_ICON, x, rowY, ammoText, iconSize, iconTextGap);
            x += ammoWidth + entryGap;
        }
        if (showFuel) {
            drawGain(graphics, font, FUEL_ICON, x, rowY, fuelText, iconSize, iconTextGap);
        }
    }

    private static void drawGain(GuiGraphics graphics, Font font, ResourceLocation icon, int x, int y, String text, int iconSize, int iconTextGap) {
        // The 11-argument overload is required: the shorter ones reuse the target width/height as the
        // sampled u/v size, which would cut a small corner out of the 128x128 sheet instead of
        // scaling the whole icon onto the map.
        graphics.blit(icon, x, y, iconSize, iconSize, 0.0F, 0.0F, GAIN_ICON_TEXTURE_SIZE, GAIN_ICON_TEXTURE_SIZE, GAIN_ICON_TEXTURE_SIZE, GAIN_ICON_TEXTURE_SIZE);
        graphics.drawString(font, text, x + iconSize + iconTextGap, y + (iconSize - font.lineHeight) / 2, GAIN_COLOR, true);
    }

    /**
     * Floors to whole units per 60 seconds, so the printed number is exactly what the row promises
     * and a node producing less than one unit per minute is not advertised as "+0".
     */
    private static long gainAmount(double perMinute) {
        return (long) Math.floor(Math.max(0.0D, perMinute));
    }

    private static String gainText(long amount) {
        return "+" + amount;
    }

    private static void drawWarzoneFills(BufferBuilder buffer, Matrix4f matrix, MapProjection projection, int screenWidth, int screenHeight) {
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
                FloatRect rect = chunkRect(projection, pos.x, pos.z, endChunkX, pos.z + 1);
                if (rect.intersects(screenWidth, screenHeight)) {
                    addQuad(buffer, matrix, rect.x1(), rect.y1(), rect.x2(), rect.y2(), color);
                }
            }
        }
    }

    private static void drawEdges(BufferBuilder buffer, Matrix4f matrix, MapProjection projection, int screenWidth, int screenHeight) {
        int chunkPixels = chunkPixelSize(projection);
        EdgeCollector collector = new EdgeCollector(XaeroWarProjectMapRenderer.dashPattern(chunkPixels));
        // Node edges are solid and outrank warzone edges; warzone edges are dashed.
        collectWarzoneEdges(collector, projection, screenWidth, screenHeight);
        collectNodeEdges(collector, projection, screenWidth, screenHeight);
        collector.draw(buffer, matrix);
    }

    private static void collectWarzoneEdges(EdgeCollector collector, MapProjection projection, int screenWidth, int screenHeight) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                FloatRect rect = chunkRect(projection, pos.x, pos.z, pos.x + 1, pos.z + 1);
                if (!rect.intersects(screenWidth, screenHeight)) {
                    continue;
                }
                collectChunkEdges(collector, rect, color, true,
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x - 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x + 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z - 1),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z + 1),
                        EDGE_PRIORITY_WARZONE);
            }
        }
    }

    private static void collectNodeEdges(EdgeCollector collector, MapProjection projection, int screenWidth, int screenHeight) {
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(node.factionId());
            for (long key : node.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                FloatRect rect = chunkRect(projection, pos.x, pos.z, pos.x + 1, pos.z + 1);
                if (!rect.intersects(screenWidth, screenHeight)) {
                    continue;
                }
                collectChunkEdges(collector, rect, color, false,
                        !node.chunks().contains(ChunkPos.asLong(pos.x - 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x + 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z - 1)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z + 1)),
                        EDGE_PRIORITY_NODE);
            }
        }
    }

    /**
     * Adds the requested edge of one chunk as a sub-pixel band that always stays inside the chunk
     * rect. Both axes keep their float coordinates, so an edge run reaches exactly the chunk corner
     * and meets the perpendicular edge there instead of stopping on a rounded pixel.
     */
    private static void collectChunkEdges(EdgeCollector collector, FloatRect rect, int color, boolean dashed, boolean west, boolean east, boolean north, boolean south, int priority) {
        if (west) {
            collector.addVertical(rect.x1(), rect.x1() + EDGE_WIDTH, rect.y1(), rect.y2(), color, dashed, priority);
        }
        if (east) {
            collector.addVertical(rect.x2() - EDGE_WIDTH, rect.x2(), rect.y1(), rect.y2(), color, dashed, priority);
        }
        if (north) {
            collector.addHorizontal(rect.y1(), rect.y1() + EDGE_WIDTH, rect.x1(), rect.x2(), color, dashed, priority);
        }
        if (south) {
            collector.addHorizontal(rect.y2() - EDGE_WIDTH, rect.y2(), rect.x1(), rect.x2(), color, dashed, priority);
        }
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix, float x1, float y1, float x2, float y2, int argb) {
        float left = Math.min(x1, x2);
        float right = Math.max(x1, x2);
        float top = Math.min(y1, y2);
        float bottom = Math.max(y1, y2);
        if (right <= left || bottom <= top) {
            return;
        }
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        // Same winding as GuiGraphics#fill so both paths share one cull state.
        buffer.vertex(matrix, left, bottom, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, right, bottom, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, right, top, 0.0F).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, left, top, 0.0F).color(red, green, blue, alpha).endVertex();
    }

    private static int chunkPixelSize(MapProjection projection) {
        FloatRect sample = chunkRect(projection, 0, 0, 1, 1);
        return Math.max(1, Math.round(Math.max(sample.x2() - sample.x1(), sample.y2() - sample.y1())));
    }

    private static int quantiseBand(float band) {
        return Math.round(band * BAND_QUANTUM);
    }

    /**
     * Walks a run in whole-pixel steps only to decide where the dash pattern flips, while every
     * emitted span keeps its exact float ends. An un-dashed run therefore starts and stops on the
     * true corner coordinate instead of on a rounded one.
     */
    private static void drawSpan(BufferBuilder buffer, Matrix4f matrix, boolean vertical, float bandStart, float bandEnd, float start, float end, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        if (end <= start) {
            return;
        }
        boolean solid = !dashed || end - start < dashPattern.minimumDashedLength();
        int firstStep = (int) Math.floor(start);
        int lastStep = (int) Math.ceil(end);
        boolean inRun = false;
        float runStart = 0.0F;
        for (int step = firstStep; step < lastStep; step++) {
            float stepStart = Math.max(start, step);
            float stepEnd = Math.min(end, step + 1);
            if (stepEnd <= stepStart) {
                continue;
            }
            if (solid || dashPattern.on(step)) {
                if (!inRun) {
                    runStart = stepStart;
                    inRun = true;
                }
            } else if (inRun) {
                emitSpan(buffer, matrix, vertical, bandStart, bandEnd, runStart, stepStart, color);
                inRun = false;
            }
        }
        if (inRun) {
            emitSpan(buffer, matrix, vertical, bandStart, bandEnd, runStart, end, color);
        }
    }

    private static void emitSpan(BufferBuilder buffer, Matrix4f matrix, boolean vertical, float bandStart, float bandEnd, float start, float end, int color) {
        if (end <= start) {
            return;
        }
        if (vertical) {
            addQuad(buffer, matrix, bandStart, start, bandEnd, end, color);
        } else {
            addQuad(buffer, matrix, start, bandStart, end, bandEnd, color);
        }
    }

    /**
     * Removes the parts of [start, end] that a higher priority line already painted on the very same
     * band. Comparison is exact in float space, so a lower priority line never eats the one pixel a
     * perpendicular line needs to close a corner, and never blends two colours on the same pixel.
     */
    private static List<float[]> subtractCovered(float start, float end, List<float[]> covered) {
        List<float[]> free = new ArrayList<>();
        free.add(new float[]{start, end});
        for (float[] cover : covered) {
            List<float[]> next = new ArrayList<>();
            for (float[] span : free) {
                if (cover[1] <= span[0] || cover[0] >= span[1]) {
                    next.add(span);
                    continue;
                }
                if (cover[0] > span[0]) {
                    next.add(new float[]{span[0], cover[0]});
                }
                if (cover[1] < span[1]) {
                    next.add(new float[]{cover[1], span[1]});
                }
            }
            free = next;
        }
        return free;
    }

    private static final class EdgeCollector {
        private final Map<EdgeKey, List<EdgeSegment>> segments = new HashMap<>();
        private final XaeroWarProjectMapRenderer.DashPattern dashPattern;

        private EdgeCollector(XaeroWarProjectMapRenderer.DashPattern dashPattern) {
            this.dashPattern = dashPattern;
        }

        void addVertical(float bandStart, float bandEnd, float start, float end, int color, boolean dashed, int priority) {
            add(new EdgeKey(true, quantiseBand(bandStart), quantiseBand(bandEnd), color, dashed, priority), start, end);
        }

        void addHorizontal(float bandStart, float bandEnd, float start, float end, int color, boolean dashed, int priority) {
            add(new EdgeKey(false, quantiseBand(bandStart), quantiseBand(bandEnd), color, dashed, priority), start, end);
        }

        private void add(EdgeKey key, float start, float end) {
            if (end <= start || key.bandEnd() <= key.bandStart()) {
                return;
            }
            segments.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new EdgeSegment(start, end));
        }

        void draw(BufferBuilder buffer, Matrix4f matrix) {
            Map<BandKey, List<float[]>> covered = new HashMap<>();
            List<Map.Entry<EdgeKey, List<EdgeSegment>>> entries = new ArrayList<>(segments.entrySet());
            entries.sort(Comparator
                    .<Map.Entry<EdgeKey, List<EdgeSegment>>>comparingInt(entry -> entry.getKey().priority())
                    .reversed()
                    .thenComparing(entry -> entry.getKey().vertical())
                    .thenComparingInt(entry -> entry.getKey().bandStart()));
            for (Map.Entry<EdgeKey, List<EdgeSegment>> entry : entries) {
                EdgeKey key = entry.getKey();
                List<EdgeSegment> lineSegments = entry.getValue();
                lineSegments.sort(Comparator.comparingDouble(EdgeSegment::start));
                boolean hasMerged = false;
                float mergedStart = 0.0F;
                float mergedEnd = 0.0F;
                for (EdgeSegment segment : lineSegments) {
                    if (!hasMerged) {
                        mergedStart = segment.start();
                        mergedEnd = segment.end();
                        hasMerged = true;
                    } else if (segment.start() <= mergedEnd + MERGE_EPSILON) {
                        mergedEnd = Math.max(mergedEnd, segment.end());
                    } else {
                        drawMerged(buffer, matrix, key, mergedStart, mergedEnd, covered);
                        mergedStart = segment.start();
                        mergedEnd = segment.end();
                    }
                }
                if (hasMerged) {
                    drawMerged(buffer, matrix, key, mergedStart, mergedEnd, covered);
                }
            }
        }

        private void drawMerged(BufferBuilder buffer, Matrix4f matrix, EdgeKey key, float start, float end, Map<BandKey, List<float[]>> covered) {
            float bandStart = key.bandStart() / BAND_QUANTUM;
            float bandEnd = key.bandEnd() / BAND_QUANTUM;
            List<float[]> occupied = covered.computeIfAbsent(new BandKey(key.vertical(), key.bandStart(), key.bandEnd()), ignored -> new ArrayList<>());
            for (float[] span : subtractCovered(start, end, occupied)) {
                drawSpan(buffer, matrix, key.vertical(), bandStart, bandEnd, span[0], span[1], key.color(), key.dashed(), dashPattern);
            }
            occupied.add(new float[]{start, end});
        }
    }

    private record EdgeKey(boolean vertical, int bandStart, int bandEnd, int color, boolean dashed, int priority) {
    }

    private record BandKey(boolean vertical, int bandStart, int bandEnd) {
    }

    private record EdgeSegment(float start, float end) {
    }

    private static FloatRect chunkRect(MapProjection projection, int startChunkX, int startChunkZ, int endChunkX, int endChunkZ) {
        float x1 = projectX(projection, startChunkX * 16.0D);
        float y1 = projectY(projection, startChunkZ * 16.0D);
        float x2 = projectX(projection, endChunkX * 16.0D);
        float y2 = projectY(projection, endChunkZ * 16.0D);
        if (x2 <= x1) {
            x2 = x1 + 1.0F;
        }
        if (y2 <= y1) {
            y2 = y1 + 1.0F;
        }
        return new FloatRect(x1, y1, x2, y2);
    }

    private static float projectX(MapProjection projection, double worldX) {
        return (float) (projection.screenCenterX() + (worldX - projection.cameraX()) * projection.guiScale());
    }

    private static float projectY(MapProjection projection, double worldZ) {
        return (float) (projection.screenCenterY() + (worldZ - projection.cameraZ()) * projection.guiScale());
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
            // Xaero's scale is both the world-to-pixel factor and the zoom figure the map UI prints
            // ("5.16x"), so one number drives both the income row's threshold and its size.
            return Optional.of(new MapProjection(
                    cameraXField.getDouble(screen),
                    cameraZField.getDouble(screen),
                    mapScale / screenScale,
                    mapScale,
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

    private record MapProjection(double cameraX, double cameraZ, double guiScale, double zoom, double screenCenterX, double screenCenterY) {
    }

    private record FloatRect(float x1, float y1, float x2, float y2) {
        boolean intersects(int width, int height) {
            return x2 > 0.0F && y2 > 0.0F && x1 < width && y1 < height;
        }
    }
}
