---
id: "7f889ca8-495a-49ec-9c5d-00068c527497"
title: "世界地图线条全浮点化与直角闭合：取代整数像素重叠检测"
description: "War Project 世界地图叠加层的第二轮修正：轴向取消整数量化、占用检测由整数像素集合改为浮点区间相减（subtractCovered/drawSpan），解决节点直角错位与断口。本文取代关联文档 465b774a 中「重叠/优先级检测仍按整数像素栅格」与「只有线宽方向变细」两节；改动该渲染前请两文并读。"
status: "active"
created_at: "2026-09-19T05:36:26.459Z"
updated_at: "2026-09-19T05:36:26.459Z"
content_hash: "b943316003b7d6dfe9ea6a06926ede4fa31e429638bcfa19c92d82b1ec006a6e"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
session_ids:
  - "abe5cf7c-9a41-48a7-9c60-b8c83591ea5f"
memory_body_ids:
  []
---

# 世界地图线条全浮点化与直角闭合

关联文档：`465b774a-b054-4ad1-a532-bd28c94919f7`（世界地图叠加层亚像素线宽：POSITION_COLOR 浮点 quad 取代 `GuiGraphics.fill`）。

## 0. 本文取代什么

`465b774a` 第 3 节中的两处描述**已作废**，对应代码已从源码中删除：

- 「重叠/优先级检测仍按整数像素栅格」——`bandPixelStart` / `bandPixelEnd` / `reservedPixels` /
  轴感知 `packedPixel` 全部移除，改为**浮点区间相减**。
- 「沿线段方向仍按整数像素步进……只有线宽方向变细」——线段的**长度方向同样浮点**，不再 `floor`/`ceil`。

`465b774a` 其余内容仍然有效：`EDGE_WIDTH = 0.5F`、`BAND_QUANTUM = 8.0F`、单批提交
（`graphics.flush()` → 一次 `begin` → 一次 `BufferUploader.drawWithShader`）、band 始终落在区块矩形内侧、
量化后合并相邻段、顶点顺序与 `GuiGraphics.fill` 一致、以及第 4 节的 javap 字节码事实。

触发原因：用户贴出节点轮廓截图，四角**竖线出头、横线悬空、右下角竖线断开**，并要求
「使顶点精度提高，完全闭合」。

## 1. 三条根因（都在第一版代码里）

1. **横线外凸**：横线的长度范围写作 `floor(x1) → ceil(x2)`，而线宽方向是浮点 `[x1, x1+0.5)`。
   当 `x1` 落在像素中间（如 100.7），横线从 100 起步，比竖线左边界更靠外，角上多出一小截悬空横线。
2. **竖线出头**：竖线的 y 范围同理写作 `floor(y1) → ceil(y2)`，比横线的 y band `[y1, y1+0.5)` 更高，
   竖线自 `floor(y1)` 起画，捅出横线之外。
3. **角部真实断口**：占用表以**整数像素**记账。同优先级排序为 priority 降序 →
   `vertical` 升序（`false` 水平线在前）→ `bandStart` 升序，于是水平线先画并占掉角部那个整数像素，
   竖线随后被判「已占用」而整段跳过 1px。

统一教训：**只要有一条边被量化到整像素，垂直于它的那条边就会在角部产生同量级错位**；
而用比绘制更粗的栅格做重叠扣除，等于在交点处主动挖洞。

## 2. 修正要点

文件：`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java`

- **轴向全浮点**：`collectChunkEdges` 直接传入 `rect.y1()/rect.y2()`（竖线）与 `rect.x1()/rect.x2()`（横线），
  band 与长度由**同一个 `FloatRect`** 派生，不再做任何取整。
- **角部覆盖**：竖线 `x ∈ [x1, x1+w)`、`y ∈ [y1, y2]`；横线 `y ∈ [y1, y1+w)`、`x ∈ [x1, x2]`，`w = EDGE_WIDTH`。
  角部交叠区 `[x1, x1+w) × [y1, y1+w)` 由两条线共同覆盖，形成实心闭合 L 角。
- **占用检测改为浮点区间相减**：`subtractCovered(start, end, covered)` 取代整像素集合；
  只在**同一 band** 上按优先级扣减，band 用 `record BandKey(vertical, bandStart, bandEnd)`（存量化后的整数）。
  - 相邻 1px 不再被误伤 → 断口消失；
  - 同一 band 的跨优先级重叠（node 实线压战区虚线）依旧**完全不叠加**，不会混色。
- **线段区间类型**：`EdgeSegment` 由 int 改为 `float start/end`；合并容差
  `MERGE_EPSILON = 0.01F`。相邻区块的共享边由同一世界坐标经同一表达式投影，浮点值逐位相同，
  该容差远小于最小 dash 间隙（2px），不会误合并真实间隙。
- **虚线**：仍按整数 step 决定 on/off 翻转（屏幕绝对相位与「虚线长度随缩放」的既有口径不变），
  但每个 run 的两端用 `Math.max/min` 裁到精确浮点 → 不虚线的段端点是真实角坐标。此逻辑集中在 `drawSpan`。
- **删除**：`drawVerticalLineAvoiding`、`drawHorizontalLineAvoiding`、`isBandReserved`、`reserveBand`、
  `bandPixelStart`、`bandPixelEnd`、`packedPixel`。

## 3. 可复用判据

- 线宽方向与长度方向必须共用同一浮点源；混用「浮点宽 + 整数长」必然在角部露馅。
- 重叠/优先级扣除必须与绘制**同粒度**。
- 宁可允许角部两条 0.5px 线微量叠加（角点覆盖度升高、视觉略实），也不要挖洞：
  断口比略亮更破坏「一笔画出的矩形」的观感。

## 4. 验证与构建

- 每步改动前对 `forge-1.20.1-47.4.10_mapped_official_1.20.1.jar` 用 `javap -p -c` 核对
  （`GuiGraphics.flush()` 字节码、`BufferBuilder` 全实例字段、`end()` 空批安全等，见 `465b774a` 第 4 节）。
- 构建：本机 Gradle **daemon 模式会卡死**（daemon 日志停在 "The daemon has started executing the build"
  之后再无输出、CPU 停滞），改用 `.\gradlew.bat build --no-daemon --offline`，后台作业 + `*>` 重定向日志。
- 部署核对：复制到 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar` 后比对 SHA256 并确认
  jar 内目标 class 的时间戳为本次编译产物。
- 运行期观感：`guiScale = 1` 时 0.5 px 线呈现为「约 50% 亮度的 1px 线」（变暗而非变窄），
  更高 GUI 缩放才是真正变窄；调参入口仍是 `EDGE_WIDTH`。
