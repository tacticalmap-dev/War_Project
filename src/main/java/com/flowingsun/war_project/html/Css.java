package com.flowingsun.war_project.html;

import java.util.Locale;

/**
 * The CSS subset understood by the HTML kernel. Only the properties listed here are supported;
 * anything else is ignored so a typo can never break the whole document.
 *
 * <p>Supported: background-color/background, color, font-size, padding(-top/right/bottom/left),
 * margin(-top/right/bottom/left), border(-width/-color/-radius), box-shadow, width/height,
 * min-width/max-width, left/top/right/bottom, position, display, flex-direction, gap, align-items,
 * justify-content, opacity, overflow, transition, transform(scale/translate), pointer-events.
 */
public final class Css {
    public int color = 0xFFFFFFFF;
    public boolean hasColor;
    public int background;
    public boolean hasBackground;
    public int fontSize = -1;
    public int paddingLeft;
    public int paddingTop;
    public int paddingRight;
    public int paddingBottom;
    public int marginTop;
    public int marginBottom;
    public int marginLeft;
    public int marginRight;
    public int borderWidth;
    public int borderColor = 0xFF000000;
    public int borderRadius;
    public boolean pillRadius;
    public int shadowSize = -1;
    public int shadowColor = 0x60000000;
    public int width = -1;
    public int height = -1;
    public int minWidth = -1;
    public int maxWidth = -1;
    public int left = Integer.MIN_VALUE;
    public int top = Integer.MIN_VALUE;
    public int right = Integer.MIN_VALUE;
    public int bottom = Integer.MIN_VALUE;
    public String position = "static";
    public String display = "block";
    public String flexDirection = "row";
    public int gap;
    public String alignItems = "flex-start";
    public String justify = "flex-start";
    public float opacity = 1.0F;
    public boolean hasOpacity;
    public float scale = 1.0F;
    public boolean hasScale;
    public int translateX;
    public int translateY;
    public boolean hasTransform;
    public boolean clip;
    public boolean pointerEvents = true;
    public String transitionProperty = "";
    public int transitionMs;
    public String transitionEasing = "ease-out";

    public Css copy() {
        Css copy = new Css();
        copy.color = color;
        copy.hasColor = hasColor;
        copy.background = background;
        copy.hasBackground = hasBackground;
        copy.fontSize = fontSize;
        copy.paddingLeft = paddingLeft;
        copy.paddingTop = paddingTop;
        copy.paddingRight = paddingRight;
        copy.paddingBottom = paddingBottom;
        copy.marginTop = marginTop;
        copy.marginBottom = marginBottom;
        copy.marginLeft = marginLeft;
        copy.marginRight = marginRight;
        copy.borderWidth = borderWidth;
        copy.borderColor = borderColor;
        copy.borderRadius = borderRadius;
        copy.pillRadius = pillRadius;
        copy.shadowSize = shadowSize;
        copy.shadowColor = shadowColor;
        copy.width = width;
        copy.height = height;
        copy.minWidth = minWidth;
        copy.maxWidth = maxWidth;
        copy.left = left;
        copy.top = top;
        copy.right = right;
        copy.bottom = bottom;
        copy.position = position;
        copy.display = display;
        copy.flexDirection = flexDirection;
        copy.gap = gap;
        copy.alignItems = alignItems;
        copy.justify = justify;
        copy.opacity = opacity;
        copy.hasOpacity = hasOpacity;
        copy.scale = scale;
        copy.hasScale = hasScale;
        copy.translateX = translateX;
        copy.translateY = translateY;
        copy.hasTransform = hasTransform;
        copy.clip = clip;
        copy.pointerEvents = pointerEvents;
        copy.transitionProperty = transitionProperty;
        copy.transitionMs = transitionMs;
        copy.transitionEasing = transitionEasing;
        return copy;
    }

    /** Applies a {@code prop: value} pair; unknown properties are ignored. */
    public void apply(String property, String rawValue) {
        String prop = property.trim().toLowerCase(Locale.ROOT);
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        switch (prop) {
            case "background", "background-color" -> {
                Integer parsed = parseColor(value);
                if (parsed != null) {
                    background = parsed;
                    hasBackground = true;
                }
            }
            case "color" -> {
                Integer parsed = parseColor(value);
                if (parsed != null) {
                    color = parsed;
                    hasColor = true;
                }
            }
            case "font-size" -> fontSize = parsePx(value, fontSize);
            case "padding" -> {
                int[] box = parseBox(value);
                if (box != null) {
                    paddingTop = box[0];
                    paddingRight = box[1];
                    paddingBottom = box[2];
                    paddingLeft = box[3];
                }
            }
            case "padding-top" -> paddingTop = parsePx(value, paddingTop);
            case "padding-right" -> paddingRight = parsePx(value, paddingRight);
            case "padding-bottom" -> paddingBottom = parsePx(value, paddingBottom);
            case "padding-left" -> paddingLeft = parsePx(value, paddingLeft);
            case "margin" -> {
                int[] box = parseBox(value);
                if (box != null) {
                    marginTop = box[0];
                    marginRight = box[1];
                    marginBottom = box[2];
                    marginLeft = box[3];
                }
            }
            case "margin-top" -> marginTop = parsePx(value, marginTop);
            case "margin-right" -> marginRight = parsePx(value, marginRight);
            case "margin-bottom" -> marginBottom = parsePx(value, marginBottom);
            case "margin-left" -> marginLeft = parsePx(value, marginLeft);
            case "border-width" -> borderWidth = parsePx(value, borderWidth);
            case "border-color" -> {
                Integer parsed = parseColor(value);
                if (parsed != null) {
                    borderColor = parsed;
                }
            }
            case "border-radius" -> {
                if (value.contains("999")) {
                    pillRadius = true;
                } else {
                    borderRadius = parsePx(value, borderRadius);
                    pillRadius = false;
                }
            }
            case "box-shadow" -> {
                int[] box = parseShadow(value);
                if (box != null) {
                    shadowSize = box[0];
                    shadowColor = box[1];
                }
            }
            case "width" -> width = parsePx(value, width);
            case "height" -> height = parsePx(value, height);
            case "min-width" -> minWidth = parsePx(value, minWidth);
            case "max-width" -> maxWidth = parsePx(value, maxWidth);
            case "left" -> left = parsePx(value, left);
            case "top" -> top = parsePx(value, top);
            case "right" -> right = parsePx(value, right);
            case "bottom" -> bottom = parsePx(value, bottom);
            case "position" -> position = value;
            case "display" -> display = value;
            case "flex-direction" -> flexDirection = value;
            case "gap" -> gap = parsePx(value, gap);
            case "align-items" -> alignItems = value;
            case "justify-content" -> justify = value;
            case "opacity" -> {
                opacity = parseFloat(value, opacity);
                hasOpacity = true;
            }
            case "overflow" -> clip = value.startsWith("hidden");
            case "pointer-events" -> pointerEvents = !value.startsWith("none");
            case "transition" -> parseTransition(value);
            case "transform" -> parseTransform(value);
            default -> {
            }
        }
    }

    private void parseTransition(String value) {
        String[] parts = value.replace(",", " ").trim().split("\\s+");
        int duration = 0;
        String property = "";
        String easing = "ease-out";
        for (String part : parts) {
            if (part.endsWith("ms")) {
                duration = (int) parseFloat(part.substring(0, part.length() - 2), duration);
            } else if (part.endsWith("s") && part.length() > 1 && !part.contains("-")) {
                duration = (int) (parseFloat(part.substring(0, part.length() - 1), 0.0F) * 1000.0F);
            } else if (part.startsWith("cubic-bezier") || part.startsWith("linear") || part.startsWith("ease")) {
                easing = part;
            } else if (!part.isBlank()) {
                property = part;
            }
        }
        transitionProperty = property;
        transitionMs = Math.max(0, duration);
        transitionEasing = easing;
    }

    private void parseTransform(String value) {
        int open = value.indexOf('(');
        int close = value.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return;
        }
        String function = value.substring(0, open).trim();
        String[] args = value.substring(open + 1, close).split(",");
        if (function.equals("scale") && args.length >= 1) {
            scale = parseFloat(args[0].trim(), scale);
            hasScale = true;
        } else if (function.equals("translate") && args.length >= 1) {
            translateX = parsePx(args[0].trim(), translateX);
            if (args.length >= 2) {
                translateY = parsePx(args[1].trim(), translateY);
            }
            hasTransform = true;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int parsePx(String value, int fallback) {
        String clean = value.trim();
        if (clean.endsWith("px")) {
            clean = clean.substring(0, clean.length() - 2);
        }
        try {
            return (int) Math.round(Float.parseFloat(clean));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int[] parseBox(String value) {
        String[] parts = value.trim().split("\\s+");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = parsePx(parts[i], 0);
        }
        return switch (values.length) {
            case 1 -> new int[]{values[0], values[0], values[0], values[0]};
            case 2 -> new int[]{values[0], values[1], values[0], values[1]};
            case 3 -> new int[]{values[0], values[1], values[2], values[1]};
            case 4 -> new int[]{values[0], values[1], values[2], values[3]};
            default -> null;
        };
    }

    private static int[] parseShadow(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        int spread = 0;
        for (String part : parts) {
            if (part.contains("px")) {
                spread = Math.max(spread, parsePx(part, 0));
            }
        }
        int color = 0x60000000;
        for (String part : parts) {
            Integer parsed = parseColor(part);
            if (parsed != null) {
                color = parsed;
                break;
            }
        }
        return new int[]{spread, color};
    }

    /** Parses #rgb / #rrggbb / #rrggbbaa / rgba(...) / a few names; null when unrecognised. */
    public static Integer parseColor(String value) {
        String clean = value.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("#")) {
            String hex = clean.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                    return 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                }
                if (hex.length() == 8) {
                    int rgb = Integer.parseInt(hex.substring(0, 6), 16);
                    int alpha = Integer.parseInt(hex.substring(6, 8), 16);
                    return (alpha << 24) | rgb;
                }
            } catch (NumberFormatException exception) {
                return null;
            }
            return null;
        }
        if (clean.startsWith("rgba(") || clean.startsWith("rgb(")) {
            int open = clean.indexOf('(');
            int close = clean.indexOf(')');
            if (open < 0 || close < 0) {
                return null;
            }
            String[] parts = clean.substring(open + 1, close).split(",");
            if (parts.length < 3) {
                return null;
            }
            int r = clamp255(parseFloat(parts[0].trim(), 0.0F));
            int g = clamp255(parseFloat(parts[1].trim(), 0.0F));
            int b = clamp255(parseFloat(parts[2].trim(), 0.0F));
            int a = parts.length >= 4 ? clamp255(parseFloat(parts[3].trim(), 1.0F) * 255.0F) : 255;
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
        return switch (clean) {
            case "white" -> 0xFFFFFFFF;
            case "black" -> 0xFF000000;
            case "transparent", "none" -> 0x00000000;
            case "red" -> 0xFFFF5555;
            case "green" -> 0xFF7CE38B;
            case "gray", "grey" -> 0xFF9AA4AF;
            case "yellow" -> 0xFFFFD479;
            default -> null;
        };
    }

    private static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    public static int scaleAlpha(int color, float factor) {
        int alpha = (color >>> 24) & 0xFF;
        int scaled = Math.max(0, Math.min(255, Math.round(alpha * Math.max(0.0F, Math.min(1.0F, factor)))));
        return (scaled << 24) | (color & 0xFFFFFF);
    }
}
