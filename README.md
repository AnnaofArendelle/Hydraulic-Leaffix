# Hydraulic Pack-Converter Crash Fix (`hydraulic-leaffix`)

一个极小的 Fabric Mixin 模组，用于修复 **基岩版（Geyser）玩家看不到 / 用不了模组方块**（典型：传送石碑 Waystones）的一系列问题。

- **版本**：1.3.1
- **目标 Minecraft**：1.21.11（Fabric）
- **运行依赖**：服务端需同时存在 Geyser + Hydraulic + Bedframe（本模组是给它们"打补丁"）；物品组件修复还需要 Polymer + Floodgate（本服务端均已具备）
- **许可证**：MIT

> **1.3.1**：**修复 1.3.0 物品修复引入的副作用**——基岩玩家手动拿取/移动物品时出现"幽灵物品图标、放不下"，以及"生存模式无法破坏方块"再次复发（偶发，原版物品/方块也受影响）。根因：1.3.0 只是在下行包上**裸剥离**模组组件，使 Geyser/客户端看到的物品与服务端**权威物品不一致**；而 Polymer 维持一致性靠的是"出站转成客户端物品、入站用 `getRealItemStack` 从 `$polymer:stack` NBT 还原成服务端真实物品"的**往返**，裸剥离的物品没有这个 NBT → 入站无法还原 → 物品栏交互与手持工具状态错乱（连带破坏判定用错工具）。本版改为把这些物品**走 Polymer 自己的 `createItemStack`**（与其它 Polymer 物品同路）：既剥离模组组件、又写入 `$polymer:stack` 往返 NBT，使服务端入站能还原真实物品、状态与其它 Polymer 物品一样保持同步（详见 [§3.8](#38-基岩物品组件修复polymerbedrockitemfixmixin)）。
>
> **1.3.0**：两项**跨 Geyser 版本兼容性**修复。
> ① **修复服务端在新版 Geyser 上无法启动**——`geyser-fabric 2.9.6` 移除/重构了 Bedframe 0.1.0 依赖的**自定义实体 API**（`GeyserEntityDefinition` 等），导致 Bedframe 在 `TranslationManager.<init>` 构造两个实体翻译器时抛 `NoClassDefFoundError`，整个 `main` 入口点失败、服务器起不来（2.9.5 可正常启动）。本版**仅在该 API 缺失时**跳过这两个实体翻译器的构造（不影响石碑的方块/物品转换），API 存在时（如 2.9.5）则原样放行——因此一份 jar 同时兼容新旧 Geyser（详见 [§3.7](#37-启动崩溃修复translationmanagermixin)）。
> ② **修复基岩版生存模式下箱子/物品栏物品不显示、无法切换**——Geyser 自带的 MCProtocolLib 只认识**原版数据组件**，当某个物品带有**模组数据组件**（如已绑定的 `waystones:attuned_shard` 携带 `waystones:attunement`）时，Geyser 读取该物品组件时抛 `Exception while reading components for item <id>`，破坏整个物品栏数据包的解析——表现为"占位还在、东西不显示"。本版在 Polymer 下发给**基岩玩家**的每个物品上处理掉非 `minecraft` 命名空间的数据组件（基岩端本就用不到），使 Geyser 能正常读取（Java 玩家不受影响）（详见 [§3.8](#38-基岩物品组件修复polymerbedrockitemfixmixin)）。
>
> **1.2.3**：**修复基岩版玩家在生存模式下无法打破石碑**——表现为挖掘后"挖掘失败、石碑依旧还在、不破碎不掉落"，而创造模式破坏、生存模式放置均正常。根因是 Hydraulic 把 Java **硬度**当作基岩 `destructible_by_mining` 的"秒数"下发（硬度 ≠ 秒），加上 Geyser 对自定义方块走"服务端权威破坏"，导致基岩客户端按这个偏短的计时**提前判定破坏**，而服务端进度还不够 → Geyser 把方块**还原**。本版把该计时改写为**裸手破坏所需的真实秒数（硬度 × 5）**，使客户端在服务端破坏完成前不会提前判定（详见 [§3.6](#36-生存模式破坏修复blockpackmodulemixin)）。
>
> **1.2.2**：**消除启动时上千行 `particle/xxx_n.png is missing. Please report this.` 刷屏**——拦截并吞掉粒子转换器逐帧打印的 missing 警告，并把崩溃兜底**按粒子表种类去重**（每种只记一行，约 5 行/次启动）（详见 [§3.2](#32-粒子表兜底gridspritesheetparticletransformermixin)）。同时在文档中记录了**普通石碑在基岩版放不下**的真正原因——一个**与本模组无关**的残留 Geyser 配置文件，需手动删除（详见 [§6 故障排查](#6-故障排查)）。
>
> **1.2.1**：在 1.1.0「让方块能显示」的基础上，修复石碑在基岩版**破坏碎屑显示为紫色缺失贴图**的问题。物品栏缩略图维持上半截显示（按需求不改动）。

---

## 1. 它解决什么问题

基岩版要渲染一个非原版方块，客户端必须拥有该方块的"定义"（几何模型 + 贴图）。模组方块的定义由 **Hydraulic** 把 Java 资源转换成 Bedrock 资源、再经 Geyser 整合包自动下发给基岩端。

如果这个转换在中途崩溃，基岩端就拿不到任何模型/贴图 —— 方块表现为**不可见**；但方块在服务端真实存在，所以**碰撞、右键、GUI 交互照常**。这正是"石碑看不见却能点开菜单"的现象。

## 2. 根本原因（技术说明）

Hydraulic 和 Bedframe 都以 **jar-in-jar** 形式内嵌了 Geyser 的 `pack-converter`，且**版本号都叫 `3.4.1-SNAPSHOT` 但内容不同**：

| 来源 | 内嵌 converter 构建日期 | 含 `GridSpritesheetParticleTransformer` |
|------|------------------------|------------------------------------------|
| Hydraulic | 2026-01-20（较旧） | 否 |
| Bedframe  | 2026-05-05（较新） | 是 |

Fabric 对同名同版本的嵌套库只会加载**一个**。实测加载的是 **Bedframe 那个较新、但有 bug 的版本**。于是 Hydraulic 运行转换时用到它，在 Minecraft 1.21.11 的若干原版资源上接连抛异常，而 `TextureConverter.extract(...)` 在调用每个转换器时**没有任何 try/catch**，导致**任意一个转换器抛异常都会中止整包转换**：

```
# 现象：每个模组都 "Failed to convert pack for mod XXX"

# 崩溃点 1：树叶粒子表网格切片越界
java.lang.ArrayIndexOutOfBoundsException: Index 12 out of bounds for length 12
    at ...particle.GridSpritesheetParticleTransformer.transform(GridSpritesheetParticleTransformer.java:83)
    at ...texture.TextureConverter.extract(TextureConverter.java:97)

# 崩溃点 2（修掉粒子后暴露出来）：地图图标 key 为 null
java.lang.NullPointerException: value
    at ...util.UnsafeKey.<init>(UnsafeKey.java:57)
    at ...transformer.TextureTransformer.gridTransform(TextureTransformer.java:63)
    at ...texture.transformer.type.ui.MapIconsTransformer.transform(MapIconsTransformer.java:40)
    at ...texture.TextureConverter.extract(TextureConverter.java:97)
```

这是"一只转换器崩 → 整包全崩"的同类问题，1.21.11 上不止一处。

> 注：这是该 converter 快照版与 1.21.11 资源格式不兼容所致。由于 Geyser-Fabric / Hydraulic 只跟进**最新** Minecraft 版本（现已是 26.x），1.21.11 不会再有官方修复构建，因此采用运行时补丁。

## 3. 工作原理

本模组用 **MixinExtras** 在不改动其它任何文件的前提下，给那个有 bug 的 converter 打两层补丁：

1. **`TextureConverterMixin`（通用兜底，主修复）**
   用 `@WrapOperation` 包裹 `TextureConverter.extract(...)` 中对 `TextureTransformer.transform(...)` 的调用：**任何转换器抛异常就记录并跳过该项、继续转换**。粒子、地图图标，以及后续同类 bug 一并覆盖。

2. **`GridSpritesheetParticleTransformerMixin`（针对性兜底 + 消除刷屏，见 [§3.2](#32-粒子表兜底gridspritesheetparticletransformermixin)）**
   两层互补、且**不引入额外依赖**的处理：① 用 `@WrapOperation` 包裹该 `transform` 内部对 `TransformContext.warn(String)` 的调用，**吞掉逐帧的 "... is missing. Please report this."**（上千行刷屏的来源），其它告警照常透传；② 仍用 `@WrapMethod` 包裹 `transform` 捕获越界崩溃，使单个粒子表失败不致中止整包转换，并**按转换器类去重**（每种只记一行，而非每个模组各记一次）。

两个 Mixin 均 `remap = false`（目标是第三方库类，非 Minecraft 类，无需 Yarn/refmap 重映射），`required = false`（即使未命中也只记日志，**绝不会让服务器崩**）。

补好之后，Hydraulic 用模组**真实的 Java 贴图 + 转换后的方块几何**生成 Bedrock 资源，所以基岩版外观会高度还原 Java 版。

### 3.2 粒子表兜底（`GridSpritesheetParticleTransformerMixin`）

启动日志里成片的：

```
[Hydraulic Conversion Thread #0/WARN]: particle/pale_oak_5.png is missing. Please report this.
```

**这是正常的"噪声"，不是崩溃、也不会丢失你模组方块的转换结果。** 来由：

- converter 里有 5 个"网格粒子表"转换器（leaf / cherry / gust / pale_oak / small_gust），它们要把**原版** Java 的逐帧贴图（`particle/leaf_1.png` … `_11.png`）拼成基岩粒子表。
- 在 MC 1.21.11 上，这些逐帧贴图不在该（旧）converter 期望的位置，于是 `pollOrPeekVanilla` 对**每一帧**都返回 null → 它**逐帧打印 `... is missing. Please report this.`**，随后越界读 `javaPaths[]`（warn 分支里的 off-by-one：`index` 自增后又拿来索引）→ 抛 `ArrayIndexOutOfBoundsException`。
- 由于每个被转换的模组都会跑一遍，单次启动就刷出约 **1150 行** missing 警告 + 上百行崩溃捕获日志；而它**根本没有产出**（崩在 `context.offer(...)` 之前），且这些是**原版粒子，基岩端本就自带**，转不出来毫无影响。

**1.2.2 的处理**（两层互补，且不引入额外依赖）：

- **吞掉刷屏**：用 `@WrapOperation` 包裹该 `transform` 内部对 `TransformContext.warn(String)` 的调用，凡是 `... is missing. Please report this.` 一律不打印（其它告警照常透传）。源帧若存在则压根不会产生该告警，故不会掩盖真实问题。
- **挡住崩溃 + 去重**：仍用 `@WrapMethod` 包裹 `transform` 捕获那次越界 `ArrayIndexOutOfBoundsException`，让单个粒子表失败不致中止整包转换；并**按转换器类去重**——每种粒子表只记一行（约 5 行/次启动），而不是每个模组各记一次（约 115 行）。

> 备注：之所以用"吞告警 + 去重崩溃"而非"事先用 `peekOrVanilla` 探测再跳过"，是因为后者需要在编译期引用 `net.kyori.adventure.key.Key`（`KeyUtil.key` 的返回类型），而该类在本服务端是以 **jar-in-jar 嵌套**形式提供、不在编译类路径上；当前做法只用到 `String` 与 `TransformContext`，无需该依赖，编译更稳。

### 3.5 方块转换修复

1.1.0 让石碑能显示后，又暴露出 Hydraulic 把 Java 方块转成 Geyser 自定义方块时的问题。修复发生在 **`onDefineCustomBlocks`（每次启动随 `GeyserDefineCustomBlocksEvent` 重新执行，不吃资源包缓存）**，所以**换上新 jar 重启即可生效，无需清缓存、无需改资源包**。

#### `BlockPackModuleMixin` —— 修复破坏碎屑紫块（问题 ③，很可能一并修复问题 ②）

- **基岩端碎屑贴图怎么来**：基岩破坏方块时，碎屑取**该方块自身材质实例 `down`、没有则取 `*`**，且这个贴图名必须是**同一个包** `textures/terrain_texture.json` 里的短名（[Bedrock Wiki](https://wiki.bedrock.dev/blocks/block-components)）。
- **根因**：Hydraulic 把每个方块模型的 `#particle` 贴图注册成基岩默认材质实例 `"*"`。石碑模型的 `#particle` 是**原版贴图**（如 `waystone_bottom` 的 `minecraft:block/polished_andesite`）。`PackUtil.getTextureName` 把被改名的原版贴图转成 `hydraulic:block/...`，它存在于**另一个**公共包 `hydraulic.mcpack` 里——**跨包引用，石碑方块解析不到** → `"*"` 没有可用贴图 → 碎屑变紫。
  - 而 `#particle` 是原版裸键的（黑石头 `blackstone`、末影石 `end_stone`）能命中客户端原版图集 → 正常。这正好对上"部分石碑碎屑紫、部分正常"，并很可能也对应"部分石碑放不下、黑石头能放"（默认材质解析失败时，基岩端疑似拒绝放置该方块）。
- **补法**：石碑的**面**用的是同包内的 `waystones:block/<石材>_waystone` 贴图（方块能正常显示就证明它解析得到）。本补丁 `@WrapOperation` 包裹 `Materials$Material.textures()`，把 `minecraft:` 的 `#particle` 值**改写为该方块自己的面贴图**，于是 `"*"`（碎屑来源）指向**同包内、确定存在**的贴图 → 碎屑正常。只动 `minecraft:` 的 particle 项，面贴图与其余材质不受影响。

> 关于问题 ①（物品栏缩略图只显示上半截）：经确认这是基岩自定义方块「物品图标 = 方块默认状态外观」机制所致；1.2.0 曾尝试把默认状态改成下半截，但按使用者要求**缩略图维持上半截、不改动**，故 1.2.1 未包含该改动。

> 关于问题 ②（**普通石碑** `waystones:waystone` 放下去只剩半块、约 1 秒后消失）：**已定位**，与本模组的贴图/材质修复**无关**。真正原因是一个残留的 Geyser 配置文件 `config/Geyser-Fabric/custom_mappings/waystones.json` 把**仅** `waystones:waystone` 重定义成了 `lodestone` 单方块并覆盖了它的方块物品，与 Hydraulic 的正规注册冲突——所以**只有这一种**石碑放不下、其余石碑都正常。**删除该文件即可修复**，详见 [§6 故障排查](#6-故障排查)。

### 3.6 生存模式破坏修复（`BlockPackModuleMixin`）

**现象（1.2.3 修复）**：基岩版玩家在**生存模式**下挖掘石碑时"挖掘失败、石碑依旧还在、不破碎也不掉落"；而**创造模式破坏**与**生存模式放置**都正常。Java 版玩家不受影响。

**为什么创造正常、生存不正常**：创造模式下基岩客户端是"一击即破"（`isInstabuild()` 直接破坏，无计时）；生存模式下破坏要走计时进度。两者代码路径完全不同，所以创造能破、生存不能。

**根因**（Java 硬度被当成秒 + Geyser 服务端权威破坏的计时错配）：

- Hydraulic 在 `onDefineCustomBlocks` 里这样下发基岩方块的破坏计时：

  ```java
  .destructibleByMining(Float.valueOf(block.method_36555()))   // method_36555() = Java 硬度
  ```

  即把 Java **硬度**直接当作基岩 `minecraft:destructible_by_mining` 的 `seconds_to_destroy`（秒）。**但硬度不是秒**：石碑硬度 `5.0`，用镐子真实破坏约 0.75–1.5 秒，**裸手约 25 秒**（原版公式：裸手秒数 = 硬度 × 100 tick ÷ 20 tps = 硬度 × 5）。
- 石碑是以**非原版方块覆盖**（`registerOverride(JavaBlockState, …)`）注册的，于是 Geyser 的 `BlockBreakHandler` 把它标为 `serverSideBlockBreaking = true`：破坏由**服务端按工具计算进度**驱动，进度到 `1.0`（或客户端判定破坏的那一刻进度 ≥ `0.65`）才真正破坏。
- 然而**基岩客户端**会**按它自己的 `destructible_by_mining` 计时（5 秒，且不分工具）自行判定破坏**（发 `BLOCK_PREDICT_DESTROY`）。裸手/拿错工具时，5 秒时服务端进度只有约 `0.2`（< `0.65`），于是 Geyser 认为"客户端以为破了、但服务端不认"，把方块**还原**（`handleContinueDestroy` 里 `restoreCorrectBlock`）→ 这正是"挖掘失败、石碑还在"。用对的镐子时服务端约 1–4 秒就到 `1.0`（早于 5 秒），所以**有时又像是正常的**——这与"看工具、时灵时不灵"完全吻合。

**补法**（只动基岩客户端的计时，不动服务端破坏数学）：用 `@ModifyArg` 把 `destructibleByMining(Float)` 的实参从"硬度"改写为**裸手破坏真实秒数 `硬度 × 5`**。这样基岩客户端的自行判定时间 ≥ 任何工具下服务端的破坏耗时，客户端**绝不会先于服务端判定**，也就不会触发还原：

- **裸手**：客户端 25 秒判定时，服务端进度恰好到 `1.0` → 正常破坏（约 25 秒，无掉落——与 Java 裸手挖石碑一致）。
- **任何镐子**：服务端 1–4 秒到 `1.0`，由 `tick()` 路径触发破坏，Geyser 经 `sendBedrockBlockDestroy` 强制客户端破坏、并用 `BLOCK_UPDATE_BREAK` 实时把破坏动画对齐到服务端进度——所以**实际破坏速度仍由工具决定、很快**，那个 25 秒上限根本到不了。
- **掉落物**由 **Java 服务端**按真实手持工具判定（镐子掉落、裸手不掉落），本补丁**不改 `JavaBlockState.blockHardness`**，故掉落与破坏速度都与 Java 完全一致。

> 仅在硬度为正数时改写；瞬破方块（硬度 0）与不可破坏方块（硬度 -1）保持原样。`@ModifyArg` 只需用到 `java.lang.Float`，**不引入对 Geyser API 类的编译期依赖**（目标用描述符字符串匹配）。`require = 0` / `remap = false`：未命中只是不生效，绝不崩服。

### 3.7 启动崩溃修复（`TranslationManagerMixin`）

**现象（1.3.0 修复）**：把 Geyser 升级到 `2.9.6-SNAPSHOT` 后服务端**直接起不来**，启动日志：

```
Could not execute entrypoint stage 'main' ... provided by 'bedframe'
Caused by: java.lang.NoClassDefFoundError: org/geysermc/geyser/api/entity/definition/GeyserEntityDefinition
    at lol.sylvie.bedframe.geyser.TranslationManager.<init>(TranslationManager.java:29)
    at lol.sylvie.bedframe.BedframeInitializer.<init>(BedframeInitializer.java:25)
```

**根因（Bedframe×Geyser 版本错配）**：Bedframe 0.1.0 是针对**较新**的 Geyser 编译的，用到了一套**自定义实体 API**（`GeyserEntityDefinition`、`CustomEntityDefinition`、`CustomJavaEntityType`、`GeyserDefineEntitiesEvent`、`ServerSpawnEntityEvent`，共 9 个类/事件）。`geyser-fabric 2.9.6` **移除/重构**了这套 API（日志历史证实：2.9.5 能正常启动、2.9.6 崩）。而 Bedframe 的 `TranslationManager` 在**字段初始化**里就直接 `new` 了两个实体翻译器：

```java
DisplayEntityTranslator displayEntityTranslator = new DisplayEntityTranslator();
ModEntityTranslator     modEntityTranslator     = new ModEntityTranslator();
```

这两个类**引用了已不存在的实体 API**，于是仅仅是构造它们就抛 `NoClassDefFoundError`，导致 Bedframe 的 `main` 入口点失败 → **整个服务器启动中止**。这两个翻译器只负责把模组**实体/家具**翻译给基岩端（在本服上它们本就没产出：日志里 `ModEntityTranslator registered 0 auto entities`）；我们真正依赖的石碑**方块/物品**转换由 `BlockTranslator` / `ItemTranslator` 完成，与此无关。

**补法（版本自适应）**：用 `@WrapOperation` 包裹 `TranslationManager` 里这两处 `new`，并先用 `Class.forName` 探测 `GeyserEntityDefinition` 是否存在：

- **API 缺失（如 2.9.6）**：跳过这两个 `new`（字段保持 `null`，**不触发类加载**故不会 `NoClassDefFoundError`），并相应地把它们从 `registerHooks()` 的 `List.of(...)` 里过滤掉（`List.of` 不接受 null）、把 `ensureLateGenerated()` 对 null 字段的调用变为空操作。服务器照常启动，石碑方块/物品转换不受影响。
- **API 存在（如 2.9.5）**：每个包装器都原样 `original.call()`，Bedframe 的实体翻译行为**与原版完全一致**。

> 一份 jar 因此**同时兼容新旧 Geyser**。全部包装器 `require = 0` / `remap = false`：若日后 Bedframe 改动这些调用点，补丁只是不生效，绝不崩服。处理器签名虽引用 `DisplayEntityTranslator` / `ModEntityTranslator`（仅出现在方法描述符里，**延迟解析**），在 API 缺失分支里不会执行任何 `checkcast`/构造，故**不会**把缺失的类拽进来。

### 3.8 基岩物品组件修复（`PolymerBedrockItemFixMixin`）

**现象（1.3.0 修复）**：基岩版玩家在**生存模式**下，**箱子和物品栏里的东西不显示、无法正常切换**，但**物品占位仍在**（数据转换不正确）。服务端日志成片出现：

```
[localSession-.../WARN]: 下游数据包错误！Exception while reading components for item 1549
```

**根因（Geyser 读不了模组数据组件）**：Geyser 以"Java 客户端"身份读取服务端下行数据包，用的是它自带的 MCProtocolLib，其 `DataComponentTypes.read(buf)` 只认识**原版**数据组件类型表：

```java
int id = readVarInt(buf);
if (id >= VALUES.size()) throw new IllegalArgumentException("Received id " + id + " ... maximum was " + VALUES.size());
```

当某个物品携带**模组数据组件**时，它的组件类型网络 id `>= VALUES.size()` → 抛 `IllegalArgumentException`。又因为下行物品组件走的是**无逐组件长度前缀**的"可信"路径，这一抛会**破坏整个物品栏数据包的分帧** → 该容器里的物品**全部读不出来**（占位还在、内容空白），与玩家反馈完全吻合。

实测定位到 **item 1549 = `waystones:attuned_shard`**：本服的数据组件类型表共 **113 项**，其中**原版 104 项（rawId 0–103）、模组 9 项（`waystones:*`，rawId 104–112）**。一枚**已绑定**的 attuned_shard 会在它的组件 patch 里携带 `waystones:attunement`（rawId 107），Geyser 一读到 107 就抛错。其余 `waystones:*` 组件（绑定卷轴等）同理。

为什么会下发到 Geyser：`waystones:attuned_shard` 这类是**普通（非 Polymer）模组物品**，Polymer 的 `getPolymerItemStack` 对它走最后的 `return itemStack;` 分支**原样放行**，于是原始物品连同它的模组组件一起发给基岩端。模组组件基岩端本就用不到，却恰好卡死了 Geyser 的读取。

**补法（1.3.1，只动基岩、不动 Java）**：Polymer 的 `class_1799` 流编解码器在编码每个外发物品时都会调用 `PolymerItemUtils.getPolymerItemStack(stack, ctx)`，这是**所有上线物品的唯一收口**。本补丁用 `@ModifyReturnValue` 包裹它的返回值，**仅当目标是基岩玩家**（`GeyserApi.isBedrockPlayer`）、且该物品的组件 patch 里确实含非 `minecraft` 组件时，把它**走 Polymer 自己的 `PolymerItemUtils.createItemStack(...)`**（用 `@Shadow` 复用）重新生成客户端物品：

- `createItemStack` 只拷贝一组固定的**原版**组件、**丢弃模组组件**，并保留**相同的物品 id**（Geyser 仍据此映射到正确的基岩物品）；
- 关键是它还会把**服务端真实物品**编码进 `minecraft:custom_data` 的 `$polymer:stack` NBT（**往返**数据）。Polymer 在 `class_1799` 编解码器上对**入站**物品调用 `getRealItemStack`，凡带该 NBT 的都会被**还原成服务端真实物品**——于是这些物品和**其它所有 Polymer 物品同路**，服务端入站拿到的是真实物品，物品栏点击/拿放与手持工具状态**保持同步**；
- 绝大多数物品（原版物品、已是 Polymer 物品的）走快速路径**原样返回**，不受影响；
- 若 `createItemStack` 不可用或没清干净，则**兜底**只做一次"以原型重建、仅回放 `minecraft` patch 项"的剥离（至少保证显示），再不行就返回原物品。

> **为什么不直接"裸剥离"**（1.3.0 的做法，已被 1.3.1 取代）：1.3.0 只在出站包上删掉模组组件、**没有往返 NBT**，于是 Geyser/客户端看到的物品与服务端**权威物品不一致**——入站时服务端无法还原，导致基岩玩家**拿取物品出现幽灵图标、放不下**，并连带**手持工具状态错乱使生存破坏判定再次失败**（偶发、原版物品也受影响）。改走 `createItemStack` 让这些物品具备和其它 Polymer 物品一样的往返一致性，从根上消除该副作用。

> Java 玩家走 `isBedrockPlayer` 判定直接原样返回，**完全不受影响**；基岩玩家少拿到的只是用不上的模组组件。整段逻辑包在 catch-all 里、`@ModifyReturnValue` 亦为 `require = 0`：万一出错或目标方法变化只是不生效、返回原物品，**绝不会**影响物品编码或崩服。`remap = false`：`PolymerItemUtils` 是 Polymer 类（其描述符本就用运行时存在的 intermediary `class_*` 名）。

## 4. 安装使用

1. 把 `hydraulic-leaffix-1.2.3.jar` 放进服务端的 `mods/` 文件夹。
2. （若之前装过旧版）务必删除旧的 `hydraulic-leaffix-*.jar`，同 mod id 不能并存。
3. 重启服务端。

不需要修改任何配置文件或资源包。

## 5. 验证是否生效

重启后查看启动日志：

```bash
# 取最新日志
LOG=$(ls -t logs/*.log 2>/dev/null | head -1)
grep -E "Failed to convert pack for mod waystones|HydraulicLeafFix" "$LOG"
```

- ✅ 成功：`Failed to convert pack for mod waystones` **消失**；可能出现 `[HydraulicLeafFix] Skipped a failing texture transformer (...)` 表示转换兜底在工作。
- ✅ 方块修复生效：出现 `[HydraulicLeafFix] Redirected break-particle texture to the block's own in-pack texture: ...`（每次启动只打印第一条）。
- ✅ 粒子刷屏消除（1.2.2）：成片的 `particle/xxx_n.png is missing. Please report this.` **不再出现**；原本每模组每粒子表各一次的崩溃日志，也收敛为每种粒子表一行的 `[HydraulicLeafFix] Skipped the crashing '<XxxParticleTransformer>' vanilla particle spritesheet transform ...`（整次启动约 5 行）。
- ✅ 生存破坏修复生效（1.2.3）：出现 `[HydraulicLeafFix] Lengthened Bedrock destructible_by_mining ... 5.0s -> 25.0s ...`（每次启动只打印第一条）。然后基岩版进服、生存模式下用镐子可正常挖掉石碑并掉落，裸手也能挖掉（较慢、无掉落，与 Java 一致），不再"挖掘失败、石碑还在"。
- ✅ 启动崩溃修复生效（1.3.0）：在**移除了实体 API 的 Geyser（如 2.9.6）**上，服务器**能正常启动**（出现 `Done (x.xxxs)!`），且打印一行 `[HydraulicLeafFix] Geyser custom-entity API is absent on this build; disabling Bedframe's entity/furniture translators ...`；启动日志中**不再**有 `NoClassDefFoundError: GeyserEntityDefinition` / `Could not execute entrypoint stage 'main' ... bedframe`。在**仍含该 API 的 Geyser（如 2.9.5）**上则不打印该行、行为照旧。`Registered N custom blocks` / `Registered N custom items` / `Registered resource pack` 仍照常出现。
- ✅ 基岩物品修复生效（1.3.0/1.3.1）：基岩玩家打开含已绑定石碑物品（如已绑定的 `waystones:attuned_shard`）的箱子/物品栏时，日志**不再**刷 `Exception while reading components for item ...`，物品可正常显示、拿取与放下；首次转换时打印一行 `[HydraulicLeafFix] Converted item(s) with non-vanilla data components to a Polymer round-trip stack ...`。Java 玩家不受影响。
- 然后基岩版进服，传送石碑应可正常显示，破坏碎屑不再是紫色。

## 6. 故障排查

- **普通石碑 `waystones:waystone` 在基岩版放不下（只剩半块、约 1 秒消失），但其它石碑都正常**：这是一个**残留的 Geyser 手写映射**与 Hydraulic 冲突所致，**与本模组无关**，需手动删除该文件：
  ```bash
  rm config/Geyser-Fabric/custom_mappings/waystones.json   # 或移出该目录；目录空着没关系
  ```
  原因：该文件把**仅** `waystones:waystone` 重定义为 `lodestone` 单方块（`unit_cube`）并**覆盖了它的方块物品**（启动日志中 `Registered 1 custom block item overrides.` 即来自它）。基岩端"放置"走的是**方块物品**这条路：被它劫持后客户端按单方块预测放置，与服务端真实的两格高石碑对不上 → 半块 + 秒消失。而**已放置方块的渲染**由 Hydraulic 接管（所以石碑能正常显示）。`custom_mappings/` 里只有这一个块，所以**只有普通石碑**受影响。删除后重启，日志应变为 `Registered 0 custom block item overrides.`，普通石碑即可与其它石碑一样正常放置。
  > Hydraulic 已完整负责所有石碑的注册，`custom_mappings/waystones.json` 是早期手动实验的遗留物，删除是安全的（不影响其它石碑）。

- **石碑仍不显示，但日志已无 waystones 转换失败**：Hydraulic 可能在用旧缓存。删除可再生缓存后再重启：
  ```bash
  rm -rf config/hydraulic/cache
  ```
- **出现新的转换器崩溃**（`extract` 之后的 `convert`/`include` 阶段也可能爆别的异常）：把完整启动日志中的新堆栈贴出来，按相同思路把兜底扩到对应方法即可。

## 7. 从源码编译

### 前置
- JDK：建议 Java 21+（仓库脚本用 `--release 21` 输出 Java 21 字节码；Java 25 亦可编译）。
- 编译依赖**直接取自服务端**（版本与运行时完全一致），因此请在服务端目录树内编译，或通过环境变量指定服务端根目录。

### 一键编译
```bash
# 默认把上级目录当作服务端根目录（本项目就放在服务端根目录下）
./build.sh

# 或显式指定服务端根目录
SERVER_ROOT=/path/to/server ./build.sh
```
脚本会：从服务端 `libraries/` 取 sponge-mixin、ASM 及**全部库 jar**（MC API 签名会牵出 datafixerupper/gson/guava/fastutil 等传递依赖，需在编译期解析）、从 `.fabric/` 取 MixinExtras 与**全部 `processedMods/`**（含 Geyser/Floodgate 拆分模块 `org.geysermc.event`/`org.geysermc.api` 与 `packet_tweaker`）、从 `.fabric/remappedJars/` 取 **intermediary 映射的 Minecraft jar**（`server-intermediary.jar`，供物品组件修复与实体禁用器引用 `net.minecraft.class_*`）、从 `mods/` 取 **Geyser**（`GeyserApi`）、`bedframe-*.jar`（解出与运行时一致的 `pack-converter` 类，并提供 `TranslationManager`/实体翻译器类型）、`hydraulic-fabric*.jar`（`Materials$Material` 形参类型）作为编译依赖，编译全部 Mixin 并打包出 `hydraulic-leaffix-1.3.0.jar`。

> **关于 `checker-qual`**：guava 的 class 文件带有 checker-qual 的 TYPE_USE 注解，javac 在补全 guava 符号时若缺这个注解 jar 会**直接崩溃**（不是警告）。本服务端未自带它，故 `build.sh` 在缺失时会把它下载到 `./.build-cache/`（仅首次需要联网；也可手动放一个 `checker-qual-*.jar` 进该目录）。

### 依赖说明（编译期 compile-only，运行期由服务端提供）
- `net.fabricmc:sponge-mixin`（`@Mixin`、`@At`）
- MixinExtras（`@WrapOperation`、`@WrapMethod`、`@ModifyArg`、`@ModifyReturnValue`、`Operation`）——Fabric Loader ≥0.15 已自带，运行时无需额外提供
- `org.geysermc.pack.converter:*`（被 Mixin 的目标类，由 Bedframe 内嵌提供）
- `org.geysermc.hydraulic:*`（`BlockPackModule`、`Materials$Material` 等，由 Hydraulic 提供）
- `lol.sylvie.bedframe:*`（`TranslationManager`、`DisplayEntityTranslator`、`ModEntityTranslator` 等，启动崩溃修复的处理器形参类型需精确类型，由 Bedframe 提供）
- **Minecraft（intermediary）** + **Geyser**（`GeyserApi`）+ **packet_tweaker**（`PacketContext`）——基岩物品组件修复需引用 `net.minecraft.class_*` 与这两者；运行期均由服务端提供
- 编译期还需 guava 的 `checker-qual` 注解 jar（见上方说明）

## 8. 项目结构

```
hydraulic-leaffix/
├── README.md
├── build.sh                                  # 复现编译脚本
├── hydraulic-leaffix-1.3.1.jar               # 编译好的成品
└── src/main/
    ├── java/dev/fwq/hydleaffix/mixin/
    │   ├── TextureConverterMixin.java         # 通用兜底（转换崩溃，主修复）
    │   ├── GridSpritesheetParticleTransformerMixin.java  # 粒子表：探测全缺失即跳过，消除 missing 刷屏 + 崩溃
    │   ├── BlockPackModuleMixin.java          # ① 把破坏碎屑贴图改指向方块自身的同包贴图（修紫块）；② 把基岩破坏计时从硬度改为裸手真实秒数（修生存破坏）
    │   ├── TranslationManagerMixin.java       # 启动崩溃修复：实体 API 缺失时禁用 Bedframe 实体翻译器（跨 Geyser 版本）
    │   └── PolymerBedrockItemFixMixin.java    # 基岩物品修复：带模组组件的物品走 Polymer createItemStack（含往返 NBT），修物品栏不显示 + 拿取/破坏错乱
    └── resources/
        ├── fabric.mod.json
        └── hydleaffix.mixins.json
```

## 9. 版本历史

- **1.3.1**：
  - `PolymerBedrockItemFixMixin` 改写——**修复 1.3.0 物品修复引入的副作用**：基岩玩家拿取/移动物品出现**幽灵图标、放不下**，且**生存破坏方块再次失败**（偶发，原版物品/方块亦受影响）。根因：1.3.0 仅在出站包**裸剥离**模组组件，未提供 Polymer 的 `$polymer:stack` **往返** NBT，使 Geyser/客户端与服务端**权威物品不一致**（入站无法还原）→ 物品栏交互与手持工具状态错乱。补法：改为把这些物品走 Polymer 自己的 `createItemStack`（`@Shadow` 复用），既丢模组组件又写入往返 NBT，使其与其它 Polymer 物品**同路、状态同步**；并保留"裸剥离"为兜底。已实测：mixin 命中 `PolymerItemUtils`、`@Shadow createItemStack` 解析成功、启动无异常（注：交互层面的最终效果需真实基岩客户端验证）。见 [§3.8](#38-基岩物品组件修复polymerbedrockitemfixmixin)。
- **1.3.0**：
  - `TranslationManagerMixin` 新增——**修复服务端在移除了自定义实体 API 的 Geyser（如 `2.9.6`）上无法启动**（`NoClassDefFoundError: GeyserEntityDefinition`，崩在 `TranslationManager.<init>`）。根因：Bedframe 0.1.0 针对较新 Geyser 编译、其 `TranslationManager` 字段初始化即构造引用该 API 的两个实体翻译器，而 2.9.6 移除了该 API。补法：用 `@WrapOperation` 在该 API 缺失时跳过这两处构造（并相应过滤 `List.of`、空操作 `ensureLateGenerated`），API 存在时（如 2.9.5）原样放行——**一份 jar 兼容新旧 Geyser**。见 [§3.7](#37-启动崩溃修复translationmanagermixin)。
  - `PolymerBedrockItemFixMixin` 新增——**修复基岩版生存模式下箱子/物品栏物品不显示、无法切换**（日志刷 `Exception while reading components for item <id>`）。根因：Geyser 自带 MCProtocolLib 只认识原版数据组件，物品上的**模组数据组件**（实测 `waystones:attuned_shard` 的 `waystones:attunement`）令其读取抛异常并破坏整个物品栏数据包分帧。补法：用 `@ModifyReturnValue` 在 Polymer 物品编码收口 `PolymerItemUtils.getPolymerItemStack` 处，**仅对基岩玩家**剥离物品组件 patch 里的非 `minecraft` 组件（基岩端用不到）；Java 玩家不受影响。见 [§3.8](#38-基岩物品组件修复polymerbedrockitemfixmixin)。
- **1.2.3**：
  - `BlockPackModuleMixin` 新增 `@ModifyArg`，**修复基岩版生存模式无法破坏石碑**（"挖掘失败、石碑还在、不掉落"；创造破坏 / 生存放置均正常）。根因：Hydraulic 把 Java **硬度**当作基岩 `destructible_by_mining` 的**秒数**下发（硬度 ≠ 秒），而石碑以非原版覆盖注册 → Geyser 走 `serverSideBlockBreaking`（服务端按工具计进度），基岩客户端却按那个偏短的计时**提前判定破坏**、服务端进度不足 → Geyser **还原方块**。补法：把该计时改写为**裸手真实秒数（硬度 × 5）**，使客户端不会先于服务端判定；**不改 `blockHardness`**，故破坏速度与掉落仍随手持工具、与 Java 一致。见 [§3.6](#36-生存模式破坏修复blockpackmodulemixin)。
- **1.2.2**：
  - `GridSpritesheetParticleTransformerMixin` 双层处理，**消除启动时约 1150 行 `... is missing. Please report this.` 刷屏**：① `@WrapOperation` 吞掉 `transform` 内部 `TransformContext.warn(String)` 打印的逐帧 missing 告警；② `@WrapMethod` 仍捕获越界崩溃保证整包转换继续，并**按转换器类去重**（每种粒子表一行，约 5 行；原先约 115 行）。源帧存在时不会产生该告警、照常转换。刻意不用 `peekOrVanilla` 探测方案，以**避免编译期引用以 jar-in-jar 提供、不在编译类路径上的 `net.kyori.adventure.key.Key`**。见 [§3.2](#32-粒子表兜底gridspritesheetparticletransformermixin)。
  - 文档化**普通石碑 `waystones:waystone` 放不下**（问题 ②）的真正根因：残留的 `config/Geyser-Fabric/custom_mappings/waystones.json` 把仅此一块重定义为 `lodestone` 单方块并覆盖其方块物品，与 Hydraulic 注册冲突，**删除该文件即修复**（非本模组改动）。见 [§6](#6-故障排查)。
- **1.2.1**：`BlockPackModuleMixin` 改为 `@WrapOperation` 包裹 `Materials$Material.textures()`，把 `minecraft:` 的 `#particle` 值改写为方块自己的**同包面贴图**，使基岩默认材质实例 `"*"`（破坏碎屑来源）指向确定存在的贴图 → 碎屑不再变紫。已实测：Mixin 正常命中、启动无异常、转换日志出现 `Redirected break-particle texture ...`。（注：当时推测此改动"很可能一并修复石碑放不下"，1.2.2 已查明放不下是上述残留配置文件所致，与本项无关。）
  - 撤销了 1.2.0 的两项尝试：① 把物品栏默认状态改成下半截（按需求**维持上半截**）；② `PackUtilMixin` 把贴图引用改成 `hydraulic:block/<名字>`——该引用属于**另一个包**，跨包解析不到，实测无效，故移除。
- **1.2.0**（已废弃尝试）：`PackUtilMixin` + `BlockPackModuleMixin(stringProperty 重排)`；实测三问题未解决（跨包贴图引用不生效 / 默认状态改动不影响图标）。
- **1.1.0**：通用兜底——包裹 `TextureConverter.extract` 中的转换器调用，任意转换器失败即跳过，覆盖粒子 + 地图图标等多个崩溃点。
- **1.0.0**：仅包裹 `GridSpritesheetParticleTransformer.transform`，验证了思路有效，但只挡住了树叶粒子这一个崩溃点。

## 10. 已知限制

- 石碑顶部由 `BlockEntityRenderer` 实时绘制的**悬浮名牌/罗盘**在基岩版无法显示（Geyser 固有限制），其余静态本体与贴图可还原。
- 物品栏缩略图按基岩自定义方块机制只显示**单个**状态的外观（当前为上半截）；按需求维持现状，未改动。
- 破坏碎屑修复改用方块**自身的面贴图**作为碎屑贴图（保证同包可解析）；与 Java 原版指定的 `#particle`（如 polished_andesite）相比贴图略有差异，但不再是紫色缺失块。
- **普通石碑 `waystones:waystone` 放不下**已查明并非本模组范围内的问题，而是残留 Geyser 配置文件 `config/Geyser-Fabric/custom_mappings/waystones.json` 与 Hydraulic 注册冲突；删除该文件即修复，详见 [§6](#6-故障排查)。
- 生存破坏修复以**裸手破坏耗时（硬度 × 5 秒）**为基岩客户端计时上限，已覆盖站立裸手/拿错工具等常见情况。极端情况下（如**水下 + 悬空**裸手挖，原版即有 ×5 / ×5 额外减速）实际服务端耗时可能超过该上限，届时仍可能"挖不掉"——这属罕见姿势，且失败是安全的（只是没破坏，不会损坏存档）；正常站立用镐子破坏不受影响。
- 启动崩溃修复在实体 API 缺失时**禁用 Bedframe 的实体/家具翻译器**：基岩端将无法看到由该功能渲染的模组**实体/家具**（本服上该功能本就无产出，故无实际损失）；石碑等**方块/物品**的转换不受影响。待 Bedframe 跟进新版 Geyser 后可恢复实体翻译。
- 基岩物品修复对基岩玩家把"带模组数据组件的物品"走 Polymer 的 `createItemStack` 重生成：基岩端本就无法使用模组组件，重生成后这些物品**不携带**模组组件数据（如石碑碎片的绑定目标在基岩端不可见），但**保留物品 id 与 `$polymer:stack` 往返**，故外观与物品栏/破坏交互均正常；Java 玩家完全不受影响。该修复依赖 Polymer 的 `PolymerItemUtils.getPolymerItemStack` / `createItemStack` 与 Geyser 的 `GeyserApi.isBedrockPlayer`，整段包在 catch-all 里且 `@ModifyReturnValue` 为 `require = 0`：相关签名若变化只是不生效、绝不崩服。
- 本模组依赖 Bedframe 内嵌的那一份 converter 被加载，并依赖 Hydraulic 的 `BlockPackModule` / `Materials$Material`、Bedframe 的 `TranslationManager`、Polymer 的 `PolymerItemUtils` 等类与方法签名。若日后移除相关模组或更换版本，目标类/方法可能变化；全部 Mixin 均 `required:false` / `require=0`，**未命中只记日志、绝不会让服务器崩**，只是对应补丁不生效。
