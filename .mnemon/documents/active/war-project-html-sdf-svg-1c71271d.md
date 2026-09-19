---
id: "1c71271d-130d-47c6-8393-2faa5b988785"
title: "War Project 增量交接：HTML 内核抗锯齿与矢量渲染（SDF 圆角 + SVG），取代逐行圆角方案"
description: "接在 437db98f（个人资源 + HTML 内核首版 + 转移面板）之后的本轮增量：圆角改为解析 SDF 逐像素覆盖率（1px AA）、新增 HtmlTextures（外形/描边高光环/渐变光泽/多重与 inset 阴影/纹理缓存）与 HtmlVector（SVG 子集 4×4 超采样非零环绕光栅化）、Css 新增 border 简写与 box-shadow 列表与 linear-gradient；并明确取代旧「逐行 fill 内缩圆角」「三角扇自建顶点」「shadowSize/shadowColor 单值阴影」；含立体感视觉规范、验证手法与未决的 MCEF A/B 决策。改 HTML 内核/灵动岛样式前先读。"
status: "active"
created_at: "2026-09-19T18:53:15.148Z"
updated_at: "2026-09-19T18:53:15.148Z"
content_hash: "e116994562de313d79a53bb9d682bd9f1af69c8fbeb76b9f2596b11dd13763d8"
source_paths:
  - "src/main/java/com/flowingsun/war_project/html/HtmlTextures.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlVector.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlRenderer.java"
  - "src/main/java/com/flowingsun/war_project/html/Css.java"
  - "src/main/java/com/flowingsun/war_project/html/HtmlLayout.java"
  - "src/main/resources/assets/war_project/html/resource_island.html"
  - "src/main/resources/assets/war_project/html/transfer_panel.html"
  - "src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "da6ffcee-6856-4e01-804b-d0a96db42d80"
memory_body_ids:
  []
---

# War Project 增量交接：HTML 内核抗锯齿与矢量渲染（取代逐行圆角）

本文只记录既有托管文档**未覆盖**的增量，接在 `437db98f-3347-4ce2-8050-91259140564c`（个人资源落地 + HTML 内核首版 + 转移面板）之后；更早脉络：`04344603`（SBW 兼容 + 个人化定调）、`b75b521b`（HUD/灵动岛/999）、`3ea0c3c0`（双资源）。

**本文明确取代** `437db98f` 中关于渲染方式的旧描述：

- 旧：圆角 = `HtmlRenderer` 逐行 `fill` 内缩（另一条「三角扇自建顶点」平滑路径）。→ 已删除，改为解析 SDF 覆盖率纹理。
- 旧：阴影 = `Css.shadowSize` / `Css.shadowColor` 单值。→ 字段已移除，改为 `Css.shadows` 列表（`Css.Shadow`）。
- 旧：图标 = `assets/war_project/textures/gui/ammo.png` / `fuel.png` 的 `img` 栅格绘制。→ 灵动岛已改内联 SVG 矢量，PNG 仅作回退保留。
- 仍有效：`HtmlDocument.refreshStyles()` + `classRevision` 机制、11 参数 `blit`、`EditBox` 复用、模板 `assets/war_project/html/*.html` + 内置常量回退、网络协议 `"6"`、资源/转移语义（本文不改动）。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 本轮用户诉求与交付

用户原话（两条）：①「能不能将样式做成像苹果灵动岛一样，使边界没有锯齿，有立体感、同时尝试将图片素材转换成矢量图」；②「引入完整的 html 渲染方式，使其可以像渲染网页一样渲染出真正的曲线」。

交付口径（**②按「自研内核具备真曲线能力」实现，未引入 MCEF/JCEF**，理由见第 7 节未决项）：

- 边界无锯齿：圆角矩形改用圆角矩形的解析 SDF 逐像素覆盖率，1px AA 渐变，不再是整像素阶梯。
- 立体感：多层外投影 + 1px 描边高光环（顶部亮/底部暗）+ 顶部白色渐变光泽。
- 矢量图标：按原 PNG 逐像素测绘重绘为 SVG（弹药三发弹体、燃料油桶），任意尺寸锐利。
- 面板/按钮/输入框/行 hover 同步享受新的圆角、描边与阴影。

## 2. 渲染架构与绘制顺序

新增两个类（均在 `html/` 包内，仍属全局客户端内核，**不注册进 `ModuleRegistry`**）：

- `HtmlTextures`：盒装饰光栅化 + 生成纹理缓存。
- `HtmlVector`：SVG 子集解析与光栅化。

`HtmlRenderer.drawBackground` 的路径：先试 `HtmlTextures.paint(...)`，返回 `false` 才落回旧的逐行矩形绘制。`paint` 的绘制顺序（即 CSS 常规层序）：

1. 外阴影（`inset` 为 false 的 `Css.Shadow`，逐条，按声明顺序压在盒下方）
2. `background-color` 主体外形（无背景但有渐变时用渐变首色兜底，保证不透明底）
3. `background-image: linear-gradient(上,下)` 光泽
4. `border` 描边高光环
5. `inset` 内阴影

`HtmlTextures.paint` 的提前返回条件：`width/height <= 0`、或「无圆角且无阴影且无渐变且无边框」——即普通方块元素仍走原生 `fill`，零开销。

核心数学（`HtmlTextures.signedDistance`）：圆角矩形 SDF（负值在内部），半径 clamp 到 `min(w,h)/2`；覆盖率取 `clamp01(0.5 - sdf)`，即 1px 宽的抗锯齿带（逐像素中心采样，误差约 1/8 像素，视觉足够）。各层是该 SDF 的派生：

- 描边环：`外层覆盖率 − 内缩 thickness 后的覆盖率`，再乘「顶亮底暗」唇线因子 `lip = 0.35 + 0.65 * (1 - y/(h-1))`。
- 外阴影：在膨胀 pad 的画布上取 `clamp01(0.5 - sdf/ramp)`，`ramp = max(1, blur*0.85)`，`pad = ceil(blur/2)+1+max(|ox|,|oy|)`，纹理绘制在 `(x-pad+ox, y-pad+oy)`。
- 内阴影：`自身覆盖率 × clamp01(1 - 深度/ramp)`，`ramp = max(1, blur*1.2)`。
- 渐变光泽：**纹理只烘焙强度斜坡（顶部 1.0）**，颜色亮度不对纹理做除法；量级由 `setColor` 的 alpha 承载——否则会双重乘 alpha，光泽淡到看不见（本轮踩过的实际 bug）。

渐变的顶部/底部 alpha 比：`bottomRatio = topAlpha<=0 ? 0 : bottomAlpha/topAlpha`。

## 3. 关键设计决策与理由

**用「白色 RGB + 变化 alpha」的纹理，绘制时 `setColor` 着色。** 三条收益：

1. 规避 `NativeImage.setPixelRGBA` 的通道序不确定性——RGB 恒为 `0xFFFFFF`，alpha 在高低两种解释下都落在 bit24–31，所以颜色不可能错（无需靠实验猜 ABGR/ARGB）。
2. 明确规避此前「自建顶点写 Tesselator，与 `GuiGraphics` 共享同一个 `BufferBuilder`，导致未 flush 的 GUI 批次被丢弃 → 黑色背景板消失」这一类事故：现在全部走标准 `blit`，GUI 批次永不被打断。
3. 一张纹理可按 `setColor` 复用出任意颜色（同一个圆角外形可作阴影/主体/高光）。

**圆角只用一套 SDF，不用 9-slice。** 纹理按元素实际像素尺寸生成（灵动岛宽度随数字变化，尺寸种类有限），省掉九宫格拉伸带来的边缘模糊与 API 复杂度。

**纹理缓存**：`LinkedHashMap`（access-order），键为形状指纹字符串（形如 `s|112x18|9.0`、`o|…|blur|ox|oy`、`v|…|viewBox|d 指纹`），值 `ResourceLocation`，上限 512 条；超限整表 `TextureManager.release` 后重建。加载名用递增计数器 `war_project:html/tex_N`（避免哈希碰撞与重注册旧名）。命中缓存时**先查表再算像素**，避免每帧重算覆盖率数组。

**回退与自愈**：纹理上传一旦抛异常，`unavailable = true` 永久锁存并打 WARN，此后走逐行矩形 + 近似外投影（`box-shadow` 只有位移与模糊时用偏移 `fill` 代偿）。界面不会消失。调试开关：`-Dwarproject.html.corners=stepped`（旧的强制逐行开关语义已随三角扇路径删除，仅剩常量可参考）。

## 4. SVG 子集能力与边界（`HtmlVector`）

- 支持元素：`<svg viewBox="...">` 内 `path`、`circle`、`ellipse`、`rect`（含 `rx`）、`polygon`、`polyline`；属性 `fill`、`fill-opacity`。
- `path` 命令：`M L H V C S Q T Z`（绝对/相对），贝塞尔按控制多边形长度自适应细分（4–24 段）；**`A` 只消费参数、以弦近似**；无 `Z` 的开放子路径按闭合填充。
- 光栅化：4×4 超采样 + **非零环绕**（反向子路径即孔洞）；输出仍为白色覆盖率纹理，颜色在绘制时着色。每个 `svg` 节点的子形状逐条成纹理并着色叠加。
- 布局语义：`svg` 像 `img` 一样是**叶子盒**（`HtmlLayout.arrange` 对其提前返回，子形状不参与盒模型），尺寸取 CSS `width/height`（缺省 16）。
- 未支持：`stroke`、SVG 内渐变、`<g>`/`transform` 属性、`use`。
- 图标几何来源：`ammo.png` / `fuel.png`（128×128）经像素分类、连通域 PCA 与径向剖线测绘——弹体轴 44.5°、体长≈21、宽≈9.5、尖端≈±13，三个弹体中心约 (59,75.9)/(68.3,66.7)/(77.5,57.6)；油桶体 47..82 × 48..87、把手在上、深色 X 十字。重绘时保留同一 128 单位 viewBox。

## 5. CSS 支持面变化（`Css`）

新增：

- `border: <width>px solid? <color>` 简写（`none`/`0` 关闭）；`border-width` / `border-color` / `border-radius` 维持。
- `box-shadow`：逗号分隔的**列表**（顶层逗号切分，括号内逗号不切），每项 `[inset] <ox> <oy> [blur] [spread] <color>`；spread 折进 blur，避免退化成硬拷贝。
- `background-image: linear-gradient(<色A>, <色B>)`：取其中第 1 与最后一个可解析颜色，做垂直双段光泽；纹理只烘焙强度斜坡。
- `:hover` / `:active` 覆盖函数 `HtmlNode.overlay` 同步支持 `shadows` 列表与渐变。

已移除（勿再引用）：`Css.shadowSize`、`Css.shadowColor`。

## 6. 视觉规范（用户已确认的外观）

灵动岛（`assets/war_project/html/resource_island.html`，内置回退常量在 `ResourceIslandView.FALLBACK`）：

| 属性 | 取值 |
| --- | --- |
| 底色 | `#05070af2`（近黑、轻微透明） |
| 光泽 | `background-image: linear-gradient(#ffffff1f, #ffffff00)` |
| 描边 | `border: 1px solid #ffffff26`（经唇线因子后顶部更亮） |
| 投影 | `box-shadow: 0 2px 5px #000000a6, 0 8px 18px #00000059`（双层悬浮） |
| 圆角/内边距 | `border-radius:999px`（= 高/2 胶囊）、`padding:1px 8px` → 高 **18px**，宽随文本（`0` 时约 112px） |
| 排列 | flex row、`gap:4px`、图标 14×14、燃料图标 `margin-left:10px` 作分组间隔 |
| 文本 | 存量 `#ffffff`，速率 `#7ce38b`（`+每60s`） |
| 结构 | `#bar`（视口宽，`justify-content:center`）→ `#island` → 两个 `<svg id="ammo-icon|fuel-icon">` + 四个 `span` |
| 图标 | 内联 SVG：黑底盘 `#00000099` + 彩色环（弹药 `#c8634a`、燃料 `#8fae62`）+ 顶部高亮弧 + 弹体/油桶 |
| 触发 | `running && hasTeam && 非 SBW 载具第一人称`；仅 `minecraft.screen == null` 时由 HUD overlay 调，Screen 打开时由 `ScreenEvent.Render.Post` 调 |

转移面板同步升级：`#131822f5` + 顶部光泽 + `border:1px solid #ffffff26` + `border-radius:12px` + 双层投影；行/按钮/输入框圆角 5–7px。

## 7. 状态、验证与未决项

**构建/部署**：`./gradlew build --offline --console=plain -Dnet.minecraftforge.gradle.check.certs=false`（本机 daemon 会卡死，需 `--no-daemon` 或单次进程）→ 同步后比对 sha256；本轮最终 jar sha 前缀 `5d02f0b9`（构建与部署一致）。**尚未在游戏内验收**（需重启客户端；转移链路还需第二账号）。

**离线视觉预检手法（可复用）**：用 Python/PIL 1:1 复刻 `HtmlTextures` 的 SDF/覆盖率/唇线/渐变/阴影公式，并在 `D:\mc\.minecraft\versions\totalwar\mods` 之外合成「天空背景 + 灵动岛」预览图，可在不启动游戏的前提下发现双重 alpha、圆角半径、阴影范围等错误。图标同理：先用多边形在 PIL 里渲染 128px 与 14px 两档对照原 PNG，再落 HTML。

**原生签名核对手法（沿用）**：`javap` 打 `~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.10_mapped_official_1.20.1/`（非 sources）jar。本轮确认：`com.mojang.blaze3d.platform.NativeImage(Format,int,int,boolean)` / `setPixelRGBA(int,int,int)`；`DynamicTexture(NativeImage)` **构造即在渲染线程上传**（非渲染线程则 `recordRenderCall`）；`TextureManager.register(ResourceLocation, AbstractTexture)` / `release(ResourceLocation)`；`GuiGraphics.blit(ResourceLocation,int,int,int,int,float,float,int,int,int,int)`（11 参数，绘制尺寸与采样尺寸分离）。

**未决决策（等用户回答）**：完整浏览器内核（MCEF + 内嵌 Chromium/JCEF）的 A/B 选择——用户第 ② 条诉求存在两种解读，当前按「自研内核对齐浏览器行为（SDF 圆角 + SVG 矢量光栅化，与浏览器处理 `border-radius`/SVG 等价）」实现；若选 MCEF 需下载约 100–200MB 原生库、仅客户端、与现有零依赖内核并存适配。**用户尚未答复**，不要把它当成已定方案，也不要重复提出。

**HTML 内核仍未实现**：`overflow:hidden` 裁剪、文本换行/省略号、`justify-content: flex-end`/`space-between`、`@keyframes`、非 px 单位、`stroke`、`<g>`/SVG `transform`。

## 8. 相关文件

- 新增：`src/main/java/com/flowingsun/war_project/html/HtmlTextures.java`、`html/HtmlVector.java`
- 改写：`html/HtmlRenderer.java`（装饰走纹理路径 + 逐行回退）
- 扩展：`html/Css.java`（`Shadow`、shadows 列表、border 简写、linear-gradient）、`html/HtmlNode.java`（overlay 支持新字段）、`html/HtmlDocument.java`（SVG 标签加入 `VOID_TAGS`）、`html/HtmlLayout.java`（`svg` 视为叶子盒）
- 模板：`src/main/resources/assets/war_project/html/resource_island.html`（内联矢量图标 + 苹果质感，3.6KB）、`.../transfer_panel.html`
- 客户端：`src/main/java/com/flowingsun/war_project/client/ResourceIslandView.java`（内置回退同步）
- 文档：`docs/ARCHITECTURE.md`（内核行与 HUD 行已更新）
