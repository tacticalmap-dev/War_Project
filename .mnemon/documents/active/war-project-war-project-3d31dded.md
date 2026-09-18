---
id: "3d31dded-b45f-4f11-a183-0d1aa78e4faa"
title: "War Project (war_project) 架构、占点系统与可选依赖集成说明"
description: "War Project 模组的模块化结构、node/warzone 数据模型、服务端命令树、网络同步、占领逻辑、Xaero/FTB 可选集成与构建注意事项。修改本模组前先读此文档。"
status: "active"
created_at: "2026-09-12T07:03:48.002Z"
updated_at: "2026-09-12T07:03:48.002Z"
content_hash: "a665cea8fcd98491345f0b9520449cf956d038322f70afb8e2cced563a191cc7"
source_paths:
  - "build.gradle"
  - "src/main/java/com/flowingsun/war_project/WarProject.java"
  - "src/main/java/com/flowingsun/war_project/module/ModuleRegistry.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/team/TeamData.java"
  - "src/main/java/com/flowingsun/war_project/wargame/WargameService.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/main/java/com/flowingsun/war_project/client/WargameCaptureHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/optionalFtb/java/com/flowingsun/war_project/compat/ftb/FtbChunksMapDivideClient.java"
  - "src/main/resources/mixins.war_project.json"
  - "src/main/resources/META-INF/mods.toml"
session_ids:
  - "ad314282-4cef-4ea8-b96a-432f0104bd14"
memory_body_ids:
  []
---

# War Project (war_project) 架构与集成说明

Minecraft 1.20.1 / Forge 47.x。模组 id `war_project`，显示名 War Project，作者 flowingsun，基础包 `com.flowingsun.war_project`。
参考模组源码：`E:\mc_mod_dev\modernwar-town`（结构、命令、HUD、Xaero 渲染均以它为蓝本简化）。
构建产物部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

## 1. 模块化结构

- `WarProject.java`：唯一 `@Mod` 入口，只做四件事——注册配置（`Config.SPEC`）、注册网络（`WarProjectNetwork.register()`）、把生命周期事件转发给 `ModuleRegistry`、玩家登录时下发地图与队伍快照。
- `module/WarProjectModule.java`：模块接口，含 `id()`、`onCommonSetup()`、`onClientSetup()`、`onServerStarting(server)`、`onServerStopping(server)`、`onRegisterCommands(dispatcher)`。
- `module/ModuleRegistry.java`：持有模块列表并统一转发。
- 当前模块固定为三个：`map/MapDivideModule`、`team/TeamModule`、`wargame/WargameModule`。
- 新增功能优先做成新模块并加进主类列表，不要在 `WarProject` 里堆逻辑。

## 2. 数据模型（SavedData）

`map/MapData.java`，存档键 `war_project_map`，只保存两类对象：

- node：`id`、`name`、`faction_id`、`color_rgb`、`updated_at`、`chunks`
- warzone：`id`、`node_id`、`faction_id`、`color_rgb`、`updated_at`、`chunks`

关键约定：

- node 的**字符串 id 是真实主键**（不是数字编号）。重命名 node 必须同步更新绑定 warzone 的 `node_id` 和 `NodeOccupationService` 里的进度引用，否则占领状态会丢失。
- 地图**允许空区块**：不被任何 node / warzone 包含的区块合法存在，不参与占领、不渲染、无归属。地图不需要被填满。
- 战区约束（`saveNodeWithWarzone` 校验）：
  - 一个 warzone 必须绑定且只绑定一个 node；
  - 必须包含该 node 的全部区块；
  - 必须至少包含一个不属于任何 node 的额外区块；
  - warzone 与 warzone 之间不允许重叠；
  - 额外区块不能落在其他 node 的区块上。
- node 被占领时，绑定 warzone 的 `faction_id` 一起变；这是 `MapDivideStateApi.applyNodeCapture` 的行为。
- 没有 town、没有旧式「战区包含多个城镇」结构，也没有 wartime/peacetime 时间状态。

`team/TeamData.java`：存档键 `war_project_teams`。保留 team admin 职位（`/warproject team admin set|remove`），但**不提供邀请/踢人**（无 invite、kick、accept、deny）。

## 3. 命令

只有一个服务端根命令 `/warproject`（`command/WarProjectCommands.java`）。**没有任何客户端命令。**

- `/warproject chunk info`
- `/warproject map set <from> <to>`
- `/warproject node list | info <id> | setfaction <id> <faction> | rename <old> <new> <name> | delete <id>`
- `/warproject warzone list | info <id> | node <nodeId> | setfaction <id> <faction> | delete <id>`
- `/warproject progress node <nodeId>`
- `/warproject team add <team> [displayName] | remove | empty | join | leave | list | msg switch | admin set|remove | modify <team> <property> <value> | ally add|remove|list`

已明确删除、不要恢复：`/warprojectclient ...`、`/warproject progress here`、`/mwteam`、`/mwadmin`、`/modernwar`、`team accept`/`deny`、`teamadmin invite`/`kick`。

## 4. 网络（`net/WarProjectNetwork.java`）

单通道 `war_project:main`，协议版本 "1"。包：

- `MapSyncPacket` / `TeamSyncPacket`：NBT 快照下发（客户端的 `ClientMapState` / `TeamClientState` 各自维护 version 计数）。
- `CaptureIntentPacket`：客户端每秒上报「我在该 node 内且想占领」。
- `CaptureProgressPacket`：完整占领快照（nodeId / currentFaction / pendingFaction / previousFaction / attackerFaction / defenderFaction / neutralized / progressSeconds / requiredSeconds / progressPercent / 兵力计数 / factionsInNode），**只发给站在该 node 里的玩家**。
- `CaptureNoticePacket`：占领提示文本 + 颜色。
- `CreateNodeWarzonePacket`：FTB 编辑器创建 node+warzone，服务端校验 OP 权限（`hasPermissions(2)`）。

客户端侧只通过 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 触碰客户端类，避免服务端加载客户端类。

## 5. 占领逻辑（`wargame/WargameService.java` + `NodeOccupationService`）

- 客户端：在 node 区块内、有队伍、且该 node 不属于自己/盟友时，每 20 tick 发一次占领意图。
- 服务端：每 20 tick 结算；**只有唯一领先阵营才推进**（票数并列不推进）；无领先者或领先者与当前归属同盟时按 `nodeCaptureRecoveryPerSecond` 回退。
- 进度到 50% 先把 node 和绑定 warzone 置为 `neutral`；到 100% 归属攻击方，随后清空进度并向范围内玩家发 `Captured <name>` / `Lost <name>` 提示。
- 配置项：`nodeCaptureBaseSeconds`、`nodeCaptureRecoveryPerSecond`、`capturePlayerCountRateMultiplier`、`capturePlayerCountRateMultiplierCap`、`captureDebugMode`。没有距离/连通惩罚。

## 6. 占点 HUD

`client/WargameCaptureHudOverlay.java` 通过 `RegisterGuiOverlaysEvent` 注册在 `VanillaGuiOverlay.HOTBAR` 之上：

- 进度圆环（size 22 / thickness 3 / 72 段三角带），底环 + 灰轨道 + 彩色进度；
- 圆环中央显示 node 名称（无名字回退 id，截断 14 字符），下方显示百分比；
- 上方兵力比条（宽 44）按 `factionsInNode` 分组为阵营侧，己方 `0xFF4FA3FF`、敌方 `0xFFFF4D4D`，多攻方时用队伍颜色；
- 占领提示居中显示在屏幕中下方。

`client/WargameCaptureHudState.java`：快照 TTL 40 tick、平滑系数 0.25；两阶段进度映射——有敌方防守且未中立化时进度的前一半映射为「削弱中」（圆环从满走向空），后一半映射为「占领中」。`WargameCaptureNoticeHudState` 同样 40 tick。

HUD 只在「玩家所在 node」与快照 nodeId 一致时显示。参考模组的 wartime 门槛已被去掉，因为本模组占领始终可用。

## 7. Xaero 集成（可选）

源码放在 `src/optionalXaero/java`，仅当本地能找到 Xaero jar 时才加入编译。分三层：

- **世界地图**：`XaeroWorldMapScreenOverlay` 监听 `ScreenEvent.Render.Post`，识别 `xaero.map.gui.GuiMap`，反射读 `cameraX`/`cameraZ`/`scale`/`screenScale` 后每帧直接 `GuiGraphics.fill` 绘制。**不使用 Xaero 高亮缓存**，否则缩放/移动时刷新不及时。`WarProjectWorldMapHighlighter` 只保留存在性、不返回颜色，避免重复绘制。
- **小地图**：`XaeroMinimapScreenOverlay` 由 `XaeroCommonMinimapRendererMixin`（Redirect `MinimapElementOverMapRendererHandler.render`）在 Xaero 绘制元素前调用。`WarProjectMinimapHighlighter` 已关闭颜色输出，避免与新叠加层重复上色。
- **渲染规则**：node 边界为**虚线**；warzone 边界为**实线**，但与相邻 warzone 同阵营/同盟时不绘制（同一 warzone 内部相邻也不绘制）。颜色按本地玩家视角：友方蓝 `0x20A4F3`、敌方红 `0xFF1F57`、中立白 `0xFFFFFF`；warzone 底色 alpha `0x40`。
- 世界地图用 `EdgeCollector` 合并同线段的边界，带**优先级**（node 高于 warzone）和 **reserved pixels**（node 虚线像素不被 warzone 实线覆盖），避免出现多余线和内部网格。
- 虚线长度跟随缩放：按投影出的单区块屏幕像素推算 dash/gap（约 35% / 22%，各自有上下限），不要退回固定屏幕像素周期。

## 8. FTB Chunks 编辑界面（可选）

`src/optionalFtb/java`，需要 FTB Library + FTB Chunks 两个 jar。使用 FTB Library 控件（`BaseScreen`/`SimpleTextButton`/`ModalPanel`/`TextBox`/`ContextMenu`）挂在大地图上：

1. 选中 node 区块（支持按住拖拽连续选择，拖拽会补齐路径）；
2. 创建 node，弹窗输入 id 与 name；
3. 继续选择 warzone 范围（node 区块被保护，无法被取消选中）；
4. 确认后发 `CreateNodeWarzonePacket`。

工具栏只保留 Node 相关操作，没有 town/warfare 按钮。

## 9. 构建与可选依赖

- **不**添加 FTB/Architectury Maven 仓库，也**不**用 implementation 依赖它们；只从本地 jar 做 `compileOnly`。
- `build.gradle` 里的 `findOptionalJar` 会扫描 `libs/` 和 `D:/mc/.minecraft/versions` 自动挑选 jar。**必须排除文件名含 `neoforge`/`fabric`/`quilt` 的候选**：`"neoforge"` 包含子串 `"forge"`，会误选 NeoForge 1.21.1 的 FTB jar（class 版本 65 vs 61）导致 `compileJava` 直接失败。排序时优先文件名含 `1.20.1` 的候选。
- `-PwarProjectDisableOptionalCompat=true` 可关闭可选源码集，用于验证纯核心构建（注意该构建会覆盖 `build/libs` 里的 jar，之后要重跑默认构建）。
- `mods.toml` 中 `architectury`/`ftblibrary`/`ftbchunks`/`xaeroworldmap`/`xaerominimap` 全部 `mandatory=false`。
- `mixins.war_project.json`：`required=false`，`injectors.defaultRequire=0`，并由 `mixins.WarProjectMixinPlugin` 的 `shouldApplyMixin` 检查目标类与 mixin 类是否真实存在，从而在未安装 Xaero 时不加载。

## 10. 踩过的坑

- **颜色格式不能混用**：`GuiGraphics.fill` 用 ARGB，Xaero 高亮像素数组用 Xaero 自己的通道顺序。混用会出现异常黄色/高不透明块。渲染核心因此分成 `relationFillArgb`/`relationEdgeArgb` 与 `relationFillXaero`/`relationEdgeXaero`。
- **Xaero 缓存刷新**：`regionHash` 必须把地图版本、队伍版本、本地队伍与最终关系色都算进去，否则阵营/归属变化后地图不重绘。
- **mixin 改动需要重启客户端**才能生效，热重载不覆盖 mixin。
- 命令与 HUD 的 node 主键一律是字符串 id，任何新的网络包/状态类都要用 `String nodeId`，不要再引入数字编号。
