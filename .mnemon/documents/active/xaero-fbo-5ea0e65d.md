---
id: "5ea0e65d-6677-419e-9263-916413603f03"
title: "Xaero 小地图叠加层改为 FBO 内绘制（取代既有「可见区精确裁形」方案）"
description: "War Project 中小地图叠加层的现行实现：注入 MinimapFBORenderer.renderChunksToFBO 内对 MinimapElementMapRendererHandler.render 的调用点，把叠加层画进地图纹理，由 Xaero 自身的方形/圆形遮罩裁形，不再自建裁剪。本文取代关联文档 dbb656b9 的「可见区精确裁形」一节与 d33309cb 中关于小地图叠加层的描述（那两个文件已被删除）；含 Xaero Minimap 26.4.2 字节码依据与已知限制。改小地图渲染前先读此文档。"
status: "active"
created_at: "2026-09-18T20:01:24.183Z"
updated_at: "2026-09-18T20:01:24.183Z"
content_hash: "32d2b857f93b891067a2a4f72a8b173d7fbe050aba753fe39fef5466ebc94f60"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapFboOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapFboOverlayMixin.java"
  - "src/main/resources/mixins.war_project.json"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroWorldMapZoomLimitsMixin.java"
session_ids:
  - "f24ce406-9df2-4796-9ec3-f4438372176c"
memory_body_ids:
  []
---

# Xaero 小地图叠加层：改为绘制进地图 FBO（War Project）

本文记录用户对 Xaero 小地图叠加层的**最新权威决定**，并**取代**两份既有文档中的相关内容：

- `dbb656b9-3bb8-4211-ad68-f2bba70b49b2`（Xaero 集成规则勘误与增量）：其「小地图叠加层不做预判断、按可见区（矩形 ±specW/±specH、圆形半径 specW）精确裁形」一节**已作废**；
- `d33309cb-4d09-4115-b151-97634cb3315e`（Xaero 地图渲染规则与缩放下限）：其中关于小地图叠加层的描述**已作废**。

两份文档中仍然有效、勿改的部分：颜色规则（友方 `0x20A4F3` / 敌方 `0xFF1F57` / 中立 `0xFFFFFF`）、战区填充 alpha `0x40`、
**node 实线 / 战区虚线**、虚线长度与线宽随缩放、node 优先级高于战区、战区相邻边规则（`shouldDrawWarzoneEdge`）、
世界地图最小缩放 **1x**（`XaeroWorldMapZoomLimitsMixin`）、世界地图屏叠加层的 `EdgeCollector` 带式绘制与 `reservedPixels` 优先级。

参考模组蓝本：`E:\mc_mod_dev\modernwar-town`。部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。
字节码依据来自 Xaero Minimap **26.4.2** / World Map **1.44.2**（位于该 mods 目录，用 JDK 的 `javap` 核对）。

## 1. 现行规则（用户明确要求，勿再自行改动或询问）

- 小地图叠加层要**直接渲染在地图上**，而不是我们自己在屏幕层做裁剪。
- **只做 FBO 路径**：安全模式（Xaero 无 FBO 的渲染器）下**不显示**叠加层——这是用户明确接受的代价。
- 不再有「是否渲染」的预判断：区块按真实位置绘制，越界由 Xaero 自己的遮罩处理。
- 边界语义与颜色不变（见上）。

## 2. 为什么只有 FBO 内绘制可行（26.4.2 字节码核对）

`xaero.common.minimap.render.MinimapFBORenderer.renderChunksToFBO(...)` 的内部顺序：

1. `scalingFramebuffer.bindAsMainTarget(true)` + 清屏 → 把地图区块画进 **scaling FBO**；
2. 解绑 scaling，`rotationFramebuffer.bindAsMainTarget(true)` + 清屏，并以 scaling 纹理为输入继续绘制（旋转/合成）；
3. 在 **rotation FBO 仍绑定期间**调用
   `minimapElementMapRendererHandler.prepareRender(ps, pc, zoom, halfWView)` 与
   `minimapElementMapRendererHandler.render(gui, renderPos, partialTick, rotationFramebuffer, mapDimensionScale, dimension)`；
4. 之后才解绑 rotation FBO 并恢复投影矩阵。

因此：
- 第 3 步的 `render` 调用点是**唯一**能把内容画进地图纹理的位置；方法尾部（TAIL）两个 FBO 都已解绑，注入到那里无效。
- `renderMinimap` 随后用 `drawMyTexturedModalRectPre`（方形）/ `drawTexturedElipseInsideRectangle`（圆形）把 FBO 纹理贴到屏幕，
  **我们的内容与地图一起被同一遮罩裁形**，所以不需要任何自绘裁剪。
- `prepareRender` 已在第 3 步之前调用，故 map handler 的 `ps`/`pc`/`zoom` 字段描述的是**当前帧**。

对照（已作废的旧结论）：`MinimapElementOverMapRendererHandler.translatePosition(...)` 会把越界点**夹取**到 `±specW × ±specH`（矩形）
或半径 `specW` 的圆上，并**用返回值表示是否发生过夹取**——屏幕层用它当绘制开关就是「进入范围才渲染」的根因；
而屏幕层调用点之后直到 `renderMinimap` 结束都**没有任何 blit / 遮罩 / scissor**，这正是当时被迫自建裁形的原因。
改为 FBO 路径后这个问题整体消失。

## 3. 实现

新增（现行生效）：

- `src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapFboOverlay.java`
  - 从 `MinimapElementMapRendererHandler` 反射读私有字段 `ps` / `pc` / `zoom`（`declaredField` 沿父类链查找）。
  - 投影与 Xaero 的 FBO 元素一致：`px = (ps·dx − pc·dz)·zoom`、`py = (pc·dx + ps·dz)·zoom`，pose 原点在地图中心；
    每区块 `pushPose` → `translate(px, py)` → 相对区块中心 `[-half, half]` 绘制 → `popPose`。
  - `half = ceil(8·zoom)`；虚线/线宽沿用 `XaeroWarProjectMapRenderer.dashPattern(half*2)` 与 `edgeThickness(half*2)`（世界相位虚线）。
  - **没有任何裁形代码**（旧的 `Viewport` / `RectViewport` / `CircleViewport` / `fillClipped` 已删除）。
- `src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapFboOverlayMixin.java`
  - `@Mixin(targets = "xaero.common.minimap.render.MinimapFBORenderer", remap = false)`，
    `@Redirect(require = 0)` 到 `renderChunksToFBO(...)` 内的 `MinimapElementMapRendererHandler.render(...)` 调用；
    方法描述符：
    `renderChunksToFBO(Lxaero/hud/minimap/module/MinimapSession;Lnet/minecraft/client/gui/GuiGraphics;Lxaero/common/minimap/MinimapProcessor;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;DIFIZZIDDZLxaero/common/graphics/CustomVertexConsumers;)V`
  - Redirect 处理体：先 `XaeroMinimapFboOverlay.renderWarProjectOverlay(...)`，再执行原 `handler.render(...)`。

已删除（**不要再恢复**）：

- `src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapScreenOverlay.java`
- `src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroCommonMinimapRendererMixin.java`

配置：`src/main/resources/mixins.war_project.json` 的 `client` 数组中以 `xaero.XaeroMinimapFboOverlayMixin` 取代了
`xaero.XaeroCommonMinimapRendererMixin`；`xaero.XaeroWorldMapSessionMixin` / `xaero.XaeroMinimapSessionMixin` /
`xaero.XaeroWorldMapZoomLimitsMixin` 保持不变。
`WarProjectMixinPlugin.shouldApplyMixin` 对 `.xaero.` 前缀的 mixin 仍做 `classExists(target) && classExists(mixin)` 守卫，
所以未安装 Xaero 或关闭 optional compat 时自动跳过，不会崩。

## 4. 保留的唯一跳过条件

`|px| − half > max(renderTarget.width, renderTarget.height)` 时跳过该区块。这是刻意放宽的上界（以整个 FBO 尺寸为阈值），
几何上不可能与 FBO 相交才会命中，**不会**造成边缘缺块；目的只是避免提交远处区块。

## 5. 已知限制与未验证项

- **仅 FBO 模式生效**。`Minimap.usingFBO()` 为假时走 `MinimapSafeModeRenderer`（无 FBO），叠加层不显示。用户已接受。
- **线宽/虚线口径**按 FBO 内的区块像素计算；若 FBO 分辨率与最终显示尺寸不同，视觉粗细可能与旧实现略有差异
  （未做 FBO→屏幕比例补偿，待实机确认是否需要补）。
- **FBO 内 pose 基准与 zoom 口径尚未实机验证**；若出现整体错位，需要补一个比例系数或改用 FBO 尺寸换算。
- `require = 0` 沿用可选兼容惯例：Xaero 若改动调用点，叠加层会**静默不绘制**而不是崩溃，升级 Xaero 后需回归核对。

## 6. 验证状态

- `gradlew build`（含 FTB/Xaero 可选兼容）与 `gradlew build -PwarProjectDisableOptionalCompat=true` 均通过。
- 默认构建产物已同步到部署目标；jar 内确认含 `XaeroMinimapFboOverlay` / `XaeroMinimapFboOverlayMixin`，
  旧类已消失，`mixins.war_project.json` 已更新。
- **实机渲染效果（边缘裁形、线宽、对齐）尚未验证**，需重启客户端后在游戏内确认。
