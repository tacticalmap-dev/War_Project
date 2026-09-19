package com.flowingsun.war_project.client.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The little bit of JSON the web bridge needs. Pages receive the whole snapshot as an object literal
 * and answer with flat objects, so a hand written writer plus two accessors is enough and the mod
 * keeps its zero dependency rule.
 */
public final class WebJson {
    private WebJson() {
    }

    public static String write(WebSnapshot snapshot) {
        StringBuilder builder = new StringBuilder(256);
        builder.append('{');
        builder.append("\"running\":").append(snapshot.running()).append(',');
        builder.append("\"hasTeam\":").append(snapshot.hasTeam()).append(',');
        builder.append("\"ammo\":").append(snapshot.ammoRounded()).append(',');
        builder.append("\"fuel\":").append(snapshot.fuelRounded()).append(',');
        builder.append("\"ammoRate\":").append(snapshot.ammoRateRounded()).append(',');
        builder.append("\"fuelRate\":").append(snapshot.fuelRateRounded()).append(',');
        builder.append("\"maxAmmo\":").append(Math.max(0L, (long) Math.floor(snapshot.transferLimit(
                com.flowingsun.war_project.resource.ResourceKind.AMMO)))).append(',');
        builder.append("\"maxFuel\":").append(Math.max(0L, (long) Math.floor(snapshot.transferLimit(
                com.flowingsun.war_project.resource.ResourceKind.FUEL)))).append(',');
        builder.append("\"teammates\":[");
        boolean first = true;
        for (WebSnapshot.Member member : snapshot.teammates()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append("{\"name\":").append(string(member.name()))
                    .append(",\"online\":").append(member.online())
                    .append(",\"ammo\":").append(Math.max(0L, (long) Math.floor(member.ammo())))
                    .append(",\"fuel\":").append(Math.max(0L, (long) Math.floor(member.fuel())))
                    .append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    /** Quoted, escaped string literal. */
    public static String string(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    /** Value of a string field, or the fallback when the field is absent. */
    public static String stringField(String json, String key, String fallback) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    /** Value of a numeric field, or the fallback when the field is absent. */
    public static double numberField(String json, String key, double fallback) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
