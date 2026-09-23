package com.flowingsun.war_project.html;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal HTML parser: div/span/b/strong/img/button/input/hr plus the SVG subset used for vector
 * shapes (svg/path/circle/ellipse/rect/polygon/polyline), plus attributes
 * id/class/style/src/value and any other attribute kept verbatim for the vector renderer.
 * Unknown tags and attributes are ignored, and unclosed tags are closed at the end, so a malformed
 * document degrades instead of throwing.
 *
 * <p>CSS rules are kept (not baked in), so toggling a class at runtime can re-evaluate them through
 * {@link #refreshStyles()}.
 */
public final class HtmlDocument {
    private static final List<String> VOID_TAGS = List.of("img", "hr", "input", "br",
            "circle", "ellipse", "rect", "path", "polygon", "polyline", "line", "use", "stop");

    public final HtmlNode root;
    private final List<Rule> rules = new ArrayList<>();

    private HtmlDocument(HtmlNode root) {
        this.root = root;
    }

    private record Rule(String base, boolean hover, boolean active, String body) {
    }

    public static HtmlDocument parse(String html) {
        HtmlNode root = new HtmlNode("#root", null);
        List<String> styleBlocks = new ArrayList<>();
        HtmlNode current = root;
        int index = 0;
        while (index < html.length()) {
            int open = html.indexOf('<', index);
            if (open < 0) {
                appendText(current, html.substring(index));
                break;
            }
            if (open > index) {
                appendText(current, html.substring(index, open));
            }
            int close = html.indexOf('>', open);
            if (close < 0) {
                break;
            }
            String token = html.substring(open + 1, close).trim();
            index = close + 1;
            if (token.isEmpty() || token.startsWith("!--") || token.startsWith("!")) {
                continue;
            }
            if (token.startsWith("/")) {
                String closing = token.substring(1).trim().toLowerCase();
                HtmlNode node = current;
                while (node != null && node != root && !node.tag.equals(closing)) {
                    node = node.parent;
                }
                current = node == null || node == root ? root : node.parent;
                continue;
            }
            boolean selfClosing = token.endsWith("/");
            if (selfClosing) {
                token = token.substring(0, token.length() - 1).trim();
            }
            String tag = tagName(token).toLowerCase();
            if (tag.equals("style")) {
                int end = html.indexOf("</style>", index);
                String body = end < 0 ? html.substring(index) : html.substring(index, end);
                styleBlocks.add(body);
                index = end < 0 ? html.length() : end + "</style>".length();
                continue;
            }
            HtmlNode node = new HtmlNode(tag, current);
            parseAttributes(node, token.substring(tag.length()));
            if (!selfClosing && !VOID_TAGS.contains(tag)) {
                current = node;
            }
        }
        HtmlDocument document = new HtmlDocument(root);
        for (String block : styleBlocks) {
            document.collectRules(block);
        }
        document.refreshStyles();
        return document;
    }

    private static String tagName(String token) {
        int space = token.indexOf(' ');
        return space < 0 ? token : token.substring(0, space);
    }

    private static void appendText(HtmlNode node, String raw) {
        String text = raw.replace("\r", " ").replace("\n", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        node.text = node.text.isEmpty() ? text : node.text + " " + text;
    }

    private static void parseAttributes(HtmlNode node, String raw) {
        int index = 0;
        while (index < raw.length()) {
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) {
                index++;
            }
            int nameStart = index;
            while (index < raw.length() && raw.charAt(index) != '=' && !Character.isWhitespace(raw.charAt(index))) {
                index++;
            }
            if (nameStart == index) {
                break;
            }
            String name = raw.substring(nameStart, index).toLowerCase();
            String value = "";
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) {
                index++;
            }
            if (index < raw.length() && raw.charAt(index) == '=') {
                index++;
                while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) {
                    index++;
                }
                if (index < raw.length() && (raw.charAt(index) == '"' || raw.charAt(index) == '\'')) {
                    char quote = raw.charAt(index++);
                    int valueStart = index;
                    while (index < raw.length() && raw.charAt(index) != quote) {
                        index++;
                    }
                    value = raw.substring(valueStart, Math.min(index, raw.length()));
                    index = Math.min(index + 1, raw.length());
                } else {
                    int valueStart = index;
                    while (index < raw.length() && !Character.isWhitespace(raw.charAt(index))) {
                        index++;
                    }
                    value = raw.substring(valueStart, index);
                }
            }
            switch (name) {
                case "id" -> node.id = value;
                case "class" -> {
                    for (String part : value.trim().split("\\s+")) {
                        if (!part.isBlank()) {
                            node.classes.add(part);
                        }
                    }
                }
                case "style" -> applyDeclarations(node.inlineStyle, value);
                default -> node.attributes.put(name, value);
            }
        }
    }

    private static void applyDeclarations(Css target, String body) {
        for (String declaration : body.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0) {
                target.apply(declaration.substring(0, colon), declaration.substring(colon + 1));
            }
        }
    }

    private void collectRules(String rawCssText) {
        // Comments are stripped up front: the selector is everything between the previous closing
        // brace and the next opening brace, so a /* ... */ left in place would be glued onto the
        // selector that follows it and that whole rule would silently never match.
        String cssText = stripComments(rawCssText);
        int index = 0;
        while (index < cssText.length()) {
            int open = cssText.indexOf('{', index);
            if (open < 0) {
                break;
            }
            int close = cssText.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String selector = cssText.substring(index, open).trim();
            String body = cssText.substring(open + 1, close);
            index = close + 1;
            for (String single : selector.split(",")) {
                String trimmed = single.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                boolean hover = false;
                boolean active = false;
                String base = trimmed;
                if (base.endsWith(":hover")) {
                    hover = true;
                    base = base.substring(0, base.length() - ":hover".length());
                } else if (base.endsWith(":active")) {
                    active = true;
                    base = base.substring(0, base.length() - ":active".length());
                }
                rules.add(new Rule(base.trim(), hover, active, body));
            }
        }
    }

    /** Rebuilds every node's style from its inline attributes plus the current class list. */
    public void refreshStyles() {
        for (HtmlNode node : all()) {
            node.style = node.inlineStyle.copy();
            node.hoverStyle = null;
            node.activeStyle = null;
            for (Rule rule : rules) {
                if (!matches(node, rule.base())) {
                    continue;
                }
                Css target;
                if (rule.hover()) {
                    target = node.hoverStyle == null ? (node.hoverStyle = new Css()) : node.hoverStyle;
                } else if (rule.active()) {
                    target = node.activeStyle == null ? (node.activeStyle = new Css()) : node.activeStyle;
                } else {
                    target = node.style;
                }
                applyDeclarations(target, rule.body());
            }
        }
    }

    private static String stripComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int index = 0;
        while (index < css.length()) {
            int start = css.indexOf("/*", index);
            if (start < 0) {
                out.append(css, index, css.length());
                break;
            }
            out.append(css, index, start);
            int end = css.indexOf("*/", start + 2);
            if (end < 0) {
                break;
            }
            index = end + 2;
        }
        return out.toString();
    }

    private static boolean matches(HtmlNode node, String base) {
        if (base.isEmpty() || base.equals("*")) {
            return true;
        }
        if (base.startsWith(".")) {
            return node.classes.contains(base.substring(1));
        }
        if (base.startsWith("#")) {
            return base.substring(1).equals(node.id);
        }
        return node.tag.equals(base);
    }

    private List<HtmlNode> all() {
        List<HtmlNode> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(HtmlNode node, List<HtmlNode> out) {
        out.add(node);
        for (HtmlNode child : node.children) {
            collect(child, out);
        }
    }

    public HtmlNode byId(String id) {
        return root.byId(id);
    }

    public HtmlNode hit(double x, double y) {
        return root.hit(x, y);
    }
}
