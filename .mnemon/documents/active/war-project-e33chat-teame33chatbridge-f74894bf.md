---
id: "f74894bf-1239-48d1-a91e-df4159d9dbf3"
title: "war_project × e33chat 群组适配（TeamE33ChatBridge）：契约、决策与验证证据"
description: "war_project 队伍/同盟与更新后 e33chat（2.4.13 fusion：E33ChatGroupApi 接到内建 GroupManager）的适配层设计与用户决策：反射桥接、群组 id/命名规则、同盟簇合并、三处同步触发点、聊天路径移交与回退、e33chat 侧必须遵守的契约（名字净化、声明式替换、按名字成员、离线登录补员、groups_enabled 闸门、群组上限）、验证证据与已知取舍。改动 team 聊天或 e33chat 集成前先读。关联文档 3d31dded-b45f-4f11-a183-0d1aa78e4faa。"
status: "active"
created_at: "2026-09-18T18:29:31.326Z"
updated_at: "2026-09-18T18:29:31.326Z"
content_hash: "1303c7a908fd0d9cddeefaab526a26eb1c68f9c9b931ad189dcabd7167c382b8"
source_paths:
  - "src/main/java/com/flowingsun/war_project/team/TeamE33ChatBridge.java"
  - "src/main/java/com/flowingsun/war_project/team/TeamApi.java"
  - "src/main/java/com/flowingsun/war_project/team/TeamModule.java"
  - "src/main/java/com/flowingsun/war_project/team/TeamChatService.java"
  - "src/main/java/com/flowingsun/war_project/team/TeamData.java"
  - "src/main/resources/META-INF/mods.toml"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "7afc4906-b754-4edf-b31f-4f0bc3dd9922"
memory_body_ids:
  []
---

# war_project × e33chat 群组适配（TeamE33ChatBridge）

适用仓库：`E:\mc_mod_dev\War_Project`（Minecraft 1.20.1 / Forge 47.x）。
关联文档：`3d31dded-b45f-4f11-a183-0d1aa78e4faa`（War Project 架构与可选依赖集成；本文是它在 team 模块上的增量）。
外部依赖参考：本地 e33chat 仓库 `E:\mc_mod_dev\E33Chat`（融合分支基于 `upstream/main` 2.4.13）。

## 1. 背景

- war_project 原本**没有任何 e33chat 代码**；参考模组 `E:\mc_mod_dev\modernwar-town` 里才有 `MwTeamE33ChatBridge`（反射调用旧 2.1.0 的伪群组 API，owner=`modernwar`）。
- 本地 e33chat 把 2.1.0 的伪群组 API 重接到了上游 2.4.13 的**内建群组引擎**：新增 `com.niuqu.chatbubble.api.E33ChatGroupApi`（四个方法与旧版逐字一致，调用方无需重编译）与 `com.niuqu.chatbubble.server.ExternalGroupBridge`，`GroupManager` 增加 `externalId/ownerMod/memberNames` 与 7 个桥接方法，`ChatServerListener.onPlayerLogin` 增加 `ExternalGroupBridge.onPlayerLogin`（位于 history 早退之前）。
- 该更新已落到实机：`D:\mc\.minecraft\versions\totalwar\mods\e33chat-Forge-1.20.1-2.4.13.jar`（旧 2.1.0 改名 `.old`），jar 内含 `E33ChatGroupApi.class` 与 `ExternalGroupBridge.class`；同目录有 `war_project-1.0.0.jar`。

## 2. 决策记录（用户拍板，勿再询问）

1. **聊天路径 A（双通道并存）**：war_project 只负责把队伍/同盟**声明**成 e33chat 群组；玩家在 e33chat 群组页签里聊天；`/warproject team msg switch` 保留为未装 e33chat 时的回退，装有 e33chat 且群组可用时只做提示。
2. **包含同盟群组**，但按**同盟簇**（盟友关系连通分量）合并，而不是「每个有盟友的队伍一个」：`addAlly` 对称，A/B/C 三方同盟若按队建组会产生 3 个成员完全相同的群组（3 个一样的页签）并吃掉一倍群组上限。
3. **代码归属**：跨模块适配代码一律归入对应模块包（本例全部在 `com.flowingsun.war_project.team`），不散落到顶层入口 `WarProject.java`；team 包外只允许声明与文档（`mods.toml`、`docs/ARCHITECTURE.md`）。

## 3. e33chat 侧契约（适配层必须遵守）

来源：`platforms/1.20.1-forge` 的 `api/E33ChatGroupApi.java`、`server/ExternalGroupBridge.java`、`server/GroupManager.java`、`config/ChatServerConfig.java`。

- **群组名**：`MAX_NAME_LEN = 12`；`isValidGroupName` 禁空白、禁 `[]<>§`、禁首字符 `#`（留给客户端伪页签）。调用方给的 `displayName` 会被净化 + 唯一化（重名加 `-2/-3`），完全不可用时回退到 `groupId`；**groupId 才是稳定句柄**，调用方不应按名字反查。
- **声明式替换**：`replaceOwnerGroups(server, ownerModId, displayNamesByGroup, membersByGroup)` 先删除该 owner **未再声明**的群组，再 upsert —— 每次同步必须提交完整集合，不能逐个增量 upsert。
- **成员按玩家名**声明：在线者立即解析为 UUID，离线者只记名字，由 e33chat 自己的登录钩子（`resolveExternalMembers`）在下次登录补上；**调用方不要自己写补员逻辑**。
- **成员集替换而非合并**：通过 e33chat 浏览器自行入组的人会在下次声明时被移出（单一真源 = `TeamData`）。
- **闸门与上限**：`groups_enabled`（默认 true）为假时整轮注册被拒且客户端隐藏整个页签条；`group_max_count` 默认 **20 且全局共享**（含玩家自建群组），超限的 `putExternalGroup` 静默返回 false 并只记一条 `warnOnce`；`group_max_members`(50) **不被** API 路径校验。
- **消息**：纯文本（`GroupChatPacket`，1024 字符上限），API 发送不受 500ms 冷却；群不存在或发送者不是成员时返回 false 并向发送者发 `You are not in this chat group.`。
- **外部群组的 owner** 是合成 UUID `nameUUIDFromBytes("e33chat:external:<modId>")`；最后一名成员从浏览器退出会按上游 `leave()` 语义解散该群组，需要靠再次声明重建。
- **客户端页签发言不走 `ServerChatEvent`**：页签激活时客户端把输入改写成 `/e33chat group msg <群名> <文本>`（`render/ChatBubbleScreen.java`），因此与 war_project 的聊天拦截天然不冲突。

## 4. 适配层设计（team 包内）

`team/TeamE33ChatBridge.java`（纯反射，无编译期依赖；e33chat 缺失时整类 no-op）：

- 常量：`E33CHAT_MOD_ID="e33chat"`、`API_CLASS="com.niuqu.chatbubble.api.E33ChatGroupApi"`、`ENGINE_CLASS="com.niuqu.chatbubble.server.GroupManager"`、`OWNER_MOD_ID="war_project"`；group id 前缀 `war_project:team:<teamId>` / `war_project:ally:<簇锚点teamId>`；同盟显示名前缀「盟·」。
- `isAvailable()`：mod 已加载 **且** 反射探测 `GroupManager.externalGroupsAllowed()` 为真。探测失败/方法缺失返回 **false**（失败方向安全：保留 war_project 自己的队伍频道，绝不出现「页签没了、开关也被禁用」＝完全没有队伍聊天）。
- `syncAll(MinecraftServer)`：构建两张 `LinkedHashMap` 后反射调用 `replaceOwnerGroups(server, "war_project", names, members)`。**声明顺序**：先全部队伍组、再全部同盟簇组，每组内按 teamId 排序 —— `TeamData.teams()` 返回无序的 `Set.copyOf`，排序才能让 e33chat 的 `-2/-3` 后缀与上限截断在重启后保持稳定。空集合也照常调用（用于清理残留群组）。
- **同盟簇**：在仍存在的队伍上按 `Team.allies()` 求连通分量；锚点 = 簇内最小 teamId；成员 = 簇内各队成员并集；成员为空的簇跳过（e33chat 的 `leave()` 会解散空组）。
- 失败隔离：`ClassNotFoundException/NoSuchMethodException` 视为永久缺失（置 `apiMissing` 后不再重试，只记一次 INFO）；其他反射失败只记一次 WARN；任何失败都不得影响队伍命令与聊天。
- 显示名不做本地净化（避免与 e33chat 的规则重复漂移），直接交给 e33chat 处理。

同步触发点（全部在 team 包内、全部在服务端线程）：

| 触发点 | 作用 |
| --- | --- |
| `TeamApi.broadcast(server)` | 全部 9 条队伍变更命令（add/remove/empty/join/leave/admin set·remove/modify/ally add·remove）的共同出口 |
| `TeamModule.onServerStarting(server)` | 开机首次声明（幂等） |
| `TeamModule.onPlayerLoggedIn`（模块自己注册/反注册监听） | 自愈：修掉被 e33chat 浏览器退出解散或 OP 删除的外部群组 |

聊天路径（`team/TeamChatService.java`）：

- `switchChannel(player)`：群组可用时清掉该玩家的队伍频道状态、提示改用 e33chat 页签、返回 1（不再切换）。
- `onServerChat(event)`：首行守卫 `if (TeamE33ChatBridge.isAvailable()) return;` —— **不取消**事件（保持公共频道，绝不吞消息/误泄）。该路径在装有 e33chat 时不可达（`teamChatPlayers` 只能由 `switchChannel` 写入），这行是显式不变量。
- e33chat 不可用时，原有「队伍频道广播」逻辑一字未改。

`META-INF/mods.toml`：可选依赖 `modId="e33chat"`、`mandatory=false`、`ordering="AFTER"`、`side="BOTH"`、`versionRange="[2.1.0,)"`。区间取 `[2.1.0,)` 的原因：暴露该 API 的只有本地 2.1.0 fork 与融合 2.4.13+，不连续版本无法用区间表达；该条只影响加载顺序，类缺失时桥接自动降级。**未改 build.gradle、未新增 optional 源码集**（反射不需要编译期依赖，也避免对 reobf 后的 mod jar 做 fg.deobf）。

## 5. 验证证据（可复现）

- `gradlew.bat compileJava` → **BUILD SUCCESSFUL in 46s**（e33chat 不在编译类路径，证明零编译期耦合）。
- `gradlew.bat build` → **BUILD SUCCESSFUL in 39s**；产物 `build/libs/war_project-1.0.0.jar`（约 189 KB），内含 `com/flowingsun/war_project/team/TeamE33ChatBridge.class`；`processResources` 展开后的 `mods.toml` 出现 `[[dependencies.war_project]] modId="e33chat"`。
- 反射签名逐字核对：本机 PATH 无 `javap`，改用 Node 解析 class 常量池，对实机 jar 得到
  `replaceOwnerGroups(Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;)V` 与 `externalGroupsAllowed()Z`（static）——与桥接的 `getMethod("replaceOwnerGroups", MinecraftServer.class, String.class, Map.class, Map.class)` 和 `getMethod("externalGroupsAllowed").invoke(null)` 完全一致；`GroupManager` 侧同时确认 `findExternalName(String)`、`putExternalGroup(...)`、`resolveExternalMembers(ServerPlayer)`、`deliverExternal(...)` 存在。

## 6. 已知取舍与边界

- **原版（未装 e33chat）客户端**在装有 e33chat 的服务器上没有队伍聊天入口——这是聊天路径 A 的直接后果（参考模组行为一致）。若要让开关也生效：在桥接可用时把 `switchChannel` 的队伍频道投递改为经 `E33ChatGroupApi.sendGroupMessage(sender, groupId, message)`。
- 通过 e33chat 浏览器自行加入队伍群组的玩家，会在下一次声明时被移出（替换语义，`TeamData` 为单一真源）。
- 超过 50 人的队伍组不会被截断（`putExternalGroup` 不校验 `group_max_members`）。
- 群组数逼近 20 时：队伍组优先、同盟簇组其次（声明顺序），被拒的组由 e33chat 侧 `warnOnce` 记录，war_project 不据此报错。
- 实机运行时回归（群组页签出现/页签发言/同盟簇唯一/开关提示/移除 e33chat 后回退/`groups_enabled=false` 降级）**当时未执行**：需要把 jar 复制到工作区外的 `D:\mc\.minecraft\versions\totalwar\mods\` 并由人在游戏内走一遍。

## 7. 源文件

- `src/main/java/com/flowingsun/war_project/team/TeamE33ChatBridge.java`（新增）
- `src/main/java/com/flowingsun/war_project/team/TeamApi.java`、`team/TeamModule.java`、`team/TeamChatService.java`、`team/TeamData.java`（触点与数据源）
- `src/main/resources/META-INF/mods.toml`、`docs/ARCHITECTURE.md`（声明与文档）
