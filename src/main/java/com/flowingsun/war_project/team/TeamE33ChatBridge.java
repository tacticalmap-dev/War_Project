package com.flowingsun.war_project.team;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional e33chat integration: declares every War Project team (and every
 * alliance cluster) as an e33chat chat group, so teams show up as tabs in
 * e33chat's panel and their members can talk in them.
 *
 * <p>Everything here is reflective, so war_project compiles and runs with or
 * without e33chat; a missing mod or a missing API class only turns this class
 * into a no-op.
 *
 * <p>Contract notes for the e33chat build this targets (2.4.13 + the group API
 * bridge over the in-mod {@code GroupManager}):
 * <ul>
 *   <li>{@code replaceOwnerGroups} is <b>declarative</b>: groups of this owner
 *       that are not declared in the same call are removed, so a sync always
 *       submits the complete set;</li>
 *   <li>members are declared by <b>player name</b>; offline members are added by
 *       e33chat itself on their next login, which is why this class has no
 *       login bookkeeping of its own;</li>
 *   <li>display names are sanitised and made unique by e33chat (max 12 chars, no
 *       whitespace / {@code []<>§}, no leading {@code '#'}); the group id is the
 *       stable handle and is never resolved by name here;</li>
 *   <li>the declared member set <b>replaces</b> the previous one, so TeamData is
 *       the single source of truth for its groups;</li>
 *   <li>everything is refused while {@code groups_enabled} is false.</li>
 * </ul>
 */
public final class TeamE33ChatBridge {
    private static final String E33CHAT_MOD_ID = "e33chat";
    private static final String API_CLASS = "com.niuqu.chatbubble.api.E33ChatGroupApi";
    private static final String ENGINE_CLASS = "com.niuqu.chatbubble.server.GroupManager";
    /** Owner id handed to e33chat; also the {@code replaceOwnerGroups} scope. */
    private static final String OWNER_MOD_ID = "war_project";
    private static final String TEAM_GROUP_PREFIX = "war_project:team:";
    private static final String ALLY_GROUP_PREFIX = "war_project:ally:";
    private static final String ALLY_NAME_PREFIX = "盟·";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean apiMissing;
    private static boolean apiMissingLogged;
    private static boolean engineProbeFailedLogged;
    private static boolean syncFailureLogged;

    private TeamE33ChatBridge() {
    }

    /**
     * True when e33chat is present <b>and</b> its group engine is usable. The
     * client hides the whole group tab strip when {@code groups_enabled} is
     * false, so deferring team chat to e33chat in that state would leave players
     * with no team chat at all; the probe therefore keeps War Project's own team
     * channel as the fallback whenever it cannot confirm the engine is on.
     */
    public static boolean isAvailable() {
        if (apiMissing || !ModList.get().isLoaded(E33CHAT_MOD_ID)) {
            return false;
        }
        try {
            Class<?> engine = Class.forName(ENGINE_CLASS);
            Method allowed = engine.getMethod("externalGroupsAllowed");
            return Boolean.TRUE.equals(allowed.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!engineProbeFailedLogged) {
                engineProbeFailedLogged = true;
                LOGGER.info("War Project could not probe the e33chat group engine ({}); "
                        + "its own team chat stays active", exception.toString());
            }
            return false;
        }
    }

    /**
     * Declares the complete War Project group set to e33chat. Server thread only:
     * it writes e33chat's group file and broadcasts the group list.
     */
    public static void syncAll(MinecraftServer server) {
        if (server == null || apiMissing || !ModList.get().isLoaded(E33CHAT_MOD_ID)) {
            return;
        }

        Map<String, String> namesByGroup = new LinkedHashMap<>();
        Map<String, Collection<String>> membersByGroup = new LinkedHashMap<>();
        buildDeclaration(server, namesByGroup, membersByGroup);
        // The call is made even for an empty set: that is what removes the groups
        // this owner left behind once every team is gone.

        try {
            Method replaceOwnerGroups = Class.forName(API_CLASS)
                    .getMethod("replaceOwnerGroups", MinecraftServer.class, String.class, Map.class, Map.class);
            replaceOwnerGroups.invoke(null, server, OWNER_MOD_ID, namesByGroup, membersByGroup);
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            apiMissing = true;
            if (!apiMissingLogged) {
                apiMissingLogged = true;
                LOGGER.info("e33chat is installed but exposes no group API; "
                        + "War Project teams are not declared as chat groups");
            }
            return;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!syncFailureLogged) {
                syncFailureLogged = true;
                LOGGER.warn("Failed to declare War Project teams to e33chat; group tabs may be stale", exception);
            }
            return;
        }
        LOGGER.debug("Declared {} War Project group(s) to e33chat", namesByGroup.size());
    }

    /**
     * Maps TeamData onto e33chat's declaration maps: one group per team, plus one
     * group per alliance cluster.
     *
     * <p>Team groups come first so that a full server keeps team tabs when
     * e33chat's group cap is reached, and both passes walk the ids in sorted
     * order — {@link TeamData#teams()} returns an unordered {@code Set.copyOf},
     * and a stable order is what keeps e33chat's {@code -2/-3} name suffixes and
     * the cap cutoff identical across restarts.
     */
    private static void buildDeclaration(MinecraftServer server, Map<String, String> namesByGroup,
                                         Map<String, Collection<String>> membersByGroup) {
        TeamData data = TeamData.get(server);
        List<String> teamIds = new ArrayList<>();
        for (TeamData.Team team : data.teams()) {
            teamIds.add(team.id());
        }
        Collections.sort(teamIds);

        for (String teamId : teamIds) {
            TeamData.Team team = data.team(teamId).orElse(null);
            if (team == null) {
                continue;
            }
            namesByGroup.put(TEAM_GROUP_PREFIX + teamId, displayName(team, ""));
            membersByGroup.put(TEAM_GROUP_PREFIX + teamId, List.copyOf(team.members()));
        }

        for (List<String> cluster : allyClusters(data, teamIds)) {
            // Groups are addressed by id, so the anchor only has to be stable.
            String anchor = cluster.get(0);
            Set<String> clusterMembers = new LinkedHashSet<>();
            for (String teamId : cluster) {
                data.team(teamId).ifPresent(team -> clusterMembers.addAll(team.members()));
            }
            if (clusterMembers.isEmpty()) {
                // A member-less group is dissolved by e33chat's own leave path.
                continue;
            }
            namesByGroup.put(ALLY_GROUP_PREFIX + anchor,
                    displayName(data.team(anchor).orElse(null), ALLY_NAME_PREFIX));
            membersByGroup.put(ALLY_GROUP_PREFIX + anchor, List.copyOf(clusterMembers));
        }
    }

    /**
     * Connected components of the (symmetric) ally relation, restricted to teams
     * that still exist. One group per cluster instead of one per team: an A/B/C
     * alliance would otherwise produce three identical tabs per member and eat
     * the server's group cap three times over.
     */
    private static List<List<String>> allyClusters(TeamData data, List<String> teamIds) {
        Set<String> existing = new LinkedHashSet<>(teamIds);
        Set<String> visited = new LinkedHashSet<>();
        List<List<String>> clusters = new ArrayList<>();
        for (String start : teamIds) {
            if (!visited.add(start)) {
                continue;
            }
            List<String> cluster = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.addLast(start);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                cluster.add(current);
                TeamData.Team team = data.team(current).orElse(null);
                if (team == null) {
                    continue;
                }
                for (String ally : team.allies()) {
                    if (existing.contains(ally) && visited.add(ally)) {
                        queue.addLast(ally);
                    }
                }
            }
            if (cluster.size() < 2) {
                continue;
            }
            Collections.sort(cluster);
            clusters.add(cluster);
        }
        return clusters;
    }

    /** e33chat sanitises, truncates and uniquifies this; the id stays the handle. */
    private static String displayName(TeamData.Team team, String prefix) {
        if (team == null) {
            return prefix;
        }
        String base = team.displayName() == null || team.displayName().isBlank() ? team.id() : team.displayName();
        return prefix + base;
    }
}
