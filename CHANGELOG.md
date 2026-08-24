# FxxkMoondrop 更新日志（Changelog）

> 版本号格式：`alpha.x.y` —— `x` 为里程碑，`y` 为迭代。`versionCode` 单调递增。
> 本项目为 **LSPosed/Xposed 模块**，注入 GMS（`com.google.android.gms`）Hook 高通 GAIA 蓝牙协议 + Moondrop 私有 `9ECA0000` 协议。
> 时间线从 2026-08-22 起（开发者实机验证款）。更早的 alpha1.x（单体 Activity + 旧打包链）不在本仓库。

---

## alpha2.25（2026-08-24，versionCode 242 / versionName alpha2.25）——能力探测回退 cmd=41→3

- **背景**：alpha2.24 起降噪探测定为「能力位图未回时主动发 AudioCuration 只读探测定路径」，并按官方 App 逆向改用 `cmd=41(GET_CURRENT_ANC_SWITCH_CONF)` 读 GA2 降噪；实测 GA2 对 cmd=41 回包不稳，导致 `fetchAncMode` 读降噪路径判定不理想。
- **修改（GaiaBleClient.kt，以装机版 `fxxk_alpha226.apk` 反编译为准）**：
  1. `fetchAncMode` 的 AudioCuration 路径（`ANC_PATH_AUDIO_CURATION`）**回退到 `cmd=3(GET_MODE)`**：`writeCommand(F_AUDIO_CURATION, CMD_AC_GET_MODE, …)`；`else` 兜底分支同样发 `cmd=3`。
  2. `setAncMode`：`ancPath==-1(UNKNOWN)` 时规整为 8 后走 `ancDevFromUi`，再按 `i==2(ANC_V2)→writeCommand(2,2)`、`i==8→writeCommand(8,4)`、`i==32(ANC_V1)→writeCommand(32,4)` 发送。
  3. 关键常量：`CMD_AC_GET_MODE=0x03`、`CMD_AC_GET_SWITCH_CONF=0x29(41)`、`CMD_AC_SET_MODE=0x04`。
- **产物**：`fxxk_alpha226.apk`（已装机验证）。LSPosed 中「启用模块」+ 作用域 `com.google.android.gms` 已勾选。
- **验证**：真机 GA2（Golden Ages 2）装机，LSPosed 激活 + GMS 作用域重启（先关蓝牙），GMS 进程换新 pid 重新注入 hook。降噪读回 cmd=3。

## alpha2.24（2026-08-24，versionCode 241）——官方 App 逆向证据落地：GA2 走 ANC_V2

- **背景**：需要确认 GA2 降噪到底走哪条 ANC 协议路径，不硬编码型号。
- **逆向证据**：官方 MOONDROP App 用 **QTIL AudioCuration(feature=0x08) + `cmd=41(GET_CURRENT_ANC_SWITCH_CONF)`** 读 GA2 降噪（GA2 走 ANC_V2，而非 AudioCuration）。
- **修改（GaiaBleClient.kt）**：回退探测发相同命令，靠真实回包确认走向 AudioCuration 路径；`handlePacket` 增加 `RX raw` 原始字节日志；按回包 `feature` 判定 ANC 路径（GA2 走 ANC_V2 0x20，兼容 AudioCuration 0x08）。
- **验证**：实机确认 GA2 走 ANC_V2（0x20）。

## alpha2.23（2026-08-24，versionCode 240）——降噪刷新「跳回关闭」bug 修复（恒等映射 + ancPath 显式化）

- **背景**：降噪控制切换后刷新又跳回「关闭」，ANC 路径映射错乱。
- **修改（GaiaBleClient.kt / GaiaCommands.kt）**：ANC 路径恒等映射；`ancPath` 显式化；降噪读取改用 `cmd=41(GET_CURRENT_ANC_SWITCH_CONF)`（GA2 等新协议耳机不回 V1 cmd=3）；`cmd=41/42` 返回 settledActions 字节流，在实测确认映射前仅记录原始字节做证据，不调用 V1 `parseAncMode`。
- **验证**：已装机验证。

## alpha2.22（2026-08-24，versionCode 239）——取消乐观更新 + 官方高通协议（AudioCuration）落地

- **背景**：按官方协议重构 GAIA 连接，取消「乐观更新」，改为以设备真实回包为准。
- **修改（跨 GaiaBleClient / GaiaCommands / AncBridge / FastPairHookEntry / HeadsetReceiver / OverviewFragment）**：
  1. 能力位图截断检测（payload 长度非 4 的倍数 → 保持 UNKNOWN，宁降级为无 ANC 也不误发错命令）。
  2. 能力探测终态 `capabilityProbeDone / capabilityProbeTruncated / ancAcProbeActive`；断开与每次新 GATT 会话均重置。
  3. 主动只读探测：GA2 只回电池不回能力位图时，超时后主动发 AudioCuration `GET_MODE` 探测，靠真实回包定路径。
  4. `AncBridge.sendAncStatus()` 广播能力状态给 GMS 弹窗，驱动降噪按钮三态。
  5. ANC_V2 恒等映射；`cachedLe` 记录设备名 `cachedLeName` 防多台串扰。
  6. `setAncMode` 区分「能力未就绪/无 ANC」与「该路径不支持此模式」，均不发送防误发。

## alpha2.21（2026-08-24，versionCode 238）——连接锁到已学习 LE 地址，GA2 秒连

- **修改（GaiaBleClient.kt）**：`retryConnect` 优先用已学习 LE 地址（cachedLe），日志显示真实候选索引。
- **效果**：GA2 开盖 1.8s 秒连 GAIA 通道，不再 12s 超时轮换。

## alpha2.20（2026-08-24，versionCode 237）——已学习 LE 地址优先于 bonded 主地址

- **修改（GaiaBleClient.kt）**：连接候选里已学习 LE 地址优先于 bonded 中的 BR/EDR PUBLIC 主地址。
- **效果**：避免拿 PUBLIC 地址走 LE 后台等广播导致 12s 超时。

## alpha2.19（2026-08-23，versionCode 236）——修复降噪「时好时坏」

- **修改（GaiaBleClient.kt）**：能力探测标志断开/每次新 GATT 会话均重置，超时自愈重发。
- **效果**：修复 `ancPath` 卡死在 -1 导致的降噪控制时好时坏。

## alpha2.18（2026-08-23，versionCode 235）——修复「耳机已断开仍显示已连接」

- **根因**：LE 缓存丢失后 resolve 无法找回；init 环境决策有缺陷；`startScanForLe` 权限异常路径未复位扫描；`getConnectedMac` 主线程未命中仍返回 SP 陈旧缓存；`pollConnected` 同族双地址并发重复连接。
- **修复**：env 决策仅 hookOk 走 GMS 桥否则自扫兜底；`requestRemoteScan` 增加铁证日志；`startScanForLe` 所有失败路径复位；`getConnectedMac` 实时探测未命中即作废陈旧缓存；`pollConnected` 同族设备 GAIA 已连时 skip reconnect；Manifest 增加 `usesPermissionFlags="neverForLocation"`。
- **验证**：force-stop 后重启，cachedLe=9C:39 加载 → GAIA connected → 双耳 100%，双地址去重生效。


## alpha2.17（2026-08-23，versionCode 234）——修复 GA2（DUAL）连接不上（方案C）
- **根因**：GA2 是 DUAL 设备，bonded 里只有 BR/EDR PUBLIC 地址，真实 LE 地址（独立身份地址，前缀 12 位相同）只能靠 BLE 扫描广播获取；`resolveLeAddress` 只匹配 bonded 中 type=LE 同名设备 → 永远解析不出 → 拿 PUBLIC 地址走 `connectGatt(auto=true, TRANSPORT_LE)` 后台等广播 → 12s 超时死循环。
- **修改（GaiaBleClient.kt，全动态无硬编码）**：
  1. `connectGatt` 的 autoConnect 由 `true` 改 `false`（direct connect，4 处调用点）——配对已知地址立即直连，失败快速回调进入候选轮换/扫描兜底。
  2. resolve 不到 LE 地址且无缓存时，接线 `startScanForLe(device)`（按设备名 BLE 扫描发现独立 LE 广播地址，幂等）。
  3. 扫描命中后改用 `doConnectLe(ScanResult)`——用原始设备（保留 PUBLIC/RANDOM 地址类型，避免 getRemoteDevice 错配）。
- **验证**：assembleRelease + testDebugUnitTest 全绿（65 项）。

## alpha2.16.1（2026-08-23，versionCode 233）——运行日志导出 hotfix

- **修改（AppLog.kt / LogCollector.kt）**：新增 `dumpAll()`（runtime.log 全文 + runtime.1.log + 内存未落盘条目合并去重）；`init()` 加固（mkdirs 失败显式置 dir=null 记录失败原因）；`06_运行日志.txt` 改用 `dumpAll()`。
- **验证**：跨进程重启历史保留通过，内存增量去重正确。

## alpha2.16（2026-08-23，versionCode 232）——全链路运行日志（支持发给他人无 Root 手机测试）

- **新增 AppLog.kt**：内存环形缓冲 2000 条 + 文件 `filesDir/appslog/runtime.log`（≤512KB 自动轮转），线程安全、init 幂等、`hex()` 帧工具。
- **接入点（全链路）**：GaiaBleClient（连接/扫描/GATT/服务发现/协议识别/GAIA RX 帧）、BleSourceSwitchClient（9ECA 帧）、HeadsetDetectService（BT_EVENT/连接决策）。
- **LogCollector.kt**：新增 `06_运行日志.txt`；设置页文案 5→6 条。
- **验证**：testDebugUnitTest 全绿（65 项）。

## alpha2.15（2026-08-23，versionCode 231）——蓝讯 9ECA0000 完整协议客户端接入 + 自动识别协议

- **协议层（新，Java 自包含）**：`BleSourceProtocol.java`（帧 [A5][01][type][msgId][seq][len][payload]，命令/响应/通知，全套载荷编码与解析）+ `BleSourceSwitchClient.java`（事务层：单 pending + seq 匹配 + 超时 + 直读 + 语义化 API）。
- **自动识别协议（GaiaBleClient.kt）**：按 GATT 服务指纹路由（GAIA=QCC 系，9ECA0000=蓝讯系），不依赖型号名；onServicesDiscovered 改 GAIA optional。
- **修正生态位 bug（GaiaCommands.kt）**：SRC_FRAME_* / SRC_NOTIF_* 常量对齐官方值。
- **验证**：testDebugUnitTest 全绿（76 项），混编通过。

## alpha2.14（2026-08-23，versionCode 230）——开源发布（GitHub）基础版

- 首个开源 GitHub 版本（repo 起点），含 README、LICENSE、支持设备适配清单。

## alpha2.13（2026-08-23，versionCode 229）——Kotlin 迁移 28/28 完成 + Gradle/AGP 工程化

- 纯 Kotlin 源码（28 个 `.kt`，零 Java）；Gradle 8.9 + AGP 8.5.2 + Kotlin 1.9.22；clean 全量构建验证通过。
- 修复设置/关于页标题与状态栏重叠；修复切换 AMOLED 触发 recreate 后页面丢失。

## alpha2.12（2026-08-23，versionCode 228）——M3 三页 Fragment 架构

- 主页 / 设置 / 关于三页 Fragment 架构，全部 Material 3 组件化。

## alpha2.0（2026-08-23，versionCode 200）——官方 Material 3 组件化重构

- UI 全面迁移到官方 Material 3 组件；版本号 alpha1.40 → alpha2.0（vc 140 → 200）；Hook/GAIA/蓝牙链路零改动。
- 新 M3 构建链（tools/build_m3.py）：aapt2 → gen_lib_rtxt → javac → D8 → 打包 → apksigner v3。

---

> **备注**：更早的 alpha1.x（vc 100-140，单体 Activity + 旧打包链）不在本仓库。上述版本历史含开发主区 alpha_src 迭代记录。
> **逆向声明**：本项目不包含 Moondrop App 任何代码/资源/反编译产物；hook 目标类名仅以字符串引用，仅供学习研究。
