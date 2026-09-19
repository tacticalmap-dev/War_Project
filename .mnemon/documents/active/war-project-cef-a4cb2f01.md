---
id: "a4cb2f01-a47f-4698-8e69-5b2b4498a2fe"
title: "War Project 增量交接：岛与面板合并为同一 CEF 表面（向下展开动画 / 固定位置 / 聊天栏显示规则更正）"
description: "接在 0fc0657c（device_scale_factor 物理分辨率）之后，更正它的两处状态：① 显示规则改为『聊天栏照常显示岛、其它 GUI 才隐藏』；② 界面不再是 156×18 岛 + 208×168 面板两个 CEF 画布，而是单一 overlay.html 表面（206×196），折叠为 148×18 胶囊、右键向下延伸扩大（width/height/radius 220ms + 内容淡入延迟 70ms），位置固定屏幕顶部居中不随鼠标；含命中区域、收起条件与离线 Chromium 的两态验证证据。改岛/面板形态或入口前必读。"
status: "active"
created_at: "2026-09-19T21:19:59.329Z"
updated_at: "2026-09-19T21:19:59.329Z"
content_hash: "13444bfb91601e9bcb90fbcc7d98e561d1a91bc493c68cbe079b5c2fedb657ac"
source_paths:
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebPages.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "docs/CHROMIUM_BACKEND.md"
session_ids:
  - "02631ce5-6c78-4e55-9bac-59f90a737cdc"
memory_body_ids:
  []
---

接在 `0fc0657c-b1c4-4619-8491-0ca9c54dff57`（Chromium 物理分辨率渲染）之后。
架构与下载/引导管线见 `dfbfbd61-6898-492a-800c-55356cf596b3`；不出帧根因与崩溃排查见 `b0da5a59-58c3-4222-b1f6-9a80bec175c1`。

**本文更正 `0fc0657c` 的两处当时状态**：① 它的第 2 节写「打开任意 GUI（含聊天栏）时隐藏岛」——用户随后明确修正为**聊天栏不隐藏**；② 它的第 3 节写「岛 CEF 画布 156×18 + 面板 208×168」两个表面——现在是**单一合并表面** 206×196。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；游戏目录 = `D:\mc\.minecraft\versions\totalwar`；部署件 `mods\war_project-1.0.0.jar`（本轮 sha `9726572a…`，jar 内含 `assets/war_project/web/overlay.html`）。

---

## 1. 形态：岛与面板合并为一个 CEF 表面

用户要求原话：「字体、界面和按钮都可以和灵动岛保持一致」「该界面应该合并至灵动岛向下延伸扩大的区域（有动画），并且位置固定，不随鼠标移动，好通过鼠标指针选择赠送队友」。

- 新页面 `assets/war_project/web/overlay.html`：**一个** `.slab` 黑板同时承担岛与面板；`WebPages.overlay()` 取代 `island()`/`panel()` 供 CEF 路径使用（旧的 island.html / panel.html 保留但 CEF 不再加载）。
- `CefWebRenderer` 由「island + panel 两个 `CefOsrView`」改为**单一 `CefOsrView("overlay", WebPages.overlay())`**；`renderIsland` 与 `renderPanel` 都只画这一块（面板展开时由 `renderPanel` 独占绘制，避免同一帧画两遍）。
- 常量（GUI 像素）：`SURFACE_WIDTH=206`、`SURFACE_HEIGHT=196`、`BAR_WIDTH=148`、`BAR_HEIGHT=18`、`TOP_MARGIN=2`。

## 2. 展开动画与固定位置

CSS 驱动，Java 只发状态（`wp.openPanel` / `wp.closePanel`）：

- 折叠：`.slab{width:148px;height:18px;border-radius:9px}`；展开：`.slab.open{width:206px;height:196px;border-radius:10px}`，过渡 `220ms cubic-bezier(.2,.8,.25,1)`。
- 面板内容 `.body` 用 `opacity` 淡入，`transition-delay .07s`；顶行 `.bar` 始终渲染资源数字 —— 观感就是"岛本身向下长出面板"。
- **位置固定**：`surfaceX = (screenWidth - 206)/2`、`surfaceY = TOP_MARGIN`，**不再使用鼠标坐标**（旧实现的 `PANEL_OFFSET` 与鼠标定位已删除），这样鼠标可以稳定移到队友行上点选。
- 视觉统一：纯黑 `#000000f0`、无外阴影、1px `#ffffff1f` 微边框、`font-size: 9px`、中文文案；按钮改中性灰（`#ffffff12` + 悬停 `#ffffff26`），仅"确认"保留蓝色。

## 3. 显示规则（更正后）

- 无 GUI 且 `running && hasTeam && 非 SBW 炮镜`（判据仍是 `ResourceIslandView.shouldShow()`）：显示折叠态胶囊。
- **聊天栏（`ChatScreen`）打开：照常显示**——它是转移面板的唯一点击入口；此时右键胶囊展开面板。
- **其它任意 GUI（背包/ESC/JEI…）打开：隐藏**，但已展开的面板仍绘制。
- `ResourceTransferController.onRenderPost` 里唯一判据是 `minecraft.screen instanceof ChatScreen`；CEF 与自研内核两条渲染路径共用这份规则。
- 面板收起条件：ESC / 取消 / 成功 0.9 秒后 / 点击面板外 / **离开聊天界面**（`tick()` 检测到 `Minecraft.screen == null` 自动收起）。

## 4. 命中区域与输入

- `islandContains`：画布顶部 18px 高的**居中 148 宽**胶囊条（不依赖上一帧渲染，按当前窗口宽度即时推导）；右键（button 1）时以中线判定 ammo / fuel 并 `openPanel`。
- `panelContains`：展开时整个 206×196 矩形；命中后把 GUI 坐标减去 `(surfaceX, surfaceY)` 转成页面坐标再转发鼠标/滚轮/键盘。
- 键盘仅面板打开时转发（`CefKeyMap` GLFW→VK），`charTyped` → `keyTyped`；ESC(256) 在 Java 侧直接收起。

## 5. 验证证据（离线 Chromium，deviceScale=2）

- 折叠态帧 412×392（=206×196×2），内容 bbox `(58,0)-(354,36)` —— 正是 148×18 的两倍物理像素且水平居中。
- 展开态帧同尺寸，内容 bbox 铺满 `(0,0)-(412,392)`，即 slab 已长到 206×196。
- 图标仍是游戏原版 PNG 原样 base64 内联（`WebPages.dataUri`，禁预缩放）；物理分辨率渲染规则不变（`browser_rect`=GUI 尺寸 + `device_scale_factor`=GUI scale）。

## 6. 已知遗留

- 自研 `html/` 内核（Chromium 不可用时的回退路径）仍是旧的"鼠标位置浮窗"面板形态，没有延伸动画与固定位置 —— 自研内核无 CSS transition 能力所致；回退期间观感与 Chromium 路径不同属预期。
- 面板展开时画布其余部分透明，每次内容变化才上传纹理（`dirty` 门控），折叠态静止不上传。
