---
id: "7d53e3fd-9728-4a97-a49f-f1fe227a7e50"
title: "War Project 增量交接：物理分辨率渲染（device_scale_factor）、岛与面板合并为单一表面、左键操作与转移冷却/单次上限"
description: "接在 b0da5a59（Chromium 不出帧根因与稳定性加固）之后：放大倍率真因是缺 device_scale_factor（1 GUI 像素=1 页面像素会被 MC 放大 N 倍而糊，附对照证据与实测帧尺寸）；岛与面板合并为同一个 CEF 表面 overlay.html 206×196（折叠贴合内容、向下延伸展开动画、位置固定不随鼠标）；操作改为左键；聊天栏显示岛、其它 GUI 隐藏；字号 8.5px≈小六、左右内边距对称、body 绝对定位防撑宽；转移新增冷却 120s 与单次上限 50/25（服务端权威）。改岛/面板外观、渲染分辨率或转移规则前先读。"
status: "active"
created_at: "2026-09-19T21:46:37.932Z"
updated_at: "2026-09-19T21:46:37.932Z"
content_hash: "0ce0cd6a73690b6c05010a8fc3fcf8b41c20561130cae5df4412d1600730ece8"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebPages.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebSnapshot.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceApi.java"
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "docs/CHROMIUM_BACKEND.md"
session_ids:
  - "09941909-558e-4e02-97e0-15c0ac97590a"
memory_body_ids:
  []
---

# War Project 增量交接：物理分辨率渲染 + 岛/面板合并 + 左键与转移限额

本文只记录**既有托管文档未覆盖**的增量，接在 `b0da5a59-58c3-4222-b1f6-9a80bec175c1`（Chromium 不出帧根因、进程级崩溃排查、稳定性加固）之后；
架构与下载/引导/OSR 上传见 `dfbfbd61-6898-492a-800c-55356cf596b3`，HTML 内核与个人资源见 `437db98f-3347-4ce2-8050-91259140564c`。

**本文修正/取代的旧结论**：
- `b0da5a59` 第 4 条「渲染必须 1 GUI 像素 = 1 页面像素」——**方向错误**，见第 1 节；正确做法是 `device_scale_factor = Minecraft.getGuiScale()`。
- `dfbfbd61` / `b0da5a59` 里「灵动岛与转移面板是两个 CEF 视图（`island.html` 156×18 + `panel.html` 208×168 各一个）」——已合并为**单一表面** `overlay.html`（206×196），旧的两个页面不再被加载。
- 面板触发由**右键改为左键**；面板**位置固定**在岛正下方，不再跟随鼠标。

仓库 `E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；游戏目录 `D:\mc\.minecraft\versions\totalwar`（CEF 二进制在 `<gameDir>/war_project-cef/windows_amd64/`，已预置，无需再下载）。

---

## 1. 「放大倍率 / 图标糊」的真因与修法（有对照证据）

- `CefOsrView` 当时把 CEF 表面按 **1 GUI 像素 = 1 页面像素** 渲染（画布仅 200×26），而 MC 在 GUI scale 4 的机器上要把这块画成 800×104 物理像素 → **1 个页面像素被拉成 4×4** → 文字发软、图标糊成色块。这就是「放大倍率」的直接来源。
- 对照实验：同一图标在 `device_scale_factor = 2` 与 `= 4` 下渲染后归一化放大对比 —— 前者三目不分，后者三发子弹、圆环与内部细节清晰可辨。
- jcef 里控制它的是 `org.cef.handler.CefScreenInfo#device_scale_factor`；`CefBrowserOsr.getScreenInfo()` 默认写死内部字段 `scaleFactor_ = 1.0`，**子类必须覆盖**。

**落地实现**（`CefOsrView`）：
- `browser_rect_` 仍设 **GUI 尺寸**；覆盖 `getScreenInfo(CefBrowser, CefScreenInfo)` → `screenInfo.Set(deviceScale, 32, 8, false, rect, rect)`，`deviceScale = Minecraft.getWindow().getGuiScale()`（该方法返回 double，取整）。
- CEF 实际输出 `GUI 尺寸 × deviceScale` 的帧（实测：scale 2 → 岛帧 400×52、面板帧 488×400；scale 4 → 800×104），MC 再画回同一 GUI 矩形 → **物理像素 1:1，零重采样**。
- GUI scale 变化由 `CefWebRenderer` 每帧比对后触发 `setGuiSize` 重建表面。

## 2. 岛与面板合并为同一个 CEF 表面

- 页面：`assets/war_project/web/overlay.html`，画布 **206×196**（GUI 像素），单一 `CefOsrView`（名 `overlay`）。
- **折叠态**：顶部居中黑色胶囊 `#000000f0`，无阴影、1px 微边框；**宽度贴合内容**（实测 129×18 GUI px）。
- **展开态**：同一块黑板向下延伸扩大（`width/height/border-radius` 过渡 220ms `cubic-bezier(.2,.8,.25,1)`，面板内容 `opacity` 淡入延迟 70ms），顶行保留资源数字，下方依次为标题 / 队员列表 / 数量 / 确认取消 / 结果。
- **位置固定**：屏幕顶部居中（`TOP_MARGIN 2`），不再按鼠标定位。
- **左键展开**：`CefWebRenderer.mousePressed` 与自研内核回退路径 `ResourceTransferController.onMousePressed` 都从 `button == 1` 改为 `button == 0`；左半 → 弹药，右半 → 燃料。
- 收起条件：ESC / 取消 / 成功后 0.9s / 点击面板外 / `Minecraft.screen == null` 时 tick 自动收起。
- 显示规则（用户两次修正后的终版）：**聊天栏（ChatScreen）打开时照常显示岛**（它是面板入口），**其它任意 GUI 隐藏岛**（只显示已展开的面板），SBW 载具炮镜同样隐藏。

## 3. 四个 CSS 层面的坑（都踩过并有确定修法）

1. **面板内容会把胶囊撑宽**：折叠态用 `width: max-content` 量宽时，`.body`（标题/输入框）的固有宽度被算入 → 量到 178px 而非纯内容 111px。修法：`.body { position: absolute; left/right/top/bottom }` 脱离文档流，`max-content` 只由 `.bar` 决定。
2. **`max-content` 与像素宽度之间不能过渡**，而展开动画需要可插值宽度。修法：脚本 `fitBar()` 先设 `width: max-content` 量出像素值再钉成 `px`（测量期间临时 `transition: none`），数字位数变化时重测并通过 `cefQuery {op:'size', bar}` 告知 Java 更新点击热区；展开时清掉内联宽度让 `.slab.open` 的 206px 生效。
3. **左右视觉不平衡（右端读起来更短）**：`.slab/.bar` 原本完全没有左右内边距，内容贴边；文本（`+0`）字形自带右侧边距（side bearing）、左侧是图标没有 → 右端显短。修法：`.bar { padding: 0 7px }` 严格对称。
4. 字号按用户要求取「≈小六」（6.5 磅 ≈ 8.67px）→ 统一 **8.5px**（岛与面板一致，标题保留 9px）。

## 4. 转移规则新增：冷却 120s + 单次上限 50/25

- 配置（`Config`，`config/war_project-common.toml`）：`transferCooldownSeconds` 默认 **120**、`transferMaxAmmoPerRequest` 默认 **50**、`transferMaxFuelPerRequest` 默认 **25**。
- 服务端权威校验（`ResourceApi.transfer`，命令 `/warproject resource transfer` 与 UI 共用同一实现，绕不过）：在目标在线/同队/余额/对方 ≤999 之后新增 **单次上限** → `You can send at most 50.00 ammo in one transfer.`；随后 **冷却** → `Transfer is cooling down (X s left).`；成功后写 `LAST_TRANSFER_AT`（`ConcurrentHashMap<UUID, Long>`，**仅内存**，与游戏阶段一样「重启即清」）。
- 查询门面：`ResourceApi.transferLimit(kind)`、`transferCooldownSeconds()`、`cooldownRemaining(player)`。
- 客户端展示：快照新增 `transferMaxAmmo/transferMaxFuel`（`WebSnapshot` + `WebJson` 的 `maxAmmo/maxFuel`，取自 `Config`），页面「上限」= min(自己余额, 单次上限, 999 − 对方存量)；`openPanel` payload 带 `cooldown`，提交成功后按钮变 `冷却 120s` 并每秒倒数；服务端拒绝文案在页面 `translate()` 中文化（`冷却中，还需 X 秒。` / `单次最多 50。`）。

## 5. 验证方式与当前状态

- 离线验证法（不必启动 MC）：`/tmp/cefsmoke` 的 `DeviceProbe`/`OverlayProbe` —— 直接 `CefApp.startup(--disable-gpu …)` + `CefBrowserOsr` + 覆盖 `getScreenInfo`，把 `overlay.html` 以 `data:` URL 加载、注入原版 PNG 的 base64、调用 `wp.apply/wp.openPanel` 后导出 PNG 检查外观与像素尺寸；页面脚本用 `node --check` 校验。
- 实机验证：`./gradlew runClient`（开发客户端，主菜单即可）看 `War Project CEF first frame for …` 的尺寸是否 = GUI 尺寸 × scale；玩家侧日志 `War Project CEF page online: … Chrome/116 …` 是「真在跑 Chromium」的证据。
- 最终部署件 sha256 `f3f4c0c2…`，可用 `unzip -p <jar> assets/war_project/web/overlay.html` 抽查 `padding: 0 7px` / `font-size: 8.5px` / `kindLimit` / `cooldownUntil`。

## 6. 仍未在真机验证的部分

GL 上传在光影（Embeddium + Oculus）环境下的稳定性、鼠标键盘转发手感、完整转移流程的各拒绝分支（含新加的冷却/超限两条）—— 需玩家在游戏内确认；异常时先看 `logs/latest.log` 里 `War Project CEF` 前缀的行（`first frame` 的尺寸会直接暴露分辨率问题）。
