package com.flowingsun.war_project.client.cef;

/**
 * The part of an off screen frame that changed, tracked as a small list of rectangles.
 *
 * <p>Chromium hands the whole frame to {@code onPaint} every time it paints and reports which regions
 * actually changed through {@code dirtyRects}. Copying and uploading the whole surface for a change
 * that touches a few pixels (a caret blink, one changed digit) is the single most expensive thing this
 * backend does, so both the copy and the GL upload are restricted to the regions tracked here.
 *
 * <p>The class is deliberately free of Minecraft, CEF and AWT types: it is pure integer bookkeeping so
 * it can be reasoned about and verified on its own. It is also allocation free after construction,
 * because it runs on the render thread while the game is drawing.
 *
 * <p>All coordinates are pixels of the CEF frame, with the origin at the top left, matching both the
 * pixel buffer CEF hands out and the texture layout this backend uploads into.
 */
public final class CefPaintRegions {
    /** Upper bound on tracked rectangles; past this a full frame upload is cheaper than many calls. */
    public static final int MAX_REGIONS = 16;
    /**
     * Once the tracked area passes this share of the frame, the bookkeeping (and the number of GL
     * calls) costs more than simply uploading everything.
     */
    public static final double FULL_FRAME_SHARE = 0.5D;
    /**
     * Merging two overlapping rectangles can balloon into a box that covers mostly unchanged pixels.
     * A merge is only kept while it stays within this factor of the area it replaces.
     */
    private static final double MERGE_GROWTH_LIMIT = 2.0D;

    private final int[] boxes;
    private int count;
    private int frameWidth;
    private int frameHeight;
    private boolean full;

    public CefPaintRegions() {
        this(MAX_REGIONS);
    }

    public CefPaintRegions(int maxRegions) {
        this.boxes = new int[Math.max(1, maxRegions) * 4];
    }

    public boolean isFull() {
        return full;
    }

    public boolean isEmpty() {
        return !full && count == 0;
    }

    public int size() {
        return full ? 1 : count;
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public int x(int index) {
        return boxes[index * 4];
    }

    public int y(int index) {
        return boxes[index * 4 + 1];
    }

    public int regionWidth(int index) {
        return boxes[index * 4 + 2];
    }

    public int regionHeight(int index) {
        return boxes[index * 4 + 3];
    }

    public void clear() {
        count = 0;
        full = false;
    }

    public void markFull() {
        count = 0;
        full = true;
    }

    /**
     * Sets the frame the regions are clipped to. A size change invalidates everything that was tracked
     * for the previous size, and the caller must repaint in full.
     */
    public void setFrameSize(int width, int height) {
        if (width == frameWidth && height == frameHeight) {
            return;
        }
        frameWidth = Math.max(0, width);
        frameHeight = Math.max(0, height);
        markFull();
    }

    /** Clips one dirty rectangle to the frame and tracks it. */
    public void add(int x, int y, int width, int height) {
        if (full || width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            return;
        }
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(frameWidth, x + width);
        int bottom = Math.min(frameHeight, y + height);
        if (right <= left || bottom <= top) {
            return;
        }
        track(left, top, right - left, bottom - top);
    }

    /** Merges every region of {@code other} into this one; a full source makes this one full too. */
    public void mergeWith(CefPaintRegions other) {
        if (other == null || other.isEmpty()) {
            return;
        }
        if (full) {
            return;
        }
        if (other.full) {
            markFull();
            return;
        }
        for (int i = 0; i < other.count; i++) {
            track(other.x(i), other.y(i), other.regionWidth(i), other.regionHeight(i));
        }
    }

    /** True when this collection covers the whole given rectangle. */
    public boolean covers(int x, int y, int width, int height) {
        if (full) {
            return true;
        }
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(frameWidth, x + width);
        int bottom = Math.min(frameHeight, y + height);
        if (right <= left || bottom <= top) {
            return true;
        }
        for (int row = top; row < bottom; row++) {
            int column = left;
            while (column < right) {
                int coveredUntil = -1;
                for (int i = 0; i < count; i++) {
                    int regionTop = y(i);
                    int regionBottom = regionTop + regionHeight(i);
                    if (row < regionTop || row >= regionBottom) {
                        continue;
                    }
                    int regionLeft = x(i);
                    int regionRight = regionLeft + regionWidth(i);
                    if (regionLeft <= column && regionRight > column) {
                        coveredUntil = Math.max(coveredUntil, regionRight);
                    }
                }
                if (coveredUntil <= column) {
                    return false;
                }
                column = coveredUntil;
            }
        }
        return true;
    }

    /** Total area of the tracked regions; a full frame reports the whole frame. */
    public long area() {
        if (full) {
            return (long) frameWidth * frameHeight;
        }
        long total = 0L;
        for (int i = 0; i < count; i++) {
            total += (long) regionWidth(i) * regionHeight(i);
        }
        return total;
    }

    private void track(int left, int top, int width, int height) {
        long incoming = (long) width * height;
        for (int i = 0; i < count; i++) {
            if (!intersects(i, left, top, left + width, top + height)) {
                continue;
            }
            int mergedLeft = Math.min(x(i), left);
            int mergedTop = Math.min(y(i), top);
            int mergedRight = Math.max(x(i) + regionWidth(i), left + width);
            int mergedBottom = Math.max(y(i) + regionHeight(i), top + height);
            long mergedArea = (long) (mergedRight - mergedLeft) * (mergedBottom - mergedTop);
            long existing = (long) regionWidth(i) * regionHeight(i);
            if (mergedArea > (long) ((existing + incoming) * MERGE_GROWTH_LIMIT)) {
                // The bounding box would cover far more than the two rectangles; keep them separate.
                continue;
            }
            boxes[i * 4] = mergedLeft;
            boxes[i * 4 + 1] = mergedTop;
            boxes[i * 4 + 2] = mergedRight - mergedLeft;
            boxes[i * 4 + 3] = mergedBottom - mergedTop;
            compact(i, mergedArea);
            return;
        }
        if (count >= boxes.length / 4) {
            markFull();
            return;
        }
        boxes[count * 4] = left;
        boxes[count * 4 + 1] = top;
        boxes[count * 4 + 2] = width;
        boxes[count * 4 + 3] = height;
        count++;
        if (exceedsFullFrameShare()) {
            markFull();
        }
    }

    /**
     * After a merge the resulting rectangle may now overlap others; fold those in as well and drop any
     * rectangle that ends up contained in another, so the upload never repeats the same pixels.
     */
    private void compact(int origin, long area) {
        for (int i = 0; i < count; i++) {
            if (i == origin) {
                continue;
            }
            if (!intersects(i, x(origin), y(origin), x(origin) + regionWidth(origin),
                    y(origin) + regionHeight(origin))) {
                continue;
            }
            int mergedLeft = Math.min(x(i), x(origin));
            int mergedTop = Math.min(y(i), y(origin));
            int mergedRight = Math.max(x(i) + regionWidth(i), x(origin) + regionWidth(origin));
            int mergedBottom = Math.max(y(i) + regionHeight(i), y(origin) + regionHeight(origin));
            long mergedArea = (long) (mergedRight - mergedLeft) * (mergedBottom - mergedTop);
            if (mergedArea > (long) (area * MERGE_GROWTH_LIMIT)) {
                continue;
            }
            boxes[origin * 4] = mergedLeft;
            boxes[origin * 4 + 1] = mergedTop;
            boxes[origin * 4 + 2] = mergedRight - mergedLeft;
            boxes[origin * 4 + 3] = mergedBottom - mergedTop;
            area = mergedArea;
            // Removing may move the last rectangle onto the freed slot; if that slot is the one being
            // merged, the merged rectangle moves with it and further merges must follow it.
            origin = removeAt(i, origin);
            if (origin < 0) {
                return;
            }
            i = -1;
        }
        if (exceedsFullFrameShare()) {
            markFull();
        }
    }

    private boolean exceedsFullFrameShare() {
        long frame = (long) frameWidth * frameHeight;
        return frame > 0L && area() > (long) (frame * FULL_FRAME_SHARE);
    }

    /**
     * Drops one rectangle and returns the index the {@code keep} rectangle lives at afterwards, or -1
     * when {@code keep} was the rectangle that got dropped.
     */
    private int removeAt(int index, int keep) {
        int last = count - 1;
        if (index < 0 || index > last) {
            return keep;
        }
        if (keep == index) {
            return -1;
        }
        int movedTo = keep;
        if (index != last) {
            System.arraycopy(boxes, last * 4, boxes, index * 4, 4);
            if (keep == last) {
                movedTo = index;
            }
        }
        count = last;
        return movedTo;
    }

    private boolean intersects(int index, int left, int top, int right, int bottom) {
        int regionLeft = x(index);
        int regionTop = y(index);
        return regionLeft < right && left < regionLeft + regionWidth(index)
                && regionTop < bottom && top < regionTop + regionHeight(index);
    }
}
