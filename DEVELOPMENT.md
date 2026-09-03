# FxxkMoondrop 开发文档

> 版本：alpha2.41.6 ｜ 更新日期：2026-09-03

## 构建环境

| 项 | 要求 |
|---|---|
| JDK | 17 |
| Gradle | 8.9（wrapper 固定) |
| AGP | 8.5.2 |
| Kotlin | 1.9.22 |
| Android SDK | compileSdk 34, build-tools 34.0.0 |
| minSdk / targetSdk | 26 / 36 |

## 构建命令

```bash
# Release 构建
./gradlew :app:assembleRelease -PfxxkKeypass=<签名密码>

# 产物路径
app/build/outputs/apk/release/app-release.apk

# Debug 构建
./gradlew :app:assembleDebug
```

### 签名配置

`app/build.gradle.kts` 中 `signingConfigs.release` 从以下来源读取密码：
- 环境变量 `FXXK_KEYPASS`
- Gradle property `-PfxxkKeypass=`
- 密钥库文件 `app2.keystore` 需放在项目根目录

### EDF 作用域注入

构建后可执行 `postEdf` 任务注入 LSPosed 作用域文件并重签：

```bash
./gradlew postEdf -PfxxkKeypass=<签名密码>
```

该任务调用 `tools/post_edf.py`，将 `src/main/META-INF/xposed/scope.list` 和 `ascope.list` 注入 APK 并用 apksigner 重签。

## 目录结构

```
FxxkMoondrop-repo/
├── app/                          # Gradle 应用模块
│   ├── build.gradle.kts          # AGP 配置 + 签名 + EDF 后处理
│   └── src/main/
│       ├── AndroidManifest.xml   # 四大组件声明
│       ├── res/                  # 资源（布局/图标/主题）
│       └── resources/META-INF/xposed/
│           ├── java_init.list    # 入口类: com.fxxkmoondrop.secret.XposedEntry
│           ├── module.prop       # LSPosed 模块元信息
│           └── scope.list        # 作用域: gms + settings
├── src/                          # 全部 Kotlin 源码（单一权威源）
│   └── com/fxxkmoondrop/secret/
│       ├── *.kt                  # App 进程代码（28 个文件）
│       └── hook/
│           └── FastPairHookEntry.kt  # GMS 进程 Hook 代码
├── gradle/wrapper/               # Gradle 8.9 wrapper
├── tools/
│   └── post_edf.py               # EDF 作用域注入脚本
├── build.gradle.kts              # 根项目配置
├── settings.gradle.kts           # 模块声明
├── README.md
├── CHANGELOG.md
├── ADAPTATION.md                 # 协议适配文档
├── ARCHITECTURE.md               # 架构文档
└── DEVELOPMENT.md                # 本文档
```

> 源码目录 `src/` 在 `app/build.gradle.kts` 中通过 `sourceSets.java.srcDirs("../src")` 引用，不复制到 `app/src/main/java/`。

## 版本号规范

| 项 | 格式 | 当前值 |
|---|---|---|
| versionName | `alpha{里程碑}.{迭代}` | `alpha2.38.7` |
| versionCode | 单调递增整数 | `267` |

发版时同步更新三处：
1. `app/build.gradle.kts` — `versionCode` + `versionName`
2. `CHANGELOG.md` — 顶部追加新条目
3. `README.md` — 顶部版本号 + 版本历史列表

提交信息格式：`alpha{版本}: {简要描述}`

## LSPosed 模块元信息

### 入口声明（`META-INF/xposed/java_init.list`）

```
com.fxxkmoondrop.secret.XposedEntry
```

### 模块属性（`META-INF/xposed/module.prop`）

```
minApiVersion=101
targetApiVersion=102
staticScope=true
```

### 作用域（`META-INF/xposed/scope.list`）

```
com.google.android.gms        # Fast Pair 弹窗 + BLE 扫描
com.android.settings          # 设置页耳机入口（还未实现）
```

### 运行时作用域（代码中动态 Hook）

| 包名 | 用途 |
|---|---|
| `com.google.android.gms` | Fast Pair 弹窗注入 + BLE 扫描借道 |
| `com.android.settings` | 设置页注入耳机入口 |
| `com.android.bluetooth` | A2DP 连接状态监听（连接拉起 / 断开停止） |
| `com.moondroplab.moondrop.moondrop_app` | 逆向参考官方 ANC 路径 |

## 依赖清单

| 依赖 | 版本 | 用途 |
|---|---|---|
| `io.github.libxposed:api` | 102.0.0 | LSPosed Xposed API（compileOnly，运行时由 LSPosed 提供） |
| `com.google.android.material:material` | 1.12.0 | Material 3 组件 |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompat 兼容层 |
| `androidx.fragment:fragment` | 1.7.1 | Fragment 导航 |
| `androidx.core:core` | 1.13.1 | AndroidX 核心 |
| `androidx.recyclerview:recyclerview` | 1.3.2 | 列表组件 |
| `androidx.lifecycle:*` | 2.7.0 | 生命周期组件 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 1.9.22 | Kotlin 标准库 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | 协程 |
| `junit:junit` | 4.13.2 | 单元测试（仅 JVM） |

## Android 四大组件

### Activity

| 类 | exported | 说明 |
|---|---|---|
| `MainActivity` | true | 主界面（三页 Fragment + 底部导航） |
| `SettingsActivity` | false | 设置页 |
| `PermissionActivity` | false | 权限检测页 |
| `AboutActivity` | false | 关于页 |

### Service

| 类 | 说明 |
|---|---|
| `HeadsetDetectService` | 前台服务；蓝牙连接监听 + GAIA 直连 + ANC 轮询 |

### ContentProvider

| 类 | authority | 说明 |
|---|---|---|
| `PrefsProvider` | `com.fxxkmoondrop.secret.prefs` | 跨进程 SharedPreferences 读写 |

### Receiver

| 类 | exported | 说明 |
|---|---|---|
| `BootReceiver` | true | 开机自启 |
| `AliveReceiver` | false | 保活广播接收 |

## 开发注意事项

### 弹窗布局相关修改

弹窗所有坐标参数集中在 `FastPairHookEntry.PopupProfile` 中。新增屏幕档位时：

1. 在 `PopupProfile` companion object 中添加 `PROFILE_XX` 常量
2. 在 `resolveScreenProfile()` 中添加分辨率匹配条件
3. 真机验证后标注坐标为实测值

### 电量显示

alpha2.38.7 起，电量文字优先写入 GMS 原生 `subhead`（耳机名副标题），位置天然在名称下方、图标上方。仅当 `subhead` 缺失时才兜底自绘 overlay，且回退位置从 `PopupProfile` 读取。

### ANC 映射

`AncProfileLib` 管理 GET/SET 双向映射。新型号实测后：

1. 在 `AncProfileLib.PROFILES` 中按设备名关键字添加条目
2. SET 方向（UI → 设备码）和 GET 方向（设备码 → UI）分开填写
3. 用户自定义映射优先级最高，会覆盖档案库
4. 布丁 PUDDING 为 5 档 ANC（增加「自适应降噪」），SET 映射 [0, 4, 2, 3, 1]，需 UI 侧适配第 5 档按钮

### 蓝牙设备详情页控制面板注入

alpha2.39 起，在 Settings 进程注入 `com.android.settings` 蓝牙设备详情页（`BluetoothDeviceDetailsFragment`）。改动集中在 `XposedEntry.hookDeviceDetailsPanel`，注意点：

- **纯 UI 注入**：`ControlPanel` / `DeviceDetailsPanel` / `CtrlBus` 只负责渲染与回调，不打 BLE/Gaia 单例，也不动主界面链路。
- **注入层次（3 个 Hook）**：
  1. `Preference.onBindViewHolder`：命中 `DeviceDetailsPanel.KEY` 时把条目 itemView 替换为控制面板；回调 `sendDeviceCommand()` + `fetchDcState()` 刷新。
  2. `DashboardFragment.onCreatePreferences`：仅当 `AncProfileLib.isMoondrop(deviceName)` 为真才追加该 Preference（标题/摘要按 `langZh` 中英文）。
  3. `BluetoothDetailsConfigurableFragment.updatePreferenceOrder`：把 `DeviceDetailsPanel.KEY` 注入 `displayOrder` 白名单，避免面板被移进 `invisible_profile_category` 隐藏。
- **跨进程自动刷新（推模式）**：面板挂载时注册 `ContentObserver` 监听 `content://com.fxxkmoondrop.secret.prefs/dc_cmd`，模块端状态变化 `notifyChange` → 重新拉取刷新；卸载（`onViewDetachedFromWindow`）时注销 observer。
- **未连接禁态**：未连接时功能开关三重禁用（`isEnabled` + `isClickable` + `isFocusable`），避免无效点击。

### 跨进程广播

新增跨进程通信时：
- Action 命名前缀 `com.fxxkmoondrop.secret.`
- 在 `FastPairHookEntry` companion object 中声明常量
- App 侧在 `AncBridge` 或 `PopupGate` 中收发
- 注意 GMS 进程中 `registerReceiver` 需要使用 `Context.RECEIVER_EXPORTED`

### LE 地址发现

不要硬编码任何 MAC 地址。地址发现走自愈闭环：
- `GaiaBleClient.requestRemoteScan()` → 广播 `ACTION_REQ_LE_SCAN`
- GMS 进程扫描 → 广播 `ACTION_LE_ADDR_FOUND`
- App 接收后持久化到文件 + SharedPreferences
- 地址变化时自动重新发现

### CapabilityProbe 探测

- 断连和每次新 GATT 会话均需 `reset()` 探测状态
- 探测超时 3500ms，超时后自动重发
- `ancPath` 锁定后不再重复探测，直到断连

## 调试技巧

### 日志 Tag

| Tag | 来源进程 | 用途 |
|---|---|---|
| `MoondropHeadset` | App | 蓝牙连接 + GAIA 通信 |
| `FastPairHook` | GMS | 弹窗 Hook + 广播 |
| `CapabilityProbe` | App | ANC 路径探测 |
| `AncBridge` | App | ANC 模式桥接 |
| `PopupGate` | App | 弹窗触发 |
| `XposedEntry` | GMS/Settings/BT | 模块入口路由 |

### 日志抓取

App 内置日志收集（设置页 → 收集日志），打包五类日志为 ZIP：
1. 系统信息
2. 应用设置
3. 蓝牙状态
4. 运行环境
5. logcat

### 单元测试

```bash
./gradlew test
```

目前仅有 GaiaCommands 帧构造的 JVM 单元测试。测试不依赖 Android 框架。

## 发布检查清单

发版前确认：
- [ ] `build.gradle.kts` versionCode + versionName 已更新
- [ ] `CHANGELOG.md` 顶部已追加新条目
- [ ] `README.md` 版本号 + 版本历史已同步
- [ ] `assembleRelease` 构建通过
- [ ] 真机验证：弹窗显示 + ANC 控制 + 电量读取
- [ ] `git commit` + `git push origin main`
