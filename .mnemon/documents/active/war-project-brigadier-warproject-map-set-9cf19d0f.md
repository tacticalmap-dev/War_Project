---
id: "9cf19d0f-cc20-47fb-9e20-43f70d30c126"
title: "War Project 指令可用性验证法 + Brigadier 参数陷阱：/warproject map set 从不可用 → 修复"
description: "两条可复用知识：(1) Brigadier 的 StringArgumentType.word() 只接受 0-9 A-Z a-z _ - . +（不含逗号），所以 /warproject map set 0,0 9,9 从最初那版起永远失败（Expected whitespace to end one argument），修复为自定义 command/ChunkPosArgument；(2) 无头端到端验证流程——runServer + datapack 的 tick function 当自动敲命令器（加载期即校验语法）+ RCON 帧协议（登录帧必须读完否则读取错位）+ save-all 后 GZip 解 run/world/data/*.dat 搜 NBT 键验落盘。含真实回显证据。要改任何命令参数或需真机验证指令/落盘时读本文；边界规则本身见 21dce6fc。"
status: "active"
created_at: "2026-09-19T20:19:43.162Z"
updated_at: "2026-09-19T20:19:43.162Z"
content_hash: "7808b5a45cec539c781628e173f1262da81bca9f145ac4f445bf8f8a9c3eaadb"
source_paths:
  - "src/main/java/com/flowingsun/war_project/command/ChunkPosArgument.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "0142d792-5af5-4e1e-b9c2-b472b59deac5"
memory_body_ids:
  []
---

# War Project 指令可用性验证法 + Brigadier 参数陷阱

本文记录两件跨任务可复用的事：**命令参数遇到自定义格式（逗号等）时必须自建 ArgumentType**，以及
**不必开图形客户端也能真机验证指令与落盘**的流程。地图边界规则与渲染本身不在本文范围：见
`21dce6fc-d9f3-402f-9213-91f18c0235a6`（地图边界子系统）；Xaero 叠加层另见 `dbb656b9-3bb8-4211-ad68-f2bba70b49b2`。

环境：Forge 1.20.1 / `E:\mc_mod_dev\War_Project`；部署目标 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。
Brigadier 版本 1.1.8（`com.mojang:brigadier`，与 MC 1.20.1 同捆）。

## 1. 根因：`word()` 不吃逗号（有日志原文为证）

`/warproject map set <fromX,fromZ> <toX,toZ>` 的坐标是 `x,z` 形式，但旧实现用
`Commands.argument("from", StringArgumentType.word())` + `raw.split(",", 2)` —— 两者天生冲突：

- `StringArgumentType.word()` 走 `StringReader.readUnquotedString()`，其字符集是
  `0-9 A-Z a-z _ - . +`，**不含逗号**：读到 `0` 就结束该参数，剩下的 `,0` 被判为多余内容。
- 失败发生在**解析期**，所以命令从来没执行过（不是"写错了参数"，是这条指令一直不可用）。

无头服务端里同样的输入给出的原文（这也是最省事的复现方式）：

```
Failed to load function warprobe:auto
java.lang.IllegalArgumentException: Whilst parsing command on line 4:
Expected whitespace to end one argument, but found trailing data at position 20: ... map set 0<--[HERE]
```

注意此处暴露一个通用规律：**`word()` 之后的失败点刚好落在分隔符上**时，报的就是
`Expected whitespace to end one argument`；`greedyString()` 不能用于多参数（它吃掉整行余下部分），
`string()` 不带引号时同样走 `readUnquotedString`，只有写成 `"0,0"` 才行（体验差，已否决）。

## 2. 修复：自定义 `command/ChunkPosArgument`

文件：`src/main/java/com/flowingsun/war_project/command/ChunkPosArgument.java`（新建），
调用点：`command/WarProjectCommands.java` 的 `mapCommands()` 与 `mapSet(...)`（旧的 `parseChunkPos` 已删除）。

实现要点（已按 Brigadier 1.1.8 的方法签名核对）：

- `parse(StringReader)`：从 `getCursor()` 起 `while (canRead() && peek() != ' ') skip()`，
  把整段 token（含逗号）取出后自行 `indexOf(',')` 切分；两个分量 `Integer.parseInt`，失败或
  绝对值 > 3e7 一律抛错。
- 错误用 `new SimpleCommandExceptionType(Component.literal("Chunk coordinate must be written as x,z (for example 0,0 or -63,-63)"))`
  的 `createWithContext(reader)` —— 玩家在聊天栏看到的是**这一句**，而不是 `Command exception` 之类的日志式报错。
- `listSuggestions(...)` 返回 `Suggestions.empty()`、`getExamples()` 返回 `List.of("0,0","-63,-63","10,10")`。
- **不需要**注册 `ArgumentTypeInfos`：补全由服务端 `ServerboundCommandSuggestionPacket` 路径处理，
  客户端不会本地重建参数类型（本模组是纯服务端命令，`,` 也无需引号）。

可复用结论：**凡是命令参数要传自定义格式（逗号、分号、坐标对、`key=value`），必须自建 `ArgumentType`；
`word()` 只能用于纯标识符。** 这条已同步记入 ARCHITECTURE 的指令表，防止被改回 `word`。

## 3. 无头端到端验证流程（可整段照搬）

目的：在没有图形客户端、也不让玩家上线的情况下，证明"命令能被解析 → 能执行 → 数据能落盘"。

1. **准备 dev 服务端**：`run/eula.txt`（`eula=true`）+ `run/server.properties`。
   为省时间用超平坦世界（`level-type=minecraft:flat`）、`online-mode=false`、`view-distance=4`；
   需要读命令回显就加 `enable-rcon=true`、`rcon.port=25575`、临时密码（探测完删掉配置文件即可）。
2. **启动**：`.\gradlew.bat runServer --no-daemon --offline --console=plain *> build\server-probe.log`
   （后台作业；首次含世界生成，本次约 3 秒生成 + 数十秒启动）。
3. **用 datapack 当"自动敲命令器"**：
   `run/world/datapacks/<ns>/pack.mcmeta`（`pack_format: 15`）、
   `run/world/datapacks/<ns>/data/<ns>/functions/auto.mcfunction`、
   `run/world/datapacks/<ns>/data/minecraft/tags/functions/tick.json` → `{"values":["<ns>:auto"]}`。
   关键性质：**function 内的命令在加载期就走完整 Brigadier 解析**，所以语法/参数类型错误会立刻变成
   `Failed to load function <ns>:<fn>` + 精确列号 —— 这是校验命令签名最快的手段（无需任何客户端）。
   幂等命令可以安全地挂在 tick 上反复执行。
4. **RCON 逐条打真实命令**：帧格式 `[int32 length][int32 requestId][int32 type][body][0x00 0x00]`，
   `length = 4 + 4 + len(body) + 2`，小端；`type`：3 = 登录、2 = 命令、0 = 响应；登录与命令**共用同一连接**。
   解析响应的 body 从 offset 8 起、长度 `length - 10`。
   **踩过的坑**：登录响应 `length == 10`（无 body），如果此时"提前 return 空串"而不把 10 字节读完，
   残留字节会让同一连接后续每次读取都错位，表现为"命令明明执行了但回显全空"。**任何长度的帧都必须读完**。
5. **验证落盘**：发 `save-all`，然后 GZip 解压 `run/world/data/war_project_map.dat`，
   在解出的字符串里搜 `bounds` / `min_chunk_x` / `max_chunk_x` 等 NBT 键名即可确认 SavedData 真写盘
   （SavedData 平时每 6000 tick 才自动保存，所以必须显式 `save-all` 或优雅停服，否则强杀看不到文件）。
6. **收尾**：删掉 `run/world`、`run/eula.txt`、`run/server.properties` 与临时探测脚本，恢复 run 目录原状
   （它同时是图形客户端的运行目录，客户端存档在 `run/saves`，与 dev 服务端的 `run/world` 不冲突）。

## 4. 修复后的真机回显（验收证据）

| 输入 | RCON 返回原文 |
| --- | --- |
| `warproject map set 0,0 9,9` | `Map area set: chunks [0,0] -> [9,9], blocks X[0, 160) Z[0, 160), 100 chunk(s)` |
| `warproject map set 12,12 30,30` | `Map area set: chunks [12,12] -> [30,30], blocks X[192, 496) Z[192, 496), 361 chunk(s)` |
| `warproject map set -63,-63 62,62` | `Map area set: chunks [-63,-63] -> [62,62], blocks X[-1008, 1008) Z[-1008, 1008), 15876 chunk(s)`（负数解析正确） |
| `warproject map info` | `Map area: chunks [-63,-63] -> [62,62] … 15876 chunk(s)` |
| `warproject chunk info` | `Chunk 0,0 node=none warzone=none` |
| `warproject map set abc` | `Chunk coordinate must be written as x,z (for example 0,0 or -63,-63)` |
| `warproject map set zz,1 2,2` | 同上，并标出 `...p set zz,1 2,2<--[HERE]` |
| `warproject map set 0,0 99999,99999` | `Map rectangle must be smaller than 8192 chunks per axis.`（MapData 的业务校验） |
| 存档 `war_project_map.dat` | NBT 内确认出现 `bounds` 与 `min_chunk_x/min_chunk_z/max_chunk_x/max_chunk_z` |

`/warproject map`（不带子命令）返回 Brigadier 标准的 `Unknown or incomplete command`，属正常行为（补全会列出 `set`/`info`）。

## 5. 记忆点

1. 命令参数 = 自定义格式 → 自建 `ArgumentType`，别用 `word()`。
2. 想验证指令语法又不想开客户端 → datapack 的 tick function 是最轻的"语法探针"，报错带精确列号。
3. 想验证"数据真的写进存档" → `save-all` 必须显式发（或优雅停服），然后 GZip 解 `.dat` 搜 NBT 键。
4. RCON 读取必须按帧长读满，登录帧（10 字节）也不例外。
