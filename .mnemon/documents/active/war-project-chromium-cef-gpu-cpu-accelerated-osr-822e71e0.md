---
id: "822e71e0-4f17-4169-b6e2-ede22a8443a3"
title: "War Project 增量交接：Chromium(CEF) GPU/CPU 渲染路径实测 —— 命令行开关静默失效缺陷、无 accelerated OSR、cefUseGpu"
description: "接在 9969356d（脏矩形/泵节流/按需启动）之后：用户问「能否让 chromium 跑在 GPU 而不是占 CPU」。① 根因缺陷：开关只给了 CefApp.startup()（Windows 分支忽略参数），getInstance 收到空数组 → --disable-gpu 等全部静默失效，Chromium 一直跑硬件 GPU；修法＝交给 CefApp.getInstance。② jcef 116 无 accelerated OSR（CefRenderHandler 仅 onPaint(ByteBuffer)；jcef.dll 335 导出 0 处 Accelerated/SharedTexture）→ OSR 必然 CPU 位图回传。③ 探针实测矩阵（704×600, 8s）：A 旧行为 Intel UHD D3D11 14.1% / B SwiftShader 9.4% / C 显式 GPU 13.3%，第二次 16.1/8.1/19.5 → 本机软件模式更省 CPU。④ 落地 cefUseGpu（默认 false）、backend 自证日志、scripts/cef-probe/。纠正 b0da5a59「CEF 不再使用 GPU 合成」从未生效。"
status: "active"
created_at: "2026-09-22T04:16:23.798Z"
updated_at: "2026-09-22T04:16:23.798Z"
content_hash: "dc5d1e20d66bbcb5c49153c3088cdd21444b5ed3d57564fd620279709d278917"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefBootstrap.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/cefApi/java/org/cef/CefApp.java"
  - "src/cefApi/java/org/cef/handler/CefAppHandlerAdapter.java"
  - "src/cefApi/java/org/cef/handler/CefRenderHandler.java"
  - "scripts/cef-probe/CefGpuProbe.java"
  - "scripts/cef-probe/run-cef-mode-matrix.sh"
  - "docs/CHROMIUM_BACKEND.md"
session_ids:
  - "012bdc6b-1570-4849-a114-78ef0c79511c"
memory_body_ids:
  []
---

# War Project 增量交接：Chromium(CEF) 的 GPU 与 CPU 渲染路径

本文只记录**既有托管文档未覆盖**的增量，接在 `9969356d-0523-4b86-916f-3e1e1e292bf8`（Chromium 渲染效率优化：脏矩形增量上传 / 消息泵节流 / 按需启动 / 首帧缺陷纠正）之后。
架构与下载·引导管线见 `dfbfbd61-6898-492a-800c-55356cf596b3`；不出帧/黑块根因与稳定性排查见 `b0da5a59-58c3-4222-b1f6-9a80bec175c1`。

**本文纠正 `b0da5a59` 的一条核心结论**：那里写的「CEF 不再使用 GPU 合成：`--disable-gpu --disable-gpu-compositing --disable-gpu-vsync`（实测仍出帧）」**从未生效**——那三个开关一次也没有到达 CEF，Chromium 一直跑在硬件 GPU 上；当年「实测仍出帧」是在 GPU 模式下的观测，不能作为软件合成可用的证据。详见第 2、5 节。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）；游戏目录 = `D:\mc\.minecraft\versions\totalwar`（CEF 二进制在 `<gameDir>/war_project-cef/`）；部署件 `mods\war_project-1.0.0.jar`。
本轮部署件 sha256 前缀 `defac37021c914de`，构建 `BUILD SUCCESSFUL in 38s`（`./gradlew.bat build --no-daemon --offline`），同步后与构建产物 sha256 一致。

---

## 1. 用户诉求（本轮原话）

「能否让chromium内核渲染时跑在gpu而不是占用cpu上」

口径：用户想知道**能不能把 Chromium 的渲染负载从 CPU 移到显卡**，并（延续上一轮）降低整机资源占用。没有要求改变界面外观或分辨率（`device_scale_factor = Minecraft.getGuiScale()` 的物理分辨率规格不得回退）。

## 2. 根因缺陷：命令行开关从未送达 CEF

调用链（读源码即证，非推测）：

- `CefApp.startup(String[] args)`：**Windows 分支完全忽略 `args`**，只做 `System.load(...)` 加载 `d3dcompiler_47/libGLESv2/libEGL/chrome_elf/libcef/jcef`；`args` 只在 macOS 用于定位 framework 路径（`src/cefApi/java/org/cef/CefApp.java`）。
- `CefApp` 构造 `super(args)` → `CefAppHandlerAdapter` 把 `args_` 存下来，并在 `onBeforeCommandLineProcessing(process_type, command_line)` 中**仅当 `process_type` 为空**（浏览器进程）时把 `args_` 逐项 `appendSwitch/appendSwitchWithValue/appendArgument`（`src/cefApi/java/org/cef/handler/CefAppHandlerAdapter.java`）。
- 旧 `CefBootstrap` 是 `CefApp.startup(COMMAND_LINE)` + `CefApp.getInstance(new String[]{}, settings)` —— 开关只走了会被忽略的那条路，`getInstance` 拿到的是**空数组**。

后果：`--disable-gpu`、`--disable-gpu-compositing`、`--disable-gpu-vsync`、`--renderer-process-limit=1`、`--js-flags=--max-old-space-size=64`、以及全部「关掉浏览器后台服务」的开关**一律静默失效**，Chromium 以默认配置运行（硬件 GPU + GPU 进程 + 后台服务）。

修法（已落地）：开关数组交给 `CefApp.getInstance(commandLine, settings)`，`startup` 调用沿用同一数组即可。修复后启动日志打印生效开关全集：

```
War Project CEF ready: Chromium 116.0.5845.190 (CEF 116.0, jcef 116.0.27.1) (gpu=false)
War Project CEF switches: --disable-gpu --disable-gpu-compositing --disable-gpu-vsync …
```

## 3. 没有「GPU 纹理直达」这条路

- `src/cefApi/java/org/cef/handler/CefRenderHandler.java` 只有 `onPaint(…, ByteBuffer buffer, int width, int height)`，**没有 `onAcceleratedPaint`**；`CefBrowserOsr.onPaint` 同样是位图签名。
- `jcef.dll` 的 335 个 JNI 导出中，`Accelerated` / `SharedTexture` / `OnAcceleratedPaint` 命中 **0 处**（用 Python 直接扫二进制字符串，非 `grep` 二进制）；`libcef.dll` 亦搜不到 `shared_texture_enabled` / `GetSharedTextureHandle`。

结论：这套 java-cef 绑定只支持 **CPU 位图 OSR**。无论 Chromium 内部用 GPU 还是 CPU 光栅化，每帧都必须把整帧像素交回游戏进程再上传成 GL 纹理。因此「让 Chromium 跑在 GPU 上就不占 CPU」在 OSR 下**不成立**；要免除回读只能自写 JNI 桥接共享纹理（D3D11 NT handle → GL interop），不在可接受成本内。

## 4. 实测矩阵（探针，不启动 Minecraft）

探针 `scripts/cef-probe/CefGpuProbe.java` 编译随 mod 入库的 `org.cef` 源码，直接用同一套 jcef 二进制起一个离屏浏览器，页面含持续 CSS 动画（强制出帧），并读回页面内 WebGL 的 `UNMASKED_RENDERER_WEBGL` 字符串作为「到底在用谁」的硬证据。

参数：176×150 GUI 像素表面、`device_scale_factor=4`（= 704×600 物理像素，等于本机 MC 真实分辨率）、30 fps、每组 8 s：

| 组 | 启动方式 | 页面报告的 WebGL 后端 | 帧率 | 8 s 回传位图 | CPU（单核占比） |
| --- | --- | --- | --- | --- | --- |
| A | 旧写法（开关只给 `startup`，`getInstance` 传空数组） | `ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)` | 30.4 | 577 MiB | **14.1%** |
| B | 开关交给 `getInstance` + `--disable-gpu*` | `ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)), SwiftShader driver)` | 29.9 | 567 MiB | **9.4%** |
| C | 开关交给 `getInstance` + 不禁用 GPU | `ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)` | 30.3 | 575 MiB | 13.3% |

第二次运行（6 s 窗口、同 scale=4）：A 16.1% / B 8.1% / C 19.5% —— 绝对值随机器负载浮动，**「软件模式更省 CPU」的方向稳定复现**（本机 Intel UHD 630 核显）。

三点判读：

1. A 组就是修复前的真实行为：**硬件 GPU**。用户想要的「跑在 GPU 上」其实早已发生，只是没人知道。
2. B 组证明开关一旦真送达，后端确实从 Intel 核显切到 SwiftShader —— 这是「开关生效」的直接证据（比读命令行更硬）。
3. 反直觉但可复现：GPU 模式多付一次「显卡→内存回读」，还多养一个与 Minecraft 抢显卡的 GPU 进程；页面只有 704×600，省下的光栅化抵不掉这笔账。三组 `copiedMiB` 都在 430–577 MiB/8 s，说明 **CPU 大头是每帧约 1.7 MiB 的整帧位图回传 + 上传，与 GPU/CPU 无关** —— 正是 `9969356d` 的脏区优化在削减的部分。

## 5. 落地改动

- `CefBootstrap`：新增 `BASE_SWITCHES` / `SOFTWARE_SWITCHES` 与 `static String[] switches()`；`switches()` 必须交给 `CefApp.getInstance(commandLine, settings)`；`cefUseGpu=false` 时前置软件三件套。启动日志打印 `gpu=` 与完整开关行。
- `Config`：新增 `cefUseGpu`（默认 `false`）。注释里写明了「两种模式都要 CPU 位图回传」与实测数据，避免后人再按直觉默认开 GPU。
- `CefWebRenderer`：`probeBackend()` 在页面 `ready` 后注入 WebGL 探测，页面用 `cefQuery` 回传 `{op:'gpu',backend:…}`；日志 `War Project CEF backend: …`；`webDiagnostics` 周期行追加 `backend=`。**这条日志是今后判断「当前到底跑在哪」的权威手段**，不要再靠读命令行推断。
- `scripts/cef-probe/`：`CefGpuProbe.java`（探针）与 `run-cef-mode-matrix.sh`（编译 `org.cef` + 探针，依次跑 A/B/C 三组，输出与上表同格式）。运行前请关闭 Minecraft 客户端：脚本只清理自己 cache 目录的 helper 进程，但游戏在跑会污染 CPU 读数。
- `docs/CHROMIUM_BACKEND.md`：新增 3.6 节（实测矩阵与结论），并在 3.1 就地更正了「不再使用 GPU 合成」那条；`docs/ARCHITECTURE.md` 的 CEF 行注明「开关必须交给 `getInstance`」这一硬约束。

## 6. 遗留与风险

- **行为首次真正改变**：修复前软件合成与「关掉浏览器后台服务」从未生效；从现在起它们才真的启用。重启游戏后应确认日志出现 `backend=…SwiftShader…`，并观察首次启动时 GPU cache 重建等一次性差异。
- **`cefUseGpu` 的默认值尚未经用户确认**：当前默认 `false`（更省 CPU、避开与光影争卡），依据是本机核显实测；换独显或换机器后该结论可能反转，需用第 4 节的脚本复测，而非沿用本文数值。
- **不要用 `--use-angle=…` 等开关去「修」GPU 模式**：默认即 ANGLE D3D11（C 组已证），问题不在选择后端，而在 OSR 回读与争卡本身。
- 未决：是否把转移面板也迁回自研 `html/` 内核（彼时为零 Chromium 进程、零位图回读的全 GPU 路径）——本轮未做，也未经用户拍板。
