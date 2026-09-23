---
id: "cba46a36-c280-4cd4-b2fe-5e898717d702"
title: "War Project：原版 1.20.1 发包路径取证与「每 tick 发包数」可配置改造设计（未实现）"
description: "Forge 1.20.1 原版 Connection 发包链路的源码/javap 取证（官方名↔SRG 名、唯一 poll 注入点、调用者），以及「每连接每 tick 从发送队列 drain 上限（maxPacketsSentPerTick，默认 0=原版）」的 Mixin 设计、首次混入 MC 类所需的 MixinGradle+refmap 前置与离线兜底、验证与部署步骤。状态：方案已提交、被驳回先讨论，尚未实现。"
status: "active"
created_at: "2026-09-23T04:38:22.326Z"
updated_at: "2026-09-23T04:38:22.326Z"
content_hash: "fbe9e2c85e50e56dd46c5ebf95f8c5323664f468bb5ec0e7bd9a791a06a7adbf"
source_paths:
  - "build.gradle"
  - "src/main/resources/mixins.war_project.json"
  - "src/main/java/com/flowingsun/war_project/Config.java"
session_ids:
  - "e72e2307-1e30-4ffd-bad4-0c6f788c1ff6"
memory_body_ids:
  []
---

# War Project：原版发包逻辑可配置化（每 tick 发包数）

仓库 `E:\mc_mod_dev\War_Project`（MC 1.20.1 / Forge 47.4.10，modid `war_project`）；构建产物部署目标 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。
相关既有文档：`3d31dded-b45f-4f11-a183-0d1aa78e4faa`（模组架构与网络同步总览）。

**状态：设计已成型但被驳回先讨论，代码尚未实现。** 继续前先读本文第 1–3 节（取证）与第 4 节（前置工程）。

## 0. 用户诉求与范围

用户原话：「对 mc 原版的发包逻辑进行优化，使其可以在配置文件内修改发包数」。

澄清问题后用户明确选定目标：**服务端连接发包队列（原版无每 tick 上限）**，即 `net.minecraft.network.Connection` 的发送队列 drain —— 不是玩家移动包限速。

## 1. 取证：原版 1.20.1 发包链路（全部实读源码/javap，非推测）

证据源（本机 gradle 缓存）：

- 反编译源：`~/.gradle/caches/forge_gradle/minecraft_user_repo/mcp/1.20.1-20230612.114412/joined/decompile/output.jar` → `net/minecraft/network/Connection.java`（SRG 名）
- 官方（Mojang）映射：`.../mcp_config/1.20.1-20230612.114412/joined/downloadClientMappings/client_mappings.txt`（第 37859 行起是 `net.minecraft.network.Connection -> sd:` 段）
- 生产 SRG 字节码：`~/.gradle/caches/forge_gradle/mcp_repo/net/minecraft/joined/1.20.1-20230612.114412/joined-1.20.1-20230612.114412-srg.jar`（用 `javap -p -c`）

| 官方名 | SRG 名 | 行为 | 证据 |
| --- | --- | --- | --- |
| `Connection#flushQueue()` | `m_129544_` | `synchronized(queue){ while((holder = queue.poll()) != null) doSendPacket(...) }` —— **取空为止，无任何数量上限** | Connection.java ≈243–253；client_mappings `242:253:void flushQueue() -> q` |
| `Connection#tick()` | `m_129483_` | 先 `flushQueue()`，tick 监听器，末尾 `channel.flush()`；每 20 tick 采样速率 | Connection.java ≈255–273；client_mappings `256:273:void tick() -> a` |
| `Connection#send(Packet, PacketSendListener)` | `m_243124_` | 已连接时**先 flushQueue，再直接 `doSendPacket` 当前包**；未连接才入队 | Connection.java ≈183–190 |
| 唯一 `poll` 调用点 | — | `m_129544_` 字节码偏移 31：`invokeinterface java/util/Queue.poll:()Ljava/lang/Object;`（全方法唯一） | javap 输出，见下 |

javap 关键片段（srg jar）：

```
private void m_129544_();          // 第 412 行
   ...
   27: aload_0
   28: getfield  #76   // Field f_129467_:Ljava/util/Queue;
   31: invokeinterface #448, 1   // InterfaceMethod java/util/Queue.poll:()Ljava/lang/Object;
   36: checkcast #8    // class net/minecraft/network/Connection$PacketHolder
   41: ifnull 59
   53: invokevirtual #354  // Method m_129520_: (Packet;PacketSendListener)V
   56: goto 27
public void m_129483_();           // 第 455 行
    1: invokevirtual #351  // Method m_129544_:()V
```

调用者：`net/minecraft/server/network/ServerConnectionListener.java:139` 每 tick 遍历 `connections` 调 `connection.m_129483_()`（登录中/已连接的连接都在列表内，登录期也照常 tick）。

**被排除的候选**（用户未选，记录备查）：`ServerGamePacketListenerImpl#handleMovePlayer` 中的 `++receivedMovePacketCount; int i = received - known; if (i > 5) { debug("sending move packets too frequently"); i = 1; }`，随后用 `i` 参与 `moved too quickly` 回弹判定（反编译源同 jar 的 `ServerGamePacketListenerImpl.java:877-895`）。若将来要做「每 tick 移动包阈值可配置」，改这里。

## 2. 问题本质

一 tick = 50ms，而「取包 → 序列化 → 加密 → 写 netty」在服务端主线程串行完成。原版把该 tick 内积攒的**全部**包一次性推出，因此大战场场景（区块加载 + 实体追踪更新 + 本模组同步包同一瞬间涌出）会出现：服务端单 tick 耗时尖峰（MSPT 抖动）、玩家端瞬间被灌一大批包。上限化后同样的包量摊到多个 tick，只改**节奏**，不减少包数、不省带宽。

## 3. 设计方案

### 3.1 配置项（提议命名）

`Config.java` 新增（现为 `ModConfig.Type.COMMON`，服务端/客户端各读自己那份）：

```java
.defineInRange("maxPacketsSentPerTick", 0, 0, 1_000_000);   // 0 = 原版不限流
public static int maxPacketsSentPerTick;                    // 默认 0，配置加载前即原版行为
```

注释需写明：0 = 原版；正值把队列摊到多 tick；**只延迟、不丢包**；过小会让所有玩家延迟累积，256–1024 是合理区间。

### 3.2 Mixin（主源集，双端通用）

`src/main/java/com/flowingsun/war_project/mixin/net/ConnectionSendLimitMixin.java`：

- `@Mixin(Connection.class)`
- `@Inject(method = "tick", at = @At("HEAD"), require = 0)` → `warProject$packetsThisTick = 0`（每 tick 重置预算；`@Unique int` 字段）
- `@Redirect(method = "flushQueue", at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;"), require = 0)` →
  预算耗尽返回 `null`（原版 `while` 自然提前结束），否则 `counter++` 后 `queue.poll()`
- **不采用** `@Inject(HEAD, cancellable) + @Shadow` 重写整个 `flushQueue`：那要触碰 private 字段 `queue`、private 内部类 `Connection$PacketHolder`、private `doSendPacket`，风险更高
- `require = 0` 沿用项目「可选兼容静默降级」约定：注入失败即回落到原版行为
- 不用 `AtomicInteger`：`flushQueue`/`tick` 均在主线程驱动（`synchronized(queue)` 内），最坏只是计数瞬时偏差，不丢包
- 首次真正触发限流时打一条一次性 INFO，便于确认配置生效；`limit <= 0` 时零日志

### 3.3 语义与边界（必须写进注释/文档）

- 粒度：**每连接、每 `Connection.tick()`**；同一 tick 内 `send()` 触发的 `flushQueue()` 也消耗同一预算 → 每 tick 从队列取出的总数 ≤ N
- 不丢包：超预算的 `PacketHolder` 留在 `ConcurrentLinkedQueue`，下一 tick 继续；不引入队列容量上限、不改排序
- 不受限路径：`send(...)` 的直接写（登录/断开/传送等关键包）不受预算影响 → 极端情况「该 tick 实际发出 = N + 该 tick 显式直接发送数」
- 运行期改配置即时生效（`ModConfigEvent` 刷新静态字段），无需重启
- 已知取舍（非目标）：不做「队列积压超阈值自动放开」、不按包类型/玩家差异化限流

## 4. 前置工程：项目首次混入 MC 自己的类

**现状**：`mixins.war_project.json` 的 `"mixins": []`，只有 5 个 Xaero 客户端 Mixin 且全部 `remap = false`（非 MC 目标）；`build.gradle` 无 mixin 插件、无注解处理器、无 refmap。因此对 `net.minecraft.network.Connection` 注入时，注解里写官方名 `flushQueue`/`tick`，**必须**有 refmap 才能在生产 SRG 环境生效，否则 `require = 0` 会把它静默吞掉。

需要补齐（同机 `E:\mc_mod_dev\SuperbWarfare` 的可用范式）：

```gradle
plugins { id 'org.spongepowered.mixin' version '0.7.38' }   // 固定版本，避免 0.7.+ 对离线元数据的要求
mixin { add sourceSets.main, 'mixins.war_project.refmap.json'; config 'mixins.war_project.json' }
dependencies { annotationProcessor 'org.spongepowered:mixin:0.8.5:processor' }
runs { configureEach {
    property 'mixin.env.remapRefMap', 'true'
    property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
} }
```

`mixins.war_project.json`：加 `"refmap": "mixins.war_project.refmap.json"`，`"mixins"` 数组加 `"net.ConnectionSendLimitMixin"`；`required:false` / `injectors.defaultRequire:0` / `client` 段保持不变。

**离线可行性证据**（本机缓存已命中，`--offline` 有希望）：`org.spongepowered/mixin/0.8.5/{mixin-0.8.5.jar, mixin-0.8.5-processor.jar}`、`org.spongepowered/mixingradle/0.7.38/mixingradle-0.7.38.jar`（该 jar 内含 `META-INF/gradle-plugins/org.spongepowered.mixin.properties`）、插件 marker 描述符 `modules-2/metadata-2.106/descriptors/org.spongepowered.mixin/org.spongepowered.mixin.gradle.plugin/0.7.38`。

**若离线解析插件 marker 失败，两级兜底**：① `settings.gradle` 的 `pluginManagement.resolutionStrategy.eachPlugin` → `useModule('org.spongepowered:mixingradle:0.7.38')`；② 退到 `buildscript { dependencies { classpath 'org.spongepowered:mixingradle:0.7.38' } }; apply plugin: 'org.spongepowered.mixin'`，或仅首次允许联网解析。

## 5. 验证与部署

1. 构建：`.\gradlew.bat build --no-daemon --offline`（本机 daemon 会卡死；后台作业 + 日志重定向，5 分钟无输出视为卡死）
2. 产物核对：`build/tmp/compileJava/mixins.war_project.refmap.json` 含 `flushQueue → Lnet/minecraft/network/Connection;m_129544_()V`、`tick → Lnet/minecraft/network/Connection;m_129483_()V`；jar 内含 refmap + Mixin 类 + 更新后的 `mixins.war_project.json`
3. 运行期抽查（慢，可选）：`.\gradlew.bat runServer --no-daemon`，控制台无 Mixin 错误
4. 部署：**先检查是否有其他 agent 正在构建/同步**，再覆盖 `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`
5. 人工验收：`config/war_project-common.toml` 设 N（如 512）观察大战场 tick 尖峰收敛；`0` 时与原版一致

## 6. 未决项（需用户确认）

- 默认值：0（=原版，安全）还是直接给正值默认生效
- 配置项命名 `maxPacketsSentPerTick` 是否采纳
- 是否也要限制 `send()` 的直接发送；是否加「队列积压安全阀」
- 是否接受为此外入 MixinGradle 插件（会改动 `build.gradle`，影响并行构建的其他 agent）
