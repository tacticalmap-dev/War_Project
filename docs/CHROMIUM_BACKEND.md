# Chromium 渲染后端（自建 CEF 集成）

资源灵动岛与资源转移面板可以由**真正的 Chromium**（CEF 116.0.5845.190）渲染，用标准 HTML/CSS/JS 写界面。
该后端**不依赖任何第三方 mod**：`org.cef` 绑定以源码形式随本 mod 编译，CEF 二进制按需下载到游戏目录。

## 1. 运行时发生了什么

1. 客户端启动（`FMLClientSetupEvent`）→ `WebRendererService.init()` 创建后端；此时界面仍由自研 HTML 内核绘制。
2. 后台线程检查 `<gameDir>/war_project-cef/windows_amd64/`：
   - 已存在 → 直接进入下一步；
   - 不存在 → 从 `https://mcef-download.cinemamod.com/java-cef-builds/<commit>/windows_amd64.tar.gz` 下载（约 119 MiB）→ 校验 sha256（拿不到校验文件时退化为长度校验）→ 自写 tar.gz 解压。
   - 支持**离线预置**：把 `windows_amd64.tar.gz` 放进 `<gameDir>/war_project-cef/` 即跳过下载。
3. 主线程启动 CEF（系统属性 `jcef.path`、`windowless_rendering_enabled=true`、透明背景），每 tick 调用 `N_DoMessageLoopWork()` 驱动消息泵。
4. 创建两个离屏页面：灵动岛（264×44）与转移面板（320×420），页面以 `data:` URL 内联加载。
5. 像素（**预乘 BGRA**）在 CEF 线程被拷进 direct buffer，渲染线程用 `glTexImage2D/glTexSubImage2D(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)` 上传，再通过 `GuiGraphics.blit` 绘制（混合模式 `ONE, ONE_MINUS_SRC_ALPHA`）。
6. 页面与 Java 通过 `cefQuery`（`CefMessageRouter`）通信：页面 → Java `ready/transfer/cancel/close`；Java → 页面 `wp.apply/openPanel/closePanel/result`。

## 2. 配置（`config/war_project-common.toml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `webRenderer` | `auto` | `auto` 有 Chromium 就用、否则自研内核；`native` 强制自研内核；`chromium` 请求 Chromium（仍会在失败时回退） |
| `webRenderScale` | `2.0` | 页面内部渲染倍率（文字更锐，代价是像素更多） |
| `cefMirror` | 空 | CEF 二进制下载镜像；留空用公开构建站 |
| `webDiagnostics` | `false` | 每 60 秒打印一次后端诊断 |

## 3. 如何确认 Chromium 生效

- 日志出现 `War Project CEF ready: Chromium 116.0.5845.190 (CEF 116.0, jcef 116.0.27.1)`
- 日志出现 `War Project CEF page online: Mozilla/5.0 ... Chrome/116.0.5845.190 ...`（页面回传的 `navigator.userAgent`，是"真的在跑 Chromium"的直接证据）
- 灵动岛有自研内核做不到的效果：玻璃质感、呼吸光环动画、真阴影

## 3.1 稳定性与 GPU（2026-09-20 加固）

排查一次"进世界后进程级退出"（无 Java 崩溃报告、无 hs_err、Windows 事件日志无 Error、latest.log 在渲染线程被硬截断）后做了这些加固：

- **CEF 不再使用 GPU 合成**：CEF 默认会拉起自己的 GPU 进程，与 Minecraft（本机装有 Embeddium + Oculus 光影）争抢显卡。现在默认以软件合成启动：
  `--disable-gpu --disable-gpu-compositing --disable-gpu-vsync`（已在离线 Chromium 中实测：照常出帧）。页面只有几十像素，软件合成完全够用。
- **像素缓冲的线程安全**：CEF 在它自己的线程写帧、渲染线程上传与释放，两者用锁互斥；`onPaint` 里也严格校验容量，避免"释放后写入"。
- **驱动越界读防护**：`glTexImage2D/glTexSubImage2D` 会按 `宽×高×4` 读取内存（不看 buffer limit），所以上传前强制校验缓冲区容量与 limit，不足则跳过该帧。
- **关闭游戏时不 `dispose()` 原生 CEF 应用**：只停止消息泵，把进程回收交给系统，避免与 Minecraft 自身关停流程竞争导致原生崩溃。
- **页面不要放常驻 CSS 动画**：CEF 每帧出帧就会每帧上传纹理，与光影同时抢 GPU 会掉帧。灵动岛只在数值变化时重绘。
- **随时可完全关闭**：配置 `webRenderer = native`。

## 3.2 当前界面规格（2026-09-20 定稿）

| 项 | 值 |
| --- | --- |
| 灵动岛 | 纯黑胶囊 `#000000f0`，**无外阴影**；CEF 画布 156×18，内容高约 16；图标用原版 `textures/gui/ammo.png` / `fuel.png` 显示 12px（Java 原样 base64 内联，不做任何预缩放）；字号 9px；速率只写 `+xx` |
| 转移面板 | CEF 画布 208×168，圆角 8，`font-size: 9px`，文案全中文 |
| 显示规则 | 无 GUI 时显示岛；**聊天栏打开时照常显示岛**（它是转移面板的入口）；**其它 GUI/Screen 打开时隐藏岛**，只显示转移面板；SBW 载具炮镜时同样隐藏 |
| 打开面板 | 聊天栏打开时，右键屏幕顶部居中的岛位置（岛隐藏但热区保留）→ 面板出现在鼠标位置 |
| 渲染分辨率 | 表面按 GUI 尺寸布局 + `CefScreenInfo.device_scale_factor = Minecraft.getGuiScale()`，CEF 实际输出 `GUI 尺寸 × GUI scale` 的物理像素帧（GUI scale 4 时岛为 624×72），MC 再缩回 GUI 尺寸显示；**禁止 1 GUI 像素 = 1 页面像素**（那会被 MC 放大 N 倍而发糊，实测已验证） |
| 自研 html 内核（回退） | 规格与上表一致 |

## 3.3 岛与面板的合并形态（2026-09-20 定稿）

- **同一个 CEF 表面**：`overlay.html`，画布 206×196（GUI 像素），只有一个视图实例。
- **折叠态**：顶部居中的黑色胶囊（`#000000f0`，无阴影，1px 微边框，左右内边距各 7px 保持视觉平衡）。**宽度贴合内容**：脚本用 `max-content` 量出真实宽度再钉成像素（浏览器无法在 max-content 与 px 之间插值），所以它随数字位数变化，同时保留动画能力。
- **打开方式**：**左键**点击资源图标（CEF 与自研内核两条路径一致）。
- **收起动画**：`closePanel` 会先把展开宽度钉成像素、强制重排，再移除 `.open` 并把宽度显式动画到折叠宽度 —— 否则宽度会从 px 直接跳到 `max-content`（瞬变）。实测收缩过程：412×392 → 316×200 → 248×65 → 236×39 → 最终 234×36（物理像素，约 190ms）。
- **展开态**：右键胶囊 → **同一块黑板向下延伸扩大**到 206×196（`width/height/border-radius` 过渡 220ms，`cubic-bezier(.2,.8,.25,1)`；面板内容 `opacity` 淡入，延迟 70ms）。顶行始终保留资源数字，下方依次为标题行、队员列表、数量输入、确认/取消、结果行；配色与字号（**7px 正文 / 8px 标题**，≈ 小六再小三个号）和岛完全一致。
- **位置固定**：屏幕顶部居中（`TOP_MARGIN 2`），**不随鼠标移动**，方便鼠标直接移到队友行上点击。
- **收起条件**：ESC、取消、成功 0.9 秒后自动收起、点击面板外、离开聊天界面（`screen == null` 时 tick 自动收起）。

## 3.4 转移限制（冷却与单次上限）

| 配置项 | 默认 | 作用 |
| --- | --- | --- |
| `transferCooldownSeconds` | `120` | 两次成功转移之间的最短间隔（秒），0 表示关闭 |
| `transferMaxAmmoPerRequest` | `50` | 单次转移弹药上限 |
| `transferMaxFuelPerRequest` | `25` | 单次转移燃料上限 |

- 规则只在 `ResourceApi.transfer` 一处实现，`/warproject resource transfer` 与面板走同一套校验，指令绕不过去。
- 冷却记录**只在内存**（`ResourceApi.LAST_TRANSFER_AT`），重启即清空，与游戏阶段同样的处理方式。
- 面板"上限"= min(自己余额, 单次上限, 999 − 对方存量)；提交成功后按钮显示 `冷却 120s` 并每秒倒数；服务端拒绝消息在页面上译为中文（`冷却中，还需 N 秒。` / `单次最多 N。`）。

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
