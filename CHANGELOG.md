# FxxkMoondrop 更新日志（Changelog）

> 版本号格式：`alpha.x.y` —— `x` 为里程碑，`y` 为迭代。`versionCode` 单调递增。
> 本项目为 **LSPosed/Xposed 模块**，注入 GMS（`com.google.android.gms`）Hook 高通 GAIA 蓝牙协议 + Moondrop 私有 `9ECA0000` 协议。
> 时间线从 2026-08-22 起（开发者实机验证款）。更早的 alpha1.x（单体 Activity + 旧打包链）不在本仓库。

---

## alpha2.38.4 (2026-08-27)
- 弹窗图标+模式面板整体上抬 140px（6.1寸 icon 1520 / modeBar 1910；6.3寸 1576 / 1979），给设置按钮腾出空间，修复设置按钮与降噪面板重叠

## alpha2.38.3（2026-08-26，versionCode 263）——设置按钮完全克隆确定按钮 + 上方对齐

- 设置按钮恢复克隆 `minHeight`/`minWidth`，确保与确定按钮（`central_btn`）尺寸完全一致。
- 设置按钮改为放在确定按钮**正上方**，`schedulePlace` 动态获取确定按钮屏幕坐标后定位：`width=ref.width`、`height=ref.height`、`leftMargin` 同左对齐、`topMargin = ref.top - ref.height - 8dp gap`。
- padding 完全克隆（不再缩放 0.6f），视觉与确定按钮一致。

## alpha2.38.2（2026-08-26，versionCode 262）——弹窗布局按屏幕分档硬编码 PopupProfile

- 新增 `PopupProfile` 数据类 + `PROFILE_61`/`PROFILE_63` 两档配置，按屏幕分辨率+density 自动选档。
- 6.1 寸档（1216×2640 / density 3.0 / 460dpi）：图标 340px@top1660、模式条 top2050。
- 6.3 寸档（等比占位，待真机精调）：图标 352px@top1716、模式条 top2119。
- 图标、模式条、模式项图标/按钮/标签尺寸全部从 Profile 读取，不再散落魔法数字。
- 移除 `injectIconIntoTree` 调用（overlay 方案下不再需要遍历树注入原生 ImageView，避免双图标）。

## alpha2.38.1（2026-08-26，versionCode 261）——设置按钮修复 + 移除 SIM 断连

- 修复设置按钮注入逻辑（overlay 方案替代 parent.addView）。
- 移除 SIM 卡断连检测逻辑（误触发）。

## alpha2.38（2026-08-26，versionCode 260）——移除 PopupOverlay + 全部硬编码 UI 值修复

- 移除旧的动态 PopupOverlay 追踪逻辑，改为屏幕绝对坐标 overlay。
- 新增 `screenXY()`（`getLocationOnScreen`）跨窗口坐标系工具 + `schedulePlace()` 轮询等待 ref 布局完成。
- 设置按钮从 parent 插入改为 decor overlay，不再依赖 central_btn 的父容器。

## alpha2.37（2026-08-26，versionCode 259）——弹窗设置按钮对齐 + DC 自定义设置

- 弹窗设置按钮与确定按钮同行对齐（克隆样式插入父容器）。
- 扩展设备控制（DC）设置页新增自定义选项。

## alpha2.36.1（2026-08-26，versionCode 256）——修复 BLE 写队列死锁

- 修复 BLE writeCommand 队列在特定时序下死锁导致命令无法发出。

## alpha2.36（2026-08-26，versionCode 255）——BLE 写队列 + 连接状态读回

- 新增 BLE writeCommand 单路径写队列，避免并发写冲突。
- 连接成功后正确读回当前 ANC 模式状态（而非乐观更新残留值）。

## alpha2.35.3（2026-08-25，versionCode 254）——消除全部硬编码标签/映射

- 所有 ANC 模式标签、设备码映射统一收归 `AncProfileLib`，不再在 UI 代码中硬编码。

## alpha2.35.2（2026-08-25，versionCode 254）——移除 DeviceControlBridge 硬编码 gainMap

- 增益映射改用 `AncProfileLib.DEFAULT_DC`，不再在 Bridge 中硬编码。

## alpha2.35.1（2026-08-25，versionCode 254）——修复 gainMap [0,1,2]→[2,1,0]

- 设备值 0=高增益、2=低增益，映射顺序修正。

## alpha2.35（2026-08-25，versionCode 254）——SET 响应为 ACK 非状态

- SET 响应是 ACK 确认而非当前状态值，拆分 GET/SET 路由避免误解析。

## alpha2.34.1（2026-08-26，versionCode 261）——增益映射修复 + 空间音频开关 + 动态 gainCount

- 修复增益映射 + 空间音频总开关/子模式 + 动态 gainCount。

## alpha2.34（2026-08-26，versionCode 261）——DC callback 架构修复 + DcProfile 增益映射

- 修复扩展设备控制 callback 架构 + DcProfile 增益映射。

## alpha2.33（2026-08-26，versionCode 260）——DC 命令解锁 + 空间音频总开关

- 修复 DC 命令被 feature bitmap guard 误拦 + MaterialSwitch tint + 空间音频总开关与子模式重构。

## alpha2.31（2026-08-26，versionCode 258）——扩展设备控制 + libxposed API 102 迁移

- 新增增益/LED/空间音频扩展设备控制 + 电量缺失保留。
- 迁移到 libxposed API 102（LSPosed 2.1.1 兼容）。

## alpha2.30（2026-08-25，versionCode 257）——修复 getMap null

- 修复 customSet=true 时 getMap 返回 null 导致降噪解析失败。

## alpha2.29（2026-08-25，versionCode 256）——修复 ANC 刷新回到关闭

- 修复 connectedDeviceName 空值导致 ANC 刷新时模式跳回关闭 + 诊断日志。

## alpha2.28（2026-08-25，versionCode 255）——BLE 扫描统一 + M3Ui 弹窗构建器

- BLE 扫描统一 + writeCommand 单路径 + M3Ui 弹窗构建器 + 性能优化。

## alpha2.27（2026-08-25，versionCode 254）——GaiaBleClient 拆分 + 跨进程电量广播

- GaiaBleClient 拆分为 4 模块（GaiaBleClient/GaiaCommands/AncProfileLib/DeviceControlBridge）+ 跨进程电量广播。

---

## alpha2.26.10（2026-08-25，versionCode 253）——GET/SET 双向映射分离（GA2 固件读回 0-based 直传）

- **背景**：真机抓包发现 GA2 固件 **GET_MODE(cmd=3) 读回值**是 0-based 直传（0=关/1=降/2=透/3=抗），与 **SET_MODE(cmd=4) 的 1-based 枚举**（1=关/2=降/4=透/3=抗）是两套编码；旧代码用 SET 映射 indexOf 反查读回值，读到 0 时解析失败（ui=-1）导致按钮状态卡死。
- **实测证据**：SET [2]→读回[1]、SET [4]→读回[2]、读回稳定 [2,1,0,0]（payload[0]=mode 值，官方 Mode.java 同解析）。
- **改动**：`AncProfileLib.Profile` 增加独立 `getMap` 字段（GA2: [0,1,2,3]）+ `resolveGetMap()`；`GaiaCommands.ancUiFromDev` 支持档案 getMap，未命中档案/自定义映射回退 indexOf 反查（不影响其他机型）；`GaiaBleClient.readAncGetMap()` 传入解析。
- **验证**：编译 BUILD SUCCESSFUL，scope 注入 VERIFY OK，vc253 已装机；四按钮 SET→读回→UI 全链路实测闭环。
- **其他**：设置页 ANC 映射提示精简（移除 GA2 实测文案）；诊断日志扩充——LogCollector 新增「ANC 映射状态」小节（设备名/档案/生效 SET 映射）、GaiaBleClient 补 AppLog（ancSetMap/ancGetMap/ancModeParse），logcat 抓取 600→1000 行。

## alpha2.26.9（2026-08-24，versionCode 252）——ANC 型号档案库 AncProfileLib


- **背景**：GA2（梦回二）官方 App 实测设备码 **1=关闭 / 2=降噪 / 3=抗风 / 4=透传**，与 AudioCuration 名义编码（1=关/2=降/3=透/4=抗）中 3、4 顺序相反，默认映射导致透传与抗风互串。
- **新增 `AncProfileLib.kt` 型号档案库**：按设备名自动匹配实测映射（`GOLDEN AGES 2` → UI[关,降,透,抗] = dev[1,2,4,3]），每次连接自动套用；未实测型号回退 AC 名义默认映射。
- **生效优先级**：用户自定义（设置页编辑即标记 `anc_map_custom=1`）> 型号档案 > 默认映射。
- **协议隔离**：档案库仅 GAIA AudioCuration 路径（ancPath=8）生效；蓝讯/中科 9ECA（BleSourceSwitch）无任何 ANC 逻辑，绝不混用。
- 连接日志打印 `connected name=xxx ancProfile=...` 可实时验证档案命中；设置页自定义映射显示「当前生效」设备码。
- **验证**：编译 BUILD SUCCESSFUL；已打包签名装机 vc252。

## alpha2.26.8（2026-08-24，versionCode 251）——连接修复装机

- `leAddrVerified`：仅扫描确认过的 LE 地址才持久化，防 DUAL 设备 BR/EDR 地址误存为 LE 导致直连 GATT 超时；连接成功先刷新 GATT 缓存再发现服务（对齐官方 App refreshDeviceCache）。
- 真机听感验证：AC 1-based 映射（关/降/透）全部正确。

## alpha2.26.7（2026-08-24，versionCode 250）——回退 UNKNOWN→AudioCuration 违规链

- 全量审查发现 3 处把 `ANC_PATH_UNKNOWN` 当 AudioCuration 误发跨路径命令，违反「未知/未就绪绝不当特定能力」铁律；全部回退为不发送并提示原因。

## alpha2.26.6（2026-08-24，versionCode 249）——弹窗电量模拟残留修复

## alpha2.26.5（2026-08-24，versionCode 248）——弹窗抗风按钮可隐藏 + 去开关 toast

## alpha2.26.4（2026-08-24，versionCode 247）——连接链路提速 + 弹窗按钮恢复

## alpha2.26.3（2026-08-24，versionCode 246）——抗风按钮可隐藏 + Material Experience 界面

## alpha2.26.2（2026-08-24，versionCode 245）——ANC 按钮映射可配置化

- 设置页新增自定义降噪按钮设备码（0-5）编辑，SP cfg `anc_map_0..3` 覆盖默认映射。
- 默认映射按 GA2 实测改为 AC 1-based `[1,2,3,4]`。

## alpha2.26 / alpha2.26.1（2026-08-24，versionCode 243/244）——降噪控制重构按钮错乱修复

- AudioCuration 路径映射对齐 fxxk_alpha226 装机版；`fetchAncMode` 真正改用 `cmd=3(GET_MODE)`（此前文档已写但源码实为 cmd=41）；官方面板/主界面补齐第 4 模式「抗风」。

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
