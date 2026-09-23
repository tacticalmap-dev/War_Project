package com.flowingsun.war_project.client.cef;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone verification for {@link CefPaintRegions}, the damage tracking behind the incremental
 * Chromium uploads. Run it with {@code scripts/verify-cef-paint-regions.sh}.
 *
 * <p>It is deliberately outside {@code src/test}: the class under test has no dependencies at all, so
 * compiling the one file and this one proves the bookkeeping without booting Minecraft. The important
 * property is coverage: whatever rectangles are reported as damaged must still be covered after
 * merging, otherwise an incremental upload would leave stale pixels on screen.
 */
public final class CefPaintRegionsCheck {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        int frameW = 824; // 206 GUI px at GUI scale 4
        int frameH = 784; // 196 GUI px at GUI scale 4

        CefPaintRegions regions = new CefPaintRegions();
        regions.setFrameSize(frameW, frameH);
        check("fresh instance starts full after a size change", regions.isFull());
        regions.clear();
        check("cleared instance is empty", regions.isEmpty());

        regions.add(10, 20, 30, 40);
        check("single rect size", regions.size() == 1);
        check("single rect geometry", regions.x(0) == 10 && regions.y(0) == 20
                && regions.regionWidth(0) == 30 && regions.regionHeight(0) == 40);
        check("single rect area", regions.area() == 30L * 40L);

        regions.add(35, 45, 30, 40);
        check("overlapping rects merge", regions.size() == 1);
        check("merged box geometry", regions.x(0) == 10 && regions.y(0) == 20
                && regions.regionWidth(0) == 55 && regions.regionHeight(0) == 65);

        CefPaintRegions clipped = new CefPaintRegions();
        clipped.setFrameSize(100, 50);
        clipped.clear();
        clipped.add(-20, -10, 30, 20);
        check("clipped geometry", clipped.size() == 1 && clipped.x(0) == 0 && clipped.y(0) == 0
                && clipped.regionWidth(0) == 10 && clipped.regionHeight(0) == 10);
        clipped.clear();
        clipped.add(120, 0, 10, 10);
        check("fully outside rect is dropped", clipped.isEmpty());

        CefPaintRegions whole = new CefPaintRegions();
        whole.setFrameSize(64, 64);
        whole.clear();
        whole.add(0, 0, 64, 64);
        check("full frame rect degrades to full", whole.isFull());

        CefPaintRegions many = new CefPaintRegions();
        many.setFrameSize(frameW, frameH);
        many.clear();
        Random random = new Random(20260920L);
        for (int i = 0; i < 40; i++) {
            many.add(random.nextInt(frameW - 40), random.nextInt(frameH - 40), 32, 32);
        }
        check("scattered rects stay bounded", many.isFull() || many.size() <= CefPaintRegions.MAX_REGIONS);

        Random traffic = new Random(7L);
        long misses = 0L;
        long dirtyPixels = 0L;
        long uploadedPixels = 0L;
        for (int round = 0; round < 200; round++) {
            CefPaintRegions live = new CefPaintRegions();
            live.setFrameSize(frameW, frameH);
            live.clear();
            List<int[]> added = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int x = traffic.nextInt(frameW - 200);
                int y = traffic.nextInt(frameH - 200);
                int w = 8 + traffic.nextInt(160);
                int h = 8 + traffic.nextInt(120);
                live.add(x, y, w, h);
                added.add(new int[]{x, y, w, h});
                if (live.isFull()) {
                    break;
                }
            }
            for (int[] rect : added) {
                if (!live.covers(rect[0], rect[1], rect[2], rect[3])) {
                    misses++;
                }
            }
            if (!live.isFull()) {
                for (int[] rect : added) {
                    dirtyPixels += (long) rect[2] * rect[3];
                }
                uploadedPixels += live.area();
            }
        }
        check("no stale pixels across random traffic (misses=" + misses + ")", misses == 0L);
        System.out.printf("merge overhead: dirty=%.1f Mpx tracked=%.1f Mpx (x%.2f)%n",
                dirtyPixels / 1e6, uploadedPixels / 1e6, (double) uploadedPixels / Math.max(1L, dirtyPixels));

        CefPaintRegions left = new CefPaintRegions();
        left.setFrameSize(frameW, frameH);
        left.clear();
        left.add(0, 0, 10, 10);
        CefPaintRegions right = new CefPaintRegions();
        right.setFrameSize(frameW, frameH);
        right.clear();
        right.add(500, 500, 20, 20);
        left.mergeWith(right);
        check("mergeWith keeps both", left.size() == 2
                && left.covers(0, 0, 10, 10) && left.covers(500, 500, 20, 20));
        right.markFull();
        left.mergeWith(right);
        check("mergeWith propagates full", left.isFull());

        CefPaintRegions caret = new CefPaintRegions();
        caret.setFrameSize(frameW, frameH);
        caret.clear();
        caret.add(395, 12, 2, 14);
        System.out.printf("caret-only frame: %d region(s), %d px (a full frame is %d px)%n",
                caret.size(), caret.area(), frameW * frameH);

        CefPaintRegions bench = new CefPaintRegions();
        bench.setFrameSize(frameW, frameH);
        int iterations = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            bench.clear();
            bench.add(300 + (i & 7), 200 + (i & 15), 16, 8);
            bench.add(500, 300, 40, 20);
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("bookkeeping cost: %d clear+2 add cycles in %.1f ms (%.3f us each)%n",
                iterations, elapsed / 1e6, elapsed / 1e3 / iterations);

        if (failures.isEmpty()) {
            System.out.println("ALL " + checks + " CHECKS PASSED");
        } else {
            System.out.println("FAILED " + failures.size() + " of " + checks);
            failures.forEach(failure -> System.out.println("  FAIL: " + failure));
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(label);
        }
    }
}
