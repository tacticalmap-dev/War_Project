package com.flowingsun.war_project.client.web;

import java.util.List;

/**
 * One immutable view of everything the web pages need. It is serialised to JSON and pushed into the
 * page only when it actually changes.
 */
public record WebSnapshot(boolean running, boolean hasTeam, double ammo, double fuel,
                          double ammoPerMinute, double fuelPerMinute,
                          double transferMaxAmmo, double transferMaxFuel, List<Member> teammates) {

    /** One roster row. */
    public record Member(String name, boolean online, double ammo, double fuel) {
    }

    public static WebSnapshot empty() {
        return new WebSnapshot(false, false, 0.0D, 0.0D, 0.0D, 0.0D, 50.0D, 25.0D, List.of());
    }

    /** Largest amount one transfer may send for the given kind. */
    public double transferLimit(com.flowingsun.war_project.resource.ResourceKind kind) {
        return kind == com.flowingsun.war_project.resource.ResourceKind.FUEL ? transferMaxFuel : transferMaxAmmo;
    }

    public long ammoRounded() {
        return Math.max(0L, (long) Math.floor(ammo));
    }

    public long fuelRounded() {
        return Math.max(0L, (long) Math.floor(fuel));
    }

    public long ammoRateRounded() {
        return Math.max(0L, (long) Math.floor(ammoPerMinute));
    }

    public long fuelRateRounded() {
        return Math.max(0L, (long) Math.floor(fuelPerMinute));
    }
}
