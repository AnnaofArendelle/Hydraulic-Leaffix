# Hydraulic Pack-Converter Crash Fix (`hydraulic-leaffix`)

一个极小的 Fabric Mixin 模组，用于修复 **基岩版（Geyser）玩家看不到 / 用不了模组方块**（典型：传送石碑 Waystones）的一系列问题。

- **版本**：1.2.2
- **目标 Minecraft**：1.21.11（Fabric）
- **运行依赖**：服务端需同时存在 Geyser + Hydraulic + Bedframe（本模组是给它们"打补丁"）
- **许可证**：MIT

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

## 4. 安装使用

1. 把 `hydraulic-leaffix-1.2.2.jar` 放进服务端的 `mods/` 文件夹。
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
脚本会：从服务端 `libraries/` 取 sponge-mixin 与 ASM、从 `.fabric/` 取 MixinExtras、从 `mods/bedframe-*.jar` 解出与运行时一致的 `pack-converter` 类、从 `mods/hydraulic-fabric*.jar` 取 Hydraulic 类（供 `@WrapOperation` 处理方法声明真实的 `Materials$Material` 形参类型）作为编译依赖，编译全部 Mixin 并打包出 `hydraulic-leaffix-1.2.2.jar`。

### 依赖说明（编译期 compile-only，运行期由服务端提供）
- `net.fabricmc:sponge-mixin`（`@Mixin`、`@At`）
- MixinExtras（`@WrapOperation`、`@WrapMethod`、`Operation`）——Fabric Loader ≥0.15 已自带，运行时无需额外提供
- `org.geysermc.pack.converter:*`（被 Mixin 的目标类，由 Bedframe 内嵌提供）
- `org.geysermc.hydraulic:*`（`BlockPackModule`、`Materials$Material` 等，由 Hydraulic 提供；**注意**：MixinExtras 的 `@WrapOperation` 处理方法形参必须用**精确类型**，不能用 `Object`，故需此编译依赖）

## 8. 项目结构

```
hydraulic-leaffix/
├── README.md
├── build.sh                                  # 复现编译脚本
├── hydraulic-leaffix-1.2.2.jar               # 编译好的成品
└── src/main/
    ├── java/dev/fwq/hydleaffix/mixin/
    │   ├── TextureConverterMixin.java         # 通用兜底（转换崩溃，主修复）
    │   ├── GridSpritesheetParticleTransformerMixin.java  # 粒子表：探测全缺失即跳过，消除 missing 刷屏 + 崩溃
    │   └── BlockPackModuleMixin.java          # 把破坏碎屑贴图改指向方块自身的同包贴图（修紫块）
    └── resources/
        ├── fabric.mod.json
        └── hydleaffix.mixins.json
```

## 9. 版本历史

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
- 本模组依赖 Bedframe 内嵌的那一份 converter 被加载，并依赖 Hydraulic 的 `BlockPackModule` / `Materials$Material` 类与方法签名。若日后移除 Bedframe 或更换 Hydraulic/Bedframe 版本，目标类/方法可能变化；全部 Mixin 均 `required:false` / `require=0`，**未命中只记日志、绝不会让服务器崩**，只是对应补丁不生效。
