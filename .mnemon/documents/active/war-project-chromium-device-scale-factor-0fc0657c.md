---
id: "0fc0657c-b1c4-4619-8491-0ca9c54dff57"
title: "War Project 增量交接：Chromium 物理分辨率渲染（device_scale_factor）、显示规则与最终界面规格"
description: "接在 b0da5a59（createImmediately 根因/崩溃排查/稳定性加固）之后的增量：CEF 必须按物理分辨率渲染 —— browser_rect=GUI 尺寸、覆盖 CefScreenInfo.getScreenInfo 返回 device_scale_factor=Minecraft.getGuiScale()，输出 GUI×scale 的帧再缩回 GUI 尺寸；**推翻**「1 GUI 像素=1 页面像素」（那会被 MC 放大 N 倍而糊）。含 2026-09-20 末轮三条修订（打开任意 GUI 隐藏岛且热区保留、岛取消阴影、岛 156×18/面板 208×168/图标 12px/字号 9px）、验证证据（scale 2/4 → 400×52/800×104，图标由团块变清晰）与改渲染分辨率/显示规则前必读的坑。"
status: "active"
created_at: "2026-09-19T20:45:00.801Z"
updated_at: "2026-09-19T20:45:00.801Z"
content_hash: "07385941d628cdb444706d8a81ab379c56e8b5c15a36aa2a7997ad2e0d54eadc"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebPages.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/resources/assets/war_project/web/island.html"
  - "src/main/resources/assets/war_project/web/panel.html"
  - "docs/CHROMIUM_BACKEND.md"
session_ids:
  - "7c4ba9b3-241c-4e2e-b2e6-094af8548f01"
memory_body_ids:
  []
---

# War Project 增量交接：Chromium 物理分辨率渲染 + 显示规则 + 最终界面规格

本文只记录**既有托管文档未覆盖**的增量，接在 `b0da5a59-58c3-4222-b1f6-9a80bec175c1`（Chromium 不出帧/黑块根因、进程级崩溃排查、稳定性加固）之后。
架构与下载/引导管线见 `dfbfbd61-6898-492a-800c-55356cf596b3`；更早的 HTML 内核与资源经济见 `437db98f-3347-4ce2-8050-91259140564c`。

**本文取代 `b0da5a59` 的两处当时状态**：① 用户"1:1 不发糊"的口径（真正的解法是物理分辨率渲染，见第 1 节）；② 界面尺寸 200×26 / 244×200（现为 156×18 / 208×168，见第 3 节）。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；游戏目录 = `D:\mc\.minecraft\versions\totalwar`；部署件 `mods\war_project-1.0.0.jar`。

---

## 1. 根因：CEF 表面必须按物理分辨率渲染（这条推翻了先前的 1:1 方案）

用户反馈"放大倍率存在问题"。实测对比后确认：**表面按 1 GUI 像素渲染是错的**。Minecraft 在 GUI scale=N 的机器上把 GUI 逻辑像素放大 N 倍绘制，若 CEF 表面也只有 1 页面像素/GUI 像素，等于 1 个页面像素被拉成 N×N 物理像素 → 文字发软、**图标糊成色团**。

正确做法（jcef 里唯一的控制点）：

- `browser_rect_` 仍按 **GUI 尺寸**布局（岛 156×18、面板 208×168）；
- 覆盖 `CefBrowserOsr.getScreenInfo(CefBrowser, CefScreenInfo)`，返回 `screenInfo.Set(deviceScale, 32, 8, false, rect, rect)`，其中 `deviceScale = Minecraft.getInstance().getWindow().getGuiScale()`。java-cef 原实现写死内部 `scaleFactor_ = 1.0`，所以默认就是"1 页面像素 = 1 GUI 像素"。
- CEF 于是输出 **GUI 尺寸 × GUI scale** 的帧（GUI scale 4 → 岛 624×72；scale 2 → 400×52），MC 再把它贴回 GUI 尺寸 → 物理像素 1:1，零重采样。
- GUI scale 变化（改设置/缩放窗口）时每帧跟随：`setGuiSize(..., guiScale())` 会重新分配缓冲并 `wasResized`。

**验证证据（离线 Chromium，同一图标归一化到同尺寸对比）**：`deviceScale=2` 时三发子弹糊成橙色团块；`deviceScale=4` 时子弹轮廓、圆环、内部细节全部可辨。实机（Forge 开发客户端，GUI scale=2）日志：`first frame for island (400x52)`、`first frame for panel (488x400)`，正各 2 倍。

**坑**：不要用固定倍率（如早期 `webRenderScale=2`）代替 GUI scale —— 用户机器 GUI scale=4 时 2× 仍会被再放大而糊；该配置项已移除。

## 2. 显示规则（打开 GUI 时隐藏岛）

- 无 GUI：显示岛；`/warproject game start` 且已组队才显示（与自研内核同一判据 `running && hasTeam && 非 SBW 炮镜`）。
- **打开任意 GUI/Screen（含聊天栏）时隐藏岛**，只显示转移面板。两条渲染路径都已改：CEF 侧 `ResourceTransferController.onRenderPost` 不再调 `renderIsland`；自研侧同样去掉了 `ResourceIslandView.render`。
- **热区保留**：岛不可见时，其在屏幕顶部居中的矩形仍响应右键 → 弹出转移面板（`CefWebRenderer.islandContains` 改为按当前窗口尺寸即时推导 bounds，不依赖上一帧渲染）。
- 取舍待用户拍板：是否改用更明确的入口（聊天栏提示行 / 纯命令 / 数字旁小标记）。

## 3. 最终界面规格（2026-09-20 末轮修订）

| 项 | 值 |
| --- | --- |
| 灵动岛 | 纯黑胶囊 `#000000f0`、**无外阴影**；CEF 画布 156×18（内容高约 16，两侧余量足够三位数数值）；图标用原版 PNG 显示 **12px**；字号 9px；速率只写 `+xx` |
| 转移面板 | CEF 画布 208×168；圆角 8；`font-size: 9px`；文案全中文（服务端英文拒绝消息在页面内按关键词译中文，成功提示由页面自生成） |
| 图标素材 | 必须用游戏原版 `assets/war_project/textures/gui/{ammo,fuel}.png`；页面是 `data:` URL 读不到 jar，所以由 Java **原样** base64 内联（`WebPages.dataUri`），**禁止任何预缩放**（此前"Java 缩到 28×28 + 浏览器再缩到 14"的两道缩放是糊的主因之一） |

## 4. 本轮改动的落点

- `client/cef/CefOsrView.java`：新增 `deviceScale`、覆盖 `getScreenInfo`、`setGuiSize(w, h, guiScale)`（`resize` 传 GUI 尺寸而非像素尺寸）、帧缓冲锁与容量校验、未出帧不绘制。
- `client/cef/CefWebRenderer.java`：尺寸常量 156×18 / 208×168、`guiScale()`（`Window#getGuiScale` 返回 double，需取整）、每帧同步 scale、`updateIslandBounds()` 供隐藏时的热区。
- `client/web/WebPages.java`：删除预缩放，图标原图直出并记一行日志（`icon … inlined raw (N bytes)`）。
- `client/ResourceTransferController.java`：Screen 打开时不再绘制岛（两条路径）。
- 页面：`assets/war_project/web/island.html`（无阴影 + 紧凑）、`panel.html`（紧凑 + 中文）。
- 文档：`docs/CHROMIUM_BACKEND.md` 新增 3.1（稳定性与 GPU）与 3.2（当前界面规格）。

## 5. 复现/自检要点

- 判断分辨率是否正确，只看一行日志：`first frame for island (WxH)`，应等于 `156×18 × getGuiScale()`（GUI scale 4 → 624×72）。
- 离线自检法：用独立进程启动 CEF（`jcef.path` 指向 `<gameDir>/war_project-cef/windows_amd64`），以 `data:` URL 加载页面并导出 PNG；对比不同 `deviceScale` 下图标的可辨度（本结论即由此得出）。
- 图标若显示为色团，先查是否又被引入预缩放，以及 device scale 是否为 1。
