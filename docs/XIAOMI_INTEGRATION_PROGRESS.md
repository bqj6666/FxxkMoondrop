# 小米 HyperOS 接入 · 进度档案

> **本文件是防上下文丢失的实时落盘点。每完成一个可回滚的步骤就更新一次。**
> 恢复工作时先读本文件，再读 `docs/superpowers/specs/` 下的设计文档。

最后更新：2026-09-29（会话开始时建立）

---

## 阶段总览

| 期 | 内容 | 状态 |
|---|---|---|
| **P0** | DexKit 升级 + `XiaomiProbe` 检测门禁 + 设置页门禁 | ✅ 已完成 |
| **P1** | 载体 ID 映射 + 主题二维正交 + 风格切换 | ✅ 已完成 |
| **P2** | Miuix 轨渲染器实装 + 系统入口 hook | ⏳ 未开始（下一步） |
| **P3** | 焦点岛实装（`focus-api:1.4`） | ⏳ 未开始 |

---

## 已定决策（不可反复）

1. **协议层 L1 完全复用，一行不改** —— `GaiaBleClient` / `GaiaCommands` / `GaiaRfcommTransport` / `DeviceMatcher`
2. **方案 1** —— 引入 Compose + Miuix，主题双轨（Material You / HyperOS）
3. **型号 → HyperOS Device ID 动态映射**（用户选 2.B），不是固定 `01010607`
4. **参考项目只读不抄** —— 用我们自己的 DexKit + API 102 热重载实现
5. **检测门禁三级判定，全部不依赖 root**（root 只是运行保证，不是检测手段）
6. **分期交付**，每期可独立验证
7. **破坏性零容忍** —— 非小米设备上一个字节都不执行；映射失败只降级，不影响核心功能

## 环境事实（实测，勿再重复探测）

```
我们的技术栈：
  Compose        无（纯 View + Material 1.14）
  minSdk         26
  compileSdk     35  targetSdk 36
  Xposed API     io.github.libxposed:api:102.0.0  ✅ 已在用
  热重载          onHotReloading / onHotReloaded  ✅ 已有清理逻辑
  DexKit         2.3.0  ✅
  root 探测      EnvProbe.isRooted()  ✅ 已就绪
  源码位置        仓库根 src/（不是 app/src），sourceSets 里 java.srcDirs("../src")

参考项目（只读）：
  PuddingPods    源码私有，只有 PUDDING_ADAPTATION.md 文档
  HyperPods      公开 3272 行，用 YukiHookAPI（我们是裸 Xposed，不能抄写法）
  OppoPods       公开 13291 行，Focus Island 实现最完整
  Miuix          top.yukonga.miuix.kmp 0.9.2
  focus-api      com.xzakota.hyper.notification:focus-api:1.4

HyperOS hook 目标类（来自 HyperPods/OppoPods 实测）：
  com.xiaomi.bluetooth
  com.milink.service
  miui.systemui.devicecenter.devices.DeviceInfoWrapper   ← 设备中心卡片
  miui.systemui.controlcenter.panel.main.MainPanelController
  com.android.bluetooth.ble.app.MiuiBluetoothNotification
  MiuiHeadsetActivity                                     ← 高级耳机页
  com.android.settings.bluetooth.BluetoothDeviceDetailsFragment

PUDDING 协议（来自 PuddingPods 文档，可信）：
  Device ID    01010607
  传输         RFCOMM/SPP  00001101-0000-1000-8000-00805f9b34fb
  ANC          TX 00 1D 40 04 <mode>   关闭00/自适应01/通透02/抗风03/基础04
  电量         TX 00 1D 1A 00 / 00 1D 1A 01 01 02   回包 00 1D 1B（类型1左2右3盒）
  增益         TX 00 1D 1E 01 查 / 00 1D 1E 02 <0|1|2>
  指示灯       TX 00 1D 26 01 查 / 00 1D 26 02 <0|1>
```

---

## 日志

### 2026-09-29 · 会话开始

**目标**：接入小米 HyperOS 系统集成

**探索结论**：
- PuddingPods 源码私有；可读的是 HyperPods + OppoPods
- 我们已有 `GaiaRfcommTransport`，与 PuddingPods 同为 RFCOMM 路线 ✅
- 我们已有 API 102 热重载 + DexKit 2.2.0，是相对参考项目的核心优势
- 阻塞点：我们零 Compose，Miuix 必须靠 Compose

**用户决策**：
- GAIA 层完全用我们的
- 方案 1（Compose + Miuix 双轨主题）
- 不抄，用我们自己的 DexKit + API 102 方案
- Device ID 动态映射
- 分期做，实时保存进度
- 先升级 DexKit

**本次动作**：
- DexKit 2.2.0 → 2.3.0（2 天前发布）

### 2026-09-29 · DexKit 升级完成 ✅

**改动**：`app/build.gradle.kts` — `org.luckypray:dexkit:2.2.0` → `2.3.0`

**验证**：
- 联网解析成功（`--offline` 会 FAILED，因为本机无新版缓存）
- 4 个 ABI 的 `libdexkit.so` 齐全，arm64 从 414728 → 434788 字节
- `ndk.abiFilters` 仍只留 arm64/armeabi-v7a，x86 已正确剔除（APK 内 lib/x86 计数 0）
- `assembleDebug` 通过，APK 8.2M
- **单变体 `testDebugUnitTest` 102 项全过**（debug/release 不要相加）
- 提交 `736a1b9`

**踩坑记录**：
- `JAVA_HOME` 必须设 `java-21-openjdk-arm64`。默认的 JDK 25 会让 Gradle 报
  `What went wrong: 25.0.4.1` —— 那串数字是 JDK 版本号，不是 SDK 错误。
  Kotlin 内嵌编译器解析不了 JDK 25。
- 只跑 `./gradlew dependencies` 不会下载 aar，必须真正 `assembleDebug`。

**下一步**：P0 主体 —— `XiaomiProbe` 检测门禁


### 2026-09-29 · P0 完成 ✅ `c2affb3`

**新增文件**：
- `src/com/fxxkmoondrop/secret/XiaomiProbe.kt`（约 230 行）
- `app/src/test/java/com/fxxkmoondrop/secret/XiaomiProbeTest.kt`（13 项）

**关键设计决策（勿推翻）**：

1. **检测三级判据全部不依赖 root**。理由：模块支持无 root 运行，
   检测依赖 `su` 会让无 root 小米用户永远拿不到功能。
   - L1 `Build.BRAND`/`MANUFACTURER` 精确匹配 {xiaomi,redmi,poco,black sesame}
   - L2 反射 `android.os.SystemProperties.get` 读 `ro.miui.ui.version.name` /
     `ro.mi.os.version.name`（MIUI 与 HyperOS 两代都认）
   - L3 `PackageManager` 查独有包 `com.miui.securitycenter` 等
   - 三者是**或**关系，任一命中即认定小米

2. **品牌必须精确匹配，不能用子串**。`XiaomiFake` / `notxiaomi` 不能误判 ——
   反过来，用包名做子串又会把「作用域里有 `com.xiaomi.bluetooth`」误当本机是小米。

3. **`evaluate()` 是纯逻辑静态方法**，单测直接调真实实现。
   ⛔ 不要在测试里复制一份逻辑 —— 那会导致实现改了测试还绿（已踩过一次，已改回）。

4. **L3 包查询必须 try 包住**。PM 异常时降级为「该级不命中」而非崩掉整次检测
   （这是测试发现的真实缺陷，实现已加固）。

5. **设置页小米分组刻意「置灰而不隐藏」**。静默消失会让人以为装漏了。
   沿用 3.2.12 的置灰范式：row 与 trailing switch **各自**禁用
   （`row.isEnabled=false` 传不到 MaterialSwitch，这是本项目的老坑）。

**验证**：`testDebugUnitTest` **115 项全过**（102 + 新增 13），
`assembleDebug` 通过，APK 8.5M。

**踩坑**：
- Kotlin 里 `_` 是保留名，lambda 参数不能叫 `_`（本项目旧代码用 `_` 是
  因为它是 catch/解构位置，函数参数位不行）。
- 辅助函数写成 `private fun noPkg(_pkg: String) = false` 会因尾随 lambda 解析
  产生歧义，改成 `private val noPkg: (String) -> Boolean = { false }` 才稳。

**下一步**：P1 —— Device ID 映射 + Miuix 轨骨架 + 主题切换


### 2026-09-29 · 关键发现：silverpoetry/HyperEars

用户要求「看看上游和 fork 有没有」→ 找到**全网唯一支持水月雨的 HyperOS 项目**。

仓库：`github.com/silverpoetry/HyperEars` · 37809 行 · AGPL-3.0 · 多模块
（`protocol` / `integration` / `system-module` / `protocol-test`）
适配厂商：Apple / Bose / Edifier / Honor / Huawei / **Moondrop** / NiceHck /
OPPO / QCY / Rose / Sony / StarRing / Technics / Vivo

### 三个决定性发现

**1. 载体 ID 不是「型号→ID」，是「形态→ID」** ⭐ 核心设计

`system-module/.../hook/MiLinkCarrierIdentity.kt`：

```kotlin
const val TWS_DEVICE_ID = "01010607"       // TWS 形态
const val HEADPHONES_DEVICE_ID = "01013A04"  // 小米 O70C 头戴
```

原文关键注释（决定我们照抄这个思路的理由）：

> These IDs are **compatibility carriers, not model identity**.
> adapters must **never depend on** the Xiaomi model represented by this value.

→ 映射键是**形态**（TWS / 头戴）而非型号，因此**不存在硬编码型号表**，
新增形态只需加一行。`01010607` 与 PuddingPods 文档一致，互为印证。

**2. 主题是二维正交的，不是一个下拉框**

`ui/theme/UiPreferences.kt`：

```kotlin
enum class UiThemeMode { SYSTEM, LIGHT, DARK }        // 亮暗
data class UiPreferences(
    val style: UiStyle = UiStyle.MIUIX,                // 风格
    val themeMode: UiThemeMode = UiThemeMode.SYSTEM,
    val navigationBlur: Boolean = false,
    val floatingNavigationBar: Boolean = false,
    val interfaceScale: Float = 1.0f,                  // 0.9~1.1
)
```

原文：
> Renderer-specific values remain persisted when another renderer is selected
> → **切换风格不丢另一套的专属设置**（Miuix 的模糊/悬浮栏/缩放等保留）

**3. OppoPods 的 `device_models.json` 不能用于我们**

那张表 137 个型号 / 132K，但**全是 OPPO(56) realme(46) OnePlus(32) DIZO(3)**，
**一个水月雨都没有**。它读表不用硬编码的方式值得学，但表本身对我们无用。

### ⚠️ 协议矛盾（三处说法不一致，待实机验证）

| 来源 | ANC 命令 | 模式值 |
|---|---|---|
| PuddingPods 文档 | `1D 40 04` | 关闭00 **自适应01** 通透02 抗风03 基础04 |
| HyperEars | `1D 40/41 03/04` | 三档 |
| **我们** | `F_ANC_V2=32` `SET=4` | OFF0 ON1 **通透2** 抗风3 **自适应4** LIVE5 |

我们的通透/自适应与 PuddingPods 错位。**用户已裁定：按我们现状不动**
（`GaiaCommandsTest` 23 项覆盖 + 记忆里 PUDDING 走 GAIA v4 为实测结论）。
差异已记录，将来实机验证时再处理。**L1 协议层继续零改动。**

### 用户本轮裁定

1. 映射键用**形态**（TWS/头戴）—— 采纳
2. PUDDING 协议**按我们的**—— 采纳
3. HyperEars 可**学设计思路**（AGPL-3.0，不抄代码）


### 2026-09-29 · P1 完成 ✅ `d1fa7fb`

**新增**：
- `src/com/fxxkmoondrop/secret/XiaomiCarrierId.kt`（载体映射，8 项测试）
- `src/com/fxxkmoondrop/secret/UiStyle.kt`（主题风格，8 项测试）
- 设置页「外观」新增「界面风格」下拉（复用 `M3Ui.dropdownRow`）

**决策要点（勿推翻）**：

1. **映射键是形态不是型号** → `TWS→01010607` / `头戴→01013A04`。
   未登记形态**返回 null 绝不兜底**。写 `when` 不写 map：新增形态编译器强制补齐。
2. **主题二维正交**：亮暗（既有 `theme_mode` 0/1/2，不动）⊥ 风格（`UiStyle`）。
   不是四选项下拉框。
3. **默认 `MATERIAL`**：存量用户升级后界面必须与上一版**完全一致**。
   Miuix 轨不引入即不加载。
4. **切换不丢设置**：各轨专属键独立（`ui_miuix_*`），切换不清空既有值。
5. **存储用字符串名不用序号**：增删档位不错位。
6. **`clampScale` 必须挡 NaN/Inf**：直接传给 `View.setScaleX` 会崩。

**踩坑（Kotlin 测试）**：
- 反引号函数名里**不能有 `.` 或 `..`**（`0.9 到 1.1` 直接编译失败）。
- 我自己写错过一个断言：`clampScale(0.5f)` 应钳到 `MIN_SCALE=0.9` 而非默认值 1.0。
  实现是对的，是测试写错 —— 这类错误要分清是「实现 bug」还是「测试 bug」。

**验证**：`testDebugUnitTest` **131 项全过**（115 + 8 + 8），
`assembleDebug` 通过，APK 8.6M。

**P1 尚未完成的部分**：Miuix 轨的**渲染器**（Compose + Miuix 实际渲染）未实装，
本期只落了偏好存储与切换通路。切到 HyperOS 目前不改变界面外观 ——
这在 P2 补上。**切换通路本身已可用且不破坏 Material 轨。**

**下一步**：P2 —— 引入 Compose + Miuix 实装渲染器，以及系统入口 hook


## ⚠️ 2026-09-29 工具链升级受阻（重要，非代码问题）

### 起因

Miuix（HyperOS 观感轨）**所有版本**（0.9.0 / 0.9.1 / 0.9.2）的
`aar-metadata.properties` 都写死 `minCompileSdk=37`，我们 compileSdk 35 用不了。
用户拍板「升级工具链搞定」。

### 发现的第一个坑：Android 17 改了平台编号

`dl.google.com/android/repository/repository2-3.xml` 里 API 37 的平台包是：

```
platforms;android-37.0   → platform-37.0_r02.zip   (ApiLevel=37.0)
platforms;android-37.1   → platform-37.1_r01.zip   (ApiLevel=37.1)
platforms;android-37.2   → platform-37.2_r01.zip
```

**没有裸 `platforms/android-37`**。我们把 `platform-37.0` 装到
`/workspace/sdk/platforms/android-37` 后，AGP 8.6.1 报
`Failed to find Platform SDK with path: platforms;android-37`。

→ 用 API 37 必须同时升 AGP（8.6.1 只认裸编号目录）。

### 第二个坑（真正的阻塞）：本机 JDK 查不到文件系统

升级到 **Gradle 9.6.0 + AGP 9.4.1 + Kotlin 2.4.10** 后，Gradle **连启动都失败**：

```
Could not create service of type FileSystem using FileSystemServices.createFileSystem().
  > java.io.IOException: Mount point not found
Caused by:
  at java.nio.file.Files.getFileStore(Files.java:1497)
  at org.gradle.api.internal.file.temp.TempFiles.createTempFile(TempFiles.java:63)
```

**根因实测**（写了 `/tmp/FS.java` 直接调 `Files.getFileStore`）：

```
/tmp        炸: Mount point not found
/workspace  炸: Mount point not found
/           炸: Mount point not found
/data       炸: Mount point not found
/dev        OK   ← 只有这一个能查
```

JDK 21 和 JDK 25 **表现一致**，所以与版本无关。`/proc/self/mountinfo`
只有 27 行、是 Android 的 tmpfs/seclabel 布局，**没有根挂载记录**，
JDK 的 `LinuxFileStore.findMountEntry` 因此对绝大多数路径抛
"Mount point not found"。

Gradle 8.9 不调 `getFileStore` 建临时目录，所以此前一直没事；
Gradle 9 调了，在这个 PROot/容器环境里**无解**（不是配置问题，
无法靠参数绕过 —— 试过 `-g`、`GRADLE_USER_HOME` 指到 /dev，仍同样失败）。

### 参考项目的工具链（说明为什么它们没事）

| 项目 | AGP | Gradle | Kotlin | 它们跑在哪 |
|---|---|---|---|---|
| **我们** | 8.6.1→9.4.1 试 | 8.9→9.6.0 试 | 2.3.21→2.4.10 试 | 本机 PROot（Gradle 9 起不来） |
| OppoPods | 9.1.0 | 9.4.1 | 2.4.0 | 正常 CI/开发机 |
| HyperEars | 9.4.0 | 9.6.0 | 2.4.10 | 正常 CI/开发机 |

### 当前仓库状态

**⚠️ 处于编译不过的中间态**：`compileSdk=37` + Compose 依赖已写入
`app/build.gradle.kts`，工具链版本已改，但 Gradle 9 起不来，因此无法验证。
**恢复方式**：`git checkout build.gradle.kts app/build.gradle.kts gradle/wrapper/gradle-wrapper.properties`

### 结论

工具链升级**不是代码问题，是本机 Linux 环境的能力边界**。
可行的替代路径（待用户选）：
  - **CI 上构建**：GitHub Actions 是完整 Linux 容器，Gradle 9 能正常跑；
    本机只做代码编辑与逻辑单测（但单测也要 gradle，同样受限）
  - **回到 3.2.12 基线 + 方案 B**：不依赖 Miuix，用 Compose Material3
    自建 HyperOS 观感（仍需 Compose，但 Compose 本身不要求 compileSdk 37）
  - **降级目标**：接受 Material 单轨，HyperOS 观感后续再说


## 2026-09-29 工具链全量升级（用户选定方案 A：由 CI 验证）

**决定**：本机 Gradle 9 起不来（见上节实测），因此**保留升级并由 GitHub Actions 验证**。
本机只能编辑代码，无法构建也无法跑单测 —— 这是方案 A 的既定代价。

### 已完成的改动

| 项 | 旧 | 新 | 依据 |
|---|---|---|---|
| AGP | 8.6.1 | **9.4.1** | AGP 最新稳定（9.5.0 还是 alpha） |
| Gradle | 8.9 | **9.6.0** | 与 HyperEars 同档 |
| Kotlin | 2.3.21 | **2.4.10** | 与 HyperEars 同档；Compose 插件须与 kotlin 一致 |
| compileSdk | 35 | **37** | Miuix 全部版本 minCompileSdk=37 |
| targetSdk | 36 | **36 不变** | 升的只是编译期 API，运行时行为不变 |
| CI JDK | 17 | **21** | 与 jvmTarget / compileOptions 对齐 |
| CI SDK | platform-34 / bt-34.0.0 | **platform-37 / bt-36.0.0** | 匹配 compileSdk |

### AGP 9 的 breaking change 处理

1. **`android.lint` 已废弃** → 移到顶层 DSL。参照 HyperEars（AGP 9.4）的写法。
   语义保持不变（`checkReleaseBuilds=false` / `abortOnError=false`）。

2. **`postEdf` 的 build-tools 34.0.0 硬编码** → 改为**按版本号倒序自动探测**。
   这是发布链的关键环节（注入 scope.list/ascope.list + 重签），硬编码版本号
   在 SDK 升级时必然失效且表现为「构建莫名中断」。现在取最新的可用 apksigner，
   找不到时报错会列出已装版本。

3. **jvmTarget / compileOptions 17 → 21**，与 CI 的 JDK 21 三处同步。
   混编（Kotlin 21 + Java 目标 17）会出警告或失败。

### ⚠️ 未验证项（必须由 CI 回答）

本机无法构建，以下**全部未经验证**：
- AGP 9.4.1 + Gradle 9.6.0 能否正常配置本项目
- compileSdk 37 能否在 CI 装到（Android 17 平台是小版本编号，
  `sdkmanager "platforms;android-37"` 能否命中待验证）
- 131 项单测是否仍全过
- **`postEdf` 是否仍正常**（AGP 8→9 的 assembleRelease 行为变化）
- Compose + Miuix 能否编译通过
- APK 体积增量

**下一步：push 后看 CI run 结果。CI 失败则按报错逐项修。**


## ✅ 2026-09-29 工具链升级完成（本机可用了！我之前的结论是错的）

### 重要更正

上一节写的「本机 Gradle 9 起不来」**是错误结论**，已推翻。用户的质疑是对的，
我当时只试了 `GRADLE_USER_HOME` 就下了结论，而那两次其实是**下载超时**，
不是启动失败 —— `gradle-9.6.0-bin` 早就在 `~/.gradle/wrapper/dists/` 里了。

### 真正的根因与解法

不是「环境不可用」，而是**两处路径的 `Files.getFileStore()` 会抛
"Mount point not found"**（JDK 的 `LinuxFileStore` 找不到挂载记录；
`/proc/self/mountinfo` 只有 27 行且无根挂载记录）。

实测可用性：
```
/dev, /dev/gtmp, /dev/gh2, /dev/gh2/tmp   OK
/tmp, /workspace, /root/.gradle           炸
```

**解法（两条同时生效，缺一不可）**：

```bash
export GRADLE_USER_HOME=/dev/gh2                      # 指向 /dev 下的真实目录
java -Djava.io.tmpdir=/dev/gtmp2 -cp <gradle-launcher.jar>      org.gradle.launcher.GradleMain <task>            # 必须直接启 launcher
```

⚠️ 只设 `GRADLE_USER_HOME` 不够：还有 `TempFiles` 用系统 tmpdir（`/tmp`）。
⚠️ `JAVA_OPTS` / `GRADLE_OPTS` / `-Dorg.gradle.jvmargs` **都无效**，
因为 FileSystem 服务在读这些之前就建好了，必须用 launcher JVM 的 `-D`。

**代价**：`/dev/gh2` 是真实副本（3.8G），不能用符号链接（实测链接目录也炸）。
放在 /dev 下会随重启丢失，需要时重建。

### AGP 9 的 6 处 breaking change（全部已修）

| # | 问题 | 修法 |
|---|---|---|
| 1 | `org.jetbrains.kotlin.android` 插件**不再需要**，AGP 9 直接报错 | 从 app 移除该插件 |
| 2 | `sourceSets` 的 `srcDirs()` 已废弃 | 改 `directories.add()`，**且必须同时注册 kotlin + java** |
| 3 | `file(File, String)` 重载移除（第二参要 `PathValidation`） | 改用 `java.io.File` 拼接 |
| 4 | manifest 里的 `<uses-sdk>` **禁止**控制 SDK 版本，merger 直接失败 | 移除（build.gradle.kts 是权威源，值本就一致） |
| 5 | `tasks.registering` 的 by-delegate 委托已废弃 | 仅 deprecation 警告，**保留原写法不动**（发布链关键环节，不冒险） |
| 6 | `android.lint` 位置 | 保持 `android {}` 内（**我一度误移到顶层并已改回**） |

#### ⚠️ 最隐蔽的一个（#2 的子坑）

只写 `java.directories.add("../src")` 会让 `compileDebugKotlin` 报 **NO-SOURCE**，
随后 Java 侧报一堆 `cannot find symbol: AppLog.hex` —— 症状（Java 找不到 Kotlin
生成的静态方法）离根因（Kotlin 根本没编译）隔了两层，极易误判为「Kotlin 2.4
改了 @JvmStatic 行为」。**必须 kotlin + java 两个都注册。**

### 验证结果（本机实测，非推测）

- `testDebugUnitTest`：**131 项全过**，0 失败（结果文件时间戳确认是本次跑的）
- `assembleDebug`：**BUILD SUCCESSFUL**
- `assembleRelease`：**packageRelease 失败于签名**（`app2.keystore` 口令本机没有，
  CI 才注入）—— 这是环境限制，**不是工具链问题**

### ⚠️ APK 体积代价（需要用户决策）

| 版本 | 体积 |
|---|---|
| 3.2.12 正式版 | **6.1M** |
| 3.2.13 debug | **18M** |

`isMinifyEnabled = false`（release 一直不压缩），所以 **18M 就是真实交付体积，
比原来涨约 12M**，全部来自 Compose + Miuix 运行时。

这个体积对 LSPosed 模块来说偏大（同类模块通常 2-4M）。
**待用户决定**：接受 / 开 R8 压缩 / 只保留 Compose 不引 Miuix。

## 2026-09-29 本机构建封装 `g9.sh`

本机 PROot 环境跑 Gradle 9 的两条绕行已固化为 `./g9.sh`：

```bash
./g9.sh assembleDebug
./g9.sh testDebugUnitTest
./g9.sh :app:assembleRelease
```

要点（已写进脚本注释，勿删）：
- `GRADLE_USER_HOME=/dev/gh2` —— 必须是**真实目录**，符号链接实测也炸
- launcher JVM 的 `-Djava.io.tmpdir=/dev/gtmp2` —— 必须直接启 launcher，
  `JAVA_OPTS` / `GRADLE_OPTS` / `-Dorg.gradle.jvmargs` 全部无效
- `/dev` 是 tmpfs，**重启即失**，脚本会在缓存缺失时自动从 `/root/.gradle` 重建
- JDK 必须 21（默认的 25 会让 Gradle 报 `25.0.4.1`）

本机验证结果：
- `testDebugUnitTest` 131 项全过
- `assembleDebug` BUILD SUCCESSFUL（18M，未压缩）
- `assembleRelease` 失败于 **keystore 口令**（本机没有，CI 才注入）——
  这是环境限制，不是代码问题

## 2026-09-29 目录整理核查（结论：无需整理）

用户要求「谨慎整理一下目录」。核查后**未做任何改动**，理由如下：

### 本地大文件全部已被 .gitignore 覆盖

| 路径 | 体积 | 在版本库？ |
|---|---|---|
| `app/build/` | 251M | 否（`build/` 已 ignore） |
| `backup/` | 17M | 否（0 个文件被跟踪） |
| `.bak_*`（52 个） | 7.1M | 否（`.gitignore:51 *.bak*`） |
| `.git/` | 24M | — |

`.bak_*` 与 `backup/` 是**本项目的历史资产**（历次改动前的快照），
刻意不进版本库。任何「清理」都会丢失回滚依据 —— **不动**。

### 版本库实际很干净

- 跟踪文件：**183 个**
- 远端 GitHub 计量：**4.8 MB**
- 分布：`app/` 86、`src/` 58、根 17、`screenshots/` 7、`.github/` 7、
  `tools/` 3、`docs/` 3、`gradle/` 2
- 未跟踪文件：**0**（工作区干净）

### 为什么不「为了整理而整理」

版本库里没有冗余、没有误提交的大文件、没有临时脚本残留。
强行调整只会产生无意义的 diff 噪音，反而掩盖真正的代码改动。

## 2026-09-29 CI 首轮验证通过（AGP 9 全链路真机跑通）

推送后 CI run 36559502102 **全部步骤 ✓**：

```
✓ 安装 Android SDK 组件（platform-37 / build-tools 36）   ← 关键：Android 17
  小版本编号的 sdkmanager 路径可用
✓ 还原签名密钥
✓ Gradle 构建（有密钥=Release）
✓ 上传 APK 产物
```

**真实产物体积：2.29 MB**（本机临时密钥测得 1.9M，CI 产物 2.29M 为准）。
即：AGP 9.4.1 + Gradle 9.6.0 + Kotlin 2.4.10 + compileSdk 37 + R8 + Miuix
这一整条链在 CI 上跑通了。

### CI 首轮暴露的两个问题（已修）

1. **CI 从不跑单测** —— workflow 只有 `assemble*`，131 项测试从未在 CI 执行。
   已补 `testDebugUnitTest` 步骤（不用 `check`，因为本项目
   `abortOnError=false`，跑 lint 不拦错误、纯浪费时间）。

2. **postEdf 是从未执行的死代码** —— 已连同 `tools/post_edf.py` 删除。
   查证：注册于 `768d9db`，但同一次提交里 `tasks.configureEach` 只留空壳，
   无 dependsOn/finalizedBy；它唯一多做的 ascope.list 注入，
   而该文件 `git log --all` 查无记录（从未存在）。
   `scope.list` 早由 packaging.resources.merges 自动合入
   （下载 ci-209 产物核实：三个 xposed 文件俱全）。

   ⛔ 更正此前的错误判断：我一度以为「发布产物的 scope 声明可能一直缺失」，
   那是错的 —— scope.list 一直在包里，只是走 packaging 而非 postEdf。

   回归验证：删除后 APK 里三个 xposed 文件仍完整。

## 2026-09-29 修 aapt2 静默产物损坏（严重，已装机验证）

**症状**：`assembleRelease` 每步 BUILD SUCCESSFUL，产物 1.9M、签名正常、
dex 正常，但 APK **缺 AndroidManifest.xml 与整个 res/**，装不上。

**根因**：`gradle.properties` 的 `android.aapt2FromMavenOverride` 指向
aapt2-861（**AGP 8.6.1 专用**），AGP 升到 9.4.1 后失配，资源打包静默失败。
CI 没事是因为 workflow 有 `sed -i '/aapt2FromMavenOverride/d'` 删掉这行
改用原生 aapt2（CI 是 x86_64）。本机 aarch64 **不能照抄**，必须用 qemu 包装。

**我犯的错**：第一反应是照抄 CI 删掉 override，结果 aarch64 上直接报
`AAPT2 Daemon startup failed`。正确做法是下载 AGP 9.4.1 匹配的 aapt2
（9.4.1-15978811 / 2.20-15978811）做新包装 `/workspace/tools/aapt2-941/`。

**教训：构建成功 ≠ 产物可用**。若当时只看「1.9M，R8 真棒」就放过这个 bug。
以后验产物至少四项：manifest 存在 + res/ 非空 + META-INF/xposed 完整 + 签名证书正确。

**装机验证**：4.0M，四项体检全过，`pm install -r -d` Success。

## 双主题架构（用户确认：两套并存，Material 一行不改）

设计文档：`docs/superpowers/specs/2026-09-29-dual-theme-design.md`

分派点唯一：`MainActivity.showTab()` 的 `when` 里按 `MiuixSurface.enabled()` 分派。
Miuix 轨全部代码集中在 `src/.../ui/miuix/`，删目录即回退。

### 步骤进度

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 基础控件库 `MiuixComponents.kt` | ✅ 已完成并装机 |
| 2 | `MiuixHostFragment` 骨架 + 分派点接入 | ✅ 已完成并装机 |
| 3 | 概览页（当前为占位） | ⏳ 下一步 |
| 4 | 关于页（最简单） | ⏳ |
| 5 | 设置页（最复杂） | ⏳ |
| 6 | 弹窗（跨进程，独立路径，风险最高） | ⏳ |

**第 1 步要点**：控件规格刻意对齐 M3Ui（圆角 20 / 行高 56 / 内边距 16 /
卡片间距 12），两套主题要像同一款应用的两个皮肤。
`MiuixSwitchRow` 复用了 3.2.12 的 MaterialSwitch 教训 ——
置灰必须同时作用在视觉（alpha）与交互（回调传 null），否则是同一个 bug。


## 2026-09-29 CI 第二轮验证通过

run 36563937804 **全绿**，且**新增的「单元测试」步骤 ✓ 真正执行了**
（此前 CI 从不跑单测，131 项测试只在���机跑）。

## 第 2 步完成：MiuixHostFragment 骨架 + 分派点接入

**分派点唯一**（`MainActivity.showTab()`）：

```kotlin
val miuix = MiuixSurface.enabled(this)
val f: Fragment = when (id) {
    2 -> if (miuix) MiuixHostFragment(Screen.SETTINGS) else SettingsFragment()
    3 -> if (miuix) MiuixHostFragment(Screen.ABOUT)     else AboutFragment()
    else -> if (miuix) MiuixHostFragment(Screen.OVERVIEW) else OverviewFragment()
}
```

Material 轨三个 Fragment **一行未改**。删掉 `ui/miuix/` 目录 + 这个 if
即可回到纯 Material。

**体积印证 R8 真的在工作**：第 1 步 4.0M → 第 2 步 **4.6M**。
涨的 0.6M 是 Miuix 渲染路径被真实引用后 R8 保留的部分 ——
第 1 步时 Miuix/Compose 会被整体裁掉（dex 实测 0 处匹配）。

**MiuixHostFragment 不需要 proguard keep**：由 MainActivity 直接 new，
编译期绑定，非字符串加载。

**已装机**：`pm install -r -d` Success，实机启动正常无崩溃。

### 待用户实测

我读不到 `/data/user/0/...`（应用数据隔离），无法代为切换主题。
请手动验证：设置 → 外观 → 界面风格 → HyperOS (Miuix) → 看「关于」页
是否变为 Miuix 观感；再切回 Material You 确认原界面完好。

## 2026-09-29 Miuix 轨用户实测：3 个 bug 全部修复

用户反馈（切到 Miuix 轨后）：
1. ❌ 切回 Material 似乎没有入口 → 关于页那行**没传 onClick**，是静态行
2. ❌ 开关能点但状态不变 → `onCheckedChange = { }` **空回调**
3. ❌ 从 Miuix 切回 Material **会闪退**

### 崩溃根因（真实堆栈）

```
FATAL EXCEPTION: main
Caused by: Unable to instantiate fragment sp0: could not find Fragment constructor
Caused by: java.lang.NoSuchMethodException: sp0.<init> []
    at com.fxxkmoondrop.secret.MainActivity.onCreate
    at android.app.ActivityThread.handleRelaunchActivityLocally
```

`MiuixHostFragment` 写成了**带参构造**。首次 `new` 没问题，但 `recreate()` 时：

1. `savedInstanceState != null` → `if (savedInstanceState == null) showTab(curTab)` **不执行**
2. `FragmentManager` 用保存的 state **自己重建** Fragment
3. 反射路径 `clazz.getConstructor().newInstance()` **只认无参构造**
4. 找不到 → FATAL

⛔ 教训：给 Xposed 模块写 Fragment，**永远用无参构造 + arguments**。
带参构造只在「永远不 recreate」时才安全，而 Activity.recreate() 是常态。

### 已加测试钉死这个约束

`MiuixHostFragmentConstructorTest`（4 项），其中「只允许一个无参构造」
专门防止有人为「传参方便」改回去。

**已验证测试有效**：临时改成 `MiuixHostFragment(private val dummy: Int = 0)`
后该测试立刻 FAILED，恢复后全绿。

### 排版已对齐 OppoPods

`MiuixPage` 升级为「大标题 + 可垂直滚动 + 卡片分组」，
参考 OppoPods 的 `MainTabs.kt` / `EarphonesTabPage.kt`。
标题暂不随滚动收缩（需嵌套滚动协作，留到第 3 步与概览页一起做）。

**当前测试数：135 项**（131 + Miuix 4）

## 2026-09-29 Miuix 轨第二轮用户实测：3 个问题修复

### 1. 切换后不立即生效（两侧共有的 bug）

根因：`MainActivity.onCreate` 里 `if (savedInstanceState == null) showTab(curTab)`。
`recreate()` 后 `savedInstanceState != null` → **showTab 不执行** →
FragmentManager 恢复**旧主题的 Fragment** → SP 已切但界面不变。

⛔ Material 设置页那行 `scheduleRebuild(0L)` 内部也是 `act.recreate()`，
**同样有这个 bug**。两侧都改用新增的 `MainActivity.applyStyleSwitch()`
（直接 `showTab(curTab)`，按当前 SP 重新分派，不重建 Activity）。

### 2. 切换入口移到设置页

- Miuix 关于页的切换行**移除**，改为说明「在设置 → 外观 → 界面风格中切换」
- Miuix **设置页**新增外观分组：状态行 + 切回 Material + 切到 Miuix
- 两侧共用同一 SP 键 `ui_style`

### 3. 排版改回对齐 Material（我上一轮做错了方向）

⛔ 上一轮我照抄了 **OppoPods** 的大标题风格 —— 那是 OppoPods 的排版，
不是我们的。用户要求「跟 material 主题时排版一样」。

已改为严格复刻 `M3Ui.collapsingHeader`：

```
大标题展开高   152dp  ← 取自 M3Ui.HEADER_EXPANDED_DP
大标题收缩高    64dp  ← 取自 M3Ui.HEADER_COLLAPSED_DP
结构           Box 叠放（Material 是 FrameLayout 叠放）
内容区         verticalScroll + paddingTop=152dp
收缩           graphicsLayer: translationY + scale 0.55
```

⚠️ 刻意**不用** material3 的 TopAppBar（Miuix 轨里塞 Material 组件会串味），
也**不用** Miuix 的 TopAppBar（它 56dp 小标题栏规格与 Material 152dp 不同，
直接用会导致切换时布局跳动）。

**当前测试数：135 项**

## 2026-09-29 下拉菜单三修（用户截图指出）

### 1. 菜单位置：坐标系混用

`PopupPositionProvider` 要**窗口坐标**，而 `pointerInput` 的 `down.position`
是**节点局部坐标**（0~280 量级）—— 直接用导致菜单 y 差约 1100px，
实测一律弹在屏幕顶部。

必须用 `positionInWindow`（**不是** `positionInRoot`），并把 Box 原点加上。

### 2. 配色：改用 Popup 自绘

material3 的 `DropdownMenu` 容器色在深色主题下仍是浅色（用户截图的问题）。
改用 `Popup` 自绘，容器色复用 `elevatedSurface()`，文字用 `onSurface`。

放弃 material3 DropdownMenu 的三个理由（都实测踩过）：
- `offset` 是 DpOffset，换算手指像素要做两次 density 转换
- 容器色/字色/图标色难完全接管

### 3. 图标：改用素材库资源

用 `R.drawable.ic_check`（项目自带，Material 版下拉同款）。
⛔ 不用 material-icons —— 加依赖仍崩（`NoClassDefFoundError`），
因为 R8 把整个 material-icons 裁掉（usage.txt 295 条删除记录）。

**⛔ 铁律：加了依赖 ≠ 类会进 dex。R8 按可达性裁剪，间接引用的类会被删。**

### 撞过的 Compose ui 1.11.2 API 坑

- `Popup` 参数名是 `popupPositionProvider`（不是 `positionProvider`）
- 参数名写错会退化到别的重载，报 `Conflicting overloads`
- `Image` 着色用 `colorFilter = ColorFilter.tint(...)`（不是 `tint`）
- `clip()` 收 `Shape` 不是 `Dp`

### 验证方式升级（用户建议）

改用 **`uiautomator dump` 拿无障碍节点坐标** + `screencap` 看渲染。
之前 `input tap 602 2530` 是瞎猜的；现在 dump 出
「界面风格」bounds `[96,1103][288,1173]` 后精确定点。

## 2026-09-29 下拉菜单改用 Miuix 原生控件（三轮返工）

用户指出四点：点哪都关不掉、宽度太长（口误）、**应该用 Miuix 原生控件
不要自创**、选项背景消失。

⛔ 教训：Miuix 源码 jar 里有 `OverlayListPopup` / `ListPopupContent` /
`BasicComponent` 等原生组件，**动手写之前应该先查**。

### 三轮返工

1. 自创 Popup → 关不掉、宽度失控、底色丢失
2. 换 `OverlayListPopup` → 菜单不弹（`LocalPopupStates` 只由 Miuix `Scaffold`
   提供）；补 Scaffold 后崩（`No NavigationEventDispatcher`，需 androidx.navigation，
   `ComponentActivity` 不实现）
3. **最终**：androidx `Popup` 管窗口与关闭行为 + Miuix `ListPopupContent`
   管视觉（surfaceContainer 底色 / 16dp 圆角 / squircle 裁切）+
   Miuix `BasicComponent` 管列表项

### Miuix 自身的边界 bug（0.9.2）

```
IllegalArgumentException: maxWidth must be >= than minWidth
```

`ListPopupColumn` 的 MeasurePolicy：
`upper = maxOf(288.dp, parentMin).coerceAtMost(parentMax)`，
当父约束 maxWidth < 288dp 时 upper < lower，`coerceIn` 抛异常。
无法从调用侧规避 → 弃用 `ListPopupColumn`，保留视觉层，
宽度改由 `Column(IntrinsicSize.Min).widthIn(max=240.dp)` 自适应。

### 配色不再自创

用户原话：「配色的话不可以用 miuix 的吗？两个主题组件不应该是完全分开的吗？」

之前自写 `elevatedSurface()` 是错的 —— 绕过 Miuix 整套 HCT 色轮。
正解：把既有种子色（`ThemeUtil.seedColor`，与 Material 轨共用同一偏好）
作为 `ThemeController` 的 `keyColor`，由 Miuix 自己算 surface 三档
与 `windowDimming`。

### Miuix API 坑表（读源码确认，勿凭印象）

| 坑 | 正确写法 |
|---|---|
| `BasicComponent.titleColor` | `BasicComponentColors(color, disabledColor)` |
| 右侧插槽 | `endActions`（不是 `trailingIcon`） |
| `onClick` | `BasicComponent` 自带 |
| `PopupLayoutPosition` | data class，构造用 `showBelow=/showAbove=/isRightAligned=`，无 `Below` 常量 |
| `PopupPositionProvider` | androidx 与 Miuix 各一套，签名不同 |
| `Image` 着色 | `colorFilter = ColorFilter.tint(...)` |
| `Popup` 参数名 | `popupPositionProvider` |

**当前测试数：135 项**

## 2026-09-29 下拉菜单第五轮：改用 MIUI 官方组件（零崩溃）

用户要求「按 MIUI 官方的用法和规范，连排版和大小都要按官方的来」。

### 官方用法（查 OppoPods，miuix 0.9.2 同版本）

`miuix-preference` 的 `OverlayDropdownPreference` / `WindowDropdownMenu`：
`title / summary / items / selectedIndex / onSelectedIndexChange`。

新增依赖 `top.yukonga.miuix.kmp:miuix-preference-android:0.9.2`。

### ⛔ 真正的拦路虎：LocalWindowInfo

所有 Miuix 弹层都读 Compose 的 `LocalWindowInfo.current`，
它要求宿主 Activity 实现 `NavigationEventDispatcherOwner`。
`ComponentActivity` **只在 androidx.navigation 在 classpath 里时**才实现。

解法（最小侵入，不引 NavHost/NavController）：

```kotlin
class MainActivity : FragmentActivity(), NavigationEventDispatcherOwner {
    override val navigationEventDispatcher: NavigationEventDispatcher by lazy {
        NavigationEventDispatcher()
    }
}
```

⚠️ 必须写 `override val`，不是 `override fun getNavigationEventDispatcher()`。
依赖坐标：`navigationevent-android:1.1.1`（**没有** `-runtime` 那个坐标，
`Could not find androidx.navigationevent:navigationevent-runtime:1.1.1`）。

### 五轮返工总表

| 轮 | 做法 | 结果 |
|---|---|---|
| 1 | 自创 Popup | 关不掉、宽度失控、无动画 |
| 2 | OverlayListPopup | 菜单不弹（缺 LocalPopupStates） |
| 3 | 补 Miuix Scaffold | 崩（缺 NavigationEventDispatcherOwner） |
| 4 | Popup+ListPopupContent+IntrinsicSize.Min | 崩（maxWidth/maxHeight 冲突） |
| 5 | **WindowDropdownMenu + 实现 Owner 接口** | ✅ 零崩溃 |

第 4 轮那个 `IllegalArgumentException` 其实同时报了三条
（maxWidth / maxHeight / minWidth and minHeight must be >= 0），
共同根因都是 `Column(IntrinsicSize.Min)` 与 `BasicComponent` 高度不定冲突。

体积 4.7M（加依赖前后无变化，R8 裁掉未用部分）。**测试数：135 项。**


## 2026-09-29 第六轮：Miuix 弹层闪退的真根因（我上一轮判断错了）

上一轮我以为「Activity implements NavigationEventDispatcherOwner」就修好了，
**实际仍然崩**。这次抓到完整链条：

```
Miuix 弹层 → LocalWindowInfo → LocalNavigationEventDispatcherOwner.current
           → 由 rememberNavigationEventDispatcherOwner 填充
           → 后者沿 **View 树**（ViewTreeOwner）逐级往上找
```

⛔ **View 树找的是「View 树节点上的 Owner」，不是「Activity 是 Owner」。**
我们的 ComposeView 挂在 Fragment 容器里，树上没有该 Owner → current 为 null → 崩。

### 正解：Compose 官方显式注入，绕开 View 树

```kotlin
CompositionLocalProvider(
    LocalNavigationEventDispatcherOwner provides object : NavigationEventDispatcherOwner {
        private val dispatcher = NavigationEventDispatcher()
        override val navigationEventDispatcher get() = dispatcher
    },
) { MiuixTheme(controller = ...) { content() } }
```

依赖：`navigationevent-android:1.1.1` + `navigationevent-compose-android:1.1.1`
（`provides()` 在 compose 变体里）。**没有** `navigationevent-runtime` 这个坐标。

### 中间踩的两个坑（都写进档案了）

1. **R8 会把 `implements` 关系优化掉**：dex 实测不加 keep 规则时
   `MainActivity.interfaces` 是**空的**。已加 `-keep interface` +
   `-keep class * implements ...`，加规则后 dex 实测接口在 ✓
2. **我一度在装旧 APK**：改完 proguard 后 `assembleRelease` 报成功但
   APK 时间戳没变（Gradle 判定 up-to-date），实际装的是 23:59 的旧包，
   于是出现「改了还是崩」的假象。
   ⛔ 改完必须重新 assemble + 重新 install，`BUILD SUCCESSFUL` 不算数。

### 顺带：下拉行不显示当前值

`WindowDropdownMenu` 的 `endActions` 被箭头与弹层占满，`showValue` 只有
`OverlayDropdownPreference` 有 —— **HyperOS 官方表达「当前选中」的方式是
`summary`**，已照此办理。

**测试数：135 项 | 体积：4.7M | 已装机零崩溃**

## 2026-09-29 第七轮：下拉行直角高亮 + 当前值显示

### 根因（读 miuix-ui-android-0.9.2-sources 确认）

`utils/MiuixIndication.kt`：

```kotlin
override fun ContentDrawScope.draw() {
    drawContent()
    if (alpha > 0f) drawRect(color = color, alpha = alpha, size = size)  // 硬编码直角
}
```

`BasicComponent.clickable` 没传 `indication` → 回落 `LocalIndication`（就是它）
→ 画**通栏直角矩形**，不跟随 squircle。
且 `WindowDropdownMenu` 的 `isHoldDown` 只在 `onDismissFinished` 才复位。

**Miuix 0.9.2 没有 squircle 版 Indication 实现。**

### 解法：行层裁剪，不魔改官方组件

```kotlin
MiuixCard(modifier = Modifier.clip(RoundedCornerShape(MiuixSpec.CARD_RADIUS))) {
    WindowDropdownMenu(...)
}
```

裁剪把通栏直角高亮切成超椭圆，与 Card 边缘对齐。纯布局层处理。

### 试过并放弃

实现自定义 `IndicationNodeFactory` 覆盖 `LocalIndication`
（行为照抄，只把 drawRect 换 drawRoundRect）——
Compose 1.11 的 `IndicationNodeFactory` / `DelegatableNode` 契约
（`getNode()` + `Modifier.Node` 集成）比预想复杂，写出来引用了不存在的 API。
**不魔改官方组件、也不硬啃底层 API**是更稳的选择，已撤销。

### 当前值显示

`WindowDropdownMenu` 的 `endActions` 被箭头 + 弹层占满，`showValue`
只有 `OverlayDropdownPreference` 有。**HyperOS 官方表达「当前选中」的方式
就是 `summary`**，已照办。另显式传 `dropdownColors`：
选中项给 `primary` 文字色 + 指示器，**不给独立底色**
（`selectedContainerColor` 默认等于 `containerColor`，这是 HyperOS 观感）。

**测试数：135 项 | 体积：4.7M | 已装机零崩溃**

## 2026-09-29 第八轮：直角高亮彻底解决（官方 pressFeedbackType）

用户反馈「还是直角」→ 上一轮 `Modifier.clip` 无效，**根因找错层级**。

### 为什么 clip 裁不到

`BasicComponent` 内部 modifier 链：

```
modifier → heightIn → fillMaxWidth → then(clickableModifier) → padding
```

画高亮的 `clickableModifier` 排在我们传入的 `modifier` **之后**，
外层 clip 裁不到（实测无效，已撤销）。

### 根因

`utils/MiuixIndication.kt` 写死
`drawRect(color, alpha, size = size)`（硬编码直角通栏），
`BasicComponent.clickable` 没传 indication → 回落 `LocalIndication`。
**Miuix 0.9.2 无 squircle 版 Indication。**

### 试过并放弃

1. 自定义 `IndicationNodeFactory` —— Compose 1.11 的 `DelegatableNode`
   契约（`getNode()` + `Modifier.Node` 集成 + 挂载点）复杂易碎，
   写出来引用不存在的 API。
2. `LocalIndication provides null` —— material3 的 `LocalIndication`
   是 **internal API**，外部不可用。

### 正解

```kotlin
MiuixCard(
    showIndication = false,
    pressFeedbackType = PressFeedbackType.Sink,
) { WindowDropdownMenu(...) }
```

`pressFeedbackType` 走 Card 自己的 `Modifier.pressable`（squircle 感知），
表现为 HyperOS 的「按下轻微下沉」—— 靠形变而非直角色块表达按下。
已给 `MiuixCard` 包装器透传这两个参数。

顺带解决展开态高亮残留（原先 `isHoldDown` 只在 `onDismissFinished` 复位）。

### 实机截图确认 ✅

- 下拉弹窗为**圆角超椭圆**，与卡片边缘对齐
- 选中项是**主色文字 + ✓ 指示器**，无色块
  （HyperOS 官方观感：`selectedContainerColor` 默认等于 `containerColor`）
- 「界面风格」行显示当前值 `HyperOS (Miuix)`
- 零崩溃 | 135 项测试 | 4.7M
