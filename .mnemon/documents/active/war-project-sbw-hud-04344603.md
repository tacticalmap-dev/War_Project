---
id: "04344603-850c-48cf-8f57-e7313ea8c5ad"
title: "War Project 增量交接：SBW 载具炮镜隐藏资源 HUD，与「资源个人化」改造定调"
description: "接在 b75b521b（HUD 修复/灵动岛/999）之后的增量：SBW（Superb Warfare）可选兼容——载具第一视角隐藏资源灵动岛，判定为零依赖的类名+第一人称检查（含 javap 证据与中文路径 javap 陷阱）；以及用户已定调但尚未实现的「资源个人化」改造口径（每人全额、仅在线、支出归个人、旧档丢弃、命令按玩家、HUD 改个性化推送协议 5）与影响面清单。改资源/HUD/SBW 兼容前先读。"
status: "active"
created_at: "2026-09-19T16:59:31.930Z"
updated_at: "2026-09-19T19:33:38.678Z"
content_hash: "eabca35c46876eb78658f3b397ce85d42ee2a204ab2fddddd4d18aec46672a48"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/SuperbWarfareCompat.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceData.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceService.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceApi.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
session_ids:
  - "28d5a581-57b2-4315-827f-b581752b73bd"
  - "session-f7acdd28-0b0d-4e91-b421-d75c5ff31933"
memory_body_ids:
  []
---

---
id: "04344603-850c-48cf-8f57-e7313ea8c5ad"
title: "War Project 增量交接：SBW 载具炮镜隐藏资源 HUD，与「资源个人化」改造定调"
description: "接在 b75b521b（HUD 修复/灵动岛/999）之后的增量：SBW（Superb Warfare）可选兼容——载具第一视角隐藏资源灵动岛，判定为零依赖的类名+第一人称检查（含 javap 证据与中文路径 javap 陷阱）；以及用户已定调但尚未实现的「资源个人化」改造口径（每人全额、仅在线、支出归个人、旧档丢弃、命令按玩家、HUD 改个性化推送协议 5）与影响面清单。改资源/HUD/SBW 兼容前先读。"
status: "active"
created_at: "2026-09-19T16:59:31.930Z"
updated_at: "2026-09-19T16:59:31.930Z"
content_hash: "8fc6aa999359d3d61ed681129077c8a6a3eff2422b6c71cb7b2482cb071ab015"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/SuperbWarfareCompat.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceData.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceService.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceApi.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
session_ids:
  - "28d5a581-57b2-4315-827f-b581752b73bd"
memory_body_ids:
  []
---

# War Project 增量交接：SBW 载具炮镜隐藏资源 HUD，与「资源个人化」改造定调

本文只记录**既有托管文档未覆盖**的增量，接在 `b75b521b-f4c9-41fb-90c8-1928b6462d99`（HUD 渲染修复 / 灵动岛外观 / 999 硬上限）之后。
双资源（ammo/fuel）与资源 HUD 首版见 `3ea0c3c0-6079-4bcf-8580-c21edf9967b5`；资源经济与全局游戏生命周期首版见 `588ee5c2-515b-4c80-9d4a-fb8d4bb766c2`；模块总览见 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. SBW（Superb Warfare）可选兼容 —— 已落地

用户定调：玩家处在 SBW 载具第一视角（即炮镜视角）时，**不渲染资源灵动岛与资源量**。

- 实现位置：`src/main/java/com/flowingsun/war_project/client/SuperbWarfareCompat.java`（主源码集，**零编译期依赖**、不改 `build.gradle`），由 `client/ResourceHudOverlay` 在渲染前调用，命中即整条不绘制；SBW 缺失（或骑的不是 SBW 载具）时恒返回 false，模组行为不变。
- 判定条件（两条同时成立）：
  1. `Minecraft.player.getVehicle()` 的类名**沿父类链**以 `com.atsuishio.superbwarfare.entity.vehicle` 开头；
  2. `Minecraft.options.getCameraType().isFirstPerson()` 为真。
- 判定依据（javap 证据，非猜测）：SBW 0.8.9.1 自身的 `com.atsuishio.superbwarfare.client.overlay.RenderContext#isFirstPerson()` 反编译结果就是 `Options.m_92176_().m_90612_()Z`，即 vanilla `getCameraType().isFirstPerson()`；因此这里不反射调用对方 API 也完全等价。载具身份用类名前缀判断，避免对 SBW 产生编译期依赖。
- 覆盖范围：**只**隐藏资源 HUD；占领圆环 HUD（`NodeLJYSCaptureHudOverlay`）等其他 HUD 未动。
- 若要收紧“炮镜”口径（仅炮塔座位 / 真正开镜缩放时隐藏），SBW 侧可用入口：`VehicleEntity#getSeatIndex(Entity)`、`getZoomPos(Entity,float)` / `getZoomDirection(Entity,float)`、`getDefaultZoom(Entity)`、`useAircraftCamera(int)`、`getThirdPersonCameraPosition()`。
- 工具陷阱：SBW jar 在 `D:\mc\.minecraft\versions\totalwar\mods\[卓越前线] superbwarfare-0.8.9.1-hotfix-mc1.20.1-…-all.jar`，路径含中文与方括号，`javap` 直接指向它会报「找不到类」；先把 jar 复制到 ASCII 路径（如 `build/tmp/…/sbw.jar`）再 `javap -p -classpath`，并用 `unzip -l | grep` 定位类名。

## 2. 「资源个人化」改造 —— 用户已定调，**尚未实现**

用户原话：**resource 改为个人资源，每个人拥有和队伍一样的资源收入，但是支出则归类到个人。**

已由用户逐项选择确认的口径（勿再询问）：

| 口径 | 取值 |
| --- | --- |
| 收入分配 | 每个**在线**队员各得**全额**：队伍产出 X、在线 N 人 → 这 N 人各得 X（总发放 = N × X） |
| 离线 | 仅在线结算；离线期间不累积、不补账 |
| 支出 | `ResourceApi.spend` 与 `/warproject resource take` 从**个人**余额扣除 |
| 上限 | 每人每种资源仍是硬上限 **999**（`ResourceData.MAX_AMOUNT` 统一 clamp） |
| 旧存档 | `war_project_resources` 旧的 `teams[]` 段**丢弃**：读档忽略 + 一条日志，不做迁移 |
| 指令 | `resource player <玩家名>` 取代 `resource team`；`set/add/take` 一律按玩家 |
| 存储键 | 玩家名（`scoreboardName`）；改名视为新玩家（旧记录成为孤儿条目，不再入账） |
| HUD | 改为**个性化推送**：服务端逐在线玩家下发自己的存量与队伍速率；协议号将由 `"4"` 升到 `"5"` |

影响面（动手前先读现状代码）：`resource/ResourceData`（NBT `teams[]` → `players[]`，API 参数改玩家名）、`resource/ResourceService.settle`（队伍聚合 → 在线队员各得全额）、`resource/ResourceApi`（门面参数 + `snapshot`/`pushSyncAll`）、`net/WarProjectNetwork`（`ResourceSyncPacket` 由「全表」改「个性化字段」，删除 `ResourceTeamEntry`）、`client/ResourceClientState` + `client/ResourceHudOverlay`（条件改 `running && hasTeam`，去掉 `TeamClientState` 查询）、`command/WarProjectCommands`（resource 命令与 `game status` 统计口径）。

**不受影响**：地图侧 node 收益行（`optionalXaero/.../XaeroWorldMapScreenOverlay`、`optionalFtb/.../FtbChunksMapDivideClient` 读的是 `node.ammoPerMinute()` / `fuelPerMinute()`，与队伍/个人存量无关）、`ResourceKind`、`MapData.Node` 的产出字段、SBW 兼容、FTB 设置入口。

当前状态：改造计划已产出（含数据契约、边界与验收步骤），但用户 **dismiss 了计划审阅并要求改口径**，因此**代码尚未改动**——不要把现有实现当成个人资源（现在仍是队伍存量 + 协议 `"4"`），也不要重复确认上表口径，等用户给出新口径后再重新提计划。

## 3. 构建与部署纪律（沿用，摘要）

- 本机 Gradle **daemon 模式会卡死**：使用 `./gradlew build --no-daemon --offline -Dnet.minecraftforge.gradle.check.certs=false`（证书校验需显式关闭），约 30–50s。
- 动构建或覆盖 jar 前先检测并行工作：`tasklist | grep -icE '^java'`（`javaw.exe` 是游戏进程，不算构建）；有则跳过并说明。
- 同步后比对 `build/libs/war_project-1.0.0.jar` 与部署 jar 的 sha256；javap 用的 jar 副本与解包目录只放 `build/tmp/` 并事后清理。
- 客户端 mod 类改动必须重启游戏（`F3+T` 只重载资源包，无法热重载类）。

## 4. 本轮明确未做（避免误判为遗漏）

- 未实施「资源个人化」，仅记录口径与影响面。
- 未把 SBW 判定扩展到占领圆环 HUD 或其他 HUD。
- 未触碰 `docs/architecture.html` / `docs/architecture.json` 等生成物。

> 模块改名注记（2026-09-20）：文中 `wargame` / `Wargame*` 已于该日更名为 `nodeLJYS` / `NodeLJYS*`（`NodeLJYSModule` 的 id 为 `"nodeLJYS"`），本文引用名已同步更新；本文其余内容为改名前的交接记录。
