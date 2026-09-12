package com.flowingsun.war_project;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

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

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static double nodeCaptureBaseSeconds;
    public static double nodeCaptureRecoveryPerSecond;
    public static double capturePlayerCountRateMultiplier;
    public static double capturePlayerCountRateMultiplierCap;
    public static boolean captureDebugMode;

    private Config() {
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        nodeCaptureBaseSeconds = NODE_CAPTURE_BASE_SECONDS.get();
        nodeCaptureRecoveryPerSecond = NODE_CAPTURE_RECOVERY_PER_SECOND.get();
        capturePlayerCountRateMultiplier = CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER.get();
        capturePlayerCountRateMultiplierCap = CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER_CAP.get();
        captureDebugMode = CAPTURE_DEBUG_MODE.get();
    }
}
