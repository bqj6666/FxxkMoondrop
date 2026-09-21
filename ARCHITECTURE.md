# FxxkMoondrop 架构文档

> 版本：3.2.7（versionCode 327） ｜ 更新日期：2026-09-18

## 系统总览

FxxkMoondrop 是一个 **LSPosed/Xposed 模块 + 独立应用** 的双形态项目，运行在两个进程中：

```
┌─────────────────────────────────────────────────────────┐
│                    App 进程                              │
│              com.fxxkmoondrop.secret                     │
│                                                          │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │  M3 UI   │  │ HeadsetDetect│  │  GaiaBleClient     │  │
│  │ (3页Fragment)│ │  Service    │  │  (BLE GATT 直连)   │  │
│  └────┬─────┘  └──────┬───────┘  └────────┬──────────┘  │
│       │               │                   │              │
│  ┌────┴─────┐  ┌──────┴───────┐  ┌───────┴──────────┐   │
│  │AncBridge │  │ PopupGate    │  │ CapabilityProbe   │   │
│  │(模式状态) │  │ (弹窗触发)   │  │ (ANC 路径探测)    │   │
│  └────┬─────┘  └──────┬───────┘  └───────────────────┘   │
│       │               │                   │              │
│  ┌────┴───────────────┴───────────────────┘              │
│  │   PrefsProvider (跨进程配置) / BatteryStore /          │
│  │   AncProfileLib (型号档案) / GaiaCommands              │
│  └───────────────────────────────────────────────────────│
│  广播收发 ──────────────────────────────────────────────  │
└──────────────────────┬──────────────────────────────────┘
                       │  Broadcast (Intent)
┌──────────────────────┴──────────────────────────────────┐
│                  GMS 进程                                │
│           com.google.android.gms                         │
│  ┌──────────────────────────────────────────────────┐    │
│  │          FastPairHookEntry (Hook 注入)            │    │
│  │  HalfSheet │ Icon Overlay │ ANC 按钮 │ Subhead   │    │
│  │  生命周期   │ (图标注入)   │ (模式条) │ (电量文字) │    │
│  │  Settings按钮 │ BLE扫描借道 │ PopupProfile(布局表) │    │
│  └──────────────────────────────────────────────────┘    │
│  广播收发 ──────────────────────────────────────────────  │
└──────────────────────────────────────────────────────────┘
```

## 进程模型与跨进程通信

项目运行在两个独立进程中，通过 **Broadcast（Intent）** 通信：

| Action | 方向 | 用途 |
|---|---|---|
| `FASTPAIR_TRIGGER` | App → GMS | 触发 Fast Pair 弹窗 |
| `FASTPAIR_MODE_CHANGED` | App → GMS | 用户点击 ANC 模式 |
| `FASTPAIR_MODE_STATE` | App → GMS | ANC 当前模式状态同步 |
| `FASTPAIR_MODE_REQUEST` | GMS → App | 弹窗请求当前 ANC 模式 |
| `FASTPAIR_ANC_STATUS` | App → GMS | ANC 能力可用性通知（三态） |
| `FASTPAIR_BATTERY_UPDATE` | App → GMS | 左右耳电量推送 |
| `FASTPAIR_CONNECTED` | GMS → App | 弹窗连接按钮点击 |
| `FASTPAIR_SHEET_CLOSED` | GMS → App | 弹窗关闭通知 |
| `ACTION_REQ_LE_SCAN` | App → GMS | 请求 GMS BLE 扫描发现 LE 地址 |
| `ACTION_LE_ADDR_FOUND` | GMS → App | 扫描结果回传 |
| `FASTPAIR_PING` / `PONG` | 双向 | 心跳检测模块是否存活 |
| `NOTIF_ANC` | 通知按钮 → App | 通知栏档位按钮点击（应用内广播，非跨进程） |

`PrefsProvider`（ContentProvider）提供跨进程 SharedPreferences 读写，App 和 GMS 进程共享配置。

### 无 Root 模式

控制耳机的能力（GAIA BLE 直连读电量、切降噪）走标准 `BluetoothGatt`，**不依赖 Root**。
未检测到 Root 时进入无 Root 模式，只停用 Root 依赖项，不影响上述主链路：

| Root 依赖项 | 无 Root 模式下的行为 |
|---|---|
| GMS Hook（LSPosed） | 不注入；设置页「官方集成」开关置灰 |
| 弹窗图标自定义（由 GMS 进程内 `readIconBytes()` 读取） | 置灰并说明 |
| `su` 拉起官方 App（`MoondropBooter`） | 直接跳过，不重试、不刷日志 |
| 保活（开机自启 + 看门狗 + 电池优化白名单） | **默认常开且无需 Root**；已 Root 时静默追加 `deviceidle` / `appops` 增强，失败不影响使用 |
| 启动时的 Fast Pair Hook 探测 | 跳过（原本要 ping 等满 4 秒超时），直接走内置 BLE 自扫 |

保活链在无 Root 下依然完整：`BootReceiver` 开机自启 + `AliveReceiver` AlarmManager 30s 循环；通知按钮被点击时也会顺带 `startService`（幂等），进程被回收后点一下通知即可恢复 GAIA 连接。

### 权限

清单只保留真正在用的权限：`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、`BLUETOOTH`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`。

- 不声明 `SYSTEM_ALERT_WINDOW`：本模块不创建悬浮窗；弹窗是 GMS 进程内的既有窗口，受 GMS 自身权限约束，与本应用无关。
- 不声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE`：`HeadsetDetectService` 从不调用 `startForeground()`，实为普通后台服务。

## 核心模块

### App 进程

| 模块 | 文件 | 行数 | 职责 |
|---|---|---|---|
| **GaiaBleClient** | `GaiaBleClient.kt` | 1246 | BLE GATT / RFCOMM 直连耳机单例；连接管理、GAIA V3 + GAIA V4 + 9ECA 三协议自动识别、电量读取、ANC 控制 |
| **HeadsetDetectService** | `HeadsetDetectService.kt` | 367 | 常驻后台服务（普通 Service，非前台服务）；监听蓝牙连接状态，驱动 GaiaBleClient 连接/断开，轮询 ANC。保活链：`BootReceiver` 开机自启 → `AliveReceiver` AlarmManager 30s 循环 `startService` → `MoondropBooter` 以 `su -c am start` 静默拉起 |
| **HeadsetGate** | `HeadsetGate.kt` | 242 | 蓝牙连接守卫；A2DP/HEADSET profile 代理获取已连接设备 MAC |
| **AncBridge** | `AncBridge.kt` | 126 | ANC 模式状态桥接；向 GMS 进程广播当前模式 + ANC 可用性 |
| **PopupGate** | `PopupGate.kt` | 202 | 弹窗触发控制；管理弹窗超时、去重、延迟触发 |
| **CapabilityProbe** | `CapabilityProbe.kt` | 169 | ANC 路径探测；查询 BASIC feature 位图，判定 ANC V1 / AudioCuration / ANC V2 |
| **BatteryStore** | `BatteryStore.kt` | 84 | 电量缓存；GAIA 左右耳分离 + 系统广播兜底 |
| **AncProfileLib** | `AncProfileLib.kt` | 119 | ANC 设备码型号档案；按设备名匹配 GET/SET 双向映射 |
| **GaiaCommands** | `GaiaCommands.kt` | 635 | GAIA V3 命令编解码；BASIC / ANC / BATTERY / AudioCuration 全套帧构造 |
| **GaiaPacketHandler** | `GaiaPacketHandler.kt` | 219 | GAIA V3 回包解析路由 |
| **PrefsProvider** | `PrefsProvider.kt` | 53 | 跨进程 ContentProvider；SharedPreferences("cfg") 读写 |
| **OverviewFragment** | `OverviewFragment.kt` | 1789 | 主页 Fragment；英雄卡 + 状态面板 + ANC 三按钮 + 权限检测 |
| **OnboardingActivity** | `OnboardingActivity.kt` | 833 | 首启使用引导（7 页横滑分页）：关于 / 权限申请 / 连接管理 / 系统集成 / 耳机控制 / 适配与诊断 / 欢迎使用；页内开关与设置页共用同一份偏好与副作用，末页汇总必要权限、可选权限与模块状态 |
| **M3Ui** | `M3Ui.kt` | 412 | Material 3 UI 组件工厂 |
| **DeviceNotif** | `DeviceNotif.kt` | 342 | 设备常驻通知（电量 + 降噪控制合并为一条）；自定义 RemoteViews 大图标档位按钮、M3 动态取色、内容指纹去重（避免重发把用户展开的通知打回折叠态）；折叠/展开两份视图以适应折叠态约 48dp 的高度上限 |
| **NotifActionReceiver** | `NotifActionReceiver.kt` | 33 | 通知档位按钮落地：先幂等拉起服务，再调用 `AncBridge.setAncMode`；无 Root 模式下靠这一步恢复被回收的进程 |
| **EnvProbe** | `EnvProbe.kt` | 161 | 运行环境探测：Root（仅探文件存在，不执行 su）、FastPairHook 心跳、**无 Root 模式判定**（`isNoRootMode()`） |
| **DeviceControlBridge** | `DeviceControlBridge.kt` | 182 | 增益/指示灯/空间音频等扩展设备控制回调 |
| **DeviceDetailsPanel** / **ControlPanel** / **CtrlBus** | `DeviceDetailsPanel.kt` `ControlPanel.kt` `CtrlBus.kt` | - | 注入系统蓝牙设备详情页的降噪 + 功能控制面板（纯 UI 组件，只回调不持 BLE/Gaia 单例） |

### GMS 进程（Hook 注入）

| 模块 | 文件 | 行数 | 职责 |
|---|---|---|---|
| **FastPairHookEntry** | `hook/FastPairHookEntry.kt` | 1677 | GMS 进程全部 Hook 逻辑；弹窗生命周期、图标/电量/ANC 按钮注入、BLE 扫描借道 |
| **PopupProfile** | (内嵌于 FastPairHookEntry) | - | 屏幕布局参数表；按分辨率分档（6.1寸/6.3寸），坐标集中配置 |
| **HearableControlHook** | `hook/HearableControlHook.kt` | 1021 | Hearable Controls（GFPS 消息组 0x08）官方链路桥接：DexKit 特征定位捕获官方 ANC 子模块、放开 Fast Pair 缓存门禁、在**意图入口**与**管理器发送出口**两处接住用户点击并转 GAIA、把 App 真实状态注入官方 DataStore |
| **XposedEntry** | `XposedEntry.kt` | 403 | LSPosed 模块入口；路由到各进程 Hook |

### LSPosed 模块入口路由

```
XposedEntry (META-INF/xposed/java_init.list)
├── onPackageReady("com.google.android.gms") → FastPairHookEntry.onGmsLoaded()
│                                               + HearableControlHook 官方耳机控制面板（Hearable Controls）桥接
├── onPackageReady("com.android.settings")  → hookSettings() 注入耳机入口 + 蓝牙设备详情页降噪/功能控制面板
├── onPackageReady("com.android.bluetooth") → hookBluetooth() A2DP 状态监听
└── onPackageReady("com.moondroplab...")     → hookMoondrop() 逆向参考
```

## 非目标设备零介入（门禁总表）

本模块的**全部** Hook 与注入点都以「这是不是我们支持的耳机」为前置条件，判定不通过就原样 `proceed()`，
官方逻辑一行不受影响。这样 Google Fast Pair、系统蓝牙详情页、空间音频等原生能力对**其他耳机**
（Pixel Buds / Sony / Nothing 等真正支持 Fast Pair 的型号）保持百分之百的原生体验。

| Hook / 注入点 | 归属判据 |
|---|---|
| Fast Pair 弹窗 `Activity.onResume` / `View.performClick` | Intent 标记 `EXTRA_OUR_SHEET`（弹窗渲染在 GMS 另一进程，静态字段跨不了进程；这是唯一可靠判据） |
| 弹窗渲染 `dtes.f` / `dthi.O` / `dthi.q` | 卡片数据里的设备名（`DeviceMatcher.isMoondrop`） |
| 弹窗图标 / 电量 / 降噪按钮 / 设置键注入 | 均只在 `onResume` 归属判定通过后被调用 |
| Hearable Controls（官方 ANC 面板；**音量面板 / 提示音和振动面板**也是由此链路启用并接管，点击经意图入口转 GAIA） | `aliasesSnapshot()` 目标地址别名集 |
| Fast Pair 缓存门禁 `cache.A(String)` | 同上，只对目标地址返回 true |
| 蓝牙详情页面板注入 | Preference key 专属（该 key 只在 `isMoondrop` 的设备页被添加） |
| 详情页行可见性 / profile 列表 | `AncProfileLib.isMoondrop(deviceName)` |
| 官方空间音频 / 头部追踪两行 | `officialTakeoverAddr`（仅在本模块耳机页被赋值）；系统 `Spatializer` 的 7 个方法各自校验 `oursSpatialDevice(dev)` 地址 |
| A2DP 状态监听 / 弹窗触发 | `DeviceMatcher.isMoondrop(name)` |
| 水月雨官方 App 逆向参考 Hook | 只作用于 `com.moondroplab.moondrop.moondrop_app` 自身进程 |

注：**音量面板 / 提示音和振动面板不是独立的 Hook 点** —— 它们是 GMS 的 Hearable Controls 面板由本模块（`feat_official_panel` 开关）启用后交给官方渲染的，档位点击落在 `dvzo` 意图入口，由本模块接住转成 GAIA。因此它与其他耳机的关系同样是「我们的耳机才接管」：别名集不含目标地址时，`forwardEntryIntent` 与 `forwardOfficialSend` 都会直接返回，官方原有行为不变。

## 关键数据流

### 1. 耳机连接 → 弹窗显示

```
耳机开盖 → 系统 A2DP 连接
  → HeadsetDetectService 检测到连接
  → PopupGate.tryShowConnected()
  → 发送 FASTPAIR_TRIGGER 广播 (含设备名)
  → GMS 进程 FastPairHookEntry 接收
  → HalfSheetActivity 弹出 (Fast Pair 半屏卡片)
  → 注入图标 overlay + subhead 电量 + ANC 按钮条
```

### 2. ANC 模式控制

```
用户点击弹窗 ANC 按钮
  → GMS 发送 FASTPAIR_MODE_CHANGED (mode)
  → App 进程 AncBridge 接收
  → GaiaBleClient.setAncMode(mode)
  → GAIA SET_MODE 命令发送
  → 耳机回包 → GaiaPacketHandler 解析
  → AncBridge.sendModeState() 广播回 GMS
  → GMS 弹窗高亮对应按钮
```

### 3. LE 地址发现（自愈闭环）

```
无缓存地址 → GaiaBleClient.requestRemoteScan()
  → 发送 ACTION_REQ_LE_SCAN 广播
  → GMS 进程接收 → 调用 BluetoothLeScanner 扫描
  → 按名称匹配 "moondrop"
  → 发送 ACTION_LE_ADDR_FOUND (addr)
  → App 进程接收 → 持久化 → 发起 GATT 连接
  → 连接成功 → 写回缓存 → 下次秒连
```

### 4. 蓝牙设备详情页控制面板注入

alpha2.39 起，向系统设置（`com.android.settings`）的蓝牙设备详情页注入降噪 + 功能控制面板（仅 Settings 进程）。纯 UI 注入组件，不打 BLE/Gaia 单例：

```
Settings 进程 hookDeviceDetailsPanel(ClassLoader)
├── ① onBindViewHolder(Preference) 拦截
│     命中 DeviceDetailsPanel.KEY → 把该条目 itemView 替换为 ControlPanel(降噪卡片+功能控制卡片)
│     → 回调 sendDeviceCommand() 发设备命令 → fetchDcState() 拉状态刷新
├── ② onCreatePreferences(DashboardFragment) 拦截
│     只对 AncProfileLib.isMoondrop(deviceName) 为真 → 追加 DeviceDetailsPanel.KEY 的 Preference
│     （标题/摘要按 langZh 显示中英文）
└── ③ updatePreferenceOrder(BluetoothDetailsConfigurableFragment) 拦截
      把 DeviceDetailsPanel.KEY 注入 displayOrder 白名单，避免被移进 invisible_profile_category 隐藏

跨进程自动刷新（推模式）：
面板 View 挂载时注册 ContentObserver 监听 content://com.fxxkmoondrop.secret.prefs/dc_cmd
→ 模块端状态变化 notifyChange → 重新 fetchDcState() 并刷新面板；卸载时注销
```


### 4.1 空间音频 / 头部跟踪改用官方两行

系统详情页本来就有「空间音频」与「头部跟踪」两个官方开关，但官方 controller（`BluetoothDetailsSpatialAudioController`）
用系统 `Spatializer` 判定可用性：本机 `getImmersiveAudioLevel()==0`、`isAvailableForDevice` 恒为假，于是它把自己刚建好的两行又移除。

模块只放开「判定」这一步，行仍由官方组件渲染与维护：

- `isAvailable()` 直接返回 true（**仅在 `AncProfileLib.isMoondrop(设备名)` 为真时**）；
- `Spatializer` 的 `isAvailableForDevice` / `hasHeadTracker` 放开；`getCompatibleAudioDevices` 按耳机端 GAIA 状态回喂；
- `add/removeCompatibleAudioDevice` 与 `setHeadTrackerEnabled` 转发成 GAIA 命令；`isHeadTrackerEnabled` 回喂三档换算结果；
- `Preference.isVisible/isEnabled` 对 `spatial_audio` / `head_tracking` 两个 key 按 GAIA 就绪状态门控（未就绪不显示、不可点）。

以上 hook 全部按**设备地址**判定归属：非本模块支持的耳机（含系统原生支持空间音频者）一律 `chain.proceed()` 交还官方，
不接管、不改写，避免误伤。

### 5. Google 官方耳机控制面板桥接（Hearable Controls）

系统蓝牙页 / Google 设置里的「耳机控制」面板由 GMS 的 Hearable Controls 链路驱动（GFPS 消息组 `0x08`）。本机没有 GFPS Message Stream（`EventStreamManager: No available EventStreamMedium`），官方入口发包前要等「上一次 SET 的响应」，而这个响应永远等不到，于是点击既不经过子模块的 `in()` 也走不到发包出口 —— 现象是**点了没反应、随后高亮回弹**。因此在 GMS 进程内接住整条官方链路（`HearableControlHook`）：

```
HearableControlHook (GMS 进程)
├── ① DexKit 特征定位官方 ANC 子模块（不硬编码混淆类名，保留硬编码兜底）
├── ② 放开 Fast Pair 缓存门禁：无 GFPS 流时官方面板也走通本地状态
├── ③ 意图入口 hook：子模块 in()（官方点击写 dataStore 的入口）
├── ④ 管理器发送出口 hook：HearableControlManager 发包方法（byte[] + int）
│      两处都把用户点击翻译成 GAIA 请求 → 广播 App 进程 → GaiaBleClient 真实下发
│      并按「报文指纹 + 3 秒窗」/「与 lastInjected 相同」过滤自身注入的 NOTIFY
├── ⑤ 注入官方 DataStore：把 App 侧真实档位写回官方缓存，官方界面高亮与实际一致
└── ⑥ 激活重试：前 20 次 × 3s，之后 30s 长期重试
       （Fast Pair 模块事件驱动加载，原 20 次窗口一过就永久失效）
```

自检日志：`自检 gate=… send=… entry=… mgr=… module=…`，全 `true` 为正常。无 Root 模式下「官方集成」开关置灰，该项注入不启用。

## 弹窗布局架构

### PopupProfile 屏幕布局表

alpha2.38.2 引入 `PopupProfile` 数据类，将所有弹窗坐标收敛到集中配置表：

| 字段 | 含义 |
|---|---|
| `tag` | 档位标识（如 "6.1in-1216x2640"） |
| `iconSizePx` / `iconTopPx` | 耳机图标尺寸与顶部位置 |
| `batteryTopPx` | 电量兜底自绘位置（主路径用 GMS subhead） |
| `modeBarTopPx` | ANC 模式按钮条顶部位置 |
| `modeItemBtnPx` / `modeItemIconPx` | 模式按钮项尺寸 |
| `settingsOffsetFromBtnPx` | 设置按钮相对 central_btn 偏移 |
| `iconTitleGapPx` / `modeBarGapPx` | 安全间距 |

- **6.1 寸档**（1216×2640 / density 3.0）：真机验证坐标
- **6.3 寸档**：等比占位（1.033× 缩放），待真机精调
- `resolveScreenProfile()` 按分辨率+density 自动选档

### 弹窗注入层级

```
HalfSheetActivity (GMS 原生)
├── toolbar_title (耳机名称)          ← GMS 原生
├── subhead (电量文字)                ← alpha2.38.7 恢复，系统原生样式
├── Icon Overlay (自定义图标)         ← FrameLayout.addView
├── Mode Button Bar (ANC 模式条)      ← 动态定位追踪 central_btn
├── Settings Button                   ← 克隆 central_btn 样式
└── central_btn (连接按钮)            ← GMS 原生
```

## 协议架构

### 三协议自动识别

```
连接成功 → 协议识别
  ├── BLE GATT: 发现 GAIA Service UUID → 初始化 GAIA V3 协议
  │   └── 帧格式: [vendor 2B][commandValue 2B][payload...]
  ├── Classic BT RFCOMM: SPP 连接 → 初始化 GAIA V4 协议（布丁 PUDDING）
  │   └── 帧格式同 V3，传输层为 RFCOMM/SPP
  └── BLE GATT: 发现 9ECA0000 Service UUID → 初始化 BleSourceSwitch 协议
      └── 帧格式: [0xA5][0x01][type][cmd][seq][len][payload ≤14B]
```

两条协议可在同一 GATT 连接中并存，互不干扰。

### ANC 三路径自动探测

```
连接成功 → CapabilityProbe.startProbes()
  → BASIC.GET_SUPPORTED_FEATURES
  → 检查位图:
      bit1 → ANC V1 (feature=2, cmd=1/2)
      bit3 → AudioCuration (feature=8, cmd=3/4)
      bit5 → ANC V2 (feature=32, cmd=3/4)
  → 锁定 ancPath → 后续读写走该路径
  → 断连/新会话重置 → 超时自愈重发
```

## 设计原则

1. **零硬编码地址**：LE 地址全动态发现
2. **协议自动识别**：按传输层（BLE GATT / RFCOMM）+ 服务指纹路由，不依赖型号名
3. **坐标集中配置**：弹窗布局参数收敛到 PopupProfile
4. **跨进程解耦**：App 与 GMS 通过广播通信
5. **自愈闭环**：地址丢失 → 扫描 → 发现 → 连接 → 缓存 → 连接
