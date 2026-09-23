---
id: "77eee1af-fa4e-4f5a-a265-046f3dfbd6d2"
title: "手工构造 SavedData 存档的格式与陷阱（War Project 无头验证补充）"
description: "补充 9cf19d0f 的验证流程与 f4ace0dc 的登录盲区：`world/data/*.dat` 的真实结构是 gzip 的 `{data:{…},DataVersion}`（DimensionDataStorage 读的是 `tag.getCompound(\"data\")`，javap 证据）；两个实测陷阱——停服保存会覆盖手写存档、手写 NBT 少一个 compound 结束标记就会让后续键被吞进上一层（症状 `keys=[nodes]`、double 被读成 float）；结论是造测试存档应加临时调试命令让服务器自己写。要造测试存档或排查「文件明明对、服务器却读到空」时读本文。"
status: "active"
created_at: "2026-09-20T07:27:17.315Z"
updated_at: "2026-09-20T07:27:17.315Z"
content_hash: "0b36f4619b90166a0622d9c4e8f7af63a09084bcc57506f0b25a9182d721285c"
source_paths:
  - "src/main/java/com/flowingsun/war_project/map/MapData.java"
  - "src/main/java/com/flowingsun/war_project/command/WarProjectCommands.java"
  - "docs/ARCHITECTURE.md"
session_ids:
  - "e9f795e8-5572-43cb-a34a-673dc370ddf3"
memory_body_ids:
  []
---

# 手工构造 SavedData 存档：格式与陷阱

本文只补两块既有文档没覆盖的内容：**`world/data/*.dat` 的真实字节结构**，以及**手写测试存档必然踩到的两个陷阱**。
验证流程主体见 `9cf19d0f-cc20-47fb-9e20-43f70d30c126`（runServer + datapack + RCON + save-all + GZip 解 NBT），
登录盲区与 BOM 坑见 `f4ace0dc-845d-42d6-bfff-0178f817d132`。

## 1. 文件结构（javap 证据）

`net.minecraft.world.level.storage.DimensionDataStorage` 的读取链（反编译逐条核对）：

```
readSavedData(loader, name)
  → getDataFile(name)                  // java.io.File，位于主世界的 world/data/
  → file.exists()
  → readTagFromDisk(name, SharedConstants.getCurrentVersion().getDataVersion().getVersion())
  → DataFixTypes.SAVED_DATA.update(fixerUpper, tag, fileVersion, currentVersion)
  → loader.apply( tag.getCompound("data") )      // ← 关键：取的是 "data" 段
```

所以磁盘上的 `.dat` 是 **gzip 压缩的 NBT**，结构为：

```
(root compound, name "")
  ├─ compound "data"      ← SavedData.load() 收到的就是这个
  │    ├─ list "nodes"
  │    ├─ list "warzones"
  │    └─ compound "bounds"          (若有)
  └─ int "DataVersion"    ← 与当前版本一致时不走 DataFixer
```

把 MapData 内容**直接放在根**上，服务器取到的是空 compound → `load` 得到空数据（症状：`map info` 说未设置、
`node list` 说无节点，而文件看起来"存在且合法"）。判断写入是否正确的最快办法：
GZip 解压后看前半段 ASCII，出现 `data` 才是对的（形如 `…data…nodes…`）。

## 2. 陷阱 A：停服保存会覆盖手写存档

**服务器的 `stop` / `save-all` 会用内存里的数据重写所有 `.dat`。** 于是一旦当前会话里 MapData 是空的，
下次停服就把"手写好的存档"换回空文件——现象是"我刚写的文件明明 239 字节，服务器启动后读到空"。

正确次序（缺一不可）：

1. `stop` 并**等到 java 进程真正退出**（`Get-Process java,gradle` 计数为 0）；
2. 写入存档；
3. 立刻启动服务器，**中间不得再有任何一次停服/保存**；
4. 启动前顺手 dump 一次文件长度与解析结果，作为本次读取的基线。

## 3. 陷阱 B：手写 NBT 少一个结束标记

NBT 里**每个 compound（包括 list 中作为元素的 compound）都必须以 `TAG_End`（0x00）结束**。
少写一个字节不会报错，而是让解析器把后续键**吞进上一层**：

- 症状 1：根只看到 `keys=[nodes]`，`warzones` / `bounds` 凭空消失（它们成了某个 node 的字段）；
- 症状 2：`ammo_per_minute` 这种 double 被读成 float（值变成 `2.125f` = 原 double 的前 4 字节），
  即类型字节整体错位 1 字节；
- 症状 3：list 的第二个元素变成 `{}`。

对照写法：`node` 元素内部若有 `chunks`（list&lt;compound&gt;），需要 **两个** `00`——
一个结 束 `chunks` 的元素，一个结束 `node` 本身。

## 4. 推荐做法：不要手写，让服务器自己写

手写 NBT 的性价比极低。可靠路径是**临时加一条调试命令**，让数据走正常服务路径生成，
再由服务器写出权威存档：

```java
// 临时：经 MapDivideStateApi.createNodeWithWarzone(...) 创建一个节点（可带 vp 参数）
private static int debugCreateNode(CommandContext<CommandSourceStack> context) { … }
```

流程：加命令 → 构建 → 启动 → RCON 创建数据并验证行为 → `save-all` → 停服 → dump 存档确认键存在 →
重启确认**读回**（探针 `keys=[nodes, warzones, bounds]` + 业务指令回显一致）→ **删除临时命令、重新构建，
并用 `javap` 确认产物里没有残留方法**。

排查期间如需临时日志探针，同样在收尾时删除并重新构建；探针输出建议一次性打印
`tag.getAllKeys()`、`tag.size()` 与整个 tag，比只打键名信息量大得多。

## 5. 读回闭环的验收判据

重启后同时满足才算通过：加载日志显示三个键齐备 → `map info` 回显 bounds → 业务指令回显与停服前一致
（本轮实例：`node list` 显示 `vptest*`、`node info` 显示 `vp=true`、`setresource` 仍被拒）。

## 6. 相关

- 验证流程主体：`9cf19d0f-cc20-47fb-9e20-43f70d30c126`
- 登录期命令树事故 / 现行 `map set` 语法 / 无 BOM 写法：`f4ace0dc-845d-42d6-bfff-0178f817d132`
- 地图区域 `bounds` 段本身：`21dce6fc-d9f3-402f-9213-91f18c0235a6`
