## alpha2.0（2026-08-23 安装，versionCode 200 / versionName alpha2.0）——官方 Material 3 组件化重构
- **需求（用户）**：UI 全面迁移到官方 Material 3 组件（material 1.12.0 + androidx 全套），符合官方规范；版本号 alpha1.40 → alpha2.0（vc 140 → 200）；务必不破坏功能（Hook/GAIA/蓝牙链路零改动）
- **新 M3 构建链（tools/build_m3.py，完全参数化）**：aapt2（qemu-x86_64 + Debian glibc 前缀封装 tools/qemu/qemu-run.sh 跑 x86-64 SDK 工具，环境为 aarch64）compile/link 资源 → gen_lib_rtxt 生成库 R → javac（42 jars classpath）→ D8（r8-8.7.18）→ 4 字节对齐打包 → apksigner v3；依赖 25+（Google Maven 双源）
- **组件迁移（只动 UI 容器，业务逻辑零改动）**：
  - MainActivity：状态卡片/权限弹窗/图标弹窗 → MaterialCardView（内嵌 LinearLayout 保留垂直堆叠，FrameLayout 不自动堆叠）；按钮 → MaterialButton（圆角 28 药丸）
  - PopupOverlay：连接/断开弹窗卡片 → MaterialCardView + 内嵌 cv 容器（保留 gravity/动画）；okBtn 等内部元素不动
  - M3Ui：topBar（MaterialToolbar）/navRow/switchRow（MaterialCardView）/filledButton 组件封装（SettingsActivity/PermissionActivity 复用）
  - 主题：Theme.FxxkMoondrop = Theme.Material3.DayNight.NoActionBar，动态取色照旧
- **关键修复（构建链，否则运行时崩溃）**：
  - collection/annotation 1.7.1 为 KMP 元数据 jar（0 类）→ 补 collection-jvm-1.4.0（143 类，含 LruCache/ArrayMap）+ annotation-jvm-1.7.1（74 类）
  - material 传递依赖 androidx.interpolator 缺失 → 补 interpolator-1.0.0
  - gen_lib_rtxt 漏生成 appcompat-resources/emoji2-views 的 R → build_m3.py 默认映射补 2 条
  - **styleable 索引错位**：aapt2 生成的 styleable 数组按 attr ID 排序而 AAR R.txt 是声明序 → 库 R 索引常量按 ID 匹配重排（gen_lib_rtxt.py 修复），否则 setTitle 等 TextAppearance 读取错位崩 NumberFormatException
- **实测验证（OnePlus 设备）**：主界面（MaterialCardView 状态卡+按钮全渲染无重叠）✓ 设置页（Toolbar+导航卡+开关行）✓ 模拟连接 FastPair 弹窗（名称/电量 86/72/ANC 三模式）✓ 进程连续启动无崩溃 ✓ 全程 FATAL=0
- **版本**：alpha1.40→alpha2.0，vc 140→200；主空间安装（铁律）；根目录 alpha200-signed.apk（5.15MB，md5 985fdc4…）；未动系统设置（铁律）
- **备份**：/workspace/backup/alpha140_to_alpha200/（src_final/、两文件 diff、stage1 基线 apk、补丁前 MainActivity/PopupOverlay）

## alpha1.40（2026-08-23，versionCode 140 / versionName alpha1.40）——修复：断连后降噪面板不隐藏 / 模拟区仍隐藏
- **Bug（用户反馈）**：耳机断开连接后，设置页/主界面降噪控制面板仍然显示，模拟按键（模拟测试区）仍然隐藏
- **根因**：`HeadsetGate` 将已连接 MAC 缓存于内存（`sLastMac`）+ SP（`last_connected_mac`），但**断连后从不清除**；主线程 `getConnectedMac` 在 quickScan 未发现时直接返回旧缓存 → 主界面 `realConnected` 永真 → `updateAncStatus` 中降噪面板 `blockVisible` 恒为 true、`simSection` 恒为 GONE
- **修复（三处清缓存，覆盖全部断连通道）**：
  - `HeadsetGate`：新增 `clearConnectedMac(Context)`（清内存 + SP）
  - `GaiaBleClient`：GATT `STATE_DISCONNECTED`（正常/异常）回调前清缓存（先清后回调，保证 MainActivity.onDisconnected -> updateAncStatus 时缓存已失效）
  - `HeadsetReceiver`：ACL / A2DP / HFP 系统层断连（moondrop 设备）清缓存（覆盖 GATT 未断但音频已断场景）
- **恢复路径不变**：重连成功 → quickScan/fullScan 重新 remember + `macUpdReceiver`(MAC_UPDATED) 或 gaiaUiCallback.onConnected 驱动 UI 恢复
- **构建**：build_all.py → build2/alpha140-signed.apk；未动系统设置（铁律）；主空间安装
## alpha1.39（2026-08-23 01:07 安装，versionCode 139 / versionName alpha1.39）——推荐作用域收窄 + 设置页提示清理
- **需求（用户）**：① 不需要对官方应用（Moondrop App）和蓝牙进行 hook → 推荐作用域删掉这两包；② 设置界面"LSPosed 推荐作用域"那行提示多余，直接删除；③ （用户澄清）只改推荐作用域，hook 代码原样保留
- **推荐作用域收窄（三处一致）**：
  - manifest xposedscope 字符串（type=3）：`com.google.android.gms;com.android.settings`（四包→两包；字符串池原地改短+空洞填充，零偏移风险）
  - `META-INF/xposed/scope.list` + `ascope.list`：仅 GMS 与 settings 两行
  - LSPosed Manager 实测：推荐应用标签只剩 Google Play 服务 + 设置，蓝牙/官方 App 不再列入 ✓
- **设置页清理**：删除 `SettingsActivity` 中 "LSPosed 推荐作用域：Google Play 服务、系统设置、Moondrop 官方 App、蓝牙（四包）" 提示行（含 scopeHint TextView 创建/设置/添加共 8 行）
- **hook 代码未动**（用户澄清后确认）：XposedEntry 保留 hookMoondrop/hookBluetooth/GAIA 全套；MoondropBooter 保留（此前误删已从 alpha1.38 备份完整恢复并校验 401/51 行）
- **版本**：alpha1.38→alpha1.39，vc 138→139；主空间安装（铁律）；根目录 alpha139-signed.apk 已更新（旧 alpha138 删除，md5 8d7f607f...）；未动系统设置（铁律）

## alpha1.38（2026-08-23 01:03 安装，versionCode 138 / versionName alpha1.38）——xposedscope 修复为官方字符串格式（推荐作用域恢复显示）
- **需求（用户）**：① LSPosed Manager 无推荐作用域；② 用户查证：推荐作用域用的是**字符串格式** xposedscope，资源数组引用 Manager 不认；③ 之前错误的代码（资源引用方案）必须移除，不留包里
- **根因**：alpha1.36 误把 xposedscope 从字符串改为 `android:value="@array/xposedscope"`（编译为 type=1 reference，值 0x7F030000）。官方 Wiki（LSPosed/LSPosed/wiki/Module-Scope）只有两种形式：`android:resource="@array/..."` 或硬编码 `android:value="com.a;com.b"`（**分号分隔字符串**）。"value 属性资源引用"两者都不是，Manager 不识别 → 推荐作用域不显示
- **修复（等长原地补丁，零偏移风险）**：模板字符串池中已有逗号四包串（idx=60），原地 `,`→`;`（UTF-16 等长 1:1）；xposedscope 的 value 属性 type 1→3（string），data→60。结果：`com.google.android.gms;com.android.settings;com.moondroplab.moondrop.moondrop_app;com.android.bluetooth`
- **错误代码移除**：`tools/patch_manifest_scope.py`（改资源引用）、`tools/patch_arsc_scope.py`（arsc 注入 GMS array）→ 归档 /workspace/archive/legacy_patch_scripts/；resources.arsc 换回干净版（build136 原始 1484B，无 xposed array）；全项目 grep 确认无 7F030000/xposed_scope 残留（仅注释提及）；zip_apk.py / build_all.py 构建链从未引用错误脚本（它们只编 dex+版本补丁+打包）
- **工具保留**：`tools/patch_scope_string.py`（重写为等长替换版，含回读校验）——官方字符串格式的正确补丁工具
- **实测验证（OnePlus PKX110, JingMatrix LSPosed）**：APK 层 xposedscope type=3 字符串 ✓ 无 0x7F030000 ✓ arsc 1484B 无 array ✓ scope.list/ascope.list 四包 ✓；**LSPosed Manager 模块页四包全部显示"推荐应用"标签** ✓
- **版本**：alpha1.37→alpha1.38，vc 137→138；主空间安装（铁律）；根目录安装包已更新 alpha138-signed.apk（旧 alpha137 已删除）；未动系统设置（铁律）
- **备份**：build2/apk_scope_ref_backup（错误资源引用版模板，供比对）；archive/legacy_patch_scripts/（错误脚本）

## alpha1.37（2026-08-23 00:37 安装，versionCode 137 / versionName alpha1.37）——日志 ZIP 化 + Material 弹窗 + AI 提示清理
- **需求（用户）**：① 日志界面删除"发送给 AI 助手"提示；② 日志隐私警告弹窗符合 Material Experience 和深色模式；③ 日志打包为 ZIP（含多条日志），保存到手机根目录方便找；④ 确认错误的 scope 注入代码已彻底移除
- **AI 提示清理**：设置页日志行描述去掉"发送给 AI 分析"→"导出 ZIP（含隐私声明）"；完成弹窗去掉"发送给 AI 助手"→"自行分享给开发者进行设备适配分析"。全源码无 "AI 分析/AI 助手" 残留
- **Material 弹窗**：新增 `showMaterialConfirm()`（pal 色板：pal.card 圆角 28 / pal.onSurface 标题 / pal.onVariant 正文 / pal.primary 主按钮、pal.onVariant 取消按钮，深浅色自适应）；日志隐私声明弹窗改用该组件（替换原生 android.app.AlertDialog.Builder）
- **日志 ZIP 化（LogCollector 重写）**：
  - 5 条分类日志：01_系统信息 / 02_应用与设置（含 cfg 设置项全量）/ 03_蓝牙信息 / 04_运行环境 / 05_logcat(tail 600)
  - `java.util.zip.ZipOutputStream` 打包为 `FxxkMoondrop_logs_yyyyMMdd_HHmmss.zip`（无额外依赖）
  - 保存优先级：① Root `su cp` 复制到 `/storage/emulated/0/`（手机根目录）→ ② 直接写公共根（旧系统）→ ③ 回退应用外部私有目录 files/logs/
  - 实测：OnePlus PKX110 生成 `/storage/emulated/0/FxxkMoondrop_logs_20260823_003633.zip`（5 文件 68KB，logcat 67KB）
- **错误代码确认（用户点名核实）**：旧字符串注入脚本 `inject_xposed.py`、`patch_manifest_xposed.py` 已在 /workspace/archive/legacy_patch_scripts/ 归档，构建链无引用；`tools/patch_manifest_scope.py`（value→@array 资源引用，正确方案）保留；alpha137 APK 复验：manifest xposedscope ✓、resources.arsc 四包 GMS/settings/moondrop/bluetooth ✓、META-INF/xposed/scope.list+ascope.list ✓、xposed_init→com.fxxkmoondrop.secret.XposedEntry ✓
- **版本**：alpha1.36→alpha1.37，vc 136→137；主空间安装（铁律）；未动系统深色/自动旋转（铁律）
- **备份**：/workspace/backup/alpha136_to_137/（src + diff 355 行 + APK + 文档）

## alpha1.36（2026-08-23 0:xx 安装，versionCode 136 / versionName alpha1.36）——推荐作用域修正 + 电量可读性 + 顶部铺满 + 日志抓取
- **需求（用户）**：① LSPosed 软件内正确显示推荐作用域（之前加错地方/格式不对）；② Google 弹窗左右电量在浅色模式下文字不可读；③ 设置页/权限检测页上边未铺满（空白区域）；④ 设置页新增日志抓取功能（含隐私声明，供其他设备适配分析）；⑤ 移除错误的 scope 注入代码，不与 FastPairHook 测试模块混淆
- **推荐作用域三重修正（核心）**：
  - 根因：模板 arrays 资源缺 `com.google.android.gms` + meta-data 写成字符串 value（LSPosed 只认 array 资源引用 `getStringArray`）
  - `tools/patch_arsc_scope.py`：resources.arsc 全局字符串池补 GMS，xposedscope 数组 3→4 项（settings/moondrop/bluetooth/gms）
  - `tools/patch_manifest_scope.py`：xposedscope 的 value 属性改 REFERENCE → 0x7F030000（二元验证通过）
  - 新增 `META-INF/xposed/scope.list` + `ascope.list`（现代推荐方式，JingMatrix fork 双保险）；`tools/zip_apk.py` 打包清单同步
  - 旧字符串注入脚本 `build2/inject_xposed.py`、`tools/patch_manifest_xposed.py` 归档至 `/workspace/archive/legacy_patch_scripts/`（废弃）
- **电量文字**：FastPairHookEntry.injectBatteryOverlay 改为随 uiMode 自适应（浅色 0xFF1C1B1F / 深色 0xFFFFFFFF）
- **顶部铺满**：SettingsActivity / PermissionActivity 窗口背景=pal.surface + 状态栏/导航栏透明（无空白带，深浅色自适应）
- **日志抓取（新增 LogCollector.java）**：设备/系统/屏幕/语言/Root/模块/GMS 与 Moondrop 版本/蓝牙配对/logcat 尾部 → files/Download/logs/fxxk_log_*.txt；设置页「📋 日志抓取（设备适配）」入口 + 隐私声明弹窗 → 后台收集 → 显示路径（实测：OnePlus PKX110 / Android 16 / Root+模块已激活 / GMS 26.30.32 / 配对 Moondrop Golden Ages 2 / 648 行日志生成成功）
- **版本**：alpha1.35→alpha1.36，vc 135→136；主空间安装（铁律）
- **验证**：APK 内 manifest xposedscope type=REFERENCE 0x7F030000 ✓；scope.list 入包 ✓；设置页/权限页顶部铺满 + 日志流程全链路实测 ✓；返回主界面流畅 ✓
- **已知点**：LSPosed Manager 里推荐作用域的显示需 lspd daemon 重新解析 APK（重启 zygote/手机后确认；未获用户同意前未重启）
- **备份**：/workspace/backup/alpha135_to_136/（src + diff 722 行 + APK + 文档）

## alpha1.35（2026-08-22 23:3x 安装，versionCode 135 / versionName alpha1.35）——设置/权限页整页化 + 移除打扰 toast + 作用域确认
- **需求（用户）**：① 设置&关于界面不要再是浮窗形式 → 整页二级界面；② 权限检测页面同样整页；③ 移除"未检测到 Moondrop 耳机"toast（太烦人）；④ 确认/补充 LSPosed 推荐作用域；⑤ 符合 Material Experience 风格并兼容浅深色模式
- **新增 ThemeUtil.java**：共享 Material You 取色工具（isDark / dyn / Palette 色板），MainActivity 取色逻辑不动，两个新页面复用同一色板
- **新增 SettingsActivity.java（整页二级界面，替代 showAbout Dialog）**
  - M3 Top App Bar（← 返回 + "设置"标题）、关于卡片（图标/名称/版本动态读取/作者 GitHub/协助者）
  - 「检查权限」入口行 → PermissionActivity；LSPosed 推荐作用域提示行（四包）
  - 开关全部迁移：Root 强力保活（含权限风险警告 Dialog）、后台隐藏、启动自动监听、Google 弹窗（Fast Pair）、弹窗图标（选图/恢复默认，onActivityResult 处理）
  - 状态栏/导航栏随色板（深浅模式图标自适应）
- **新增 PermissionActivity.java（整页二级界面，替代 showPermissionDialog）**
  - 状态头（✅ 全部就绪 / ⚠️ 发现 N 项缺失）、7 项权限列表（蓝牙/通知/悬浮窗/电池白名单/Root/FastPairHook/GAIA 直连）
  - 缺失项可点击修复（运行时权限请求、悬浮窗/电池设置跳转）；「重新检查」按钮；onResume 自动重查；推荐作用域提示行
- **MainActivity**：showAbout() / checkAndShowPermissions() 改为 `startActivity` 跳转（旧 Dialog 代码移除，-14.4KB）；刷新状态不再弹"未检测到 Moondrop 耳机"toast（alpha1.35 注释标记）
- **Manifest**：tools/patch_manifest_activities.py 向二进制 AXML 模板注入 `.SettingsActivity` / `.PermissionActivity`（exported=false；字符串池扩展 + resmap 同步，chunks 校验通过）
- **LSPosed 推荐作用域（已确认）**：manifest xposedscope = 四包 `com.google.android.gms, com.android.settings, com.moondroplab.moondrop.moondrop_app, com.android.bluetooth`（build2/inject_xposed.py 注入）；UI 与 README 同步展示
- **构建**：javac --release 8 → d8 --min-api 26 → patch_version（alpha1.34→alpha1.35，vc 134→135）→ zip 对齐 → apksigner v2+v3；产物 build2/alpha135-signed.apk
- **验证（实机）**：设置页整页渲染（返回栏/关于卡/四包作用域/全部开关）✓；权限页整页渲染（7 项全 ✔）✓；`cmd uimode night yes` 深色切换后 UI 正常（深色 surface/白色文字/开关正常）✓，已恢复浅色；toast 移除源码确认 ✓；安装于主空间（铁律）
- **备份**：/workspace/backup/alpha134_to_135/（src + diff）

## alpha1.34（2026-08-22 已安装，versionCode 134 / versionName alpha1.34）——备用自扫模式 + 环境探测 + 自检弹窗修复
- **需求（用户）**：① 应用内置 BLE 自扫仅在【未检测到 Root 且未检测到 LSPosed 模块】的纯净环境允许使用；② 权限自检弹窗缺少 LSPosed 模块检测；③ GAIA 检测显示状态不正确；④ 版本号 +1
- **新增 EnvProbe.java（alpha1.34）**：运行环境探测
  - `isRooted()`：动态遍历 PATH + 系统惯例位置查找 su / magisk / magisk64（只探测文件存在，不执行 su，零副作用；结果进程内缓存）
  - `isFastPairHookActive(ctx)`：向 GMS 发 `FASTPAIR_PING` 广播，FastPairHookEntry 收到后回 `FASTPAIR_PONG`（超时 1.8s，子线程阻塞；结果缓存）
  - `isCleanEnv(ctx)`：无 Root 且无模块
- **FastPairHookEntry（hook 包）**：新增 PING/PONG receiver（GMS 进程注册，`ping -> pong` 日志）
- **GaiaBleClient.init()**：无缓存时按环境决策——Root 或模块存在 → 沿用 `requestRemoteScan("boot-no-cache")`（GMS 桥接）；纯净环境 → `handler.postDelayed(startGenericScan, 800)` 备份自扫（不再依赖 GMS）
- **PermissionChecker**：GAIA 直连项改为动态（模块激活→桥接正常 / 纯净→备用自扫 / 有 Root 无模块→✘ 提示启用模块）；新增「Root / 特权环境」显示项与「FastPairHook 模块（LSPosed）」检测项；删除恒 true 的 isXposedActive 与旧 pingMoondrop / isProcessRunning 死代码
- **构建**：javac --release 8 → d8 --min-api 26 → 版本补丁（alpha1.33→alpha1.34 等长，vc 133→134）→ zip 对齐 → apksigner v2+v3；产物 build2/alpha134-signed.apk；签名证书与 alpha133 一致
- **端到端调优（复测驱动）**：
  - PING/PONG 链路实测：GMS 4 子进程均注册 `ping receiver` 并回 `ping -> pong`；应用侧 PONG 投递受冷启动主线程繁忙影响延迟 3.2s → 探测延迟 3.5s 启动（避开繁忙期）+ 超时 4s + GMS 回包加 `FLAG_RECEIVER_FOREGROUND`
  - 注册/发送不依赖主线程 post（原实现主线程繁忙时错过超时窗口，改为调用线程直接注册+发送）
  - 权限自检弹窗复测：Root 项、FastPairHook 项（已激活）、GAIA 桥接状态均正确显示；设置页「检查权限」副标题同步更新
- **备份**：backup/alpha133_to_134/（src + manifest 模板）


## alpha1.14fix6（2026-08-22 19:0x 安装，versionCode 119 / versionName alpha1.14fix6）——弹窗耳机图标放大 1.4x（用户反馈）
- **需求**：用户反馈 Google 弹窗里耳机图片尺寸偏小，要求放大
- **测量（实测截图）**：fix5 图标显示约 147×140px → 偏小
- **改动（仅 FastPairHookEntry.injectIconIntoTree，实际生效路径；logcat 确认本次弹窗走 tree 注入，dthi.O/q 未命中）**：
  - 注入 bitmap 后：`iv.setScaleType(FIT_CENTER)` + LayoutParams 宽高 >0 时 ×1.4 + setLayoutParams + requestLayout（不动 wrap/match 尺寸）
  - 逻辑包 try-catch，失败仅打日志不中断；其余注入路径（dthi.O/q、injectIconOverlay、injectBatteryOverlay、injectModeButtons）零改动
- **构建**：javac --release 8 → d8 --min-api 26（dex30）→ zip14fix6.py（4 字节对齐 ALL_ALIGNED）→ apksigner v2+v3；产物 build2/alpha114fix6-signed.apk（287650B）
- **版本**：versionName alpha1.14fix5→alpha1.14fix6（等长原位替换，不动字符串池）、versionCode 118→119；dumpsys 确认
- **验证**：GMS force-stop 后重触发弹窗，logcat 无 resize fail；截图对比图标 147×140 → 209×169（1.42x），模式按钮/确定按钮布局正常
- **备份**：backup/fix6_20260822/（源 bak + 旧 APK + fix6-icon-resize.diff）

## alpha1.33（2026-08-22 22:24 安装，versionCode 133 / versionName alpha1.33）——LE 扫描链路三修复（端到端复测通过）
- **背景**：alpha1.32 端到端验证发现"应用 REQ → GMS 扫描"链路全程 `LE scan window done (no hit)`，找不到耳机 LE 广播地址
- **根因 1（主因）**：GA2 的 LE 身份地址 `24:01:30:B5:9C:39` 与 BR/EDR 地址 `24:01:30:B5:C9:39` **第 5 字节即不同**（9C vs C9），代码用 `substring(0,14)`（5 字节）做前缀匹配 → 永不命中。改为 `substring(0,12)`（4 字节），共 3 处（GaiaBleClient 380/424/430 行区域 + FastPairHookEntry 980/987）
- **根因 2**：扫描回调无全量日志，无法判断耳机广播是否活跃 → 新增 `LE scan result: addr name rssi` 日志
- **根因 3**：4 个 GMS 子进程（persistent/gms/unstable/ui）同时收到 REQ 并发扫描互相干扰 → 新增**跨进程文件锁**（GMS filesDir `.fxxk_le_scan_lock`，`createNewFile()` 原子建锁，15s 超时兜底；acquire 移到所有前置检查之后避免提前 return 泄漏；hit/fail/done/catch 四处释放）
- **实测（22:27 重启加载新 dex 后）**：3 子进程 `skip (locked by sibling process)`，仅 persistent 扫描；0.1s 内扫到 `24:01:30:B5:9C:39 name=Moondrop Golden Ages 2 rssi=-49` → `LE scan hit` → `LE addr pushed` → 应用 `LE addr from FastPairHook` → **GAIA locked** → 双耳电量 90%/90% → `gaia_le_addr.txt` 写回（自愈闭环）
- **构建**：javac --release 8 → d8 --min-api 26 → zip（4 字节对齐）→ apksigner v2+v3；产物 build2/alpha133-signed.apk（经 pm install -r 安装）
- **备份**：backup/alpha133_20260822/（源码 + APK + _p132d/_p132e.py）+ backup/alpha132-to-alpha133.diff（138 行）

## alpha1.32（2026-08-22 21:47 安装，versionCode 132 / versionName alpha1.32）——FastPair 全链路动态发现合入（零脚本、零硬编码）
- **FastPairHook（LSPosed 注入 GMS，多进程激活）**：
  - 应用发 `ACTION_REQ_LE_SCAN` 广播 → GMS 侧 `handleLeScanRequest`：BLE 扫描 10s（ColorOS 放行 GMS 扫描、第三方应用被拦，故借道 GMS）→ `DeviceMatcher.isMoondrop` 名称匹配 / 前缀匹配 bonded 设备 → 命中后 `ACTION_LE_ADDR_FOUND` 推送地址给应用（30s 节流）
  - `ACL_CONNECTED` 事件顺带推送设备真实地址（LE 连接触发时，事件通道覆盖"耳机在连接中但扫描不到"的场景）
- **应用侧（GaiaBleClient）**：接收推送 → 正则校验地址格式 → 保存 + 连接；GAIA 连接成功自学习写回 `files/gaia_le_addr.txt`（应用原生 978 行机制）；无缓存/失联时自动 REQ 重新发现 → **地址变化永远自愈**
- **零硬编码**：源代码不含任何耳机 MAC / 设备名常量；匹配逻辑全部运行时动态（DeviceMatcher 名称关键词 + bonded 前缀）
- **实测**：清空全部缓存启动 → SP 兜底 → 连接 → `LE addr file saved` → 下一次启动 `list=[gaia_le_addr.txt] len=18` 从文件秒连
- **备份**：backup/alpha132_20260822/（源码 + APK + e2e 数据）+ backup/alpha131-to-alpha132.diff（189 行，GaiaBleClient 1.31→1.32）

## alpha2.9（vc 224，2026-08-23，基于 alpha2.8 修复）
- 修复：模拟连接弹窗自动恢复后，主页电量行残留显示（86%/72% 或 --%）
- 根因：恢复只改了模拟态/清电量数据，但 UI 无刷新触发——updateStatus()（onResume）只刷英雄卡，stateReceiver 只刷降噪
- 修复：①MainActivity.updateStatus() 末尾加 updateStatusPanel()（onResume 同步面板）②HeadsetReceiver 恢复模拟态后发 STATE_UPDATED 广播（主页在前台立即刷新）
- 验证：模拟连接→弹窗关闭→主页电量行隐藏、GAIA/耳机连接显示未连接
