---
id: "72ed23f4-82d1-4b8e-a724-27d157712ce6"
title: "War Project 顶部 HUD 融合连接处几何定稿（无缝化）与相邻形状抗锯齿缝"
description: "顶部 HUD 条与资源岛连接处的最终几何（条 r=11 单体圆角、20×11「沙漏」凹角补片、岛顶盖缝条、全 HUD 纯黑不透明）与根本教训「相邻 SDF 形状必须重叠、不能相切」，含像素级诊断手法与 Python 轮廓模拟验证法。取代 9995b13c 中「条＝14×22 圆角端盖 SVG」「交界＝9×9 凹角补片」的旧描述。改 top_hud.html 或任何拼接形状的 HTML 内核 UI 前读此文档。"
status: "active"
created_at: "2026-09-22T04:36:43.299Z"
updated_at: "2026-09-22T04:36:43.299Z"
content_hash: "342c52667e22385881fa9e73355812b1359b4726ada6c0a507821b42d9c895dd"
source_paths:
  - "src/main/resources/assets/war_project/html/top_hud.html"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlTextures.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlVector.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlLayout.java"
session_ids:
  - "03a89610-2861-43e1-b865-c222f3ce7e75"
memory_body_ids:
  []
---

# War Project 顶部 HUD 融合连接处几何定稿（无缝化）

接在 `9995b13c-e6eb-426d-bed6-5f2e700c1e8a`（融合顶部 HUD 与 SBW 炮镜判定）之后。

**本文取代**该文档中关于「条＝纯矩形主体 + 两端固定 14×22 圆角端盖 SVG」「交界＝两个 9×9 凹角补丁」的描述：分离端盖已全部删除，补片几何重做。该文档其余部分（显隐规则、SBW 炮镜判定、CEF 表面只承载转移面板、内核 CSS 单 class 限制）仍然有效；SDF/矢量渲染机制见 `1c71271d`，内核陷阱清单见 `f4af89b7`。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge）；部署：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 症状与根因

**症状**：把 HUD 区域截图放大后，条的下缘、岛的顶缘、凹角补片三者之间各有一条**亮色细缝**；整个 HUD 看起来像三块黑拼起来，而不是一体的「灵动岛」。用户多轮以「圆角方向不对 / 圆角位置不对」表述，实际指的都是这条缝与补片生硬的斜切感。

**根因**：`HtmlTextures` 的圆角是解析 SDF 的**逐像素覆盖率**（1px AA 斜坡），`HtmlVector` 的矢量形状是 4×4 超采样覆盖率。两个形状**紧贴（相切）**时，交界像素被两边各覆盖一部分，alpha 合成后仍留有透明 → 缝。原配色 `#000000f0`（alpha 94%）让缝更明显。

**结论**：只有让形状**重叠**，覆盖率叠加到接近 1，缝才消失。这是所有拼接式 HTML 内核 UI 的通用约束，不是本 HUD 的特例。

## 2. 定稿几何（GUI 像素，`#hud` 高 40）

| 部件 | 规格 |
| --- | --- |
| 条 `#topbar` | **单个**圆角矩形：宽 = 屏宽×0.68（clamp 240–420、量化 4px），高 22，`padding:0 12px`，`border-radius:11px`（= 半高 → 半圆端头），背景 `#000000`。不再有任何端盖元素 |
| 岛 `#island` | 高 18、`border-radius:9px`、`padding:0 12px`、背景 `#000000`；flex column 紧随条之下（y=22） |
| 左补片 `#filletL` | SVG 盒 20×11，置于 `(island.x − 9, 20)` |
| 右补片 `#filletR` | SVG 盒 20×11，置于 `(island.x + island.width − 11, 20)` |
| 盖缝条 `#seam` | `(island.x + 9, 20)`，尺寸 `(island.width − 18, 3)`；只覆盖岛顶边的**直线段** |

补片 path（左，20×11）：

```
M 0 0 L 0 2 C 4.97 2 9 6.03 9 11 L 10 11 C 10 6.58 13.58 3 18 3 L 18 0 Z
```

右补片为 `x → 20 − x` 镜像：`M 20 0 L 20 2 C 15.03 2 11 6.03 11 11 L 10 11 C 10 6.58 6.42 3 2 3 L 2 0 Z`（三次贝塞尔控制点取 kappa = 0.5523）。

几何关系：

- 外侧边 = `r = 9` 的凹弧，圆心在 `(island.left, bar.bottom + 9)`，与条底边相切于 `(island.left − 9, 22)`，与岛侧边相切于 `(island.left, 31)`；后者正是岛的 `r = 9` 圆角的起点 —— 两弧**外切**，形成连续过渡。
- 内侧边 = `r = 8`、圆心 `(18, 11)` 的弧，**比岛的圆角小 1px**，因此补片向岛内叠进 1px（最小重叠 1px，切点处靠 `L 10 11` 的水平段保证）。
- 补片顶部从 y=20 起，向条内爬 2px；左右两端各超出岛边缘 9px。
- 凹弧半径受岛高限制：`r ≤ island.height / 2`，当前 9 已是上限，想要更缓的过渡只能加高岛。

## 3. 可复用的四条教训

1. **相邻形状必须重叠，不能相切**（本条最关键）：任何两个 SDF/AA 形状共边都会留缝；左右叠 1px、上下叠 2px 实测足够。
2. **重叠必须配不透明色**：半透明形状重叠处会变深，形成"更深的边"，比缝更显眼。全 HUD 统一 `#000000` —— 94% 与 100% 的观感差异肉眼分辨不出，但叠色问题彻底消失。
3. **盖缝条要避开圆角**：横跨岛顶边的补条只覆盖直线段（左右各缩 9px），否则会把两端圆角填成直角。
4. **不要用整张宽 SVG 当背景**：`HtmlVector.rasterize` 的代价是 `width × height × 16` 次非零环绕测试（宽 480×40、ras=2 时约 122 万格 × 每条边），几何一变就要重算，正是此前「帧率暴跌」的来源。宽元素继续用 CSS 圆角/SDF；SVG 只做**固定尺寸的小件**（补片、星、图标），几何稳定时才有缓存收益。

## 4. 离线验证手法（无需启动游戏）

- **轮廓模拟**：用 Python/PIL 按同一组参数把条（`rounded_rectangle` r=11）、岛（r=9）、补片（三次贝塞尔采样成多边形后 `polygon` 填充）合成到 480×40 位图上，逐行打印连通 run。可直接看出越界、空洞、凹弧是否收敛到岛边缘。本轮实测：y=22 横跨 `183→297`，之后逐行收敛到岛左边缘 `192`，与岛圆角外切、无外凸。
- **截图取证**：对截图取阈值（`max(r,g,b) < 70` 视作 HUD 深色），逐行打印深色 run，即可定位缝 / 错位出现在哪一行；用户红笔标注可用 `R > 140 且 G、B < 90` 筛出并做连通域分析，还原其标注的走向（比目测猜意图可靠得多）。
- 构建：`.\gradlew.bat build --no-daemon --offline`，产物同步到部署路径（daemon 模式会卡死）。

## 5. 诊断日志

`ResourceIslandView.render` 每 100 帧输出一次（累计 ≤600 帧）：

```
War Project top HUD: bar=WxH at (x,y) island=WxH at (x,y) joints=[filletL.x, filletL.y, filletR.x, filletR.y] screen=W
```
