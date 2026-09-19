---
id: "b0da5a59-58c3-4222-b1f6-9a80bec175c1"
title: "War Project 增量交接：Chromium 不出帧/黑块根因、进程级崩溃排查与 Chrome 后端稳定性加固"
description: "接在 dfbfbd61（自建 CEF 后端架构）之后的增量：createImmediately 是浏览器真正创建的唯一步骤（漏调=不出帧+黑块）、未写内容的 GL 纹理会被驱动读回成不透明黑块、门控必须与自研内核一致、进程级崩溃的判据与排除法（无 Java 报告/无 hs_err/事件日志无 Error/日志在渲染线程硬截断）、CEF GPU 合成与 MC 光影争卡的加固（--disable-gpu 实测仍出帧）、像素缓冲锁与 GL 容量校验、用户 2026-09-20 的 UI 四条修订（紧凑/中文/1:1 不发糊/改回原版 PNG）。追 Chromium 出帧或崩溃问题前先读。"
status: "active"
created_at: "2026-09-19T19:59:59.191Z"
updated_at: "2026-09-19T19:59:59.191Z"
content_hash: "589da72e92a9668f185df8acbf05a6a439764f5e4e086dd26162f92c75e9b64e"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefBootstrap.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebPages.java"
  - "src/main/java/com/flowingsun/war_project/client/web/WebRendererService.java"
  - "src/main/resources/assets/war_project/web/island.html"
  - "src/main/resources/assets/war_project/web/panel.html"
  - "docs/CHROMIUM_BACKEND.md"
session_ids:
  - "efa146ba-b4e8-42cf-afa0-fc2150a2c6fe"
memory_body_ids:
  []
---

# War Project 增量交接：Chromium 根因与稳定性

本文只记录**既有托管文档未覆盖**的增量，接在 `dfbfbd61-6898-492a-800c-55356cf596b3`（自建 Chromium(CEF) 后端：下载/引导/OSR 上传/输入/回退）之后。
HTML 内核与灵动岛转移面板见 `437db98f-3347-4ce2-8050-91259140564c`；早期 HUD 外观与 blit 陷阱见 `b75b521b-f4c9-41fb-90c8-1928b6462d99`。

**本文取代 `dfbfbd61` 的以下当时状态**：灵动岛画布 264×44 / 面板 320×420、`webRenderScale=2` 的两倍内部渲染、内联 SVG 矢量图标、以及"浏览器创建后自动加载页面"的隐含假设。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；游戏目录 = `D:\mc\.minecraft\versions\totalwar`（因此 CEF 二进制在 `<gameDir>/war_project-cef/`）；部署件 `mods\war_project-1.0.0.jar`。

---

## 1. 不出帧的真因：`CefBrowserOsr.createImmediately()` 必须显式调用

症状：CEF 初始化成功、视图对象建好，但**页面不加载、永不paint**（日志里没有 `CEF loaded` / `page online` / `first frame`），岛位置是一块不透明黑。

根因（读 `src/cefApi/java` 内源码即证，不是推测）：

- `CefBrowserOsr.createImmediately()`（`org/cef/browser/CefBrowserOsr.java:46`）才是创建原生浏览器的唯一入口：它设 `justCreated_` 并调用 `createBrowserIfRequired(false)`。
- 该调用最终走到 `CefBrowser_N.createBrowser(...)`（`CefBrowser_N.java:141`）→ `N_CreateBrowser(...)`。
- 只在构造 `new CefOsrView(...)` + `setGuiSize(...)` 而不调 `createImmediately()`，浏览器对象始终不存在 → 不加载页面、不产生任何帧。

对照：MCEF 的 `MCEF.createBrowser` 字节码正是 `setCloseAllowed()` + **`createImmediately()`** + `resize(...)`。
修复：在 `CefOsrView` 构造末尾直接 `createImmediately()`（并打印 `getIdentifier()`，创建瞬间为 `-1`，异步完成后才有效）。

旁证：CEF 自己的 `debug.log`（写在游戏目录根）只有一条无关的 CBCM policy 警告 —— 引擎、natives、子进程通路都正常，缺的只是这一步。

## 2. 264×44 不透明黑块的两个来源

`draw()` 画的是整块视图矩形，所以只要纹理内容不对，就是一块大小恰等于画布的黑：

1. **未写下内容的 GL 纹理**：`glGenTextures` + 设过滤参数后从未 `glTexImage2D` 写入，纹理内容未定义；实测驱动读回为**不透明黑**。修复：纹理创建后立刻用全 0 帧初始化（透明），并且**收到过任何帧之前绝不绘制**（`frames == 0 → return`）。
2. **缺少与自研内核一致的显示门控**：`renderIsland()` 原先只要 `ready` 就画；自研路径有 `ResourceIslandView.shouldShow()`（`running && hasTeam && 非 SBW 炮镜`）。未开局/未组队时前者照画、后者不画 → 必须补上同一判据。

## 3. 进程级崩溃的判定与排查方法（无 Java 崩溃报告的场合）

判据（本次实测）：`crash-reports/` 无新文件、无 `hs_err_pid*.log`、Windows Application 事件日志无 Error、`latest.log` 在**渲染线程某条日志中途被硬截断**（文件 mtime 晚于最后一行）→ 属于进程级终止，不是 Java 异常，`latest.log` 就是唯一现场。

排除法（本次据此定位到"与我的绘制代码无关"）：`shouldShow()` 在单人档（无队伍）恒为 false → `draw()/upload()` 从未执行；日志显示 CEF 出帧全部正常（`first frame for island (528x88)` 属于旧尺寸版本）。因此崩溃时的新增变量只剩 **CEF 自身在跑的 GPU 合成进程**，叠加本机 Embeddium + Oculus 光影。

加固（5 项，已落地）：

- CEF 改**软件合成**：`--disable-gpu --disable-gpu-compositing --disable-gpu-vsync`（离线 probe 实测：仍正常出帧、`page online` 正常）。CEF 默认自带 GPU 进程会与 MC/光影争显卡，是这套后端唯一新增的 GPU 使用者。
- 像素缓冲**线程互斥**：CEF 在自身线程写帧、渲染线程上传/释放，原先存在"释放后被 CEF 线程写入"的真实崩溃风险 → 用锁保护，`dispose` 也进锁。
- **驱动越界读防护**：`glTexImage2D/glTexSubImage2D` 按 `宽×高×4` 读取内存（**不看 buffer limit**），上传前校验 `capacity` 与 `limit`，不足则跳过该帧。
- 关闭游戏时**不 `dispose()` 原生 CefApp/CefClient**，只停消息泵（避免与 MC 自身关停竞争导致原生崩溃）。
- 页面**不要常驻 CSS 动画**：CEF 每帧出帧就会每帧上传纹理，与光影同时抢 GPU；岛页面改为只在数值变化时重绘。

## 4. 用户 2026-09-20 的界面四条修订与实现要点

用户原话：「1.界面过大 2.没有中文 3.界面偏糊 4.改回使用png贴图」。

- **尺寸**：画布 `200×26`（岛内容高约 20，与早期 18 同量级）、面板 `244×200`；常量在 `CefWebRenderer` 顶部。
- **中文**：`panel.html` 全中文（转移弹药/燃料、持有、弹/燃、离线、数量、上限、确认、取消）；服务端拒绝消息是英文（`ResourceApi` 里的 `TransferOutcome` 文案），页面按关键词映射成中文，成功提示由页面自己生成（不依赖服务端文案）。
- **不发糊**：改为 **1 GUI 像素 = 1 页面像素**（移除 `Config.webRenderScale` 与 2× 降采样），纹理过滤 `GL_NEAREST`。任何"高倍渲染再缩小"都会重采样文字。
- **改回原版 PNG 贴图**：页面以 `data:` URL 内联加载，**读不到 jar 内资源**，因此由 `WebPages` 读取 `assets/war_project/textures/gui/ammo.png` / `fuel.png`，**预缩放到 28×28**（显示 14px，浏览器 2:1 平滑缩小，优于 128→14 直接丢像素）后 base64 内联，页面用 `__AMMO_ICON__` / `__FUEL_ICON__` 占位；不要再用矢量重绘图标。

## 5. 验证手段（不启动游戏也能验到出帧与外观）

- **离线 probe（无 MC/无 GL）**：用 `src/cefApi/java` 直接编译并起 `CefApp` + `CefBrowserOsr`，`jcef.path` 指向 natives 目录，每 15ms 调 `N_DoMessageLoopWork()`；可验证初始化、页面加载、`cefQuery` 回传、OSR 像素（**预乘 BGRA**）与外观。做法：注入 `window.wp.apply({...})` / `openPanel({...})`，把 `onPaint` 的 buffer 按 BGRA→ARGB 反预乘写成 PNG 直接看。
- **开发客户端**：`./gradlew runClient --offline --console=plain`（主菜单即可触发 CEF 初始化、页面加载与出帧，无需进世界）；日志关键字见下。
- **日志关键字**：`CEF ready: Chromium 116.0.5845.190`、`CEF view <name>: page N chars, data url M chars`、`CEF loaded … (http 200)`、`CEF page online: … Chrome/116 …`、`first frame for <name> (WxH)`、`status after Nms: island frames=… loading=… document=… url=…`。
- **随时回退**：配置 `webRenderer = native`（`config/war_project-common.toml`）；失败自动回退自研内核（`WebRendererService.active()` 返回 null）。

## 6. 关键文件

- `src/main/java/com/flowingsun/war_project/client/cef/`：`CefNatives`（下载/校验/自写 tar.gz 解压/离线预置）、`CefBootstrap`（`jcef.path` + `CefApp.startup` + 软件合成命令行 + 每 tick `N_DoMessageLoopWork` + `onLoadEnd/onLoadError` 诊断）、`CefOsrView`（构造即 `createImmediately`、帧门控、纹理清零、锁、容量校验、`GL_BGRA` + `GL_UNSIGNED_INT_8_8_8_8_REV` 上传）、`WpCefBrowser`、`CefKeyMap`、`CefTexture`、`CefWebRenderer`（画布尺寸、看门狗、诊断、周期重推）。
- `src/main/java/com/flowingsun/war_project/client/web/`：`WebRenderer`、`WebRendererService`（唯一开关与回退）、`WebSnapshots`、`WebPages`（PNG 预缩放 + base64 注入）、`WebJson`。
- `src/main/resources/assets/war_project/web/`：`island.html`、`panel.html`（单文件自包含、中文）。
- 运行期数据：`<gameDir>/war_project-cef/windows_amd64/`（约 281 MB 解压后；tar.gz 源 `https://mcef-download.cinemamod.com/java-cef-builds/d5e3cece98755ff1e5af39261e6a486a5d9adb5d/windows_amd64.tar.gz`，119 MiB，可预置离线安装）。
- 说明文档：`docs/CHROMIUM_BACKEND.md`（含「稳定性与 GPU」一节）、`THIRD_PARTY_LICENSES.md`。
