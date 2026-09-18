---
id: "da05742d-18c8-4b29-b1c3-8852d2c2d8d3"
title: "War Project 小地图叠加层：注入根因定位与修正（订正 b8b4935c 的注入机制与线宽）"
description: "小地图叠加层长期不渲染的决定性根因：render 声明在抽象基类 MinimapElementRendererHandler 而非子类 MinimapElementOverMapRendererHandler，按子类+方法名匹配找不到方法，require=0 静默失败。含修正后的注入方式（基类 + 仅方法名 + instanceof 过滤 + Object 中转）、一次性诊断日志、线宽第二次收细（/40 夹取 1–2）、已排除项与部署核对。改小地图渲染或排查注入失效前先读本文。"
status: "active"
created_at: "2026-09-18T20:44:18.357Z"
updated_at: "2026-09-18T20:44:18.357Z"
content_hash: "5c85c611eda9b8c0bc347601d5cd1b2bc690f1ac7819dd5e9bc20438d36cedaf"
source_paths:
  - "src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapScreenOverlayMixin.java"
  - "src/optionalXaero/java/com/flowingsun/war_project/compat/xaero/XaeroMinimapOverlay.java"
  - "src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java"
  - "src/main/resources/mixins.war_project.json"
session_ids:
  - "92e9eb4d-22d0-4feb-b5ea-16a7fe32b92a"
memory_body_ids:
  []
---

# War Project 小地图叠加层：注入根因定位与修正

## 0. 本文订正 / 取代哪些内容

- `b8b4935c-5926-449f-a42c-ad50c22883f9`（小地图改走屏幕层、线宽收细、FTB 拦截范围）：
  **两处已过时**，以本文为准 ——
  1. **注入机制**：该文写的是「`@Redirect` 于 `MinimapRenderer.renderMinimap(...)` 内对
     `MinimapElementOverMapRendererHandler.render(...)` 的调用点」；实际应注入**抽象基类**
     `xaero.hud.minimap.element.render.MinimapElementRendererHandler` 的 `render` 方法，且**只按方法名匹配**。
  2. **线宽**：`chunkPixels/28` 夹取 1–3 → 现为 `chunkPixels/40` 夹取 **1–2**。
  该文其余内容仍然有效：屏幕层方案、FBO 路径废弃、FTB 仅编辑模式拦截、已删文件清单、可见区裁形口径。
- `1223eb8a-dd85-486c-acd6-b327dec5584d`（FBO 未渲染排查 / node 名称 / FTB 交互）：
  其排查**证据**仍有效（描述符逐字符一致、`endBatch` 早于解绑、`usingFBO()` 判定式），
  但对「注入为何不生效」的归因请以本文为准（该文把主因归到 `usingFBO()` 为假，本文给出更根本的一层）。
- `5ea0e65d-6677-419e-9263-916413603f03`（FBO 内绘制）：现行机制描述已被 `b8b4935c` 与本文取代；
  其留存的 Xaero 字节码结构事实（FBO 绑定/解绑偏移、元素处理器 prepareRender 时机）仍可参考。
- `dbb656b9-3bb8-4211-ad68-f2bba70b49b2`、`d33309cb-4d09-4115-b151-97634cb3315e`、
  `3d31dded-b45f-4f11-a183-0d1aa78e4faa`：未涉及本次变更，仍有效。

Xaero 侧依据版本：World Map **1.44.2**、Minimap **26.4.2**（用 JDK `javap` 核对）。
部署目标：`D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`。

---

## 1. 根因（决定性，解释「小地图叠加层一次都没渲染过」）

`render` 的**声明位置**在抽象基类，**不在**具体子类：

- 声明 `public void render(GuiGraphics, Vec3, float, RenderTarget, double, ResourceKey)` 的类是
  `xaero.hud.minimap.element.render.MinimapElementRendererHandler`（抽象基类）。
- `xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler`（屏幕层 over-map 处理器）
  **没有覆写** `render`；它自己的成员只有 `prepareRender(...)`、`transformAndRenderForRenderer(...)`、
  `beforeRender(...)`、`afterRender(...)`、`translatePosition(...)`。

后果：以**子类**为 `@Mixin(targets = ...)` 并用 `method = "render"` 匹配时**根本找不到该方法**；
配合 `require = 0`，注入**静默跳过**，叠加层一次都不会绘制。

这同时解释了长期现象：**世界地图叠加层始终正常**（走 Forge `ScreenEvent`，完全不经过 mixin），
而小地图的每一版实现都失效（全部压在那一处注入上）。

## 2. 修正后的注入方式

文件：`src/optionalXaero/java/com/flowingsun/war_project/mixin/xaero/XaeroMinimapScreenOverlayMixin.java`

```
@Mixin(targets = "xaero.hud.minimap.element.render.MinimapElementRendererHandler", remap = false)
public abstract class XaeroMinimapScreenOverlayMixin {
    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void warProject$renderMinimapOverlay(GuiGraphics graphics, Vec3 renderPos, float partialTick,
            RenderTarget renderTarget, double mapDimensionScale, ResourceKey<Level> dimension, CallbackInfo ci) { ... }
}
```

要点（每一条都是踩过的坑）：

1. **目标 = 真正声明 `render` 的抽象基类**，不是具体子类。
2. **只按方法名匹配**（`method = "render"`，不写参数描述符）。此前写法依赖 `renderMinimap` 的
   **11 个参数**的超长描述符，人工抄写极易出偏差，而 `require = 0` 会让偏差无从察觉。
3. 基类被三类处理器共用（屏幕层 over-map、FBO 内 map、world），因此注入体内用
   `instanceof MinimapElementOverMapRendererHandler` **只对屏幕层 over-map pass 生效**，
   避免同时在 FBO 内重复画一遍。
4. **Mixin 编译限制**：mixin 类与目标类型无继承关系，直接写 `this instanceof X` 会编译失败
   （「不兼容的类型」）。必须经 `Object` 中转：
   ```java
   Object self = (Object) this;
   if (!(self instanceof MinimapElementOverMapRendererHandler handler)) { return; }
   ```
5. 选 `@At("RETURN")` 的原因：此时 pose 仍是 Xaero 为 over-map 元素准备的那套
   （原点在小地图中心，其自身 push/pop 已平衡），与 `XaeroMinimapOverlay` 的绘制空间一致。
6. **一次性诊断日志**（便于二分定位，只在首次注入执行时打印）：
   `War Project minimap overlay injected into the Xaero over-map element handler`
   - **有**该行 → 注入成功，问题在绘制层；
   - **无**该行 → 注入仍未生效，应改走无注入取数路径（见第 5 节）。

## 3. 线宽第二次收细

`src/main/java/com/flowingsun/war_project/client/xaero/XaeroWarProjectMapRenderer.java`：

- `edgeThickness(chunkPixels) = clamp(round(chunkPixels / 40.0), 1, 2)`
- 演进轨迹：`/14`（上限 6）→ `/28`（上限 3）→ **`/40`（上限 2）**；低缩放恒为 1px。
- `dashPattern` 未变：`dash = clamp(round(chunkPixels*0.30), 3, 96)`、`gap = clamp(round(chunkPixels*0.20), 2, 64)`。
- node 与战区、世界地图与小地图**共用**该函数，改一处即同时生效。

## 4. 本轮已排除的可能

- **版本不匹配：排除。** `D:\mc\.minecraft\versions\totalwar\mods` 内 Xaero 小地图**只有一个** jar
  （`[Xaero的小地图] xaerominimap-forge-1.20.1-26.4.2.jar`），与 build.gradle 编译期选中的是同一文件。
- **mixin 声明缺失：排除。** jar 内三处齐备：`META-INF/MANIFEST.MF` 的
  `MixinConfigs: mixins.war_project.json`、`META-INF/mods.toml` 的
  `[[mixins]] config="mixins.war_project.json"`、jar 根 `mixins.war_project.json`（client 阵列 4 条）。
- **其他模组干扰：未发现证据。** mods 目录内 `embeddium`、`oculus`、`xaeros-map-server-utils`、
  `packetfixer` 等未见会阻断注入或覆盖小地图渲染的迹象。

## 5. 备用取数路径（若注入仍不可用）

目标：不依赖 mixin 也能拿到「小地图屏幕位置 + 投影」。

- 已核对的公开入口：`xaero.hud.minimap.BuiltInHudModules.MINIMAP`（`HudModule<MinimapSession>` 静态字段）
  → `getCurrentSession()` → `MinimapSession`，其公开方法有 `getProcessor()`、`getWidth(double)`、
  `getHeight(double)`；`minimap` 字段为 **private**，需反射。
- `xaero.hud.minimap.Minimap#getOverMapRendererHandler()` 为公开方法（可拿到含
  `ps`/`pc`/`zoom`/`specW`/`specH`/`circle` 的处理器）。
- **`MinimapProcessor` 只有 zoom/size，没有位置信息**（成员已核对）；小地图屏幕位置目前只能来自
  渲染调用参数或 Xaero 配置（位置/偏移选项），这正是走屏幕层时必须先解决的一环。

## 6. 部署核对

- 构建产物 `build/libs/war_project-1.0.0.jar` → 同步到
  `D:\mc\.minecraft\versions\totalwar\mods\war_project-1.0.0.jar`（195747 字节），
  两端 **SHA256 一致**，确认已同步。
- 同目录另有 `war_project-1.0.0.jar.old`（184149 字节）：后缀非 `.jar`，Forge 不加载、无影响，
  建议清理以免混淆。
- 两种构建配置均通过：`gradlew build`、`gradlew build -PwarProjectDisableOptionalCompat=true`。

## 7. 可复用教训

- **`require = 0` 的可选兼容注入会静默失败。** 排查「注入了但没效果」时，第一步是确认方法
  **声明在哪个类**，而不是假设它在子类；能用**方法名匹配**就不要写长描述符。
- Mixin 类内部对目标类型做 `instanceof` / 强转，必须经 `Object` 中转。
- 「世界地图（Forge 事件）正常、小地图（mixin）全灭」这一差异本身就是强信号：问题在注入层，
  而不是渲染公式或配置。
- 同一个 API 家族里，`render` 可能声明在基类、`prepareRender` 在子类 —— 注入前用 `javap` 看
  **成员列表归属**，别只看名字存在。
