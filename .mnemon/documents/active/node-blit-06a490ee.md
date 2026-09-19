---
id: "06a490ee-d2a3-42d8-bb50-7ce479c1424d"
title: "世界地图 node 标记：资源收益行、缩放驱动尺寸与 blit 重载陷阱"
description: "War Project 世界地图上「node 名称 + 资源收益行」的设计与踩坑记录：图标/收益行的布局与缩放规则、缩放判定值取自 Xaero GuiMap.scale 的证据链、GuiGraphics.blit 9 参与 11 参重载的字节码差异（图标\"画了但看不见\"的真因，同时影响顶部资源 HUD）、纹理路径约定与数据来源。改世界地图标记或任何图标渲染前读本文；几何线条见 465b774a / 7f889ca8。"
status: "active"
created_at: "2026-09-19T07:16:27.126Z"
updated_at: "2026-09-19T07:16:27.126Z"
content_hash: "9c4cfaa04503ded1fb50bd41541900c4edcff299b31ff69b34e64947a8d85335"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/ClientMapState.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/resources/assets/war_project/textures/gui/ammo.png"
  - "src/main/resources/assets/war_project/textures/gui/fuel.png"
session_ids:
  - "58247cb8-0b2a-45e2-b8ca-2ba3529f7449"
memory_body_ids:
  []
---

# 世界地图 node 标记：资源收益行、缩放驱动尺寸与 blit 重载陷阱

本文记录 War Project 在 Xaero 世界地图上渲染「node 名称 + 资源收益行」这一轮的设计决定、证据链与
踩坑。几何线条（亚像素线宽、浮点闭合）见 `465b774a-b054-4ad1-a532-bd28c94919f7` 与
`7f889ca8-495a-49ec-9c5d-00068c527497`；小地图 FBO 与 node 名称渲染分工见
`1223eb8a-dd85-486c-acd6-b327dec5584d`。本文只覆盖**名称下方的收益行**这一层。

## 1. 规格（用户定调）

1. 在 Xaero **世界地图**上，node 名称下方渲染一行资源收益：每个资源 = 图标 + `+N`。
2. **整块居中**：名称与其下的收益行视为一个整体，垂直居中于 node 区块的几何中心；
   没有收益行时，名称自身居中。
3. **随缩放变化**：图标尺寸与各间距随地图缩放线性变化；数字文字保持默认字号（缩小到 2.5x 附近
   仍要清晰）。
4. **低于 2.5x 不渲染收益行**（图标与 `+N` 都不画），只保留名称。

## 2. 缩放判定值取自哪里（证据链）

实现里用 Xaero `GuiMap` 的 `scale` 字段（`XaeroWorldMapScreenOverlay.readProjection` 反射读取），
它就是地图界面右下角显示的那个倍数（如 `5.16x`）。三条依据：

- `GuiMap.applyZoomLimits()` 的字节码里出现常量 `0.0625d`（UNLIMITED_ZOOM_OUT 时 `0.001953125d`）
  与 `50.0d`，作用于静态字段 `destScale`；本项目此前把下限改成 `1.0D` 正是「界面最小 1x」，
  说明该字段的量纲就是界面显示值。
- `scale` 是插值到 `destScale` 的当前渲染缩放，投影公式
  `screenX = centerX + (worldX - cameraX) * (scale / screenScale)` 已在既有实现中对齐正确，
  即 `scale` = 世界方块 → 物理像素的比例。
- 截图实测交叉验证：1 个 chunk ≈ 88 物理像素 → 约 5.5 px/方块，与界面显示的 `5.16x` 吻合。

**不要用 `getUserScale()`**：反汇编显示它只是 `return this.userScale;`，与渲染缩放不是同一量。

## 3. 布局算法

文件：`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java`

- `drawNodeLabel(...)`：计算 `labelHeight = font.lineHeight`；收益行存在时
  `blockHeight = labelHeight + rowGap + max(iconSize, labelHeight)`，否则 `blockHeight = labelHeight`；
  然后 `labelTop = round(centerY - blockHeight / 2)`。收益行画在 `labelTop + labelHeight + rowGap`。
- `gainSize(base, zoom)`：`factor = clamp(zoom / GAIN_ZOOM_BASE, GAIN_MIN_SCALE_FACTOR,
  GAIN_MAX_SCALE_FACTOR)`，返回 `round(base * factor)`（至少 1）。
- `drawResourceGains(...)`：只画向下取整后 ≥ 1 的资源（`gainAmount` 用 `floor`，所以不会出现
  `+0`）；`rowWidth` 由各段宽度相加后整体水平居中于 node 中心。
- 调参入口（全部是常量）：`GAIN_MIN_ZOOM = 2.5`、`GAIN_ZOOM_BASE = 5.0`、
  `GAIN_MIN_SCALE_FACTOR = 0.7`、`GAIN_MAX_SCALE_FACTOR = 2.2`、`GAIN_ICON_SIZE_BASE = 14`、
  `GAIN_ICON_TEXT_GAP_BASE = 2`、`GAIN_ENTRY_GAP_BASE = 6`、`GAIN_ROW_GAP_BASE = 2`、
  `GAIN_COLOR = 0xFF7CE38B`（与顶部资源 HUD 同色）。
- `MapProjection` 为此新增 `zoom` 字段（原始 `scale`），`guiScale`（`scale / screenScale`）仍只用于
  几何投影。

## 4. 核心教训：`GuiGraphics.blit` 的 9 参重载把目标尺寸当作采样尺寸

**症状**：图标完全看不见，但旁边的 `+N` 文字正常渲染；无 missing-texture 的紫黑格。

**真因**（`javap -c net.minecraft.client.gui.GuiGraphics` 反汇编证实）：9 参重载
`blit(ResourceLocation, int, int, float, float, int, int, int, int)` 转发给 11 参重载时，
把**目标宽高**同时当作 u/v 采样尺寸：

```
blit(RL,int,int,float,float,int,int,int,int)
  -> blit(rl, x, y, width, height, uOffset, vOffset,
                         width, height, texW, texH)
                         ^ uWidth = width, vHeight = height
```

于是「把 128×128 的图画成 14×14」实际变成「只取该纹理**左上角 14×14**」。两张图标是画在
128×128 画布正中（四周透明），左上角恰好是全透明区 —— 画了等于没画。

**正确做法**：必须用 **11 参**重载并显式声明源矩形：

```java
graphics.blit(icon, x, y, iconSize, iconSize, 0.0F, 0.0F,
        ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
// (rl, x, y, drawW, drawH, uOffset, vOffset, srcW, srcH, texW, texH)
```

前四个 `int` 是 `(x, y, width, height)` 已由字节码证明（`iload_2 + iload 4`、`iload_3 + iload 5`）。
后六个参数传 0 与边长这类对称值时，即使槽位理解有偏差，结果也一致 —— 推荐的写法。

**同一坑的第二处受害者**：`src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java`
的 `drawEntry`（顶部弹药/燃料条）原本也用 9 参调用，因此 HUD 图标同样不显示；已一并改为 11 参。
任何新增图标渲染都应复用 11 参形式。

## 5. 纹理路径约定

`ResourceLocation` 要写**完整路径**：`war_project:textures/gui/ammo.png`。
证据：`AbstractWidget.WIDGETS_LOCATION` 的常量就是 `textures/gui/widgets.png`（字节码 ldc），
且 `TextureManager` 中不存在二次拼接 `textures/` 前缀的逻辑。资源实体放在
`src/main/resources/assets/war_project/textures/gui/`（ammo.png / fuel.png，128×128），
构建后随 jar 打包（可在 jar 内以 `assets/war_project/textures/gui/...` 核对）。

## 6. 数据来源

收益数值直接来自 node 自身的产出配置，无需新增网络字段：

- 服务端：`MapData` 的 node `save()` 写入 `ammo_per_minute` / `fuel_per_minute`，
  经 `clientSnapshot()` → `MapSyncPacket` 下发。
- 客户端：`ClientMapState.ClientNode` 已有 `ammoPerMinute` / `fuelPerMinute` 两个 double 字段
  （`ClientMapState.replace` 读取同名 NBT 键）。

## 7. 验证状态

- 已用 `javap` 核对的：`blit` 两个重载的参数语义、vanilla 纹理路径约定、`GuiMap` 的
  `scale` / `destScale` / `userScale` 字段与 `applyZoomLimits` 常量。
- 已在游戏内被用户确认过的：图标显示本身（此前空白问题修复后）。
- **尚未经游戏内确认**：整块居中、随缩放的尺寸、2.5x 阈值这三项（本轮改动仅通过编译与 jar 打包校验）。
  若缩放判定与界面显示值有偏差，先怀疑 `scale` 口径，再看 §2 的三条依据。
