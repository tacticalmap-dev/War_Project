---
id: "588ee5c2-515b-4c80-9d4a-fb8d4bb766c2"
title: "War Project 增量交接：资源经济（resource 模块）与全局游戏生命周期（/warproject game）"
description: "War Project 中新增的资源经济与游戏生命周期：/warproject game start|stop|end 属全局指令与全局内核 module/GameStateService（不属任何模块）、node 只存 60s 产出量、resource 模块按可配间隔结算给归属队伍、ENDED 清空资源并重置全部 node 为 neutral、协议号升至 2、构建需禁用 ForgeGradle 证书校验。改动 game/资源/占点门控前读此文档；模块总览见 3d31dded-b45f-4f11-a183-0d1aa78e4faa。"
status: "active"
created_at: "2026-09-19T03:37:29.977Z"
updated_at: "2026-09-19T03:37:29.977Z"
content_hash: "c0299b58073fe7680c910511261d8860a8a6bc7f1c8fd03baa21e1a60809696f"
source_paths:
  - "src/main/java/com/flowingsun/war_project/module/GamePhase.java"
  - "src/main/java/com/flowingsun/war_project/module/GameStateService.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceData.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceService.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceApi.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceModule.java"
  - "src/main/java/com/flowingsun/war_project/WarProject.java"
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/map/MapDivideStateApi.java"
  - "src/main/java/com/flowingsun/war_project/wargame/WargameService.java"
  - "src/main/java/com/flowingsun/war_project/wargame/WargameModule.java"
  - "src/main/java/com/flowingsun/war_project/wargame/NodeOccupationService.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/client/ClientMapState.java"
  - "src/optionalFtb/java/com/flowingsun/war_project/compat/ftb/FtbChunksMapDivideClient.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "efb678ea-7cec-4f86-93bc-369b568eabcc"
memory_body_ids:
  []
---

# War Project 增量交接：资源经济 + 全局游戏生命周期

本文只记录**既有文档未覆盖**的增量。模块总览见 `3d31dded-b45f-4f11-a183-0d1aa78e4faa`（其中「模块固定为三个、命令树无 game/resource」已被本文取代）；FTB 编辑器交互语义见 `1223eb8a-dd85-486c-acd6-b327dec5584d`；渲染规则见 `d33309cb-4d09-4115-b151-97634cb3315e`。

参考模组 `E:\mc_mod_dev\modernwar-town` 无资源经济先例（仅有 `WarStateApi`/战时状态），因此本次设计无蓝本可抄，全部为新增。
部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 用户定调的决策（勿再询问）

| 决策 | 取值 |
| --- | --- |
| `/warproject game ...` 归属 | **全局**：属于全局命令树（`command/WarProjectCommands`，单一注册点）与全局内核 `module/GamePhase` + `module/GameStateService`，**不得归属任何业务模块**；resource/wargame 只订阅阶段变化并在各自包内反应 |
| 阶段持久化 | **不落盘**：每次开服一律 STOPPED，需管理员 `game start` |
| 资源持久化 | **落盘**：`war_project_resources`，重启后队伍存量保留 |
| `game end` 语义 | 清空全部队伍资源 **且** 把所有 node 阵营重置为 `neutral`（并清占领进度/意图） |
| 产出归属 | 只给 node 的归属队伍；**盟友不共享、不平分** |
| node 产出配置入口 | 服务端命令 + FTB 大地图右键菜单「设置产出」（数字输入弹窗） |
| 资源形态 | 每队一个标量（double），不区分资源种类 |

## 2. 架构落点

- **全局内核**（不属于模块）
  - `module/GamePhase`：三态 `STOPPED / RUNNING / ENDED`（`id()` 为 `stopped/running/ended`）。
  - `module/GameStateService`：单例 `active()`/`clearActive()`；`phase()`/`isRunning()`/`addListener`/`removeListener`/`reset()`/`transition(server,target)`。
    - `reset()` 只改阶段、**不动监听器列表**，使模块注册顺序无关。
    - `transition` 先改阶段，再逐个回调监听器副本，**单个监听器抛异常只记日志**（一个模块失败不影响另一个）。
    - 监听器必须用**稳定字段**持有（方法引用每次求值是新对象，`removeListener` 才会命中）。
  - 初始化/清理在入口 `WarProject`：`onServerStarting` 末尾 `reset()`、`onServerStopping` 末尾（模块停止之后）`clearActive()`。
- **resource 模块**（`resource/`）
  - `ResourceData`（SavedData `war_project_resources`，`teams[] = {id, amount}`，amount 为 double；负值/NaN 归一为 0）提供 set/add/addAmounts/spend/clearAll/teamIds/total。
  - `ResourceService`：挂 Forge 总线，`ServerTickEvent`(END) 累计 tick。阶段非 RUNNING 时 `pendingTicks = 0` 直接返回（**不补算暂停期**）；攒满 `intervalTicks = max(1, round(Config.resourceSettleIntervalSeconds * 20))` 后以 `elapsedSeconds = pendingTicks / 20.0` 结算，即 `gain = node.resourcePerMinute * elapsedSeconds / 60`。
  - `ResourceApi`：`amount`/`amounts`（只列 `TeamData` 中仍存在的队伍）/`set`/`add`（管理员用，任意阶段）/`spend`（**仅 RUNNING**，余额不足返回 false）。所有写操作先校验队伍存在。
  - `ResourceModule`：起服注册服务 + 订阅阶段（`ENDED` → `clearAll()`），停服移除监听、反注册、`clearActive()`（顺序：先 removeListener 再 clearActive）。
- **map 侧（对应需求「只负责设置数值」）**
  - `MapData.Node` 增加 `double resourcePerMinute`（NBT `resource_per_minute`），`withIdentity`/`withFaction` 保留该值；新增 `withResourceOutput`、`setNodeResourceOutput(nodeId, perMinute)`、`resetAllNodeFactions()`（同时把镜像 warzone 置 neutral，有改动才 setDirty）。
  - `MapDivideStateApi.setNodeResourceOutput(...)` 写成功即 `broadcastMap`；`resetAllNodeFactions(server)` 改动数 > 0 才广播一次。
- **wargame 侧**
  - `WargameService.onServerTick` 与 `submitCaptureIntent` 增加 `GameStateService.active().isRunning()` 门控（STOPPED 冻结推进与回退，且不再累积意图）。
  - `onGamePhaseChanged(..., ENDED)` → `MapDivideStateApi.resetAllNodeFactions` + `NodeOccupationService.clearAll()` + `intents.clear()`。
  - `NodeOccupationService.clearAll()` 返回移除条数；`WargameModule` 持有稳定 listener 字段注册/移除。

## 3. 阶段状态机

| 当前 | 命令 | 目标 | 副作用 |
| --- | --- | --- | --- |
| 任意（非同态） | `game start` | RUNNING | 无（占领与结算 tick 恢复） |
| RUNNING | `game stop` | STOPPED | 冻结：占领进度、node 归属、资源存量保留；待结算 tick 清零 |
| 任意（非同态） | `game end` | ENDED | 清空全部队伍资源；全部 node→neutral；清空占领进度与意图 |
| 与目标相同 | 任意 | 不变 | 无副作用，回显 `already <phase>` |

阶段不落盘 ⇒ 重启后需重新 `start`；占领进度本就内存态（重启即空），资源从 SavedData 读回。

## 4. 指令契约（`/warproject`，OP 2）

```
/warproject game start|stop|end|status
/warproject node setresource <nodeId> <perMinute>     # 0 下限，node info 回显
/warproject resource list                            # 现存队伍 + 资源
/warproject resource team <team>
/warproject resource set <team> <amount>             # 管理员覆盖，任意阶段
/warproject resource add <team> <amount>             # 管理员加值，任意阶段
/warproject resource take <team> <amount>            # 走 spend，仅 RUNNING
```

- `game` 的 start/stop/end 向全体玩家广播；命令源非玩家时（控制台/命令方块）额外回显给命令源。`status` 输出 `phase / settleInterval / teams / nodesWithOutput / totalResources`。
- `node delete` 顺带 `NodeOccupationService.clear(nodeId)`（与 FTB 删除路径对齐，避免残留进度）。

## 5. 网络与配置

- `WarProjectNetwork.PROTOCOL` 由 `"1"` 升为 `"2"`：包集合不兼容变更，新旧客户端/服务端不得混连。
- 新增 C→S `SetNodeResourcePacket(nodeId, perMinute)`，服务端做 **OP 2 级校验**，回执经 `sendSystemMessage`。`MapSyncPacket` 载荷自动带上 `resource_per_minute`（`MapData.clientSnapshot()` 走 `node.save()`），客户端 `ClientMapState.ClientNode` 增加 `resourcePerMinute`。
- **资源存量不广播**：只在服务端命令可读，无 S→C 资源包（非目标）。
- 配置新增：`resourceSettleIntervalSeconds`（默认 5.0，范围 0.05–3600）、`resourceDebugMode`（默认 false）；`Config.onLoad` 同步静态字段，`resourceSettleIntervalSeconds` 带初值 5.0D 以防配置加载前按 1 tick 结算。

## 6. FTB 入口（`src/optionalFtb/.../FtbChunksMapDivideClient.java`）

- 右键 node（或右键其 warzone，解析到所属 node）菜单新增：不可点标题 `Resource: x.xx / 60s`、动作 `Set resource output`。
- 新增 `AmountPromptOverlay extends ModalPanel`（单 `TextBox`，仿既有 `NameIdPromptOverlay`）：回车确认、ESC 取消；`Double.parseDouble` 失败或为负时**面板内报错且不关闭**；确认后 `WarProjectNetwork.sendNodeResource(id, value)`。
- 弹窗经既有 `schedulePrompt` + `INPUT_MODAL_Z` + `pushModalPanel` 路径打开（不要另起渲染路径）。

## 7. 环境与工具事实

- **构建必须禁用 ForgeGradle 证书校验**，否则配置阶段即失败（`Failed to validate certificate for host 'https://maven.minecraftforge.net/'`）：
  `./gradlew build --offline -Dnet.minecraftforge.gradle.check.certs=false`
  注意 `compileJava` 有时仍能成功而 `build` 失败，遇到构建失败先怀疑此处，不要误判为代码错误。
- 可选 FTB 源码集只在构建期探测到 FTB Library + FTB Chunks 的 jar（`libs/` 或 `D:/mc/.minecraft/versions`，排除 neoforge/fabric/quilt）时才编译；日志须出现 `War Project optional FTB compat enabled:` 才说明 FTB 菜单改动真的进了 jar（`build/classes/.../FtbChunksMapDivideClient$AmountPromptOverlay.class` 可佐证）。Xaero 同理。
- 项目无 `src/test`，验证靠构建 + 游戏内。

## 8. 验收断言（可观测）

1. `game status` → `stopped`；`game start` 全体广播；`node setresource <node> 60` 后 5 秒 `resource team <owner>` ≈ `5.00`。
2. `game stop` 后 10 秒数值不变；`resource take <team> 2` 失败（消耗被暂停）。
3. `game start` 后数值继续增长、`take` 恢复可用。
4. `game end` 后 `resource team <owner>` = `0.00`，`node info <node>` 的 faction=`neutral`，`progress node <node>` 无进度。
5. 阶段为 stopped/ended 时站进敌方 node，`progress node` 不增长、node 阵营不变；running 时行为与旧版一致。
6. FTB 右键菜单显示当前产出量；输入 `abc`/`-1` 应报错不关闭；输入 120 后 `node info` 显示 120。
7. `resourceSettleIntervalSeconds=1` 后按 1 秒一跳，且同样 10 秒的总产出与 5 秒配置一致（因为按真实经过秒数折算）。
8. 重启服务器：阶段回到 `stopped`（需 `start`），队伍资源存量保留。

## 9. 非目标与后续可做

- 非目标：资源种类/多资源、个人或小队资源、盟友分成、客户端资源 HUD 或同步、团队删除时的资源转移或清理（存量条目保留，`resource list` 只显示仍存在的队伍）、FTB 建号流程中的产出量输入、重新生成 `docs/architecture.html|json`。
- 可做：资源 HUD/计分板、消耗侧真正的玩法消费方（`ResourceApi.spend` 目前无调用方）、节点产出量的批量设置命令。

## 10. 已同步文档

仓库内 `docs/ARCHITECTURE.md` 已更新（模块表、目录树、主流程图、指令树、SavedData 与配置清单、主流程第 6–8 步），与本文一致；`docs/architecture.html|json` 未重新生成。
