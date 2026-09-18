---
id: "b8b4935c-5926-449f-a42c-ad50c22883f9"
title: "War Project 增量交接：小地图叠加层改走屏幕层（FBO 路径废弃）、线宽收细、FTB 仅编辑模式拦截"
description: "War Project 小地图渲染主线的最终裁定：FBO 注入路径因两次实机不可见而废弃，叠加层（几何+名称）改由屏幕层 XaeroMinimapOverlay 实现并按可见区精确裁形；同时记录线宽公式收细（chunkPixels/28，1–3px）、FTB 编辑器仅在 node 模式拦截鼠标的规则、以及已删除文件清单。本文修正/取代 1223eb8a 的 FBO 现行机制描述与其中列出的已删文件，并订正 dbb656b9、d33309cb 中 XaeroMinimapScreenOverlay 的类名。改小地图渲染或 FTB 编辑交互前先读本文。"
status: "active"
created_at: "2026-09-18T20:32:33.083Z"
updated_at: "2026-09-18T20:32:33.083Z"
content_hash: "e38f9422e91955aef838ef217555e57440355cc7ae7724cd65e68117d779179c"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapScreenOverlayMixin.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/main/resources/mixins.war_project.json"
  - "src/optionalFtb/java/com/flowingsun/war_project/compat/ftb/FtbChunksMapDivideClient.java"
session_ids:
  - "2aa26abe-2d39-4a13-abed-be13a454004d"
memory_body_ids:
  []
---

# War Project 增量交接：小地图改走屏幕层、线宽收细、FTB 拦截范围

## 0. 本文修正哪些既有文档

- `1223eb8a-dd85-486c-acd6-b327dec5584d`（小地图 FBO 未渲染排查 / node 名称 / FTB 交互）：
  其中「FBO 路径是现行机制」「一次性诊断日志」「`graphics.flush()` 加固」「`isFboActiveRecently()`」
  以及列为 sourcePaths 的 `XaeroMinimapFboOverlay.java` / `XaeroMinimapLabelOverlay.java` /
  `XaeroMinimapFboOverlayMixin.java` **均已废弃**。该文档的**排查证据仍然有效**
  （mixin 描述符与字节码逐字符一致、`endBatch` 发生在解绑之前、`usingFBO()` 的判定式），
  正是这些证据把主因锁定在「FBO 未启用 / 该路径在本机不可见」，可作为废弃决策的依据保留。
- `dbb656b9-3bb8-4211-ad68-f2bba70b49b2`、`d33309cb-4d09-4115-b151-97634cb3315e`：
  其中提到的 `XaeroMinimapScreenOverlay.java` 已不存在，等价实现改名为 `XaeroMinimapOverlay.java`；
  其余内容（1x 下限、node 实线/战区虚线、可见区裁形口径、`EdgeCollector` 带式绘制与轴感知像素键）仍然有效。
- `5ea0e65d-6677-419e-9263-916413603f03`、`3d31dded-b45f-4f11-a183-0d1aa78e4faa`：未涉及本次变更，仍有效。

## 1. 现行小地图叠加层：屏幕层（用户决定，勿再切回 FBO）

背景：FBO 注入路径经**两次实机反馈**（用户先后报「小地图未渲染」「依旧没有渲染叠加层」）确认在该机器上看不到。
排查已排除描述符不匹配与缓冲时机（证据见 `1223eb8a` 第 1 节），因此改为屏幕层实现以**保证一定可见**。

- 实现类：`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapOverlay.java`
- 注入：`src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapScreenOverlayMixin.java`，
  `@Redirect` 于 `xaero.common.minimap.render.MinimapRenderer.renderMinimap(...)` 内对
  `MinimapElementOverMapRendererHandler.render(...)` 的调用点（先画我们的，再执行原调用），`require = 0`。
- `src/main/resources/mixins.war_project.json` 的 `client` 现只含 4 条 xaero 项：
  `XaeroWorldMapSessionMixin`、`XaeroMinimapSessionMixin`、`XaeroMinimapScreenOverlayMixin`、`XaeroWorldMapZoomLimitsMixin`。
- 绘制顺序（同一层，名称保持正向、大小可读）：warzone 半透明填充 → warzone 虚线边 → node 实线边 → node 名称。
- 投影：反射读 `MinimapElementOverMapRendererHandler` 的 `ps` / `pc` / `zoom` / `specW` / `specH` / `circle`；
  `px = ps·dx·zoom − pc·dz·zoom`，`py = pc·dx·zoom + ps·dz·zoom`，pose 原点为小地图中心
  （与 `translatePosition` 第 1 步的真实位置公式等价，但**不做夹取**）。
- 裁形：绘制后按可见区裁 —— 方形 `±specW × ±specH` 取交集；圆形半径 `specW` 逐行弦长
  （`xMin = ceil(-√(R²−y²))`、`xMax = floor(√(R²−y²)) + 1`，半开区间）。统一走 `fillClipped`：
  `rowGate` 承载虚线相位，连续行区间相同则合并为一次 `fill`（避免逐行 draw call）。
- 唯一的跳过判定：区块方块与可见区**完全不相交**（`Viewport.intersectsBlock`）；部分可见的一律绘制再裁。
- 不依赖 `Minimap.usingFBO()`，安全模式（无 FBO）下同样显示。
- node 名称：`XaeroWarProjectMapRenderer.geometricCenter(chunks)` 求几何中心 → 投影 → 需落在可见区内且留
  `LABEL_EDGE_MARGIN = 10px` 边距；截断 `LABEL_MAX_LENGTH = 14`。世界地图侧同样在几何中心绘制（截断 16）。

## 2. 线宽与虚线口径（用户要求「线条变细」）

`src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java`：

- `edgeThickness(chunkPixels) = clamp(round(chunkPixels / 28.0), 1, 3)` —— 原为 `/14`、上限 6，已收细。
- `dashPattern(chunkPixels)`：`dash = clamp(round(chunkPixels × 0.30), 3, 96)`、
  `gap = clamp(round(chunkPixels × 0.20), 2, 64)`；`DashPattern.minimumDashedLength() = dash + gap`（一个完整周期）。
- 世界地图与小地图共用这两个函数，因此线宽与虚线长度随缩放同步变化。

## 3. FTB 大地图编辑器交互（`FtbChunksMapDivideClient.java`）

- **只在 `mode != NONE`（NODE / WARZONE）时拦截鼠标**：非编辑模式的右键一律透传，FTB 原有右键菜单恢复正常；
  左键与拖拽本就限定在编辑模式。
- 编辑模式下右键 node **与**右键战区是同一件事：右键战区先解析出它绑定的 node
  （`warzoneAtChunk(...).flatMap(w -> nodeById(w.nodeId()))`），统一弹 `Edit node` / `Delete node` / `Cancel`；
  点在没有 node 的空白区块上则不拦截。
- 拖选释放后的选择菜单中 **Clear selection 与 Cancel 合并为一项**：NODE 阶段点 Cancel 即清空选择，
  WARZONE 阶段的 Cancel 是放弃该 node 的创建。
- 工具栏：`Node` 按钮是进出编辑模式的 toggle（进入时清空临时状态并提示操作方式，再次点击退出并清空），
  `Create Node` / `Confirm Warzone` 按阶段出现；原 `Clear` 按钮已移除。
- Shift + 左键在编辑模式下不拦截（交回 FTB 平移地图）。
- 服务端入口：`EditMapObjectPacket`（`rename_node` / `delete_node` / `delete_warzone`），OP 权限校验并广播地图快照；
  改名走 `MapDivideStateApi.renameNode`（同步绑定战区的 `nodeId` 与占领进度），删除 node 会一并清掉其战区与占领进度。

## 4. 已删除、勿再引用的文件

- `src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapFboOverlay.java`
- `src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapFboOverlayMixin.java`
- `src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapLabelOverlay.java`（名称逻辑并入 `XaeroMinimapOverlay`）

## 5. 待实机确认

两种构建均已通过（默认可选兼容、以及 `-PwarProjectDisableOptionalCompat=true`），jar 已同步
`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。重启客户端后小地图应能看到
node 实线、战区虚线、战区半透明底色与 node 名称。

若**世界地图正常而小地图仍无任何叠加层**：两者现在同源（同为屏幕层 + 同套投影/裁形），说明问题不在渲染路径本身，
需要 `logs/latest.log`，或确认小地图是否被其他 mod 覆盖 / Xaero 小地图版本差异。
