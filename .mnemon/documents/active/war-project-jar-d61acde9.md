---
id: "d61acde9-7a8e-4aff-b9de-57437ee968f5"
title: "War Project 客户端 jar 热替换事故与安全部署／离线验证运行手册"
description: "2026-09-23 事故复盘：游戏运行中用 cp -f 原地覆盖 mods 里的 jar，JVM 的 zip 视图与磁盘不一致，首次加载未加载过的类即 NoClassDefFoundError 崩游戏（打开 Xaero 世界地图时炸在 XaeroWorldMapScreenOverlay$MapProjection）。本文给出安全部署流程（先查 javaw/java 进程；必要时 mv 成 .prev 再放入新文件）、同因的 Error 兜底加固，以及无游戏环境下的离线界面验证流程（DOM 桩自检、WMI 起脱离 shell 的静态服务器 + 自驱动探针页 + browser_open 读文本/截图），并记录本轮用户口径。做构建/部署/离线 UI 验证前读。"
status: "active"
created_at: "2026-09-23T12:10:24.203Z"
updated_at: "2026-09-23T12:10:24.203Z"
content_hash: "6f3076d34d858c6094533053250992a9c7ff549ba32565351ad15308466cc656"
source_paths:
  - "docs/CHROMIUM_BACKEND.md"
  - "docs/ARCHITECTURE.md"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceTransferController.java"
  - "src/main/java/com/flowingsun/war_project/client/cef/CefOsrView.java"
  - "src/main/resources/assets/war_project/web/overlay.html"
  - "scripts/verify-overlay-page.mjs"
  - "scripts/verify-cef-paint-regions.sh"
session_ids:
  - "e39b0782-5eb0-41fc-93ad-1b6b4eb3e527"
memory_body_ids:
  []
---

# War Project 客户端 jar 热替换事故与安全部署／离线验证运行手册

仓库 `E:\mc_mod_dev\War_Project`；游戏目录 `D:\mc\.minecraft\versions\totalwar`；部署件 `mods\war_project-1.0.0.jar`。
上游相关文档：`9d8463a9-6a57-46f5-8704-3dd0b864c215`（CEF 上传崩溃根因与开合纹理一致性）、`a4cb2f01-a47f-4698-8e69-5b2b4498a2fe`、`7d53e3fd-9728-4a97-a49f-f1fe227a7e50`；仓库内 `docs/CHROMIUM_BACKEND.md`（§3.3 面板形态、§3.5 增量上传与崩溃记录）、`docs/ARCHITECTURE.md`。

本文只记上游文档**没有覆盖**的两件事：**运行中替换 jar 导致的类加载崩溃 + 由此定下的部署红线**，以及**无游戏环境时验证界面与页面的可靠流程**。

---

## 1. 事故：游戏运行中覆盖 mod jar → `NoClassDefFoundError`（2026-09-23 19:47）

**现象**：打开 Xaero 世界地图时客户端抛 `ReportedException: Rendering screen`，根因链为
`java.lang.NoClassDefFoundError: com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay$MapProjection`
→ `ClassNotFoundException` at `XaeroWorldMapScreenOverlay.readProjection(:596)` → `onScreenRenderPost(:121)`。

**时间线（三方对照即可复现判断）**：
- `logs/latest.log` 第一行 **19:40:47** 启动客户端；
- `mods/war_project-1.0.0.jar` 的 mtime **19:45:43** —— 构建产物在游戏运行期间被 `cp -f` 覆盖；
- 崩溃报告 `crash-reports/crash-2026-09-23_19.47.01-client.txt` **19:47:01**。

**根因**：`cp -f` 是**原地截断重写**，而 JVM 已经打开了该 jar 并缓存了 zip 中央目录。运行中的进程按旧视图加载类，磁盘内容却已变，于是**任何尚未被加载过的类**首次加载都会失败。启动时已加载的类全部在内存里不受影响，所以只有"第一次用到"的类会炸 —— 本例里 `MapProjection` 是嵌套 `record`（定义在同文件第 622 行），**只有打开世界地图才会第一次加载**，因此崩在那一刻。

**证明 jar 本身完好（排除代码问题）**：`unzip -t` 无错误、382 条目 / 332 个 class，且 `XaeroWorldMapScreenOverlay$MapProjection.class`（2423 B）就在 jar 里；源码与该 record 定义一致。

---

## 2. 部署红线（此后每次构建/同步都照此执行）

1. **构建**（本机 daemon 模式会卡死）：
   `./gradlew.bat build --no-daemon --offline --console=plain -Dnet.minecraftforge.gradle.check.certs=false`（约 30–40 s），日志重定向到 `build/.tmp-build.log` 后 `grep -E 'BUILD|error:'`。
2. **先查进程，再决定怎么写文件**：
   - `tasklist //FI "IMAGENAME eq javaw.exe"`（Minecraft 走 `javaw.exe`；`java.exe`、`GradleDaemon.exe` 一并看）；
   - **无游戏进程**：可以直接 `cp -f build/libs/war_project-1.0.0.jar <mods>/war_project-1.0.0.jar`；
   - **游戏在运行**：**绝不原地覆盖**。改为 `mv <mods>/war_project-1.0.0.jar <mods>/war_project-1.0.0.jar.prev` 再 `cp` 新文件（重命名不影响 JVM 已打开的句柄，当前这一局不受影响），并在回复里明确"重启后生效"。需要绝对安全时也可以干脆等用户退出游戏。
3. **部署后校验**：`sha256sum` 比对 `build/libs` 与 `mods`；`unzip -t` 无错；确认 jar 内 **CEF 二进制计数为 0**（`unzip -l | grep -ciE 'libcef|jcef\.dll|icudtl'`）、**没有临时探针页**；关键常量用 `javap -p -constants` / `javap -c` 反编译核对（不要只 grep class 字节，整数常量在二进制里 grep 不到）。
4. 构建产物 sha256 每次构建都会变，**不要把 sha 当成文档事实**；需要时现算。

---

## 3. 同因防护：客户端事件入口必须兜住 `Error`

`NoClassDefFoundError` / `ClassNotFoundException` 属于 `Error`，**不会被 `catch (RuntimeException)` 拦住**。因此任何"渲染或事件入口 + 反射/类加载"的路径都要兜 `Throwable`，并在失败后**本会话停用该叠加**而不是每帧重试。已落地的形态（`src/optionalXaero/java/.../XaeroWorldMapScreenOverlay.java`）：

- `onScreenRenderPost` 只做 `broken` 短路 + `try { render(event); } catch (Throwable t) { broken = true; LOGGER.warn("War Project world map overlay disabled after a failure", t); }`；
- `readProjection` 的捕获从 `ReflectiveOperationException | RuntimeException` 扩到 `| LinkageError`；
- 效果：即使 jar 被换、Xaero 字段改名，最坏也只是失去地图叠加并留一行日志，不再崩客户端。

---

## 4. 无游戏环境下的界面/页面验证流程

**先知道两个限制**：`browser_open` 只接受**带 hostname 的 URL**（`file://`、`data:` 被使用策略拒绝）；`browser_evaluate` 属于需审批的页面操作（本会话审批为 never 时会直接被拒）。所以不要设计依赖外部注入脚本的验证。

**(a) 零浏览器自检（最快，先跑）**
- `node scripts/verify-overlay-page.mjs` —— 用 DOM 桩跑 `overlay.html` 的真实 `<script>`，校验增量写入契约（当前 ALL 17 CHECKS PASSED）；
- `bash scripts/verify-cef-paint-regions.sh` —— `CefPaintRegions` 的裁剪/合并/退化自检（当前 ALL 14 CHECKS PASSED）。

**(b) 真 Chromium 的布局与像素（需要静态服务器）**
1. 用 node 把 `assets/war_project/web/overlay.html` 读出来、在 `</body>` 前注入一段**自驱动脚本**：`wp.apply(snapshot)` → `wp.openPanel({kind, cooldown, width})` → `setTimeout` 后在页面里量 `getBoundingClientRect()` / `scrollHeight` / `getComputedStyle().fontSize`，把结果拼成一行文本塞进 `document.body` 末尾的可见 `<div>`（必要时冻结某一帧：把当时尺寸写成 inline 并 `transition:none`）。探针页写到 **`build/`**（例如 `build/.panel-probe.html`），**绝不能放 `src/main/resources`**（会被打进 jar）。
2. 起一个**脱离 shell 的**静态服务器（普通 `&`/`Start-Process` 会随 bash 会话退出被清掉）：
   `powershell -NoProfile -Command "Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='\"<python.exe>\" -m http.server <port> --bind 127.0.0.1 -d E:\mc_mod_dev\War_Project'}"`，返回 PID；`curl -s -o /dev/null -w '%{http_code}'` 确认 200。
3. `browser_open` 打开 `http://127.0.0.1:<port>/build/.panel-probe.html` → 页面文本里就有测量结果，`browser_screenshot` 用来目测字号/形状。
4. 收尾：`Stop-Process -Id <pid> -Force`，`curl` 确认端口已关（exit 7 / `000`），删除探针页与临时 `.ps1`；`ls -a build/ scripts/ | grep -E '^\\.tmp|^\\.panel|^\\.star'` 确认干净。
5. 注意：Windows 上 MSYS bash 会把 `$_`、`//FI` 之类参数做路径转换 —— 复杂 PowerShell 一律写进临时 `.ps1` 再 `-File` 执行；杀进程用已知 PID 或 `Stop-Process`，不要用 `taskkill /F`。

**同类坑（CEF 像素上传）**：脏矩形上传**不得**用 `GL_UNPACK_ROW_LENGTH` 从整帧 direct buffer 直接定位子矩形（驱动按整行行距读取，贴边即越界崩在 `nvoglv64.dll`）。必须把区域按行打包进紧凑缓冲、`GL_UNPACK_ROW_LENGTH = 0`。细节与崩溃栈见 `9d8463a9-6a57-46f5-8704-3dd0b864c215` 与 `docs/CHROMIUM_BACKEND.md` §3.5。

---

## 5. 本轮用户口径（勿再询问）

- 转移面板**已打开**时再点资源图标**不重开**（点击被吞掉、不清空已输入数量）；点面板外才收起。
- 面板外框尺寸维持 `132×110`（GUI 像素，宽度 = `clamp(岛实测宽, 132, 136)`、紧贴岛底 1px）；用户逐轮只要求**内容同比缩小**：当前正文 5px / 标题 6px，按钮 `2px 4px` 圆角 4，输入框 `1px 3px` 圆角 3，队员行 `1px 3px`。
- 战局条 VP 星几何固定：`viewBox="-7 -7 38 38"`、五角星外接半径 11.2、占领弧内 13.8 / 外 18（环比星明显大一圈）、槽位 14/13/12 px（14 = 条高）。
- 页面里那条「量宽条」`.bar` 字号必须与内核画的岛一致（现 6px），它量出的宽度决定面板宽度与点击热区。
- 硬性要求：**不得猜测**，每一步先读源码（必要时 `javap` 反编译）验证。
