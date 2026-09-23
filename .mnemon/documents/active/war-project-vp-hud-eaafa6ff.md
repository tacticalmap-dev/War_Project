---
id: "eaafa6ff-e567-4077-8178-e5417cd3f6f1"
title: "War Project VP 战局进度条：实现落点与 HUD 排障交接"
description: "VP 战局进度条已实现的代码落点、CEF 表面被改导致整个 HUD 消失的事故与回退、以及「条在游戏内仍不显示」的已排除项、诊断日志与下一步二分法、可复用的离线验证手法。设计方案见 40925ee8，模块总览见 3d31dded，game 生命周期见 588ee5c2。"
status: "active"
created_at: "2026-09-20T13:09:17.011Z"
updated_at: "2026-09-20T13:09:17.011Z"
content_hash: "c6976ad768964c180a206d8ff2846389c2d354520a69d2a6b42680f3e13c230c"
source_paths:
  - "src/main/java/com/flowingsun/war_project/nodeLJYS/VpWarService.java"
  - "src/main/java/com/flowingsun/war_project/nodeLJYS/VpWarState.java"
  - "src/main/java/com/flowingsun/war_project/client/VpWarBarView.java"
  - "src/main/java/com/flowingsun/war_project/client/VpWarClientState.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/resources/assets/war_project/html/vpwar_bar.html"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "scripts/verify-overlay-page.mjs"
session_ids:
  - "52bf524f-d7f9-485e-b49c-c73990ae3bee"
memory_body_ids:
  []
---

# War Project VP 战局进度条：实现落点与 HUD 排障交接

本文记录仓库里**已经实现**的东西与**仍未解决**的排障。设计方案见 `40925ee8-8ff7-40e7-8db2-bc43689c4388`（写于实现前，其中「改 CEF 表面尺寸」已被实践否决）；模块与数据模型见 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`；阶段与 ENDED 见 `588ee5c2-515b-4c80-9d4a-fb8d4bb766c2`。

## 1. 用户口径（勿再询问）

- 双方按**同盟簇**（队伍 + 传递盟友）；客户端显示自己所在簇（蓝）与其余簇中分数最高者（红）。
- 双方初始分 `vpWarScoreStart`（默认 500，可配置）。
- 己方占据的每个 vpnode 每分钟扣对手 `vpWarDrainPerMinutePerNode`（默认 30），多座累加。
- 任一方归零 → **立即自动** `GameStateService.transition(ENDED)` + 全服播报胜方。
- 布局：灵动岛**上方**、左蓝／右红两条独立条，**五角星横排在两列之间的中央**（每 vpnode 一颗）；星色＝归属（中立白／己方簇蓝／敌对簇红），被占领中星外顺时针进度弧，归属变化闪一次。
- 显示条件：阶段 RUNNING 即显示（无队伍玩家也可见，己方条按中立处理）。

## 2. 已实现的落点

- `nodeLJYS/VpWarService`：算同盟簇（键 = 簇内队伍 id 升序用 `|` 连接）；RUNNING 每 20 tick 结算；某 `vp()` 节点归 A 簇时 A 以外每个簇按 `vpWarDrainPerMinutePerNode / 60` 扣分；任一簇 ≤ 0 → 播报胜方 + `transition(server, ENDED)`（`ending` 防重入）；RUNNING 初始化、ENDED 清空、STOPPED 冻结；分数 1 位小数、占领百分比 2 位小数量化后**仅在变化时**广播。
- `nodeLJYS/VpWarState`：快照 record（`running, maxScore, sides[], nodes[]`）。
- `Config`：`vpWarScoreStart`（500）、`vpWarDrainPerMinutePerNode`（30）。
- `net/WarProjectNetwork`：`PROTOCOL` **8 → 9**；新增 S→C `VpWarStatePacket`（`nextId()` 顺序注册在 `OutOfMapWarningPacket` 之后；登录补发 + 变化广播）。
- `client/VpWarClientState`：客户端镜像（`mySide()` / `enemySide()` / `isMySide(key)`）。
- `client/VpWarBarView` + `assets/war_project/html/vpwar_bar.html`：条由**内置 HTML 内核**绘制，与 CEF 解耦；接入 `client/ResourceHudOverlay.render`（先画条再画岛）与 `client/ResourceTransferController.onRenderPost`；`tick` 在 `onClientTick` 的 CEF 早退之前。
- 位置协调：内置路径 `ResourceIslandView.TOP_MARGIN` 4 → 30；CEF 表面 `surfaceY = TOP_MARGIN + 24 + 3`（整块下移给条让位）。
- 只读命令 `/warproject vpwar status`（start 分、每簇分数、每 vpnode 的 owner / 攻方 / 占领百分比）。

## 3. 事故：CEF 表面不能随便改（已修复）

CEF 表面是「岛 + 面板」**一整张纹理**（`CefWebRenderer` 的 `SURFACE_WIDTH/HEIGHT` + `CefOsrView.setGuiSize/getScreenInfo` + `web/overlay.html`）。把它从 176×150 改成 320×177 并在页面里加战局条层后，**整个 HUD（含灵动岛）消失**，而日志显示 CEF 启动正常、页面已加载、无异常。修复方式：CEF 侧全部回退（尺寸、页面、可见性判断 `running && hasTeam && 非 SBW 炮镜`），只保留整块表面下移 27px；条改由内核常驻绘制。**新增 HUD 元素不要动 CEF 表面几何。**

## 4. 仍未解决：条在游戏内不显示

已排除（有证据）：诊断日志 `War Project CEF surface shown: running=true hasTeam=true island=true war=true screen=none`（`war` = `VpWarClientState.isRunning()`）⇒ 数据已到客户端、表面与岛正常；该时段无 `war_project` 异常。

已做的稳健化：条**默认可见**（只用一个 `.off` 类隐藏，不再依赖 opacity 淡入）；星槽 32 → 16；每颗 `<svg>` 补 `width/height`；行宽由 Java 每帧钉成屏宽；`tick/render` 全程 try/catch，失败只禁用条。离线用项目自己的 HTML 内核解析该页面已验证：`vpbar` → `display=flex / flex-direction=row / justify=center / align=center / height=24`，`vpMineTrack` → `150×6`，`vpStar0` → `12×12` 且含 `polygon + path` ⇒ **页面结构与 CSS 解析层无问题**。

下一步（按成本排序）：

1. 重启后读日志 `War Project war bar: show=… node=… visible=… off=… rect=WxH at (x,y) nodes=… mine=… foe=…`（前约 1200 帧每 100 帧打印一次）。`node=false` ⇒ 内核文档里找不到 `#vpbar`；`rect` 为 0 或坐标为负 ⇒ 布局问题。
2. 把 `webRenderer` 临时改成 `"native"` 二分：岛与条都走内核。若岛在画而条不在 ⇒ 问题在条的文档/绘制；若岛也不在 ⇒ 内核路径整体没在画。注意 CEF 活跃时内核路径的岛从不绘制，**内核路径此前从未在真机跑过**，这是首要怀疑方向。

## 5. 可复用的验证手法

- 真实 Chromium 离线复现（验 CEF 页面）：`NODE_PATH=<dsh profiles>/web/node_modules` 下用 node 跑 `chromium.launch()` + `file://` 加载「把 `__AMMO_ICON__`/`__FUEL_ICON__` 换成真实 PNG base64」的 `overlay.html`，再 `page.evaluate(s => window.wp.apply(s), snapshot)` 并读 `getComputedStyle` / `getBoundingClientRect`。坑：headless 下 `opacity` 过渡约 1.2s 才稳定，400ms 时可能仍读到 `0`。
- 内核离线解析（不需要 MC 运行时）：`javac -cp build/classes/java/main tmp/Probe.java && java -cp "build/classes/java/main;tmp" Probe <html>`，用 `HtmlDocument.parse` + `byId` + `node.style.*` 检查标签/CSS 是否被内核正确解析。
- 页面回归校验：`node scripts/verify-overlay-page.mjs`（当前 17 项，覆盖岛/面板「没变就不动 DOM」的契约）。

## 6. 源文件

`nodeLJYS/VpWarService.java`、`nodeLJYS/VpWarState.java`、`nodeLJYS/NodeLJYSModule.java`、`net/WarProjectNetwork.java`、`command/WarProjectCommands.java`、`Config.java`、`client/VpWarClientState.java`、`client/VpWarBarView.java`、`client/ResourceHudOverlay.java`、`client/ResourceTransferController.java`、`client/ResourceIslandView.java`、`client/cef/CefWebRenderer.java`、`assets/war_project/html/vpwar_bar.html`、`assets/war_project/web/overlay.html`、`scripts/verify-overlay-page.mjs`、`docs/ARCHITECTURE.md`。
