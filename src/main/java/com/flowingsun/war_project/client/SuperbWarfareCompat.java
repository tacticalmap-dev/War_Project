package com.flowingsun.war_project.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Optional Superb Warfare compatibility. There is no compile-time dependency: SBW is recognised from
 * the runtime class of the ridden vehicle only, so this class is inert when SBW is absent.
 *
 * <p>The first-person check is deliberate: SBW's own RenderContext#isFirstPerson() decompiles to
 * exactly {@code Minecraft.options.getCameraType().isFirstPerson()} (verified against
 * superbwarfare-0.8.9.1: Options.m_92176_().m_90612_()Z), so War Project asks vanilla instead of
 * reflectively calling into the other mod.
 */
public final class SuperbWarfareCompat {
    private static final String SBW_VEHICLE_PACKAGE = "com.atsuishio.superbwarfare.entity.vehicle";
    /** SBW's client-side state holder; {@code zoomVehicle} is set while HOLD_ZOOM is held in a vehicle. */
    private static final String SBW_CLIENT_EVENT_HANDLER = "com.atsuishio.superbwarfare.event.ClientEventHandler";

    private static boolean zoomLookupDone;
    private static java.lang.reflect.Field zoomVehicleField;

    private SuperbWarfareCompat() {
    }

    /**
     * True while the local player rides a Superb Warfare vehicle in first person, i.e. the vehicle
     * gun-sight view. The resource HUD hides itself while this is true.
     */
    public static boolean isVehicleFirstPerson() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        Entity vehicle = minecraft.player.getVehicle();
        return vehicle != null && isSuperbWarfareVehicle(vehicle);
    }

    /**
     * True while the vehicle gun sight is up, including the third person case: sitting in an SBW
     * vehicle and holding the gun-sight key keeps the vanilla camera third person, so
     * {@link #isVehicleFirstPerson()} alone would miss it. SBW's own state is read reflectively
     * ({@code ClientEventHandler.zoomVehicle}, set by its ClickEventHandler for HOLD_ZOOM), so this
     * class stays free of any compile-time dependency on that mod.
     */
    public static boolean isVehicleGunSight() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        Entity vehicle = minecraft.player.getVehicle();
        if (vehicle == null || !isSuperbWarfareVehicle(vehicle)) {
            return false;
        }
        if (minecraft.options.getCameraType().isFirstPerson()) {
            return true;
        }
        return isZoomingVehicle();
    }

    private static boolean isZoomingVehicle() {
        if (!zoomLookupDone) {
            zoomLookupDone = true;
            try {
                zoomVehicleField = Class.forName(SBW_CLIENT_EVENT_HANDLER).getField("zoomVehicle");
            } catch (Throwable missing) {
                zoomVehicleField = null;
            }
        }
        if (zoomVehicleField == null) {
            return false;
        }
        try {
            return zoomVehicleField.getBoolean(null);
        } catch (Throwable failed) {
            return false;
        }
    }

    private static boolean isSuperbWarfareVehicle(Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().startsWith(SBW_VEHICLE_PACKAGE)) {
                return true;
            }
        }
        return false;
    }
}
