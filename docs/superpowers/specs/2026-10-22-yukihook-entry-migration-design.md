# YukiHook 迁入（阶段 B′：只换宿主）

- 日期：2026-10-22
- 状态：待主人评审
- 分类：Architectural（改动入口层与 hook 的宿主关系）
- 前置事实：`exp/yukihook-1.5` 分支已完成装机实测（YukiHook 1.5.0 可用、双入口共存成功、生成 `META-INF/xposed` 正常）

## 一、目标

1. 把模块入口换成 **YukiHook 单一入口**，`META-INF/xposed/{java_init.list,module.prop,scope.list}` 交由编译器生成，不再手写。
2. **不动现有 4711 行 hook 逻辑**（40 处 `hook()` / 86 处 `intercept`），只改它们的宿主与类型。
3. 保住热重载，并让**将来的增量迁移（阶段 C）与 YukiHook 2.0 都不必再动入口**。

## 二、非目标

- 不把 hook 改写成 YukiHook DSL / KavaRef（那是阶段 C）。
- 不引入 `YukiHookSharedFiles` / remote prefs / `ModuleScope`（阶段 C 起按需）。
- 不改任何跨品牌门禁（`isOurSheetActivity` / `ProbeBudget` / `rejectedNames` / `KNOWN_KEYS`）。

## 三、现状事实（实测）

| 项 | 值 |
|---|---|
| 入口 | `class XposedEntry : XposedModule()`，实现 `onModuleLoaded` / `onPackageReady` / `onHotReloading` / `onHotReloaded` |
| hook 规模 | `XposedEntry` 1543 行 / 27 hook / 54 intercept；`FastPairHookEntry` 2128 / 7 / 25；`HearableControlHook` 1040 / 6 / 7 |
| hook 类当前签名 | `FastPairHookEntry.onGmsLoaded(module: XposedModule, cl: ClassLoader)`；`HearableControlHook` 同样持 `XposedModule` |
| libxposed API 类型关系 | `XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface`；`XposedInterface` 提供 `hook(Executable)` / `hookClassInitializer` / `getInvoker` / `log(...)` |
| 需要一个 `XposedModule` 的理由 | 仅因为 `hook()` / `log()` 定义在 `XposedInterface` 上 —— 换成 `XposedInterface` 即可满足 |
| 热重载 | 现有 `onHotReloading` 负责注销 receiver（旧实例/旧 ClassLoader 下必须做），`onHotReloaded` 负责重装 |
| YukiHook 约束 | 一个构建变体只允许一个入口注解；热重载要求「只有一个 Java 入口」；源码目录存在 `resources/META-INF/xposed` 或 `assets/xposed_init` 会拒绝构建 |

## 四、设计

### 4.1 入口

新增唯一入口：

```kotlin
@YukiHookLibXposedEntry(
    minApiVersion = 101,
    targetApiVersion = 102,
    scope = ["com.google.android.gms", "com.android.settings"],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.AUTO,   // 与现手写 module.prop 的 autoHotReload=true 对齐
)
object YukiEntry : YukiHookXposedModule {
    override fun onInit() {
        registerFrameworkEvents {
            onModuleLoaded { param -> XposedEntry(this).onModuleLoaded(param) }
            onPackageReady { param -> XposedEntry(this).onPackageReady(param) }
            onHotReloading { param -> XposedEntry(this).onHotReloading(param) }
            onHotReloaded { param -> XposedEntry(this).onHotReloaded(param) }
        }
    }
    override fun onHook() { /* 阶段 B′ 不使用 YukiHook 的 hook DSL */ }
}
```

- `this` 在框架事件里即 `XposedInterface`，原样传给 `XposedEntry`。
- `staticScope = true`、`minApiVersion = 101`、`targetApiVersion = 102` 与现手写 `module.prop` 逐字段对齐。
- `hotReload = AUTO` **必须显式写**：现基线 `module.prop` 是 `autoHotReload=true`，单一 Java 入口下才合法（这正是 `javaEntries` 与热重载互斥的原因）。

### 4.2 现有入口降级为「逻辑宿主」

```kotlin
class EntryLogic(private val xposed: XposedInterface) {
    private fun hook(e: Executable) = xposed.hook(e)   // 垫片，40 处调用点一行不改
    ...
}
```

- **类名保留为 `XposedEntry`**（只去掉 `: XposedModule()` 继承、构造函数改为接收 `XposedInterface`）：
  `proguard-rules.pro` 与既有排查经验都按这个类名定位，改名属无谓风险。`YukiEntry` 只作为新入口。
- 因此实际形态是 `class XposedEntry(private val xposed: XposedInterface) { ... }`。
- 4 个 `override fun onXxx` 改为普通方法（参数类型 `ModuleLoadedParam` / `PackageReadyParam` / `HotReloadingParam` / `HotReloadedParam` 全部来自 `XposedModuleInterface`，是接口，可直接复用）。
- 垫片保证 `hook(m).intercept { chain -> ... }` 的调用点零改动。

### 4.3 hook 类的类型替换

- `FastPairHookEntry.onGmsLoaded(module: XposedModule, ...)` → `module: XposedInterface`
- `HearableControlHook` 同样替换
- 两处只改**参数类型与 import**，内部逻辑不动

### 4.4 META-INF/xposed 交接

1. 删除源码目录下的三个手写文件，留档到 `.bak_yukihook_entry/`。
2. 编译器生成到 build 目录；`module.prop` 只含 `minApiVersion/targetApiVersion/staticScope/autoHotReload` 四个字段。
3. **`id`/`name`/`version`/`description` 不再进 `module.prop`**：`name`/`description` 取自 `AndroidManifest` 的 `android:label`/`android:description`（已具备），`version` 显示取 APK 的 `versionName`（已实测）。
4. 发布核验判据不变 —— 核验的是 **APK 内**的 `META-INF/xposed/*`，生成的照样在。

### 4.4.1 R8 keep（必须做，否则 release 包入口会被裁掉）

`proguard-rules.pro` 现在写死的是 `-keep class com.fxxkmoondrop.secret.XposedEntry`，因为 **LSPosed 按类名字符串从 `java_init.list` 加载入口**。
换成生成入口后，`java_init.list` 里是 `com.fxxkmoondrop.secret.YukiEntry_YukiHookXposedInit`（名字由入口类名 + `_YukiHookXposedInit` 后缀生成，可用注解的 `entryClassName` 改写），**必须为它补 keep**：

```proguard
-keep class com.fxxkmoondrop.secret.YukiEntry_YukiHookXposedInit { *; }
```

现状的 keep 保留（`XposedEntry` 仍是逻辑宿主，且被入口直接引用）。若不补，release 包会**静默**变成"模块加载了但什么都不做"——这是最难查的一类故障。

### 4.5 构建链

- Kotlin `2.4.10` → `2.4.21`；新增 KSP `2.3.12`
- 依赖：`implementation(platform(yukihook-bom:1.5.0))` + `yukihook-core` + `yukihook-runtime-libxposed` + `ksp(platform(bom))` + `ksp(yukihook-compiler)`
  （**`ksp` 配置必须单独加一次 platform**，否则 compiler 版本解析为空）
- `compileOnly(io.github.libxposed:api:102.0.0)` 保留（运行时仍由 LSPosed 提供）

## 五、分阶段路线（B′ → C）

| 阶段 | 内容 | 验收判据 |
|---|---|---|
| **B′（本次）** | 4.1–4.5 | 见第六节 |
| C1 | 试点迁移 `HearableControlHook.kt`（最小：1040 行 / 7 hook / 7 intercept）到 YukiHook DSL + KavaRef | 试点文件内 hook 行为等价；宿主异常策略不劣化；真机 ANC/音量面板回归 |
| C2 | 迁移 `FastPairHookEntry.kt`（门禁最密集） | 跨品牌门禁逐条复测：Google 原生卡片不被认领、未收录型号探测上限 2 次、已证伪不再重连 |
| C3 | 迁移 `EntryLogic` 内剩余设置页注入等 hook | 设置页注入、配对弹窗、DexKit 定位全部回归 |

每阶段独立可回滚，且**每阶段都必须过一次真机**。

## 六、B′ 的验收判据（缺一不算完成）

1. CI 构建绿（单测 + Release 构建）
2. APK 内 `java_init.list` **只有一条**（生成的入口）；`scope.list` = `com.google.android.gms` + `com.android.settings`（逐字节与基线一致）
3. 签名指纹仍为 `f8f26c61…`
4. LSPosed 正确识别模块、作用域保留、**热重载可用**（改一次配置触发重载，确认 receiver 注销与重装无残留）
5. 真机功能不退化：设置页注入、配对弹窗、ANC、电量通知
6. 日志标签与关键路径日志不丢（`hookSettings` / `FastPairHook` / DexKit 定位）
7. **release 包（R8 开）**里 `YukiEntry_YukiHookXposedInit` 仍存在且未被改名（`dexdump`/`unzip` 核 `classes*.dex` 中的类名）

## 七、回滚与止损

- **回滚点**：B′ 是单分支单提交合并，出问题直接 revert 到 `65433c6` 之前；源码侧改动可完全还原（手写 META-INF 有 `.bak` 档）。
- **止损条件**（出现任一条即停止推进 C）：
  1. 热重载在 B′ 下无法达到与现状同等的清理语义
  2. 真机出现任一功能退化且一天内定位不到
  3. YukiHook 1.x 在 30 天内出现影响入口加载的缺陷

## 八、与 YukiHook 2.0 的关系

B′ 之后 **4711 行 hook 代码不依赖任何 YukiHook API**（只依赖 `XposedInterface` + 自研 `HookHelper`）。因此 2.0 到来时：
- 若 2.0 改了 DSL / 扩展模块 → 只影响 C 阶段已迁移的那部分文件，未迁移部分零影响
- 换宿主只需改 `YukiEntry` 一处
- 这也是选择 B′ 而不是直接冲 C 的主要战略理由

## 九、风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 入口新增 YukiHook runtime 依赖 | 它坏 = 模块不加载 | 固定 1.5.0；止损条件 3 |
| 构建链多一环 KSP | 新增失败方式（已踩 4 坑） | 坑已记录进记忆；`ksp(platform())` 已明确 |
| 热重载语义不完全等价 | receiver 残留 / 重复注册 | 第六节判据 4 专项验证 |
| 生成 `module.prop` 字段变少 | 旧 LSPosed 版本可能依赖 id/name | 已实测当前 LSPosed 不受影响；回滚点可用 |

## 十、后续变更：minSdk 提升到 Android 12（API 31）

已由主人拍板，**之后**执行；不属于 B′，独立提交。

**牵动面（实测）**

| 位置 | 内容 |
|---|---|
| `app/build.gradle.kts:19` | `minSdk = 26` |
| `DEVELOPMENT.md:14` | 文档表格 `minSdk / targetSdk | 26 / 36` |
| 约 20 处版本分支 | `DeviceNotif`(5) / `GaiaBleClient`(5) / `XposedEntry`(2) / `HeadsetDetectService`(2) / `AncTileService`(2) / `OnboardingActivity` / `EnvProbe` / `LogCollector` / `M3Ui` / `MiuixComponents` 里的 `SDK_INT` 与 `VERSION_CODES` 判断 |
| `AndroidManifest.xml` | `BLUETOOTH` 与 `BLUETOOTH_SCAN(maxSdkVersion=…)` 两条旧权限可收（31+ 由 `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` 取代） |

**收益**

1. 运行时权限模型简化：`BLUETOOTH_CONNECT` 成为必填，不再有"31 以下没这个权限"的分支
2. 删掉一批 `if (SDK_INT >= S)`，减少一类"只在老系统上错"的静默失败
3. 收窄 issue #16 的验证面：作者要求"在 API 26–30 上也要验"的部分不再需要

**代价 / 风险**

1. **Android 8–11 的用户将再也收不到更新**（破坏性变更），必须在 CHANGELOG 与发布页明示
2. 老 OEM 机型上的蓝牙行为差异不再被覆盖

**顺序**：B′ 验完 → 再单独做 minSdk 31（一次性、可回滚、影响面清晰）
