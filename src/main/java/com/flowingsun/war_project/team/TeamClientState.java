package com.flowingsun.war_project.team;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class TeamClientState {
    private static CompoundTag snapshot = new CompoundTag();
    private static int version;

    private TeamClientState() {
    }

    public static void replace(CompoundTag tag) {
        snapshot = tag.copy();
        version++;
    }

    public static Optional<String> teamOf(String playerName) {
        ListTag teams = snapshot.getList("teams", Tag.TAG_COMPOUND);
        for (Tag raw : teams) {
            CompoundTag team = (CompoundTag) raw;
            if (containsValue(team.getList("members", Tag.TAG_COMPOUND), playerName)) {
                return Optional.of(team.getString("id"));
            }
        }
        return Optional.empty();
    }

    public static Set<String> alliesOf(String teamId) {
        Set<String> allies = new LinkedHashSet<>();
        ListTag teams = snapshot.getList("teams", Tag.TAG_COMPOUND);
        for (Tag raw : teams) {
            CompoundTag team = (CompoundTag) raw;
            if (team.getString("id").equals(teamId)) {
                for (Tag allyRaw : team.getList("allies", Tag.TAG_COMPOUND)) {
                    String value = ((CompoundTag) allyRaw).getString("value");
                    if (!value.isBlank()) {
                        allies.add(value);
                    }
                }
            }
        }
        return allies;
    }

    public static boolean areAllied(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        return alliesOf(left).contains(right) || alliesOf(right).contains(left);
    }

    public static Optional<String> colorOf(String teamId) {
        ListTag teams = snapshot.getList("teams", Tag.TAG_COMPOUND);
        for (Tag raw : teams) {
            CompoundTag team = (CompoundTag) raw;
            if (team.getString("id").equals(teamId)) {
                String color = team.getString("color");
                return color.isBlank() ? Optional.empty() : Optional.of(color);
            }
        }
        return Optional.empty();
    }

    public static OptionalInt teamColor(String teamId) {
        return colorOf(teamId)
                .map(TeamClientState::parseColor)
                .orElse(OptionalInt.empty());
    }

    private static OptionalInt parseColor(String color) {
        int rgb = switch (color.toLowerCase(Locale.ROOT)) {
            case "white" -> 0xFFFFFF;
            case "black" -> 0x000000;
            case "dark_blue" -> 0x0000AA;
            case "dark_green" -> 0x00AA00;
            case "dark_aqua", "dark_cyan" -> 0x00AAAA;
            case "dark_red" -> 0xAA0000;
            case "dark_purple" -> 0xAA00AA;
            case "gold", "orange" -> 0xFFAA00;
            case "gray", "grey" -> 0xAAAAAA;
            case "dark_gray", "dark_grey" -> 0x555555;
            case "blue" -> 0x5555FF;
            case "green" -> 0x55FF55;
            case "aqua", "cyan" -> 0x55FFFF;
            case "red" -> 0xFF5555;
            case "light_purple", "pink" -> 0xFF55FF;
            case "yellow" -> 0xFFFF55;
            default -> 0;
        };
        if (rgb == 0 && !"black".equalsIgnoreCase(color)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(0xFF000000 | rgb);
    }

    public static int version() {
        return version;
    }

    private static boolean containsValue(ListTag list, String value) {
        for (Tag raw : list) {
            if (((CompoundTag) raw).getString("value").equals(value)) {
                return true;
            }
        }
        return false;
    }
}
