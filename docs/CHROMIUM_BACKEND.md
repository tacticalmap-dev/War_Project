# Chromium 渲染后端（自建 CEF 集成）

资源灵动岛与资源转移面板可以由**真正的 Chromium**（CEF 116.0.5845.190）渲染，用标准 HTML/CSS/JS 写界面。
该后端**不依赖任何第三方 mod**：`org.cef` 绑定以源码形式随本 mod 编译，CEF 二进制按需下载到游戏目录。

## 1. 运行时发生了什么

1. 客户端启动（`FMLClientSetupEvent`）→ `WebRendererService.init()` 创建后端；此时界面仍由自研 HTML 内核绘制。
2. 后台线程检查 `<gameDir>/war_project-cef/windows_amd64/`：
   - 已存在 → 直接进入下一步；
   - 不存在 → 从 `https://mcef-download.cinemamod.com/java-cef-builds/<commit>/windows_amd64.tar.gz` 下载（约 119 MiB）→ 校验 sha256（拿不到校验文件时退化为长度校验）→ 自写 tar.gz 解压。
   - 支持**离线预置**：把 `windows_amd64.tar.gz` 放进 `<gameDir>/war_project-cef/` 即跳过下载。
3. **按需启动**（`cefLazyStart=true`，默认）：只在玩家**进入世界后**，客户端线程才启动 CEF（系统属性 `jcef.path`、`windowless_rendering_enabled=true`、透明背景）；在那之前**不存在任何 Chromium 进程**，界面由自研内核绘制。
4. 创建唯一的离屏页面 `overlay.html`（136×110 GUI 像素，`data:` URL 内联加载），并**按可见性节流**地调用 `N_DoMessageLoopWork()` 驱动消息泵（见 3.5）：界面可见时不超过 `cefMaxFrameRate`（默认 30）次/秒，隐藏时 2 次/秒。Chromium 只在消息泵运行时出帧，所以这个频率就是表面真实帧率上限。
5. 像素（**预乘 BGRA**）在 CEF 线程按**脏矩形**拷进 direct buffer，渲染线程同样只上传脏矩形：每个矩形先**按行打包进一个紧凑的临时缓冲**，再 `glTexSubImage2D(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)`（`GL_UNPACK_ROW_LENGTH` 必须保持 0，理由与崩溃证据见 3.5），最后通过 `GuiGraphics.blit` 绘制（混合模式 `ONE, ONE_MINUS_SRC_ALPHA`）。
6. 页面与 Java 通过 `cefQuery`（`CefMessageRouter`）通信：页面 → Java `ready/transfer/cancel/close`；Java → 页面 `wp.apply/openPanel/closePanel/result`。

## 2. 配置（`config/war_project-common.toml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `webRenderer` | `auto` | `auto` 有 Chromium 就用、否则自研内核；`native` 强制自研内核；`chromium` 请求 Chromium（仍会在失败时回退） |
| `cefMirror` | 空 | CEF 二进制下载镜像；留空用公开构建站 |
| `cefLazyStart` | `true` | 只在玩家进入世界后才启动 Chromium（主菜单/未进世界不拉起任何浏览器进程）；`false` 恢复"客户端启动即拉起" |
| `cefMaxFrameRate` | `30` | Chromium 表面的帧率上限（1–240）；消息泵按此节流，调低即按比例省 CPU |
| `cefUseGpu` | `false` | `false`＝Chromium 用 CPU（Skia + SwiftShader）光栅化/合成；`true`＝放开 GPU（ANGLE D3D11）。本机实测 `false` 更省 CPU，理由与数据见 3.6 |
| `webDiagnostics` | `false` | 每 60 秒打印一次后端诊断（帧数、上传/拷贝像素量、泵频率与耗时） |

注：旧版本本文档列出的 `webRenderScale` 在当前代码中已不存在（没有任何引用）；Chromium 路径的分辨率由 `CefScreenInfo.device_scale_factor = Minecraft.getGuiScale()` 决定。

## 3. 如何确认 Chromium 生效

- 日志出现 `War Project CEF ready: Chromium 116.0.5845.190 (CEF 116.0, jcef 116.0.27.1)`
- 日志出现 `War Project CEF page online: Mozilla/5.0 ... Chrome/116.0.5845.190 ...`（页面回传的 `navigator.userAgent`，是"真的在跑 Chromium"的直接证据）
- 灵动岛有自研内核做不到的效果：玻璃质感、呼吸光环动画、真阴影

## 3.1 稳定性与 GPU（2026-09-20 加固）

排查一次"进世界后进程级退出"（无 Java 崩溃报告、无 hs_err、Windows 事件日志无 Error、latest.log 在渲染线程被硬截断）后做了这些加固：

- **CEF 不再使用 GPU 合成**：CEF 默认会拉起自己的 GPU 进程，与 Minecraft（本机装有 Embeddium + Oculus 光影）争抢显卡。默认以软件合成启动：
  `--disable-gpu --disable-gpu-compositing --disable-gpu-vsync`。页面只有几十像素，软件合成完全够用。
  > **2026-09-22 更正**：这一条在很长时间里**并没有真正生效**。开关只传给了 `CefApp.startup()`，而 Windows 分支的 `startup()` 完全忽略参数（只加载 DLL），真正决定命令行的是 `CefApp.getInstance(args, settings)` 里的 `args`（由 `CefAppHandlerAdapter.onBeforeCommandLineProcessing` 转发）；当时的代码给 `getInstance` 传的是空数组，于是 `--disable-gpu`、`--renderer-process-limit`、`--js-flags` 全部被静默丢弃，Chromium 一直跑在**硬件 GPU** 上。现已修复并加入自证日志（见 3.6）。
- **像素缓冲的线程安全**：CEF 在它自己的线程写帧、渲染线程上传与释放，两者用锁互斥；`onPaint` 里也严格校验容量，避免"释放后写入"。
- **驱动越界读防护**：`glTexImage2D/glTexSubImage2D` 会按 `宽×高×4` 读取内存（不看 buffer limit），所以上传前强制校验缓冲区容量与 limit，不足则跳过该帧。
- **关闭游戏时不 `dispose()` 原生 CEF 应用**：只停止消息泵，把进程回收交给系统，避免与 Minecraft 自身关停流程竞争导致原生崩溃。
- **页面不要放常驻 CSS 动画**：CEF 每多出一帧就多一次拷贝与纹理上传，与光影同时抢 GPU 会掉帧。灵动岛只在数值变化时重绘；即便将来要加动画，也受 `cefMaxFrameRate`（默认 30）兜底（见 3.5）。
- **随时可完全关闭**：配置 `webRenderer = native`。

## 3.2 当前界面规格（2026-09-22 定稿）

> **职责已经分开**：顶部 HUD（战局条 + 资源胶囊）由自研 HTML 内核绘制（`client/ResourceIslandView` + `html/top_hud.html`），Chromium 只负责转移面板（`overlay.html`），表面下移紧贴岛底边。

| 项 | 值 |
| --- | --- |
| 顶部 HUD（内核） | 战局条：宽 = 屏宽 × 0.54（clamp 200–360，量化 4px）、高 14、圆角 7，两端分数框；其下（无间隙）接资源胶囊：高 16、圆角 7、宽贴合内容、居中；图标用原版 `textures/gui/ammo.png` / `fuel.png` 显示 12px |
| 转移面板（Chromium） | CEF 表面 **136×110**（GUI 像素）；面板实际宽度 = `clamp(岛宽, 132, 136)`（岛宽 = 页面折叠态量出的胶囊宽度，经 `op:'size'` 回报给 Java，见 3.3）；圆角 8；正文 6px / 标题 7px，文案全中文 |
| 显示规则 | 无 GUI 时显示顶部 HUD；**聊天栏打开时照常显示**（岛是转移面板的入口）；**其它 GUI/Screen 打开时隐藏**；SBW 载具炮镜时同样隐藏（岛与战局条同一条规则） |
| 打开面板 | 聊天栏打开时，**左键**点击岛上的资源图标（`ResourceIslandView.iconAt` 命中）→ 面板在岛的正下方展开 |
| 渲染分辨率 | 表面按 GUI 尺寸布局 + `CefScreenInfo.device_scale_factor = Minecraft.getGuiScale()`，CEF 实际输出 `GUI 尺寸 × GUI scale` 的物理像素帧（GUI scale 4 时岛为 624×72），MC 再缩回 GUI 尺寸显示；**禁止 1 GUI 像素 = 1 页面像素**（那会被 MC 放大 N 倍而发糊，实测已验证） |
| 自研 html 内核（回退） | 规格与上表一致 |

## 3.3 面板＝灵动岛向下展开（2026-09-22 定稿）

- **一个 CEF 表面**：`overlay.html`，画布 136×110（GUI 像素），只有一个视图实例。它**只是面板**：折叠态的顶栏由内核的 `html/top_hud.html` 在它正上方绘制，页面在展开时把自己的 bar 淡出（`.slab.open .bar { opacity: 0 }`），否则屏幕上会出现两条一模一样的资源胶囊。
- **以岛为基础**：面板宽度取岛的实测宽度（下限 132px —— 队员名字与数值行的最小可读宽度；上限 136px = 表面宽度），由 `openPanel({width:N})` 传入并写成 CSS 变量 `--pw`；`CefWebRenderer.panelWidth()` 用同一条公式算命中区域，所以热区与画面始终一致。
- **紧贴岛底**：`surfaceY = TOP_MARGIN(0) + TOPHUD_HEIGHT(30) + TOPHUD_GAP(1)`，即面板顶边距岛底边 1 GUI 像素，读起来是同一块界面往下长出来。
- **量宽条（`<div class="bar">`）**：页面里的这条胶囊**常驻 `opacity: 0`，只负责量宽**。它用 `max-content` 量出真实宽度再钉成像素（浏览器无法在 max-content 与 px 之间插值），所以宽度随数字位数变化，同时保留动画能力。
- **生长动画**：展开时黑块从「与岛等宽、高度 0、贴住岛底边」开始（`openPanel` 先 `transition:none` 钉 `width=岛宽 / height=0`），再过渡到面板尺寸，内容延迟 140ms 淡入；收起时宽度回到岛宽、高度收到 0 并同时淡出。黑块任何一帧都只有纯色 —— **绝不能让它显示量宽条**：它是页面自己的快照（比内核岛慢一拍），展开动画开始时若画出来，屏幕上会闪出第二条岛（用户实测：内核显示 47，闪出的那条显示 46）。
- **打开方式**：**左键**点击资源图标（CEF 与自研内核两条路径一致）。
- **收起动画**：`closePanel` 会先把展开宽度钉成像素、强制重排，再移除 `.open` 并把宽度显式动画到折叠宽度 —— 否则宽度会从 px 直接跳到 `max-content`（瞬变）。实测收缩过程：412×392 → 316×200 → 248×65 → 236×39 → 最终 234×36（物理像素，约 190ms）。
- **展开态**：左键点岛上的资源图标 → **同一块黑板向下延伸扩大**到 136×110（`width/height/border-radius` 过渡 220ms，`cubic-bezier(.2,.8,.25,1)`；面板内容 `opacity` 淡入，延迟 70ms）。自上而下为标题行（`转移燃料`＋`持有 N · +x/分`）、队员列表（每行 `名字` ＋ `弹 N · 燃 N`，离线行半透明且不可选，超出可滚动）、数量输入行（`数量` ＋ 输入框 ＋ `上限 N`）、确认/取消按钮、结果行；正文 6px / 标题 7px。面板高度按内容固定：内容实测总高 108px = 表面高 110 − 边框 2，正好铺满不溢出。
- **位置固定**：屏幕顶部居中、紧贴岛底边（见上），**不随鼠标移动**，方便鼠标直接移到队友行上点击。
- **收起条件**：ESC、取消、成功 0.9 秒后自动收起、点击面板外、离开聊天界面（`screen == null` 时 tick 自动收起）。
- **重复点击**：面板已经打开时再点资源图标会被**吞掉**（`ResourceTransferController.onMousePressed` 先看 `web.isPanelOpen()`），既不重播生长动画，也不清空已经输入的数量；点面板外仍然收起。
- **打开/关闭的纹理一致性（2026-09-23 定稿，治闪烁）**：CEF 只在 `panelOpen` 时被绘制，所以关闭期间**纹理根本不更新**，而 Chromium 的脏区是相对**它自己上一帧**报的 —— 两边一旦不一致，重新打开时没被更新的像素会留着上一场的画面（表现为闪烁／残影）。三条措施：
  1. 打开时 `CefOsrView.skipUntilNextFrame()` 丢掉 `uploaded` 标记（下一次上传必然是**整帧**）并在这之前拒绝绘制，旧画面不会先闪一下；
  2. 打开后 `forceFullFrames(600ms)` 内强制整帧上传 —— 生长动画期间几乎每个像素都在变，此时相信脏区列表就是在赌它不漏报；
  3. 关闭后 `SETTLE_NANOS = 400ms` 内仍按可见帧率泵消息循环，让 220ms 的收起动画在屏幕外播完，页面不会冻结在半途（上一次冻结的半成品就是下次打开时残影的来源）。

## 3.4 转移限制（冷却与单次上限）

| 配置项 | 默认 | 作用 |
| --- | --- | --- |
| `transferCooldownSeconds` | `120` | 两次成功转移之间的最短间隔（秒），0 表示关闭 |
| `transferMaxAmmoPerRequest` | `50` | 单次转移弹药上限 |
| `transferMaxFuelPerRequest` | `25` | 单次转移燃料上限 |

- 规则只在 `ResourceApi.transfer` 一处实现，`/warproject resource transfer` 与面板走同一套校验，指令绕不过去。
- 冷却记录**只在内存**（`ResourceApi.LAST_TRANSFER_AT`），重启即清空，与游戏阶段同样的处理方式。
- 面板"上限"= min(自己余额, 单次上限, 999 − 对方存量)；提交成功后按钮显示 `冷却 120s` 并每秒倒数；服务端拒绝消息在页面上译为中文（`冷却中，还需 N 秒。` / `单次最多 N。`）。

## 3.5 渲染效率与资源占用（2026-09-20 优化）

目标：在保留"真 Chromium 渲染"的前提下，把它的 CPU 与内存占用压到接近 0（空闲时）并让每次界面更新只付出与变化面积成正比的代价。四条措施与它们的可验证依据：

### (1) 增量上传：只拷贝/上传脏矩形（`CefPaintRegions` + `CefOsrView`）

- CEF 的 `onPaint` 每次都给出**整帧**位图，并通过 `dirtyRects` 指出真正变化的区域（`CefRenderHandler.onPaint` 的文档原文：*"buffer Pixel buffer for the whole window"*）。
- 旧实现忽略 `dirtyRects`，每帧 `memcpy` 整帧（GUI scale 4 时 824×784×4 ≈ **2.58 MiB**）并 `glTexSubImage2D` 整个纹理；而界面静止时一次变化的往往只有几十到几百像素（数字变化、输入框光标闪烁）。
- 现在 `CefPaintRegions` 跟踪脏矩形（裁剪到帧内、相交则合并、膨胀过大则不合并、面积超过半帧或数量超过 16 个则退化为整帧），`onPaint` 用 `MemoryUtil.memCopy` 按行拷贝这些区域，`upload()` 再把每个区域**按行打包进 `region` 临时缓冲**（首次分配后复用）并 `glTexSubImage2D`，`GL_UNPACK_ROW_LENGTH` **恒为 0**。
- **崩溃记录（2026-09-23，已修）**：此前为了少一次逐行拷贝，`upload()` 用 `GL_UNPACK_ROW_LENGTH = pixelWidth` + `frame.position(regionY*stride + regionX*4)` 直接从整帧 buffer 上传子矩形。驱动是按**整行行距**读取的（读 `regionH` 个完整行，而不是「前 `regionH-1` 行整行 + 最后一行 `regionW` 像素」），所以只要脏矩形贴到右/下边缘，驱动就会读到 direct buffer 末尾之外 → 在 `nvoglv64.dll` 里 `EXCEPTION_ACCESS_VIOLATION`（崩溃转储 `hs_err_pid21068.log`：Render thread → `CefOsrView.upload` → `glTexSubImage2D`；当时帧 `272×220`、stride `1088`，寄存器 `R12=1856`、`R15=2176`）。现在改成紧凑打包：驱动可读字节数 = `w*h*4` = 缓冲容量，**由本方法自己保证，不再依赖驱动的读取边界**。
- 配套护栏：`regionsFitFrame()` 会先校验每个区域都落在 `pixelWidth × pixelHeight` 内，越界就退化为整帧上传（整帧路径不依赖任何行距）；`textureId == 0` 时直接拒绝上传。
- 正确性关键：**待上传区域是累积的**（`toUpload.mergeWith(copyNow)`），渲染线程忙时的脏区不会被丢弃，因此纹理永远不会残留过期像素；尺寸变化 / 首帧 / 纹理未初始化时强制整帧。上传抛错时也不清空待上传区域与 dirty 标记，下一次 `draw` 会用同一批脏区重试。
- 顺带修掉一处首帧缺陷：旧实现为了绕开"未初始化纹理读回是黑块"，在 `ensureTexture()` 中把 **frame 缓冲清零后上传**——但那时 CEF 的首帧已经拷进该缓冲，会被一并清掉（此后页面若长时间不出帧，界面就停在空白）。现在首帧直接 `glTexImage2D` 上传完整帧，且 `frames == 0` 时绝不 `blit`，因此既不需要占位清屏，也不会丢首帧（`upload()` 失败时 `draw()` 直接跳过绘制）。
- 自检（不依赖 Minecraft）：`bash scripts/verify-cef-paint-regions.sh` —— 14 项检查，含 200 轮随机脏区流量的**覆盖性不变量**（每个上报过的脏矩形合并后仍被覆盖）。当前结果：

  ```
  merge overhead: dirty=14.7 Mpx tracked=16.6 Mpx (x1.13)
  caret-only frame: 1 region(s), 28 px (a full frame is 646016 px)
  bookkeeping cost: 200000 clear+2 add cycles in 18.1 ms (0.091 us each)
  ALL 14 CHECKS PASSED
  ```

  即：合并带来的额外像素只有 13%，而"只有光标闪烁"的帧从 646 016 px 降到 28 px。

### (2) 消息泵节流：可见 30 次/秒、隐藏 2 次/秒

- Chromium 只在 `N_DoMessageLoopWork()` 被调用时推进合成，所以**泵频率就是帧率上限**。旧实现是"每 tick 20 次/秒 + 面板打开时每渲染帧再泵一次"，在 60–240 fps 的游戏里白白驱动软件合成。
- 现在 tick 与绘制（`renderIsland`/`renderPanel`）共用同一个节流器（`pumpIfDue`）：可见时 `1000 / cefMaxFrameRate`（默认 33 ms），隐藏时 500 ms。`--disable-gpu-vsync` 之下这是唯一有效的限帧手段（jcef 116 的绑定**没有** `set_windowless_frame_rate`，已用二进制导出表核对：`jcef.dll` 中不存在该 JNI 符号）。

### (3) 按需启动：没进世界就没有浏览器进程

- `cefLazyStart=true`（默认）时，`CefWebRenderer.tick()` 只在玩家**已进入世界**（`Minecraft.getInstance().player != null`）后才启动 CEF：停在主菜单 / 只开启动器的会话完全没有 `jcef_helper.exe`，也没有那 ~150 MiB 常驻内存与后台线程。
- 放在"进世界"而不是"岛第一次要显示"是因为 CEF 启动要占用主线程约 1–2 秒（`CefBootstrap.start` 里等待 `INITIALIZED` 的窗口实测 15:45:50.480 → 15:45:52.253）：进世界时游戏本身还在加载地形，这个一次性开销被掩盖；若推迟到开局后第一次显示，同一笔开销就变成肉眼可见的卡顿。
- 界面在这段时间由自研内核绘制（`statusText()` 会显示准备进度）。需要"客户端启动即常驻"的老行为时设 `cefLazyStart=false`。

### (4) 启动参数：关掉浏览器里用不到的后台服务

`CefBootstrap.COMMAND_LINE` 在原有软件合成三件套之外新增：

```
--disable-background-networking --disable-component-update --disable-breakpad
--disable-client-side-phishing-detection --disable-domain-reliability
--disable-default-apps --disable-extensions --disable-sync
--no-first-run --no-default-browser-check --renderer-process-limit=1
--disable-features=Translate,MediaRouter,OptimizationHints,BackForwardCache,CalculateNativeWinOcclusion
--js-flags=--max-old-space-size=64
```

这些开关去掉的是"浏览器后台"（网络服务、组件更新、崩溃上报、扩展、同步、多余的 renderer 进程）与 V8 堆上限；不改变页面渲染路径，因此不影响已经在 3.1 验证过的软件合成结论。

### (5) 页面侧：不做无谓的重排与重绘（`overlay.html`）

- `apply()` 现在先比较岛的四段文本，文本没变就**不写 DOM、也不调用 `fitBar()`**（后者会 `getBoundingClientRect` + 强制重排 + 动画宽度钉值，是页面里最贵的操作）。
- 队员列表（`roster`）与面板控件拆成 `updateRoster()` / `updateControls()`，各自按签名缓存：**面板收起时根本不重建玩家列表**，展开时也只在内容或选中项变化时才重建。
- `openPanel` 使用 `renderForced()`，避免因为签名恰好相同而漏掉面板刚打开时的首次填充。

### 如何量化（回归检查清单）

1. 打开 `config/war_project-common.toml` 里的 `webDiagnostics = true`，进世界并让界面显示（in-game 有队伍）。
2. 等待 `War Project CEF diagnostics: ...` 行，它每 60 秒打印一次：

   | 字段 | 期望 |
   | --- | --- |
   | `frames=+N (N/s)` | 空闲时应为 0（页面静止时 CEF 不出帧，实测启动 30 秒内只有首帧 `frames=1`） |
   | `uploads=+N uploaded=… KiB copied=… KiB` | 空闲/仅数字变化时应是 KiB 级，而不是每帧 2.5 MiB |
   | `pumps=+N (N/s)` | 可见时 ≈ `cefMaxFrameRate`；隐藏（打开背包、炮镜、无 GUI 未开局）时 ≈ 2 |
   | `pumpAvg=… us uploadAvg=… us` | 泵单次耗时应远小于 1 ms |
3. 面板打开 → 界面可见时 `visible=true`；打开背包或进入 SBW 炮镜 → `visible=false`、`pumps` 降到 2/s。
4. 不启动游戏只想验证脏区逻辑：`bash scripts/verify-cef-paint-regions.sh`。
5. 不启动游戏验证页面"无变化就不动 DOM"：`node scripts/verify-overlay-page.mjs`（17 项检查：重复快照零写入、收起时重建玩家列表、变化只写一个文本）。
6. 想完全排除 Chromium 影响做对照：`webRenderer = native`（自研内核）。

## 3.6 GPU 还是 CPU：实测矩阵（2026-09-22）

问题："能不能让 Chromium 跑在 GPU 上、别占 CPU？" 结论分三层，全部有实测支撑。

### (1) 这份绑定没有"GPU 纹理直达"这条路

- `src/cefApi/java/org/cef/handler/CefRenderHandler.java` 只有 `onPaint(…, ByteBuffer buffer, …)`（CPU 位图），**没有** `onAcceleratedPaint`；`jcef.dll` 的 335 个 JNI 导出里也搜不到任何 `Accelerated` / `SharedTexture` 符号（0 处）。CEF 的 accelerated OSR（共享纹理）在这套 java-cef 绑定里不存在。
- 因此离屏渲染**必然**以 CPU 位图收尾：无论 Chromium 内部用 GPU 还是 CPU 光栅化，最后都要把整帧像素交回游戏进程再上传成 GL 纹理。所谓"跑在 GPU 上就不用 CPU"在 OSR 下不成立。

### (2) 现状其实一直在 GPU 上（因为开关没生效）

用独立探针（`scripts/cef-probe/`，不启动 Minecraft，直接驱动同一套 jcef 二进制）跑三组，每组 8 秒、表面尺寸取当时的线上值 176×150 GUI 像素（现已缩到 136×110）、`device_scale_factor=4`（等于本机 MC 的实际分辨率）、页面持续动画：

| 组 | 启动方式 | 页面报告的 WebGL 后端 | 帧率 | 8 秒回传位图 | CPU（单核占比） |
| --- | --- | --- | --- | --- | --- |
| A | 修复前的写法（开关只给 `startup`，`getInstance` 传空数组） | `ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)` | 30.4 | 577 MiB | **14.1%** |
| B | 开关传给 `getInstance` + `--disable-gpu*` | `ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)` | 29.9 | 567 MiB | **9.4%** |
| C | 开关传给 `getInstance` + 不禁用 GPU | `ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)` | 30.3 | 575 MiB | 13.3% |

- A 组就是修复前的真实行为：硬件 GPU（Intel UHD 630）。所以"让它用 GPU"这件事**早已发生**，只是没人知道。
- B 组证明开关一旦真的送进 CEF，后端确实会从 Intel 核显切换成 SwiftShader。
- **反直觉但可复现的一条**：在这台机器上软件模式比硬件 GPU 模式更省 CPU（9.4% vs 14.1%，约 −33%）。原因就是 (1)：GPU 模式下多付了一次 GPU→CPU 回读，还要多养一个与 Minecraft 抢显卡的 GPU 进程；页面只有 704×600，省下的光栅化根本不抵这笔开销。
- 三组的 `copiedMiB` 都在 570–577 MiB/8s，说明 CPU 大头与后端无关，而是"每帧 ~1.7 MiB 的整帧位图回传 + 上传"——这正是 3.5 的脏区优化要砍掉的东西（探针刻意按整帧统计，模拟未优化路径）。

### (3) 现在的做法

- `CefBootstrap.switches()` 动态拼命令行，并**必须**交给 `CefApp.getInstance(...)`；`cefUseGpu=false`（默认）加入软件三件套，`true` 则不加。
- 启动日志会打印开关全集与模式：`War Project CEF ready: … (gpu=false)` / `War Project CEF switches: --disable-gpu --disable-gpu-compositing …`。
- 页面加载完成后会注入一段 WebGL 探测并回传，日志出现 `War Project CEF backend: ANGLE (Google, Vulkan 1.3.0 (SwiftShader…))` 就说明软件模式真的生效；出现 `ANGLE (… Direct3D11 vs_5_0 …)` 则是硬件 GPU。`webDiagnostics` 的周期行也带 `backend=…`。
- 想自己复测：`bash scripts/cef-probe/run-cef-mode-matrix.sh [秒数] [缩放]`（编译 `org.cef` + 探针，依次跑三组，输出与上表同格式）。第二次运行（6 秒窗口、同样 scale=4）得到 A 16.1% / B 8.1% / C 19.5%——绝对值随机器负载浮动，但"软件模式更省 CPU"的方向稳定复现。

## 4. 回退与故障排查

任何一步失败都会**回退自研内核**并打印原因，界面不会消失：

| 现象 | 原因与处理 |
| --- | --- |
| 日志 `could not prepare ... (UnknownHostException)` | 无网/镜像不可达：联网重试，或离线预置 tar.gz |
| 日志 `checksum mismatch` | 下载损坏：删除 `<gameDir>/war_project-cef/*.tar.gz` 后重试 |
| 日志 `CefApp.startup` / `UnsatisfiedLinkError` | 安全软件拦截 `jcef.dll`/`jcef_helper.exe`/`libcef.dll`：把这些文件加入白名单后重启 |
| 日志 `no Chromium Embedded Framework build` | 非 Windows x86_64（当前只接了这个平台），自动使用自研内核 |
| 想强制关掉 | 配置 `webRenderer = native` |

清理：删除 `<gameDir>/war_project-cef/` 即可完全重置（下次启动重新下载/解压）。

## 5. 构建说明

- `org.cef` 源码位于 `src/cefApi/java`，由 `build.gradle` 加入主源码集；纯 Java、无 Minecraft 依赖，因此 `--offline` 构建照常可用。
- jar 审计：`unzip -l build/libs/war_project-1.0.0.jar | grep -c 'org/cef/.*\.class'` ≈ 163；`grep -ciE 'libcef|jcef\.dll|icudtl'` = 0（**不含**二进制）。
- 页面资源：`src/main/resources/assets/war_project/web/{island,panel}.html`（单文件自包含）。
- 脏区逻辑自检（不需要 Minecraft、Gradle 或 CEF 二进制）：`bash scripts/verify-cef-paint-regions.sh`，输出见 3.5。
- 页面增量 DOM 自检（只需 Node，不需要浏览器）：`node scripts/verify-overlay-page.mjs`。
