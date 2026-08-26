# FxxkMoondrop 架构文档

> 版本：alpha2.38.7 ｜ 更新日期：2026-08-27

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

`PrefsProvider`（ContentProvider）提供跨进程 SharedPreferences 读写，App 和 GMS 进程共享配置。

## 核心模块

### App 进程

| 模块 | 文件 | 行数 | 职责 |
|---|---|---|---|
| **GaiaBleClient** | `GaiaBleClient.kt` | 1246 | BLE GATT 直连耳机单例；连接管理、GAIA V3 + 9ECA 双协议自动识别、电量读取、ANC 控制 |
| **HeadsetDetectService** | `HeadsetDetectService.kt` | 367 | 前台服务；监听蓝牙连接状态，驱动 GaiaBleClient 连接/断开，轮询 ANC |
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
| **M3Ui** | `M3Ui.kt` | 412 | Material 3 UI 组件工厂 |
| **DeviceControlBridge** | `DeviceControlBridge.kt` | 182 | 增益/指示灯/空间音频等扩展设备控制回调 |

### GMS 进程（Hook 注入）

| 模块 | 文件 | 行数 | 职责 |
|---|---|---|---|
| **FastPairHookEntry** | `hook/FastPairHookEntry.kt` | 1677 | GMS 进程全部 Hook 逻辑；弹窗生命周期、图标/电量/ANC 按钮注入、BLE 扫描借道 |
| **PopupProfile** | (内嵌于 FastPairHookEntry) | - | 屏幕布局参数表；按分辨率分档（6.1寸/6.3寸），坐标集中配置 |
| **XposedEntry** | `XposedEntry.kt` | 403 | LSPosed 模块入口；路由到各进程 Hook |

### LSPosed 模块入口路由

```
XposedEntry (META-INF/xposed/java_init.list)
├── onPackageReady("com.google.android.gms") → FastPairHookEntry.onGmsLoaded()
├── onPackageReady("com.android.settings")  → hookSettings() 注入耳机入口
├── onPackageReady("com.android.bluetooth") → hookBluetooth() A2DP 状态监听
└── onPackageReady("com.moondroplab...")     → hookMoondrop() 逆向参考
```

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

### 双协议自动识别

```
GATT 连接成功 → onServicesDiscovered
  ├── 发现 GAIA Service UUID → 初始化 GAIA V3 协议
  │   └── 帧格式: [vendor 2B][commandValue 2B][payload...]
  └── 发现 9ECA0000 Service UUID → 初始化 BleSourceSwitch 协议
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
2. **协议自动识别**：按 GATT 服务指纹路由
3. **坐标集中配置**：弹窗布局参数收敛到 PopupProfile
4. **跨进程解耦**：App 与 GMS 通过广播通信
5. **自愈闭环**：地址丢失 → 扫描 → 发现 → 连接 → 缓存 → 连接
