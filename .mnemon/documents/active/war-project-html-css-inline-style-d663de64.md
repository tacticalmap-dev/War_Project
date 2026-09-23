---
id: "d663de64-877e-4a01-8da6-3703f6e450c2"
title: "War Project HTML 内核样式求值语义与元素定位原则（CSS 覆盖 inline style / 首帧定位）"
description: "顶部 HUD 内核级语义：refreshStyles 用 CSS 覆盖 inline style（Java 设的 width 被静默吃掉）、justify-content 只有 center、随动元素须做成目标的 absolute 子元素 + CSS 常量定位；含 HUD 现状勘误（补片 20×9 挂岛内、seam 取消、轨道等比缩放）。Java 驱动内核元素尺寸/位置前先读。"
status: "active"
created_at: "2026-09-22T05:30:54.072Z"
updated_at: "2026-09-22T05:30:54.072Z"
content_hash: "c32bf1ae19bdee5823187276a396ce9d41b8d305edd4a2335790eba4e91e1a09"
source_paths:
  - "src/main/java/com/flowingsun/war_project/html/HtmlDocument.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlLayout.java"
  - "src/main/java/com/flowingsun/war_project/html/Css.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "src/main/resources/assets/war_project/html/top_hud.html"
session_ids:
  - "3f4092be-13b4-4812-a3a7-880e19740a57"
memory_body_ids:
  []
---

# War Project HTML 内核：样式求值语义与元素定位原则

**适用**：任何「Java 侧驱动 HTML 内核元素」的界面（`client/ResourceIslandView`、转移面板等）。2026-09-22 调试顶部 HUD 时确定。

**关系**：本文记录的是**内核语义**，不是 HUD 几何。顶部 HUD 连接处的几何定稿与「相邻形状必须重叠」的根因见 `72ed23f4`；内核渲染质量与陷阱清单（超采样、absolute 占流、单 class 选择器、大纹理性能）见 `f4af89b7`；SDF/SVG 圆角机制见 `1c71271d`。本文**勘误** `72ed23f4` 中「补片置于 `(island.x − 9, 20)`、由 Java 定位」「存在 `#seam` 盖缝条」的描述——该做法已被下文第 3、4 节取代。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge）；部署：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 陷阱一：CSS 规则覆盖 inline style（与浏览器相反）

`HtmlDocument.refreshStyles()` 的求值顺序是：

1. `node.style = node.inlineStyle.copy()` —— 先铺一份 inline 声明；
2. 再对每条匹配的 CSS 规则执行 `applyDeclarations(node.style, rule.body())` —— **CSS 后应用，因此覆盖 inline**。

浏览器里 inline 优先级高于样式表，内核里恰好相反。后果：**凡 CSS 里写过同属性的元素，Java 通过 `inlineStyle` / `style` 设置的该属性会被静默吃掉**，没有任何日志或异常。

已发生的事故（同一根因，两次）：

| 元素 | CSS 声明 | Java 想设 | 实际结果 |
| --- | --- | --- | --- |
| `#mineTrack` / `#foeTrack` | `.track { width:120px }` | `setWidth(..., half)` | 轨道恒 120px，填充最多 `half`（96）→ 右侧固定 24px 空档；用户报「进度条在满进度时不应该有留白」 |
| `#starN` | `.star { width:12px; height:12px }` | `setSize(..., starSize(count))` | 星星尺寸的自适应（12/10/8）全部失效 |

**规则**：Java 要驱动的 CSS 属性，就绝不能在页面的 `<style>` 里给同一元素写该属性。要在页面里留默认值时，改用不同元素层级或 `min-width` 之类的非冲突属性。改动后自检：`grep` 出 Java 侧所有 `setWidth/setSize/setBox/inlineStyle.*=` 的目标 id，逐个确认其 CSS 规则里没有同属性。

## 2. 陷阱二：flex 只实现了 `align-items` 与 `justify-content:center`

`HtmlLayout.arrange()` 只在 `css.justify.equals("center")` 时做居中位移，`flex-end` / `space-between` 等不会被处理（写了也不报错，只是按 start 排）。`#mineTrack { justify-content:flex-end }` 因此长期无效，填充实际靠左。

## 3. 定位原则：不要用 Java 每帧去「追」元素的坐标

**症状**：用户报「初始屏会出现错位」——连接处的凹角补片出现在**页面左上角**，而不是岛的左右两端。像素取证：盒 20×11、位置约 `x=4, y=20`（GUI），即 `left` 停留在 CSS 默认值 `0`。

**根因**：当时的写法是「先 `HtmlLayout.apply(...)`，读 `island.x/width`，再 `setPosition(filletL, island.x − 9, ...)`」，并包在 `if (island.width > 0)` 里。首帧/布局尚未稳定时读到宽度为 0，整段定位被跳过，补片就留在页面原点——而且 `HtmlViewHost.render()` 内部还会再布局一次，手工追踪的坐标天生滞后一帧。

**正确做法**：让随动元素成为**目标元素的绝对定位子元素**，位置写成 **CSS 常量**，由布局引擎每帧随父级一起重排。内核的 `applyAbsolute` 就是相对**父节点内容盒**解析的，且 `Css.parsePx` 接受负数，所以「向左伸出 9px」可以直接写：

```css
.island   { padding: 0 12px; }        /* 内容盒左侧 = island.x + 12 */
.filletl  { position: absolute; left: -21px; top: 0px; width: 20px; height: 9px; }   /* → island.x − 9 */
.filletr  { position: absolute; right: -21px; top: 0px; width: 20px; height: 9px; }  /* → island.x + w − 11 */
```

配套要点：

- 子元素用 `position:absolute` 后不占流（`arrange` 已跳过其流消耗），也不会影响父级的 `measureWidth`；
- 左/右补片各自只写 `left` 或 `right`，**不要写 `left:auto`** 之类内核不解析的值；两个补片用两个不同的 class（`.filletl` / `.filletr`），避免依赖多 class 叠加；
- 这样 `ResourceIslandView` 里所有 `setPosition` / `setBox` / `setWidth(轨道)` 的追踪代码都可以删掉，`HtmlLayout` 也不必再被显式调用。

## 4. 顶部 HUD 现状勘误（取代 `72ed23f4` 的补片/盖缝条描述）

- 条 `#topbar`：单个圆角矩形，`border-radius:11px`（半高 → 半圆端头），纯黑 `#000000`，无任何端盖元素。
- 岛 `#island`：高 18、`border-radius:9px`、`padding:0 12px`，**顶边正好等于条底边（y=22）**——这一点是几何前提：凹弧的切点必须落在条的下边界上。
- 两处交界补片：盒 **20×9**，作为**岛的 absolute 子元素**，`left:-21px` / `right:-21px`、`top:0`（见第 3 节）。path（左）：
  `M 0 0 C 4.97 0 9 4.03 9 9 L 10 9 C 10 4.58 13.58 1 18 1 L 18 0 Z`
  外侧 = r=9 凹弧（圆心在岛左边缘外侧 9px、条底边下方 9px，与条底边相切于 `island.x − 9`，与岛 r=9 圆角外切）；内侧 = 半径 8 的弧，比岛圆角**小 1px**，使补片向岛内叠进 1px 以消除抗锯齿缝。
- **`#seam` 盖缝条已删除**：岛与条不再需要额外盖缝（条的行 21 与岛的行 22 都是满覆盖行）。
- 两条资源轨道宽 = **条宽 × 0.56 ÷ 2**（比例而非死区间），并夹一次 `2×轨道 + 星标区 + 两个分数 + 4 个 gap + 内边距 24 ≤ 条宽`；满进度时 `fill` 宽度精确等于轨道宽，因此零留白、且背景板变小时轨道等比缩小、永不溢出。

## 5. 验证手法

- **轮廓模拟（不启游戏）**：Python/PIL 按同一组参数合成 480×40 位图（条 `rounded_rectangle(r=11)`、岛 `r=9`、补片三次贝塞尔采样成多边形后 `polygon` 填充），逐行打印连通 run，可直接看出越界、空洞、凹弧是否收敛到岛边缘。本轮实测 y=22 横跨 `183→297`（= island.left−9 … island.right+9），之后逐行收敛到岛左边缘 192。
- **截图取证**：对截图取阈值（`max(r,g,b) < 70` 视作 HUD 深色），按行打印深色 run，能把「补片跑到原点」「条是否有圆角」「轨道与填充各多宽」全部量化——比目测可靠，用户报「对齐/错位」类问题时优先用它。

## 6. 相关文档

`72ed23f4`（连接处几何 + 相邻形状抗锯齿缝）、`f4af89b7`（内核陷阱与渲染质量）、`1c71271d`（SDF/SVG 圆角机制）。
