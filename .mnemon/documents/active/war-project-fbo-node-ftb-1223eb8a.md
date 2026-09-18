---
id: "1223eb8a-dd85-486c-acd6-b327dec5584d"
title: "War Project 增量交接：小地图 FBO 未渲染排查、Node 名称渲染、FTB 编辑器交互"
description: "War Project 中三类新知识的交接：小地图叠加层完全不渲染的排查（已排除描述符与缓冲时机、主因是 usingFBO() 为假/安全模式，含一次性诊断日志与 graphics.flush() 加固）、node 名称在几何中心的渲染分工（世界地图屏幕层、小地图也走屏幕层以保持正向）、以及 FTB 大地图编辑器的交互语义与 EditMapObjectPacket 服务端入口。改 Xaero 小地图渲染或 FTB 编辑器前读此文档，机制细节见关联文档。"
status: "active"
created_at: "2026-09-18T20:24:39.927Z"
updated_at: "2026-09-18T20:24:39.927Z"
content_hash: "dcb2c07cc33bc9d8634a22455684b45ac5b927f11bf290e86a92fb4ab1292294"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapFboOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapLabelOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapFboOverlayMixin.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapScreenOverlayMixin.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/optionalFtb/java/com/flowingsun/war_project/compat/ftb/FtbChunksMapDivideClient.java"
  - "src/main/resources/mixins.war_project.json"
session_ids:
  - "f6011282-c5cf-4a79-950a-a108d0ac6682"
memory_body_ids:
  []
---

# War Project 增量交接：小地图 FBO 未渲染排查、Node 名称渲染、FTB 编辑器交互

本文只记录**尚未被既有文档覆盖**的增量：小地图叠加层「完全不显示」的排查与加固、node 名称渲染的实现分工、FTB 大地图编辑器的交互语义。

关联文档（按需先读）：

- `5ea0e65d-6677-419e-9263-916413603f03` — 小地图 FBO 路径的**现行机制**与字节码依据。本文**补充**其失败排查与加固，不取代它。
- `d33309cb-4d09-4115-b151-97634cb3315e` — 渲染规则、颜色、几何口径（`dashPattern` / `edgeThickness` / `minimumDashedLength`）、大地图 `EdgeCollector` 带式绘制与**轴感知像素键**。
- `dbb656b9-3bb8-4211-ad68-f2bba70b49b2` — 世界地图最小缩放 1x 与「不做渲染预判断」的变更说明（其「可见区精确裁形」一节已被 `5ea0e65d` 取代）。
- `3d31dded-b45f-4f11-a183-0d1aa78e4faa` — 架构、占点系统与可选依赖集成。

Xaero 侧依据版本：World Map **1.44.2**、Minimap **26.4.2**（用 JDK 的 `javap` 核对）。部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 小地图 FBO 叠加层「完全不渲染」的排查

### 1.1 已排除的两个假设

**假设 A：mixin 方法描述符不匹配（→ 静默失效）。排除。**
用 `javap -s` 取到 `MinimapFBORenderer.renderChunksToFBO` 的**权威描述符**，与 `XaeroMinimapFboOverlayMixin` 中写的字符串逐字符一致：

```
renderChunksToFBO(Lxaero/hud/minimap/module/MinimapSession;Lnet/minecraft/client/gui/GuiGraphics;
Lxaero/common/minimap/MinimapProcessor;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/resources/ResourceKey;
DIFIZZIDDZLxaero/common/graphics/CustomVertexConsumers;)V
```

redirect 目标（与调用点一致）：

```
Lxaero/hud/minimap/element/render/map/MinimapElementMapRendererHandler;render:
(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/phys/Vec3;FLcom/mojang/blaze3d/pipeline/RenderTarget;
DLnet/minecraft/resources/ResourceKey;)V
```

**假设 B：`GuiGraphics` 缓冲晚于 FBO 解绑。排除。**
`renderChunksToFBO` 的顺序是：map 元素 `render(...)`（偏移 1746）→ `BufferSource.endBatch()`（偏移 **1755**）→ ImmediatelyFast flush（1759）→ `rotationFramebuffer` 解绑（偏移 **1763**）。提交发生在解绑**之前**，所以注入点追加的几何确实进入 minimap FBO。

### 1.2 主要嫌疑：`usingFBO()` 为假

`xaero.hud.minimap.Minimap.usingFBO()` = `!SAFE_MODE && getMinimapFBORenderer().isLoadedFBO() && …`。

为假时 Xaero 改走 `xaero.common.minimap.render.MinimapSafeModeRenderer`（**没有 framebuffer**），我们针对 `MinimapFBORenderer` 的注入**根本不会触发**，表现为叠加层完全不出现。常见触发：

- 小地图配置 `MinimapProfiledConfigOptions.SAFE_MODE` 被开启；
- FBO 初始化失败（旧显卡 / 驱动 / 显存）。

### 1.3 诊断手段（已落地）

`XaeroMinimapFboOverlay` 首次成功绘制时打印一次：

```
War Project minimap overlay active in the Xaero framebuffer (target WxH, zoom Z)
```

- **有**该日志 → FBO 路径生效，问题转到坐标 / 深度层面，可按日志里的 target 尺寸与 zoom 继续定位；
- **无**该日志 → 未走 FBO 路径（安全模式或注入未生效）。

另提供 `XaeroMinimapFboOverlay.isFboActiveRecently()`（1 秒活跃窗口），供屏幕层判断 FBO 是否在用（例如将来做回退路径时使用）。

### 1.4 加固：绘制后立即提交

`XaeroMinimapFboOverlay.renderWarProjectOverlay` 在画完几何后调用 `graphics.flush()`，让自己在 **FBO 仍绑定期间**提交，避免：

- 被 Xaero 后续元素渲染改动的着色状态二次调制（顶点虽已写入缓冲，但 uniform 在 `endBatch` 时才取值）；
- 提交落到错误的渲染目标。

### 1.5 未决项

若确认用户实例处于安全模式且仍需几何可见，可把几何也搬到屏幕层并配可见区裁形。当前用户明确选择「只做 FBO 路径」，因此安全模式下只显示名称、不显示几何。

---

## 2. Node 名称渲染

约定（用户明确）：**世界地图与小地图都要在 node 区块的几何中心渲染其名称。**

### 2.1 共享工具（`XaeroWarProjectMapRenderer`）

- `geometricCenter(Iterable<Long> chunks)` — 所有区块中心的世界方块坐标取平均；空集合返回 `null`。
- `label(String name, String id, int maxLength)` — 名称为空/空白时回退 id，再按 `maxLength` 截断。

### 2.2 世界地图

`XaeroWorldMapScreenOverlay.drawLabels(...)`，在填充与边界之后调用：

- 取几何中心所在区块的 `chunkRect(...)` 中心作为文字锚点；
- `graphics.drawCenteredString(font, label, cx, cy - 4, 0xFFFFFFFF)`，上限 `LABEL_MAX_LENGTH = 16`；
- 矩形与屏幕无交集时跳过。

### 2.3 小地图（**屏幕层**，不放进 FBO）

新增 `XaeroMinimapLabelOverlay` + `XaeroMinimapScreenOverlayMixin`（注入 `xaero.common.minimap.render.MinimapRenderer.renderMinimap` 内 over-map `handler.render` 调用点，`require = 0`）。

**为什么放在屏幕层**：FBO 内容会随地图旋转 / 缩放，文字必须保持**正向**与**可读尺寸**；放进 FBO 会被地图的旋转与缩放带走，或需要额外抵消变换。

实现要点：

- 反射读 over-map handler 的 `ps` / `pc` / `zoom` / `specW` / `specH` / `circle`；
- 定位：`px = (ps·dx − pc·dz)·zoom`、`py = (pc·dx + ps·dz)·zoom`（与屏幕层元素同构）；
- 可见性：矩形用 `±specW × ±specH`、圆形用半径 `specW`，并留 `EDGE_MARGIN = 10px`，中心离边缘过近则不画（避免文字糊在圆边外）；
- 上限 `LABEL_MAX_LENGTH = 14`。

### 2.4 mixin 注册表现状

`src/main/resources/mixins.war_project.json` 的 `client` 数组共 5 项：

```
xaero.XaeroWorldMapSessionMixin
xaero.XaeroMinimapSessionMixin
xaero.XaeroMinimapFboOverlayMixin      （几何 → FBO）
xaero.XaeroMinimapScreenOverlayMixin   （名称 → 屏幕层）
xaero.XaeroWorldMapZoomLimitsMixin     （世界地图最小缩放 1x）
```

`WarProjectMixinPlugin.shouldApplyMixin` 对 `.xaero.` 前缀做 `classExists(target) && classExists(mixin)` 守卫，未安装 Xaero / 关闭 optional compat 时整组跳过。

---

## 3. FTB 大地图编辑器交互

- **Shift + 左键**：不进入区块编辑、**不 cancel 事件**，交回 FTB 拖图（判定用 `Screen.hasShiftDown()`）。普通左键拖拽仍是选择 / 取消区块，释放后弹出选择菜单。
- **右键统一语义**：右键 node 与右键战区是**同一件事**——战区与 node 一对一时，战区按 `warzoneAtChunk → warzone.nodeId() → nodeById` 解析到所属 node。菜单项：`Edit node`（`NameIdPromptOverlay` 预填当前名称与 id → `sendRenameNode`）、`Delete node`、`Cancel`。点在空白处返回 `false`，事件透传不影响 FTB 原有右键行为。
- **选择菜单去重**：`Clear selection` 与 `Cancel` 合并为一项（NODE 阶段 = 清空选择并留在编辑模式，WARZONE 阶段 = 退出流程）。
- **工具栏**：`Node` 按钮是进出编辑模式的 toggle（退出即清空选择、清空 pending node）；`Clear` 按钮已移除；`Create Node` / `Confirm Warzone` 按阶段出现。
- **服务端入口**：`WarProjectNetwork.EditMapObjectPacket(action, id, newId, name)`，`action ∈ {rename_node, delete_node, delete_warzone}`，全部走 `hasPermissions(2)` 校验后广播地图快照。
  - 删除 node 会连带删除其绑定战区（`MapData.deleteNode` 内部处理）并清理占领进度（`NodeOccupationService.clear`）；
  - 改名复用 `MapDivideStateApi.renameNode`，会同步绑定战区的 `nodeId` 与占领进度引用。

---

## 4. 构建与验证口径

- 两种构建都必须通过：`gradlew build`（自动探测本地 jar 以启用 FTB / Xaero 可选兼容）与 `gradlew build -PwarProjectDisableOptionalCompat=true`。
- 可选 jar 探测**必须排除**文件名含 `neoforge` / `fabric` / `quilt` 的包：`"neoforge"` 含子串 `"forge"`，曾导致误选 NeoForge 1.21.1 的 FTB Library（class 版本 65）而编译失败；并优先选择文件名含 `1.20.1` 者。
- 同步 jar 到部署目录后需**重启客户端**才会重新加载 mixin。

---

## 5. 已知缺口

- 安全模式（无 FBO）下不显示叠加层几何，仅名称可见。
- FBO 内坐标**未乘** `optionalScale`；若实机出现整体错位，需要把该字段乘到 `px` / `py`（字段已在反射读取范围内，属一行改动）。
- 世界地图节点名称未做重叠消隐，密集节点可能文字叠压。
