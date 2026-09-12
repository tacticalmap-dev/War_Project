package com.flowingsun.war_project.team;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TeamData extends SavedData {
    private static final String NAME = "war_project_teams";

    private final Map<String, Team> teams = new LinkedHashMap<>();

    public static TeamData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TeamData::load, TeamData::new, NAME);
    }

    public static TeamData load(CompoundTag tag) {
        TeamData data = new TeamData();
        ListTag teamsTag = tag.getList("teams", Tag.TAG_COMPOUND);
        for (Tag raw : teamsTag) {
            Team team = Team.load((CompoundTag) raw);
            if (validId(team.id())) {
                data.teams.put(team.id(), team);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag teamsTag = new ListTag();
        teams.values().forEach(team -> teamsTag.add(team.save()));
        tag.put("teams", teamsTag);
        return tag;
    }

    public Collection<Team> teams() {
        return Set.copyOf(teams.values());
    }

    public Optional<Team> team(String teamId) {
        return Optional.ofNullable(teams.get(cleanId(teamId)));
    }

    public Optional<String> teamOf(String playerName) {
        String cleanPlayer = cleanPlayer(playerName);
        return teams.values().stream()
                .filter(team -> team.members().contains(cleanPlayer))
                .map(Team::id)
                .findFirst();
    }

    public boolean addTeam(String teamId, String displayName) {
        String clean = cleanId(teamId);
        if (!validId(clean) || teams.containsKey(clean)) {
            return false;
        }
        teams.put(clean, Team.create(clean, displayName == null || displayName.isBlank() ? clean : displayName.trim()));
        setDirty();
        return true;
    }

    public boolean removeTeam(String teamId) {
        String clean = cleanId(teamId);
        if (teams.remove(clean) == null) {
            return false;
        }
        teams.replaceAll((id, team) -> team.withoutAlly(clean));
        setDirty();
        return true;
    }

    public boolean emptyTeam(String teamId) {
        Team team = teams.get(cleanId(teamId));
        if (team == null) {
            return false;
        }
        teams.put(team.id(), team.withMembers(Set.of()).withAdmins(Set.of()));
        setDirty();
        return true;
    }

    public boolean joinTeam(String teamId, Collection<String> players) {
        Team team = teams.get(cleanId(teamId));
        if (team == null || players.isEmpty()) {
            return false;
        }
        Set<String> cleanPlayers = cleanPlayers(players);
        for (String player : cleanPlayers) {
            removeMemberFromAll(player);
        }
        teams.put(team.id(), team.withMembers(union(team.members(), cleanPlayers)));
        setDirty();
        return true;
    }

    public boolean leave(Collection<String> players) {
        if (players.isEmpty()) {
            return false;
        }
        Set<String> cleanPlayers = cleanPlayers(players);
        boolean changed = false;
        for (Map.Entry<String, Team> entry : teams.entrySet()) {
            Team team = entry.getValue();
            Set<String> members = new LinkedHashSet<>(team.members());
            Set<String> admins = new LinkedHashSet<>(team.admins());
            boolean removed = members.removeAll(cleanPlayers) | admins.removeAll(cleanPlayers);
            if (removed) {
                entry.setValue(team.withMembers(members).withAdmins(admins));
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean setAdmin(String teamId, String playerName) {
        Team team = teams.get(cleanId(teamId));
        String player = cleanPlayer(playerName);
        if (team == null || player.isBlank() || !team.members().contains(player)) {
            return false;
        }
        teams.put(team.id(), team.withAdmins(union(team.admins(), Set.of(player))));
        setDirty();
        return true;
    }

    public boolean removeAdmin(String teamId, String playerName) {
        Team team = teams.get(cleanId(teamId));
        String player = cleanPlayer(playerName);
        if (team == null || player.isBlank()) {
            return false;
        }
        Set<String> admins = new LinkedHashSet<>(team.admins());
        if (!admins.remove(player)) {
            return false;
        }
        teams.put(team.id(), team.withAdmins(admins));
        setDirty();
        return true;
    }

    public boolean modify(String teamId, String property, String value) {
        Team team = teams.get(cleanId(teamId));
        if (team == null) {
            return false;
        }
        Team updated = switch (property) {
            case "displayName" -> team.withDisplayName(value);
            case "color" -> team.withColor(value);
            case "friendlyFire" -> team.withFriendlyFire(Boolean.parseBoolean(value));
            case "seeFriendlyInvisibles" -> team.withSeeFriendlyInvisibles(Boolean.parseBoolean(value));
            case "nametagVisibility" -> team.withNametagVisibility(value);
            case "deathMessageVisibility" -> team.withDeathMessageVisibility(value);
            case "collisionRule" -> team.withCollisionRule(value);
            case "prefix" -> team.withPrefix(value);
            case "suffix" -> team.withSuffix(value);
            default -> null;
        };
        if (updated == null) {
            return false;
        }
        teams.put(team.id(), updated);
        setDirty();
        return true;
    }

    public boolean addAlly(String teamA, String teamB) {
        Team left = teams.get(cleanId(teamA));
        Team right = teams.get(cleanId(teamB));
        if (left == null || right == null || left.id().equals(right.id())) {
            return false;
        }
        teams.put(left.id(), left.withAllies(union(left.allies(), Set.of(right.id()))));
        teams.put(right.id(), right.withAllies(union(right.allies(), Set.of(left.id()))));
        setDirty();
        return true;
    }

    public boolean removeAlly(String teamA, String teamB) {
        Team left = teams.get(cleanId(teamA));
        Team right = teams.get(cleanId(teamB));
        if (left == null || right == null) {
            return false;
        }
        teams.put(left.id(), left.withoutAlly(right.id()));
        teams.put(right.id(), right.withoutAlly(left.id()));
        setDirty();
        return true;
    }

    public boolean areAllied(String teamA, String teamB) {
        if (teamA == null || teamB == null || teamA.equals(teamB)) {
            return true;
        }
        return team(cleanId(teamA)).map(team -> team.allies().contains(cleanId(teamB))).orElse(false);
    }

    public CompoundTag clientSnapshot() {
        CompoundTag tag = new CompoundTag();
        save(tag);
        return tag;
    }

    private void removeMemberFromAll(String player) {
        for (Map.Entry<String, Team> entry : teams.entrySet()) {
            Team team = entry.getValue();
            if (!team.members().contains(player) && !team.admins().contains(player)) {
                continue;
            }
            Set<String> members = new LinkedHashSet<>(team.members());
            Set<String> admins = new LinkedHashSet<>(team.admins());
            members.remove(player);
            admins.remove(player);
            entry.setValue(team.withMembers(members).withAdmins(admins));
        }
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_\\-.:]{1,64}");
    }

    public static String cleanId(String id) {
        return id == null ? "" : id.trim();
    }

    public static String cleanPlayer(String playerName) {
        return playerName == null ? "" : playerName.trim();
    }

    private static Set<String> cleanPlayers(Collection<String> players) {
        Set<String> clean = new LinkedHashSet<>();
        for (String player : players) {
            String value = cleanPlayer(player);
            if (!value.isBlank()) {
                clean.add(value);
            }
        }
        return clean;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> values = new LinkedHashSet<>(left);
        values.addAll(right);
        return values;
    }

    private static ListTag writeStrings(Collection<String> values) {
        ListTag tags = new ListTag();
        for (String value : values) {
            CompoundTag tag = new CompoundTag();
            tag.putString("value", value);
            tags.add(tag);
        }
        return tags;
    }

    private static Set<String> readStrings(CompoundTag tag, String key) {
        Set<String> values = new LinkedHashSet<>();
        ListTag tags = tag.getList(key, Tag.TAG_COMPOUND);
        for (Tag raw : tags) {
            String value = ((CompoundTag) raw).getString("value");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    public record Team(
            String id,
            String displayName,
            String color,
            boolean friendlyFire,
            boolean seeFriendlyInvisibles,
            String nametagVisibility,
            String deathMessageVisibility,
            String collisionRule,
            String prefix,
            String suffix,
            Set<String> members,
            Set<String> admins,
            Set<String> allies
    ) {
        static Team create(String id, String displayName) {
            return new Team(id, displayName, "white", false, true, "always", "always", "always",
                    "", "", Set.of(), Set.of(), Set.of());
        }

        static Team load(CompoundTag tag) {
            String id = cleanId(tag.getString("id"));
            return new Team(
                    id,
                    tag.getString("display_name").isBlank() ? id : tag.getString("display_name"),
                    tag.getString("color").isBlank() ? "white" : tag.getString("color"),
                    tag.getBoolean("friendly_fire"),
                    !tag.contains("see_friendly_invisibles") || tag.getBoolean("see_friendly_invisibles"),
                    tag.getString("nametag_visibility").isBlank() ? "always" : tag.getString("nametag_visibility"),
                    tag.getString("death_message_visibility").isBlank() ? "always" : tag.getString("death_message_visibility"),
                    tag.getString("collision_rule").isBlank() ? "always" : tag.getString("collision_rule"),
                    tag.getString("prefix"),
                    tag.getString("suffix"),
                    Set.copyOf(readStrings(tag, "members")),
                    Set.copyOf(readStrings(tag, "admins")),
                    Set.copyOf(readStrings(tag, "allies"))
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            tag.putString("display_name", displayName);
            tag.putString("color", color);
            tag.putBoolean("friendly_fire", friendlyFire);
            tag.putBoolean("see_friendly_invisibles", seeFriendlyInvisibles);
            tag.putString("nametag_visibility", nametagVisibility);
            tag.putString("death_message_visibility", deathMessageVisibility);
            tag.putString("collision_rule", collisionRule);
            tag.putString("prefix", prefix);
            tag.putString("suffix", suffix);
            tag.put("members", writeStrings(members));
            tag.put("admins", writeStrings(admins));
            tag.put("allies", writeStrings(allies));
            return tag;
        }

        Team withDisplayName(String value) {
            return new Team(id, value, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, admins, allies);
        }

        Team withColor(String value) {
            return new Team(id, displayName, value, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, admins, allies);
        }

        Team withFriendlyFire(boolean value) {
            return new Team(id, displayName, color, value, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, admins, allies);
        }

        Team withSeeFriendlyInvisibles(boolean value) {
            return new Team(id, displayName, color, friendlyFire, value, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, admins, allies);
        }

        Team withNametagVisibility(String value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, value,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, admins, allies);
        }

        Team withDeathMessageVisibility(String value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    value, collisionRule, prefix, suffix, members, admins, allies);
        }

        Team withCollisionRule(String value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, value, prefix, suffix, members, admins, allies);
        }

        Team withPrefix(String value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, value, suffix, members, admins, allies);
        }

        Team withSuffix(String value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, value, members, admins, allies);
        }

        Team withMembers(Set<String> value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, Set.copyOf(value), admins, allies);
        }

        Team withAdmins(Set<String> value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, Set.copyOf(value), allies);
        }

        Team withAllies(Set<String> value) {
            return new Team(id, displayName, color, friendlyFire, seeFriendlyInvisibles, nametagVisibility,
                    deathMessageVisibility, collisionRule, prefix, suffix, members, admins, Set.copyOf(value));
        }

        Team withoutAlly(String ally) {
            Set<String> values = new LinkedHashSet<>(allies);
            values.remove(ally);
            return withAllies(values);
        }
    }
}
