---
id: "d33309cb-4d09-4115-b151-97634cb3315e"
title: "Xaero 地图渲染规则与缩放下限实现（War Project，含 Xaero 字节码核对证据）"
description: "War Project 中 Xaero 世界地图/小地图的边界样式规则（node 实线、战区虚线、虚线长度与线宽随缩放）、两个屏幕叠加层的分工与 EdgeCollector 带式绘制、以及世界地图最小缩放 0.5x 的注入依据（含对 Xaero 1.44.2 applyZoomLimits/destScale 的字节码核对）。改动地图渲染或缩放前先读此文档。关联文档 3d31dded-b45f-4f11-a183-0d1aa78e4faa。"
status: "active"
created_at: "2026-09-12T11:54:24.005Z"
updated_at: "2026-09-12T11:54:24.005Z"
content_hash: "5bb9b601c598c4d9f7ea5984815a32ec4711f201afdf9f677bda2d95c363883c"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapScreenOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroWorldMapZoomLimitsMixin.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/main/resources/mixins.war_project.json"
  - "build.gradle"
session_ids:
  - "5cb30376-0b59-4307-92d2-76cfd090879d"
memory_body_ids:
  []
---

# Xaero 地图渲染规则与缩放下限（War Project）

关联文档：`3d31dded-b45f-4f11-a183-0d1aa78e4faa`（War Project 架构、占点系统与可选依赖集成说明）。本文只补充**地图渲染与缩放**部分，避免与那份文档重复。

## 1. 已定渲染规则（用户明确要求，勿再自行改动或询问）

- **node 边界：实线**；**战区（warzone）边界：虚线**。这一对关系曾被对调过一次，当前以「node 实线 / 战区虚线」为准。
- 颜色按本地玩家与阵营的关系决定：友方/盟友 `0x20A4F3`，敌方 `0xFF1F57`，中立/无阵营 `0xFFFFFF`。
- 战区区域填充半透明同色，alpha `0x40`；node 区域不额外填充。
- **虚线的长度与线宽都要随地图缩放变化**，小地图与大地图使用同一套口径。
- 战区边界只在外轮廓绘制：相邻区块属于同一 warzone 时不画；相邻属于己方/盟友 warzone 时不画；相邻为空、中立或敌方时才画（`shouldDrawWarzoneEdge`）。
- **node 边界优先级高于战区边界**，重合或交叉处 node 实线不被虚线压盖。
- 不属于任何 node/warzone 的空区块不渲染任何覆盖。

## 2. 实现分工（可见绘制只来自两个屏幕叠加层）

- `src/optionalXaero/.../compat/xaero/XaeroWorldMapScreenOverlay.java`：监听 `ScreenEvent.Render.Post`，识别 `xaero.map.gui.GuiMap`，反射读取 `cameraX` / `cameraZ` / `scale` / `screenScale`，用 `GuiGraphics.fill` 逐帧实时绘制（不依赖 Xaero 区块高亮缓存）。
- `src/optionalXaero/.../compat/xaero/XaeroMinimapScreenOverlay.java`：由 `XaeroCommonMinimapRendererMixin` 在 Xaero 渲染小地图元素前调用；反射读取 `ps`/`pc`/`zoom`/`halfViewW`/`halfViewH`/`specW`/`specH`/`circle`，借用 `MinimapElementOverMapRendererHandler.translatePosition` 把每个区块平移到中心再绘制。
- `src/main/java/.../client/xaero/XaeroWarProjectMapRenderer.java`：共享几何与颜色口径的唯一出处——`relationEdgeArgb` / `relationFillArgb`、`shouldDrawWarzoneEdge`、`dashPattern(chunkPixels)`、`edgeThickness(chunkPixels)`、`DashPattern`。
- 两个 highlighter（`WarProjectWorldMapHighlighter` / `WarProjectMinimapHighlighter`）已**不再提供颜色**（返回 `null`/`false`），只保留 `regionHash` / `regionHasOverlay` / `chunkHasOverlay` 供 Xaero 缓存判定；`XaeroWarProjectMapRenderer.renderChunk` 像素路径目前**无调用方**（保留但已同步为相同语义，防止将来重新启用时不一致）。

### 几何口径

- `chunkPixels` = 一个区块在当前缩放下的屏幕像素数（大地图用 `chunkRect(proj,0,0,1,1)` 采样；小地图用 `half*2`，`half = ceil(8*zoom)`）。
- `dashPattern`：`dash = clamp(round(chunkPixels*0.30), 3, 96)`，`gap = clamp(round(chunkPixels*0.20), 2, 64)`。旧实现上限只有 28/18，导致高缩放下虚线看起来长度不变——这是「虚线长度不随缩放」的根因。
- `edgeThickness`：`clamp(round(chunkPixels/14), 1, 6)`，低缩放 1px、高缩放渐粗。旧实现线宽恒为 1px。
- `DashPattern.minimumDashedLength()` 用**一个完整周期**（`dash+gap`）；段长连一个周期都放不下时退化为实线。

### EdgeCollector（大地图）要点

- 边界以「**带**」为单位：`EdgeKey(vertical, bandStart, bandEnd, color, dashed, priority)`；带始终落在区块矩形内（西 `[x1, x1+t)`、东 `[x2-t, x2)`，南北同理）。
- 绘制按平行轴 run 批处理，每条线一次 `fill`，不逐像素绘制。
- `reservedPixels` 记录**整条带**的像素，保证高优先级（node）先画并占位，低优先级（战区）在冲突位置跳过。
- **踩过的坑**：像素键必须**轴感知**——竖线的带 `(x=offset, y=parallelCoordinate)`，横线的带 `(x=parallelCoordinate, y=offset)`。若两轴共用同一个 `packedPixel(offset, parallel)`，横竖线的保留集合会互相错位，node 实线将无法阻止战区虚线穿插。修改此处务必保持该区分。
- 线宽不可能超过区块尺寸：`thickness ≤ chunkPixels/14 < chunkPixels`，因此带不会溢出区块矩形（无需额外的溢出裁剪）。

## 3. 世界地图最小缩放 0.5x

### 核对到的 Xaero 事实（对 `xaero.map.gui.GuiMap`，Xaero 世界地图 forge-1.20.1-1.44.2 字节码）

- `private void applyZoomLimits()` 是缩放夹取的**唯一咽喉**：`changeZoom(double,int)` 末尾调用它，且渲染方法每帧也调用它。
- 该方法把**静态字段** `destScale` 夹到 `[0.0625, 50.0]`；当 `WorldMapProfiledConfigOptions.UNLIMITED_ZOOM_OUT` 生效时下界降到 `0.001953125`，`UNLIMITED_ZOOM_IN` 生效时上界升到 `1e6`。
- 缩放语义链：`destScale`(static，目标) → `SinAnimation` 平滑为 `userScale`(实例) → `scale = userScale * k`。玩家看到的 `N.NNNx` 读数即这组用户缩放值；我们的叠加层读的 `scale`/`screenScale` 由它派生。
- `WorldMapProfiledConfigOptions` 只有 `UNLIMITED_ZOOM_IN` / `UNLIMITED_ZOOM_OUT` 两个布尔，**没有「最小缩放数值」配置项**，所以只能代码注入。

### 实现

- `src/optionalXaero/.../mixin/xaero/XaeroWorldMapZoomLimitsMixin.java`：`@Mixin(targets="xaero.map.gui.GuiMap", remap=false)`，`@Shadow private static double destScale;`，在 `applyZoomLimits` 的 `@At("TAIL")` 注入 `if (destScale < 0.5) destScale = 0.5;`。
- 选 TAIL 注入的理由：该方法同时被缩放动作与每帧渲染调用，因此按钮、滚轮、键盘以及任何外部赋值都会被重新夹取；只注入 `changeZoom` 会漏掉渲染期路径。
- 只加下界，不改 Xaero 的上界与 `UNLIMITED_ZOOM_*` 语义（即使用户开启 unlimited zoom out，仍强制 0.5 下界）。
- **权衡**：注入使用 `require = 0`，沿用本模块可选兼容风格——Xaero 若改名 `applyZoomLimits`，限制会**静默失效而不是崩溃**；升级 Xaero 后需要回归核对。
- 注册：`src/main/resources/mixins.war_project.json` 的 `"client"` 数组加入 `xaero.XaeroWorldMapZoomLimitsMixin`；由 `WarProjectMixinPlugin.shouldApplyMixin` 对 `.xaero.` 前缀做 `classExists(target) && classExists(mixin)` 门控，因此未安装 Xaero 或关闭 optional compat 时该类不存在会被自动跳过。

## 4. 构建与部署（本部分特有注意事项）

- 可选兼容以源码集拆分：`src/optionalFtb/java`、`src/optionalXaero/java`；`build.gradle` 仅在本地找到对应 jar 时才把源码集加入编译，并输出 `War Project optional ... compat enabled: <jar 名>`。
- **踩过的坑**：可选 jar 自动发现必须排除 `neoforge` / `fabric` / `quilt`——`"neoforge"` 包含子串 `"forge"`，会把 NeoForge 1.21.1 的 FTB Library（class 版本 65）当成 Forge 1.20.1 依赖，导致 `class file has wrong version 65.0, should be 61.0` 之类的编译失败。发现逻辑还应优先选择文件名含 `1.20.1` 的 jar。
- 验证固定跑两种配置：`.\gradlew.bat build`（默认，启用可选兼容）与 `.\gradlew.bat build -PwarProjectDisableOptionalCompat=true`（纯核心，验证无硬依赖）。后者会覆盖 `build/libs` 产物，因此**最后要再跑一次默认构建**再同步 jar。
- 部署：把 `build/libs/war_project-1.0.0.jar` 覆盖到 `D:\mc\.minecraft\versions\totalwar\mods\`。

## 5. 已知限制 / 尚未验证

- 上述渲染与缩放改动**只做了编译验证**，尚未在游戏内实机确认（需要重启客户端加载新 mixin）。
- 虚线相位仍锚定在**屏幕坐标**（小地图用 `chunk*2*half` 的近似世界相位），平移地图时虚线相位会「游走」，缩放时相位锚点也会变化；这是既有行为，属已知非目标。
- 颜色/填充/相邻边规则、`regionHash` 缓存机制、FTB 大地图编辑器与占点 HUD 均不在本次范围。
