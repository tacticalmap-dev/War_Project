---
id: "7ec44de5-4589-455c-bc29-2dc58f1470d9"
title: "War Project VP node：特殊节点类型的语义、星标渲染与创建入口"
description: "VP node 子系统的设计与实现要点（2026-09-20）：`vp` 标记的语义与\"永不产出\"的双重保障、Xaero 双地图星标的配色/尺寸/阈值取舍（为何与收益行天然互斥）、FTB 创建与编辑面板的开关、协议号升到 8 与新增 SetNodeVpPacket、以及真机验收回显。改 VP 相关逻辑或星标渲染前读本文；无头验证方法与存档格式见 77eee1af，命令参数红线见 f4ace0dc，边界规则见 21dce6fc。"
status: "active"
created_at: "2026-09-20T09:25:36.048Z"
updated_at: "2026-09-20T09:25:36.048Z"
content_hash: "430665755b00af78c78d7a3178b3d062ffc0a0646a9c9ac1a77925daf71f0a60"
source_paths:
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/client/VpStarIcon.java"
  - "src/main/java/com/flowingsun/war_project/net/WarProjectNetwork.java"
  - "src/main/java/com/flowingsun/war_project/resource/ResourceService.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "src/optionalFtb/java/com/flowingsun/war_project/compat/ftb/FtbChunksMapDivideClient.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroWorldMapScreenOverlay.java"
  - "src/main/resources/assets/war_project/textures/gui/vp_star.png"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "4a0babb1-ea7c-4b69-b1a3-ab5329596f20"
memory_body_ids:
  []
---

# War Project VP node（胜利点节点）

本文覆盖 VP node 子系统的**语义、实现要点与设计取舍**。相关但不同的主题：
无头验证流程与 SavedData 存档格式陷阱见 `77eee1af-fa4e-4f5a-a265-046f3dfbd6d2`；
命令参数只能用原版类型（登录期命令树事故）见 `f4ace0dc-845d-42d6-bfff-0178f817d132`；
地图边界（bounds / 黄墙 / 红雾 / 越界执法）见 `21dce6fc`。

## 1. 用户定调的语义

1. VP node 只是在普通 node 上多一个 `vp` 标记：**照常参与占领**，不引入任何"VP 专属"占领或计分规则，数量不限。
2. **永不产出资源**，用**双重保障**实现，任一路径都拦得住：
   - 写入侧：创建时带 `vp` 的节点产出即为 0；`MapData#setNodeVp(id, true)` 切换时**同时清零** ammo/fuel；
   - 读取侧：`ResourceService#teamGains` 遍历节点时直接 `if (node.vp()) continue;`，即使存档被外部改成有产出也不结算；
   - 指令侧：`node setresource` 对 VP node 直接拒绝并提示先清标记（FTB 的 "Set ammo output" 同样在客户端拦下）。
   - 反向操作有明确提示：`setNodeVp(id, false)` **不返还**产出，需要重新 `node setresource`（回显里写明）。
3. 星标颜色取决于**当前归属**，复用既有阵营关系配色（中立白 / 本方·同盟蓝 / 敌对红），因此占领方一变星标立刻变色。
4. 已有 node 可随时在 VP 与普通之间转换（FTB 右键菜单或指令）。

## 2. 数据层与网络

- `MapData.Node` 末尾追加 `boolean vp`，随 `save()`/`load()` 走 NBT 键 `vp`，因此**随 `clientSnapshot()` 自动下发**，不需要新的同步机制；旧存档缺该键时 `getBoolean` 返回 `false`，行为与加 VP 之前完全一致。
- `saveNodeWithWarzone(..., boolean vp)` 透传；`MapDivideStateApi.createNodeWithWarzone(..., vp)` 与新增 `setNodeVp(server, id, vp)`（写成功即 `broadcastMap`）。
- 网络（`net/WarProjectNetwork`）：
  - `CreateNodeWarzonePacket` 增加 `boolean vp` 字段（encode/decode 各加 `writeBoolean`/`readBoolean`）；
  - 新增 C→S 包 `SetNodeVpPacket(nodeId, vp)`，与其它写地图的包一样做 **OP 2 级权限校验**；
  - `PROTOCOL` 由 `"7"` 升到 `"8"`（包结构与数量变化）。
- 客户端 `ClientMapState.ClientNode` 同步增加 `boolean vp`，供渲染层与 FTB 界面读取。

## 3. 渲染：名称下方的 ⭐

- 共享绘制类 `client/VpStarIcon`：素材 `assets/war_project/textures/gui/vp_star.png`（纯白五角星 + 透明底，64×64，用 **4× 超采样**生成以保证缩小时边缘平滑），绘制用 **11 参 `blit`**（9 参会把目标尺寸当采样尺寸，只截取纹理左上角），并用 `RenderSystem.setShaderColor(r,g,b,1)` 按 `XaeroWarProjectMapRenderer.relationEdgeArgb(factionId)` 着色，画完复位 `(1,1,1,1)`。
- 世界地图（`XaeroWorldMapScreenOverlay#drawNodeLabel`）：
  - 名称与星标视为**一个整体**垂直居中于 node 几何中心：`blockHeight = 名称行高 + starGap + 星标尺寸`，`labelTop = round(centerY - blockHeight/2)`；星标紧贴名称下方（`starGap = 1`，**不随缩放**——早先用收益行的随缩放行距，缩放越大缝隙越明显）。
  - 星标尺寸沿用收益行的缩放机制（基准 12px、系数 `clamp(zoom/5, 0.7, 2.2)`），但**不套用收益行的 2.0x 隐藏阈值**：VP 是归属标识，任何缩放都应看得见。
  - 因为 VP node 产出恒为 0，`showGains` 必为 false，**星标行与收益行天然互斥**，不需要额外优先级判断。
- 小地图（`XaeroMinimapOverlay#drawLabels`）：在既有的 `translate + scale(LABEL_SCALE=2.0)` 姿态内，名称下方一行画同一张星标（固定 8px，等效 16px），颜色规则相同。
- 几何均按上/左坐标给 `blit`（`centerX - size/2`），名称与星标共用同一水平中心。

## 4. 入口

- **FTB 创建面板**（`FtbChunksMapDivideClient#openNodePrompt` → `NameIdPromptOverlay`）：面板内新增 `Toggle VP` 按钮（匿名 `SimpleTextButton` 子类，`onClicked` 翻转布尔并播放点击音）与 `VP node: ON/OFF` 状态文本（`drawBackground` 里绘制，ON 用黄色）；面板高度 104 → 122，控件重排为 name 25 / id 55 / VP 开关 78 / accept·cancel 100。
- **FTB 编辑面板**（`openRenameNodePrompt`）复用同一 overlay：VP 初值取自当前 node，**改名字不会丢标记**；若在面板里改了开关，确认时先发 `sendRenameNode` 再按差异发 `sendSetNodeVp`。
- **FTB 右键 node 菜单**：新增信息行 `VP node: yes/no` 与动作 `Mark as VP node` / `Clear VP marker`（`Icons.ACCEPT`）。
- **指令**：`/warproject node setvp <nodeId> <true|false>`（参数用 `StringArgumentType.word()` + 手动解析 true/false，非法值给出明确失败信息）；`node list` 用后缀 `*` 标出 VP node（`Nodes (* = VP): …`），`node info` 输出追加 `vp=true|false`。
- 创建链路：`PendingNode(id, name, nodeChunks, colorRgb, vp)` 携带标记，`confirmWarzone()` 发 `sendCreateNodeWarzone(..., vp)`。

## 5. 已验收的行为（无头服务端 + RCON 真机回显）

| 操作 | 回显 |
| --- | --- |
| 创建带 vp 的节点 | `Debug node created: vptest vp=true`（走正常服务路径） |
| `node list` | `Nodes (* = VP): vptest*` |
| `node info vptest` | `…chunks=1 ammoPerMinute=0.00 fuelPerMinute=0.00 vp=true` |
| `node setresource vptest 5 5` | `Node vptest is a VP node and never produces resources; clear its VP flag first …` |
| `node setvp vptest false` → `setresource 5 5` | 清除后设置成功：`ammo=5.00/60s fuel=5.00/60s` |
| `node setvp vptest true` → `node info` | `VP marker set (resource output cleared)` ⇒ `vp=true, ammo=0.00, fuel=0.00` |
| 停服重启后再查 | 加载日志 `keys=[nodes, warzones, bounds] nodes=1 warzones=1 bounds=true`，节点里 `vp:1b` 完好；`node list` 仍 `vptest*`、`setresource` 仍被拒 |

（这轮验证用的临时调试命令与日志探针都已删除，并在最终构建后用 `javap` 确认产物中无残留方法。）

## 6. 待实机确认与可能调整

- **星标的排布**有两种合理解读，当前实现是「名称在上、星标紧贴其下、两者作为整体居中于 node 几何中心」：若用户想要「★ 与名称同一行并排后整体居中」，或「名称单独居中、星标下挂不参与居中」，改动点只在 `XaeroWorldMapScreenOverlay#drawNodeLabel` 的 `blockHeight`/`starGap` 与 `VpStarIcon.draw` 的 y 计算。
- 截图显示的画面里，node 可能是 **L 形区域**——"几何中心"取的是 chunk 包围盒中心，L 形的视觉重心与之不重合，容易误判为"没居中"。
- FTB 面板的开关与两处地图的星标属可视效果，需要真机确认（无头验证覆盖不到渲染）。
