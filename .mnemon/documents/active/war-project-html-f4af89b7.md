---
id: "f4af89b7-f749-4cd4-a2d7-fa33d2618987"
title: "War Project HTML 内核渲染质量与陷阱（超采样 / 绝对定位 / 大纹理性能）"
description: "2026-09-21 调试顶部 HUD 时定位并修复的 HTML 内核级问题：超采样抗锯齿的参数与开关、position:absolute 占流 bug、单 class 选择器限制、跨屏大纹理每帧重建导致掉帧，以及顶部 HUD 的最终参数与离线验证手段。改 HUD 或内核前先读本文；HUD 结构与 SBW 炮镜判定见 9995b13c，早期 SDF/SVG 圆角方案见 1c71271d。"
status: "active"
created_at: "2026-09-21T14:23:18.318Z"
updated_at: "2026-09-21T14:23:18.318Z"
content_hash: "36384f89419ba945b81722ef1197e4690374aaaa585cf5c30796d3ece34b74a6"
source_paths:
  - "src/main/java/com/flowingsun/war_project/html/HtmlTextures.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlVector.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlLayout.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlDocument.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "src/main/resources/assets/war_project/html/top_hud.html"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/SuperbWarfareCompat.java"
session_ids:
  - "38078e15-ee14-43e5-9577-751f2e91140b"
memory_body_ids:
  []
---

# War Project HTML 内核渲染质量与陷阱（2026-09-21）

自研 HTML 渲染内核（`html/` 包）负责顶部 HUD、资源胶囊与（无 Chromium 时的）转移面板。它按"1 纹素 = 1 GUI 像素"绘制，而 Minecraft 会把 GUI 放大 `guiScale` 倍，因此**形状密度与纹理生命周期**是这个内核最容易踩的两个坑。本文记录 2026-09-21 调试顶部 HUD 时定位到的全部内核级问题与修法。

## 1. 症状 → 根因 → 修复

| 症状 | 根因（已读代码验证） | 修复 |
| --- | --- | --- |
| 圆角/斜边全是阶梯（用户："要苹果一样的视感"） | 形状纹理按元素 GUI 尺寸生成（`HtmlTextures.texture` 收到 width×height），且 `DynamicTexture.setFilter(false,false)` 即 NEAREST；屏幕上被 `guiScale` 放大 | 引入超采样：`HtmlTextures.supersample()` = ceil(guiScale)（≤4；宽或高 > 256px 的元素 ≤2），形状与 SVG 都按该倍数光栅化，纹理改 LINEAR，`MAX_SIDE` 1024 → 4096，纹理 key 与 blit 的 uv 尺寸都换成纹素尺寸 |
| 资源胶囊与图标整体下移约 40px（"图标飘到屏幕中间"） | `HtmlLayout.arrange` 对 `position:absolute` 的子节点仍执行流布局并推进 cursor/rowCursor；`measureHeight` 却跳过 absolute → 父高度少算、兄弟被顶下去 | `arrange` 中 absolute 子节点照常 `arrange`（取得尺寸并布局自身子树）但**不推进** cursor / rowCursor / maxCross |
| 地图只有 1 个 vpnode 却画出 16 颗星 | `HtmlDocument.matches` 只支持单 class / 单 id / 元素选择器；`.star.off { display:none }` 被解析成"类名 `star.off`"，永不匹配 | 隐藏一律用单类：`.staroff`、`.hudhidden`；不要把复合选择器当作可用特性 |
| 帧率暴跌 | 背景是**横跨整屏**的 SVG path（640×40 GUI ×超采样 ≈1280×80 纹素 ×16 采样），其形状依赖胶囊宽度 → 宽度一变就重算整幅纹理并上传 | 不再用整幅 SVG：改成"CSS 圆角长条 + CSS 圆角胶囊 + 两个固定 10×10 内圆角补丁"；长条/胶囊/补丁的纹理尺寸恒定，只在窗口尺寸变化时生成一次，稳态零纹理生成 |
| 整个 HUD 不居中（偏左/偏右） | 块级子节点在内核里按**内容宽度**收缩（不是撑满父宽），`.content` 因此不是屏宽，其 `align-items:center` 的基准也就不是屏幕中心 | 每帧把 `.content` 的宽度钉成 `getGuiScaledWidth()`（变化才 `markLayoutDirty`） |
| 内圆角补丁与胶囊边缘脱开、像悬空黑块 | 补丁位置取"上一帧"记录的胶囊坐标（为省一次布局），差一帧即错位 | 渲染前先 `HtmlLayout.apply(view.document(), font, 0, TOP_MARGIN, screenWidth)`，读**本帧** `island.x / island.width` 再 `setPosition` 钉补丁，最后 `view.render(...)` |

## 2. 超采样实现要点

- `HtmlTextures.supersample()`：读取 `Minecraft.getInstance().getWindow().getGuiScale()`，取 ceil 并夹在 1..4；`rasterScale(width,height)` 再叠加两条约束——宽或高 > 256px 时最多 2 倍（大表面一次性光栅化成本），以及 `width*ras / height*ras ≤ MAX_SIDE(4096)`。
- 每个绘制路径（`drawShape` / `drawRim` / `drawGradient` / `drawOuterShadow` / `drawInsetShadow` / `HtmlVector.draw`）都：用纹素尺寸计算 coverage、把 key 加上 `ras`、把 radius/pad/blur/厚度同比乘以 ras、调用 `texture(key, texW, texH, values, ras > 1)` 决定过滤，并用**纹素尺寸**作为 blit 的 uv 采样尺寸（11 参数 blit 的最后两个参数）。
- 缓存键含 `ras`，因此同一形状在不同 GUI scale 下不会互相污染；`HtmlTextures.CACHE` 上限 512，超过即整体释放重建，所以**不要让纹理 key 每帧变化**。

## 3. 顶部 HUD 最终参数（与 `ResourceIslandView` / `html/top_hud.html` 对齐）

- 长条：贴 `y=0`、高 22px、宽 = `clamp(240, 屏宽×0.68, 420)` 并量化到 4px、水平居中、圆角 7px、`#000000f0`。
- 进度条（track）：宽 = `clamp(24, (条宽 − 星区 − 文本与 gap 预留) / 2, 96)`；用户词汇里"背景板"= 黑条、"进度条"= track，改宽度时不要混。
- 资源胶囊：高 18px、圆角 9px、`padding 0 10px`，图标 12px（`war_project:textures/gui/{ammo,fuel}.png`，`<img>` 直读）。
- 连接处：两个 10×10 内圆角补丁（凹弧 path），位置随胶囊实测边缘。
- 显隐（整块同显同隐）：`ResourceClientState.isRunning() && hasTeam() && !SuperbWarfareCompat.isVehicleGunSight()`；无屏幕与 ChatScreen 都画，其它 GUI 隐藏。SBW 炮镜判定（含第三人称按住 `HOLD_ZOOM`）见文档 9995b13c。

## 4. 内核能力边界（改 HUD 前必读）

- 选择器：仅单 class、`#id`、元素名；`:hover`/`:active` 支持；**无**后代/复合选择器。
- 动画：有 `transition`，**无** keyframes/animation，动画只能由 tick 驱动。
- `position:absolute`：相对父节点内容框定位（`applyAbsolute`），自 2026-09-21 起不占文档流。
- 矢量：SVG 子集 `path`(M L H V C S Q T Z)/`circle`/`ellipse`/`rect`/`polygon`/`polyline`；`A` 弧只按弦近似（要真弧请用 C/Q 贝塞尔）；`fill`/`fill-opacity` 每帧从属性读取，**改属性即变色**，无需失效缓存。
- 块级子节点按内容宽收缩；要撑满必须显式给宽度。
- `img`/`svg` 的 11 参数 blit 必须传纹素尺寸，否则只采样到左上角。

## 5. 验证手段（无头、可重复）

- 结构/样式：用 JDK 单文件执行可直接调用 `HtmlDocument.parse` + `doc.byId(...)` 打印节点、`style.display/heights/borderRadius/position` 与 `matches()` 结果（`HtmlNode.texture()` 会拉入 MC 类，探测时避开）。
- 面页行为：`scripts/verify-overlay-page.mjs` 用最小 DOM stub 跑页面脚本并统计文档写入（Chromium 页面用）。
- 运行时：`ResourceIslandView` 启动后约 60 秒内每 100 帧打印 `War Project top HUD: bar=WxH island=WxH nodes=N mine=… foe=…`，可直接回答"条为什么看起来没铺满/没居中"。
- 截图取证：用 JDK 单文件程序 `ImageIO` 扫描指定行的颜色分段，比目测可靠。

## 6. 相关

- HUD 结构与 SBW 炮镜判定交接：文档 `9995b13c-e6eb-426d-bed6-5f2e700c1e8a`
- HTML 内核早期方案（SDF 圆角 + SVG）：文档 `1c71271d-130d-47c6-8393-2faa5b988785`
- 仓库内权威说明：`docs/ARCHITECTURE.md`（顶部 HUD 与 CEF 分工、指令与协议号）
