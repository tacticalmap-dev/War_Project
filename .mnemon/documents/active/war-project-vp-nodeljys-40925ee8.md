---
id: "40925ee8-8ff7-40e7-8db2-bc43689c4388"
title: "War Project VP 战局进度条与归零结束（nodeLJYS 模块）设计方案"
description: "VP 战局进度条的完整设计方案：左右两条独立条＋中央五角星列、按同盟簇记分、每占领一个 vpnode 每分钟扣对手 30 点、任一方归零自动 game end。含 HUD 双路径（CEF overlay.html / 内置 HTML 内核）改动点、协议号升 9、配置项、边界与验收标准。状态：方案已提交待批准，尚未实现；实现前先读本文与 3d31dded。"
status: "active"
created_at: "2026-09-20T10:56:14.907Z"
updated_at: "2026-09-20T10:56:14.907Z"
content_hash: "b3bc4ff68bfac1032f370a6dbf6d853ddafd7407df499e28af16a17c7314ecff"
source_paths:
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/module/GameStateService.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/nodeLJYS/NodeLJYSService.java"
  - "src/main/java/com/flowingsun/war_project/nodeLJYS/NodeOccupationService.java"
  - "src/main/java/com/flowingsun/war_project/team/TeamData.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "src/main/resources/assets/war_project/html/resource_island.html"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
session_ids:
  - "fa358e1f-813b-4f27-aeb1-ec4efc88cbec"
memory_body_ids:
  []
---

# War Project VP 战局进度条与归零结束（nodeLJYS 模块）设计方案

> **状态：设计方案（plan）已提交待批准，仓库中尚未实现任何一行。** 实现前先读本文与 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`（架构总览）、`588ee5c2-515b-4c80-9d4a-fb8d4bb766c2`（game 生命周期与 ENDED 终态）。
> 来源：用户需求原话「在 game start 后于灵动岛上方渲染进度条（初始 500 可配置，蓝＝己方/红＝敌方，按 map 内 vpnode 数量在中央渲染五角星显示占领动画）」「占领 vpnode 后按每个 vp 点每分钟 30 点扣除对方进度条，任一方归零即 game end」，以及随后 5 项口径问答的确认结果。

---

## 1. 用户拍板的口径（勿再询问）

| 议题 | 定调 |
| --- | --- |
| 双方界定 | 按**同盟簇**（队伍＋盟友连通分量）为一方；客户端显示「自己所在簇」（蓝）与「其余簇中分数最高者」（红） |
| 布局 | **左右两条独立条**：左蓝（己方）、右红（敌方），**五角星横排在两列之间的中央** |
| 五角星 | 颜色＝当前归属（中立白／己方簇蓝／敌对簇红）；被占领中时星外一圈**顺时针进度弧**（弧色＝进攻方阵营色）；占领完成瞬间闪一次 |
| 归零 | **立即自动** `GameStateService.transition(server, ENDED)` ＋全服播报胜方 |
| 显示范围 | 只要阶段为 RUNNING 就显示（**无队伍玩家也可见**，己方条按中立处理） |

---

## 2. 现状调研证据（可直接复用，省去重新翻代码）

- **阶段内核**：`module/GameStateService.transition(server, target)` 先回调 `Listener.onBeforeGamePhaseChanged`，改阶段后再回调 `onGamePhaseChanged`；`GamePhase` = STOPPED/RUNNING/ENDED，**刻意不落盘**（开服固定 STOPPED）。
- **VP 数据**：`map/MapData.Node.vp()`、`isFaction`/`normalizeFaction`、`MapDivideStateApi.setNodeVp`；客户端 `client/ClientMapState.ClientNode` 已带 `vp()`/`factionId()`（MapSync 快照已含，**星的归属颜色不需要新包**）。
- **占领进度源**：`nodeLJYS/NodeOccupationService.progress(nodeId)`（`attackerFactionId`、`progressSeconds`、`required`），由 `NodeLJYSService.tickNode()` 每 20 tick 维护，归属变化发生在 `next >= required → MapDivideStateApi.applyNodeCapture(server, nodeId, attacker)`。战局服务**只读**这些状态，不改占领算法。
- **势力来源**：`team/TeamData.teams()`（`Collection<Team>`，`Team.allies()`）+ `areAllied` → 可在服务端算连通分量；客户端 `team/TeamClientState.teamOf/areAllied`。
- **HUD 双路径（关键约束）**：
  - **CEF 路径**（主）：`client/cef/CefWebRenderer`，表面常量 `SURFACE_WIDTH=206`、`SURFACE_HEIGHT=196`、`BAR_HEIGHT=18`、`TOP_MARGIN=2`；每 tick `pushSnapshot()` → 页面 `window.wp.apply(json)`；页面 `assets/war_project/web/overlay.html`（`.stack > .slab > .bar/.body`，岛是 slab 的收起态，面板向下展开）。命中检测 `islandContains()` 用 `surfaceY + BAR_HEIGHT`、`panelContains()` 用 `SURFACE_HEIGHT` —— **改几何必须同时改这两处与页面 CSS**。
  - **内置 HTML 内核路径**（降级）：`client/ResourceIslandView`（`HtmlViewHost`，`TOP_MARGIN=4`，文档 `assets/war_project/html/resource_island.html`），绘制点两处：`client/ResourceHudOverlay.render`（无屏时）与 `client/ResourceTransferController.onRenderPost`（仅 ChatScreen）。
  - **内核能力边界**：标签 `div/span/b/strong/img/button/input/hr` + SVG 子集（`svg/path/circle/ellipse/rect/polygon/polyline/line/use/stop`）；支持 `transition`，**不支持 animation/keyframes** —— 一切动画必须 tick 驱动（`HtmlViewHost.tick(deltaMs)` 插值）。
- **网络**：`net/WarProjectNetwork`，`PROTOCOL` 当前为 `"8"`，包用 `nextId()` 顺序注册；`broadcastMap/broadcastTeams` 模式、登录补发在 `WarProject.onPlayerLoggedIn`。
- **配置**：`Config`（`ForgeConfigSpec` 定义 + `onLoad` 同步静态字段）。

---

## 3. 服务端设计（nodeLJYS 包内新增，遵循「适配代码归对应模块包」）

新增 `nodeLJYS/VpWarService`（单例 `active()/clearActive()`，仿 `NodeLJYSService`）：

1. **簇与指纹**：簇＝`TeamData.teams()` 上 `allies` 的连通分量；**簇指纹 = 簇内队伍 id 升序 `join("|")`**。每次结算重算簇集合。
2. **分数**：`Map<String, Double> scores`，键为簇指纹；指纹**新出现**时初始化为 `Config.vpWarScoreStart`（即合并/拆分/加队导致指纹变化时，该簇分数重置为初始值并记 INFO 日志）。
3. **结算**（`ServerTickEvent`，`Phase.END`，每 20 tick 一次，`elapsedSeconds = 1.0`）：
   - 非 RUNNING 直接返回并清零计数器（STOPPED 冻结）。
   - 遍历 `MapData.get(server).nodes()` 中 `vp()==true` 者：`owner = normalizeFaction(node.factionId())`；owner 不是有效队伍或不属于任何簇（neutral）→ **跳过，不扣任何人**。
   - 对每个 `key != ownerCluster` 的簇：`scores[key] -= Config.vpWarDrainPerMinutePerNode / 60.0 * elapsedSeconds`（多个 vpnode **累加**）。
   - 分数 clamp 到 0；任一 `score <= 0` 且未在结束流程中 → 结束。
4. **结束流程**（`boolean ending` 防重入）：胜方＝剩余分数最大且 >0 的簇（全为 0 则「平局」）→ `server.getPlayerList().broadcastSystemMessage(...)` 播报 → `GameStateService.active().transition(server, GamePhase.ENDED)`。**不要自己改 `GamePhase`**，必须走 `transition`，否则其它订阅者不触发。
5. **阶段订阅**（实现 `GameStateService.Listener`，由 `nodeLJYS/NodeLJYSModule` 在 `onServerStarting` 注册、`onServerStopping` 反注册）：`→ RUNNING` 初始化分数；`→ STOPPED` 冻结保留；`→ ENDED` 清空并复位 `ending`（进度条随 RUNNING 条件自然隐藏）。
6. **广播节流**：结算后仅在「分数（1 位小数）或任一 vpnode 归属/占领百分比（1% 粒度）变化」时广播，最多 1 次/秒。
7. 只读门面：`snapshot(server)` 供网络包与 `vpwar status` 共用。

---

## 4. 网络协议（`net/WarProjectNetwork`）

- **`PROTOCOL` 由 `"8"` 升为 `"9"`**（新增包/字段必须升版）。
- 新增 S→C 包 `VpWarStatePacket(boolean running, double maxScore, List<SideEntry> sides, List<NodeEntry> nodes)`：
  - `SideEntry(String key, List<String> teamIds, String name, double score)`
  - `NodeEntry(String nodeId, String name, String ownerFaction, String ownerSideKey, String attackerSideKey, double capturePercent)`
  - `ownerSideKey` 让客户端**直接判定蓝/红**（与自己所在簇 key 比较），不需要客户端重算连通分量。
  - 编解码沿用现有模式：`writeBoolean/writeDouble/writeVarInt + writeUtf`，集合长度前缀；`handle` 用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 投递到客户端状态类。
- 新增 `sendVpWar(ServerPlayer)` / `broadcastVpWar(MinecraftServer)`；`WarProject.onPlayerLoggedIn` 补发一次。

---

## 5. 客户端渲染

### 5.1 状态与数据流
- 新增 `client/VpWarClientState`：镜像包数据 + `mySideKey()`（用 `TeamClientState.teamOf(玩家名)` 匹配 `sides[].teamIds`）、`enemySide()`（其余簇中 score 最高）、`sideOf(node)`；登出时 `reset()`。
- `client/web/WebSnapshot` 增加 `vp` 段，`WebSnapshots.current()` 组装，`WebJson.write()` 输出 `"vp":{...}`（并入同一次 `apply` 推送，不新增推送通道）；`WebSnapshot.empty()` 同步扩展以保持 `equals` 去重语义。

### 5.2 统一视觉规范（两条路径必须一致）
| 元素 | 规格 |
| --- | --- |
| 条高/圆角 | 6 GUI px / 3 px |
| 己方蓝 | `#4FA3FF`（与 `NodeLJYSService.CAPTURE_NOTICE_CAPTURED_COLOR` 一致） |
| 敌方红 | `#FF4D4D`（与 `CAPTURE_NOTICE_LOST_COLOR` 一致） |
| 中立 | `#E5E7EB`；条槽底 `#FFFFFF1F` |
| 数值文本 | 9 px、`tabular-nums`，蓝条左端 / 红条右端 |
| 五角星 | 基准 10 px，按星区宽自适应（下限 5 px）、间距 2 px、单行不换行；星区宽 `min(140, 8 + count*12)`，居中 |
| 占领弧 | 星外 1.5 px 圆环顺时针点亮，颜色＝进攻方阵营色 |
| 完成闪烁 | owner 变化瞬间 200 ms 白色高亮＋轻微放大（客户端对比前后快照，服务端不必发 flash 字段） |

### 5.3 CEF 路径
- `CefWebRenderer`：`SURFACE_WIDTH` 206 → **320**，新增 `VPWAR_HEIGHT = 24`，`SURFACE_HEIGHT` 196 → **220**（＝24+196），`TOP_MARGIN` 保持 2；`islandContains()` 的岛顶改为 `surfaceY + VPWAR_HEIGHT`；`panelContains()` 用新高度；`wantsSurface()/surfaceVisible()/drawSurface()` 的显示条件从「岛可见」放宽为「**RUNNING 且非 SBW 炮镜** 或 岛可见」，显隐交给页面 class。
- `overlay.html`：`.stack` 改纵向 flex，首子元素新增 `#vpwar`（`pointer-events:none`，且**不参与 slab 宽度测量**，保持 `measureBarWidth()` 语义），其后仍是 `#slab`；`apply(snapshot)` 增加 `snapshot.vp` 处理（条宽 = `score/max × halfWidth`、星状态、`conic-gradient` 弧、`.flash` class）。

### 5.4 内置内核路径
- 新增 `client/VpWarBarView` + `assets/war_project/html/vpwar_bar.html`：横向 flex `[蓝条+数值][星列][数值+红条]`；星用 `<svg viewBox>` + `<polygon>`；**占领弧用 24 段 `<line>` 组成圆环**（内核没有 conic-gradient），按百分比点亮。
- 接入点：`ResourceHudOverlay.render` 与 `ResourceTransferController.onRenderPost` 分支里**先画进度条再画岛**；`ResourceIslandView.TOP_MARGIN` 4 → **30**（＝4+24+2），保证岛正好落在进度条下方。

---

## 6. 配置与命令

```java
vpWarScoreStart              = defineInRange("vpWarScoreStart", 500.0D, 1.0D, 1_000_000.0D);
vpWarDrainPerMinutePerNode   = defineInRange("vpWarDrainPerMinutePerNode", 30.0D, 0.0D, 100_000.0D);
```
只读命令 `/warproject vpwar status`：回显 `max=`、每簇（key/teams/score）、每个 vpnode（owner/side/capture%）。不新增可写子命令。

---

## 7. 边界、失败模式与必读交互点

| 场景 | 行为 |
| --- | --- |
| map 内无 vpnode | 进度条显示、两侧恒为初始值、星列为空 |
| 只有 1 个簇 | 无对手可扣，永不自劫结束 |
| vpnode 归属 neutral | 不扣任何一方 |
| 双方同 tick 归零 | 判平局，仍切 ENDED |
| 队伍/盟友变更 | 簇指纹变化 → 该簇分数重置为初始值（记日志） |
| STOPPED / ENDED | 冻结（保留分数）／清空并隐藏 |
| CEF 不可用 | 自动降级到内置内核路径（现有机制） |
| SBW 炮镜、其它 GUI | 与资源岛一致：整块表面隐藏（ChatScreen 例外，岛与进度条都画） |

**必读交互点（本方案未展开、实现与验收时必须确认）**：归零自动 `transition(ENDED)` 与手输 `/warproject game end` **完全等价**，因此会连带触发既有 ENDED 链路 —— `resource` 清空资源、`nodeLJYS` 重置全部 node 为 neutral 并清占用进度、以及 **`recovery` 模块按注册顺序（在最后）把地图恢复回 game start 前的快照**（见 `d24b47cb-4018-45a1-bed3-e71ede24d5a8`）。若「归零即收场」不希望地图被回滚，需要与用户确认后调整（例如归零走一条不触发恢复的结束路径）；**不要**在未确认的情况下绕过 `GameStateService.transition`。

---

## 8. 验收标准（方案自带，可直接当测试清单）

| # | 验收点 |
| --- | --- |
| A1 | `game start` 后进度条出现，双方各为 `vpWarScoreStart`；`vpwar status` 回显一致 |
| A2 | 占 1 个 vpnode 后对手条 60 s 内下降 30±2（结算粒度 1 s） |
| A3 | 占 N 个 vpnode 时速率 = 30×N/分钟 |
| A4 | 任一方 ≤0 → 1 s 内切 ENDED、全服播报胜方、既有 ENDED 行为照常发生 |
| A5 | 星数 = map 内 vpnode 数，颜色随归属，占领中显示顺时针弧，完成闪一次 |
| A6 | 无队伍玩家也看得到进度条（蓝侧按中立处理） |
| A7 | `webRenderer=native`（内置内核）与 `auto/chromium`（CEF）两条路径都验证 |
| A8 | `gradlew.bat build --no-daemon --offline` BUILD SUCCESSFUL（本机 Gradle daemon 模式会卡死） |

---

## 9. 假设与非目标

**假设**：初始 500 为「每个同盟簇各自 500」；30 点/分钟 = 「每座被对手占领的 vpnode 每分钟扣对手 30 点，多座累加」；双方同 tick 互扣；仅 `vp()==true` 参与；星序按 `node.id` 字典序稳定；战局分数**不落盘**（与阶段状态一致）；进度条与资源岛同样只在「无屏 + ChatScreen」绘制。

**非目标**：不改 `NodeLJYSService` 占领算法与 `NodeOccupationService`；不新增 `GamePhase`；不做历史战绩/赛后统计；不改 Xaero/FTB 地图渲染；不做进度条点击交互；不新增 SavedData。
