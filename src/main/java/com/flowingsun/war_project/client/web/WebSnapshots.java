package com.flowingsun.war_project.client.web;

import com.flowingsun.war_project.client.ResourceClientState;
import com.flowingsun.war_project.net.WarProjectNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the snapshot the pages render from. It mirrors what the built in transfer panel shows: the
 * personal stocks, the rates and every teammate with an online flag.
 */
public final class WebSnapshots {
    private WebSnapshots() {
    }

    public static WebSnapshot current() {
        Set<String> online = onlinePlayerNames();
        List<WebSnapshot.Member> members = new ArrayList<>();
        for (WarProjectNetwork.TeamMemberEntry entry : ResourceClientState.teammates()) {
            members.add(new WebSnapshot.Member(entry.name(), online.contains(entry.name()), entry.ammo(), entry.fuel()));
        }
        members.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return new WebSnapshot(ResourceClientState.isRunning(), ResourceClientState.hasTeam(),
                ResourceClientState.ammo(), ResourceClientState.fuel(),
                ResourceClientState.ammoPerMinute(), ResourceClientState.fuelPerMinute(),
                com.flowingsun.war_project.Config.transferMaxAmmoPerRequest,
                com.flowingsun.war_project.Config.transferMaxFuelPerRequest,
                List.copyOf(members));
    }

    private static Set<String> onlinePlayerNames() {
        Set<String> names = new HashSet<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return names;
        }
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            names.add(info.getProfile().getName());
        }
        return names;
    }
}
