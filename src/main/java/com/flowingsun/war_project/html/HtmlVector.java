package com.flowingsun.war_project.html;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Vector shapes for the HTML kernel: a small SVG subset that is rasterised the way a browser
 * rasterises it, through area coverage instead of whole pixel steps.
 *
 * <p>Supported elements: {@code <svg viewBox="...">} containing {@code path} (M L H V C S Q T Z,
 * absolute and relative), {@code circle}, {@code ellipse}, {@code rect} (with {@code rx}) and
 * {@code polygon}/{@code polyline}; solid {@code fill} plus {@code fill-opacity}. Each shape is
 * rasterised once into a white coverage texture with 4x4 supersampling and then tinted and blitted,
 * so the outline stays a true curve with no staircase at any size and no image asset is needed.
 */
public final class HtmlVector {
    private static final int SAMPLES = 4;

    private HtmlVector() {
    }

    /** True for the tags this class consumes inside an {@code <svg>} element. */
    public static boolean isShape(String tag) {
        return switch (tag) {
            case "path", "circle", "ellipse", "rect", "polygon", "polyline" -> true;
            default -> false;
        };
    }

    /** Draws every shape child of {@code svg} into the element box the layout computed. */
    public static void draw(GuiGraphics graphics, HtmlNode svg, float opacity) {
        int width = svg.width;
        int height = svg.height;
        if (width <= 0 || height <= 0 || svg.children.isEmpty()) {
            return;
        }
        // Rasterise denser than the element is drawn: Minecraft scales the GUI up, and a 1:1 outline
        // would show stair steps once it is on screen.
        int ras = HtmlTextures.rasterScale(width, height);
        int texWidth = width * ras;
        int texHeight = height * ras;
        double[] box = viewBox(svg);
        for (HtmlNode child : svg.children) {
            if (child.effectiveStyle().display.equals("none") || !isShape(child.tag)) {
                continue;
            }
            int color = fill(child);
            int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, opacity)));
            if (alpha <= 0) {
                continue;
            }
            List<double[]> subpaths = shape(child);
            if (subpaths.isEmpty()) {
                continue;
            }
            String key = "v|" + texWidth + "x" + texHeight + "|" + box[0] + "," + box[1] + "," + box[2] + "," + box[3]
                    + "|" + ras + "|" + signature(child);
            ResourceLocation texture = HtmlTextures.texture(key, texWidth, texHeight,
                    rasterize(subpaths, texWidth, texHeight, box), ras > 1);
            if (texture == null) {
                return;
            }
            int tinted = (alpha << 24) | (color & 0xFFFFFF);
            graphics.setColor(((tinted >>> 16) & 0xFF) / 255.0F, ((tinted >>> 8) & 0xFF) / 255.0F,
                    (tinted & 0xFF) / 255.0F, alpha / 255.0F);
            graphics.blit(texture, svg.x, svg.y, width, height, 0.0F, 0.0F, texWidth, texHeight, texWidth, texHeight);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static String signature(HtmlNode node) {
        StringBuilder builder = new StringBuilder(node.tag);
        for (String attribute : List.of("d", "points", "cx", "cy", "r", "rx", "ry", "x", "y", "width", "height")) {
            String value = node.attributes.get(attribute);
            if (value != null) {
                builder.append(';').append(attribute).append('=').append(value);
            }
        }
        return builder.toString();
    }

    private static int fill(HtmlNode node) {
        Integer parsed = Css.parseColor(node.attributes.getOrDefault("fill", "#ffffff"));
        int color = parsed == null ? 0xFFFFFFFF : parsed;
        float opacity = 1.0F;
        try {
            String value = node.attributes.get("fill-opacity");
            if (value != null) {
                opacity = Float.parseFloat(value.trim());
            }
        } catch (NumberFormatException ignored) {
            opacity = 1.0F;
        }
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, opacity)));
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private static double[] viewBox(HtmlNode svg) {
        String raw = svg.attributes.get("viewbox");
        if (raw != null) {
            String[] parts = raw.trim().split("[\\s,]+");
            if (parts.length == 4) {
                try {
                    return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                            Math.max(0.001D, Double.parseDouble(parts[2])), Math.max(0.001D, Double.parseDouble(parts[3]))};
                } catch (NumberFormatException ignored) {
                    // fall through to the default box
                }
            }
        }
        return new double[]{0.0D, 0.0D, Math.max(1, svg.width), Math.max(1, svg.height)};
    }

    // ---------------------------------------------------------------- element to polygon

    private static List<double[]> shape(HtmlNode node) {
        List<double[]> subpaths = new ArrayList<>();
        switch (node.tag) {
            case "path" -> PathParser.parse(node.attributes.getOrDefault("d", ""), subpaths);
            case "circle" -> addEllipse(subpaths, number(node, "cx", 0.0D), number(node, "cy", 0.0D),
                    number(node, "r", 0.0D), number(node, "r", 0.0D));
            case "ellipse" -> addEllipse(subpaths, number(node, "cx", 0.0D), number(node, "cy", 0.0D),
                    number(node, "rx", 0.0D), number(node, "ry", 0.0D));
            case "rect" -> addRect(subpaths, number(node, "x", 0.0D), number(node, "y", 0.0D),
                    number(node, "width", 0.0D), number(node, "height", 0.0D), number(node, "rx", 0.0D));
            case "polygon", "polyline" -> addPoints(subpaths, node.attributes.getOrDefault("points", ""));
            default -> {
            }
        }
        return subpaths;
    }

    private static double number(HtmlNode node, String attribute, double fallback) {
        String value = node.attributes.get(attribute);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim().replace("px", ""));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void addPoints(List<double[]> subpaths, String raw) {
        String[] parts = raw.trim().split("[\\s,]+");
        List<Double> coordinates = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            try {
                coordinates.add(Double.parseDouble(part));
            } catch (NumberFormatException ignored) {
                // skip malformed entries instead of dropping the whole polygon
            }
        }
        if (coordinates.size() < 6) {
            return;
        }
        double[] flat = new double[coordinates.size() - coordinates.size() % 2];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = coordinates.get(i);
        }
        subpaths.add(flat);
    }

    private static void addRect(List<double[]> subpaths, double x, double y, double width, double height, double radius) {
        if (width <= 0.0D || height <= 0.0D) {
            return;
        }
        double r = Math.max(0.0D, Math.min(radius, Math.min(width, height) / 2.0D));
        if (r <= 0.0D) {
            subpaths.add(new double[]{x, y, x + width, y, x + width, y + height, x, y + height});
            return;
        }
        double[] xs = new double[16];
        double[] ys = new double[16];
        int index = 0;
        double[][] corners = {
                {x + r, y + r, 180.0D, 270.0D},
                {x + width - r, y + r, 270.0D, 360.0D},
                {x + width - r, y + height - r, 0.0D, 90.0D},
                {x + r, y + height - r, 90.0D, 180.0D}};
        for (double[] corner : corners) {
            for (int step = 0; step < 4; step++) {
                double angle = Math.toRadians(corner[2] + (corner[3] - corner[2]) * step / 3.0D);
                xs[index] = corner[0] + Math.cos(angle) * r;
                ys[index] = corner[1] + Math.sin(angle) * r;
                index++;
            }
        }
        subpaths.add(interleave(xs, ys));
    }

    private static void addEllipse(List<double[]> subpaths, double cx, double cy, double rx, double ry) {
        if (rx <= 0.0D || ry <= 0.0D) {
            return;
        }
        int segments = Math.max(16, Math.min(96, (int) Math.ceil(Math.max(rx, ry) * 2.0D)));
        double[] xs = new double[segments];
        double[] ys = new double[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0D * i / segments;
            xs[i] = cx + Math.cos(angle) * rx;
            ys[i] = cy + Math.sin(angle) * ry;
        }
        subpaths.add(interleave(xs, ys));
    }

    private static double[] interleave(double[] xs, double[] ys) {
        double[] flat = new double[xs.length * 2];
        for (int i = 0; i < xs.length; i++) {
            flat[i * 2] = xs[i];
            flat[i * 2 + 1] = ys[i];
        }
        return flat;
    }

    // ---------------------------------------------------------------- rasterisation

    /** 4x4 supersampled coverage of the subpath set, in viewBox space mapped onto the element box. */
    private static float[] rasterize(List<double[]> subpaths, int width, int height, double[] box) {
        float[] values = new float[width * height];
        double scaleX = box[2] / width;
        double scaleY = box[3] / height;
        double step = 1.0D / SAMPLES;
        int total = SAMPLES * SAMPLES;
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int inside = 0;
                for (int sy = 0; sy < SAMPLES; sy++) {
                    double v = box[1] + (py + (sy + 0.5D) * step) * scaleY;
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        double u = box[0] + (px + (sx + 0.5D) * step) * scaleX;
                        if (windingInside(subpaths, u, v)) {
                            inside++;
                        }
                    }
                }
                values[py * width + px] = inside / (float) total;
            }
        }
        return values;
    }

    /** Non zero winding test, which keeps holes drawn with the opposite direction hollow. */
    private static boolean windingInside(List<double[]> subpaths, double u, double v) {
        int winding = 0;
        for (double[] points : subpaths) {
            int count = points.length / 2;
            for (int i = 0, j = count - 1; i < count; j = i++) {
                double xi = points[i * 2];
                double yi = points[i * 2 + 1];
                double xj = points[j * 2];
                double yj = points[j * 2 + 1];
                if (yi <= v) {
                    if (yj > v && (xj - xi) * (v - yi) - (u - xi) * (yj - yi) > 0.0D) {
                        winding++;
                    }
                } else if (yj <= v && (xj - xi) * (v - yi) - (u - xi) * (yj - yi) < 0.0D) {
                    winding--;
                }
            }
        }
        return winding != 0;
    }

    /** Flattens SVG path data into polygonal subpaths. Arcs are approximated by their chord. */
    private static final class PathParser {
        private final String data;
        private int index;
        private double x;
        private double y;
        private double startX;
        private double startY;
        private double lastControlX;
        private double lastControlY;
        private char previous = ' ';

        private PathParser(String data) {
            this.data = data;
        }

        static void parse(String data, List<double[]> out) {
            PathParser parser = new PathParser(data);
            try {
                parser.run(out);
            } catch (RuntimeException exception) {
                // A malformed path draws the part that parsed instead of nothing at all.
            }
        }

        private void run(List<double[]> out) {
            List<Double> current = null;
            while (index < data.length()) {
                skipSeparators();
                if (index >= data.length()) {
                    break;
                }
                char command = data.charAt(index);
                if (Character.isLetter(command)) {
                    index++;
                } else if (previous == ' ') {
                    break;
                } else {
                    command = previous == 'M' ? 'L' : previous == 'm' ? 'l' : previous;
                }
                boolean relative = Character.isLowerCase(command);
                switch (Character.toUpperCase(command)) {
                    case 'M' -> {
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        if (current != null && !current.isEmpty()) {
                            out.add(toArray(current));
                        }
                        current = new ArrayList<>();
                        current.add(nx);
                        current.add(ny);
                        x = nx;
                        y = ny;
                        startX = nx;
                        startY = ny;
                    }
                    case 'L' -> {
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        add(current, nx, ny);
                        lastControlX = nx;
                        lastControlY = ny;
                    }
                    case 'H' -> {
                        double nx = number(relative, true);
                        add(current, nx, y);
                        lastControlX = nx;
                        lastControlY = y;
                    }
                    case 'V' -> {
                        double ny = number(relative, false);
                        add(current, x, ny);
                        lastControlX = x;
                        lastControlY = ny;
                    }
                    case 'C' -> {
                        double c1x = number(relative, true);
                        double c1y = number(relative, false);
                        double c2x = number(relative, true);
                        double c2y = number(relative, false);
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        curve(current, x, y, c1x, c1y, c2x, c2y, nx, ny);
                        lastControlX = c2x;
                        lastControlY = c2y;
                        x = nx;
                        y = ny;
                    }
                    case 'S' -> {
                        double c2x = number(relative, true);
                        double c2y = number(relative, false);
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        boolean smooth = "CcSs".indexOf(previous) >= 0;
                        double c1x = smooth ? 2 * x - lastControlX : x;
                        double c1y = smooth ? 2 * y - lastControlY : y;
                        curve(current, x, y, c1x, c1y, c2x, c2y, nx, ny);
                        lastControlX = c2x;
                        lastControlY = c2y;
                        x = nx;
                        y = ny;
                    }
                    case 'Q' -> {
                        double c1x = number(relative, true);
                        double c1y = number(relative, false);
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        quadratic(current, x, y, c1x, c1y, nx, ny);
                        lastControlX = c1x;
                        lastControlY = c1y;
                        x = nx;
                        y = ny;
                    }
                    case 'T' -> {
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        boolean smooth = "QqTt".indexOf(previous) >= 0;
                        double c1x = smooth ? 2 * x - lastControlX : x;
                        double c1y = smooth ? 2 * y - lastControlY : y;
                        quadratic(current, x, y, c1x, c1y, nx, ny);
                        lastControlX = c1x;
                        lastControlY = c1y;
                        x = nx;
                        y = ny;
                    }
                    case 'A' -> {
                        // Arc parameters are consumed in order; the curve itself is approximated by
                        // the chord to the endpoint, which is enough for icon sized shapes.
                        raw();
                        raw();
                        raw();
                        raw();
                        raw();
                        double nx = number(relative, true);
                        double ny = number(relative, false);
                        add(current, nx, ny);
                        lastControlX = nx;
                        lastControlY = ny;
                    }
                    case 'Z' -> {
                        if (current != null && !current.isEmpty()) {
                            add(current, startX, startY);
                            out.add(toArray(current));
                            current = null;
                        }
                        x = startX;
                        y = startY;
                    }
                    default -> {
                        return;
                    }
                }
                previous = command;
            }
            if (current != null && !current.isEmpty()) {
                out.add(toArray(current));
            }
        }

        private void curve(List<Double> current, double x0, double y0, double c1x, double c1y,
                           double c2x, double c2y, double x1, double y1) {
            double length = distance(x0, y0, c1x, c1y) + distance(c1x, c1y, c2x, c2y) + distance(c2x, c2y, x1, y1);
            int steps = Math.max(4, Math.min(24, (int) Math.ceil(length / 1.5D)));
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                double mt = 1.0D - t;
                double px = mt * mt * mt * x0 + 3 * mt * mt * t * c1x + 3 * mt * t * t * c2x + t * t * t * x1;
                double py = mt * mt * mt * y0 + 3 * mt * mt * t * c1y + 3 * mt * t * t * c2y + t * t * t * y1;
                add(current, px, py);
            }
        }

        private void quadratic(List<Double> current, double x0, double y0, double cx, double cy, double x1, double y1) {
            double length = distance(x0, y0, cx, cy) + distance(cx, cy, x1, y1);
            int steps = Math.max(4, Math.min(24, (int) Math.ceil(length / 1.5D)));
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                double mt = 1.0D - t;
                double px = mt * mt * x0 + 2 * mt * t * cx + t * t * x1;
                double py = mt * mt * y0 + 2 * mt * t * cy + t * t * y1;
                add(current, px, py);
            }
        }

        private static double distance(double x0, double y0, double x1, double y1) {
            return Math.hypot(x1 - x0, y1 - y0);
        }

        private void add(List<Double> current, double nx, double ny) {
            if (current == null) {
                return;
            }
            current.add(nx);
            current.add(ny);
            x = nx;
            y = ny;
        }

        /** Reads one number without applying the relative offset (radii, flags). */
        private double raw() {
            skipSeparators();
            int start = index;
            while (index < data.length()) {
                char character = data.charAt(index);
                if (character == '-' || character == '+' || character == '.' || Character.isDigit(character)
                        || character == 'e' || character == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("expected a number in path data");
            }
            return Double.parseDouble(data.substring(start, index));
        }

        private double number(boolean relative, boolean horizontal) {
            skipSeparators();
            int start = index;
            while (index < data.length()) {
                char character = data.charAt(index);
                if (character == '-' || character == '+' || character == '.' || Character.isDigit(character)
                        || character == 'e' || character == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("expected a number in path data");
            }
            double value = Double.parseDouble(data.substring(start, index));
            if (!relative) {
                return value;
            }
            return (horizontal ? x : y) + value;
        }

        private void skipSeparators() {
            while (index < data.length()) {
                char character = data.charAt(index);
                if (Character.isWhitespace(character) || character == ',') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private static double[] toArray(List<Double> values) {
            double[] flat = new double[values.size()];
            for (int i = 0; i < flat.length; i++) {
                flat[i] = values.get(i);
            }
            return flat;
        }
    }
}
