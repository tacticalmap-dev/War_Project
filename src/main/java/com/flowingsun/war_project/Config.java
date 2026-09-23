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

    private static final ForgeConfigSpec.DoubleValue VP_WAR_SCORE_START = BUILDER
            .comment("Starting war score of every allied cluster (side) shown on the VP progress bar.",
                    "The score drops while the opposing sides hold VP nodes; a side reaching zero ends the game.")
            .defineInRange("vpWarScoreStart", 500.0D, 1.0D, 1000000.0D);

    private static final ForgeConfigSpec.DoubleValue VP_WAR_DRAIN_PER_MINUTE_PER_NODE = BUILDER
            .comment("Points per minute the opposing side loses for every VP node this side holds.",
                    "Several VP nodes add up (three nodes -> 3x this value per minute).")
            .defineInRange("vpWarDrainPerMinutePerNode", 30.0D, 0.0D, 100000.0D);

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

    private static final ForgeConfigSpec.IntValue CEF_MAX_FRAME_RATE = BUILDER
            .comment("Upper bound on how many frames per second the Chromium surface may produce.",
                    "Chromium only paints while its message loop is pumped, so this is the real frame",
                    "rate: the pages are static apart from short transitions, and a lower value means",
                    "less CPU for a surface nobody is animating. 30 is plenty for this interface.",
                    "Only the Chromium backend uses it.")
            .defineInRange("cefMaxFrameRate", 30, 1, 240);

    private static final ForgeConfigSpec.BooleanValue CEF_LAZY_START = BUILDER
            .comment("Start Chromium only when the interface first has to be shown instead of at game",
                    "startup. A session that never shows the island then runs without any browser",
                    "process at all (no idle CPU, no ~150 MiB of helper processes); the built in",
                    "renderer draws the island until Chromium is up.")
            .define("cefLazyStart", true);

    private static final ForgeConfigSpec.BooleanValue CEF_USE_GPU = BUILDER
            .comment("Let Chromium rasterise and composite on the graphics card instead of the CPU.",
                    "Both modes hand the finished pixels back to the game as a CPU bitmap (the",
                    "java-cef bindings have no shared-texture path), so the GPU mode additionally pays",
                    "for a GPU-to-CPU read back and for a GPU process competing with Minecraft.",
                    "Measured on the Intel UHD 630 of this machine with a 704x600 surface: hardware",
                    "GPU ~14% of one core, software (SwiftShader) ~9%. Default false therefore, and",
                    "true is there for machines where the calculation comes out the other way.",
                    "Setting this changes the real Chromium command line: with false it starts with",
                    "--disable-gpu and friends, with true it does not.")
            .define("cefUseGpu", false);

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
    public static double vpWarScoreStart = 500.0D;
    public static double vpWarDrainPerMinutePerNode = 30.0D;
    public static String webRenderer = "auto";
    public static String cefMirror = "";
    public static boolean webDiagnostics;
    public static int cefMaxFrameRate = 30;
    public static boolean cefLazyStart = true;
    public static boolean cefUseGpu;

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
        vpWarScoreStart = VP_WAR_SCORE_START.get();
        vpWarDrainPerMinutePerNode = VP_WAR_DRAIN_PER_MINUTE_PER_NODE.get();
        webRenderer = WEB_RENDERER.get();
        cefMirror = CEF_MIRROR.get();
        webDiagnostics = WEB_DIAGNOSTICS.get();
        cefMaxFrameRate = CEF_MAX_FRAME_RATE.get();
        cefLazyStart = CEF_LAZY_START.get();
        cefUseGpu = CEF_USE_GPU.get();
    }
}
