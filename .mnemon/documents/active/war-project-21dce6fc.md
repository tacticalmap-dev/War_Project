---
id: "21dce6fc-d9f3-402f-9213-91f18c0235a6"
title: "War Project 地图边界子系统：世界边界退场 → 黄色边界墙 + 地图界外红雾 + 越界处决"
description: "用户定调的地图区域规则与落地依据：Bounds 数据层（MapData NBT 段、主世界限定、半开区间）、世界里自绘黄色条纹墙（状态与顶点逐条对齐 LevelRenderer.renderWorldBorder 字节码）、Xaero 世界地图（轴对齐 4 条）与旋转小地图（4 角投影 + 逐行扫描）的界外红雾两种算法、越界计时与 kill 的服务端权威设计（协议号 7 的 OutOfMapWarningPacket + 客户端本地倒数）。改边界渲染或越界规则前读本文；Xaero 叠加层几何口径另见 5ea0e65d 与 dbb656b9。"
status: "active"
created_at: "2026-09-19T19:10:45.460Z"
updated_at: "2026-09-19T19:10:45.460Z"
content_hash: "3ec4bd5a27d0d8953b985542dff5378dcb4e23c8e17d0cdd1b2fff3adc0244b2"
source_paths:
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/map/MapBoundaryService.java"
  - "src/main/java/com/flowingsun/war_project/map/MapDivideStateApi.java"
  - "src/main/java/com/flowingsun/war_project/map/MapDivideModule.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/Config.java"
  - "src/main/java/com/flowingsun/war_project/client/ClientMapState.java"
  - "src/main/java/com/flowingsun/war_project/client/OutOfMapClientState.java"
  - "src/main/java/com/flowingsun/war_project/client/OutOfMapHudOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/MapBoundaryRenderer.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapOverlay.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "3244defe-3747-4ce9-ba33-7def5ed0bc8c"
memory_body_ids:
  []
---

# War Project 地图边界子系统（2026-09-20 落地）

本文记录「地图指令改造」这一轮定下的规则、实现依据与踩点，供后续改动边界渲染或越界规则时参照。
Xaero 叠加层的线条/颜色口径不在本文范围：见 `5ea0e65d-6677-419e-9263-916413603f03`（小地图 FBO 绘制）与
`dbb656b9-3bb8-4211-ad68-f2bba70b49b2`（世界地图 1x 缩放等）；`d33309cb` 与 `dbb656b9` 中关于小地图裁形的旧描述已被
`5ea0e65d` 取代，但颜色与「node 实线 / 战区虚线」规则仍然有效。

部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`；参考模组蓝本：`E:\mc_mod_dev\modernwar-town`
（其 `ModernWarCommands#setWorldBorderFromRange` 正是本轮要淘汰的「世界边界当硬边界」做法）。
字节码依据来自 `forge-1.20.1-47.4.10_mapped_official_1.20.1.jar`（`javap` 反汇编）。

## 1. 现行规则（用户定调，勿再询问、勿擅自改动）

1. 地图区域 = **单个矩形**，用**区块坐标**定义：`/warproject map set <fromX,fromZ> <toX,toZ>`，两角可任意顺序（内部归一化）。
   `/warproject map info` 只读回显。没有 `map clear`（只能被新的 `map set` 覆盖），与「删除入口只留 `node delete`」的收敛风格一致。
2. **不再使用原版世界边界作为硬性边界**：玩家可以走出去，代价是倒计时处决。
   `map set` 时若发现主世界 `border.getSize() < WorldBorder.MAX_SIZE`，会把它 `setCenter(区域中心)` + `setSize(WorldBorder.MAX_SIZE)`
   并居中于地图区域，成功消息里注明。这是本模组唯一一次触碰原版边界。
3. 界外提示分三处：世界里**黄色**条纹墙（纹理同原版世界边界）、Xaero **世界地图与小地图都**画界外红雾、
   准星下方红字倒计时（用户在三选一中选了「世界地图和小地图都画」）。
4. 越界惩罚**始终生效**（不与 `GameStateService` 阶段耦合），但**只判主世界坐标**，且**创造/旁观模式豁免**（用户选定）。
5. 超时秒数可配置：`Config.outOfMapReturnSeconds`，默认 30.0，范围 1..3600。
6. node / 战区 / 占领 / 资源规则完全不受本轮影响。

## 2. 数据层

- `map/MapData`：新增 `private Bounds bounds`（可空）与 `public record Bounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)`。
  持久化在 `war_project_map` 的 `bounds` 段（键 `min_chunk_x/min_chunk_z/max_chunk_x/max_chunk_z`），随 `save()` 与 `clientSnapshot()` 一起走。
  `setBounds(...)` 归一化两角、拒绝单轴跨度 ≥ `MAX_BOUNDS_CHUNKS`(8192) 的矩形（防止误输入把客户端投影撑爆）；`normalize()` 会**丢弃 min>max 的非法矩形**
  （手改档或截断档），避免地图「里外颠倒」。
- `Bounds.containsBlock(double x, double z)` 用**半开区间** `[minBlock, maxBlock)`（`minBlock = chunk*16`，`maxBlock = (chunk+1)*16`），
  所以远端边界线算界外；坐标全用 double，避免整数边界歧义。
- `map/MapDivideStateApi`：`getBounds(server)` / `setBounds(server, ...)`，写成功即 `broadcastMap(server)`（沿用门面惯例）。
- `client/ClientMapState`：新增 `ClientBounds` 与可空 `bounds()`，在 `replace(snapshot)` 里读 `bounds` 段；缺段 = 未设置（旧档兼容）。
- 未设置地图区域时：不画红雾、不画黄墙、不执法——`map set` 之前一切行为与改造前完全一致。

## 3. 世界里黄色边界墙（`client/MapBoundaryRenderer`）

**依据：`LevelRenderer.renderWorldBorder` 的字节码逐条核对结果（照抄状态机，只换颜色与几何来源）**

| 环节 | 原版做法（字节码） | 本实现 |
| --- | --- | --- |
| 纹理 | `FORCEFIELD_LOCATION = new ResourceLocation("textures/misc/forcefield.png")` | `ResourceLocation.withDefaultNamespace("textures/misc/forcefield.png")`（等价） |
| 混合 | `enableBlend` + `blendFuncSeparate(SRC_ALPHA, ONE, ONE, ZERO)`（加法混合） | 同 |
| 深度 | `depthMask(false)`（不写深度），`enableDepthTest` 保持 | 同 |
| 剔除 | `disableCull()` … 绘制 … `enableCull()` | 同（故墙体双面可见，从界外也看得到） |
| 着色 | `RenderSystem.setShaderColor(...)`，颜色来自 `BorderStatus.getColor()` | 黄色 `(1.0, 0.82, 0.25, 0.65)` |
| 着色器 | `setShader(GameRenderer::getPositionTexShader)` + `setShaderTexture(0, forcefield)` | 同 |
| 顶点 | `buffer.begin(QUADS, DefaultVertexFormat.POSITION_TEX)` → `BufferUploader.drawWithShader(buffer.end())` | 同 |
| 坐标 | **相机相对**：`vertex(borderMinX − cameraX, …, z − cameraZ)` | 同：`(world − Camera.getPosition())` + `event.getPoseStack().last().pose()` |
| UV | 沿墙与世界坐标成 0.5/格（每 2 格重复一次纹理），叠加 `Util.getMillis()` 的滚动量 | 0.5/格 + 3s 周期滚动 |

- 触发：`RenderLevelStageEvent`（`bus = FORGE`）且仅 `Stage.AFTER_TRANSLUCENT_BLOCKS`；只在 `Minecraft.level.dimension() == Level.OVERWORLD`
  且有 `bounds` 时绘制。四面向外各一个 quad，高度取 `level.getMinBuildHeight()`..`getMaxBuildHeight()`。
- **风险点（首帧目视确认）**：顶点采用相机相对坐标是据原版字节码推断的约定。若实机看到墙体整体偏移一个相机位移，
  说明该阶段 `poseStack` 已含平移，改为 `pose.pushPose(); pose.translate(-cam.x, -cam.y, -cam.z)` 并用世界坐标顶点即可。
- 可调项：颜色/alpha；墙高范围（现为世界最低到最高建筑高度）。

## 4. 地图界外红雾（两张图两种算法）

- **世界地图**（`XaeroWorldMapScreenOverlay#drawOutOfBoundsShade`）：世界地图轴对齐，直接算屏幕矩形，画上/下/左/右 4 条
  `addQuad`，坐标 `clamp` 到屏幕内；**在同一个 `POSITION_COLOR` 批里最先画**，使战区填充、node 实线、名称都压在红雾之上。
  地图区域整片移出屏幕时，4 条自然退化为「整屏红」。颜色 `0x38FF3B30`。
- **小地图**（`XaeroMinimapOverlay#drawOutOfBoundsShade`）：小地图视图**随玩家朝向旋转**，矩形会投成旋转四边形，轴对齐矩形条不适用。
  做法：用 `MinimapProjection.project(renderPos, worldX, worldZ)` 投 4 角 → 对 viewport 每行求「行内区间」`[L,R]`（4 条边与行中线的交点取极值）
  → 两侧剩余为界外；无交集时令哨兵 `insideX = xe + 1` 表示「整行界外」；视口完全看不到矩形时退化为一次 `fillClipped` 全屏填充。
  颜色 `0x44FF3B30`（小地图更小，略高不透明度）。
- 小地图红雾**总是画在屏幕层**，不放进 `if (!XaeroMinimapFramebufferOverlay.isActiveRecently())` 分支：FBO 路径画的是地图纹理内的几何，
  屏幕层遮罩天然盖在其上，两条路径都不会重复绘制。副作用：界外的 node 线会被红雾盖住（语义上可接受）。
- 屏幕层进入时 pose 已在「小地图中心」坐标系，`graphics.fill(xs, y, xe, y+1, color)` 直接使用局部坐标（与既有 `fillClipped` 同一约定）。

## 5. 越界执法与协议

- `map/MapBoundaryService`（挂 FORGE 总线，由 `MapDivideModule.onServerStarting` 注册、`onServerStopping` 反注册 + `clearActive()`）：
  `TickEvent.ServerTickEvent` 的 `Phase.END` **每 tick**（不是 20 tick 节流，倒计时与处决需要 tick 精度）。
  判定顺序：无 `bounds` → 不判定；`isDeadOrDying()` / `isCreative()` / `isSpectator()` / 非主世界 → 不判定（并清除计时）；
  否则 `!bounds.containsBlock(x, z)` 即界外。
- 状态机 `Map<UUID, Integer> outSinceTick`：
  - **首个越界 tick 只发包、不处决**（`sendOutOfMapWarning(player, graceTicks)`），避免踏入瞬间被秒杀；
  - 持续越界**不再发包**（客户端本地倒数）；
  - `now - since >= graceTicks` → `player.kill()`（等价 `/kill`，无额外伤害源与聊天消息，`KillCommand` 也未发消息）→ 随后清状态，
    因此死亡动画期间不会连环计数；
  - 回到区内 / 切维度 / 变成创造或旁观 / 地图区域被覆盖清除 → 发 `OutOfMapWarningPacket(-1)` 并移出 map；
  - 玩家下线：每 tick `outSinceTick.keySet().retainAll(在线 UUID)` 兜底回收（不依赖登出事件）。
- 客户端：`client/OutOfMapClientState` 用 `Util.getMillis()` 记 `deadlineMillis`（**不依赖客户端 tick 计数**，避免依赖 client level 的 gameTime），
  `remainingSeconds()` 向上取整且 active 时不低于 1；`client/OutOfMapHudOverlay` 用
  `registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "war_project_out_of_map", ...)` 在准星下方 12px 居中画红字
  「请在 Ns 内返回地图区域」，先四向黑色描边再画 `0xFFFF4444` 正字（雪地/天空/熔岩上都可读）；`options.hideGui`、`player == null`、
  `screen != null` 时不画（与 `ResourceHudOverlay` 的门控一致）。
- 网络：`net/WarProjectNetwork` 新增 S→C 包 `OutOfMapWarningPacket(int remainingTicks)`（`-1` = 清除），
  **协议号 `6` → `7`**（包集合变化时提升，让旧客户端明确被拒而不是撞未知包 id）。
- 配置：`Config.outOfMapReturnSeconds` 为 COMMON 配置，**服务端是唯一权威**；客户端只用包里的剩余 tick 倒数，不读自己的配置。

## 6. 易踩点与已知限制

- 地图区域**只属于主世界**：下界/末地既不受越界限制也不渲染黄墙；地图区域的维度不可配置（若将来需要，须同时改判定、渲染与协议）。
- `map set` 会改动原版主世界边界（仅当它被缩小过），这是「不再使用硬边界」的落地动作；若希望模组完全不碰原版边界，去掉
  `WarProjectCommands#clearHardWorldBorder` 即可（改为在消息里提示手动 `/worldborder set 59999968`）。
- 小地图红雾逐行 `graphics.fill`：矩形视口下会合并成极少次，圆形视口最坏约每行 2 次（与既有「每 chunk 一次 fill」同量级）。
- 旧客户端/旧服务端混用会被协议号拒绝；单人游戏不受影响。
- `bounds` 缺段 = 未设置，切忌把「未设置」当成「全世界都是界外」。

## 7. 验收与交付

- 构建：`.\gradlew.bat build --no-daemon --offline --console=plain *> build\last-build-info.log`（构建前确认无其他 agent 的 java/gradle 进程；
  本机 Gradle daemon 会卡死，必须 `--no-daemon`）。本轮结果：`BUILD SUCCESSFUL in 37s`，
  产物 `build/libs/war_project-1.0.0.jar` 361613 字节，SHA256 `0397E66768B023811AB0889EF3E36CD88311E914E6A4DFC78B8911CE625BC25B`，
  与部署目标哈希一致；新类 `MapBoundaryService` / `MapData$Bounds` / `MapBoundaryRenderer` / `OutOfMapClientState` /
  `OutOfMapHudOverlay` / `ClientMapState$ClientBounds` / `OutOfMapWarningPacket` 均已在 jar 内，并用 `javap` 反查确认
  `isDeadOrDying / isCreative / isSpectator / Level.OVERWORLD / ServerPlayer.kill() / Config.outOfMapReturnSeconds` 出现在编译产物里。
- 验收清单：`map set 0,0 9,9` 后可见黄墙与两张地图的红雾；`map info` 回显一致；越界出现红字并逐秒递减、回区即消失；
  持续越界 30s 被击杀；把 `outOfMapReturnSeconds` 改成别的值后行为随之改变；创造/旁观完全不受影响。
- **必须重启客户端**（新增类与网络包，`F3+T` 资源重载不够）。

## 8. 源文件

`src/main/java/com/flowingsun/war_project/map/MapData.java`、`map/MapBoundaryService.java`、`map/MapDivideStateApi.java`、
`map/MapDivideModule.java`、`command/WarProjectCommands.java`、`net/WarProjectNetwork.java`、`Config.java`、
`client/ClientMapState.java`、`client/OutOfMapClientState.java`、`client/OutOfMapHudOverlay.java`、`client/MapBoundaryRenderer.java`、
`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java`、
`src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapOverlay.java`、`docs/ARCHITECTURE.md`。
