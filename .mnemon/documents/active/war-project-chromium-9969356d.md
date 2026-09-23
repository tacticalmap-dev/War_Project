---
id: "9969356d-0523-4b86-916f-3e1e1e292bf8"
title: "War Project 增量交接：Chromium 渲染效率优化（脏矩形增量上传/消息泵节流/按需启动）与首帧清除缺陷纠正"
description: "接在 0fc0657c（device_scale_factor 物理分辨率）之后的增量：用户诉求「优化 chromium 核心渲染效率以降低电脑资源占用率」。五条措施——① CefPaintRegions 脏矩形增量拷贝/上传（CEF OnPaint 契约=整帧 buffer+dirtyRects；memCopy 按行 + GL_UNPACK_ROW_LENGTH 直传子矩形；待上传区域累积、失败保留重试）② 消息泵=真实帧率上限（jcef 116 二进制导出表无 set_windowless_frame_rate，故只能靠泵节流：可见 ≤30/s、隐藏 2/s）③ 按需启动（未进世界零浏览器进程，启动 1–2s 落在地形加载期）④ 命令行砍浏览器后台服务 ⑤ 页面签名驱动增量 DOM。并**纠正 b0da5a59 §2 的「纹理先全 0 初始化」**：它会清掉已拷入的首帧导致空白，现改为首帧直接 glTexImage2D + frames==0 不 blit。含 LWJGL 语义实测（memCopy 方向、memAddress 含 position）、离线验证器（scripts/verify-cef-paint-regions.sh、verify-overlay-page.mjs）与量化口径。改 CEF 帧路径/泵/启动时机前先读。"
status: "active"
created_at: "2026-09-20T09:41:17.954Z"
updated_at: "2026-09-20T09:41:17.954Z"
content_hash: "9bacf9a38ee7a969452a65f6674e2c9a19b51c3a25d33c3a9952db5b3c1354f5"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefPaintRegions.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefBootstrap.java"
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "docs/CHROMIUM_BACKEND.md"
  - "scripts/verify-cef-paint-regions.sh"
  - "scripts/verify-overlay-page.mjs"
session_ids:
  - "03ae85ab-df31-44a9-b34a-ad9574df5412"
memory_body_ids:
  []
---

# War Project 增量交接：Chromium 渲染效率优化与首帧缺陷纠正

本文只记录**既有托管文档未覆盖**的增量，接在 `0fc0657c-b1c4-4619-8491-0ca9c54dff57`（CEF 物理分辨率渲染 + 显示规则）之后。
架构与下载/引导管线见 `dfbfbd61-6898-492a-800c-55356cf596b3`；不出帧/黑块根因、崩溃排查与稳定性加固见 `b0da5a59-58c3-4222-b1f6-9a80bec175c1`。

**本文纠正 `b0da5a59` §2 第 1 条的缓解措施**（"纹理创建后用全 0 帧初始化"）——该做法会清掉已经拷入缓冲的 CEF 首帧，见第 7 节。
**现状提示**：CEF 路径早已不是 `dfbfbd61`/`0fc0657c` 描述的 island.html + panel.html 两个视图，而是**单一 `overlay.html` 表面 206×196 GUI 像素**（折叠态是顶部胶囊，展开态向下长成转移面板），`CefWebRenderer` 只用 `WebPages.overlay()`。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；游戏目录 = `D:\mc\.minecraft\versions\totalwar`（CEF 二进制在 `<gameDir>/war_project-cef/`）；部署件 `mods\war_project-1.0.0.jar`。
本轮部署件 sha256 `f80dd6df45cbb746…`（前缀），构建 `BUILD SUCCESSFUL in 34s`（`./gradlew.bat build --no-daemon --offline`），jar 同步后与构建产物 sha256 一致。

---

## 1. 用户诉求（本轮原话）

「优化chromium核心渲染效率以降低电脑资源占用率」

口径：保留真 Chromium 渲染，做法是**减少每帧像素流量**与**减少空载占用**，而不是降低渲染分辨率（分辨率由 `device_scale_factor = Minecraft.getGuiScale()` 决定，`0fc0657c` 已定，**不得回退**）。

## 2. 措施一：脏矩形增量拷贝与上传（`CefPaintRegions` + `CefOsrView`）

证据（不是推测）：`src/cefApi/java/org/cef/handler/CefRenderHandler.java` 的 `onPaint` 文档明确 `@param buffer Pixel buffer for the whole window`，同时提供 `dirtyRects`（"Array of dirty regions"）。即：**每次回调都给整帧，且告诉应用哪些区域变了**。

旧实现忽略 `dirtyRects`：每帧 `ByteBuffer.duplicate()+put` 整帧 memcpy，并整纹理 `glTexSubImage2D`。GUI scale 4 时表面 824×784 → **每帧 2.58 MiB**；而界面静止时一次变化往往只有几十~几百像素。

新实现：

- `CefPaintRegions`（新类，`client/cef/`）：零分配整数矩形集合。规则——裁剪到帧内、相交则合并包围盒（膨胀超过 `(a+b)×2` 时**不合并**以免吞进大片未变化像素）、数量超 `MAX_REGIONS=16` 或面积超帧的 `0.5` 时**退化为整帧**；`setFrameSize` 变化即 `markFull()`；`covers()` 供自检断言覆盖性。
- `CefOsrView` 维护两个集合（均在 `frameLock` 内）：`copyNow`=本次 `onPaint` 要拷进缓冲的区域，`toUpload`=缓冲已持有但纹理还没见过的区域。`onPaint` 里 `copyNow.markFull()` 条件为 `!uploaded`（首帧/尺寸变化/纹理未初始化），否则按 `rects` 累积；随后 `copyInto(...)`、`toUpload.mergeWith(copyNow)`、`dirty.set(true)`。
- 拷贝用 `MemoryUtil.memCopy` 绝对地址按行搬运；**待上传区域是累积的**，所以渲染线程忙时丢弃的中间帧不会造成过期像素（这是脏区方案能成立的前提）。
- 上传：`RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, pixelWidth)` + `frame.position(regionY*stride + regionX*4)` 后对子矩形 `glTexSubImage2D(x,y,w,h)`，**无逐行拷贝、无临时缓冲、零分配**；`!uploaded || toUpload.isFull()` 时走整帧 `glTexImage2D/glTexSubImage2D`。
- 健壮性：上传若抛错，`finally` **不清空** `toUpload` 与 `dirty`，下一次 `draw` 用同一批脏区重试（避免纹理永久不完整）。

量化（`scripts/verify-cef-paint-regions.sh` 实跑）：

```
merge overhead: dirty=14.7 Mpx tracked=16.6 Mpx (x1.13)
caret-only frame: 1 region(s), 28 px (a full frame is 646016 px)
bookkeeping cost: 200000 clear+2 add cycles in 18.8 ms (0.094 us each)
ALL 14 CHECKS PASSED
```

即合并只多付 13% 像素，而"只有光标闪烁"的帧从 646 016 px 降到 **28 px**；自检含 200 轮随机脏区流量的覆盖性不变量（每个上报过的脏矩形合并后仍被覆盖 —— 这条不变量曾抓出一个真实缺陷：合并时移除元素会把正在合并的矩形挪位，已修）。

## 3. 措施二：消息泵频率就是帧率上限（唯一可行的限帧点）

- Chromium 只在 `N_DoMessageLoopWork()` 被调用时推进合成。旧逻辑：每 tick 20 次/秒，**且**面板打开时每个渲染帧再泵一次（60–240 fps 的游戏里白白驱动软件合成）。
- 现在 tick 与绘制（`renderIsland`/`renderPanel`）共用同一个 `pumpIfDue(visible)`：可见时 `1000/cefMaxFrameRate`（默认 33 ms），隐藏时 500 ms（`IDLE_PUMP_INTERVAL_NANOS`）。
- **为什么不用 host API 限帧**：在 `<gameDir>/war_project-cef/windows_amd64/jcef.dll` 的 PE 导出表里检索 `SetWindowlessFrameRate` / `set_windowless_frame_rate` / `WindowlessFrameRate` 均 **0 命中**（该 dll 有 335 个 `Java_org_cef_*` 导出），因此无法新增 JNI 方法调用 `CefBrowserHost::SetWindowlessFrameRate`（libcef 的 C API 走结构体函数指针、不导出符号）。泵节流是唯一途径。

## 4. 措施三：按需启动（`cefLazyStart`，默认 true）

`CefWebRenderer.tick()` 只在 `Minecraft.getInstance().player != null`（已进入世界）时才 `startOnClientThread()`。停在主菜单 / 未进世界的会话**没有任何 `jcef_helper.exe`**，省掉 ~150 MiB 常驻内存与后台线程；这期间界面由自研 `html/` 内核绘制（`WebRendererService.active()` 未就绪即返回 null，机制见 `dfbfbd61`）。

选"进世界"而不是"岛第一次要显示"的理由：CEF 启动占主线程约 1–2 s（日志实测 `War Project CEF startup` 15:45:50.480 → `CEF ready` 15:45:52.253），落在进世界的地形加载期可被掩盖；若推迟到开局后第一次弹岛，同一笔开销就变成肉眼可见的卡顿。

注意：后台**下载/解压**（119 MiB）仍在客户端启动后进行（`prepare()` 守护线程），与"启动浏览器进程"是两件事。

## 5. 措施四：命令行砍掉浏览器后台（`CefBootstrap.COMMAND_LINE`）

在既有软件合成三件套（`--disable-gpu --disable-gpu-compositing --disable-gpu-vsync`，`b0da5a59` 实测仍出帧）之外新增：

```
--disable-background-networking --disable-component-update --disable-breakpad
--disable-client-side-phishing-detection --disable-domain-reliability
--disable-default-apps --disable-extensions --disable-sync
--no-first-run --no-default-browser-check --renderer-process-limit=1
--disable-features=Translate,MediaRouter,OptimizationHints,BackForwardCache,CalculateNativeWinOcclusion
--js-flags=--max-old-space-size=64
```

只砍"浏览器后台"（网络服务、组件更新、崩溃上报、扩展、同步、备用 renderer）与 V8 堆上限，不触碰渲染路径。

## 6. 措施五：页面签名驱动的增量 DOM（`overlay.html`）

- `apply()` 先比较岛的四段文本，未变则**不写 DOM、也不调用 `fitBar()`**（后者会 `getBoundingClientRect` + 强制重排 + 钉像素宽度，是页面里最贵的操作）。
- 玩家列表与面板控件拆成 `updateRoster()` / `updateControls()`，各自按签名缓存：**面板收起时根本不重建列表**；`openPanel` 用 `renderForced()` 保证首次填充不被签名短路。
- 离线验证：`node scripts/verify-overlay-page.mjs`（用最小 DOM 桩加载页面真实 `<script>`，统计 textContent/innerHTML/layout 读取/appendChild 次数）→ **ALL 17 CHECKS PASSED**，含"重复快照 0 次写 DOM、0 次强制布局"与"收起时忽略列表变化"。

## 7. 首帧缺陷纠正（取代 `b0da5a59` §2 第 1 条的缓解措施）

`b0da5a59` 当时为规避"未写内容的 GL 纹理被驱动读回成不透明黑块"，做法是纹理创建后**立刻用全 0 帧初始化**。但那条清屏写的是 **`frame` 缓冲本身**，而此刻 CEF 首帧早已拷入该缓冲 —— 首帧被一并清掉，上传的是全透明缓冲；此后页面若长时间不再出帧，界面就停在空白（这也解释了历史现象"有时岛要等一会才有内容"）。

现已删除 `clearTexture()`（连同那次 2.58 MiB 清零与全纹理上传），改为：

- 纹理创建后不做占位上传（`ensureTexture()` 只建纹理与注册 `CefTexture`）；
- 首次上传必然走 `glTexImage2D` 且携带完整帧（`!uploaded` ⇒ `copyNow.markFull()`）；
- `draw()` 保留 `frames == 0 → return`，并在 `upload()` 之后增加 `if (!uploaded) return;` —— 未初始化的纹理在任何情况下都不会被 `blit`。

## 8. LWJGL 语义实测（关键假设不靠记忆）

代码依赖"绝对地址拷贝"与"position 参与指针计算"，均用 `lwjgl-3.3.1.jar` + natives 实跑核对（`C:\Users\flowingsun\.gradle\caches\forge_gradle\maven_downloader\org\lwjgl\...`）：

- `MemoryUtil.memCopy(long src, long dst, long bytes)` → **第一个参数是源**（实测 target 被写入、source 不变）。
- `MemoryUtil.memAddress(ByteBuffer)` 与 `memAddressSafe(ByteBuffer)` **都包含 position 偏移**（position 20 → 地址 +20；`buffer.remaining()` = 44）。
- `org/lwjgl/opengl/GL11.class` 引用 `memAddressSafe` 与 `remaining`，且存在 `(IIIIIIIILjava/nio/ByteBuffer;)V` 重载 → `glTexSubImage2D` 的指针确实基于 position，配合 `GL_UNPACK_ROW_LENGTH` 的子矩形上传成立。

## 9. 新增配置与回退口径（`Config.java`）

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `cefMaxFrameRate` | `30`（1–240） | 表面帧率上限＝泵频率；调低按比例省 CPU |
| `cefLazyStart` | `true` | 只在进入世界后启动 Chromium；`false` 恢复"客户端启动即拉起" |

其余同前：`webRenderer=native` 强制自研内核（完全排除 Chromium）、`cefMirror`、`webDiagnostics`。
`webRenderScale` 在当前代码中**已不存在**（旧文档残留），Chromium 分辨率只看 `Minecraft.getGuiScale()`。

## 10. 量化与回归检查（`webDiagnostics=true`）

每 60 秒一行，字段与期望：

```
War Project CEF diagnostics: frames=+N (N/s) uploads=+N uploaded=… KiB copied=… KiB
  pumps=+N (N/s) pumpAvg=… us uploadAvg=… us visible=… open=…
```

- 空闲（页面静止）：`frames=0`（实测：启动后 30 秒内只有首帧，日志 `frames=1` 稳定不变 —— CEF 无脏区就不出帧）。
- 仅数字变化/光标闪烁：`uploaded`/`copied` 应为 KiB 级，而非每帧 2.5 MiB。
- 可见时 `pumps ≈ cefMaxFrameRate`；打开背包 / SBW 炮镜 / 未开局时 `pumps ≈ 2`、`visible=false`。
- 离线回归：`bash scripts/verify-cef-paint-regions.sh`（14 项）、`node scripts/verify-overlay-page.mjs`（17 项）。

## 11. 改动文件与踩坑提示

- 新：`client/cef/CefPaintRegions.java`、`scripts/verify-cef-paint-regions.sh`、`scripts/CefPaintRegionsCheck.java`、`scripts/verify-overlay-page.mjs`。
- 改：`client/cef/CefOsrView.java`（帧路径重写）、`client/cef/CefWebRenderer.java`（可见性/节流/按需启动/诊断）、`client/cef/CefBootstrap.java`（命令行 + 泵统计）、`Config.java`、`assets/war_project/web/overlay.html`。
- 文档同步：`docs/CHROMIUM_BACKEND.md`（新增 3.5 节，并在配置表与 3.1 修正陈旧描述）、`docs/ARCHITECTURE.md`（Chromium 后端那一行的机制与配置项）。
- 陷阱：① 脏区方案下**任何**"丢弃待上传区域"的写法都会让纹理永久残留过期像素，唯一安全的丢弃点是"整帧重传"；② `CefPaintRegions` 的合并必须在移除元素时保住被合并矩形的索引（自检的覆盖性不变量专门盯这条）；③ 不要把 `GL_UNPACK_ROW_LENGTH` 留在非 0 状态（上传后必须归零，MC 自身也用这个状态）；④ 限帧只能靠泵节流，jcef 116 没有 host 侧帧率 API；⑤ 降低 `device_scale_factor` 不是省资源手段，会破坏 `0fc0657c` 已定调的发糊判据。
