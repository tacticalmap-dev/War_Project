---
id: "9d8463a9-6a57-46f5-8704-3dd0b864c215"
title: "War Project 增量交接：CEF 面板上传崩溃根因（GL_UNPACK_ROW_LENGTH 越界）与打开/关闭的纹理一致性"
description: "接在 0c645feb（面板 136×110、宽度跟随岛宽）之后的增量：① 游戏崩溃的根因是一条自旧版就存在的 GL_UNPACK_ROW_LENGTH 子矩形上传——驱动按整行行距读取，脏矩形贴边时读到 direct buffer 之外，在 nvoglv64.dll 里 EXCEPTION_ACCESS_VIOLATION（附 hs_err 栈与寄存器佐证）；改为「按行打包进紧凑临时缓冲 + GL_UNPACK_ROW_LENGTH=0」，并加区域越界退化为整帧、textureId==0 拒传两道护栏。② 打开面板闪烁的三个来源与对应措施：丢 uploaded 强制整帧并门控绘制、动画期 600ms 强制整帧、关闭后 400ms 继续泵让收起动画播完。③ 面板已打开时点图标改为吞掉点击。改 CEF 上传路径/面板开合时序前必读。"
status: "active"
created_at: "2026-09-23T11:47:13.261Z"
updated_at: "2026-09-23T11:47:13.261Z"
content_hash: "6f3ce5fa913a49e6df153f1695825c78098792911899a33ebde4808894f4c2c4"
source_paths:
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefWebRenderer.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "docs/CHROMIUM_BACKEND.md"
  - "scripts/verify-cef-paint-regions.sh"
  - "scripts/verify-overlay-page.mjs"
session_ids:
  - "3c223645-2d82-4822-b85a-7fc5aafff79f"
memory_body_ids:
  []
---

# War Project 增量交接：CEF 面板上传崩溃根因 + 打开/关闭的纹理一致性

本文只记录**既有托管文档未覆盖**的增量，并更正其中已过时的一处实现。

- 上游：`0c645feb-3584-49fb-b6d3-d35f67b7f76c`（面板缩到 136×110、宽度跟随岛宽、贴岛底）、`a4cb2f01-a47f-4698-8e69-5b2b4498a2fe`、`7d53e3fd-9728-4a97-a49f-f1fe227a7e50`、`b0da5a59-58c3-4222-b1f6-9a80bec175c1`、`dfbfbd61-6898-492a-800c-55356cf596b3`。
- **本文更正 `0c645feb` §3 的第一条**：当时写「`.slab.open .bar { opacity: 0 }`（展开时才隐藏页内胶囊）」——不够。展开动画的**第一帧**仍会画出那条胶囊，屏幕上闪出一条与内核岛重复、且数字慢一拍的「岛」。现在 `.bar` 是**常驻 `opacity: 0`**，只保留 `max-content` 量宽职责；展开起点也从「折叠胶囊尺寸」改为「与岛同宽 + 高度 0」。

用户口径（本轮三条，按出现顺序）：①「会闪烁出现一个岛」②「使用时崩溃」③「在资源交换 hud 以打开的情况下再次点击，不需要重新打开 hud／修复在打开 hud 时会闪烁的问题」。

仓库 `E:\mc_mod_dev\War_Project`；游戏目录 `D:\mc\.minecraft\versions\totalwar`；部署件 `mods\war_project-1.0.0.jar`（本轮最终 sha256 `4d7c65261ef1d1b7383468cd637e9d6fc74722d1d4bf1e43893a61fedd9ea4a5`）。

---

## 1. 崩溃：`GL_UNPACK_ROW_LENGTH` 子矩形上传导致驱动越界（已修）

**现象**：打开转移面板几秒后客户端进程直接消失（无 Java 异常、无 crash-report），游戏目录留下 `hs_err_pid21068.log`（2026-09-23 15:22:12）。

**栈（Render thread，`_thread_in_native`）**：

```
ResourceTransferController.onRenderPost
 → CefWebRenderer.drawSurface → CefOsrView.draw → CefOsrView.upload()
 → org.lwjgl.opengl.GL11.glTexSubImage2D → nvoglv64.dll
EXCEPTION_ACCESS_VIOLATION, reading address 0x000001fdbca37eec
```

**根因**：旧 `upload()` 为了省一次逐行拷贝，对每个脏矩形用
`frame.position(regionY*stride + regionX*4)` + `RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, pixelWidth)`
**直接从整帧 direct buffer 上传子矩形**。驱动是**按整行行距**读取的（读 `regionH` 个完整行，而不是「前 N−1 行整行 + 最后一行 `regionW` 像素」），所以只要脏矩形贴到右/下边缘，驱动就会读到 buffer 末尾之外 → 原生崩溃。

**证据（可复核）**：日志 `War Project CEF first frame for overlay (272x220)` → 帧 `272×220`（= 136×2，用户 GUI scale 为 2），`stride = 272×4 = 1088`；崩溃寄存器 `R12=1856`、`R15=2176=2×1088`（行距的整数倍关系），`RSI/R10=0x1fdbca37eec` 指向无效内存，`R13=nvoglv64.dll`。这段上传代码本轮**未改动**，是旧版既有隐患，只是面板尺寸变化改变了脏矩形形状才第一次踩到边缘。

**修法（结构性，不是加 try/catch）**，全部在 `client/cef/CefOsrView.java`：

| 措施 | 内容 |
| --- | --- |
| 紧凑打包 | 新增 `region` 临时缓冲（首次分配后复用、`dispose()` 释放）。`uploadRegions()` 把每个脏矩形**按行** `MemoryUtil.memCopy` 进该缓冲（行距 = `regionW*4`），`GL_UNPACK_ROW_LENGTH` **恒为 0**，驱动可读字节数 = `w*h*4` = 缓冲容量，**由代码自己保证** |
| 越界退化 | `regionsFitFrame()` 先校验每个区域都落在 `pixelWidth × pixelHeight` 内；不满足则整帧上传并打一次 `WARN`（整帧路径不依赖任何行距） |
| 纹理守卫 | `textureId == 0` 时拒绝上传；`upload()` 仍保留 `frame.capacity()/limit() ≥ w*h*4` 的前置校验 |
| 加速判定 | 整帧条件收敛为 `!uploaded \|\| toUpload.isFull() \|\| !regionsFitFrame() \|\| now < forceFullUntilNanos` |

**验证**：`./gradlew build --no-daemon --offline` 通过；`javap -p` 确认新字段 `region` 与新方法 `uploadRegions()`、`regionsFitFrame()` 已进 jar；全项目 `GL_UNPACK_ROW_LENGTH` 只剩两处、值均为 `0`；离线脏区自检 `bash scripts/verify-cef-paint-regions.sh` → `ALL 14 CHECKS PASSED`。

## 2. 打开面板「闪烁」的三个独立来源与对应措施

前提事实（源码级）：CEF 只在 `panelOpen` 时被绘制，所以**关闭期间纹理完全不更新**；而 Chromium 的脏区是相对**它自己上一帧**报的。两者一旦不一致，重新打开时未被更新的像素就留着上一场的画面。

1. **纹理与 CEF 帧不是同一张** → 打开时 `CefOsrView.skipUntilNextFrame(timeoutNanos)`：在 `frameLock` 内丢掉 `uploaded` 标记（下一次上传必然是**整帧**，两边重新对齐），并让 `draw()` 在新帧到来前拒绝绘制（`drawFromFrame` 门控 + 1.5 s 超时兜底，页面卡住也不会永久空白）。
2. **动画期几乎全屏在变，相信脏区列表就是在赌它不漏报** → 打开后 `forceFullFrames(600ms)` 内强制整帧上传（272×220 = 60 KB，代价可忽略）。
3. **收起动画被冻结在半途** → 关闭时 Java 立刻停止绘制、消息泵降到 2 次/秒，220 ms 的 CSS 收起动画跑不完，页面停成「半个面板」，这正是下次打开时残影的原料。修法：`CefWebRenderer.SETTLE_NANOS = 400ms`，`pumpIfDue()` 在此期间仍按可见帧率泵消息循环，让收起动画在屏幕外播完（页面对应收成 0 高度 = 全透明）。

配合项（上一轮）：`.bar` 常驻 `opacity: 0`；展开起点改为 `transition:none` 钉「`width = 折叠胶囊实测宽` + `height = 0`」再过渡到面板尺寸；`closePanel` 宽度回到岛宽、高度收到 0 并淡出；`fitBar()` 复位内联 `height/opacity`。

## 3. 重复点击语义

`ResourceTransferController.onMousePressed` 先取 `ResourceIslandView.iconAt(...)`；命中图标时若 `WebRendererService.active()` 非空，**只在 `!web.isPanelOpen()` 时才 `web.openPanel(...)`**，其余情况**吞掉这次点击**（`setCanceled(true)` 后 return）。效果：面板已打开时再点图标不重播生长动画、不清空已输入数量、也不会被紧随其后的「点面板外 → 收起」判成关闭。点面板外仍然收起；`active() == null`（自研内核回退路径）行为不变。

## 4. 排查教训（可复用）

- **先确认「这块画面是谁画的」再改**：内核顶部 HUD 只有两个绘制点且互斥——`ResourceHudOverlay`（无 Screen 时）与 `ResourceTransferController.onRenderPost`（`ChatScreen` 时）。据此可断定「闪出来的第二条岛」不可能来自内核双画。
- **数值不同就是指纹**：用户截图里内核岛显示 `47/23`、闪出的那条显示 `46/23` —— 慢一拍说明它来自 CEF 页面持有的快照（`WebSnapshots`），而不是内核实时读取的 `ResourceClientState`。
- **原生崩溃必须读 `hs_err_pid*.log`**：Java 栈会精确指到哪一次 GL 调用；寄存器里的行距/偏移能反推驱动实际读取范围。本机 `javap` 路径为 `C:\Program Files\Java\jdk-21\bin\javap.exe`（`javap` 不在 PATH 里）。
- **离线复现页面的办法**（无需启动游戏）：用 `python -m http.server`（**必须用 `Invoke-CimMethod Win32_Process Create` 之类的独立进程方式启动**，MSYS `nohup … &` 会随 shell 退出被杀）服务仓库根目录，再用浏览器打开 `assets/war_project/web/overlay.html`；需要驱动页面状态时可临时生成一份自驱动「探针页」（复制页面 + 注入 `window.wp.apply/openPanel` 与测量脚本，把结果写进页面文本），验证后立即删除，构建前确认 jar 里没有它。

## 5. 待验证 / 未覆盖

- 真机复测（用户侧）：本轮所有修复的最终确认都在用户的客户端上，交付时**未**再跑一次 dev 客户端进世界（CEF 出帧需要进世界，dev 运行历史上有 Gradle 日志占用/卡死风险）。
- 面板已打开时点**另一个**资源图标目前也只是被吞掉，没有实现「切换 kind 但不重播动画」。
- 若再次原生崩溃，应急开关是 `config/war_project-common.toml` 里 `webRenderer = "native"`（完全不启动 Chromium，转移面板回到自研 HTML 内核）；新的 `hs_err_pid*.log` 栈可直接定位调用点。
