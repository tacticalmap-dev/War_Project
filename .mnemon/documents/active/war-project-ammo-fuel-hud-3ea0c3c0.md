---
id: "3ea0c3c0-6079-4bcf-8580-c21edf9967b5"
title: "War Project 增量交接：双资源（ammo/fuel）与资源 HUD"
description: "取代 588ee5c2 中「单资源标量」表述的增量：node 双产出（ammo/fuel）、ResourceKind/ResourceData 结构、命令与 FTB 菜单、ResourceSyncPacket 与顶部资源 HUD（图标+存量+每60s速率）、协议号升至 4、以及本轮未 build/未同步 jar 的状态。改动资源、HUD、网络包前先读。"
status: "active"
created_at: "2026-09-19T06:54:30.055Z"
updated_at: "2026-09-19T06:54:30.055Z"
content_hash: "19de02f916e36a9bbfde0d111db577550fd572e0c3da0a5aab2f232fc1105b17"
source_paths:
  - "src/main/java/com/flowingsun/war_project/resource/ResourceKind.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceData.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceService.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceApi.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceModule.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/map/MapDivideStateApi.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceClientState.java"
  - "src/main/java/com/flowingsun/war_project/client/ResourceHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/optionalFtb/java/com/flowingsun/war_project/compat/ftb/FtbChunksMapDivideClient.java"
  - "src/main/resources/assets/war_project/textures/gui/ammo.png"
  - "src/main/resources/assets/war_project/textures/gui/fuel.png"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "f2dbaa3b-c4bf-4366-9fe0-f08d000253a7"
memory_body_ids:
  []
---

# War Project 增量交接：双资源（ammo/fuel）与资源 HUD

本文只记录**既有文档未覆盖**的增量。前序文档 `588ee5c2-515b-4c80-9d4a-fb8d4bb766c2`（资源经济 + 全局游戏生命周期首版）中的「每队一个资源标量（double），不区分资源种类」「node 只存一个 60s 产出量」「协议号 2」等表述**已被本文取代**；模块总览见 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`；FTB 编辑器交互见 `1223eb8a-dd85-486c-acd6-b327dec5584d`；渲染规则见 `d33309cb-4d09-4115-b151-97634cb3315e`。

仓库：`E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10）。部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 用户定调的规格（勿再询问）

| 规格 | 取值 |
| --- | --- |
| node 产出种类 | **两种**：弹药 ammo 与燃料 fuel；每个 node 可同时拥有**不同**的两项 60s 产出量 |
| `/warproject game ...` 归属 | **全局**：全局命令树（`command/WarProjectCommands` 单一注册点）+ 全局内核 `module/GamePhase` / `module/GameStateService`，不得归属任何业务模块；resource / wargame 只订阅阶段变化 |
| 资源 HUD | 执行 `game start` 后，在**屏幕正上方中央**渲染 ammo / fuel 图标，并在图标后标出当前资源量与增加速率；速率**只显示 `+xx`**，默认语义为**每 60s 增加量** |

## 2. 双资源数据与结算

- node（`map/MapData.Node`）：字段 `ammoPerMinute` / `fuelPerMinute`，NBT 键 `ammo_per_minute` / `fuel_per_minute`；旧的单值键 `resource_per_minute` 在 load 时迁移为 ammo。map 侧只存数值，不做结算。
- 队伍存量（`resource/ResourceData`，SavedData `war_project_resources`）：`teams[] = {id, ammo, fuel}`，旧键 `amount` 迁移为 ammo；负值/NaN 归一为 0。
- `resource/ResourceKind`：唯一枚举 `AMMO("ammo")` / `FUEL("fuel")`，`parse(String)` 非法值返回空、**不猜测默认值**。
- 结算（`resource/ResourceService`）：挂 Forge 总线，`ServerTickEvent`(END) 累计 tick；阶段非 RUNNING 时 `pendingTicks = 0` 直接返回（暂停期不补算）；`intervalTicks = max(1, round(Config.resourceSettleIntervalSeconds * 20))`（默认 5s）；到点后 `elapsedSeconds = pendingTicks / 20.0`，两种资源各 `perMinute * elapsedSeconds / 60`，只发给 node 归属且仍在 `TeamData` 中的队伍（**盟友不分成**，neutral 不产出）。
- `resource/ResourceApi`：`stock` / `stocks`（只列现存队伍）/ `amount(team, kind)` / `set` / `add`（管理员，任意阶段）/ `spend(team, kind, cost)`（**仅 RUNNING**）；另新增 `snapshot(server)` / `broadcastSync(server)` / `sendSync(player)` 供 HUD 同步。
- `game end` 仍为：清空两个资源 + 把所有 node（及其镜像 warzone）重置 `neutral` + 清占领进度与意图；`stop` 只冻结增量与消耗。

## 3. 命令与 FTB 入口

- `/warproject node setresource <nodeId> <ammoPerMinute> <fuelPerMinute>`（一次写入两项；`node info` 回显两者）
- `/warproject resource list` → 每队 `ammo=…, fuel=…`；`/warproject resource team <team>`；`/warproject resource set|add|take <team> <ammo|fuel> <amount>`（kind 补全 ammo/fuel；take 仅 RUNNING）
- `/warproject game status` → 阶段 / 结算间隔 / 队伍数 / 有产出 node 数 / ammo 合计 / fuel 合计
- FTB 大地图右键菜单：标题行 `Ammo: x / 60s`、`Fuel: y / 60s`；动作 `Set ammo output` / `Set fuel output` 各开数字弹窗（`AmountPromptOverlay`，非法输入不关闭），提交时**把未修改的一项按当前值一并回传**，服务端始终写入完整一对。

## 4. 资源 HUD 与同步协议

- 包 `ResourceSyncPacket(boolean running, List<ResourceTeamEntry> teams)`（S→C），条目 `ResourceTeamEntry(teamId, ammo, fuel, ammoPerMinute, fuelPerMinute)`；`ammoPerMinute` / `fuelPerMinute` 是该队**全部归属 node 的 60s 产出之和**（即 HUD 的 `+xx`）。
- 推送时机：每 5s 结算后（`ResourceService.onServerTick`）、game 阶段变化后（`ResourceModule` 的监听器）、玩家登录时（`WarProject.onPlayerLoggedIn` → `ResourceApi.sendSync`）。
- 客户端：`client/ResourceClientState` 镜像（`replace` / `entry(teamId)` / `isRunning()` / `reset()`，登出时由 `client/WargameCaptureClient.onLogout` 清理）；`client/ResourceHudOverlay` 注册在 `VanillaGuiOverlay.HOTBAR` 之上，**仅 `running=true` 且本地玩家属于某队**时绘制，屏幕顶部居中，每条 = 图标 + 当前存量 + `+每60s速率`（速率为 0 时用灰色）。
- 图标：`src/main/resources/assets/war_project/textures/gui/ammo.png`、`fuel.png`（均 128×128）。绘制要点：`GuiGraphics` 必须用 9 参数重载 `blit(rl, x, y, uOffset, vOffset, w, h, texW, texH)`，**已用 javap 反编译确认 `texW/texH` 参与 `fdiv` 归一化**，故 128 图能正确缩到 16×16；7 参数重载按 256×256 采样，对 128 图只显示左上 1/4，不可用。
- 协议号演变：单通道 `war_project:main`，本迭代中依次升到 **`"4"`**（新增 C→S `SetNodeResourcePacket`（携带 ammo+fuel）时升到 `"3"`，新增 S→C `ResourceSyncPacket` 时升到 `"4"`）。两端必须同一个 jar。

## 5. 验证状态（截至本次交接）

- 已通过：`./gradlew compileJava --no-daemon --offline` → BUILD SUCCESSFUL（仅 2 条既有的 `ResourceLocation(String,String)` 弃用警告）；可选源码集日志确认 `optional FTB compat enabled`，说明 FTB 菜单改动确实参与编译。
- **未完成**：用户明确要求本轮**不要 build、不要同步 jar**（多 agent 并行，避免构建/部署冲突）。因此 `build/libs/war_project-1.0.0.jar` 与 `D:\mc\.minecraft\versions\totalwar\mods\` 下的 jar **仍是上一版（单资源、无 HUD）**。后续需在确认无其他 agent 构建后，用 `--no-daemon --offline` 打包并同步（本机 Gradle daemon 模式会卡死）。

## 6. 已知取舍与风险

- 速率刷新延迟：结算广播周期为 5s，地图归属/产出改动后 HUD 速率最多 5s 才刷新（阶段变化与登录则即时）；玩家换队由客户端 `TeamClientState` 即时判定，但该队存量仍等待下一次广播。
- 旧档迁移后语义变化：旧的单值产出/存量在迁移后**只作为 ammo**，fuel 从 0 开始。
- `resource list` / `game status` 只统计 `TeamData` 中仍存在的队伍；被删队伍的历史存量仍落盘但不显示。
- 资源只是普通标量，暂无消耗方调用 `ResourceApi.spend`（除了 `resource take` 命令），消耗入口已就绪。
