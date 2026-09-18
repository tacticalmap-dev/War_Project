---
id: "dbb656b9-3bb8-4211-ad68-f2bba70b49b2"
title: "Xaero 集成规则勘误与增量：世界地图最小缩放 1x、小地图「不预判断 + 可见区精确裁形」"
description: "War Project 中 Xaero 集成的规则变更与调研证据：世界地图最小缩放由 0.5x 改为 1x（GuiMap.applyZoomLimits TAIL 注入 destScale）；小地图叠加层不再用 translatePosition 的返回值判断是否渲染，改为等价投影公式 + 按可见区（矩形 ±specW/±specH、圆形半径 specW）精确裁形；含 Xaero 1.44.2/26.4.2 字节码依据。本文取代关联文档 d33309cb 中「最小缩放 0.5x」一节与「小地图借用 translatePosition 平移」的描述，改动 Xaero 渲染前请两文一并阅读。"
status: "active"
created_at: "2026-09-18T17:35:49.645Z"
updated_at: "2026-09-18T17:35:49.645Z"
content_hash: "be8538bfcd5b0948113156975b574ad9fa808ed4bca6f47a8cc8a22440a8a700"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroWorldMapZoomLimitsMixin.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapScreenOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/main/resources/mixins.war_project.json"
session_ids:
  - "aa3383ad-59cc-4faa-a3da-c923f4a09867"
memory_body_ids:
  []
---

# Xaero 集成规则勘误与增量（War Project）

本文记录用户对 Xaero 集成的**权威性规则变更**及支撑它的调研结论。它**取代**关联文档
`d33309cb-4d09-4115-b151-97634cb3315e`（Xaero 地图渲染规则与缩放下限）中的两处内容：

- 该文档第 3 节「世界地图最小缩放 0.5x」→ 现为 **1x**；
- 该文档「小地图叠加层……借用 `translatePosition` 把每个区块平移到中心再绘制」→ 现改为**不使用 `translatePosition` 判断**（见第 3、4 节）。

该文档其余内容仍然有效：颜色、战区填充 alpha `0x40`、`dashPattern`/`edgeThickness`/`minimumDashedLength` 口径、
大地图 `EdgeCollector` 带式绘制与**轴感知像素键**、`reservedPixels` 优先级、两个 highlighter 已不再提供颜色、
`renderChunk` 像素路径无调用方。

参考模组蓝本：`E:\mc_mod_dev\modernwar-town`。部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。
本文的字节码依据来自 Xaero World Map 1.44.2 与 Xaero Minimap 26.4.2（位于该 mods 目录，用 JDK 的 `javap` 核对）。

## 1. 现行规则（用户明确要求，勿再自行改动或询问）

- 世界地图**最小缩放 = 1x**（此前为 0.5x）。
- 小地图叠加层**不做「是否渲染」的预判断**；所有区块按真实位置绘制，越界部分由**可见区精确裁形**裁掉。
- 渲染方式仍沿用原版手段：`GuiGraphics` + `pose` + 相对区块中心的 `[-half, half]` 坐标 + 世界相位虚线；
  不自创纹理、着色器或另一套坐标系。
- 边界语义不变：**node 实线 / 战区虚线**，虚线长度与线宽随缩放，node 优先级高于战区。

## 2. 缩放下限实现（1x）

文件：`src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroWorldMapZoomLimitsMixin.java`

- `@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)`，`@Shadow private static double destScale`，
  `@Inject(method = "applyZoomLimits", at = @At("TAIL"), require = 0)`，把 `destScale` 下限夹到 `1.0`。
- **为何这个注入点足够**（1.44.2 字节码）：`applyZoomLimits()` 把目标缩放夹到 `[0.0625, 50.0]`，
  开启 `UNLIMITED_ZOOM_OUT` 时下界降到 `0.001953125`、开启 `UNLIMITED_ZOOM_IN` 时上界升到 `1e6`；
  该方法**只有两个调用点**——`changeZoom(double,int)` 末尾与渲染方法每帧一次。因此按钮、滚轮、键盘以及
  任何对 `destScale` 的外部赋值都会被重新夹取。仅注入 `changeZoom` 会漏掉渲染期路径。
- 值关系：`destScale`（static，目标）→ `userScale`（实例，经 `SinAnimation` 平滑）→ `scale = userScale * k`（渲染用）。
  玩家看到的「N.NNNx」读数就是这组用户缩放值。
- Xaero 只有 `UNLIMITED_ZOOM_IN` / `UNLIMITED_ZOOM_OUT` 两个布尔，**没有「最小缩放数值」配置项**，所以必须注入。
- `require = 0`（沿用本模块可选兼容惯例）：若 Xaero 升级后改掉该方法名，限制会**静默失效**而不是崩溃；
  升级 Xaero 后需回归核对。

## 3. 小地图为何不能再依赖 translatePosition（调研证据）

`MinimapElementOverMapRendererHandler.translatePosition(pose, specW, specH, halfViewW, halfViewH, ps, pc, x, z, zoom, circle, partialTranslate)`
的实际行为（26.4.2 字节码逐条核对）：

1. 先算真实位置：`px = (ps·x − pc·z)·zoom`、`py = (pc·x + ps·z)·zoom`；
2. **把点夹取**到 `±specW × ±specH`（矩形）或半径 `specW` 的圆上（夹取时按比例缩放另一分量）；
3. 把 `px − round(px)`、`py − round(py)` 写入 `partialTranslate`；
4. `pose.translate(round(px), round(py))` —— **平移总会完成**；
5. **返回值 = 是否发生过夹取**。

因此原实现 `return !translatePosition(...)` 把返回值当绘制开关，等价于「区块中心落在可见区外就整块跳过」——
部分可见的区块被整块丢弃，这就是「进入范围才渲染」的根因。而若只去掉判断、继续用它夹取后的平移结果，
越界区块会被画在边界/圆周上（位置错误）。

**覆盖层没有原版裁剪保护**（同一批字节码）：

- `MinimapRenderer.renderMinimap` 在调用 `handler.render(gui, renderPos, partialTick, **null**, mapDimensionScale, dimension)`
  （我们的注入点）之后，直到方法结束只剩 `drawArrow`、`InfoDisplayRenderer.render` 与 `Lighting` 恢复，
  **没有任何 blit / 遮罩 / scissor**；
- 矩形与圆形的小地图纹理及其椭圆遮罩（`pose.scale` + `drawMyTexturedModalRectPre` / `drawTexturedElipseInsideRectangle`）
  都在注入点**之前**已经画完；
- 整个 over-handler 及其父类中搜不到 scissor。

结论：必须**自己**按同一公式平移（不夹取），并**自己**裁剪越界部分。`RenderSystem.enableScissor` 不可行
（只支持矩形，且需要窗口绝对坐标，而注入点只有 pose 相对坐标）。

## 4. 小地图实现要点（现行）

文件：`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapScreenOverlay.java`

- 反射读取 `ps` / `pc` / `zoom` / `halfViewW` / `halfViewH` / `specW` / `specH` / `circle`（沿父类链查找）。
  `halfViewW` / `halfViewH` **不参与裁剪**（Xaero 只把它们当夹取上限），避免误裁。
- **定位**：按上式自行计算 `px` / `py` 并 `pose.translate((float) px, (float) py, 0)`，**不夹取**。
  公式与 Xaero 逐位一致，所以坐标系、旋转与缩放与原版完全相同。
  粗筛（仅性能，不改变可见结果）：区块方块与视口无交集才跳过——矩形用区间重叠判定；
  圆形把圆心夹取到方块后比较距离是否 `≤ radius`。
- **`Viewport` 抽象**（内部接口 + `RectViewport` / `CircleViewport`）：
  - 矩形：`x ∈ [-specW, specW]`、`y ∈ [-specH, specH]`；
  - 圆形：半径 `specW`（与 Xaero 地图圆同半径，因为它的圆形夹取判据就是 `specW²`）；
  - 区间取**半开**语义，`xMaxAt(y)` 返回排他上界（圆形为 `floor(sqrt(R² − y²)) + 1`），可直接当 `fill` 边界；
  - `specW ≤ 0 || specH ≤ 0` 视为空视口，整体跳过。
- **`fillClipped(graphics, viewport, x1, y1, x2, y2, color, rowGate)`**：区块底色、竖边、横边**全部**走它。
  矩形完全可见时一次 `fill`；否则逐行取 `[xs, xe] = [x1, x2] ∩ 可见弦区间`，
  连续行区间相同则合并成一次 `fill`（因此圆形下跨圆周的区块才会逐行，内部区块仍是一次填充）。
- **虚线**：竖边用 `rowGate` 在 `y` 上按世界相位 `chunkZ·half·2 + (y + half)` 过滤；
  横边先按 `chunkX·half·2 + (x + half)` 在 `x` 上切 run，再逐 run 交给 `fillClipped`。
  `dashPattern` / `edgeThickness` / `minimumDashedLength` 与 `d33309cb` 记载一致（`half = ceil(8·zoom)`，`chunkPixels = half·2`）。
- 绘制顺序不变：战区填充 → 战区虚线边 → node 实线边（node 最后画并覆盖战区）。
- 若实机出现整体错位，检查 handler 的 `optionalScale` 是否需要乘到 `px`/`py`（该字段已在反射范围内，属一行回退）。

## 5. 验证状态

- `gradlew build`（自动启用 FTB/Xaero 可选兼容）与 `gradlew build -PwarProjectDisableOptionalCompat=true` 均通过。
- jar 已同步到 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。
- **未实机确认**：1x 缩放下限的实际手感、圆形与矩形两种小地图形态下的裁形效果与边缘是否还有跳变。
  验收点：边缘区块被裁成正确形状、不再整块消失；圆外无溢出；覆盖层不越出小地图范围。需重启客户端。

## 6. 非目标

- 不改世界地图叠加层绘制方式（它画在屏幕坐标系，越界由 framebuffer 边界天然裁掉）。
- 不改 dash 相位锚定方式与线宽比例；不改颜色 / alpha / 战区相邻边规则；不改占领 HUD 与 FTB 编辑器。
- 不把覆盖层迁到 Xaero 的 FBO 内（依赖 FBO 模式、注入点更深且脆弱，非 FBO 时还需退化路径）。
