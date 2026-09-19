---
id: "d24b47cb-4018-45a1-bed3-e71ede24d5a8"
title: "War Project recovery 模块：game start 前地图快照 / game end 后恢复"
description: "recovery 模块的作用域与实现契约：快照只含 MapData（玩家数据默认为空）、落盘 <存档>/war_project_recovery/snapshot.nbt、模块必须注册在最后、GameStateService 新增 onBeforeGamePhaseChanged 前置钩子、三条 /warproject recovery 指令与边界容错。改 game 生命周期、快照或恢复行为前读本文；ENDED 的重置行为见 588ee5c2，模块骨架与 SavedData 见 3d31dded。"
status: "active"
created_at: "2026-09-19T19:34:56.459Z"
updated_at: "2026-09-19T19:34:56.459Z"
content_hash: "92392d220c1b7319ce2d32131617a50d1daafcf47f4d3712b0d41461bfdcb0ee"
source_paths:
  - "src/main/java/com/flowingsun/war_project/recovery/RecoveryModule.java"
  - "src/main/java/com/flowingsun/war_project/recovery/RecoveryService.java"
  - "src/main/java/com/flowingsun/war_project/recovery/RecoveryApi.java"
  - "src/main/java/com/flowingsun/war_project/recovery/RecoverySnapshot.java"
  - "src/main/java/com/flowingsun/war_project/module/GameStateService.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/map/MapDivideStateApi.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/main/java/com/flowingsun/war_project/WarProject.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "ee78862f-3406-422f-9fac-5a9330b793c4"
memory_body_ids:
  []
---

---
title: War Project recovery 模块：game start 前地图快照 / game end 后恢复
tags: [war_project, recovery, 快照, 游戏生命周期]
related_documents:
  - "588ee5c2-515b-4c80-9d4a-fb8d4bb766c2"   # 增量交接：资源经济与 /warproject game 生命周期（本文改写了其描述的 ENDED 终态）
  - "3d31dded-b45f-4f11-a183-0d1aa78e4faa"   # 模块骨架、node/warzone 数据模型、SavedData 与命令树总览
  - "21dce6fc-d9f3-402f-9213-91f18c0235a6"   # MapData 的 bounds 段（随快照一起备份）
---

# War Project recovery 模块（2026-09-20 落地）

## 1. 需求与用户定调（勿再询问）

- 需求原话：「注册一个 recovery 模块，在该模块中实现：在 game start 前对世界进行备份，并在 game end 后将其恢复。」
- **「世界」= War Project 地图状态**（用户明确回答「地图状态，玩家数据默认为空」）：只备份/恢复 `MapData`，即 node（id / 名称 / 阵营 / 颜色 / 区块集合 / 60s 产出）与 warzone，外加地图区域 `bounds`。
- **玩家数据默认为空**：个人资源（`ResourceData`）与队伍（`TeamData`）**不备份、不恢复**。快照段结构里预留了位置，但除 `map` 以外的段一律不写入；恢复只套用 `map` 段，因此 end 之后资源为空（由 `ResourceModule` 的 ENDED 清空决定）、队伍保持原样。
- 需要手动运维指令（用户选定「要」）：`/warproject recovery status|snapshot|restore`。

## 2. 架构与落点

```
src/main/java/com/flowingsun/war_project/recovery/
├─ RecoveryModule.java    模块生命周期 + 阶段订阅（id = "recovery"）
├─ RecoveryService.java   快照唯一持有者：capture / loadFromDisk / restore / status
├─ RecoveryApi.java       服务端门面：captureSnapshot / restoreSnapshot / statusLine → Outcome(ok, message)
└─ RecoverySnapshot.java  record(takenAtMillis, phaseId, mapSection) + NBT 往返
```

**注册顺序是硬约束**：`WarProject` 的模块列表把 `RecoveryModule` 放在**最后**（mapdivide → team → resource → nodeLJYS → recovery）。`GameStateService` 按注册顺序同步回调监听器，所以 end 时的执行序必须是「resource 清空资源 → nodeLJYS 重置全部 node 为 neutral + 清空占领进度 → recovery 覆盖回快照」，否则恢复结果会被前两者的重置抹掉。

**内核改动（唯一一处）**：`module/GameStateService.Listener` 新增 `default void onBeforeGamePhaseChanged(server, from, to)`；`transition()` 在 `phase = target` **之前**按同一顺序回调它（异常单独 try/catch + warn），之后才改阶段并回调 `onGamePhaseChanged`。默认方法让既有 4 个监听器零改动。需要「旧阶段状态」的逻辑一律用前置钩子，不要用后置回调（那时阶段已经变了）。

## 3. 快照数据契约

- 文件：`server.getWorldPath(LevelResource.ROOT)/war_project_recovery/snapshot.nbt`，即 `<存档>/war_project_recovery/snapshot.nbt`；**单槽**，每次 `game start` 覆盖（旧的先写 `snapshot.nbt.tmp` 再 `Files.move(..., ATOMIC_MOVE)`，不支持原子移动时降级 `REPLACE_EXISTING`）。
- NBT 结构：`{ version:1, takenAt:<epoch millis>, phase:"stopped", sections:{ map:{...MapData.save 输出...} } }`；`sections` 下**只有 `map`**（有意的：玩家数据默认为空）。
- 读回：`RecoverySnapshot.fromTag`，版本不符或缺 `map` 段 → `IllegalArgumentException`，调用方当作「无快照」处理。
- 恢复路径（就地覆盖，不替换 `DimensionDataStorage` 中的实例）：`MapDivideStateApi.restoreSnapshot(server, mapSection)` → `MapData.restoreFrom(tag)`（复用 `MapData.load` 解析后 `clear + putAll`，再 `setDirty()`）→ `WarProjectNetwork.broadcastMap(server)` 全量推送客户端。选择就地恢复的原因：全仓库没有任何地方缓存 SavedData 实例（每次 `MapData.get(server)`），就地覆盖最省事且无需触碰 DataStorage 内部。
- 强制读取的底层 API 事实（javap 于 `forge_gradle/.../joined/rename/output.jar`）：`NbtIo.writeCompressed(CompoundTag, File)` / `NbtIo.readCompressed(File)` 均 `throws IOException`；`MinecraftServer.getWorldPath(LevelResource)`；`LevelResource` 的 `ROOT` 值为 `"."`。

## 4. 时序

```
game start → transition(server, RUNNING)
             ├─ [前置钩子] RecoveryModule.captureSnapshot：MapData.save 深拷贝 + 写盘 + 内存留存 + 日志
             └─ [后置]     phase = RUNNING，各模块常规反应
game end   → transition(server, ENDED)
             ├─ [后置] ResourceModule  : ResourceData.clearAll()          （玩家数据 → 空）
             ├─ [后置] NodeLJYSModule  : 全部 node 重置 neutral + 清占领进度
             └─ [后置] RecoveryModule  : restoreSnapshot → restoreFrom + broadcastMap + 全服提示
```

`game start` 的前置钩子拿到的仍是 start 之前的地图（此刻各业务模块的 tick 尚未运行）。

## 5. 指令与运维

| 指令 | 行为 |
| --- | --- |
| `/warproject recovery status` | 快照时间、`phase`、node / warzone 数量、文件路径、`persisted`、恢复次数；无快照时显示 `snapshot=<none>` |
| `/warproject recovery snapshot` | 手动重拍（与 start 前自动拍快照完全同一路径） |
| `/warproject recovery restore` | 手动套用（与 end 后自动恢复同一路径） |

自动流程与手动指令**共用同一份快照**；三条指令都在 `/warproject` 权限门（`hasPermission(2)`）之下，命令树只解析与回执，全部经 `RecoveryApi`。

## 6. 边界与容错（已实现的行为，别再当 bug 修）

- 无快照（从未 start、文件缺失、文件损坏、版本不符）→ `game end` **不改动地图**（保留 end 的默认重置结果）并 warn；手动 `restore` 返回失败回执。
- 写盘失败（磁盘满 / 只读）→ 只记日志，**内存快照保留**，本会话仍可恢复。
- 快照本身是空地图（start 前没有划分地图）→ 严格按语义恢复成空地图；`status` 的 `nodes=0` 是唯一预警。
- 连续两次 `game end` → `transition` 因 `target == current` 直接返回，不触发回调，不会重复恢复。
- `ENDED → RUNNING` 直接 start 是允许的：快照按「本次 start 前」重拍，内容即上一局恢复后的地图。
- 恢复不涉及占领进度（内存态、不落盘）：end 时 `NodeOccupationService.clearAll()` 已清空，recovery 不重复处理。

## 7. 验收（手测清单）

1. 建 node（+战区）并 `setfaction` → `/warproject game start` → 日志出现 `pre-game map snapshot taken (N node(s), M warzone(s))`，且快照文件生成。
2. 抢下一个 node → `/warproject game end` → `/warproject node info <id>` 阵营/产出/名称回到 start 前，客户端（Xaero / FTB）颜色同步回滚。
3. **重启服务器**（不执行 start）直接 `game end` → 仍能从磁盘快照恢复（验证 `loadFromDisk` 路径）。
4. `/warproject recovery status|snapshot|restore` 三条指令各走一遍。
5. 未 start 直接 end → 只有警告，地图不被清空。

构建与部署：`./gradlew.bat build --no-daemon --offline`（本机 daemon 模式会卡死），产物覆盖到 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`；本仓库常有多 agent 并行改动，构建/部署前先确认无人正在构建。

## 8. 与既有文档的关系

- `588ee5c2-515b-4c80-9d4a-fb8d4bb766c2` 描述「ENDED 清空资源 + 全部 node 重置为 neutral」——该重置**仍然执行**，但随后被本模块的恢复覆盖为「战前地图 + 空玩家资源」。涉及 end 终态时以本文为准。
- `3d31dded-b45f-4f11-a183-0d1aa78e4faa` 的模块骨架（`WarProjectModule` / `ModuleRegistry` / SavedData 访问方式）未变，本模块沿用同一模式（Service 单例 + Api 门面 + Module 生命周期）。
- `21dce6fc-d9f3-402f-9213-91f18c0235a6` 的 `bounds` 段随快照一起备份与恢复，恢复会连同地图区域一起回到 start 前。
