---
id: "b75b521b-f4c9-41fb-90c8-1928b6462d99"
title: "War Project 增量交接：资源 HUD 渲染修复、灵动岛外观与 999 硬上限"
description: "接在 3ea0c3c0（双资源+HUD 首版）之后的增量：GuiGraphics blit 重载陷阱（图标只采左上 16×16 透明角而不显示）、顶部资源条灵动岛黑底规格（纯黑胶囊/圆角=高度一半/逐行 fill）、资源硬上限 999（ResourceData.MAX_AMOUNT 统一 clamp 含读档）、本机构建与同步纪律（daemon 卡死、证书参数、并行检测）。改 HUD/资源/构建前先读。"
status: "active"
created_at: "2026-09-19T07:22:35.289Z"
updated_at: "2026-09-19T07:22:35.289Z"
content_hash: "bcf64c9ff845faab387d58b01522a8bb8f6b25b4bf5ccee23f6642909259ea4c"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceClientState.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceData.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/main/resources/assets/war_project/textures/gui/ammo.png"
  - "src/main/resources/assets/war_project/textures/gui/fuel.png"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "238114eb-14df-4681-9e21-fad3f00d601e"
memory_body_ids:
  []
---

# War Project 增量交接：HUD 渲染修复、灵动岛外观与 999 硬上限

本文只记录**既有托管文档未覆盖**的增量，接在 `3ea0c3c0-6079-4bcf-8580-c21edf9967b5`（双资源 ammo/fuel 与资源 HUD 首版）之后。
模块与占点总览见 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`；资源经济与全局游戏生命周期首版见 `588ee5c2-515b-4c80-9d4a-fb8d4bb766c2`（其中「单资源标量 / 协议号 2」已被 3ea0c3c0 与本文取代）。
仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 资源 HUD 图标不显示的真因（GuiGraphics.blit 重载语义）

现象：HUD 文本（存量与 `+xx` 速率）正常渲染、位置正确，但**图标位置是空白**。
根因：使用了返回签名较短的 blit 重载，它把**绘制尺寸当作纹理采样尺寸**，于是 128×128 的图标只采样左上角 16×16 区域——恰好是圆徽章外的透明像素。

对 mapped 1.20.1 `net.minecraft.client.gui.GuiGraphics` 做 javap 反编译得到的可用签名：

- `blit(ResourceLocation, int x, int y, int drawW, int drawH, float uOffset, float vOffset, int srcW, int srcH, int texW, int texH)`（**正确可缩放**；bytecode 里 `srcW/srcH` 是采样区域，`texW/texH` 是 `fdiv` 分母）
- 短重载（7/9 参数）：`srcW = drawW`、`srcH = drawH`，无法在“整图 → 小尺寸”之间独立取值 → 图标不可见

结论与规范：
- HUD 图标一律用 11 参数重载，例如 `blit(icon, x, y, 16, 16, 0.0F, 0.0F, 128, 128, 128, 128)`。
- 贴图缺失会显示粉黑格；若“空白但文本正常”，先怀疑采样区域而不是资源缺失（可用 `unzip -l <jar> | grep textures/gui` 确认图片确实在包里）。
- **mod 类改动必须重启客户端**才生效；F3+T 只重载资源包，不能热重载 class（本轮“修好了却没变化”就是这个原因）。

## 2. 顶部资源条（灵动岛外观）

- 背景：**纯黑 `0xFF000000`** 胶囊。高度 = 图标 16 + 上下内边距 3×2 = **22**；左右内边距 **8**；**圆角半径 = 高度 ÷ 2**；用 `GuiGraphics.fill` **逐行内缩**（每行按 `sqrt(r² - dy²)` 计算 inset）画出真圆角，不要用直角矩形。
- 内容：`[ammo 图标] 存量 +每60s速率` 与 `[fuel 图标] 存量 +每60s速率`，组内顺序“图标 → 存量 → 速率”，组间 14；速率 0 时用灰色，其余用绿色；存量与速率都是整数（`floor`）。
- 位置与显隐：屏幕正上方居中（`TOP_MARGIN = 4`）；仅 `ResourceSyncPacket.running == true`（即 `/warproject game start` 之后）且本地玩家属于某队时渲染；无队伍或未开局不显示。
- 图标资源：`src/main/resources/assets/war_project/textures/gui/ammo.png`、`fuel.png`（各 128×128，PNG 头已校验）；ResourceLocation 形如 `war_project:textures/gui/ammo.png`。
- 所有可调常量集中在 `client/ResourceHudOverlay` 顶部（内边距、颜色、间距、尺寸），改外观只需动常量。

## 3. 资源硬上限 999（用户定调：锁死）

- 常量 `resource/ResourceData.MAX_AMOUNT = 999.0D`，**不配置、不可改**。
- 统一夹紧点：私有 `normalize(double)` 改为 clamp 到 `[0, 999]`（NaN/负值 → 0），因此以下路径全部受限：结算入账 `addStocks`、管理员 `setAmount`/`addAmount`、NBT 读档 `load`（旧档超过 999 的值读档即被压回 999）。
- `spend` 只做减法且先比较余额，天然不会越界。
- `/warproject resource set|add` 的**回显改为显示实际存量**（否则会出现“请求 1200、显示 1200、实际 999”的误导）；HUD 与 `resource list/team` 显示的都是夹紧后的值。

## 4. 本机构建 / 同步纪律（踩过的坑）

- **Gradle daemon 会卡死**（daemon 日志停在 “The daemon has started executing the build” 后长时间无输出）→ 一律用 `./gradlew build --no-daemon --offline`，并把输出重定向到日志文件再 tail。
- `build` 任务在 apply plugin 阶段会做证书校验：本机需加 `-Dnet.minecraftforge.gradle.check.certs=false`，否则构建在配置阶段即失败（`compileJava` 通常不会触发该失败）。
- 多 agent 并行改同一仓库时，**构建/覆盖 jar 前先检测**：`tasklist | grep -icE '^java'`，结果 > 0 就跳过构建（本轮 999 上限改动因此只做了 `compileJava`，未 build/未同步）。`javaw` 是游戏进程，与 Gradle 的 `java.exe` 区分对待。
- 同步后必须校验：`sha256sum build/libs/... totalwar/mods/...` 两份一致，并 `unzip -l` 确认新 class 与 `assets/war_project/textures/gui/*.png` 都在包里。
- javap 可执行文件不在 PATH：用 `"/c/Program Files/Java/jdk-21/bin/javap"`；反编译对象用 `~/.gradle/caches/forge_gradle/minecraft_user_repo/.../forge-1.20.1-47.4.10_mapped_official_1.20.1.jar`。

## 5. 截至本检查点的状态

- 双资源（ammo/fuel）、资源 HUD、999 上限的源码均已落地，`compileJava` 通过（仅既有的 `ResourceLocation(String,String)` deprecation 警告）。
- HUD（含灵动岛黑底与 11 参数 blit 修复）已 build + 同步（该次 sha256 `07d19a32…`，jar 时间 15:15），需要重启客户端才可见。
- **999 上限那一轮尚未 build / 未同步**（当时检测到 3 个 java 进程）；要生效需再执行一次 build + 同步 + 重启客户端。
- 网络协议号：`"4"`（`ResourceSyncPacket` 为 S→C，走 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)`）。

## 6. 关键文件

- 客户端显示：`client/ResourceHudOverlay.java`（灵动岛胶囊 + 图标 + 文本）、`client/ResourceClientState.java`（快照镜像）
- 资源经济：`resource/ResourceKind.java`、`resource/ResourceData.java`（含 `MAX_AMOUNT`）、`resource/ResourceService.java`、`resource/ResourceApi.java`、`resource/ResourceModule.java`
- 网络与命令：`net/WarProjectNetwork.java`（`ResourceSyncPacket`）、`command/WarProjectCommands.java`（`game` / `resource` / `node setresource`）
- 贴图与文档：`src/main/resources/assets/war_project/textures/gui/{ammo,fuel}.png`、`docs/ARCHITECTURE.md`
