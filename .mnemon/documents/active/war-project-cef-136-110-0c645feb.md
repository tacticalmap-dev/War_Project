---
id: "0c645feb-3584-49fb-b6d3-d35f67b7f76c"
title: "War Project 增量交接：转移面板改为「灵动岛向下展开」（CEF 表面 136×110、宽度跟随岛宽、贴岛底）"
description: "接在 a4cb2f01 / 7d53e3fd（岛与面板合并为 206×196 单一 CEF 表面）之后的增量：本轮把 Chromium 表面缩到 136×110 且只承载转移面板，面板宽度 = clamp(岛实测宽,132,136)（岛宽经 op:'size' 回报、openPanel 回传为 CSS 变量 --pw），表面下移到内核顶部 HUD 之下、展开时页面自绘胶囊淡出避免与 html/top_hud.html 的岛重复；含离线 Chromium 实渲测量、DOM 桩回归、javap 校验方法与一处 TOP_MARGIN 不一致。改面板尺寸/锚点/入口前必读。"
status: "active"
created_at: "2026-09-23T04:22:13.505Z"
updated_at: "2026-09-23T04:22:13.505Z"
content_hash: "4bb08c4290830c66109ef330f372eb08d2e9eeb1b0ee209a786d637f64dc49b4"
source_paths:
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/resources/assets/war_project/html/top_hud.html"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "docs/CHROMIUM_BACKEND.md"
  - "scripts/verify-overlay-page.mjs"
session_ids:
  - "f6053bc4-6daf-434c-934b-97367a92829b"
memory_body_ids:
  []
---

# War Project 增量交接：转移面板改为「灵动岛向下展开」

本文只记录**既有托管文档未覆盖**的增量，并更正其中已过时的形态与尺寸：

- 上游：`a4cb2f01-a47f-4698-8e69-5b2b4498a2fe`（岛与面板合并为同一 CEF 表面）、`7d53e3fd-9728-4a97-a49f-f1fe227a7e50`（device_scale_factor 物理分辨率 + 左键 + 冷却/单次上限）、`0fc0657c-b1c4-4619-8491-0ca9c54dff57`（早期两表面规格）。
- **本文更正**：CEF 表面不再是 206×196 / 176×150 的「岛＋面板」整张纹理，也不再由 CEF 画岛 —— 顶部 HUD（战局条＋资源胶囊）自 2026-09-21 起由自研 HTML 内核绘制（`client/ResourceIslandView` + `html/top_hud.html`），Chromium 只画转移面板。
- 用户口径（本轮）：**「在使用资源转移界面时，请以现在的灵动岛作为基础展开，同时缩小该界面的大小（缩小至少 20%）」**。

仓库 `E:\mc_mod_dev\War_Project`；游戏目录 `D:\mc\.minecraft\versions\totalwar`；部署件 `mods\war_project-1.0.0.jar`（本轮 sha256 `51792881362ea75c32c9b988511a295700c6036f08770041415696e54061bce3`）。

---

## 1. 常量与几何（`javap -p -constants` 反编译校验过）

`client/cef/CefWebRenderer.java`：

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `SURFACE_WIDTH` / `SURFACE_HEIGHT` | 136 / 110 | CEF 表面（GUI 像素），**只承载面板**，上下限即面板边界 |
| `PANEL_MIN_WIDTH` | 132 | 面板宽度下限（队员行「名字 + 弹 N · 燃 N」的最小可读宽度） |
| `TOPHUD_HEIGHT` | 30 | 内核顶部 HUD 的高度（= `top_hud.html` 的 `.topbar` 14 + `.island` 16，无 gap） |
| `TOPHUD_GAP` | 1 | 本轮由 3 改为 1 |
| `TOP_MARGIN` | **2** | **未被本轮改动**，见 §5 的不一致 |
| `BAR_HEIGHT` / `BAR_WIDTH_FALLBACK` | 18 / 120 | 折叠态胶囊（内核在画，这里只用于热区兜底） |

- `surfaceX = (screenWidth - 136) / 2`（水平居中）；面板本身在表面内再居中：`panelX() = surfaceX + (136 - panelWidth())/2`。
- `surfaceY = TOP_MARGIN + TOPHUD_HEIGHT + TOPHUD_GAP`。
- 面板尺寸对比（相对上一轮 176×150）：宽 −25%、高 −26.7%、面积 ≈ −45%。

## 2. 宽度「跟随灵动岛」的实现链

1. 页面折叠态用 `max-content` 量出胶囊真实宽度（`measureBarWidth()`），钉成像素后发 `{op:'size', bar:N}`。
2. Java 收下 `barWidth`，`panelWidth() = max(132, min(136, barWidth))`；`panelContains` 用 `panelX()/panelWidth()`，**画面与热区共用同一条公式**（两侧各差 ≤4px 的误判已被消除）。
3. `openPanel` 时把宽度回传：`wp.openPanel({kind, available, cooldown, width: panelWidth()})`。
4. 页面 `openWidth = max(PANEL_MIN(132), min(PANEL_WIDTH(136), collapsed))`，写入 CSS 变量 `--pw`；`.slab.open { width: var(--pw, 136px); height: 110px; border-radius: 8px; }`；`closePanel` 用 `openWidth` 而不是固定常量做收缩起点。

## 3. 页面侧的三个必要改动（`assets/war_project/web/overlay.html`）

- **展开时不再自绘胶囊**：`.slab.open .bar { opacity: 0 }`。折叠态的胶囊由内核 `html/top_hud.html` 在表面正上方绘制；页面若继续画 `.bar`，屏幕上会出现两条一模一样的资源胶囊（用户截图里的重复行就是这样来的）。
- **`.body` 由 `top: 16px` 改为 `top: 0`**：不再给页内 `.bar` 留 16px，展开时内容从表面顶边铺到底边，观感是「从岛里长出来」。
- **内边距收紧**：`.body` padding `3px 6px 6px 6px → 2px 5px 4px 5px`、gap `3 → 2`；字号沿用上一轮的 6px 正文 / 7px 标题（用户未再要求改字号）。

## 4. 验证证据与可复现方法

- **离线 Chromium 实渲**（视口 136×110）：面板 `132×110`，`.body` 高 `108 = 110 − 2×1px 边框`，`scrollHeight == clientHeight`（零溢出零裁切）；3 行队员均单行、行高实测 10px；`.bar` 计算样式 `opacity: 0`；头部文本 `转移燃料` + `持有 21 · +6/分`。折叠态量出胶囊宽 93 → 面板取 132（下限生效）。
- **页面契约回归**：`node scripts/verify-overlay-page.mjs` → `ALL 17 CHECKS PASSED`（增量 DOM、「无变化不写文档」契约保持）。
- **字节码校验**：`javap -p -constants -cp build/libs/war_project-1.0.0.jar com.flowingsun.war_project.client.cef.CefWebRenderer` 确认四个常量；`javap -p -c` 确认 `panelWidth()` 被 `panelX / panelContains / openPanel` 调用。
- **本机工具链坑（可复用）**：
  - `javap` 不在 PATH：用 `/c/Program Files/Java/jdk-21/bin/javap.exe`（JDK 17/21 均在 `C:\Program Files\Java\`）。
  - DSH 浏览器工具**要求 URL 带 hostname**，`file://` 与 `data:` 都被策略拒绝（「usage policy requires a URL hostname」）。
  - 普通 `nohup … &` 或 PowerShell `Start-Process` 起的本地静态服务器会随 shell 会话退出而消失（下一次 `browser_open` 报 `ERR_CONNECTION_REFUSED`）；用 **WMI 创建独立进程**才存活：`Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='"<python.exe>" -m http.server 8731 --bind 127.0.0.1 -d E:\mc_mod_dev\War_Project'}`，测完 `Stop-Process -Id <pid>`。
- **未验证**：游戏内实拍（进世界才会出 CEF 帧），以及 `TOP_MARGIN` 下的真实间隙观感。

## 5. 已知不一致（留给后续修正，本轮未改代码）

父会话的完成回复与仓库 `docs/CHROMIUM_BACKEND.md` §3.3 写的是 `surfaceY = TOP_MARGIN(0) + TOPHUD_HEIGHT(30) + TOPHUD_GAP(1) = 31`，但 `CefWebRenderer.TOP_MARGIN` 实际是 **2**（`javap` 确认），因此真实 `surfaceY = 33`，面板顶边距内核岛底边是 **3 GUI 像素**而不是声称的 1。`ResourceIslandView.TOP_MARGIN = 0` 是另一个类里的同名常量，混淆即源于此。若要做到严格的 1px 贴合，需要把 `CefWebRenderer.TOP_MARGIN` 改为 0（或把 `TOPHUD_GAP` 定为 1 并让 `TOP_MARGIN` 不再参与），并同步 §3.3 的公式文字。

## 6. 改动文件

`src/main/resources/assets/war_project/web/overlay.html`、`src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java`、`docs/CHROMIUM_BACKEND.md`（§3.2/§3.3 已改写为「顶部 HUD 归内核、Chromium 只画面板」）。
