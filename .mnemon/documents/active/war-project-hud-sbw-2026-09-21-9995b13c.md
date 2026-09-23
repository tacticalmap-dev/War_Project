---
id: "9995b13c-e6eb-426d-bed6-5f2e700c1e8a"
title: "War Project 融合顶部 HUD 与 SBW 炮镜判定（2026-09-21 实现交接）"
description: "顶部 HUD 从「灵动岛 + 独立战局条」改为一个内核文档 html/top_hud.html（贴顶长条 + 下缘资源胶囊），CEF 表面退化为只承载转移面板；含 SBW 第三人称炮镜判定（ClientEventHandler.zoomVehicle，javap 证据）与内核 CSS「只支持单 class 选择器」的坑与验证方法。改动顶部 HUD、CEF 表面或 SBW 兼容前读此文档。"
status: "active"
created_at: "2026-09-21T10:14:03.584Z"
updated_at: "2026-09-21T10:14:03.584Z"
content_hash: "b87976afe98e120d2e3854bc174213d1f4dc201e04cf1d808363c949621226d0"
source_paths:
  - "src/main/resources/assets/war_project/html/top_hud.html"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "src/main/java/com/flowingsun/war_project/client/SuperbWarfareCompat.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlDocument.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "a0a70cfc-abf5-461a-80f4-dbe94fc6cb50"
memory_body_ids:
  []
---

# War Project 融合顶部 HUD 与 SBW 炮镜判定

> 交接时间：2026-09-21 · 模块：client（HUD/CEF/SBW 兼容）
> 相关文档：`eaafa6ff-e567-4077-8178-e5417cd3f6f1`（VP 战局进度条：实现落点与 HUD 排障交接）、`40925ee8-8ff7-40e7-8db2-bc43689c4388`（VP 战局进度条与归零结束设计方案）、`3ea0c3c0-6079-4bcf-8580-c21edf9967b5`（资源 HUD 增量交接）

## 1. 这一轮解决什么

用户在前一版「灵动岛 + 独立战局条」基础上提了三条要求，本文件记录实现落点与踩到的坑：

1. 地图只有 1 个 vpnode，却渲染出 16 颗星。
2. HUD 的显隐要与灵动岛一致，并把战局条**融合**进灵动岛：顶部一条长矩形紧贴窗口上边缘，灵动岛贴在这条长矩形的**下边缘**，所有转角圆角。
3. 玩家坐在 SBW（Superb Warfare）载具上、**第三人称**按住炮镜键进入炮镜时，顶部 HUD 与灵动岛同样隐藏。

## 2. 最终视觉与显隐规则（用户定调）

- 顶部横向长条：贴 `y = 0`、宽度 = 屏幕 GUI 宽、高 22px、圆角 6px、底色 `#000000f0`；内容为「己方蓝 track + 分数 ｜ 星列 ｜ 分数 + 敌方红 track」。
- 资源胶囊：紧贴长条下缘（同属一个文档、无间隙），高 18px、圆角 9px、`padding 0 10px`；图标用原版 PNG `war_project:textures/gui/{ammo,fuel}.png`（`<img>` 直读 12px，不重绘）。
- 星列：每个 vpnode 一颗星（`<svg>` + `<polygon>`），颜色随归属（中立白 `#e5e7eb` / 本方·盟友蓝 `#4fa3ff` / 敌对红 `#ff4d4d`）；被占领中画顺时针进度弧（`<path>` 环带，24 档量化，弧色 = 进攻方阵营色）；归属变化闪 200ms。
- 显隐（整块 HUD 一条规则）：`running && hasTeam && 非 SBW 炮镜`，无屏幕时由 `ResourceHudOverlay` 调、ChatScreen 打开时由 `ResourceTransferController` 调。

## 3. 实现落点（工作区相对路径）

| 文件 | 作用 |
| --- | --- |
| `src/main/resources/assets/war_project/html/top_hud.html` | 融合文档：`#hud`（column）→ `#topbar`（战局条）+ `#island`（资源胶囊）；16 个 `#starN`（含 `#starShapeN` polygon 与 `#starArcN` path）；显隐用**单类** `.hudhidden` / `.staroff` |
| `src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java` | 唯一的顶部 HUD 视图：`shouldShow()`、`tick(deltaMs)`、`render(graphics, screenWidth)`（把 `#topbar` 宽度钉成屏宽）、`iconAt(x, y)`（命中 ammo/fuel 图标）、星状态/占领弧/闪烁、`reset()`；异常时只禁用自己（`broken`），不影响其它 HUD |
| `src/main/java/com/flowingsun/war_project/client/SuperbWarfareCompat.java` | `isVehicleGunSight()` = 坐在 `com.atsuishio.superbwarfare.entity.vehicle.*` 载具上 且（`options.getCameraType().isFirstPerson()` **或** 反射读 `com.atsuishio.superbwarfare.event.ClientEventHandler.zoomVehicle`） |
| `src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java` | CEF 表面 176×150 **只承载转移面板**：`drawSurface` 要求 `panelOpen`、`renderIsland` 空实现、`surfaceVisible()` 仅面板打开时为真、`surfaceY = TOP_MARGIN + TOPHUD_HEIGHT(40) + TOPHUD_GAP(3)` |
| `src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java` | 板内交互分发改为：先 `ResourceIslandView.iconAt` 命中 → `WebRendererService.openPanel`（CEF）或内置 `openPanel`；tick 里始终 `ResourceIslandView.tick`（CEF 活跃时也 tick） |
| `src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java` | 无屏时只调 `ResourceIslandView.render`（CEF 不再画岛） |
| 已删除 | `client/VpWarBarView.java`、`assets/war_project/html/vpwar_bar.html`（旧的独立战局条） |

## 4. 关键证据与教训

### 4.1 内核 CSS 匹配只支持单 class / 单 id / 元素选择器（16 颗星的根因）

`HtmlDocument.matches(node, base)`：`base.startsWith(".")` 时只做 `node.classes.contains(base.substring(1))`。因此复合选择器 `.star.off` 会被当成「类名 = `star.off`」→ **永不匹配**，隐藏规则（`display:none`）从未生效，16 个预置星槽全部可见。

修复：隐藏一律用单类（`.staroff { display:none; }`），Java 侧 `star.setClass("staroff", !used)`。

离线验证方法（无需启动游戏，也不需要 MC 类）：`javac -cp build/classes/java/main` 编译一个探针类，调用 `HtmlDocument.parse(...)` + 反射 `matches(HtmlNode, String)` + `node.setClass(...)` + `doc.refreshStyles()`，断言 `node.style.display == "none"`。本轮实测：`before=false → after=true, display=none`。

### 4.2 SBW 炮镜（含第三人称）的判定

用 `javap -c -p` 反汇编 superbwarfare-0.8.9.1：

- `com.atsuishio.superbwarfare.init.ModKeyMappings.HOLD_ZOOM` 是「按住进入炮镜」的键。
- `com.atsuishio.superbwarfare.event.ClientEventHandler` 有 `public static boolean zoomVehicle`，其 `putstatic ... iconst_1` 出现在 `com.atsuishio.superbwarfare.event.ClickEventHandler` 中（同处引用 `HOLD_ZOOM`）。
- 第三人称按炮镜时 vanilla `CameraType` 仍是第三人称，只靠 `isFirstPerson()` 会漏判 → 需要叠加 `zoomVehicle`。

读取方式保持零编译依赖：反射 `Class.forName("com.atsuishio.superbwarfare.event.ClientEventHandler").getField("zoomVehicle").getBoolean(null)`，并先确认玩家确实坐在 SBW 载具上。

### 4.3 CEF 表面的边界（历史事故）

CEF 表面曾是「岛 + 面板」一整张纹理：只改表面尺寸或页面结构，会让整个 HUD（含灵动岛）一起消失（2026-09-20 实际发生）。现在的边界是：**CEF 只画转移面板，HUD 一律由内核文档画**，新增 HUD 元素不要动 CEF 表面几何。

## 5. 本轮验证

- `./gradlew.bat build --no-daemon --offline` → BUILD SUCCESSFUL；产物覆盖到 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`（657372 字节，2026-09-21 18:10）。
- 打包内容核对：`assets/war_project/html/top_hud.html`、`client/ResourceIslandView.class` 在包内；`vpwar_bar` / `VpWarBarView` 已无残留。
- 内核离线解析 `top_hud.html`：`#hud`/`#topbar`/`#island`/`#mineTrack`/`#foeTrack`/`#star0..`/`#ammoIcon`/`#fuelIcon` 全部命中；`topbar` = flex/row/justify=center/align=center/gap=6/height=22/radius=6/bg=`f0000000`；`island` = flex/height=18/radius=9/padding 10/10；`star0` = 12×12 含 polygon+path；`#mineTrack` 宽 120、`justify=flex-end`（id 选择器有效）。
- 尚未在游戏内肉眼确认：长条贴顶效果、1 个 vpnode 只画 1 颗星、SBW 第三人称炮镜隐藏。

## 6. 后续注意

- 想改星的显隐/绑定，只能加**单类**或单 id 规则；需要复合语义时在 Java 侧算好后切换单一 class。
- 战局条与资源胶囊同属一个文档、共用 `shouldShow()`：无队伍时整块 HUD（含战局条）都不显示——这是用户要求的「显隐与灵动岛一致」。
- 若面板打开后岛屿点击失效，先检查 `ResourceTransferController.onMousePressed` 是否仍先用 `ResourceIslandView.iconAt` 再做面板分发。
