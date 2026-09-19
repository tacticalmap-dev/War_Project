---
id: "465b774a-b054-4ad1-a532-bd28c94919f7"
title: "世界地图叠加层亚像素线宽：POSITION_COLOR 浮点 quad 取代 GuiGraphics.fill"
description: "世界地图叠加层改用自建 POSITION_COLOR 浮点 quad 实现亚像素线宽（EDGE_WIDTH=0.5）的设计与字节码证据；取代 d33309cb / 8eb90438 中「用 fill 绘制、1px 是下限」的记载。改该渲染前先读本文。"
status: "active"
created_at: "2026-09-18T21:14:32.775Z"
updated_at: "2026-09-19T19:33:38.649Z"
content_hash: "a2355115304a7c864175b18bfbae219801e10a97557d791facb5200c225941a4"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/NodeLJYSCaptureHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
session_ids:
  - "f003a139-913a-4204-aa6a-3caba1ae5680"
  - "session-f7acdd28-0b0d-4e91-b421-d75c5ff31933"
memory_body_ids:
  []
---

---
id: "465b774a-b054-4ad1-a532-bd28c94919f7"
title: "世界地图叠加层亚像素线宽：POSITION_COLOR 浮点 quad 取代 GuiGraphics.fill"
description: "世界地图叠加层改用自建 POSITION_COLOR 浮点 quad 实现亚像素线宽（EDGE_WIDTH=0.5）的设计与字节码证据；取代 d33309cb / 8eb90438 中「用 fill 绘制、1px 是下限」的记载。改该渲染前先读本文。"
status: "active"
created_at: "2026-09-18T21:14:32.775Z"
updated_at: "2026-09-18T21:14:32.775Z"
content_hash: "c5d9ff3556af1ad83aec5007e9b1d7f5aabcf06736dbf3a3fb66a8f4663dc598"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/NodeLJYSCaptureHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
session_ids:
  - "f003a139-913a-4204-aa6a-3caba1ae5680"
memory_body_ids:
  []
---

# 世界地图叠加层亚像素线宽实现（War Project）

本文记录 `XaeroWorldMapScreenOverlay` 从 `GuiGraphics.fill(int…)` 迁移到自建 `POSITION_COLOR` 浮点
四边形（下称 B 方案）的背景、设计决定与字节码验证证据。

**与既有文档的关系（改世界地图渲染前请合并阅读）**

- 本文**取代** `d33309cb-4d09-4115-b151-97634cb3315e` 中「世界地图用 `GuiGraphics.fill` 逐帧绘制」与
  `edgeThickness` 口径的记载：`edgeThickness` 现已**恒返回 1**（hairline 底限），不再是
  `clamp(round(chunkPixels/14), 1, 6)`；世界地图也不再走 `fill`。该文其余规则（颜色口径、战区外轮廓判定、
  node 优先级、`DashPattern`）仍然有效。
- 本文**取代** `8eb90438-7478-4cfa-90a0-112cdbe5168f` 中「线宽恒定 1px 是像素下限、无法再细」的结论：
  1px 只是 `GuiGraphics.fill` 的下限，自建顶点后可低于 1 逻辑像素。
- `dbb656b9-3bb8-4211-ad68-f2bba70b49b2`（1x 缩放下限、小地图裁形）不受影响，仍然有效。

## 1. 问题与约束

- 世界地图叠加层原本每段线/每块填充调用一次 `GuiGraphics.fill(int x1,int y1,int x2,int y2,int color)`。
  该方法参数是 `int`，**1 逻辑像素是硬下限**。
- 真正的「粗」来自 GUI 缩放：1 逻辑像素在 `guiScale = N` 时占 N×N 物理像素。界面缩放 3 时，
  屏幕上的线就是 3 物理像素宽。
- 用户诉求：世界地图线条进一步变细（小地图字体已在另一轮放大，小地图线宽本轮不动）。

## 2. 候选方案与选择

| 方案 | 做法 | 代价 | 结论 |
| --- | --- | --- | --- |
| A | 降低线条 alpha（物理宽度不变，视觉变轻） | 改一个常量 | 未采纳（未真正变细） |
| B | 自建 `POSITION_COLOR` quad，float 坐标，亚像素线宽 | 需重写线段绘制 | **已实现** |
| C | 半宽渐变纹理 + 自定义 RenderType | 新增纹理资源，收益同 A | 不推荐 |

不可行方向：`RenderType.lines()` 的线宽在现代 MC GUI 管线中不可控；绕过 GUI 逻辑像素坐标不可能
（GUI 坐标本身就是逻辑像素）。亚像素的物理必然：`guiScale = 1` 时 0.5 px 线只覆盖半像素，
呈现为「约 50% 亮度的 1px 线」，是变暗而非真正变窄。

## 3. 实现要点

文件：`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java`

- **常量**：`EDGE_WIDTH = 0.5F`（逻辑像素，唯一调参入口）；`BAND_QUANTUM = 8.0F`（band 位置量化到
  1/8 像素）；`MIN_GUI_SCALE = 0.015D`。
- **单批提交**：`graphics.flush()` → `Tesselator.getInstance().getBuilder()` → 一次
  `buffer.begin(QUADS, POSITION_COLOR)` → 写全部填充与边界顶点 → 一次
  `BufferUploader.drawWithShader(buffer.end())`。取代原先每段一次 `fill` 的排布。
- **顺序保证**：自绘前先 `flush()` 提交地图自身挂起的批次，overlay 因此稳定位于地图内容之上、
  节点名称之下（名称仍在自绘之后用 `graphics.drawCenteredString` 画）。
- **填充一并浮点化**：战区半透明填充与边界共用同一 float 坐标。原实现的填充用 `floor` 取整边界，
  与细边界线最多错位 1px；统一后缝隙消失。
- **band 语义不变**：带仍始终落在区块矩形内侧（西 `[x1, x1+w)`、东 `[x2-w, x2)`，南北同理），
  `w = EDGE_WIDTH`。
- **量化与合并**：`EdgeKey` 存 `quantiseBand(band) = round(band * 8)` 的整数值，避免浮点噪声把同一条边
  拆成两个 band，破坏 `mergedEnd + 1` 的相邻段合并（合并是防止半透明线段交界处叠加变亮的关键）。
- **重叠/优先级检测仍按整数像素栅格**：`bandPixelStart/bandPixelEnd` 把亚像素带映射到它触及的整像素，
  `reservedPixels` 键仍是**轴感知**的 `packedPixel`（竖线 `(offset, parallel)`、横线 `(parallel, offset)`，
  参见 `d33309cb` 记录的坑）。检测保持保守，node 实线仍完整压住战区虚线。
- **虚线节奏不变**：沿线段方向仍按整数像素步进判断 `dashPattern.on(...)`，只有线宽方向变细。
  小缩放时 `chunkPixelSize` 由浮点 `chunkRect` 四舍五入得到，喂给 `dashPattern` 的口径不变。
- **顶点顺序**与 `GuiGraphics.fill` 相同（左下 → 右下 → 右上 → 左上，z = 0.0F），共用同一 cull 状态；
  `addQuad` 内部对坐标取 min/max 并对零面积返回，避免反向或退化 quad。

## 4. 已核对的字节码事实（forge-1.20.1-47.4.10_mapped_official_1.20.1.jar）

用 `javap -p -c` 反汇编确认，非推测：

- `GuiGraphics.flush()` = `RenderSystem.disableDepthTest()` → `bufferSource.endBatch()` →
  `RenderSystem.enableDepthTest()`。幂等且结束共享 builder 的 building 状态，因此在它之后调用
  `Tesselator.getInstance().getBuilder().begin(...)` 安全。
- `BufferBuilder` 字段全部为实例字段（仅 `GROWTH_SIZE` / `LOGGER` 是 `static final`），
  构造器 `BufferBuilder(int)` 为 public → 独立实例可行，不共享 Tesselator 状态。
- `BufferBuilder.end()` 只校验 `building` 标志，`storeRenderedBuffer()` 以 `vertices` 计算索引数，
  **0 顶点空批安全**（不必为「有数据但全在屏幕外」的帧做特殊处理）。
- `BufferUploader.drawWithShader(BufferBuilder$RenderedBuffer)` 存在。
- `vertex(Matrix4f,float,float,float)` 走 `VertexConsumer` 接口（`javap` 不列继承方法）；
  该调用链在本项目 `client/NodeLJYSCaptureHudOverlay.drawRingSegment` 中已实际运行，
  是同一套已验证写法（`RenderSystem.setShader(GameRenderer::getPositionColorShader)` +
  `enableBlend` + `defaultBlendFunc`）。
- 注意：绘制完成后**不要** `RenderSystem.disableBlend()`，否则随后绘制的节点名称会丢失 alpha 混合。
  既有 HUD 代码在圆环后 disableBlend 是安全的，因为它的文字画在圆环之前。

## 5. 备份、构建与部署流程（本轮实际执行）

- 改动前整包备份源码：`E:\mc_mod_dev\_backup\War_Project_planA_20260919_050521`
  （55 个文件：`src`、`build.gradle`、`settings.gradle`、`gradle.properties`、`docs`、`gradlew.bat`）。
- `.\gradlew.bat build`（自动启用 FTB/Xaero 可选兼容）→ BUILD SUCCESSFUL（33s，Java 21 运行 Gradle，
  目标 toolchain 17）。
- 产物 `build/libs/war_project-1.0.0.jar` = 204968 字节（A 方案为 203188，新增 `FloatRect` 等内部类）。
- 同步到 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar` 并比对 SHA256
  （`5E1DA0CF…FEFA3924`）一致。
- mods 目录残留 `war_project-1.0.0.jar.old`（184149 字节），Forge 不加载 `.old`，可删除。

## 6. 验证状态

- 编译与打包已通过；jar 内容确认含新内部类（`XaeroWorldMapScreenOverlay$FloatRect` 等，时间戳为本轮构建）。
- **未实机确认**。需重启客户端后验收：世界地图线条是否明显变窄、线段是否不再因取整而轻微跳动、
  0.5px 线在低 GUI 缩放下是否偏暗到不可接受、节点名称是否仍正常渲染（不应因 blend 被关闭而异常）。

## 7. 调参与后续选项

- 更细 / 更实：改 `EDGE_WIDTH`（0.35 / 0.25 更细，0.75 更实），或单独提高
  `XaeroWarProjectMapRenderer.EDGE_ALPHA`。
- 小地图两条路径（`compat/xaero/XaeroMinimapOverlay.java`、`XaeroMinimapFramebufferOverlay.java`）
  本轮**未动**，仍使用 `edgeThickness(chunkPixels)`（现恒为 1）与 `fill`/`fillClipped`。
  若小地图也要同款亚像素线宽，需按同一思路改造其裁剪与 dash 相位逻辑（圆/矩形可见区裁形会变复杂）。

## 8. 非目标

- 不改颜色口径、战区外轮廓规则、node 优先级语义、`DashPattern` 公式与 dash 相位锚定。
- 不改占领 HUD、FTB 编辑器、小地图两条渲染路径与 FBO 注入。
- 不调整世界地图投影公式（仍反射读 `cameraX` / `cameraZ` / `scale` / `screenScale`）。

> 模块改名注记（2026-09-20）：文中 `wargame` / `Wargame*` 已于该日更名为 `nodeLJYS` / `NodeLJYS*`（`NodeLJYSModule` 的 id 为 `"nodeLJYS"`），本文引用名已同步更新；本文其余内容为改名前的交接记录。
