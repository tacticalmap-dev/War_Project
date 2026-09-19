---
id: "dfbfbd61-6898-492a-800c-55356cf596b3"
title: "War Project 增量交接：自建 Chromium(CEF) 渲染后端（下载/引导/OSR 上传/输入/回退）"
description: "接在 1c71271d（HTML 内核 SDF+SVG）之后：用户定调「参考 MCEF 做法、在本 mod 内自建」；含 java-cef 源码入库、CEF 二进制自建下载管线（实证 URL/commit/119 MiB）、jcef.path+每 tick N_DoMessageLoopWork 引导、OSR 预乘 BGRA → GL_BGRA/UNSIGNED_INT_8_8_8_8_REV 上传 + ONE/ONE_MINUS_SRC_ALPHA 混合、cefQuery 通道、data: URL 编码陷阱、后端抽象与回退触发、离线 probe 验证法与游戏目录预置位置。改渲染后端、诊断 Chromium 或接新页面前先读。"
status: "active"
created_at: "2026-09-19T19:28:45.332Z"
updated_at: "2026-09-19T19:28:45.332Z"
content_hash: "7d089f901261e64f3a21727a1c9414b3c63f21d1bfdcdcf80081bc45c2f75b5e"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefNatives.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefBootstrap.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/WpCefBrowser.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefTexture.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebRendererService.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebSnapshots.java"
  - "src/main/resources/assets/war_project/web/island.html"
  - "src/main/resources/assets/war_project/web/panel.html"
  - "src/cefApi/java/org/cef/CefApp.java"
  - "build.gradle"
  - "docs/CHROMIUM_BACKEND.md"
  - "THIRD_PARTY_LICENSES.md"
session_ids:
  - "55d124d3-a260-49d9-bdbd-5385a2c2ff37"
memory_body_ids:
  []
---

# War Project 增量交接：自建 Chromium(CEF) 渲染后端

本文只记录**既有托管文档未覆盖**的增量，接在 `1c71271d-130d-47c6-8393-2faa5b988785`（HTML 内核抗锯齿 + 矢量渲染）之后。
它**明确取代** `1c71271d` 第 7 节列为「未决的 MCEF A/B 决策」：用户已定调走 **B —— 参考 MCEF 的做法，在本 mod 内自建一套 Chromium 系统**，不接受对 MCEF 等第三方 mod 的依赖。
资源/HTML 内核更早脉络：`437db98f-3347-4ce2-8050-91259140564c`、`b75b521b-f4c9-41fb-90c8-1928b6462d99`。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`；
**游戏目录 = `D:\mc\.minecraft\versions\totalwar`**（mods 从此目录解析，因此 `<gameDir>/war_project-cef/` 也在此）。

本轮部署件 sha256 `9a0ccfdc…`，jar 578,734 B，其中 `org/cef/**` 163 个 class、**不含任何 CEF 二进制**。

---

## 1. 用户诉求与决策口径

用户原话：「参考 mcef 模组引入 chromium 内核的方法，在本 mod 内自建一套系统」。
即：**要真的 Chromium，但集成必须属于本 mod**。因此 org.cef 绑定随 mod 编译，CEF 二进制由 mod 自己下载，运行期零第三方 mod 依赖。
附带要求（前一 checkpoint 遗留）：灵动岛的苹果质感继续保留，只是渲染主体从自研 `html/` 内核换成 Chromium。

## 2. 交付清单（包与职责）

主源码集，`com.flowingsun.war_project.client.web`（**零 org.cef 引用**，永远编译）：

- `WebRenderer`：后端接口（`isReady/statusText/tick/renderIsland/renderPanel/isPanelOpen/islandContains/panelContains/openPanel/closePanel/mouse*/keyPressed/charTyped/onTransferResult/onDisconnect/shutdown`）。
- `WebRendererService`：**唯一开关**。`active()` 在「无后端」或「后端未就绪」时返回 `null` —— 返回 null 就等于「用自研 `html/` 内核」，这是回退能成立的根本；`statusText()` 供自研 HUD 打一行准备进度；`disable(reason)` 永久切回。
- `WebSnapshot`(record) / `WebSnapshots`(从 `ResourceClientState` 组装 + 在线名单) / `WebJson`(手写序列化 + 两个字段读取器) / `WebPages`(读 `assets/war_project/web/*.html`，缺失回退内置最小页) / `CefAvailability`。

主源码集，`com.flowingsun.war_project.client.cef`（引用 org.cef，经 `Class.forName` 惰性触达）：

- `CefNatives`：下载/校验/解压/路径/离线预置（**纯 JDK，可独立测试**）。
- `CefBootstrap`：`jcef.path` + `CefApp.startup` + `CefSettings` + `CefApp/CefClient` + 每 tick `N_DoMessageLoopWork()`；`version()` 产出 `Chromium 116.0.5845.190 (CEF 116.0, jcef 116.0.27.1)`。
- `CefOsrView extends WpCefBrowser`：一个离屏页面（尺寸、direct buffer、GL 纹理、上传、绘制、输入、执行 JS、释放）。
- `WpCefBrowser extends CefBrowserOsr`：暴露 `resize` 与 `sendMouse*/sendKey*`（java-cef 的发送器是 `protected`，**必须靠继承**）；`CefKeyMap`：GLFW→Windows VK 映射（字母数字本身同值）。
- `CefTexture extends AbstractTexture`：包装视图的 GL id，`close()` 必须空实现。
- `CefWebRenderer implements WebRenderer, CefAvailability`：下载→启动→建两个视图→推快照→收消息→转发输入；`Bridge extends CefMessageRouterHandlerAdapter`。

绑定源码：`src/cefApi/java/org/cef/**`（126 个 `.java`，来自 `chromiumembedded/java-cef`，BSD-3，附 `LICENSE-java-cef.txt`/`AUTHORS-java-cef.txt`，未经修改）；`build.gradle` 用 `cefApiSourceDir.exists()` 条件加入主源码集。

页面：`src/main/resources/assets/war_project/web/island.html`（264×44 画布）与 `panel.html`（320×420 画布），单文件自包含（内联 CSS/JS），以 `data:` URL 加载，**不需要自定义 scheme handler**。

文档：`docs/CHROMIUM_BACKEND.md`（使用/排障/验收关键字）、`THIRD_PARTY_LICENSES.md`、`docs/ARCHITECTURE.md` 新增一行。

## 3. 关键实证参数（做对一次就别再猜）

| 项 | 值 | 来源 |
| --- | --- | --- |
| Chromium / CEF / jcef | 116.0.5845.190 / CEF 116.0 / jcef 116.0.27.1 | 运行 `CefApp.getVersion()` |
| java-cef commit | `d5e3cece98755ff1e5af39261e6a486a5d9adb5d` | `mcef-forge.jar` 的 `java-cef-commit`；GitHub API 确认存在于官方仓库 |
| 二进制 URL | `<mirror>/java-cef-builds/<commit>/windows_amd64.tar.gz`（`.sha256` 同路径） | MCEF 下载器字节码模板 + HTTP 206 实测 |
| 二进制大小 | 124,345,275 B（≈118.6 MiB）；解压后 ≈281 MB（`libcef.dll` 203 MB） | `content-range` 与解压实测 |
| 平台名 | `windows_amd64`（**不是** `windows64` / `WINDOWS_AMD64`，后两者 403） | 逐个探测 |
| 包内结构 | 顶层 `windows_amd64/`，含 `libcef.dll`、`jcef.dll`、`jcef_helper.exe`、`icudtl.dat`、`chrome_*.pak`、`resources.pak`、`locales/`、`vk_swiftshader*`、`vulkan-1.dll` | tar 列表 |
| OSR 像素 | **预乘 alpha + BGRA 字节序**（半透明白采样得到 `B128 G128 R128 A128`） | 两个独立 probe |
| GL 上传 | `glTexImage2D/glTexSubImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_BGRA,GL_UNSIGNED_INT_8_8_8_8_REV,buffer)`（MCEF 用的常量即 32993/33639） | javap 常量 + 实测 |
| 混合 | `RenderSystem.blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`，且**必须 `graphics.flush()` 后再恢复**（blit 是延迟批次） | 预乘结论 |
| 消息通道 | `CefMessageRouter.create(new CefMessageRouterConfig(), handler)` + `client.addMessageRouter` → 页面 `cefQuery({request,onSuccess,onFailure})`；**必须在创建 browser 之前挂到 client 上** | javap + 实测收到 `ready` |
| 尺寸控制 | `browser_rect_.setBounds(0,0,w,h)` + `wasResized(w,h)`（java-cef **没有** `resize()`，是 MCEFBrowser 自己实现的） | 源码 + MCEF 字节码 |
| 消息泵 | 每 tick `CefApp.N_DoMessageLoopWork()`（MCEF 用 mixin 注入渲染前；本项目用 Forge tick，**不需要 mixin**） | javap + 实测 |
| 库加载 | 只需 `System.setProperty("jcef.path", <natives 平台目录>)`，**无需 JVM 启动参数** | CefDownloadMixin 字节码 |

## 4. 管线与数据流

1. **下载（后台线程）**：`<gameDir>/war_project-cef/windows_amd64/` 已含 `libcef.dll/jcef.dll/jcef_helper.exe/icudtl.dat` 即视为已装；否则下载到 `*.part` 再改名 → 校验（优先 `.sha256` 的 64 位 hex；拿不到就退回大小校验 124,345,275）→ 自写 tar.gz 解压（支持 GNU `L` 长名、`x`/`g` 跳过、拒绝 `..` 与绝对路径）→ 写 `<platform>.installed`。**离线预置**：把 `<platform>.tar.gz` 放进 `<gameDir>/war_project-cef/` 即跳过下载。
2. **引导（主线程）**：等下载完成 → `CefApp.startup(["--autoplay-policy=no-user-gesture-required"])`（**不要** `--disable-web-security`）→ `CefSettings{windowless_rendering_enabled=true, background_color=ColorType(0,0,0,0), log_severity=WARNING, cache_path=<root>/cache, user_agent_product}`（用 `settings.new ColorType(...)` 内部类语法）→ 泵最多 1.5 s 等 `INITIALIZED`（NEW 状态也能建 browser，所以不阻塞等）→ `createClient()`。
3. **视图**：`new CefOsrView(name, html, renderScale)` → `setGuiSize(w,h)`（GUI 尺寸 × renderScale）→ 构造里 `super(CefBootstrap.client(), "data:text/html;charset=utf-8," + encode(html), true, null)`（transparent=true）。
4. **帧**：CEF 线程 `onPaint(...)` 把 BGRA 帧 `duplicate()+get()` 进 direct buffer（`MemoryUtil.memAlloc`），**dirty 位为真时丢帧**（宁可少一帧也不撕裂）→ 渲染线程 `upload()` 上传 → `CefTexture` 经 `GuiGraphics.blit` 11 参重载采样整张纹理 → 面板/岛绘制。
5. **数据**：`tick()` 里 `WebSnapshots.current()` 与上次比较，变化才 `executeJavaScript("window.wp&&window.wp.apply(<json>)")`（**不做每帧调用**）。
6. **交互**：命中测试在 Java（岛固定 264×44 顶部居中；面板固定 320×420 夹回屏幕内）→ 坐标 × renderScale 转发；键盘只在面板打开时转发（ESC 由 Java 关面板）；右键岛左/右半区分别为 ammo/fuel。
7. **回退触发**：平台不支持 / 下载或校验失败 / `CefApp.startup` 或 `createClient` 抛错（含 `UnsatisfiedLinkError`）/ 建视图异常 → `WebRendererService.disable(reason)`，日志 `War Project web backend: built in renderer (<reason>)`；界面由自研内核继续绘制，准备期间 HUD 只多一行 `statusText()`。

## 5. 本轮踩到的坑（都已在代码里修掉）

- **`data:` URL 编码**：percent-encode 的 safe 集合**不能含 `#` 和 `?`**。含 `#` 时页面会在 CSS 的 `#id` 选择器处被当作 fragment 截断 → HTML 残缺、`<script>` 不再执行（现象：`cefQuery` 从不回调、画面只有首帧清屏）。
- **预乘 alpha 与混合**：CEF 透明模式给的是预乘像素；用默认 `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` 会发暗，必须 `ONE/ONE_MINUS_SRC_ALPHA`，并且因为 `GuiGraphics.blit` 是延迟批次，要在 `blit` 后 `graphics.flush()` 才能让批次在该混合下出图。
- **纹理生命周期**：`CefTexture.close()` 必须空实现（GL id 归 CEF）；资源包重载会清空 `TextureManager` 并 close 所有纹理 → 每帧 `ensureTexture()` 用 `getTexture(rl, null) == null` 判断后重新注册。
- **GL 线程**：`dispose()` 可能被聊天指令/卸载触发，删除纹理要包在 `RenderSystem.recordRenderCall(...)` 里。
- **启动线程**：下载可在后台线程，但 `CefApp` 启动与 browser 创建放主线程（`tick()` 里做），避免 CEF 绑定错线程。
- **natives 平台名**：MCEF 的 `${platform}` 用的是小写下划线 `windows_amd64`，其枚举名 `WINDOWS_AMD64`（对应解压目录名）不是 URL 用名 —— 两者别混。

## 6. 离线验证方法（可复用，不需要启动游戏）

`org.cef` 不依赖 Minecraft，CEF 初始化也不需要 GL，因此可以在普通 JVM 里完整验证到「出帧 + 消息」：

- 编译 `src/cefApi/java` 全部源码 + 一个 probe 到临时目录；
- probe：`System.setProperty("jcef.path", <natives 平台目录>)` → `CefApp.startup` → `CefSettings(windowless=true, 透明背景)` → `CefApp.getInstance` → `createClient` → `addMessageRouter` → `new CefBrowserOsr(...){}` 覆写 `onPaint` → 循环 `N_DoMessageLoopWork()`；
- `onPaint` 里把 BGRA 拷成 `BufferedImage`（反预乘：`c*255/a`）落 PNG，即可目视校对页面；
- 断言口径：像素 `0x5f3a12ff` 对应页面背景 `#123a5f`（证明 BGRA）、半透明白得到 `128/128/128/128`（证明预乘）、收到 `{"op":"ready","ua":"…Chrome/116.0.0.0…"}`（证明脚本与消息通道）。
- 本轮用该方法实测：natives 下载+校验+解压 12.2 s；灵动岛页面连续 65 帧（CSS 动画在跑）、面板 2 帧，两张 PNG 目视正常。
- 已把解压好的 natives **预置**到 `D:\mc\.minecraft\versions\totalwar\war_project-cef\windows_amd64\`，所以本机首次启动不需要再下载。

## 7. 配置（`config/war_project-common.toml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `webRenderer` | `auto` | `auto` 有则用、`native` 强制自研内核、`chromium` 请求但失败仍回退 |
| `webRenderScale` | `2.0` | 页面内部渲染倍率（1.0–4.0），决定文字锐度与像素量 |
| `cefMirror` | 空 | 二进制镜像；留空用公开构建站 |
| `webDiagnostics` | `false` | 每 60 s 打印一次后端诊断 |

## 8. 交接提示与未验证项

- **未在游戏内验证**（本机无自动化启动手段，留给实际运行时）：GL 纹理上传路径与混合观感、鼠标/键盘转发手感、真实转移五分支、断线/切资源包后的重建、`jcef_helper.exe` 子进程是否被杀软拦截。
- 撞车约束：若将来又装 MCEF，会与本 mod 自带的 `org.cef` 形成双份类 —— 目前**没有**做检测，属已知未决项（计划里写过「检测到 mcef 就退让」，实现时未加入）。
- 页面改动后需要重启客户端（页面以 `data:` URL 内联，无热重载）；若要热重载，可加一条调试指令重新注入，或改用自定义 scheme。
- 平台：`CefNatives.platform()` 目前只认 `windows_amd64`，其它平台直接回退自研内核（非目标）。
