---
id: "8eb90438-7478-4cfa-90a0-112cdbe5168f"
title: "Xaero 小地图叠加层注入根因与 FBO 路径实现（War Project）"
description: "War Project 中 Xaero 小地图叠加层的注入根因定位与最终实现：`render` 声明在抽象基类（按子类匹配必失败）、`renderMinimap` 长描述符易抄错，两个陷阱都会因 require=0 静默失效；最终采用「几何进 FBO（注入 MinimapElementMapRendererHandler.beforeRender 的 RETURN）+ 屏幕层画名称与兜底」双路门控；含 FBO 绑定/提交/解绑偏移、基类 render 的 pose.translate(0,0,depth) 不恢复 + FBO 内深度测试开启的陷阱、线宽恒定 1px 的像素下限结论，以及用日志二分诊断的清单。更正 b8b4935c「FBO 路径废弃」的结论。改 Xaero 小地图渲染前先读本文。"
status: "active"
created_at: "2026-09-18T20:53:46.236Z"
updated_at: "2026-09-18T20:53:46.236Z"
content_hash: "433ffd42145ca7042ecf2b5f14c5630aaa109e98c0e64891d7cf81cfd8319cbb"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapFramebufferOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapFramebufferOverlayMixin.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapScreenOverlayMixin.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/main/resources/mixins.war_project.json"
session_ids:
  - "363387f6-e073-4f0d-84d6-99d8696644aa"
memory_body_ids:
  []
---

# Xaero 小地图叠加层注入根因与 FBO 路径实现（War Project）

本文是**增量交接**，只记录增量与新发现，不重复既有文档已覆盖的口径。

## 0. 本文更正哪些既有文档

- `b8b4935c-5926-449f-a42c-ad50c22883f9`：其中「**FBO 注入路径已废弃**」的结论**不再成立**。
  该结论依据的「两次实机不可见」实为**注入点错误**（见第 2 节根因），并非该路径不可用。
  本文恢复 FBO 路径并给出正确注入点；该文档中「线宽 `chunkPixels/28`（1–3px）」「几何+名称都在屏幕层」
  两项也已被本文第 6、3 节取代。其余内容（FTB 仅在编辑模式拦截、右键战区与右键 node 同义、
  菜单只保留一个取消项）仍然有效。
- `dbb656b9-3bb8-4211-ad68-f2bba70b49b2`、`d33309cb-4d09-4115-b151-97634cb3315e`：
  二者描述的「小地图叠加层走屏幕层并按可见区精确裁形」现降级为**兜底路径**；
  颜色口径、战区填充 alpha `0x40`、`EdgeCollector` 带式绘制与轴感知像素键、
  `applyZoomLimits` 的 1x 下限依据**仍然有效**，本文不重复。
- `1223eb8a-dd85-486c-acd6-b327dec5584d`：其排查证据（mixin 描述符与字节码逐字符一致、
  `endBatch` 发生在解绑之前、`usingFBO()` 判定式）**仍有效**，但其中「该路径不可见」的归因已被本文取代。

参考模组蓝本：`E:\mc_mod_dev\modernwar-town`。
部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。
字节码依据来自 mods 目录内实际加载的 **Xaero Minimap 26.4.2（1.20.1）** 与 **Xaero World Map 1.44.2**，用 JDK 的 `javap -p -c` / `javap -s` 核对。

## 1. 现行机制（本节为准）

| 内容 | 实现 | 注入方式 |
|---|---|---|
| 小地图**几何**（node/战区填充与边界） | `compat/xaero/XaeroMinimapFramebufferOverlay.java` | `mixin/xaero/XaeroMinimapFramebufferOverlayMixin.java` → `MinimapElementMapRendererHandler.beforeRender` 的 `@At("RETURN")` |
| 小地图**名称** + FBO 不可用时的几何兜底 | `compat/xaero/XaeroMinimapOverlay.java`（含 `Viewport` 可见区裁形） | `mixin/xaero/XaeroMinimapScreenOverlayMixin.java` → `MinimapElementRendererHandler.render` 的 `@At("RETURN")`，用 `instanceof MinimapElementOverMapRendererHandler` 过滤 |
| 世界地图（填充/边界/名称） | `compat/xaero/XaeroWorldMapScreenOverlay.java` | Forge `ScreenEvent.Render.Post`，**不使用 mixin** |

- 两路**不会重复绘制**：屏幕层用 `XaeroMinimapFramebufferOverlay.isActiveRecently()`（最近 1 秒内 FBO 是否活跃）决定是否跳过几何，名称始终由屏幕层画（保持正向、可读）。
- FBO 路径下**不需要自建可见区裁剪**：绘制进入地图纹理，由 Xaero 自身的方形/椭圆遮罩裁形，因此与地形像素级对齐。
- 屏幕层兜底路径仍按可见区裁形：矩形 `±specW × ±specH`，圆形半径 `specW`（Xaero 自身夹取所用的界限）。

## 2. 根因：两个都会「静默失效」的注入陷阱（本轮确立，最具复用价值）

1. **`render` 声明在抽象基类，不在具体 handler 上。**
   `xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler` 与
   `...element.render.map.MinimapElementMapRendererHandler` **都没有声明 `render`**；
   它声明在 `xaero.hud.minimap.element.render.MinimapElementRendererHandler`。
   若 `@Mixin(targets = "…MinimapElementOverMapRendererHandler")` 配 `method = "render"`，
   **目标类里找不到该方法**，配合 `require = 0` 就是**完全静默**——叠加层一次都不画。
2. **`renderMinimap` 有 11 个参数**，手写描述符（`L…MinimapSession;L…GuiGraphics;L…MinimapProcessor;IIIIDIFL…CustomVertexConsumers;`）
   极易差一个字符；`@Redirect` 的 method 串一旦不符同样静默跳过。

**由此确定的写法约定**：Xaero 侧一律
①**按方法名匹配**（`method = "beforeRender"` / `"render"`，不写描述符）；
②注入到**声明该方法的类**；
③在基类注入时用 `instanceof` 过滤到目标 handler，避免 FBO 侧与屏幕侧互相串台。
（`XaeroWorldMapZoomLimitsMixin` 用 `method = "applyZoomLimits"` 属同一约定，因此它一直生效。）

## 3. FBO 路径的字节码依据（`MinimapFBORenderer.renderChunksToFBO`）

按偏移顺序：

- `199` `scalingFramebuffer.bindAsMainTarget(true)` + `clear` —— 绑定缩放 FBO；
- `1427` `rotationFramebuffer.bindAsMainTarget(true)` + `clear`，`1444-1447` 读 scaling 纹理 —— 绑定旋转 FBO；
- `1712-1726` `mapHandler.prepareRender(ps, pc, zoom, halfWView)`；
- `1729-1746` **`mapHandler.render(gui, renderPos, partialTick, rotationFramebuffer, mapDimensionScale, mapDimension)`** —— 期间旋转 FBO 仍绑定；
- `1750` `GuiGraphics.m_280262_()`、`1755` `MultiBufferSource$BufferSource.endBatch()` —— 缓冲在此提交，**仍在 FBO 内**；
- `1763` `rotationFramebuffer.m_83970_()` 解绑；`1780` 恢复投影矩阵。

结论：在 **map handler 的渲染期间**绘制即进入地图纹理，且**不需要手动 `flush()`**（1750/1755 会提交）。

`prepareRender(double, double, double, float)` 的实现只是把 4 个值写入字段 `ps` / `pc` / `zoom` / `halfWView`；
字段 `ps`/`pc`/`zoom` 为 private，用反射读取。

## 4. 深度测试陷阱：注入点必须选 `beforeRender`

- `1692-1695`：`XaeroRenderType.resetDepthTest()` + `RenderSystem.enableDepthTest()` —— **FBO 内深度测试是开启的**。
- 基类 `MinimapElementRendererHandler.render` 偏移 `159-166`：
  `pose.translate(0, 0, getElementIndexDepth(...))`，**且从不恢复**。
- 因此在 `render` 的 `RETURN` 绘制会被这个 z 平移污染，在 FBO 里可能被深度拒绝（屏幕层因 GUI 深度状态不同而不受影响）。
- **两个子类的 `beforeRender` / `afterRender` 都是空实现**（字节码仅 `0: return`），而 `beforeRender` 在基类中于该 translate **之前**调用 →
  取 `MinimapElementMapRendererHandler.beforeRender` 的 `RETURN` 是「FBO 已绑定 + pose 已就位 + 深度未被污染」的唯一干净时点。

参数来源：`MinimapElementRenderInfo` 的字段全部 `public final`，用其中 `renderPos` 与 `framebuffer`。

## 5. 投影与坐标系（两处一致）

`transformAndRenderForRenderer`（over handler 与 map handler）用同一公式，pose 原点都在地图/小地图中心：

```
px = ps * dx * zoom - pc * dz * zoom
py = pc * dx * zoom + ps * dz * zoom     // dx = worldX - renderPos.x, dz = worldZ - renderPos.z
```

区块尺寸用 `half = ceil(8 * zoom)`（一个区块 16 方块）。两个 handler 的差别只是 `zoom` 的口径（FBO 内 vs 屏幕）。

## 6. 线宽「减不动」的原因与结论

`XaeroWarProjectMapRenderer.edgeThickness` 的历史：`/14 上限 6` → `/28 上限 3` → `/40 上限 2` → **恒返回 1**。

原因：`chunkPixels ≤ 60` 时 `/28` 与 `/40` 的结果都是 `1`，而**1px 是 `GuiGraphics.fill` 的像素下限**，
所以两次公式调整在常见缩放下不可见。现行实现返回常量 1，保证任何缩放级别都不会变粗。
若将来需要「视觉更细」，唯一有效手段是降低线条 alpha（`relationEdgeArgb` 的 `EDGE_ALPHA`），当前未改。

## 7. 诊断与部署清单

1. **mixin 三处声明**都要在：jar `MANIFEST.MF` 的 `MixinConfigs`、`META-INF/mods.toml` 的 `[[mixins]] config=…`、jar 根的 `mixins.war_project.json`。
2. `WarProjectMixinPlugin.shouldApplyMixin` 对 `.xaero.` 前缀做 `classExists(target) && classExists(mixin)` 守卫 → 未装 Xaero 或关闭可选兼容时自动跳过，不会崩。
3. **用日志二分**：FBO 首次绘制会打印
   `War Project minimap overlay is drawing inside the Xaero framebuffer (target WxH, zoom Z)`；
   屏幕层注入首次执行打印 `…injected into the Xaero over-map element handler`。
   有前者 → FBO 路径生效；只有后者 → Xaero 未用 FBO（`usingFBO()` = `!SAFE_MODE && isLoadedFBO()`），此时走屏幕层兜底。
4. 注入点若改动，先按第 2 节约定核对**声明类**与**方法名**，再用 `javap -p -c` 确认目标方法体与偏移。
5. 部署核对：比对 `build/libs/war_project-1.0.0.jar` 与 mods 目录同名文件的 `Get-FileHash`；
   注意旧备份 `war_project-1.0.0.jar.old` 后缀非 `.jar`，Forge 不会加载。

## 8. 用户硬性工作要求

用户明确要求：**不得猜测，每一步都必须先读源代码（含 `javap` 反编译字节码）验证后再改**。
本轮结论均按此方式取得；后续改动同一区域时同样先取证再改。
