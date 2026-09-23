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
            .comment("占领一个节点所需的基础秒数。")
            .defineInRange("nodeCaptureBaseSeconds", 30.0D, 1.0D, 36000.0D);

    private static final ForgeConfigSpec.DoubleValue NODE_CAPTURE_RECOVERY_PER_SECOND = BUILDER
            .comment("占领停止后，每秒回退的进度秒数。")
            .defineInRange("nodeCaptureRecoveryPerSecond", 1.0D, 0.0D, 1000.0D);

    private static final ForgeConfigSpec.DoubleValue CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER = BUILDER
            .comment("领先方每多一名玩家，额外增加的占领速度倍率。")
            .defineInRange("capturePlayerCountRateMultiplier", 0.1D, 0.0D, 100.0D);

    private static final ForgeConfigSpec.DoubleValue CAPTURE_PLAYER_COUNT_RATE_MULTIPLIER_CAP = BUILDER
            .comment("由在场人数带来的占领速度倍率上限。")
            .defineInRange("capturePlayerCountRateMultiplierCap", 2.0D, 1.0D, 100.0D);

    private static final ForgeConfigSpec.BooleanValue CAPTURE_DEBUG_MODE = BUILDER
            .comment("开启占领调试日志。")
            .define("captureDebugMode", false);

    private static final ForgeConfigSpec.DoubleValue RESOURCE_SETTLE_INTERVAL_SECONDS = BUILDER
            .comment("资源结算的间隔秒数；每次结算发放「每分钟产出 × 间隔秒数 ÷ 60」。")
            .defineInRange("resourceSettleIntervalSeconds", 5.0D, 0.05D, 3600.0D);

    private static final ForgeConfigSpec.BooleanValue RESOURCE_DEBUG_MODE = BUILDER
            .comment("开启资源结算调试日志。")
            .define("resourceDebugMode", false);

    private static final ForgeConfigSpec.DoubleValue OUT_OF_MAP_RETURN_SECONDS = BUILDER
            .comment("玩家越出地图边界后允许停留的秒数，超时将被击杀。")
            .defineInRange("outOfMapReturnSeconds", 30.0D, 1.0D, 3600.0D);

    private static final ForgeConfigSpec.DoubleValue TRANSFER_COOLDOWN_SECONDS = BUILDER
            .comment("两次资源转移之间必须等待的秒数（0 表示无冷却）。")
            .defineInRange("transferCooldownSeconds", 120.0D, 0.0D, 86400.0D);

    private static final ForgeConfigSpec.DoubleValue TRANSFER_MAX_AMMO = BUILDER
            .comment("单次资源转移可发送的弹药上限。")
            .defineInRange("transferMaxAmmoPerRequest", 50.0D, 1.0D, 999.0D);

    private static final ForgeConfigSpec.DoubleValue TRANSFER_MAX_FUEL = BUILDER
            .comment("单次资源转移可发送的燃料上限。")
            .defineInRange("transferMaxFuelPerRequest", 25.0D, 1.0D, 999.0D);

    private static final ForgeConfigSpec.DoubleValue VP_WAR_SCORE_START = BUILDER
            .comment("VP 战局条上每个同盟簇（阵营）的初始战局分。",
                    "对方占据 VP 节点时本方分数会持续下降；任一方归零即结束战局。")
            .defineInRange("vpWarScoreStart", 500.0D, 1.0D, 1000000.0D);

    private static final ForgeConfigSpec.DoubleValue VP_WAR_DRAIN_PER_MINUTE_PER_NODE = BUILDER
            .comment("本方每占据一个 VP 节点，对方每分钟被扣除的分数。",
                    "多个 VP 节点可叠加（占三个节点即每分钟扣三倍该值）。")
            .defineInRange("vpWarDrainPerMinutePerNode", 30.0D, 0.0D, 100000.0D);

    private static final ForgeConfigSpec.ConfigValue<String> WEB_RENDERER = BUILDER
            .comment("由哪个后端绘制资源条与转移面板。",
                    "auto     - 可用时使用 Chromium，否则回退到内置 HTML 渲染器",
                    "native   - 始终使用内置 HTML 渲染器",
                    "chromium - 申请使用 Chromium；若无法启动仍由内置渲染器接管")
            .defineInList("webRenderer", "auto", List.of("auto", "native", "chromium"));

    private static final ForgeConfigSpec.ConfigValue<String> CEF_MIRROR = BUILDER
            .comment("Chromium Embedded Framework 二进制的下载镜像地址；留空则使用公开构建源。")
            .define("cefMirror", "");

    private static final ForgeConfigSpec.BooleanValue WEB_DIAGNOSTICS = BUILDER
            .comment("每 60 秒输出一次 Chromium 表面诊断日志（帧数、上传次数、帧耗时）。")
            .define("webDiagnostics", false);

    private static final ForgeConfigSpec.IntValue CEF_MAX_FRAME_RATE = BUILDER
            .comment("Chromium 表面每秒最多可产出的帧数上限。",
                    "Chromium 只在其消息循环被驱动时才会绘制，因此这个值就是真实帧率：",
                    "页面除短暂过渡外基本静止，取更低的值意味着",
                    "为一块无人在动的表面省下 CPU。对本界面而言 30 已经足够。",
                    "仅 Chromium 后端使用此项。")
            .defineInRange("cefMaxFrameRate", 30, 1, 240);

    private static final ForgeConfigSpec.BooleanValue CEF_LAZY_START = BUILDER
            .comment("仅在界面首次需要显示时才启动 Chromium，而不是在游戏启动时。",
                    "这样整局都不显示资源条的会话可以完全不启动浏览器进程",
                    "（没有空转 CPU 开销，也不占用约 150 MiB 的辅助进程）；",
                    "在 Chromium 就绪之前由内置渲染器绘制资源条。")
            .define("cefLazyStart", true);

    private static final ForgeConfigSpec.BooleanValue CEF_USE_GPU = BUILDER
            .comment("让 Chromium 使用显卡而非 CPU 进行光栅化与合成。",
                    "两种模式都要把最终像素以 CPU 位图形式交回游戏（java-cef",
                    "绑定没有共享纹理通路），因此 GPU 模式还要额外付出",
                    "一次 GPU 到 CPU 的回读，以及一个与 Minecraft 争抢显卡的 GPU 进程。",
                    "在本机 Intel UHD 630、704x600 表面上的实测：硬件",
                    "GPU 约占单核 14%，软件渲染（SwiftShader）约 9%，故默认 false；",
                    "若某些机器上结论相反，可改为 true。",
                    "此项会改变真实的 Chromium 命令行：false 时带",
                    "--disable-gpu 等参数，true 时不带。")
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
