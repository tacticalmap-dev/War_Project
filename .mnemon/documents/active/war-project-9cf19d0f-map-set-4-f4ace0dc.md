---
id: "f4ace0dc-845d-42d6-bfff-0178f817d132"
title: "War Project 命令参数红线与登录期命令树事故：勘误 9cf19d0f（map set 已改为 4 个整数）"
description: "取代 9cf19d0f「必须自建 ArgumentType / 不需要注册 ArgumentTypeInfos」的结论，并更正 21dce6fc 规则 1 的逗号语法：自定义 ArgumentType 未注册进 ArgumentTypeInfos 时，登录期 ClientboundCommandsPacket 会抛 Unrecognized argument type → Couldn't place player in world，装该 jar 后任何存档都进不去（2026-09-20 真实事故）。含现行 /warproject map set <fromX> <fromZ> <toX> <toZ> 语法、无头验证的登录盲区、以及 MC 文件必须无 BOM 的坑。改命令参数或做指令验证前必读。"
status: "active"
created_at: "2026-09-19T20:28:51.051Z"
updated_at: "2026-09-19T20:28:51.051Z"
content_hash: "87ed830237b040d7452651ba6201bdb47681b005fde16b5f9fd81a1657a1f3b2"
source_paths:
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "docs/ARCHITECTURE.md"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
session_ids:
  - "ab8bbdd5-419e-4667-a96b-682352a957d2"
memory_body_ids:
  []
---

# War Project 命令参数红线与登录期命令树事故

**本文取代（不可再依原文执行的）内容：**

- `9cf19d0f-cc20-47fb-9e20-43f70d30c126` 第 2 节「修复：自定义 `command/ChunkPosArgument`」及其
  「凡是命令参数要传自定义格式…**必须自建 `ArgumentType`**」「**不需要**注册 `ArgumentTypeInfos`」两条结论
  —— 该做法已导致真实事故并被完全回退，`command/ChunkPosArgument.java` 已从仓库删除。
- `21dce6fc-d9f3-402f-9213-91f18c0235a6` 第 1 节规则 1 里的 `/warproject map set <fromX,fromZ> <toX,toZ>`
  写法：语义（单个矩形、区块坐标、可任意顺序、无 `map clear`、`map set` 会复位被缩小的主世界边界）**全部不变**，
  但**参数必须写成 4 个空格分隔的整数**（见第 3 节）。该文的边界渲染、红雾算法、越界执法部分不受影响。

`9cf19d0f` 里仍然有效的部分：无头端到端验证流程本身（datapack tick function 当自动敲命令器、RCON 帧必须整帧读完、
`save-all` 后 GZip 解 `run/world/data/*.dat` 搜 NBT 键）——但**必须补上本文第 4 节的盲区说明**。

环境：Forge 1.20.1（47.4.10）/ `E:\mc_mod_dev\War_Project`；部署目标
`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`；Brigadier 1.1.8。

## 1. 红线：命令参数只能用原版已注册的类型

`Commands.argument(name, type)` 的 `type` 只能用**原版已登记在 `ArgumentTypeInfos` 中**的类型：
`StringArgumentType`（word/greedyString/string）、`IntegerArgumentType` / `DoubleArgumentType` / `LongArgumentType`、
`EntityArgument`、`BlockPosArgument` / `Vec3Argument` 等。

**自定义 `ArgumentType` 光实现接口不够**：登录时服务端要用 `ClientboundCommandsPacket` 把整棵命令树序列化给客户端，
序列化按类查表，未注册的类型直接抛异常（`javap` 已确认注册表内只有 `FloatArgumentInfo`、`DoubleArgumentInfo`、
`IntegerArgumentInfo`、`LongArgumentInfo`、`StringArgumentSerializer` 等原版条目）。
若确实需要自定义格式，必须同时实现并注册 `ArgumentTypeInfo`（`ArgumentTypeInfos.registerByClass`，两端可见），
**且在确认登录路径通过之前不得发布**。

## 2. 事故复盘（症状 → 日志原文 → 原因链）

- 症状：装上某版 jar 后**任何存档都进不去**（用户原话「彻底无法进入存档」），不是崩溃，而是登录阶段被拒。
- 证据（`logs/latest.log`，totalwar 实例）：

```
[Server thread/ERROR] [ServerLoginPacketListenerImpl]: Couldn't place player in world
java.lang.IllegalArgumentException: Unrecognized argument type
com.flowingsun.war_project.command.ChunkPosArgument@1c76ae7f
```

- 原因链：`map set` 旧实现用 `StringArgumentType.word()` 收 `x,z` → `word()` 走 `readUnquotedString()`，
  字符集只有 `0-9 A-Z a-z _ - . +`，**不含逗号**，`0,0` 只读到 `0`，余下 `,0` 被判多余内容
  （`Expected whitespace to end one argument, but found trailing data`）→ 为兼容逗号自建 `ChunkPosArgument`
  → 未注册 → 登录序列化炸服。**一条指令的可用性问题被"修"成了全服不可进。**
- 教训优先级：**能进游戏 > 语法好看**。遇到参数格式冲突时，改语法（用原版类型）永远优先于引入自定义类型。

## 3. 现行语法（2026-09-20 定稿）

```
/warproject map set <fromX> <fromZ> <toX> <toZ>      四个整数（区块坐标），两角任意顺序
/warproject map info                                 只读回显当前地图区域
```

- 实现：`command/WarProjectCommands#mapCommands()` 用四次 `IntegerArgumentType.integer(-MAX_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE)`
  （`MAX_CHUNK_COORDINATE = 30_000_000`），`mapSet(...)` 用 `IntegerArgumentType.getInteger(context, "fromX"|"fromZ"|"toX"|"toZ")`；
  旧的 `parseChunkPos` 与 `ChunkPosArgument` 均已删除，`ChunkPos` import 也已移除。
- 业务校验仍在 `MapData.setBounds`：单轴跨度 ≥ 8192 区块即拒绝（消息 `Map rectangle must be smaller than 8192 chunks per axis.`）。
- 玩家侧回显：`map info` / `map set` 一律打印 `chunks [x,z] -> [x,z], blocks X[a, b) Z[a, b), N chunk(s)`
  （`chunk info` 也按 `Chunk 0,0` 显示结构，便于对照）；但**输入时逗号要换成空格**。
- 常见错误提示（实测）：缺参数 → `Unknown or incomplete command`；非整数 → `Expected integer`；
  仍写 `0,0 9,9` → `Expected whitespace to end one argument, but found trailing data`。

## 4. 验证方法学的盲区（本轮最贵的教训）

`runServer` + datapack + RCON 这套无头验证覆盖的是：**解析（function 加载期即校验语法）→ 执行 → 落盘（NBT）**。
它**不经过玩家登录**，因此**证明不了命令树能被序列化发送**——上面那起"进不去存档"的事故就是在无头验证全绿之后发生的。

- 登录安全的可静态确认判据：**命令树里所有 `Commands.argument(...)` 的类型都是原版类型**。
  做法：对源码扫 `Commands.argument\("[^"]+",\s*([A-Za-z0-9_.]+)` 取出唯一类型集合，确认只出现
  `StringArgumentType.*` / `IntegerArgumentType.*` / `DoubleArgumentType.*` / `EntityArgument.*` 等白名单项。
  公布 jar 前跑一次，成本几乎为零。
- 若必须验证真实登录路径，无头服务端无法胜任（没有客户端登录握手）；替代手段只有让真人进一次存档，
  或把序列化本身纳入测试。不要用「控制台能跑通」代替「玩家能进来」。

## 5. 附加坑：给 Minecraft 写文件必须无 BOM

本轮两次被 BOM 挡住（都是 `Set-Content -Encoding UTF8` 在 Windows PowerShell 兼容层下写出 `EF BB BF` 所致）：

- `run/eula.txt` 带 BOM → 服务端直接拒绝启动：`You need to agree to the EULA in order to run the server.`
- datapack 的 `.mcfunction` 带 BOM → 函数加载失败：
  `Failed to load function <ns>:<fn>` / `Whilst parsing command on line 1: Unknown or incomplete command … at position 0`
  （第 0 列就报错 = 典型 BOM 症状）。

写法：`[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`。
排查写法：读前 3 字节，正常应为 `65 75 6C`（`eula`）/ `77 61 72`（`war`），而不是 `EF BB BF`。

## 6. 落地文件与相关文档

- 现行实现：`src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java`；
  规则说明已同步 `docs/ARCHITECTURE.md`（指令表内注明了「只能用 Brigadier 自带参数类型」与该事故）。
- 已删除：`src/main/java/com/flowingsun/war_project/command/ChunkPosArgument.java`（不要再恢复）。
- 边界规则与渲染实现：`21dce6fc-d9f3-402f-9213-91f18c0235a6`（本文只更正其规则 1 的语法写法）。
- 无头验证流程与 RCON 帧细节：`9cf19d0f-cc20-47fb-9e20-43f70d30c126`（保留，但按本文第 4 节补盲区）。
- 验证产物哈希记录：修复版 jar 594610 字节，SHA256 `712F9B45C227395FF4E060E8547C8B1BC240C0D677CB95846661842C2E5568FA`
  （`build/libs` 与 totalwar 部署副本一致，且 jar 内已无 `ChunkPosArgument`）。
