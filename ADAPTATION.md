# FxxkMoondrop 适配说明

> 本文档记录 FxxkMoondrop 项目在 Moondrop 耳机适配过程中积累的协议知识、踩坑经验和实测数据。

## 设备与连接

| 项目 | 说明 |
|---|---|
| 目标设备 | Moondrop 全系列高通 QCC 耳机（GAIA）+ 中科蓝讯耳机（9ECA） |
| 已实测 | 梦回2 / Golden Ages 2（GA2），TWS-01 定制 SoC |
| 连接方式 | BLE GATT 直连（非 Classic Bluetooth RFCOMM / SPP） |
| 业务协议 | Qualcomm GAIA V3 over BLE |
| 包名 | `com.fxxkmoondrop.secret` |

### BLE GATT 服务与特征 UUID

| 用途 | UUID |
|---|---|
| GAIA Service | `00001100-d102-11e1-9b23-00025b00a5a5` |
| Command（写入） | `00001101-d102-11e1-9b23-00025b00a5a5` |
| Response（通知） | `00001102-d102-11e1-9b23-00025b00a5a5` |
| Data（通知） | `00001103-d102-11e1-9b23-00025b00a5a5` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

> 注意：GA2 存在双地址问题——系统配对使用的 PUBLIC 地址无法直接走 BLE GATT，必须发现真实的 LE 随机地址。详见下文「连接策略」。

## GAIA V3 包格式

```
[vendor 2B BE][commandValue 2B BE][payload...]
```

- `vendor = 0x001D`（Qualcomm）
- `commandValue = (feature << 9) | (type << 7) | command`
- type: `0 = COMMAND`, `1 = NOTIFICATION`, `2 = RESPONSE`

示例：查询 ANC V2 当前模式（feature=32, cmd=3, type=COMMAND）
```
TX: 00 1D 40 03
RX: 00 1D C0 03 <mode>
```

## 噪声控制

### 三条 ANC 路径

FxxkMoondrop 运行时自动探测耳机支持哪条 ANC 路径，不硬编码型号：

| 路径 | Feature ID | 探测条件 | GET 命令 | SET 命令 |
|---|---|---|---|---|
| ANC V1 | 2 | BASIC 特性位图含 bit1 | cmd=1 (GET_ANC_STATE) | cmd=2 (SET_ANC_STATE) |
| AudioCuration | 8 | BASIC 特性位图含 bit3 | cmd=3 (GET_CURRENT_MODE) | cmd=4 (SET_MODE) |
| ANC V2 | 32 | BASIC 特性位图含 bit5 | cmd=3 (GET_CURRENT_MODE) | cmd=4 (SET_CURRENT_MODE) |

探测流程：连接成功 → 查询 `BASIC.GET_SUPPORTED_FEATURES` → 按位图判断 → 锁定 ancPath → 后续读写走该路径。断连和每次新 GATT 会话均重置探测状态并超时自愈重发。

### GA2 实测 ANC 设备码映射

> ⚠️ SET 和 GET 的映射**不同**！这是 GA2 固件的特殊行为，直接使用 AudioCuration 名义编码会导致按钮错乱。

**SET 方向（UI → 设备码）：**

| UI 模式 | 设备码 |
|---|---|
| 关闭 | 1 |
| 降噪 | 2 |
| 透传 | 4 |
| 抗风 | 3 |

**GET 方向（设备码 → UI）：**

固件读回是 **0-based** 直传，与 SET 的 1-based 枚举不同：

| 设备码 | UI 模式 |
|---|---|
| 0 | 关闭 |
| 1 | 降噪 |
| 2 | 透传 |
| 3 | 抗风 |

### ANC V2 模式枚举（官方 App AncV2Handler）

| 模式 | 值 |
|---|---|
| OFF | 0 |
| ON | 1 |
| TRANSPARENT | 2 |
| ANTI_WIND | 3 |
| ADAPTIVE | 4 |
| LIVE | 5 |

### AudioCuration 高级命令

除了基本的 GET/SET_MODE，AudioCuration 还包含丰富的子功能（部分 GA2 不稳定支持）：

| 命令 | ID | 说明 |
|---|---|---|
| GET_AC_STATE | 0 | AC 开关状态 |
| SET_AC_STATE | 1 | 设置 AC 开关 |
| GET_MODES_COUNT | 2 | 可用模式数 |
| GET_CURRENT_MODE | 3 | 当前模式 ← **GA2 稳定** |
| SET_MODE | 4 | 设置模式 ← **GA2 稳定** |
| GET_GAIN | 5 | 透传增益 |
| SET_GAIN | 6 | 设置透传增益 |
| GET_TOGGLE_CONFIGURATION | 8 | 触控切换配置 |
| SET_TOGGLE_CONFIGURATION | 9 | 设置触控切换 |
| GET_WIND_NOISE_DETECTION_STATE | 23 | 风噪检测状态 |
| SET_WIND_NOISE_DETECTION_STATE | 24 | 设置风噪检测 |
| GET_CURRENT_ANC_SWITCH_CONF | 41 | 当前 ANC 切换配置 ← **GA2 对此回包不稳定，不推荐** |
| SET_ANC_SWITCH_CONF | 42 | 设置 ANC 切换配置 |

> 踩坑：GA2 对 cmd=41 (GET_CURRENT_ANC_SWITCH_CONF) 回包不稳定，会导致 ancPath 卡死在 -1。alpha2.25 回退到 cmd=3 (GET_MODE) 解决。

### 型号档案库（AncProfileLib）

未实测型号回退默认映射（AC 名义编码 1=关 / 2=降噪 / 3=透传 / 4=抗风）。新型号实测确认后追加到 `AncProfileLib.PROFILES`，按设备名关键字匹配。用户可在设置页自定义映射，优先级最高。

## 电量

### GAIA V3 电量查询

```
TX: feature=13 (BATTERY), cmd=1 (GET_BATTERY_LEVELS), payload=[1, 2]
```

payload 中 `[1, 2]` 表示查询左耳(1)和右耳(2)。

| Battery ID | 含义 |
|---|---|
| 1 | 左耳 |
| 2 | 右耳 |
| 3 | 充电盒 |

回包中每个 byte 对应一个 batteryId 的电量百分比。GA2 实测仅返回左右耳，无充电盒。

> GA2 不通过 Android 系统的 `BluetoothBattery` API 提供电量，必须走 GAIA 协议直读。

## Moondrop 私有协议（9ECA0000 BleSourceSwitch）

> 适用于中科蓝讯（Bluetrum）主控耳机（猫饼 NEKOCAKE / 太空漫游2 / 音乐胶囊等）。

### BLE GATT 服务

| 用途 | UUID |
|---|---|
| Service | `9eca0000-7f3a-4f32-9a38-a91b2c6e0100` |
| Command（写入） | `9eca0001-...` |
| Response（通知） | `9eca0002-...` |
| Notification（通知） | `9eca0003-...` |
| Capability（直读） | `9eca0004-...` |
| Firmware Info（直读） | `9eca0005-...` |

### 帧格式

```
[0xA5][0x01][frameType][commandId][seq][payloadLen][payload ≤ 14 bytes]
```

| 字段 | 说明 |
|---|---|
| Magic | `0xA5` 固定 |
| Version | `0x01` 固定 |
| frameType | 1=COMMAND, 2=RESPONSE, 3=NOTIFICATION |
| seq | 序列号，请求与回包匹配 |
| payloadLen | 最大 14 字节 |

### 命令列表

| 命令 | ID | 说明 |
|---|---|---|
| GET_AUDIO_SOURCE | 1 | 查询当前音源 |
| SET_AUDIO_SOURCE | 2 | 切换音源（sourceId + options + fadeSec） |
| GET_CAPABILITY | 3 | 查询能力页 |
| GET_FW_VERSION | 4 | 查询固件版本 |
| GET_DEVICE_VOLUME | 5 | 查询设备音量 |
| SET_DEVICE_VOLUME | 6 | 设置音量（left, right, mute） |
| GET_PRESET_EQ | 7 | 查询预设 EQ 列表 |
| SET_PRESET_EQ | 8 | 设置预设 EQ |
| GET_PEQ_CONFIG | 9 | 查询 PEQ 配置 |
| SET_PEQ_PREGAIN | 10 | 设置 PEQ 前置增益 |
| GET_PEQ_POINT | 11 | 查询 PEQ 点 |
| SET_PEQ_POINT | 12 | 设置 PEQ 点 |
| COMMIT_PEQ | 13 | 提交 PEQ |
| GET_MIC_GAIN | 14 | 查询麦克风增益 |
| SET_MIC_GAIN | 15 | 设置麦克风增益 |
| PING | 127 | 心跳 |

### 音源 ID

| 音源 | ID |
|---|---|
| 蓝牙 | 0 |
| USB 音频 | 1 |
| 2.4G 无线 | 2 |
| AUX Line-In | 3 |
| 光纤 SPDIF | 4 |
| 同轴 Coaxial | 5 |
| HDMI ARC | 6 |
| 本地播放 | 7 |
| 自动选择 | 127 |
| 无信号 | 254 |

### 9ECA 与 GAIA 共存

9ECA 服务和 GAIA 服务可以在同一个 GATT 连接中并存。FxxkMoondrop 连接后同时探测两种服务，存在 9ECA 则初始化 `BleSourceSwitchClient` 复用同一 GATT 连接。两条协议互不干扰。

## 连接策略

### 双地址问题

GA2（DUAL 模式）存在两个蓝牙地址：
- **PUBLIC 地址**：系统配对使用的地址，走 Classic BT profile（A2DP/HFP）
- **LE 随机地址**：BLE GATT 通信必须使用的地址

系统 `BluetoothAdapter.getBondedDevices()` 返回的是 PUBLIC 地址，直接用它发起 GATT 连接会等待后台广播超时。

### 动态 LE 地址发现

FxxkMoondrop 不硬编码任何 LE 地址，采用自愈闭环：

```
无缓存 → 请求 GMS 扫描 → GMS BLE 扫描发现耳机 LE 地址 → 推送给应用
→ 连接成功 → 写回缓存文件/SharedPreferences → 下次秒连
→ 地址变化自动重新发现
```

### GMS 扫描注入（LSPosed 模块）

LSPosed 模块 Hook `com.google.android.gms` 进程，注入 BroadcastReceiver 接收应用的扫描请求广播。收到请求后调用 GMS 自身的 `BluetoothLeScanner` 进行 BLE 扫描，按设备名过滤结果，将发现的 LE 地址回传给应用。

扫描节流：30 秒内仅允许一次扫描请求，防止频繁唤醒。

扫描结果按名称匹配 `moondrop` 关键字（不区分大小写），命中后持久化到应用私有目录。

## Fast Pair 弹窗集成

### Google Fast Pair 半屏弹窗

通过 Hook GMS 的 `HalfSheetActivity`，注入自定义设备名和图标：

1. Hook `dtes.f(Context)` 方法——修改设备名字段（`dtok.l` / `dtok.i`）
2. Hook `dthi.O(ImageView, dtok)` 方法——注入自定义设备图标 Bitmap
3. Hook `HalfSheetActivity` 生命周期——在弹窗显示后注入 ANC 控制按钮

### 应用自带悬浮卡片（已经废弃）

不依赖 Xposed，通过 `WindowManager` 添加 TYPE_APPLICATION_OVERLAY 窗口。设置页可切换两种模式。

## LSPosed 模块架构

### 入口

- 入口类：`com.fxxkmoondrop.secret.XposedEntry`
- 继承 `XposedModule`（libxposed API 102，LSPosed ≥ 2.1.1）
- 资源声明：`META-INF/xposed/{java_init.list, module.prop, scope.list}`

### 作用域

| 包名 | 用途 |
|---|---|
| `com.google.android.gms` | Fast Pair 弹窗注入 + BLE 扫描能力借道 |
| `com.android.settings` | 设置页注入耳机入口（准备实现） |
| `com.android.bluetooth` | A2DP 连接状态监听 |
| `com.moondroplab.moondrop.moondrop_app` | Hook 官方 App 的 ANC 控制路径（逆向参考） |

### 模块功能

| Hook 目标 | 功能 |
|---|---|
| GMS `dtes.f` | 修改弹窗设备名 |
| GMS `dthi.O` | 注入弹窗设备图标 |
| GMS `HalfSheetActivity` | 弹窗内注入 ANC 按钮 |
| Settings `AdvancedConnectedDeviceDashboardFragment` | 设置页注入耳机入口 |
| Bluetooth A2DP 状态 | 连接时静默拉起 App / 断开时停止进程 |
| Moondrop App `AncV2Handler` | 逆向参考官方 ANC 控制路径 |

## 问题记录

### 1. ANC 刷新跳回关闭（alpha2.23-2.29）

现象：设置 ANC 模式后刷新，按钮状态跳回关闭。

根因：`connectedDeviceName` 为空时 `AncProfileLib.resolve()` 回退默认映射，而 GA2 的默认映射与实测映射不同（3 和 4 颠倒）。GET 方向又是 0-based，与 SET 的 1-based 不同。

修复：连接成功后确保 `connectedDeviceName` 正确赋值；GET/SET 双向映射分离。

### 2. 能力探测卡死 ancPath=-1（alpha2.19）

现象：首次连接后 ANC 完全不可用，`ancPath` 始终为 -1。

根因：探测标志一旦设置永不重置，断连后重新连接时跳过探测。

修复：断连和每次新 GATT 会话均重置探测状态，超时后自动重发探测命令。

### 3. cmd=41 回包不稳定（alpha2.25）

现象：使用 `GET_CURRENT_ANC_SWITCH_CONF` (cmd=41) 查询 ANC 状态时，GA2 回包不稳定。

修复：回退到 `GET_MODE` (cmd=3)，GA2 对 cmd=3 回包稳定。

### 4. 假连接（alpha2.18）

现象：耳机已物理断开，但 App 仍显示「已连接」。

根因：陈旧 GATT 缓存 + 双地址自我反馈（PUBLIC 地址连上 PUBLIC 地址）。

修复：断连时清空所有缓存地址；连接成功后校验 GATT 服务是否包含 GAIA Service UUID。

### 5. GA2 连接 12 秒超时（alpha2.20-2.21）

现象：开盖后需 12 秒才连上，经常超时。

根因：使用 bonded PUBLIC 地址发起 GATT 连接，等待后台广播超时后轮换。

修复：优先使用已学习的 LE 随机地址；无缓存时通过 GMS 扫描发现真实 LE 地址。

## 已知限制

- GA2 无充电盒电量读取（GAIA 回包仅含左右耳）
- 无增益控制和指示灯控制（GA2 固件未暴露相关 feature）
- 9ECA 协议客户端已实现但未经实机验证
- Fast Pair 弹窗依赖完整 GMS，ColorOS 需额外模块
