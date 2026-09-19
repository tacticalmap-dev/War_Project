package com.flowingsun.war_project;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

@Mod.EventBusSubscriber(modid = WarProject.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.DoubleValue NODE_CAPTURE_BASE_SECONDS = BUILDER
            .comment("Base seconds required to capture a node.")
            .defineInRange("nodeCaptureBaseSeconds", 30.0D, 1.0D, 36000.0D);

    private static final ForgeConfigSpec.DoubleValue NODE_CAPTURE_RECOVERY_PER_SECOND = BUILDER
            .comment("Progress seconds recovered each second when capture stops.")
            .defineInRange("nodeCaptureRecoveryPerSecond", 1.0D, 0.0D, 1000.0D);

    private static final ForgeConfigSpec.DoubleValue CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER = BUILDER
            .comment("Extra capture speed multiplier for each additional leading player.")
            .defineInRange("capturePlayerCountRateMultiplier", 0.1D, 0.0D, 100.0D);

    private static final ForgeConfigSpec.DoubleValue CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER_CAP = BUILDER
            .comment("Maximum capture speed multiplier from player count.")
            .defineInRange("capturePlayerCountRateMultiplierCap", 2.0D, 1.0D, 100.0D);

    private static final ForgeConfigSpec.BooleanValue CAPTURE_DEBUG_MODE = BUILDER
            .comment("Enable capture debug logging.")
            .define("captureDebugMode", false);

    private static final ForgeConfigSpec.DoubleValue RESOURCE_SETTLE_INTERVAL_SECONDS = BUILDER
            .comment("Seconds between resource settlements; each settlement grants output * seconds / 60.")
            .defineInRange("resourceSettleIntervalSeconds", 5.0D, 0.05D, 3600.0D);

    private static final ForgeConfigSpec.BooleanValue RESOURCE_DEBUG_MODE = BUILDER
            .comment("Enable resource settlement debug logging.")
            .define("resourceDebugMode", false);

    private static final ForgeConfigSpec.DoubleValue OUT_OF_MAP_RETURN_SECONDS = BUILDER
            .comment("Seconds a player may stay outside the map area before being killed.")
            .defineInRange("outOfMapReturnSeconds", 30.0D, 1.0D, 3600.0D);

    private static final ForgeConfigSpec.DoubleValue TRANSFER_COOLDOWN_SECONDS = BUILDER
            .comment("Seconds a player must wait between two resource transfers (0 disables the cooldown).")
            .defineInRange("transferCooldownSeconds", 120.0D, 0.0D, 86400.0D);

    private static final ForgeConfigSpec.DoubleValue TRANSFER_MAX_AMMO = BUILDER
            .comment("Maximum ammo a single transfer may send.")
            .defineInRange("transferMaxAmmoPerRequest", 50.0D, 1.0D, 999.0D);

    private static final ForgeConfigSpec.DoubleValue TRANSFER_MAX_FUEL = BUILDER
            .comment("Maximum fuel a single transfer may send.")
            .defineInRange("transferMaxFuelPerRequest", 25.0D, 1.0D, 999.0D);

    private static final ForgeConfigSpec.ConfigValue<String> WEB_RENDERER = BUILDER
            .comment("Which backend draws the resource island and the transfer panel.",
                    "auto     - use Chromium when it is available, otherwise the built in HTML renderer",
                    "native   - always use the built in HTML renderer",
                    "chromium - request Chromium; the built in renderer still takes over if it cannot start")
            .defineInList("webRenderer", "auto", List.of("auto", "native", "chromium"));

    private static final ForgeConfigSpec.ConfigValue<String> CEF_MIRROR = BUILDER
            .comment("Download mirror for the Chromium Embedded Framework binaries; empty uses the public build host.")
            .define("cefMirror", "");

    private static final ForgeConfigSpec.BooleanValue WEB_DIAGNOSTICS = BUILDER
            .comment("Log Chromium surface diagnostics (frames, uploads, frame time) every 60 seconds.")
            .define("webDiagnostics", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static double nodeCaptureBaseSeconds;
    public static double nodeCaptureRecoveryPerSecond;
    public static double capturePlayerCountRateMultiplier;
    public static double capturePlayerCountRateMultiplierCap;
    public static boolean captureDebugMode;
    public static double resourceSettleIntervalSeconds = 5.0D;
    public static boolean resourceDebugMode;
    public static double outOfMapReturnSeconds = 30.0D;
    public static double transferCooldownSeconds = 120.0D;
    public static double transferMaxAmmoPerRequest = 50.0D;
    public static double transferMaxFuelPerRequest = 25.0D;
    public static String webRenderer = "auto";
    public static String cefMirror = "";
    public static boolean webDiagnostics;

    private Config() {
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        nodeCaptureBaseSeconds = NODE_CAPTURE_BASE_SECONDS.get();
        nodeCaptureRecoveryPerSecond = NODE_CAPTURE_RECOVERY_PER_SECOND.get();
        capturePlayerCountRateMultiplier = CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER.get();
        capturePlayerCountRateMultiplierCap = CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER_CAP.get();
        captureDebugMode = CAPTURE_DEBUG_MODE.get();
        resourceSettleIntervalSeconds = RESOURCE_SETTLE_INTERVAL_SECONDS.get();
        resourceDebugMode = RESOURCE_DEBUG_MODE.get();
        outOfMapReturnSeconds = OUT_OF_MAP_RETURN_SECONDS.get();
        transferCooldownSeconds = TRANSFER_COOLDOWN_SECONDS.get();
        transferMaxAmmoPerRequest = TRANSFER_MAX_AMMO.get();
        transferMaxFuelPerRequest = TRANSFER_MAX_FUEL.get();
        webRenderer = WEB_RENDERER.get();
        cefMirror = CEF_MIRROR.get();
        webDiagnostics = WEB_DIAGNOSTICS.get();
    }
}
