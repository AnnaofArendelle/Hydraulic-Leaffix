# Hydraulic Pack-Converter Crash Fix (`hydraulic-leaffix`)

一个极小的 Fabric Mixin 模组，用于修复 **基岩版（Geyser）玩家看不到模组方块**（典型：传送石碑 Waystones 不可见、但可右键弹出 GUI）的问题。

- **版本**：1.1.0
- **目标 Minecraft**：1.21.11（Fabric）
- **运行依赖**：服务端需同时存在 Geyser + Hydraulic + Bedframe（本模组是给它们"打补丁"）
- **许可证**：MIT

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

2. **`GridSpritesheetParticleTransformerMixin`（针对性兜底，双保险）**
   用 `@WrapMethod` 单独包裹粒子转换器的 `transform`，万一主兜底未命中也能挡住已知的粒子崩溃。

两个 Mixin 均 `remap = false`（目标是第三方库类，非 Minecraft 类，无需 Yarn/refmap 重映射），`required = false`（即使未命中也只记日志，**绝不会让服务器崩**）。

补好之后，Hydraulic 用模组**真实的 Java 贴图 + 转换后的方块几何**生成 Bedrock 资源，所以基岩版外观会高度还原 Java 版。

## 4. 安装使用

1. 把 `hydraulic-leaffix-1.1.0.jar` 放进服务端的 `mods/` 文件夹。
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

- ✅ 成功：`Failed to convert pack for mod waystones` **消失**；可能出现 `[HydraulicLeafFix] Skipped a failing texture transformer (...)` 表示兜底在工作。
- 然后基岩版进服，传送石碑应可正常显示。

## 6. 故障排查

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
脚本会：从服务端 `libraries/` 取 sponge-mixin 与 ASM、从 `.fabric/` 取 MixinExtras、从 `mods/bedframe-*.jar` 解出与运行时一致的 `pack-converter` 类作为编译依赖，编译两个 Mixin 并打包出 `hydraulic-leaffix-1.1.0.jar`。

### 依赖说明（编译期 compile-only，运行期由服务端提供）
- `net.fabricmc:sponge-mixin`（`@Mixin`、`@At`）
- MixinExtras（`@WrapOperation`、`@WrapMethod`、`Operation`）——Fabric Loader ≥0.15 已自带，运行时无需额外提供
- `org.geysermc.pack.converter:*`（被 Mixin 的目标类，由 Bedframe 内嵌提供）

## 8. 项目结构

```
hydraulic-leaffix-1.1.0/
├── README.md
├── build.sh                                  # 复现编译脚本
├── hydraulic-leaffix-1.1.0.jar               # 编译好的成品
└── src/main/
    ├── java/dev/fwq/hydleaffix/mixin/
    │   ├── TextureConverterMixin.java         # 通用兜底（主修复）
    │   └── GridSpritesheetParticleTransformerMixin.java  # 粒子针对性兜底
    └── resources/
        ├── fabric.mod.json
        └── hydleaffix.mixins.json
```

## 9. 版本历史

- **1.1.0**：通用兜底——包裹 `TextureConverter.extract` 中的转换器调用，任意转换器失败即跳过，覆盖粒子 + 地图图标等多个崩溃点。
- **1.0.0**：仅包裹 `GridSpritesheetParticleTransformer.transform`，验证了思路有效，但只挡住了树叶粒子这一个崩溃点。

## 10. 已知限制

- 石碑顶部由 `BlockEntityRenderer` 实时绘制的**悬浮名牌/罗盘**在基岩版无法显示（Geyser 固有限制），其余静态本体与贴图可还原。
- 本模组依赖 Bedframe 内嵌的那一份 converter 被加载。若日后移除 Bedframe 或更换 Hydraulic/Bedframe 版本，目标类可能变化，需要相应调整（`required:false` 保证此时不会让服务器崩，只是补丁不生效）。
