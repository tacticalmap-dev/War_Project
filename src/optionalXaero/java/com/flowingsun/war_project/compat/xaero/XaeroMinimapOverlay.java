package com.flowingsun.war_project.compat.xaero;

import com.flowingsun.war_project.client.ClientMapState;
import com.flowingsun.war_project.client.VpStarIcon;
import com.flowingsun.war_project.client.xaero.XaeroWarProjectMapRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

import java.lang.reflect.Field;
import java.util.function.IntPredicate;

/**
 * Draws the node / warzone overlay and the node labels on the Xaero minimap, on the screen layer.
 *
 * <p>Node and warzone islands are drawn at their real position without any "should I render this"
 * decision; whatever falls outside the minimap is clipped afterwards against the minimap viewport
 * ({@code +/-specW x +/-specH} for the square shape, radius {@code specW} for the circle - the same
 * bounds Xaero itself clamps against). The projection is the one Xaero uses for over-map elements:
 * {@code px = ps*dx*zoom - pc*dz*zoom}, {@code py = pc*dx*zoom + ps*dz*zoom}, with the pose origin at
 * the minimap centre.
 *
 * <p>Labels stay on this same layer so they are upright and keep a readable size.
 */
public final class XaeroMinimapOverlay {
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int LABEL_MAX_LENGTH = 14;
    /** Everything outside the map area; slightly stronger than the world map's haze. */
    private static final int OUT_OF_BOUNDS_COLOR = 0x44FF3B30;
    private static final float LABEL_SCALE = 2.0F;
    /** Star under a VP node's name, in minimap-local pixels before {@link #LABEL_SCALE} is applied. */
    private static final int VP_STAR_SIZE = 8;
    private static final double LABEL_EDGE_MARGIN = 10.0D;
    private static Field psField;
    private static Field pcField;
    private static Field zoomField;
    private static Field specWField;
    private static Field specHField;
    private static Field circleField;

    private XaeroMinimapOverlay() {
    }

    public static void renderWarProjectOverlay(GuiGraphics graphics, MinimapElementOverMapRendererHandler handler, Vec3 renderPos) {
        MinimapProjection projection = readProjection(handler);
        if (projection == null || projection.zoom() <= 0.0D) {
            return;
        }
        Viewport viewport = projection.viewport();
        if (viewport.isEmpty()) {
            return;
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int half = Math.max(1, (int) Math.ceil(8.0D * projection.zoom()));
        XaeroWarProjectMapRenderer.DashPattern dashPattern = XaeroWarProjectMapRenderer.dashPattern(half * 2);
        int thickness = XaeroWarProjectMapRenderer.edgeThickness(half * 2);

        // The haze is always a screen layer pass: it sits above whatever the framebuffer path drew.
        drawOutOfBoundsShade(graphics, renderPos, projection, viewport);

        // The framebuffer path draws the shapes as part of the map texture, which keeps them aligned with
        // the terrain. This screen pass then only adds the labels, plus the shapes as a fallback for when
        // Xaero is not using its FBO renderer at all (safe mode), so nothing is drawn twice.
        if (!XaeroMinimapFramebufferOverlay.isActiveRecently()) {
            drawWarzoneFills(graphics, renderPos, projection, viewport, half);
            drawWarzoneEdges(graphics, renderPos, projection, viewport, half, thickness, dashPattern);
            drawNodeEdges(graphics, renderPos, projection, viewport, half, thickness, dashPattern);
        }
        drawLabels(graphics, renderPos, projection);
    }

    /**
     * Shades everything outside the map area. The minimap is rotated by the player's facing, so the map
     * rectangle projects to a rotated quad: every viewport row is scanned for the row's inside span and
     * the two remaining spans are shaded. A view that cannot see the rectangle at all is covered in one
     * clipped fill instead.
     */
    private static void drawOutOfBoundsShade(GuiGraphics graphics, Vec3 renderPos, MinimapProjection projection, Viewport viewport) {
        ClientMapState.ClientBounds bounds = ClientMapState.bounds();
        if (bounds == null || viewport.isEmpty()) {
            return;
        }
        double[] cornerMinMin = projection.project(renderPos, bounds.minBlockX(), bounds.minBlockZ());
        double[] cornerMaxMin = projection.project(renderPos, bounds.maxBlockX(), bounds.minBlockZ());
        double[] cornerMaxMax = projection.project(renderPos, bounds.maxBlockX(), bounds.maxBlockZ());
        double[] cornerMinMax = projection.project(renderPos, bounds.minBlockX(), bounds.maxBlockZ());
        double[][] quad = {cornerMinMin, cornerMaxMin, cornerMaxMax, cornerMinMax};

        double minX = Math.min(Math.min(cornerMinMin[0], cornerMaxMin[0]), Math.min(cornerMaxMax[0], cornerMinMax[0]));
        double maxX = Math.max(Math.max(cornerMinMin[0], cornerMaxMin[0]), Math.max(cornerMaxMax[0], cornerMinMax[0]));
        double minY = Math.min(Math.min(cornerMinMin[1], cornerMaxMin[1]), Math.min(cornerMaxMax[1], cornerMinMax[1]));
        double maxY = Math.max(Math.max(cornerMinMin[1], cornerMaxMin[1]), Math.max(cornerMaxMax[1], cornerMinMax[1]));
        double halfSpan = Math.max(maxX - minX, maxY - minY) * 0.5D;
        if (!viewport.intersectsBlock((minX + maxX) * 0.5D, (minY + maxY) * 0.5D, (int) Math.ceil(halfSpan))) {
            fillClipped(graphics, viewport, viewport.xMinAt(0) - 1, viewport.yMin(),
                    viewport.xMaxAt(0) + 1, viewport.yMax(), OUT_OF_BOUNDS_COLOR, null);
            return;
        }

        for (int y = viewport.yMin(); y < viewport.yMax(); y++) {
            if (!viewport.rowVisible(y)) {
                continue;
            }
            int xs = viewport.xMinAt(y);
            int xe = viewport.xMaxAt(y);
            if (xe <= xs) {
                continue;
            }
            // Inside span of this row; xe + 1 stays a sentinel meaning "the map area misses this row".
            int insideX1 = xe + 1;
            int insideX2 = xe + 1;
            double rowY = y + 0.5D;
            double left = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                double[] a = quad[i];
                double[] b = quad[(i + 1) % 4];
                if ((a[1] <= rowY) == (b[1] <= rowY)) {
                    continue;
                }
                double t = (rowY - a[1]) / (b[1] - a[1]);
                double x = a[0] + t * (b[0] - a[0]);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
            if (left <= right) {
                insideX1 = (int) Math.ceil(left);
                insideX2 = (int) Math.floor(right) + 1;
            }
            if (insideX1 > xs) {
                graphics.fill(xs, y, Math.min(insideX1, xe), y + 1, OUT_OF_BOUNDS_COLOR);
            }
            if (insideX2 < xe) {
                graphics.fill(Math.max(insideX2, xs), y, xe, y + 1, OUT_OF_BOUNDS_COLOR);
            }
        }
    }

    private static void drawWarzoneFills(GuiGraphics graphics, Vec3 renderPos, MinimapProjection projection, Viewport viewport, int half) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationFillArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                if (!beginChunk(graphics, renderPos, projection, viewport, half, pos.x, pos.z)) {
                    continue;
                }
                fillClipped(graphics, viewport, -half, -half, half, half, color, null);
                graphics.pose().popPose();
            }
        }
    }

    private static void drawWarzoneEdges(GuiGraphics graphics, Vec3 renderPos, MinimapProjection projection, Viewport viewport, int half, int thickness, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        for (ClientMapState.ClientWarzone warzone : ClientMapState.warzones().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(warzone.factionId());
            for (long key : warzone.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                if (!beginChunk(graphics, renderPos, projection, viewport, half, pos.x, pos.z)) {
                    continue;
                }
                drawChunkEdges(graphics, viewport, half, thickness, pos.x, pos.z, color, true, dashPattern,
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x - 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x + 1, pos.z),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z - 1),
                        XaeroWarProjectMapRenderer.shouldDrawWarzoneEdge(warzone, pos.x, pos.z + 1));
                graphics.pose().popPose();
            }
        }
    }

    private static void drawNodeEdges(GuiGraphics graphics, Vec3 renderPos, MinimapProjection projection, Viewport viewport, int half, int thickness, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        for (ClientMapState.ClientNode node : ClientMapState.nodes().values()) {
            int color = XaeroWarProjectMapRenderer.relationEdgeArgb(node.factionId());
            for (long key : node.chunks()) {
                ChunkPos pos = new ChunkPos(key);
                if (!beginChunk(graphics, renderPos, projection, viewport, half, pos.x, pos.z)) {
                    continue;
                }
                drawChunkEdges(graphics, viewport, half, thickness, pos.x, pos.z, color, false, dashPattern,
                        !node.chunks().contains(ChunkPos.asLong(pos.x - 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x + 1, pos.z)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z - 1)),
                        !node.chunks().contains(ChunkPos.asLong(pos.x, pos.z + 1)));
                graphics.pose().popPose();
            }
        }
    }

    private static void drawLabels(GuiGraphics graphics, Vec3 renderPos, MinimapProjection projection) {
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
            double[] local = projection.project(renderPos, center[0], center[1]);
            if (!projection.labelFits(local[0], local[1])) {
                continue;
            }
            graphics.pose().pushPose();
            graphics.pose().translate((float) local[0], (float) local[1], 0.0F);
            // The minimap is small, so its labels are scaled up from the default font size.
            graphics.pose().scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
            int labelY = Math.round(-4.0F / LABEL_SCALE);
            graphics.drawCenteredString(minecraft.font, label, 0, labelY, LABEL_COLOR);
            if (node.vp()) {
                // Same star as the world map, one line under the name, drawn inside the scaled pose.
                VpStarIcon.draw(graphics, node.factionId(), 0, labelY + minecraft.font.lineHeight + 1, VP_STAR_SIZE);
            }
            graphics.pose().popPose();
        }
    }

    /**
     * Pushes a pose translated to the chunk centre. The only skip is a chunk that cannot touch the
     * viewport at all, so partially visible chunks are still drawn and clipped afterwards.
     */
    private static boolean beginChunk(GuiGraphics graphics, Vec3 renderPos, MinimapProjection projection, Viewport viewport, int half, int chunkX, int chunkZ) {
        double[] local = projection.projectChunk(renderPos, chunkX, chunkZ);
        if (!viewport.intersectsBlock(local[0], local[1], half)) {
            return false;
        }
        graphics.pose().pushPose();
        graphics.pose().translate((float) local[0], (float) local[1], 0.0F);
        return true;
    }

    /**
     * Each edge becomes a band of {@code thickness} pixels that stays inside the chunk square.
     */
    private static void drawChunkEdges(GuiGraphics graphics, Viewport viewport, int half, int thickness, int chunkX, int chunkZ, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern, boolean west, boolean east, boolean north, boolean south) {
        if (west) {
            drawVerticalEdge(graphics, viewport, -half, -half + thickness, -half, half, chunkZ, half, color, dashed, dashPattern);
        }
        if (east) {
            drawVerticalEdge(graphics, viewport, half - thickness, half, -half, half, chunkZ, half, color, dashed, dashPattern);
        }
        if (north) {
            drawHorizontalEdge(graphics, viewport, -half, -half + thickness, -half, half, chunkX, half, color, dashed, dashPattern);
        }
        if (south) {
            drawHorizontalEdge(graphics, viewport, half - thickness, half, -half, half, chunkX, half, color, dashed, dashPattern);
        }
    }

    private static void drawVerticalEdge(GuiGraphics graphics, Viewport viewport, int bandStart, int bandEnd, int y1, int y2, int chunkZ, int half, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        IntPredicate rowGate = null;
        if (dashed && y2 - y1 >= dashPattern.minimumDashedLength()) {
            int phase = chunkZ * half * 2;
            rowGate = y -> dashPattern.on(phase + (y + half));
        }
        fillClipped(graphics, viewport, bandStart, y1, bandEnd, y2, color, rowGate);
    }

    private static void drawHorizontalEdge(GuiGraphics graphics, Viewport viewport, int bandStart, int bandEnd, int x1, int x2, int chunkX, int half, int color, boolean dashed, XaeroWarProjectMapRenderer.DashPattern dashPattern) {
        if (!dashed || x2 - x1 < dashPattern.minimumDashedLength()) {
            fillClipped(graphics, viewport, x1, bandStart, x2, bandEnd, color, null);
            return;
        }
        int phase = chunkX * half * 2;
        int runStart = Integer.MIN_VALUE;
        for (int x = x1; x < x2; x++) {
            if (dashPattern.on(phase + (x + half))) {
                if (runStart == Integer.MIN_VALUE) {
                    runStart = x;
                }
            } else if (runStart != Integer.MIN_VALUE) {
                fillClipped(graphics, viewport, runStart, bandStart, x, bandEnd, color, null);
                runStart = Integer.MIN_VALUE;
            }
        }
        if (runStart != Integer.MIN_VALUE) {
            fillClipped(graphics, viewport, runStart, bandStart, x2, bandEnd, color, null);
        }
    }

    /**
     * Fills a rectangle clipped against the minimap viewport, in minimap-local pixels.
     *
     * <p>{@code rowGate} optionally rejects individual rows (the dashed phase on vertical edges);
     * consecutive rows sharing the same visible x range are merged into one draw call.
     */
    private static void fillClipped(GuiGraphics graphics, Viewport viewport, int x1, int y1, int x2, int y2, int color, IntPredicate rowGate) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        int yStart = Math.max(y1, viewport.yMin());
        int yEnd = Math.min(y2, viewport.yMax());
        if (yStart >= yEnd) {
            return;
        }
        if (rowGate == null && viewport.containsRect(x1, yStart, x2, yEnd)) {
            graphics.fill(x1, yStart, x2, yEnd, color);
            return;
        }

        int runStartY = Integer.MIN_VALUE;
        int runEndY = 0;
        int runX1 = 0;
        int runX2 = 0;
        for (int y = yStart; y < yEnd; y++) {
            boolean visible = viewport.rowVisible(y) && (rowGate == null || rowGate.test(y));
            int xs = 0;
            int xe = 0;
            if (visible) {
                xs = Math.max(x1, viewport.xMinAt(y));
                xe = Math.min(x2, viewport.xMaxAt(y));
                visible = xs < xe;
            }
            if (!visible) {
                runStartY = flushRun(graphics, color, runStartY, runEndY, runX1, runX2);
                continue;
            }
            if (runStartY != Integer.MIN_VALUE && y == runEndY && xs == runX1 && xe == runX2) {
                runEndY = y + 1;
            } else {
                runStartY = flushRun(graphics, color, runStartY, runEndY, runX1, runX2);
                runStartY = y;
                runEndY = y + 1;
                runX1 = xs;
                runX2 = xe;
            }
        }
        flushRun(graphics, color, runStartY, runEndY, runX1, runX2);
    }

    private static int flushRun(GuiGraphics graphics, int color, int runStartY, int runEndY, int runX1, int runX2) {
        if (runStartY != Integer.MIN_VALUE && runEndY > runStartY && runX2 > runX1) {
            graphics.fill(runX1, runStartY, runX2, runEndY, color);
        }
        return Integer.MIN_VALUE;
    }

    private static MinimapProjection readProjection(MinimapElementOverMapRendererHandler handler) {
        try {
            if (psField == null) {
                Class<?> type = handler.getClass();
                psField = declaredField(type, "ps");
                pcField = declaredField(type, "pc");
                zoomField = declaredField(type, "zoom");
                specWField = declaredField(type, "specW");
                specHField = declaredField(type, "specH");
                circleField = declaredField(type, "circle");
            }
            return new MinimapProjection(
                    psField.getDouble(handler),
                    pcField.getDouble(handler),
                    zoomField.getDouble(handler),
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

    /**
     * The minimap viewport in minimap-local pixels: rectangles use {@code +/-specW x +/-specH}, the
     * circular shape uses radius {@code specW}. Ranges are half-open so they can be used as fill bounds.
     */
    private interface Viewport {
        boolean isEmpty();

        int yMin();

        int yMax();

        boolean rowVisible(int y);

        int xMinAt(int y);

        int xMaxAt(int y);

        boolean containsRect(int x1, int y1, int x2, int y2);

        boolean intersectsBlock(double centerX, double centerY, int half);
    }

    private record RectViewport(int halfW, int halfH) implements Viewport {
        @Override
        public boolean isEmpty() {
            return halfW <= 0 || halfH <= 0;
        }

        @Override
        public int yMin() {
            return -halfH;
        }

        @Override
        public int yMax() {
            return halfH;
        }

        @Override
        public boolean rowVisible(int y) {
            return y >= -halfH && y < halfH;
        }

        @Override
        public int xMinAt(int y) {
            return -halfW;
        }

        @Override
        public int xMaxAt(int y) {
            return halfW;
        }

        @Override
        public boolean containsRect(int x1, int y1, int x2, int y2) {
            return x1 >= -halfW && x2 <= halfW && y1 >= -halfH && y2 <= halfH;
        }

        @Override
        public boolean intersectsBlock(double centerX, double centerY, int half) {
            return centerX + half >= -halfW && centerX - half <= halfW
                    && centerY + half >= -halfH && centerY - half <= halfH;
        }
    }

    private record CircleViewport(int radius) implements Viewport {
        @Override
        public boolean isEmpty() {
            return radius <= 0;
        }

        @Override
        public int yMin() {
            return -radius;
        }

        @Override
        public int yMax() {
            return radius;
        }

        @Override
        public boolean rowVisible(int y) {
            return Math.abs(y) < radius;
        }

        @Override
        public int xMinAt(int y) {
            double span = spanSquared(y);
            return span <= 0.0D ? 0 : (int) Math.ceil(-Math.sqrt(span));
        }

        @Override
        public int xMaxAt(int y) {
            double span = spanSquared(y);
            return span <= 0.0D ? 0 : (int) Math.floor(Math.sqrt(span)) + 1;
        }

        @Override
        public boolean containsRect(int x1, int y1, int x2, int y2) {
            return cornerInside(x1, y1) && cornerInside(x2, y1) && cornerInside(x1, y2) && cornerInside(x2, y2);
        }

        @Override
        public boolean intersectsBlock(double centerX, double centerY, int half) {
            double nearestX = clamp(0.0D, centerX - half, centerX + half);
            double nearestY = clamp(0.0D, centerY - half, centerY + half);
            return nearestX * nearestX + nearestY * nearestY <= (double) radius * radius;
        }

        private double spanSquared(int y) {
            return (double) radius * radius - (double) y * y;
        }

        private boolean cornerInside(int x, int y) {
            return (double) x * x + (double) y * y <= (double) radius * radius;
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /**
     * Projection of the over-map element layer plus the minimap bounds.
     */
    private record MinimapProjection(double ps, double pc, double zoom, int specW, int specH, boolean circle) {
        double[] projectChunk(Vec3 renderPos, int chunkX, int chunkZ) {
            return project(renderPos, (chunkX + 0.5D) * 16.0D, (chunkZ + 0.5D) * 16.0D);
        }

        double[] project(Vec3 renderPos, double worldX, double worldZ) {
            double dx = worldX - renderPos.x;
            double dz = worldZ - renderPos.z;
            double px = (ps * dx - pc * dz) * zoom;
            double py = (pc * dx + ps * dz) * zoom;
            return new double[]{px, py};
        }

        Viewport viewport() {
            if (specW <= 0 || specH <= 0) {
                return new RectViewport(0, 0);
            }
            return circle ? new CircleViewport(specW) : new RectViewport(specW, specH);
        }

        boolean labelFits(double px, double py) {
            if (specW <= 0 || specH <= 0) {
                return false;
            }
            double margin = LABEL_EDGE_MARGIN * LABEL_SCALE;
            if (circle) {
                double limit = specW - margin;
                return limit > 0.0D && px * px + py * py <= limit * limit;
            }
            return Math.abs(px) <= specW - margin && Math.abs(py) <= specH - margin;
        }
    }
}
