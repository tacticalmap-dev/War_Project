---
id: "437db98f-3347-4ce2-8050-91259140564c"
title: "War Project 增量交接：个人资源落地、全局 HTML 渲染内核与灵动岛资源转移"
description: "接在 04344603（SBW 兼容 + 个人化定调）之后的落地增量：个人资源已实现（players[] 存储、每人全额仅在线、transfer 校验链、协议号 6、指令按玩家）；新增非模块的全局 HTML/CSS 子集渲染内核（支持面、refreshStyles 关键机制、11 参数 blit、EditBox 复用、模板回退）；灵动岛改 HTML 渲染并新增聊天栏右键转移面板（触发时机、双渲染路径、事件拦截、上限与离线规则、动画）。改资源/HUD/UI 前先读。"
status: "active"
created_at: "2026-09-19T17:35:34.217Z"
updated_at: "2026-09-19T17:35:34.217Z"
content_hash: "229ae496baa02b16bb5a2deb1387eb5b5d0996da7b152fb272118b2df0fa0a15"
source_paths:
  - "src/main/java/com/flowingsun/war_project/resource/ResourceData.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceService.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceApi.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/main/java/com/flowingsun/war_project/html/Css.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlNode.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlDocument.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlLayout.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlRenderer.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlViewHost.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/java/com/flowingsun/war_project/client/HtmlResources.java"
  - "src/main/resources/assets/war_project/html/resource_island.html"
  - "src/main/resources/assets/war_project/html/transfer_panel.html"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "653a4e6f-07c7-43d3-bbfa-7baf447ec912"
memory_body_ids:
  []
---

# War Project 增量交接：个人资源落地 + 全局 HTML 渲染内核 + 资源转移

本文只记录**既有托管文档未覆盖**的增量，接在 `04344603-850c-48cf-8f57-e7313ea8c5ad`（SBW 兼容 + 资源个人化**定调**）之后。
该文档第 2 节的「资源个人化尚未实现」**已被本文取代**；队伍制首版见 `588ee5c2-515b-4c80-9d4a-fb8d4bb766c2`，HUD/灵动岛/999 见 `b75b521b-f4c9-41fb-90c8-1928b6462d99`，双资源见 `3ea0c3c0-6079-4bcf-8580-c21edf9967b5`，模块总览见 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 个人资源（已落地，取代队伍存量）

用户定调：**每位玩家拥有和队伍一样的资源收入，支出归类到个人**。口径（含此前逐项确认的选择）：

| 项 | 取值 |
| --- | --- |
| 存储 | `war_project_resources` → `players[] = {id(scoreboardName), ammo, fuel}`；旧 `teams[]` 段读档**忽略并记一条日志**，不迁移 |
| 收入分配 | node 归属队伍产出 X → 该队伍**每个在线成员各得 X**（总发放 = 在线人数 × X） |
| 离线 | 仅在线结算；离线期间不累积、不补账（结算瞬间以在线列表 + 当时 `TeamData` 归属为准） |
| 支出/转移 | 全部从个人余额扣 |
| 上限 | 每人每种 **999**（`ResourceData.MAX_AMOUNT`，结算/set/add/转移/读档统一 clamp） |

实现落点（`resource/` 包）：

- `ResourceData`：`stock/amount/setAmount/addAmount/addStocksForPlayers/spend/clearAll/size/playerNames`；`Stock(ammo, fuel)` 不可变值对象；键为玩家名，改名视为新玩家（旧记录成孤儿条目，不再入账）。
- `ResourceService.settle(server, elapsedSeconds)`：① 遍历 node 得 `Map<teamId, Stock> teamGains`（`isFaction` 且队伍存在）；② 遍历在线玩家，命中则**每人各得全额**累加 `Map<playerName, Stock>`；③ `addStocksForPlayers`；④ 由 tick 调用方 `ResourceApi.pushSyncAll(server)`。阶段非 RUNNING 时 `pendingTicks = 0` 直接返回（不补算）。
- `ResourceApi`：`playerKnown/stock/amount/playerStocks`（在线 ∪ 有记录，按名排序）`/set/add/spend/teamRate/snapshotFor/sendSync/pushSyncAll/transfer`；`teamRate` = 该队全部归属 node 的 perMinute 之和（HUD 与命令共用）。
- `transfer` 校验链（命令与 UI 共用同一实现，顺序即优先级）：kind 合法 → **仅 RUNNING** → 数量 > 0 且有限 → 目标名非空且非自己 → **目标在线** → **同队**（`TeamData.teamOf` 两侧非空且相等） → 自己余额足额 → **对方 `held + amount ≤ 999`（不满足则整笔拒绝，提示还能接收多少）** → 执行 `spend` + `addAmount`（同 tick）→ 双方 `sendSync` + 各自聊天回执 + INFO 日志。

协议与指令：

- 协议号 `"6"`：`ResourceSyncPacket(running, hasTeam, ammo, fuel, ammoPerMinute, fuelPerMinute, teammates[])` 为**个性化**包（`teammates` 带同队其他成员的 A/F，用于转移名单）；新增 C→S `TransferResourcePacket(targetName, kindId, amount)` 与 S→C `TransferResultPacket(ok, message)`。
- 指令：`resource list`、`resource player <player>`、`resource set|add|take|transfer <player> <ammo|fuel> <amount>`（`transfer` 需玩家执行）；`game status` 输出 `onlinePlayers / trackedPlayers / nodesWithOutput / ammo / fuel`。

## 2. 全局 HTML 渲染内核（`html/` 包，**不属于任何模块**，零依赖）

用户定调：引入 HTML 渲染系统且「不属于任何模块」，并把灵动岛改用 HTML 渲染。因此它是与 `module/GameStateService` 同级的全局客户端内核，**不注册进 `ModuleRegistry`**，不引入 MCEF/JCEF。

类清单：`Css`（样式模型 + 解析）、`HtmlNode`（DOM + 布局结果 + 动画运行值 + `classRevision`）、`HtmlDocument`（解析 + 规则收集 + `refreshStyles()` + 命中）、`HtmlLayout`（盒模型 + 简化 flex + 绝对定位 + 文本度量）、`HtmlRenderer`（绘制）、`HtmlViewHost`（tick 动画 / 渲染 / 指针状态与事件）。

支持面（严格限定，非通用浏览器）：

- 标签：`div span b strong img button input hr`；属性 `id class style src value`；容错解析（未闭合自动闭合、未知标签/属性忽略、`:hover`/`:active` 选择器、逗号分组、`#id`/`.class`/元素/`*`）。
- 样式：`background(-color) color font-size padding* margin* border(-width/-color/-radius) box-shadow width height min-width max-width left top right bottom position(static|relative|absolute) display(block|flex|none) flex-direction gap align-items justify-content opacity overflow transition transform(scale|translate) pointer-events`。
- 颜色：`#rgb` `#rrggbb` `#rrggbbaa` `rgba()/rgb()` 与少量命名色；`border-radius: 999px` 视为胶囊（半径 = 高度/2）。
- `transition: <时长> <缓动>` 对全部属性按同一时长插值（`HtmlViewHost.tick(deltaMs)` 驱动）。

**关键机制与陷阱（踩过的坑，勿重犯）**：

1. **CSS 规则不烘焙**：`HtmlDocument` 收集规则而非解析时写死；切换 class 后必须 `refreshStyles()` 重算（`HtmlViewHost` 每帧比对各节点 `classRevision`，变化才刷新）。否则运行时 `setClass`（灵动岛 hidden、行 hover/selected/offline/hidden）**完全不生效**。
2. `:hover` / `:active` 样式存在 `hoverStyle` / `activeStyle`，由 `HtmlNode.effectiveStyle()` 按 `hasBackground/hasColor/hasOpacity/hasScale/hasTransform` 等标记叠加，未声明的属性不覆盖基线。
3. 图片必须用 **11 参数** `blit(rl, x, y, drawW, drawH, uOffset, vOffset, srcW, srcH, texW, texH)`（短重载把绘制尺寸当采样尺寸，会把 128×128 图标采成左上角透明块）。见 `b75b521b`。
4. 命中与不可见：`HtmlNode.contains()` 同时要求 `visible` 与 `currentOpacity >= 0.05`，所以淡出后的面板不再吞事件。
5. 数字输入复用 vanilla `EditBox`（光标/退格/粘贴可靠），其矩形由布局中 `#inputbox` 节点给出；外层外观（圆角底、边框）仍由内核绘制。
6. 模板加载：`client/HtmlResources` 从 `assets/war_project/html/*.html` 读取并缓存，任何失败回退到代码内置常量（资源包异常不会导致 UI 空白）。

## 3. 灵动岛 HTML 化 + 资源转移面板

- 灵动岛：`client/ResourceIslandView`（模板 `assets/war_project/html/resource_island.html`）——纯黑胶囊、16px 图标、白字存量 + 绿色 `+每60s速率`；显示条件 `running && hasTeam && !SuperbWarfareCompat.isVehicleFirstPerson()`；顶部居中（先 `HtmlLayout.apply` 量宽再居中）。
- **双渲染路径**：无 Screen 时由 `client/ResourceHudOverlay`（注册在 `VanillaGuiOverlay.HOTBAR` 之上）调用；任意 Screen 打开时由 `ScreenEvent.Render.Post` 调用同一视图，保证「聊天栏模式下」仍可见可点。
- 转移面板：`client/ResourceTransferController`（模板 `transfer_panel.html`，8 行名单 + 输入框 + Confirm/Cancel + 结果行）。
  - 触发：仅当 `Minecraft.screen instanceof ChatScreen` 时，`ScreenEvent.MouseButtonPressed.Pre` 中**右键**（`getButton()==1`）命中 `#ammo-icon` / `#fuel-icon` → 面板出现在鼠标位置（越界夹回屏幕内）。
  - 名单来源：`ResourceSyncPacket.teammates`（服务端下发同队其他成员的 A/F）+ 客户端 `connection.getOnlinePlayers()` 判定在线；离线行置灰不可选；>8 人用滚轮翻页。
  - 数量上限：`min(自己余额, 999 − 对方该项存量)`；非法/超限时 Confirm 禁用并给出红字原因；成功 → 关闭面板（服务端已发聊天回执），失败 → 保持打开显示红字。
  - 事件拦截：面板打开期间，面板矩形内的 `MouseButtonPressed/Released/Scrolled/KeyPressed/CharacterTyped` 的 `.Pre` 一律 `setCanceled(true)`（ESC 关闭、回车确认、点击面板外关闭），避免聊天栏误响应；`EditBox` 由这些事件驱动。
  - 动画：`transition:150–160ms`（出现 `scale 0.94→1` + `opacity 0→1`、行 hover 高亮、按钮 hover/禁用降透明、结果文字变色）；`tick(deltaMs)` 在 `ClientTickEvent` 中统一驱动。

## 4. 验证清单（本次实际执行）

1. `./gradlew compileJava --offline --console=plain`（配 `timeout`，见 §5）→ BUILD SUCCESSFUL。
2. 无并行 java 进程时 `./gradlew build --offline --console=plain -Dnet.minecraftforge.gradle.check.certs=false`，再把 `build/libs/war_project-1.0.0.jar` 复制到 totalwar 实例并比对 sha256（本次 `659744e7…`），`unzip -l` 确认 `assets/war_project/html/*.html` 与新类都在 jar 内。
3. 游戏内（需两个账号）：同队两人 + node 产出 60/60 → 一次结算两人**各** +5；聊天栏右键 ammo 图标 → 选队友 → 输入 3 → 确认，双方数值与 HUD 即时刷新；余额不足 / 对方满 999 / 队友离线 / `game stop` 状态一律被拒；ESC 与点击面板外可关闭；`game end` 全体清零且 node 回 neutral。
4. 回归：SBW 炮镜仍隐藏灵动岛；无队伍玩家无灵动岛；Xaero 世界地图 node 收益行（读 node 产出，与个人存量无关）不受影响。

## 5. 构建/部署纪律（本机特有）

- Gradle **daemon 模式会卡死**；统一用 `--no-daemon --offline`，并加 `timeout 280 ./gradlew …`（偶发与并行 agent 抢锁导致前台长时间无输出，超时后直接重试即可）。
- 构建前先 `tasklist | grep -ic '^java'`：>0（有其他 agent 在跑）则**跳过构建与覆盖 jar**，避免冲突；`javaw` 是游戏进程，不是构建。
- 覆盖 jar 后必须提示重启客户端（mod 类不能热重载，F3+T 只重载资源）。

## 6. 已知限制与非目标

- 不执行 JS；HTML/CSS 仅上述子集；不引入 MCEF/JCEF。
- 不支持跨队转移、转账税、延迟到账、交易审计、拖拽/批量转移、给离线成员记账。
- 名单只显示 8 行（滚轮翻页），无搜索；面板无自定义标题/备注输入。
- `war_project_resources` 的旧 `teams[]` 数据按定调丢弃；如需保留需另做一次性迁移。
