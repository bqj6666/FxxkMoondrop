## 3.2.0 (320)
> 空间音频与头部跟踪改用系统蓝牙详情页里官方自带的那两行开关（不再自绘），
> 追踪档位不再卡在「关闭」；降噪按钮顺序在主界面、通知栏与弹窗三处统一；
> 新增首次启动的使用引导（设置页最底部可重看）。

### 新增：使用引导（7 页）
- **首次启动自动展示**：横滑分页，每页一个功能分区 —— 关于、权限申请、连接管理、系统集成、
  耳机控制、适配与诊断、欢迎使用。看完（或跳过）后不再自动弹出，设置页最底部随时可重看。
- **第一页只有「跳过」能提前退出**：不设返回箭头，系统返回键只回退到上一页，首页不退出，
  避免误触直接跳过整段引导；末页「开始使用」正常完成并回到主界面。
- **权限页就地授权**：与「权限检测」页同一套判定，点条目直接前往授权；已就绪项以绿色圆形勾标记。
- **各页开关与设置页同源**：写同一份偏好、走同一套副作用（起停监听服务、刷新通知），改完立即生效，
  不存在第二套状态。
- **末页「欢迎使用」**：汇总权限、FastPairHook 模块与引导完成状态。
- 措辞为中性书面语，不使用表情符号。

### 调整：权限分为必要与可选两类
- 「权限检测」页与引导的权限页都按**必要 / 可选**分组呈现：
  必要 —— 蓝牙权限、通知权限、GAIA 直连（缺失时对应核心功能完全不可用）；
  可选 —— 电池优化白名单、Root 权限、FastPairHook 模块（只影响后台留存与系统集成）。
- **结论只看必要项**：权限检测页标题、引导末页「欢迎使用」的就绪状态均以必要项为准，
  可选项缺失不再被当成「权限没配好」；缺失时只在副标题里如实提示数量。
- 引导权限页与末页的就绪标记改为绿色圆形背景勾，直径由 14dp 放大到 30dp；
  同时修掉「徽标被行容器压成图标本身大小」的问题（尺寸原先落在会被覆盖的那一层）。

### 调整：空间音频 / 头部跟踪改用官方那两行
- **现象与背景**：系统自带的 `BluetoothDetailsSpatialAudioController` 在本机恒判不可用
  （`getImmersiveAudioLevel()==0`），于是把自己刚建好的两行又移除，详情页只剩我们自绘的控件。
- **做法**：放开官方的可用性判定，让官方自己渲染与维护那两行；勾选态与点击接到耳机端（GAIA）——
  空间音频开关对应我们的「空间音频开 / 关」，头部跟踪开关对应三档追踪里的「全方位 / 30°」。
  两行仅在 GAIA 就绪时出现，耳机断开或未就绪时不显示、也不可点。
- **删除自绘控件**：详情页面板不再自建空间音频行与追踪三档，卡片只剩抗风、增益、指示灯。
- **只对 Moondrop 耳机生效**：`Spatializer` 相关 hook 全部按设备地址判定，
  非本模块耳机（含系统原生就支持空间音频的他牌耳机）一律原样交还官方，不会把人家本可用的开关弄没。

### 修复：打开空间音频后追踪档位停在「关闭」
- **现象**：打开空间音频，耳机端回报的追踪档位是 0（关闭），界面显示为「关闭追踪」，
  与「空间音频已开」矛盾。
- **做法**：确立不变量 —— 空间音频开着时不会是「关闭追踪」。读到 0 档即自动补为 30°；
  但用户在软件内明确把追踪切到过「关闭」时尊重其选择，不再自动补档。
  只有「本来在 30° / 全方位、用户自己切到关闭」才算手动关，本来就关着再点一次不算表态。

### 调整：降噪按钮顺序三处统一
- 主界面、通知栏与 Google 弹窗的档位按钮统一为「降噪 / 关闭 / 通透 / 自适应 / 直播 / 抗风」，
  与官方 GFPS Hearable Controls 通知对齐；顺序表收敛为唯一出处，抗风与自适应的可用性门控不变。

### 文档
- README 补充设备详情页截图与赞赏入口。
- 文档全量同步到 3.2.0：README / README.en 版本号与发布徽章、功能列表（官方空间音频两行、使用引导、必要/可选权限、保活默认常开、日志六类）、版本历史补 3.0.1~3.2.0；ARCHITECTURE 新增「空间音频 / 头部跟踪改用官方两行」章节并补 OnboardingActivity；DEVELOPMENT 的发版同步项由四处改为五处；模块仓 README 同步更新。

## 3.1.0 (310)
> 蓝牙设备详情页大改：把官方自己的行放出来（HD 音频 / 通话 / 媒体音频 等），
> 我们的面板与官方降噪切片各归其位；面板跟着连接状态显隐 —— 未连接时整块收起，
> 连上但 GAIA 还没就绪时用官方加载行在原位占位。

### 修复：详情页「相关工具」下方一大片空白 / 面板被挤下去或消失
- **现象**：进入 Moondrop 设备详情页往下翻，官方「相关工具」那一栏出现一大片空白，把下方的
  Moondrop 面板整体往下挤，面板有时还直接消失；翻页过程中偶发重复的一整行内容。
- **根因**（logcat 实测，逐行打了 RecyclerView 子行的高度/适配器位置/所属偏好）：
  1. 详情页用行视图承载面板，官方重排（`updatePreferenceOrder`）会把行视图回收给别的条目，
     我们挂上去的面板跟着残留 —— 同一个面板会在其它位置（如官方「实时字幕」那一行）再画一份，
     那一行高度被撑成面板高度（812px），残留行之间还出现 1445px 的空档。
  2. 适配器/布局管理器重排没落地时，列表会保留这些残留行，表现为「一大片空白」。
- **做法**：
  1. 给所有官方条目补一条清理：绑定到**非本模块 key** 的条目时，先把残留的面板视图从它的
     itemView 上摘掉 —— 面板只可能出现在它自己那一行。
  2. 官方重排 + 显形跑完后，只让列表 **重新布局一次**（`requestLayout`，立即 + 400ms + 1200ms），
     让残留行被回收、行距恢复。
     **注意**：不能用 `notifyDataSetChanged` —— 实测它会把官方「实时字幕」那类自绘条目重新膨胀成
     一张没有文字的空白卡片（控制器不会重跑），那正是空白的一大来源。
- **实测结果**：残留行 0、面板行 `found=1`、官方「实时字幕」正常渲染，重复行与 1445px 空档消失。

### 调整：详情页面板（抗风开关 / 边距 / 位置）与官方降噪切片的状态
- **抗风改成开关**：原来那个小药丸按钮换成与「空间音频」完全同一行的开关（共用同一个开关行构件，
  拿的也是设置 App 自己的开关控件），样式不会再各自漂移；显隐仍以主界面同一套判据为前提
  （设备实际支持该档 + 用户偏好），未就绪时置灰。降噪档位切换仍交给官方切片，我们只留这一个快捷键。
- **抗风开关只在「降噪 / 抗风」档出现**：抗风是降噪的加强档，所以只有处于降噪档时才提供这个开关 ——
  打开即切到抗风档，关闭即回到降噪档；若直接切到通透或关闭，开关自动弹回关闭，并连同整行一起隐藏
  （行后的间距随整块一起收掉，不会残留一段空白）。
- **设置项跟着改名说明**：「显示抗风噪按钮」的描述改为讲清抗风 = 降噪的加强档 —— 详情页快捷开关只在
  「降噪／抗风」档出现，切到通透或关闭会自动收起；关掉该项后弹窗、主界面与详情页都不再提供抗风。
  「蓝牙详情页面板」的描述补上「未连接时整块收起、未就绪时用官方加载行原位占位」。
- **边距**：面板左侧不再自己加留白（偏好行本身已带官方那套左右边距），卡片内再收 8dp，
  「抗风 / 空间音频 / 追踪模式 / 增益」与官方切片内容同一条竖线，元素不贴边。
- **位置固定**：官方那次重排会把「不在它名单里的项」收进它自己生成的分类，面板位置因此会漂
  （有时紧跟官方操作按钮、有时沉到底部）。现在在官方重排跑完之后，把我们的几行摘成屏幕根的直接子项，
  按官方「操作按钮」那一行的 order 依次给号（PreferenceGroup 本来就按 order 决定插入位置），
  顺序固定为：**官方操作按钮 → 官方降噪切片 → 官方加载行 → 我们的面板 → HD 音频等官方配置行**。
- **官方降噪切片的状态**：切片来自设备元数据（机型不支持就既不显示切片也不显示占位，不留一条永远转的进度条）。
  本机还没就绪（GAIA 未完成服务发现，命令发不出去）时，用官方自己的加载行（`loading_pref`）在切片原位占位，
  切片收起；就绪后切片出来、加载行收起。状态由模块进程跨进程推送，连上后无需重开页面。
- **顺带修掉一个空白**：官方行自带的子视图以前被我们删掉再放面板，行视图被适配器回收给别的条目后
  （实测「实时字幕」），对方重新绑定时找不到自己的控件，整行就变成空白卡片。现在只「藏」不「删」，
  别的条目绑到这一行时先摘掉残留面板、再把被藏的孩子放出来。
### 新增：设备详情页放出官方自己的行（HD 音频 / 通话 / 媒体音频 等）
- **背景**：官方 `BluetoothDetailsConfigurableFragment.updatePreferenceOrder` 会把「不在 displayOrder 里」的项
  整体挪进 `invisible_profile_category`（该分类自身 visible=false），于是官方自己的行全被藏掉；
  ROM 还额外通过 `BluetoothFeatureProvider.getInvisibleProfilePreferenceKeys` 把
  通话音频 / 媒体音频 / HD 音频 列进隐藏名单（官方日志 `Invisible profiles: [...]`）。
- **做法**：只对 Moondrop 设备 (1) 不去填那两个官方隐藏名单；(2) 在官方那次重排跑完、主线程下一次消息里，
  把 `invisible_profile_category` 及其内部「有可见子项的」分组显示出来。不搬动子项、不指定具体条目，
  每行出不出来仍由官方各自的 controller 决定（「HD 音频」因此仍然只在连接且支持可选编解码器时出现）。
- **面板重复行**：上述改动会让该页适配器重排后偶尔给我们的面板条目留下残留行（同一面板画两遍）。
  绑定后按父容器把多余的兄弟行折叠成 0 高度，只保留当前正在绑定的那一行。
- **「耳机控制」切片（`bt_extra_control`）已放出**：本 ROM 没有任何代码给它设 URI。
  设备元数据里的 `HEARABLE_CONTROL_SLICE_WITH_WIDTH` 的 `view_width` 是**空的**，GMS 对空宽度直接返回空切片；
  用本机屏幕宽度补齐后 GMS 正常返回内容（实测 `items=3`），再用官方 `SlicePreferenceController`
  按官方流程（setSliceUri -> displayPreference -> onStart）挂上即可。地址仍取自设备元数据，不写死。
- **面板重复行**：上面的改动会让该页适配器重排后给我们的面板条目留下残留行（同一面板画两遍，
  表现为元素挤在一起）。改为：绑定后就地扫描根视图里所有带面板标记的视图，只保留当前这一行、
  其余整行 GONE（只压高度挡不住内容溢出）。实测 `found=2 hidden=1` → 单份。
- **加载占位**：官方 `loading_pref` 是配置数据未到位时的滚动进度条，随隐藏分类一并放出来后很扎眼，
  显式保持隐藏。

### 修复：设备详情页凭空多出「自适应」档
- **问题**：只有 0..3 四档的设备（如 Golden Ages 2，走 AudioCuration 路径）在能力探测
  尚未成功时（未连接 / RFCOMM 失败），控制面板会多出一个「自适应」按钮 —— 点了没反应。
- **根因**：可用档位未知（`modes` 为空）时，可见性规则 `!knownModes || modes.contains(i)`
  对**所有**列都为真，自适应(4) 也被兜底显示；而 `buildAncCard` 里那行「初始 GONE」
  紧接着就被首次 `refreshAncCard` 点亮，实现与注释相左。
- **修复**：可见性规则收敛到纯函数 `AncProfileLib.ancColumnVisible(uiMode, knownUiModes, showWind)` ——
  能力未知时只兜底通用基础四档（新增 `BASIC_UI_MODES`），自适应(4) 必须有能力证据才出现。
  同时 `supportedUiModes()` 的 ANC_V2 未收录型号兜底由 `0..5` 收窄为 `BASIC_UI_MODES`，
  不再向官方音量面板 / 通知 / 弹窗宣告「自适应 / 直播」这两个拿不到证据的档位。
- **影响面**：布丁等有档案的 ANC_V2 设备行为不变（仍宣告 `[关,降,透,抗,自适应]`）；
  主界面降噪卡本就只建 4 档，通知与弹窗早有能力判据，本次只补齐设备详情页这一处漏网。

### 修复：未连接时不再留下占位与面板（3.1.0 收口）
- **现象**：耳机断开之后，官方那条加载行还一直在原处占位；面板也赖着不走。
- **根因**：显隐只看「GAIA 是否就绪」，没看「耳机是否连着」——断开后 GAIA 必然不就绪，
  于是永远落进「占位」那一档。
- **做法**：三行的显隐改成看三个事实：设备元数据里**有没有控制切片地址**、耳机**是否已连接**、
  **GAIA 是否就绪**。未连接 -> 三行全隐藏；已连接但未就绪 -> 官方加载行在原位占位、我们的面板
  出来（控件置灰）；就绪 -> 切片出来、加载行收起。规则本身抽成纯逻辑 `DetailRows`，7 个单测覆盖
  三档与「没有切片地址的机型」「上报顺序抖动」等边界。

### 修复：蓝牙被关掉后不再报「已连接」
- **现象**：适配器关掉后上层收不到断连回调（实测 GATT 被静默丢弃，只剩写命令失败），
  模块一直认为连着 —— 面板、官方切片、通知都停在旧状态。
- **做法**：连接判据加上「适配器必须是开着的」这一条兜底（拿不到适配器状态时不改变原有判断），
  关掉即视为未连接；详情页另外自己听系统的蓝牙广播（适配器开关 / 链路断开），
  断开当场就把三行收起来，不用退出页面再进来。

### 修复：详情页现在读得到「隐藏抗风档」这个偏好
- 以前详情页读的是 Settings 进程里那份并不存在的偏好文件，于是用户关掉抗风档后详情页照样显示。
  现在这个偏好跟着状态一起由模块进程带过来（和其它跨进程配置同一条通道），显示与主界面一致。

## 3.0.5 (305)
> 修复：**没有 root 但模块已启用时，依赖模块的功能被整片置灰**；模块激活不再与「有 root」混为一谈；
> root 探测失败不再成为永久结论，并新增重试入口。

### 修复：没 root 就被整片禁用（依赖模块的功能不该看 root）
- **问题**：旧判定 `EnvProbe.isNoRootMode()` = `!isRooted() && hookActiveCached() != true`，
  而 hook 缓存**未探测时是 null** —— 一进设置页（此时还没探测过）就会被判成「无 Root 模式」：
  弹窗图标、官方降噪面板、蓝牙详情页面板全部置灰、开关点了没反应，
  后台也不再尝试拉起官方 App。可这三项本来就由 **GMS 进程里的 Hook** 承担，
  **与 App 自身有没有 root 无关**：没 root 但 LSPosed 模块已启用 = 照常可用。
- **修复**：
  - 判定拆开：新增 `EnvProbe.hookUsable()` = **只有确定未激活（false）才算不可用**，
    未探测（null）不再当作不可用；设置页只按它置灰，文案改为
    「需要 FastPairHook 模块（在 LSPosed 中启用后可用）」。
  - 设置页首次进入时若 hook 尚未探测，后台补探一次（离开主线程），确认未激活才重刷页面。
  - 真正依赖 root 的两条链路（`MoondropBooter` 用 su 拉起官方 App、
    `LogCollector` 用 root 复制到公共目录）改为只看 `isRooted()`，不再被模块状态牵连。
  - 权限检查「运行模式」改用同一次检查里的**实测结果**，不再把「还没探测」显示成「模块未激活」。
  - 删除已无调用方的 `isNoRootMode()` / `hasRootEntry()` / `noRootSummary()`，避免再次被误用。

### 修复：「模块激活」与「有 root」不再混为一谈
- **问题**：`EnvProbe.isNoRootMode()` 只看 root 探测结果；`GaiaBleClient.init` 更是
  「没 root 就直接跳过 hook 探测」。可 GMS 桥接与官方面板注入靠的是 **LSPosed 模块**
  （Hook 跑在 GMS 进程里），App 自身并不需要 root —— 有模块无 root 的设备因此被误判成
  无 Root 模式：跳过模块探测、设置页相关项被置灰、权限检查显示「仅通知栏控制降噪」。
- **修复**：
  - `EnvProbe.isNoRootMode()` = 无 root **且** hook 未确认激活；hook 缓存未就绪时按旧行为保守处理，
    启动时序不变。
  - `GaiaBleClient.init` 一律实测 hook（不再因「没 root」跳过），日志如实写 `root=… module=…`。
  - 权限检查「运行模式」改三态：**Root 模式** / **模块模式**（有模块无 root：桥接与面板注入可用，
    Root 增强功能不可用）/ **无 Root 模式**。

### 修复：root 探测失败不再永久缓存（未授权时 root 整体隐藏）
- **背景**：未在 Root 管理器里授权时，root 会**整体隐藏**（FolkPatch 的 pathhide 等），
  App 侧看到的现象和「设备根本没 root」完全相同 —— 因此不能把一次失败当作结论。
- **修复** `RootShell`：**正结果进程内永久锁定**；**负结果只保留 10 秒 TTL**
  （避免一个 UI 流程里反复 exec），TTL 过后任何调用自动重试；`retryNow()` 供用户显式动作立即重试。
  hook 探测同样处理（`hookActiveCached()` / `retryHookProbe()`，对应「刚在 LSPosed 里启用模块」）。

### 保活改为「无需 Root、默认常开」（移除设置里的 Root 强力保活开关）
- **问题**：强力保活整套都挂在 Root 上（`dumpsys deviceidle whitelist` + `appops` + 写 `service.d`
  开机脚本），没 Root 就完全用不了；设置页还得让用户自己开、自己确认风险。
- **修复**：新增 `KeepAlive`，保活改为常开、且不再需要 Root：
  - 开机自启用 `BOOT_COMPLETED`、看门狗用 30s `AlarmManager` + `START_STICKY`（本来就不需要 Root）；
  - **电池优化白名单**改用系统的 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹窗申请
    （新增权限声明），只主动问一次，避免骚扰；被拒后可在「设置 → 检查权限」里手动再点；
  - 有 Root 时**额外**静默做 `deviceidle` + `appops` 增强（失败不影响，也不再有开关/风险弹窗）。
- 设置页移除「Root 强力保活」开关与风险确认弹窗；权限检查的「电池优化白名单」变为**点击即申请**。
- 至此，**应用不再需要直接授予 Root**：唯一仍然碰 Root 的可选项（把图标兼容写到 GMS 老路径）已改为增强路径。

### 修复：弹窗自定义图标「选了没反应」（结果回调丢失 + 必须 Root）
- **问题一（结果回调）**：选图用的是 `requireActivity().startActivityForResult(...)` 配合
  `Fragment.onActivityResult`。实测（Android 15 / ColorOS）回调根本到不了 Fragment ——
  选完图片什么都没发生。证据：应用 cacheDir 里连中间产物 `moondrop_custom_icon.png` 都没有生成，
  即 `saveIconFromUri` 从未被调用。
- **问题二（必须 Root）**：图标要写进 GMS 私有目录
  `/data/user/0/com.google.android.gms/files/moondrop_icon.png`，没有 Root（或 Root 被隐藏）必然失败。
- **修复**：选图改用 `registerForActivityResult(GetContent())` 启动器；
  图标改为写**本应用 filesDir**，由 exported 的 `PrefsProvider`（`openFile`）跨进程提供给 GMS 侧 Hook 读取
  （与 `show_wind` / `lang` 同一条通道），因此**无需 Root**；有 Root 时仍额外写一份 GMS 老路径。
  顺带新增 `icon_ver`，Hook 侧可据此判断图标是否换过。

### 新增：权限检查里的「重新检测 Root」入口
- 权限检查新增 **Root 权限** 一项，显示当前状态与选中的入口（`su` / `kp`），点击即在
  后台清缓存并重新探测（探测会 exec、可能弹授权框，绝不在主线程做），完成后回到 UI 线程重渲染。
- 提示文案据实分两种：见到了二进制但没拿到 uid=0 → 「已找到 root 入口但未取得授权…」；
  完全没见到 → 「未检测到 root 入口。若设备已 root：请先在 Root 管理器中把本应用加入授权列表…」。

## 3.0.4 (304)
> 修复：FolkPatch（APatch 系）等使用 `kp` 作为 root 入口的设备上「识别不到 Root」，以及由此连带失效的 Root 功能。

### 修复：Root 检测不认 `kp`（APatch / FolkPatch 系）
- **问题**：`EnvProbe.detectRoot()` 只探测文件名 `su` / `magisk` / `magisk64`，而
  FolkPatch（APatch 的非并行扩展分支）默认把 root 入口装在 **`/system/bin/kp`** ——
  反编译其源码可证：`uapi/scdefs.h` 中 `#define SU_PATH "/system/bin/kp"`、
  `LEGACY_SU_PATH "/system/bin/su"`，`APatchApp.DEFAULT_SU_PATH = "/system/bin/kp"`，
  `su` 仅作旧版兼容路径。这类设备因此被判成「未检测到 Root」，整体掉进无 Root 模式
  （跳过 GMS 桥接探测、停用强力保活与静默拉起等）。
- **修复**：检测标记补 `kp`；惯例目录补 `/debug_ramdisk`、`/data/adb/{ap,ap/bin,fp,fp/bin,ksu,ksu/bin}`。
  注：`/data/adb` 在真机上实测为 `drwx------ root root`（普通 App 不可读），
  这些条目只在放宽权限的 ROM 上提供额外命中机会，真正生效的是 `/system/bin/kp` 这类世界可读路径。

### 修复：Root 命令执行不再硬编码 `su`
- **问题**：四处调用均为 `Runtime.getRuntime().exec(arrayOf("su", "-c", ...))`
  （日志 Root 导出、静默拉起官方 App、强力保活写入/清除、Root 可用性自检）。
  在 `su` 不存在而只有 `kp` 的设备上，即便检测通过也会全部静默失败。
- **修复**：新增 `RootShell`，按 `su` → `/system/bin/su` → `/system/xbin/su` → `kp` →
  `/system/bin/kp` → `/debug_ramdisk/su` → `/data/adb/ksu/bin/su` 顺序探测，
  **取第一个执行 `id` 返回 uid=0 的入口并缓存**。`su` 排在最前，因此
  Magisk / KernelSU / 旧 APatch 设备选中的仍是 `su`，行为与旧版一致。
  命令改为合并读 stdout+stderr、带超时（授权框弹出时留有点击时间，不无限等待）。

### 诊断
- 日志抓取的「运行环境」段改为 `Root: 检测到 | 入口=<su|kp|…>`，便于一眼看出选中的是哪个入口。

---

## 3.0.3 (303)
> 修复：布丁（PUDDING / MD-TWS-056）ANC 命令发不出去（issue #4 复测反馈）；补齐 ANC_V2 档位映射与自适应档。

### 修复：能力响应按「特征对列表」解析，ANC 路径不再被误判为未知（issue #4）
- **问题**：布丁每次连接都回同一帧 23 字节能力响应
  `00 1D 01 01 | 00 | 00 02 | 01 01 | 05 01 | 0D 01 | 0E 01 | 0F 01 | 10 01 | 13 01 | 14 01 | 16 01 | 20 01`。
  旧实现只认「长度是 4 的倍数」的 32 位位图，见 `23 % 4 != 0` 即判「位图截断」并把 `ancPath` 钉回
  `UNKNOWN`；于是 `setAncMode` 在发送前就被 `dev < 0` 拦下（日志 `setAncMode: mode 1 not allowed on path -1`
  连打 57 次）—— 现象是「降噪点了没反应」，而能力探测其实每次都收到了完整能力表。
- **依据**：反编译官方 Moondrop App 内 Qualcomm GAIA SDK
  `com.qualcomm.qti.gaiaclient.core.gaia.qtil.data.GetSupportedFeaturesData`：
  `byte0 = hasMoreData(0/1)`，其后每 2 字节一组 `featureId | version` —— 是**特征对列表**，不是位图。
  按此解析，布丁的 feature = `0x00 BASIC / 0x0D 电量 / 0x0F 增益 / 0x13 指示灯 / 0x20 ANC_V2`
  （无 AudioCuration、无空间音频），与 PuddingPods 文档和本仓库布丁档案完全吻合。
- **修复**：`GaiaCommands.parseSupportedFeatures` 现在同时支持两种封装 ——
  长度奇数且首字节 ≤1 判为对列表，否则按老位图解析（GA2 等老固件行为不变）；
  `isFeaturePayloadTruncated` 只对位图生效，对列表不再误报截断。

### 修复：布丁 ANC_V2 档位映射（设备库）
- **问题**：ANC_V2 路径此前是**恒等映射**，而布丁固件语义是
  `00 关闭 / 01 自适应降噪 / 02 通透 / 03 抗风噪 / 04 基础降噪`，于是
  「降噪」按钮发成 `01`（自适应降噪）、「自适应」按钮发成 `04`（基础降噪）——两档互换。
- **修复**：`AncProfileLib` 新增 ANC_V2 型号档案表（`PUDDING`：
  SET `UI[关,降,透,抗,自适应,直播] -> [0,4,2,3,1,-1]`，GET `dev -> UI = [0,4,2,3,1]`）。
  `GaiaCommands.ancDevFromUi / ancUiFromDev` 在 ANC_V2 路径上支持型号映射，未收录型号仍回退恒等。

### 界面：自适应档位在所有界面统一可用
- `supportedUiModes()` 对 ANC_V2 先问型号档案：布丁宣告 `[关,降,透,抗,自适应]`（不含直播），
  于是官方面板 / 通知 / 弹窗都只出现该设备真能执行的档位；
  官方面板（含**音量条**入口那套 Hearable Controls）的 `ui_modes` 宣告随之收窄，
  自适应按钮映射到 `dev 01`、降噪映射到 `dev 04`，高亮状态与设备读回一致。
- 弹窗 mode bar 新增「自适应」按钮：仅当设备能力含该档才出现；能力后到时自动重建 mode bar，
  不再出现「探测完成前开过的弹窗少一档」。
- 诊断：能力探测结论文本写入 AppLog（`probe: len=... truncated=... ancPath=... features=...`），
  用户导出的日志里可直接看到探测结果。

### 加固：Hook 返回值类型安全化（防宿主进程崩溃）
- 新增 `HookGuard`：libxposed / LSPosed 的 Hook 桥按目标方法**返回类型**解包回调返回值，
  目标返回基本类型（如 `boolean`）而回调给 null 时，桥内解包即抛异常 —— 崩的是**宿主进程**
  （GMS / Settings / SystemUI），不是模块自己。本模块大量使用「不满足条件就 early-return null
  放行原逻辑」的写法，对 void 方法恰好正确，对少数返回基本类型的方法就是一颗雷。
- 现有 24 处 early-return 全部改走 `HookGuard.nullSafe(chain)`：由目标方法的真实返回类型决定
  安全值（void / 引用类型 → null，语义不变；基本类型 → 该类型默认值并记警告日志）。
- `HookGuardTest` 新增 8 例覆盖这张回落表（void / 引用 / boolean / int / 类型不匹配 / 构造器 / 空 chain）。

### 界面：版本号去掉 "Alpha" 字样
- 关于页与主页英雄卡此前固定显示 `V3.0.3Alpha`。3.0 起已是正式版，改为直接显示 `V3.0.3`
  （仍以 PackageManager 为唯一来源，不硬编码；旧 alpha 版本号的兼容剥离保留）。

---

## 3.0.2 (302)
> 修复：布丁（PUDDING / MD-TWS-056）等 Classic Bluetooth 设备在 RFCOMM/SPP 上「连得上但控制无效」。

### 修复：RFCOMM 发送缺少 GAIA 传输帧封装（issue #4）
- **问题**：RFCOMM/SPP 路径一直直接发送**裸 PDU**（`00 1D <cmdValue> <payload>`），
  没有官方 GAIA 传输帧头。设备侧解析器读到的首字节是 `0x00`（非法 SOF），整帧错位，
  于是对每条命令都不回有效应答 —— 现象就是「RFCOMM 已连接、命令发出去了，
  但能力探测一直超时、降噪点击无反应」。日志特征：`framer pending 1 bytes (partial frame)`
  反复出现、且全程没有一条 `RX pdu`。
- **依据**：反编译本机安装的官方 Moondrop App 内的 Qualcomm GAIA Client SDK
  （`com.qualcomm.qti.gaiaclient.core.gaia.core.transport.TransportProtocol$Rfcomm$Frame.format`），
  官方 RFCOMM 封装为 `FF | version | flags | length | PDU`，其中
  `length = PDU 长度 - 4`，`flags` 的 bit0=校验和、bit1=双字节长度
  （双字节长度仅 `version >= 4` 且长度 > 255 时启用）；官方 `GaiaFormatter.Rfcomm` 默认**不带校验和**。
- **修复**：新增 `wrap()` 按上述格式封装；发送封装**自适应**——先 `FRAMED_V4`、
  再 `FRAMED_V3`、最后回退旧的 `BARE`，每种尝试 3.5 秒，**收到第一个有效回包即锁定**该封装
  （设备固件差异不硬编码，靠回包自证）。
- **顺带修复接收路径两个确定性缺陷**：
  1. 旧实现 `input.read(full, n, avail)` **不检查返回值** —— 少读时 `ByteArray` 尾部
     会留下 `0x00` 填充，凭空造出假字节污染帧缓冲；
  2. 旧实现固定 `sleep(50ms)` 后只查一次 `available()` 判定 burst 结束，分段到达的帧
     会被切碎。改为「累积到线路静默 45ms」再切分，并新增 `RX burst(<n>) <HEX>` 十六进制诊断日志。

---

## 3.0.1 (301)
> 修复：未收录型号被误判为「不支持的设备」而永久拉黑。

### 修复：协议指纹证伪之前不得拉黑设备
- **问题**：设备识别门禁对「名字不含关键字」的型号（如系统里显示为 `Robin's Earphones`
  的知更鸟）会放行一次协议指纹探测，由 GATT 服务发现裁定；但拒绝名单的写入点此前是
  **无条件**的 —— 只要 GAIA / 9ECA / RFCOMM 三条路全失败就写进 `rejected`，
  而这三条路失败的原因可能是**传输层问题**（连接超时、`status=147`、耳机刚出仓未就绪、
  信号差），与设备到底支不支持协议无关。结果是未收录型号撞上一次抖动就被永久拉黑，
  此后不再探测，只能靠主界面「刷新状态」手动清空名单。
- **修复**：引入 `protocolRefuted` 标记，只有**服务发现成功、且确认 GATT 里既无 GAIA 服务
  也无 9ECA 服务**时才置位（这是「设备确实不支持」的唯一铁证）；拒绝名单写入点改为仅在
  该标记置位时执行。传输层失败不再写名单，下一轮 detect 轮询照常重试。
- 影响面：仅 `GaiaBleClient.kt`；已实测型号命中名字白名单、本就不走探测路径，不受影响。

---

## 3.0 (300)
> 从 alpha2.x 进入正式版：版本号收敛为 `3.0`，versionCode 300。

### 新增：设备常驻通知（电量 + 降噪控制合成一条）
- 原来电量与降噪控制是**两条**独立通知，现合并为一条：标题为设备名，正文显示左右耳电量
  （按需求**不含充电盒**），下方一排降噪档位按钮。
- 按钮**不用系统 action**：Android 12 起系统只渲染文字胶囊、传进去的图标被直接丢弃，
  而这里要的是**像主界面那样的大图标按钮**。改用自定义布局 ——
  圆形底 + 大图标 + 文字，当前档位实心高亮，颜色全部走 **M3 动态取色**
  （与 App 内面板同一套 token：选中 = primary/onPrimary，未选中 = container/onContainer）。
- 折叠态与展开态分别提供视图：系统给折叠态自定义视图的高度上限约 48dp，
  带上标题与电量行就会把按钮整块裁掉，因此折叠态只放按钮行、展开态给完整信息。
- **内容不变不重发**：每次 `notify()` 都会让系统重建视图、把用户刚展开的通知打回折叠态，
  这正是「通知自己折叠起来」的来源；现按内容指纹去重。
- 档位按钮按设备能力动态生成（`supportedUiModes()`），顺序对齐官方 GFPS 通知：
  降噪 / 关闭 / 通透，其余档位随后。

### 修复：官方降噪面板（音量条 / 提示音和振动）点击无反应
- **根因**：官方面板的点击走 `HearableControlManager` 的发送出口，**既不经过**子模块的
  `in()`（那条只写 dataStore），**也不经过** `dwaa.m` —— 后者发包前要等
  「上一次 SET 的响应」，而本机没有 GFPS Message Stream
  （`EventStreamManager: No available EventStreamMedium`），这个响应永远等不到，
  于是发包出口根本不会被调用，现象就是「点了没反应、随后高亮回弹」。
- **修复**：在**意图入口**（子模块 `in()`）与**管理器发送出口**两处都接住用户意图，
  把拿到的 GFPS 报文翻译成 GAIA 请求交给 App 真正下发给耳机；两侧都按
  「报文指纹 + 时间窗」过滤掉自己注入的 NOTIFY，不会自触发。
- 激活重试由「20 × 3 秒后永久放弃」改为「前 20 次 3 秒、之后 30 秒长期重试」——
  Fast Pair 模块是事件驱动加载的，原窗口一过就永久失效，面板会一直显示旧模式且点击无效。

### 新增：设置页「功能开关」四类开关
- 官方集成：官方降噪面板、蓝牙详情页面板。
- 通知：电量通知、通知内降噪控制。
- 开关经跨进程 SP 读取，hook 侧按开关**真实停用**（关掉即不注入、不发通知），
  读取失败一律按「开」处理，不因读取异常静默废掉功能。

### 改进：面板与详情页
- 降噪档位按**设备实际能力**动态分配（`supportedUiModes()`），不再固定四档；
  能力表外的档位不注入官方面板，避免出现点了没反应的档位。
- 面板内控制项统一以 **GAIA 就绪**作为可交互判据（比「已连接」更强），未就绪时置灰。

### 新增：无 Root 模式（未检测到 Root 时自动回落）
- 项目本体是 LSPosed 模块，但**控制耳机的能力从来不需要 Root** ——
  读电量、切降噪走的是标准 GAIA BLE 直连（BluetoothGatt）。
  因此在没有 Root 的设备上自动回落为「仅靠通知栏 + App 主界面控制降噪」。
- 回落时一律**静默停用**所有 Root 依赖项，不再尝试、不再报错、不再弹窗：
  - `su` 拉起官方 App（`MoondropBooter`）——必然失败，直接跳过；
  - 官方面板 / 详情页注入（GMS Hook，需 LSPosed）——设置页对应开关置灰并说明；
  - Root 强力保活——开关置灰并说明，且**保留用户原有设置值**（临时无 Root 不该改掉它）；
  - 弹窗图标自定义——该图标由 GMS 进程内的 Hook 读取（`readIconBytes`），
    无 Root 时 Hook 不工作，设置页该项一并置灰并说明；
  - 日志抓取里的 `su` 复制——走非 Root 兜底路径；
  - 启动时的 Fast Pair Hook 探测——跳过那 4 秒必然超时的等待，直接走内置 BLE 自扫。
- 保证回落链路可用：通知栏按钮被点击时会**顺带把服务拉起**（幂等），
  因此即便后台进程被系统回收，点一下通知按钮也能恢复 GAIA 连接；
  开机自启与 AlarmManager 保活本来就不依赖 Root。
- 权限页与设置页会明确显示当前处于「无 Root 模式」及其可用范围，
  避免用户误以为功能坏了。

### 改进：权限收紧（移除三处从未使用的声明）
- **`SYSTEM_ALERT_WINDOW`**：本模块**从不创建悬浮窗** —— 代码里没有任何
  `canDrawOverlays` / `TYPE_APPLICATION_OVERLAY` / 添加 overlay 窗口的调用；
  弹窗走的是 GMS 进程内的既有窗口，受 GMS 自身权限约束，与本应用无关。
  该权限此前声明了却一直是 `granted=false`，权限检测页还据此提示用户去授予，
  属于误导，现一并删除（清单 + 检查项 + 两处跳转分支 + 相关文案）。
- **`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE`** 与 service 上的
  `android:foregroundServiceType`：全仓没有任何 `startForeground()` / `stopForeground()`
  调用，运行时 `dumpsys activity services` 也确认该服务未处于前台状态 ——
  `HeadsetDetectService` 实为普通后台服务（靠 `START_STICKY` + AlarmManager 保活），
  这几项声明对运行时零影响。
- 收紧后清单只剩真正在用的权限：`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、`BLUETOOTH`、
  `POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`。

### 改进：设置页分类重组
- 原「行为」段把官方集成 / 通知 / 保活 / 后台 / 界面元素混在一起，现按功能域拆分：
  **外观**（主题、取色、AMOLED、语言）、**功能**（官方集成、通知、抗风噪按钮）、
  **自定义映射**、**后台**（监听、隐藏、保活）、**诊断**（权限、日志、弹窗图标、模拟测试）。

- 版本号升至 **3.0**（versionCode 300）

## alpha2.54 (289)
### 修复：反复开关「动态取色 / AMOLED 纯黑」导致崩溃
- **根因（三处叠加，缺一即不崩但隐患仍在）**：主题开关的 OnCheckedChangeListener 里直接
  `Handler().postDelayed({ requireActivity().recreate() }, 350/550)` ——
  1. **没有任何取消机制**：快速反复开关会排出多个 recreate 回调，逐个执行；
  2. 回调闭包持有的是**创建它的那个 Fragment 实例**。第一次 recreate 完成后该实例已 detach，
     后续排队的回调再调 `requireActivity()` 必抛 `IllegalStateException: Fragment not attached to an activity`；
  3. **没有生命周期清理**，视图销毁后回调照跑。
- **修复**：
  - 新增统一入口 `scheduleRebuild(delayMs)`：**先 removeCallbacks 再 post**，把连续请求合并成一次重建，
    从根上消除「多个 pending 回调」这一前提。
  - `rebuildRunnable` 执行前用 `isAdded` / `activity` / `isFinishing` 三重检查，任一不满足静默跳过，
    不再依赖 `requireActivity()`。
  - `onDestroyView` 取消挂起重建并释放 `seedRow` 引用。
  - 主题下拉、种子色、语言、重置映射**四处直接 recreate 一并收拢**到该入口（同样存在 detach 风险）。
  - `makeThemeSwitch` 的 `seedRow` 改为局部引用，消除 `!!` 断言与 `parent` 检查之间的 TOCTOU。
  - 种子行入场动画同样改为局部引用 + `parent` 检查 —— `onDestroyView` 置空 `seedRow` 后，
    原 `seedRow!!` 延迟回调会抛 `KotlinNullPointerException`（本次一并堵住）。
- **实测**：12 次连续点击（296ms 内，全部落在同一个 350ms 窗口）只触发 **1 次**重建，
  进程存活、无异常（修复前会排队 12 次）。用户真机复测通过。全仓单测 26 例全绿。
- 版本号升至 **alpha2.54**（versionCode 289）

## alpha2.53 (288)
### 修复：断开连接后英雄卡徽章仍停留在旧编解码器
- **根因（两处叠加，缺一不可）**：
  1. `codecLabel` 为缓存字段，断开时从不清空 —— 徽章继续显示上一次查到的编解码器，与「未连接」的真实状态矛盾。
  2. 断连广播只走 `updateAncStatus`（不走 `updateStatus`），而徽章原先只在 `updateStatus` 内刷新 —— 状态变化根本没触发重算。
- **修复**：
  - `linkTypeLabel()` 无链路时返回 `null`（原返回「未连接」字符串）。让调用方直接判断「是否该丢弃缓存」，避免依赖字符串比较。
  - `badgeText()` 检测到无链路即清空 `codecLabel`；`refreshCodecBadge()` 无链路直接返回，不白跑一次 dumpsys。
  - 徽章刷新接入 `updateAncStatus`，随连接状态广播同步更新。
- **该缺陷影响 `alpha2.52`（287），该版本已撤回**，请升级到本版。
- 版本号升至 **alpha2.53**（versionCode 288）

## alpha2.52 (287)
### 界面按 Material 3 规范统一重构
- **大标题随滚动收缩**：三页统一为 M3 LargeTopAppBar 形态（展开 152dp / 收起 64dp，标题 28sp→22sp）。内容自标题下方穿过、标题钉在上层。`ScrollView` 必须 `clipToPadding=false`，否则顶部内边距区成为裁剪区，标题收起后中间会空出一条缝（本次实测踩到并修复）。
- **切页动效**：进入 fadeIn + scaleIn(0.985) 320ms（M3 淡入手感），退出快速淡出；新增 `res/anim/m3_page_in.xml`、`m3_page_out.xml`。
- **英雄卡改强调色**：底色 `container` → `primary`，文字与图标走 `onPrimary`，API 徽章反色；新增 `heroFg` 字段，不再借用功能卡配色。
- **三选一改下拉**：主题、语言由成排按钮改为「行 + 右侧当前值 + 下拉菜单」。菜单出现在**手指落点**（贴边自动内收），入场 scale 0.8→1 + fade 140ms，选中项 primary 填充 + ✓；尺寸按 M3 菜单规范（文字 14sp / 图标 18dp / 行高 48dp）。
- 补齐语言卡与「检查权限」卡之间缺失的 12dp 卡间距。

### 修复：蓝牙设备详情面板退化为单行条目
- **根因**：hook 进 `com.android.settings` 后，面板构建拿的是**宿主 Context**。模块与宿主的资源包 ID 都是 `0x7f`，同一数值在宿主体内被解析成宿主自己的资源 —— 实测 `ic_anc_off` 撞上 `com.android.settings:dimen/animation_max_size`，抛 `Resources$NotFoundException`；异常被 `onBindViewHolder` 的 catch 吞掉后走 `chain.proceed()`，渲染成原生单行，表现为「整块面板消失、点击无反应」。
- **修复**：新增 `M3Ui.moduleDrawable()` —— 模块进程内直接用传入 Context（与改动前逐字等价），hook 进程才 `createPackageContext` 并缓存。`DcIcons` 一并改走它（追踪 / 增益 / 指示灯同理会崩）；`standardSwitch` 的 ✓/✗ 拇指图标同类隐患一并堵住。

### 修复：空间音频开关改用设置 App 原版控件
- 直接构造 `MaterialSwitch` 会在 Settings 进程抛 `IllegalArgumentException: The style on this component requires your app theme to be Theme.AppCompat`，使整个面板注入失败。
- 改为 inflate 设置自身的开关布局（按新到旧：`settingslib_expressive_preference_switch` → `preference_widget_switch_compat` → `preference_widget_switch`；API 36 实测命中第一个）。该布局内的 `MaterialSwitch` 自带 `Theme.Material3.DynamicColors.DayNight` 覆盖，就是设置页在用的同一个控件；全部失败才退回系统 `Switch` + M3 配色。

### 图标
- **ANC 四态与弹窗降噪按钮**改 Material Symbols 矢量（关闭 / 降噪 / 透传 / 抗风），取代原先手绘 Canvas 几何；真机确认主界面、hook 面板、GMS 弹窗三处一致。
- **应用图标全新重构**为自适应图标：108dp 画布、中心 72dp 安全区、图形收敛在 66dp 内，含 background / foreground / monochrome 三层（支持 Android 13+ 主题图标）。笔画刻意加粗以适应 24–48dp 的单色渲染；原有位图保留作 API 26 以下回落。

### 英雄卡徽章改显示当前编解码器
- 由固定的 API 级别改为显示耳机当前在用的编解码器（LDAC / AAC / SBC / aptX …）。该信息只有蓝牙栈掌握（`BluetoothA2dp.getCodecStatus` 为 `@SystemApi`、需 `BLUETOOTH_PRIVILEGED`），故经已有 Root 通道读取并解析 dumpsys，**不维护任何编解码名称表**；读不到时退回链路类型（LE Audio / ACL / LE），再退回「未连接」。异步查询 + 5s 节流，不阻塞 UI。

### 自定义映射交互规范化
- 降噪 / 增益的裸数字输入框改**下拉选择**：可选值收敛为固定集合，越界与半截输入不再可能出现；降噪编辑仍四档全量落库。增益第 0 项为「隐藏该档位」（设备码 -1）。
- 空间音频追踪标签由「每次按键都落库」改为失焦 / 回车才保存，空值视为恢复默认。
- 「当前型号档案」与「当前：…」两行统一规格（12sp medium / primary）。
- 修复菜单选中态不跟随：原先把创建时的选中值闭包进菜单，选完重开仍是旧高亮；改为行内维护可变选中态。

### 其他
- 主界面「刷新状态」新增线性进度条（BLE 重连需数秒），状态回包即收起，6s 超时兜底。
- 本轮另含（commit 749f8a6）：未收录型号门禁指纹化（issue #5）、主界面电量行未连接仍显示、刷新按钮重试探测。
- 版本号升至 **alpha2.52**（versionCode 287）

## alpha2.51 (286)
### 修复 ANC 按钮映射「透传 ↔ 抗风」互换（issue #1）
- **根因**：设置页编辑任一档位时只写被编辑的那一格，其余格在读取时回退到**名义默认映射** `[1,2,3,4]`（`AncProfileLib.DEFAULT_MAP`），而不是该型号的**档案映射**。设备码 3 与 4 在不同型号上分别对应「抗风」与「透传」，名义顺序恰好与型号档案相反 —— 于是用户在 GA2 / 太空漫游2 上只要动过任意一格映射，两个按钮就静默互换。
- **修复**：`GaiaBleClient.readAncMap()` 的回退基准由名义默认映射改为**型号档案映射**（`AncProfileLib.resolve(deviceName, null)`）；并抽出纯函数 `AncProfileLib.isStalePartialCustom(rebuilt, profileMap)` 统一判定，消除内联重复。
- **存量数据自愈**：新增一次性 `healStaleCustomAncMap()`，识别旧版本部分写入留下的脏配置 —— 判定特征为「重建出的映射恰好 == 名义默认映射，而当前型号档案 != 名义默认映射」。命中则清除该自定义映射交回型号档案（标记 `anc_map_migrated_v3`，只跑一次）；**档案与名义顺序一致的型号恒不命中，绝不误清用户设置**。
- **源头堵住**：设置页编辑任一档位改为**四档按当前生效映射全量落库**，不再产生「部分自定义」；提示文案补充当前生效的型号档案名（中英同步）。
- **回归测试**：新增 `AncProfileLibTest`（6 例）覆盖档案解析优先级、残留判定正例与两条反向护栏；期望值全部由公开常量与函数推导，不写死映射字面量。全仓单测 **26 例全绿**。
- **无行为改动**：未触碰连接链、协议识别、LE 地址发现、电量、DC 扩展控制、弹窗逻辑；`readAncGetMap()` 与 GET/SET 双向映射语义不变。已验证未自定义、四格全有效自定义两种场景与修复前**逐字等价**。
- 版本号升至 **alpha2.51**（versionCode 286）

## 2.50 (285)
### 引入 DexKit 特征定位：GMS Fast Pair 混淆类名失效时自动兜底
- **新增 `DexKitLocator`**：用 dex 内的**稳定特征串**（日志格式串）反查被 R8 混淆的类，针对 Fast Pair 弹窗那几个只剩两三个字母的短类名（`dtes` / `dthi` / `dtok`）——它们随上游每次重编译都可能改名，硬编码类名迟早失效。
- **改动范围仅 3 处 hook 点**（`FastPairHookEntry` 的 `dtes.f`、`dthi.O`、`dthi.q`）：把 `Class.forName(x)` 换成 `DexKitLocator.resolveOrFallback(cl, key, x)`。作用域（`com.android.settings` + `com.google.android.gms`）与包名不变。
- **快路径零开销**：先按**原硬编码类名**加载（与改造前逐字等价），只有加载失败（上游改版改名）才启用 DexKit 按特征定位；单进程单 bridge 缓存复用，定位耗时约 200–460ms，仅发生在兜底时。
- **只增强、不替代**：定位失败统一回退原硬编码名，并把原始异常原样抛出——任何机型 / 版本都不会因为定位器失效而丢功能。
- **`dtok` 的特殊处理**：该类内部几乎全是 protobuf 字段、字符串常量只有单字母枚举名，**没有可用特征串**，改为「由持有它的 `dtes` 字段 `c` 的类型反推」——字段 `c:Ldtok;` 本就是现有 hook 依赖的成员，零新增风险。
- **依赖与体积**：引入 `org.luckypray:dexkit:2.2.0`；APK 内置 `libdexkit.so`。同时用 `ndk.abiFilters` 限定 `arm64-v8a` / `armeabi-v7a`，剔除只服务模拟器的 x86 / x86_64，单 APK 减约 0.8MB。
- **实机验证**：把硬编码名临时改成假名（`dtes__FAKE_TEST` 等）强制走兜底路径，DexKit 正确反查回真实类 `dtes` / `dthi` / `dtok`，GMS 三进程 hook 全部成功，证明兜底链真实可用。
- **无行为改动**：未触碰任何 hook 逻辑、GAIA / 9ECA 协议、设备库、弹窗逻辑；`hookMoondrop` / `hookBluetooth` 两条链保持原样（当前不在启用作用域内，代码保留以备多设备适配）。
- 版本号升至 **2.50**（versionCode 285）

## alpha2.41.10 (284)
### 监听开关合并：主界面按钮并入设置，单一入口
- **主界面「开始/停止后台监听」按钮移除**：该按钮与设置页开关职责重叠（一个管立即启停、一个管启动时自动监听），统一收敛到 **设置 → 行为 → 后台监听**，避免两处入口互相打架。
- **设置「启动自动监听」升级为「后台监听」总开关**：开启 = 立即开始监听，并把 `enable` / `auto_service` 双写为真（启动应用自动恢复、开机自启、后台防杀随之生效）；关闭 = 停止监听服务、取消保活、双写为假。因原开关只管「启动应用时是否自动监听」、无法表达「立即启停」，与主界面按钮**功能并不等价**，故按合并处理而非直接删除。
- 主界面保留英雄卡运行状态（运行中 / 未运行）实时展示，状态可见性不变。
- `SettingsActivity`（遗留页）同步为总开关语义，避免代码库残留旧行为。
- 版本号升至 **alpha2.41.10**（versionCode 284）

## alpha2.41.9 (283)
### issue #3 三连修：后台隐藏误退出 / GAIA 地址缓存污染 / 后台弹窗失效
- **①「权限检查导致应用退出」（根因定位）**：`MainActivity.onStop()` 里无条件 `finishAndRemoveTask()`——`bg_hide` 打开后，只要离开主界面（进入「权限检测」二级页、跳系统授权页）就会销毁整个任务，用户回来时应用「自己退出了」。现改为只有 `onUserLeaveHint`（Home / 最近任务等**用户主动离开**）才隐藏，并用 `Application.registerActivityLifecycleCallbacks` 维护全局可见界面计数：还有其他本应用界面可见时不隐藏；配置变更（旋转/主题）直接跳过；隐藏前再延迟 500ms 复核一次，避免快速返回被误杀。应用内跳转与授权流程完全不受影响。
- **②「能连耳机但所有功能失效」**：`gaia_le_addr` 持久缓存污染链（坏地址写入后永不自愈），三处修复：
  - `FastPairHookEntry`：`ACL_CONNECTED` 推送地址时**只校验 MAC 格式，不校验设备身份**，任何蓝牙设备（键鼠/车机/其他耳机）连接都会把地址推给应用并落盘。现按真实设备名经 `DeviceMatcher.isMoondrop()` 过滤（不硬编码型号/MAC）。
  - `GaiaBleClient`：广播来源、LE 扫描命中、`doConnectLe()` 三处**未经验证即写持久缓存**（prefs + `gaia_le_addr.txt`）已全部改为只驻内存候选；只有 `onServicesDiscovered` 确认存在 GAIA 或 9ECA0000 服务后才 `cacheVerifiedLeAddr()` 落盘。
  - 服务发现确认该地址**无 GAIA/9ECA 服务**时，把地址加入 `invalidLeAddrs` 移出候选，并 `clearLeAddrCache()` 清除被污染的 prefs + 文件缓存，下次连接重新发现正确地址（自愈）。
- **③「后台连耳机不弹窗」**：与 ② 同源——GAIA 永远不就绪导致 `PopupGate.flushPendingIfReady()` 拿不到电量；叠加 `bg_hide` 误销毁任务，应用无法后台驻留。①② 修复后链路自愈。
- 设置页「后台隐藏」文案同步澄清为「用户切到后台时隐藏主界面（不驻留最近任务）；应用内跳转与授权流程不受影响」，避免与后台监听/弹窗功能互相误解。
- 版本号升至 **alpha2.41.9**（versionCode 283）

## alpha2.41.8 (282)
### 图标避开电量改为布局完成后轮询定位
- 图标避让电量 subhead 的 top 偏移由「一次性计算」改为轮询等待 subhead 布局完成后再定位，避免 subhead 尚未测量时偏移量取 0、图标仍被电量百分比遮挡；新增详细定位日志便于排查。
- 版本号升至 **alpha2.41.8**（versionCode 282）

## alpha2.41.7 (281)
### 图标 top 动态避开电量 subhead
- 设备详情/概览卡图标 top 由固定值改为动态计算，避开电量 subhead 区域，防止图标遮挡电量百分比。
- 版本号升至 **alpha2.41.7**（versionCode 281）

## alpha2.41.6 (280)
### 首次关闭弹窗后 GAIA 就绪不再二次弹窗
- **根因**：弹窗有两条触发通道都汇聚到 `postShow` 防重，但首次弹窗走 ACL 通道（FastPairHook 直接弹，设备名未写入 `connectedShown`）；用户关闭后走 PopupGate 通道，`pollConnected` 每轮对同一设备反复 `tryShowConnectedDeferred` 重建 pending，旧 `cancelPending()` 只清一次，等 GAIA 就绪再次弹窗。旧 12s 窗口 + 关一次 pending 治标不治本。
- **修复（3 文件）**：
  - `PopupGate.kt`：新增 `userClosedKeys` 集合；`tryShowConnectedDeferred`/`tryShowConnected` 入口加"用户已关闭则跳过"拦截；新增 `markUserClosed()` 登记设备并清除 pending；断开时清除登记（`tryShowDisconnected`），允许下次连接再弹。
  - `FastPairHookEntry.kt`：HalfSheet onDestroy 发 `SHEET_CLOSED` 广播时 `sLastShowMs = System.currentTimeMillis()`（让 postShow 12s 防重窗口生效）并带出设备名。
  - `HeadsetReceiver.kt`：`SHEET_CLOSED` 分支取设备名/地址调 `PopupGate.markUserClosed()`。
- **行为**：仅在"用户关闭弹窗"这条路径增加拦截，不影响正常连接与其他型号（GA2/布丁/猫咖/MOCA）回归；断开清除登记，下次连接仍正常弹窗。
- 版本号升至 **alpha2.41.6**（versionCode 280）

## alpha2.41.5 (279)
### Space Travel 2 映射写库 + 弹窗防重
- **设备库**：Space Travel 2 加入 PROFILES（ANC AudioCuration 映射 `[1,2,4,3]`：关=1/降噪=2/透传=4/抗风=3，与 GOLDEN AGES 2 一致），实测不再需手动设置设备码；DcProfile 增益 `gainMap` 修正为 `[2,1,0]`（真机实测 0x00=高/0x01=中/0x02=低 反向），档位名不再颠倒。
- **弹窗防重**：`postShow` 增加统一防重——弹窗还开着（`sHalfSheetActivity != null`）或距上次显示低于 12s 不再弹新窗，ACL 与 PopupGate 两条通道汇聚到 postShow 后不会弹两次；弹窗关闭（HalfSheet onDestroy）时应用侧 `cancelPending()` 取消排队连接弹窗，GAIA 就绪/超时不再二次弹。行为：连上只弹一次，等待 GAIA 就绪后同一弹窗刷新为可控制，或关闭后改在 App 操作。
- **文档**：README 版本头与版本历史同步至 alpha2.41.5。

## alpha2.41.4 (278)
### RFCOMM 帧切分重构 + 能力探测响应驱动降级
- **新增 GaiaRfcommFramer 流式状态机**：SPP 流按官方 TransportProtocol 精确切帧——FF 传输帧按 Len 字段切、裸 PDU（00 1D）按下一帧起始/ burst 结束切、半截帧跨 burst 保留续读；修复设备对每个响应双发（裸 PDU + FF 帧各一遍）时粘包错切、device info 串乱码尾巴的问题，GaiaRfcommTransport readLoop 改为逐 PDU 分发。
- **CapabilityProbe 响应驱动能力降级**：不回能力位图的设备（如 Space Travel 2 走 RFCOMM）由真实回包驱动——新增 onFeatureResponseSeen()（DAC/LED/空间音频任何响应都标记能力，无硬编码）、onBasicAlive()（BASIC 版本/型号响应证明链路活着，超时"无 ANC"结论可信）；GaiaPacketHandler 处理 BASIC cmd0（GET_GAIA_VERSION）。
- **探测节流防重连风暴**：startProbes() 8 秒内重复调用（RFCOMM 抖动重连风暴）只轻量补发 cmd1/cmd4，跳过 ANC 阶梯与超时重发链，探测循环不再叠加刷屏；reset() 清零节流基准。
- TX 路径零改动：仍发裸 PDU，GA2/布丁等已验证设备行为不变。
## alpha2.41.3 (277)
- **运行时权限申请补 BLUETOOTH_SCAN**：OverviewFragment.requestNeededPermissions/fixPermission 与 PermissionActivity.fixPermission 把 BLUETOOTH_CONNECT 申请数组扩为 CONNECT+SCAN（与官方 Moondrop App 一致）；PermissionChecker 蓝牙权限改为同时检测 CONNECT 与 SCAN。实测 Space Travel 2 依赖 BLE(9ECA0000) 控制，LE 扫描缺 BLUETOOTH_SCAN 抛 SecurityException 导致 BLE 通道挂掉、App 被逼回退 RFCOMM、GAIA 能力不完整的问题。

## alpha2.41.2 (276)
### 连接稳定性增强：MOCA 等 dual-mode 设备 RFCOMM/SPP 兜底
- 单候选分支做 LE → TRANSPORT_AUTO → RFCOMM 三级升级兜底（默认，所有设备）
- LE 与 TRANSPORT_AUTO 均失败(status=147)时，主动尝试 RFCOMM/SPP，解决 MOCA(猫咖) 等 LEE GATT 被 BR/EDR 挤掉无法建立 GAIA 控制通道的问题
- 新增 rfcommFallbackTried 标志：每次连接会话重置，RFCOMM 失败不再刷屏，等 detect 下轮 re-scan
- 防护：useRfcomm 已连时直接复用，避免 detect 轮询的 connect() 断开 RFCOMM
- 日志增强：`LogCollector` 在系统信息一节补充设备 OS 详情——`Build.VERSION.CODENAME`/`INCREMENTAL`/`SECURITY_PATCH`（安全补丁）、`Build.VERSION.BASE_OS`/`PREVIEW_SDK_INT`、`Build.DISPLAY`（如 ColorOS 完整版本串）、内核版本 `System.getProperty("os.version")`，便于适配与定位问题。
## alpha2.41.1 (2026-08-31)
- **修复日志导出 EACCES（Permission denied）**：部分 ColorOS 系统上 `getExternalFilesDir` 返回的 `/Android/data/.../files/Download/logs/` 路径在写 ZIP 时被存储策略拦截，日志抓取报「保存失败」。`LogCollector` 打包目录改为应用内部 `filesDir`（绝对可写），导出链调整为 Root 复制公共根 → MediaStore 写入系统公共下载（Android 10+ 免存储权限）→ 兜底内部目录，确保日志 ZIP 任何 ROM / 有无 Root 都能保存并分享。

---
## alpha2.41.0 (2026-08-31)
- **蓝讯系连接稳定性 + 太空漫游2（Space Travel 2）适配**：
  - `GaiaBleClient`：dual-mode TWS（BR/EDR+LE）在服务发现阶段易被 LE 连接挤掉（status=147）；新增 `lastConnectedAddr` + `transportAutoTried`，纯 LE 持续失败时 `transportFor()` 回退 `TRANSPORT_AUTO`，单候选断连时记录地址延迟重连同一地址（900ms），成功发现 GAIA/9ECA service 时重置标记。
  - `AncProfileLib`：新增 `SPACE TRAVEL 2`（BT8932F，中科蓝讯）DC 档案——无空间音频、三档增益、恒等映射（0=低 / 1=中 / 2=高）；为 `DC_PROFILES` 列表里的 PUDDING 条目结尾补逗号以容纳新档案。
  - 说明：太空漫游2 ANC 走标准 GAIA ANC_V2 恒等映射（关=00 / 降噪=01 / 透传=02），无需 AudioCuration 映射表；ANC 能否切换取决于连接链路能否探测到 ANC_V2，本次连接稳定性修复即为其前置。
- 版本号升至 **alpha2.41.0**（versionCode 274）


## alpha2.40.1 (2026-08-30)
- **Fast Pair 弹窗「设置」按钮改为跳转系统蓝牙设备详情页**：不再跳转软件主界面 MainActivity。
  - 新增 `resolveMoondropAddress()`：从 `BluetoothAdapter` 已配对设备里用 `DeviceMatcher.isMoondrop()` 动态匹配 Moondrop 耳机蓝牙地址（**不硬编码 MAC**），找不到返回 null
  - 匹配到地址时，用 `:settings:show_fragment` + `:settings:show_fragment_args`(device_address) 打开 `Settings$BluetoothDeviceDetailActivity` 的 `BluetoothDeviceDetailsFragment`，进入系统蓝牙设备详情页（路径从 SettingsActivity 反编译确认）
  - **兜底逻辑**：动态匹配不到 Moondrop 地址时才回退到原 MainActivity，避免异常
- 版本号升至 **alpha2.40.1**（versionCode 273）

## alpha2.40.0 (2026-08-30)
- **控制面板搬进蓝牙设备详情页**：在 Settings 蓝牙设备详情页（BluetoothDeviceDetailsFragment）注入**降噪控制** 与 **功能控制**（空间音频 / 追踪 / 增益 / 指示灯）面板。
  - 纯注入 UI 组件（ControlPanel / DeviceDetailsPanel / CtrlBus），只调用回调，不持有 BLE / Gaia 单例引用，不动主界面 OverviewFragment / Moondrop 链路
  - **空间音频开关**：未连接时三重禁用（`isEnabled` + `isClickable` + `isFocusable` 均按 `state.connected` 置 false），配合 `safeCommand` 命令拦截，彻底避免未连接时能点击 / 切换
  - **降噪控制标题**：加 `topMargin = dp(16)`，不再紧贴卡片顶边
- 版本号升至 **alpha2.40.0**（versionCode 272）

## alpha2.38.10 (2026-08-29)

- **显示层中英文切换补全**：新增语言偏好（跟随系统 / 中文 / English，`cfg` 的 `lang` 键）。
  - **降噪面板**：ANC 模式按钮（关闭 / 降噪 / 透传 / 抗风 + 自适应 / 直播）与设置页 ANC 按钮映射行标签随语言切换
  - **日志弹窗**：日志抓取隐私声明弹窗、进度提示、保存路径提示、日志 ZIP 内分类文件名随语言切换
  - **检查权限页**：权限检测标题、状态头、权限项名称与详情、缺失提示、跳转失败提示随语言切换
  - 纯显示层改动，不改蓝牙 / ANC / 图标 / 逻辑；GMS 原生按钮（确定 / 关闭）保持不动
- 语言偏好通过 exported ContentProvider 供 GMS 进程弹窗跨进程读取

## alpha2.38.9 (2026-08-29)
- **显示层中英文切换（首批）**：概览 / 设置 / 关于 主界面三 Tab 顶部导航与标题、设置项标题与描述、关于页内容随语言切换
- 弹窗电量文字（左耳 / 右耳 / 充电盒 / 电量）随语言切换

## alpha2.38.8 (2026-08-27)
- **布丁 PUDDING（MD-TWS-056）适配**：借助 [PuddingPods](https://github.com/lingbai-rong/PuddingPods) 协议文档完成
  - GAIA v4 over Classic BT RFCOMM/SPP 连接（UUID `00001101-0000-1000-8000-00805f9b34fb`）
  - 5 档 ANC：关闭(0x00) / 自适应降噪(0x01) / 通透(0x02) / 抗风噪(0x03) / 基础降噪(0x04)
  - 三路电量：左耳 + 右耳 + 充电盒（GA2 仅左右耳）
  - 增益控制：低 / 中 / 高三档
  - 指示灯开关控制

## alpha2.38.7 (2026-08-27)
- **修复弹窗电量文字定位**：电量恢复写进 GMS 原生 `subhead`（耳机名副标题），位置天然在名称下方、图标上方，样式系统原生、无自绘背景
- 移除 alpha2.38.5 自绘 overlay + 硬编码 `batteryTopPx=1080`（导致电量跑到屏幕中央并带圆角胶囊背景）
- 电量文字格式恢复 `左耳 X% · 右耳 Y%`；仅当 `subhead` 缺失时才兜底自绘，且回退位置从 `PopupProfile` 屏幕布局库读取，不再散落魔法数字

- **修复弹窗电量显示丢失**：模式条从硬编码坐标改回动态定位（追踪 central_btn），不再覆盖原生 subhead 文本
- **修复弹窗降噪按钮无响应**：模式条动态定位后不再与设置按钮 overlay 重叠拦截触摸
- 模式条定位算法：central_btn 上方 → 设置按钮高度 + gap → 模式条底边，确保三层不重叠
- 修复工作目录整理后 aapt2/qemu 路径断裂（创建 symlink 恢复）



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
