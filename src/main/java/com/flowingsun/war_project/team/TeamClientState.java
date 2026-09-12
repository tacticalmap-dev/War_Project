package com.flowingsun.war_project.team;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashSet;
import java.util.Optional;
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
