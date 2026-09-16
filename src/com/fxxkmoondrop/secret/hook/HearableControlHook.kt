package com.fxxkmoondrop.secret.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fxxkmoondrop.secret.DexKitLocator
import com.fxxkmoondrop.secret.HookHelper
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * Hearable Controls（Google Fast Pair 扩展 · Message Stream 消息组 `0x08`）官方链路桥接。
 *
 * ## 为什么需要它
 * 模块过去只在**界面层**工作（自绘面板 + GAIA）。官方这条链其实完整、可驱动：
 *
 * ```
 * dtyp.A(address) ──门禁──> dvzo.e(BluetoothDevice, byte[])   官方 NOTIFY(0x13) 摄取入口
 *                              ├─ 官方解析（NotifyAncStateDataHelper）
 *                              ├─ 写官方 DataStore（HearableControlSetting / dvzf）
 *                              ├─ notifyChange("hearable_control")
 *                              └─ 写设备 metadata(25) <HEARABLE_CONTROL_SLICE>
 * 反向：dwaa.m(String, ixpv, byte[])  所有 hearable 子模块的**唯一发包出口**
 * ```
 *
 * ## 为什么官方链路一次都没跑过
 * 官方 `HearableControlManager` 是**惰性**创建的，注册表上的门禁是：
 *
 * ```
 * dwfg.c(dwab.class, canCreate = dwes.j() && dtxs.d(context, dsmg),
 *        reason = "Haven't receiving session nonce", factory = new dwab(...))
 * ```
 *
 * `dtxs.d()` 要求设备**已送达 session nonce**（Fast Pair Message Stream 握手产物）。
 * 本机耳机不走 Fast Pair → 拿不到 nonce → 管理器永不创建 → `dvzo` 永不构造。
 * 而全 dex 里请求它的只有两处（`dvqn` / `dvvx`），也都挂在 event stream 上。
 *
 * ## 本类的做法
 * 不碰注册表门禁，直接走**官方构造函数 + 官方初始化方法**：
 *
 * 1. 依赖实例（`duow` / `dtyp` / `iofx`）从**官方模块容器**取 ——
 *    静态 `dsmg.c(context, cls)` 要求 Context 能遍历到 `LocatorContext`，
 *    而 GMS Application 不满足（实测 `No locator found in context`），
 *    因此改为 hook 官方注册方法 `dsmg.f(Class, Object)`，被动收集官方注册的单例。
 *    依赖**类型从 `dwab` 构造函数签名现读**，无需硬编码任何混淆类名。
 * 2. 反射构造 `dwab(Context, duow, dtyp, iofx)`，调官方 `h()`
 *    —— 它内部 `new dvzo(...)`，构造函数 hook 顺势捕获 ANC 子模块实例。
 * 3. 从同一实例读出 `g`（`CacheManager`）与 `h`（DataStore 访问器），供门禁放行与状态注入复用官方存储。
 *
 * ## 设计约束（配合铁律）
 * - **不硬编码类名/MAC/型号**：混淆类一律 [DexKitLocator] 按唯一日志特征串定位（硬编码名仅作快路径回退）；
 *   目标设备地址由既有 GAIA 链路动态推送，未设置时本类完全静默、零副作用。
 * - **只增强、不替代**：任何一步失败都只打日志，绝不把异常抛回 GMS。
 * - **最小侵入**：只 hook 与 hearable 相关的三处；其余设备、其余子模块、其余模块一律原样放行。
 */
object HearableControlHook {
    private const val TAG = "FxxkMoondrop/Hearable"

    /** GFPS Message Stream 的 Hearable Controls 消息组（协议事实，= 0x08）。
     *
     *  ⚠️ 它**不在** `dwaa.m` 的 byte[] 里：那里的 byte[] 是消息体（4 字节 ANC 数据），
     *  组号与事件类型由方法参数承载。旧代码用 `payload[0] == 0x08` 判组，条件永远为假
     *  （所幸本机发包路径也从没触发过，所以一直没暴露）。 */
    @Suppress("unused")
    private const val GROUP_HEARABLE = 0x08

    /** GFPS 事件类型 —— 官方枚举 `goko`（实现 `ixpv`）的声明顺序，属协议定义：
     *  `0=EVENT_UNKNOWN 1=EVENT_GET_ANC_STATE 2=EVENT_SET_ANC_STATE 3=EVENT_NOTIFY_ANC_STATE`。
     *
     *  该枚举的实例就是 [hookSendMessage] 拦住的那个方法的第 2 个参数，
     *  ordinal 与协议里的 e 值一一对应，所以按 ordinal 判别即可，
     *  既不用读它的私有字段，也不必硬编码任何混淆名。 */
    private const val EV_GET_ANC_STATE = 1
    private const val EV_SET_ANC_STATE = 2

    /** 弹窗三模式按钮那条既有通道：App 侧 `HeadsetReceiver` 收到即 `AncBridge.setAncMode()`。 */
    private const val ACTION_MODE_CHANGED = "com.fxxkmoondrop.secret.FASTPAIR_MODE_CHANGED"
    private const val EXTRA_MODE = "mode"

    /** 激活重试间隔与次数（GMS 进程起来后 nearby 模块才可用）。 */
    private const val ACTIVATE_INTERVAL_MS = 3000L
    private const val ACTIVATE_TRIES = 20

    /** 本模块自己的应用包名（用于把广播定向发给 App，不广播给全系统）。 */
    private const val PKG_APP = "com.fxxkmoondrop.secret"

    /** 「向 App 索要当前模式」的既有通道：App 侧 AncBridge 收到即回 MODE_STATE。 */
    private const val ACTION_MODE_REQUEST = "com.fxxkmoondrop.secret.FASTPAIR_MODE_REQUEST"

    /** 索要模式的节流间隔（模式未知时才发）。 */
    private const val MODE_REQ_INTERVAL_MS = 10000L

    /** 目标设备地址（大写 MAC）。null = 未确定，此时本类不做任何干预。 */
    @Volatile private var targetAddress: String? = null

    /** 官方 ANC 子模块实例（`ActiveNoiseCancellationModule`）。 */
    @Volatile private var ancModule: Any? = null

    /** 官方缓存管理器实例（`CacheManager`），门禁方法 `A(String)` 在它身上。 */
    @Volatile private var cacheManager: Any? = null

    /** 官方 Hearable Control DataStore 访问器（`dwal`），供状态注入复用官方存储。 */
    @Volatile private var storeAccessor: Any? = null

    /**
     * 官方模块容器注册的实例表：`类 -> 单例`。
     *
     * 通过 hook 官方 `dsmg.f(Class, Object)` 被动收集，等价于官方自己的依赖注入容器。
     */
    private val registered = ConcurrentHashMap<Class<*>, Any>()

    private var module: XposedModule? = null
    private var appCtx: Context? = null
    private var gateHooked = false
    private var sendHooked = false
    private var setEntryHooked = false
    private var mgrSendHooked = false
    private var lastMgrLogMs = 0L

    /** 刚注入过的报文指纹与时间：意图入口用它滤掉自己的 NOTIFY，避免自触发死循环。 */
    @Volatile private var lastInjectPayloadHex: String? = null
    @Volatile private var lastInjectPayloadMs = 0L

    /** 官方 GET 自检只做一次（每个 GMS 进程生命周期）。 */
    @Volatile private var probedGet = false

    // ==================== 第 2 节：协议常量（GFPS，非混淆实现） ====================

    /** Hearable Controls 的 NOTIFY_ANC_STATE 事件码（0x13 = 19）。 */
    private const val CODE_NOTIFY_ANC_STATE = 19

    /** AncVersionCode —— GFPS Hearable Controls 当前版本。 */
    private const val ANC_VERSION = 2

    /** 官方 AncMode 序号（GMS 枚举 `bzch` 声明顺序，属协议定义）：
     *  0=TRANSPARENCY 1=ADAPTIVE 2=ANC_OFF 3=RESERVED_3 4=ANC_ON。 */
    private const val G_TRANSPARENCY = 0
    private const val G_ADAPTIVE = 1
    private const val G_ANC_OFF = 2
    private const val G_ANC_ON = 4

    /** 报文里的模式位 = 1 shl (7 - 序号)，见官方 `gorq.b()` 与 `gorq.r()`。 */
    private fun modeBit(ordinal: Int): Int = 1 shl (7 - ordinal)

    /**
     * 对外宣告的模式集合（supported 与 enabled 同值）。
     *
     * 官方 `bzda.b()` 要求 enabled 数 >= 2 面板才可交互，故四个真实模式一起给。
     * RESERVED_3 的图标与 ANC_ON 完全相同，不是「抗风」，不宣告。
     * ponytail: 设备真实能力域应在第 3 节由 GAIA 应答后收窄，目前是保守全集。
     */
    /**
     * 对外宣告的 GFPS 模式位（决定官方面板出现哪几个档位）。
     *
     * **默认 0（什么都不宣告）**：能力未知时面板就不该出现。
     *
     * 曾经默认给三种「通用」模式，但这对不走本模块控制链的设备是**错的**——
     * 面板会长出点了没反应的空档位（GA2 的「自适应」就是这么来的）。
     * 只有 App 上报设备实际能力（`ui_modes`，见 [updateAdvertised]）后才宣告。
     * 全程由能力探测驱动，不与任何型号绑定。
     */
    @Volatile private var advertisedMask: Int = 0

    /**
     * App 侧 UI 模式 -> Google AncMode 序号。
     *
     * UI 序（`AncBridge`：0关 1降噪 2透传 3抗风 4自适应 5直播）；-1 = GFPS 协议无法表达。
     *
     * 抗风 / 直播给 -1 是**与官方一致**的行为（GFPS 枚举无对应项，Pixel Buds 面板也不给直选），
     * 因此遇到时不注入、面板停留上一个可表达状态，是正确语义而不是缺陷。
     */
    private val UI_TO_GOOGLE = intArrayOf(
            G_ANC_OFF, G_ANC_ON, G_TRANSPARENCY, -1, G_ADAPTIVE, -1)

    /** [UI_TO_GOOGLE] 的逆表：下标 = Google AncMode 序号，值 = App 侧 UI 模式（-1 = 不转）。 */
    private val GOOGLE_TO_UI = intArrayOf(
            /* G_TRANSPARENCY */ 2, /* G_ADAPTIVE */ 4,
            /* G_ANC_OFF */ 0, /* RESERVED_3 */ -1, /* G_ANC_ON */ 1)

    /** 最近一次 App 报上来的 UI 模式（全量程 0-5；-1=未知）。 */
    @Volatile private var uiMode = -1

    /** 注入去重：上一次成功的 Google 序号与时间戳。 */
    @Volatile private var lastInjected = -2
    @Volatile private var lastInjectMs = 0L

    /** 最近一次向 App 索要模式的时刻（节流）。 */
    @Volatile private var lastModeReqMs = 0L
    private const val INJECT_MIN_INTERVAL_MS = 1500L

    /** 由既有 GAIA 链路推送目标设备地址（不硬编码）。传 null 表示清空、恢复完全静默。 */
    fun setTargetAddress(addr: String?) {
        val a = addr?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (a == null) {
            if (targetAddress != null) {
                targetAddress = null; clearAliases()
                Log.d(TAG, "target address -> <null>")
            }
            return
        }
        targetAddress = a
        if (!addAlias(a)) return
        Log.d(TAG, "target address -> " + a + " (别名集 " + aliasesSnapshot() + ")")
        injectState("addr")
    }

    /** 当前是否已拿到官方 ANC 模块实例（供诊断/日志）。 */
    fun isReady(): Boolean = ancModule != null

    /** 官方 FastPair 代码只跑在 GMS 主进程；其余子进程里激活注定失败，
     *  却会白烧 20 次重试并可能重复写官方存储，所以先按进程名限定。 */
    private fun isMainGmsProcess(): Boolean {
        return try {
            java.io.File("/proc/self/cmdline").readText()
                    .trim('\u0000').substringBefore('\u0000') == "com.google.android.gms"
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 在 GMS 进程加载完成后安装。调用方：`FastPairHookEntry.onGmsLoaded`。
     * 全程吞异常 —— 官方类名/结构随版本变动时只降级、不崩宿主。
     */
    fun install(xm: XposedModule, cl: ClassLoader) {
        if (!isMainGmsProcess()) {
            // 官方 FastPair 代码只跑在主进程；其余 gms 子进程里激活注定失败，
            // 却会白烧 20 次重试并可能重复写官方存储。
            Log.d(TAG, "非主 GMS 进程，官方桥接不启用")
            return
        }
        module = xm
        try {
            val ancCls = DexKitLocator.resolveOrFallback(cl, DexKitLocator.HEARABLE_ANC, "dvzo")
            Log.d(TAG, "ANC module class = " + ancCls.name
                    + " (dexkit=" + DexKitLocator.peek(DexKitLocator.HEARABLE_ANC) + ")")
            // 三个 hook 各自独立：任何一个失败都不能拖累后面的（否则点击入口会静默缺失）。
            runCatching { hookConstructor(xm, ancCls) }
                .onFailure { Log.d(TAG, "hookConstructor 失败: " + unwrap(it)) }
            runCatching { hookSendMessage(xm, ancCls) }
                .onFailure { Log.d(TAG, "hookSendMessage 失败: " + unwrap(it)) }
            runCatching { hookSetEntry(xm, ancCls) }
                .onFailure { Log.d(TAG, "hookSetEntry 失败: " + unwrap(it)) }
            runCatching { hookManagerSend(xm, cl) }
                .onFailure { Log.d(TAG, "hookManagerSend 失败: " + unwrap(it)) }
        } catch (t: Throwable) {
            Log.d(TAG, "install hook 阶段失败（降级为不干预）: " + t)
        }
        try {
            val locatorCls = DexKitLocator.resolveOrFallback(cl, DexKitLocator.MODULE_LOCATOR, "dsmg")
            hookRegistry(xm, locatorCls)
        } catch (t: Throwable) {
            Log.d(TAG, "install 注册表阶段失败: " + t)
        }

        // 诊断：确认 DexKit 对**全部**定位项都能命中。
        // 硬编码优先的项平时不会触发定位，peek() 永远为 null，看不出 DexKit 是否可用；
        // 而 Hearable 三项已改为 DexKit 优先，必须能证明它真的定位成功而不是一直在回退。
        Thread {
            try {
                DexKitLocator.locateAllForDiagnostics(cl)
            } catch (t: Throwable) {
                Log.d(TAG, "DexKit 全量定位失败: " + t)
            }
        }.apply {
            isDaemon = true
            name = "FxxkHearableDexDiag"
        }.start()

        scheduleActivate(cl)
    }

    // ==================== 1. 抓实例 ====================

    /**
     * hook 官方 ANC 子模块的构造函数，拿到实例；顺带从其父类字段 `g`（`CacheManager`）安装门禁 hook。
     * 构造函数由官方管理器 `h()` 调用时触发。
     */
    private fun hookConstructor(xm: XposedModule, ancCls: Class<*>) {
        val ctors = ancCls.declaredConstructors
        if (ctors.isEmpty()) {
            Log.d(TAG, "ANC module 无构造函数，跳过")
            return
        }
        for (ctor in ctors) {
            try {
                xm.hook(ctor).intercept { chain ->
                    chain.proceed()
                    try {
                        val inst = chain.thisObject
                        if (inst != null && ancCls.isInstance(inst)) {
                            ancModule = inst
                            val cache = HookHelper.getObjectField(inst, "g")
                            if (cache != null) {
                                cacheManager = cache
                                hookGate(cache.javaClass)
                            }
                            Log.d(TAG, "官方 ANC 模块实例已捕获 (cache="
                                    + (cache?.javaClass?.name ?: "<null>") + ")")
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "构造函数捕获失败: " + t)
                    }
                    null
                }
            } catch (t: Throwable) {
                Log.d(TAG, "hook 构造函数失败: " + t)
            }
        }
        Log.d(TAG, "已 hook " + ctors.size + " 个构造函数")
    }

    // ==================== 2. 放开设备门禁 ====================

    /**
     * hook 官方缓存管理器的 `A(String)boolean`（等价于「设备是否在 Fast Pair 缓存中」）。
     *
     * 官方 ANC 子模块摄取状态的第一行就是 `if (!cache.A(address)) return;`，
     * 本机耳机不在缓存里 → 永远提前返回。这里**只对目标设备**返回 true，
     * 其余设备一律走原逻辑，不影响任何真 Fast Pair 设备。
     */
    @Synchronized
    private fun hookGate(cacheCls: Class<*>) {
        if (gateHooked) return
        val xm = module ?: return
        val m = cacheCls.declaredMethods.firstOrNull {
            it.name == "A" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.returnType == Boolean::class.javaPrimitiveType ||
                            it.returnType == java.lang.Boolean::class.java)
        }
        if (m == null) {
            Log.d(TAG, "未找到门禁方法 A(String)，跳过（功能降级）")
            return
        }
        xm.hook(m).intercept { chain ->
            val addr = chain.args.getOrNull(0) as? String
            if (addr != null && aliasesSnapshot().contains(addr.trim().uppercase())) {
                Log.d(TAG, "门禁放行: " + addr)
                return@intercept java.lang.Boolean.TRUE
            }
            chain.proceed()
        }
        gateHooked = true
        Log.d(TAG, "门禁已 hook: " + cacheCls.name + ".A(String)")
    }

    // ==================== 3. 观察官方发包出口（本节仅记录，不拦截） ====================

    /**
     * hook 父类 `dwaa.m(String, ixpv, byte[])` —— 所有 hearable 子模块的唯一发包出口。
     *
     * 官方经这条路要发给耳机的 GFPS 报文，在本机**必然石沉大海**：这副耳机不走
     * Fast Pair，没有 Message Stream 会话，报文无人应答。于是把官方的意图
     * 翻译给真正能控制耳机的那条链路（App 的 GAIA）—— 见 [forwardOfficialSend]。
     *
     * 原调用**照旧执行**（铁律「只增强、不替代」）：多一条无效报文无害，
     * 而新能力由 GAIA 转发提供，官方那条链的既有行为完全不变。
     */
    private fun hookSendMessage(xm: XposedModule, ancCls: Class<*>) {
        if (sendHooked) return
        val base = ancCls.superclass ?: return
        val m = base.declaredMethods.firstOrNull {
            it.name == "m" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[2] == ByteArray::class.java
        }
        if (m == null) {
            Log.d(TAG, "未找到发包出口 m(String, ?, byte[])，跳过")
            return
        }
        xm.hook(m).intercept { chain ->
            try {
                if (ancCls.isInstance(chain.thisObject)) {
                    // 参数 0 = 目标 MAC；参数 1 = 事件类型（官方枚举 `goko`，实现 `ixpv`）；
                    // 参数 2 = 消息体（4 字节：版本 / supported / enabled / 模式位）。
                    val addr = chain.args.getOrNull(0) as? String
                    val ev = (chain.args.getOrNull(1) as? Enum<*>)?.ordinal ?: -1
                    val payload = chain.args.getOrNull(2) as? ByteArray
                    forwardOfficialSend(addr, ev, payload)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "发包转发失败: " + t)
            }
            chain.proceed()
        }
        sendHooked = true
        Log.d(TAG, "发包出口已 hook: " + base.name + ".m(String, ?, byte[])")
    }

    /**
     * hook 官方管理器（`HearableControlManager`，dwab）里**真正发送 GFPS 报文**的方法。
     *
     * 为什么还要这一层：实测音量面板三个档位按钮的点击会走
     * `HearableControlManager.sendMessageViaEventStream`，而它
     * **不经过**子模块的 `dvzo.in(...)`（那条只写 dataStore），
     * 也**不经过** `dwaa.m`（那条被「等上一次 SET 响应」永久挡住）。
     * 只有在这里接住，才能拿到用户真实的点击意图。
     *
     * 按参数结构筛选（同时含 byte[] 与 int），不依赖混淆名；
     * 原调用照旧执行（只增强、不替代）。
     */
    private fun hookManagerSend(xm: XposedModule, cl: ClassLoader) {
        if (mgrSendHooked) return
        val mgrCls = runCatching {
            DexKitLocator.resolveOrFallback(cl, DexKitLocator.HEARABLE_MGR, "dwab")
        }.getOrNull()
        if (mgrCls == null) {
            Log.d(TAG, "管理器类未定位，跳过发送出口")
            return
        }
        val methods = mgrCls.declaredMethods.filter { m ->
            m.parameterTypes.any { it == ByteArray::class.java } &&
                    m.parameterTypes.any { it == Integer.TYPE }
        }
        if (methods.isEmpty()) {
            Log.d(TAG, "管理器发送出口无候选方法，跳过")
            return
        }
        for (m in methods) {
            runCatching {
                xm.hook(m).intercept { chain ->
                    try {
                        var ev = -1
                        var payload: ByteArray? = null
                        var addr: String? = null
                        for (a in chain.args) {
                            when (a) {
                                is Int -> ev = a
                                is ByteArray -> payload = a
                                is String -> if (a.length >= 17 && a.contains(":")) addr = a
                                is BluetoothDevice -> addr = a.address
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (ev == CODE_NOTIFY_ANC_STATE && now - lastMgrLogMs > 1500) {
                            lastMgrLogMs = now
                            Log.d(TAG, "管理器出口 " + m.name + " ev=" + ev
                                    + " addr=" + addr + " payload="
                                    + (payload?.let { hex(it) } ?: "-"))
                        }
                        if (ev == CODE_NOTIFY_ANC_STATE && payload != null && payload.size >= 4) {
                            // 自己注入的报文不转发（防自触发）。
                            if (!isSelfInject(payload)) {
                                forwardEntryIntent(addr ?: aliasesSnapshot().firstOrNull(), payload)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "管理器出口转发失败: " + t)
                    }
                    chain.proceed()
                }
            }.onFailure { Log.d(TAG, "管理器出口 hook 失败(" + m.name + "): " + unwrap(it)) }
        }
        mgrSendHooked = true
        Log.d(TAG, "管理器发送出口已 hook: " + mgrCls.name + " 候选=" + methods.size)
    }

    /**
     * hook 官方**意图入口** `dvzo.in(BluetoothDevice, int, byte[])`（eventCode 19）。
     *
     * 为什么光有 [hookSendMessage] 不够：实测音量面板 / 设置侧面板的点击先落到这里
     * 更新官方 dataStore，随后官方**等上一次 SET 的响应**再决定是否发包 ——
     * 本机没有 GFPS Message Stream（EventStreamManager: No available
     * EventStreamMedium），这个响应永远等不到，于是 `dwaa.m` 那条发包出口
     * 根本不会被调用，现象就是「点了没反应」。在意图入口接住即可绕开这道闸门。
     *
     * 自己注入的 NOTIFY 走同一入口，按「报文指纹 + 时间窗」和「与已同步状态相同」
     * 两重过滤，不会自触发、也不会把耳机切回旧状态。
     */
    private fun hookSetEntry(xm: XposedModule, ancCls: Class<*>) {
        if (setEntryHooked) return
        val m = ancCls.declaredMethods.firstOrNull {
            it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java &&
                    it.parameterTypes[1] == Integer.TYPE &&
                    it.parameterTypes[2] == ByteArray::class.java
        }
        if (m == null) {
            Log.d(TAG, "未找到官方意图入口 (BluetoothDevice,int,byte[])，跳过")
            return
        }
        xm.hook(m).intercept { chain ->
            try {
                val ev = chain.args.getOrNull(1) as? Int ?: -1
                val payload = chain.args.getOrNull(2) as? ByteArray
                val dev = chain.args.getOrNull(0) as? BluetoothDevice
                // 探针：我们自己的注入也走这个方法，命中即证明 hook 真的挂上了。
                Log.d(TAG, "意图入口命中 ev=" + ev + " self="
                        + (payload != null && isSelfInject(payload)))
                if (ev == CODE_NOTIFY_ANC_STATE && payload != null && !isSelfInject(payload)) {
                    forwardEntryIntent(dev?.address, payload)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "意图入口转发失败: " + t)
            }
            chain.proceed()
        }
        setEntryHooked = true
        Log.d(TAG, "官方意图入口已 hook: " + ancCls.name + ".(BluetoothDevice,int,byte[])")
    }

    /** 3 秒内与刚注入过的报文一字不差 -> 是我们自己写的，不再转发。 */
    private fun isSelfInject(payload: ByteArray): Boolean {
        val h = hex(payload)
        return h == lastInjectPayloadHex &&
                System.currentTimeMillis() - lastInjectPayloadMs < 3000
    }

    /** 把意图入口的 GFPS ANC 报文翻成 App 的 GAIA 切换请求。 */
    private fun forwardEntryIntent(addr: String?, payload: ByteArray) {
        if (addr == null) return
        if (!aliasesSnapshot().contains(addr.uppercase())) return
        if (payload.size < 4) {
            Log.d(TAG, "意图入口报文过短，忽略: addr=" + addr)
            return
        }
        val mask = payload[3].toInt() and 0xFF
        val g = googleOrdinalFromMask(mask)
        val ui = if (g in GOOGLE_TO_UI.indices) GOOGLE_TO_UI[g] else -1
        Log.d(TAG, "官方意图 SET ANC: addr=" + addr + " google=" + g + " ui=" + ui
                + " payload=" + hex(payload))
        if (ui < 0) {
            Log.d(TAG, "意图无对应 App 模式，不转发（与官方一致）")
            return
        }
        // 与官方已同步的状态相同 -> 大概率是官方读回后的写回，不是用户点击，不转发。
        if (g == lastInjected) {
            Log.d(TAG, "意图与已同步状态一致，不转发")
            return
        }
        val ctx = appCtx ?: return
        ctx.sendBroadcast(Intent(ACTION_MODE_CHANGED).setPackage(PKG_APP).putExtra(EXTRA_MODE, ui))
        Log.d(TAG, "意图已转 GAIA: ui=" + ui)
    }

    // ==================== 第 3 节：官方意图 -> 既有 GAIA 链路 ====================

    /**
     * 把官方要发的 GFPS 报文转成我们这条链能做的事。
     *
     * 只处理**已登记为目标设备**的地址；其余设备（别的耳机、键鼠、车机）一律
     * 原样放行、零介入 —— 这是「不误伤」的边界。
     *
     * - [EV_SET_ANC_STATE]：有人（官方 Slice／面板／AIDL）请求切换模式 ->
     *   解析出目标模式 -> 复用弹窗三模式按钮的既有通道交给 App，
     *   由 App 走 GAIA 真正下发给耳机。耳机**第一次**能被官方面板驱动。
     * - [EV_GET_ANC_STATE]：官方在问耳机当前状态，而耳机不会答 ->
     *   直接把 App 的真实状态喂回官方（[injectState] 内部就是走官方 NOTIFY 入口），
     *   等于替耳机答话，官方的存储与 Slice 因此始终有正确状态。
     */
    private fun forwardOfficialSend(addr: String?, ev: Int, payload: ByteArray?) {
        if (addr == null || ev < 0) return
        if (!aliasesSnapshot().contains(addr.uppercase())) return
        when (ev) {
            EV_SET_ANC_STATE -> {
                val mask = payload?.takeIf { it.size >= 4 }?.get(3)?.toInt()?.and(0xFF)
                if (mask == null) {
                    Log.d(TAG, "官方 SET ANC 报文过短，忽略: addr=" + addr)
                    return
                }
                val g = googleOrdinalFromMask(mask)
                val ui = if (g in GOOGLE_TO_UI.indices) GOOGLE_TO_UI[g] else -1
                Log.d(TAG, "官方请求 SET ANC: addr=" + addr + " google=" + g + " ui=" + ui
                        + " payload=" + hex(payload))
                if (ui < 0) {
                    Log.d(TAG, "SET ANC 无对应 App 模式，不转发（与官方一致）")
                    return
                }
                val ctx = appCtx ?: return
                ctx.sendBroadcast(Intent(ACTION_MODE_CHANGED)
                        .setPackage(PKG_APP)
                        .putExtra(EXTRA_MODE, ui))
                Log.d(TAG, "SET ANC 已转 GAIA: ui=" + ui)
            }
            EV_GET_ANC_STATE -> {
                Log.d(TAG, "官方请求 GET ANC，用 App 真实状态代答: addr=" + addr)
                injectState("official_get")
            }
        }
    }

    /** 报文第 4 字节的模式位（`1 shl (7 - 序号)`）-> Google AncMode 序号；非单一位返回 -1。 */
    private fun googleOrdinalFromMask(mask: Int): Int {
        if (mask == 0 || (mask and (mask - 1)) != 0) return -1
        return 7 - Integer.numberOfTrailingZeros(mask)
    }

    /**
     * 请官方**主动**发一次 GET ANC（官方 `dvzo.j(BluetoothDevice)`，本类里唯一的
     * `(BluetoothDevice)V`，按签名结构定位）。
     *
     * 为什么需要：官方这条链本来只在 Fast Pair 会话建立时才问耳机状态，本机永远等不到
     * 那一刻，于是整条链一直静默。代它问一次，官方的 GET 一发出就会落进上面的转发层，
     * 被用 App 的真实状态代答 —— 链路由此从「等不到」变成「立刻有」。
     */
    private fun probeOfficialGet(from: String) {
        if (probedGet) return
        val mod = ancModule ?: return
        val a = aliasesSnapshot().firstOrNull() ?: return
        val dev = remoteDevice(a) ?: return
        val m = mod.javaClass.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == BluetoothDevice::class.java
        }
        if (m == null) {
            Log.d(TAG, "未找到官方 GET 入口，跳过自检")
            return
        }
        try {
            m.isAccessible = true
            m.invoke(mod, dev)
            probedGet = true
            Log.d(TAG, "已请官方发 GET ANC(" + from + ") addr=" + a)
        } catch (t: Throwable) {
            Log.d(TAG, "请官方发 GET ANC 失败: " + unwrap(t))
        }
    }

    // ==================== 4. 官方模块容器注册表 ====================

    /**
     * hook 官方容器注册方法 `dsmg.f(Class, Object)`，被动收集官方注册的模块单例。
     *
     * 这是等价于官方依赖注入容器的读法：不依赖任何 Context 形态，
     * 也不需要在静态 `dsmg.c()` 上满足 LocatorContext 前提。
     */
    private fun hookRegistry(xm: XposedModule, locatorCls: Class<*>) {
        val m = locatorCls.declaredMethods.firstOrNull {
            it.name == "f" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Class::class.java
        }
        if (m == null) {
            Log.d(TAG, "未找到注册方法 f(Class, Object)，跳过")
            return
        }
        xm.hook(m).intercept { chain ->
            try {
                val cls = chain.args.getOrNull(0) as? Class<*>
                val obj = chain.args.getOrNull(1)
                if (cls != null && obj != null) {
                    registered.putIfAbsent(cls, obj)
                }
            } catch (_: Throwable) {
            }
            chain.proceed()
        }
        Log.d(TAG, "官方注册表已 hook: " + locatorCls.name + ".f(Class, Object)")
    }

    // ==================== 5. 主动激活官方管理器 ====================

    /**
     * 后台线程重试激活。
     *
     * GMS 进程刚起时 nearby 模块容器还没就绪、官方单例尚未注册，
     * 因此按固定间隔重试若干次；一旦拿到 ANC 模块实例就退出。
     */
    private fun scheduleActivate(cl: ClassLoader) {
        Thread {
            var i = 0
            while (true) {
                i++
                if (ancModule != null) {
                    Log.d(TAG, "已激活，停止重试")
                    return@Thread
                }
                try {
                    if (activateOnce(cl)) {
                        Log.d(TAG, "官方管理器激活成功（第 " + i + " 次）")
                        // 自检：install 阶段的日志在 GMS 进程极早期会丢（logd 尚未就绪），
                        // 这里（延迟几秒后）才能可靠地看到三个 hook 到底装没装上。
                        Log.d(TAG, "自检 gate=" + gateHooked + " send=" + sendHooked
                                + " entry=" + setEntryHooked + " mgr=" + mgrSendHooked
                                + " module=" + (ancModule != null))
                        injectState("activate")
                        return@Thread
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "激活尝试 " + i + " 异常: " + unwrap(t))
                }
                // 前 20 次 3 秒一探（等 Fast Pair 模块加载）；之后降到 30 秒，
                // 长期挂着等下一次设备事件（耳机重连等）。原来 20 次一过就永久放弃，
                // 于是那一次 GMS 重启后面板一直显示旧模式、点击也无效。
                val gap = if (i <= ACTIVATE_TRIES) ACTIVATE_INTERVAL_MS else 30000L
                if (i == ACTIVATE_TRIES + 1) {
                    Log.d(TAG, "激活尚未成功，转为 30 秒一探继续等待"
                            + "（已收集官方单例 " + registered.size + " 个）")
                }
                try {
                    Thread.sleep(gap)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "FxxkHearableActivate"
        }.start()
    }

    /**
     * 尝试一次激活：
     * 构造官方 `HearableControlManager(Context, duow, dtyp, iofx)` 并调其 `h()`。
     *
     * 依赖实例优先取自官方注册表 [registered]（类型由构造函数签名现读），
     * 取不到再退回静态 `dsmg.c(context, cls)`。
     */
    private fun activateOnce(cl: ClassLoader): Boolean {
        if (ancModule != null) return true
        val ctx = gmsContext(cl) ?: return false

        val mgrCls = DexKitLocator.resolveOrFallback(cl, DexKitLocator.HEARABLE_MGR, "dwab")
        val ctor = mgrCls.declaredConstructors.firstOrNull { it.parameterTypes.size == 4 }
                ?: run {
                    Log.d(TAG, "未找到 4 参构造函数")
                    return false
                }

        val args = ctor.parameterTypes.map { p ->
            if (Context::class.java.isAssignableFrom(p)) ctx
            else registered[p] ?: lookupViaLocator(cl, ctx, p)
        }
        if (args.any { it == null }) {
            Log.d(TAG, "依赖实例未就绪: " + ctor.parameterTypes.joinToString { it.simpleName }
                    + " (已收集 " + registered.size + " 个官方单例)")
            return false
        }

        ctor.isAccessible = true
        val mgr = try {
            ctor.newInstance(*args.toTypedArray())
        } catch (t: Throwable) {
            Log.d(TAG, "构造 dwab 失败: " + unwrap(t))
            return false
        }
        Log.d(TAG, "官方管理器已构造: " + mgr.javaClass.simpleName)

        // 官方管理器字段：h = DataStore 访问器(dwal)，g = CacheManager
        storeAccessor = HookHelper.getObjectField(mgr, "h")
        val cache = HookHelper.getObjectField(mgr, "g")
        if (cache != null && !gateHooked) {
            cacheManager = cache
            hookGate(cache.javaClass)
        }

        // 官方初始化：内部 new dvzo(...)，由构造函数 hook 捕获
        try {
            HookHelper.callMethod(mgr, "h")
        } catch (t: Throwable) {
            Log.d(TAG, "调用官方 h() 失败: " + unwrap(t))
        }
        Log.d(TAG, "官方 h() 已调用，ancModule=" + (ancModule != null)
                + " store=" + (storeAccessor?.javaClass?.name ?: "<null>"))
        return ancModule != null
    }

    /** 兜底：走静态 `dsmg.c(context, cls)`（需要 LocatorContext，实测通常不可用）。 */
    private fun lookupViaLocator(cl: ClassLoader, ctx: Context, p: Class<*>): Any? {
        return try {
            val locatorCls = DexKitLocator.resolveOrFallback(cl, DexKitLocator.MODULE_LOCATOR, "dsmg")
            HookHelper.callStaticMethod(locatorCls, "c", ctx, p)
        } catch (_: Throwable) {
            null
        }
    }

    /** 取 GMS 应用 Context。 */
    private fun gmsContext(cl: ClassLoader): Context? {
        appCtx?.let { return it }
        try {
            val at = Class.forName("android.app.ActivityThread", true, cl)
            val app = HookHelper.callStaticMethod(at, "currentApplication")
            if (app is Context) {
                appCtx = app
                return app
            }
        } catch (t: Throwable) {
            Log.d(TAG, "取 GMS context 失败: " + t)
        }
        return null
    }

    /** 解包反射异常链，取出真实 cause（否则只能看到 InvocationTargetException）。 */
    private fun unwrap(t: Throwable): String {
        var c: Throwable = t
        var guard = 0
        while (c is java.lang.reflect.InvocationTargetException && c.cause != null && guard < 8) {
            c = c.cause!!
            guard++
        }
        return c.javaClass.name + ": " + c.message
    }

    // ==================== 7. 状态注入：App -> 官方 DataStore ====================

    /**
     * App 侧 ANC 模式变化入口（既有广播 ACTION_MODE_STATE 的接收器接线）。
     *
     * 传**全量程** 0-5：弹窗高亮那段有历史 0-3 限制，不该牵制官方面板。
     */
    fun onAncMode(mode: Int) {
        if (mode == uiMode) return
        uiMode = mode
        injectState("MODE_STATE")
    }

    /** App 广播 ANC 能力可用（status 1）时补推一次；无能力则没有状态可写。 */
    fun onAncAvailability(status: Int, uiModes: IntArray? = null) {
        if (uiModes != null) updateAdvertised(uiModes)
        if (status == 1) injectState("ancReady")
    }

    /**
     * 按 App 上报的**实际可用 UI 档位**收窄对外宣告。
     *
     * App 侧 `GaiaBleClient.supportedUiModes()` 依据能力探测出的 GAIA 路径给出档位集合，
     * 这里把它翻译成 GFPS 模式位 —— 于是不同耳机（4 档 / 6 档 / 仅开关）各自只呈现自己
     * 真能执行的那些模式，多设备适配天然成立，且无需任何型号表。
     */
    private fun updateAdvertised(uiModes: IntArray) {
        var mask = 0
        for (ui in uiModes) {
            val g = if (ui in UI_TO_GOOGLE.indices) UI_TO_GOOGLE[ui] else -1
            if (g >= 0) mask = mask or modeBit(g)
        }
        if (mask == 0 || mask == advertisedMask) return
        advertisedMask = mask
        lastInjected = -2   // 让下一次注入必然重发（宣告变了）
        Log.d(TAG, "宣告已按设备能力更新: ui=" + uiModes.joinToString(",")
                + " mask=0x" + Integer.toHexString(mask))
        injectState("capability")
    }

    /**
     * 把当前降噪状态推进**官方自己的存储**。
     *
     * 不碰官方私有字段、不自建 DataStore：只组一个 4 字节合法 NOTIFY 报文，
     * 调官方入口 `dvzo.in(BluetoothDevice, int, byte[])`。该方法内部判
     * `eventCode == 19` 后 `executor.execute(() -> e(device, payload))`，
     * 于是线程、门禁、解析、写存储、notifyChange、metadata 全是官方逻辑。
     *
     * 未激活 / 未确定目标设备 / 模式无法用 GFPS 表达时静默返回，零副作用。
     */
    @Synchronized
    private fun injectState(from: String) {
        try {
            val mod = ancModule ?: return
            if (aliasesSnapshot().isEmpty()) return
            var ui = uiMode
            if (ui < 0) {
                ui = FastPairHookEntry.sLastMode
                if (ui >= 0) {
                    uiMode = ui
                } else {
                    // 模式还未知（GMS 重启后 App 可能还没推过），主动问一次，别干等。
                    requestAppMode(from)
                    return
                }
            }
            if (ui !in UI_TO_GOOGLE.indices) {
                Log.d(TAG, "跳过注入($from): UI 模式未知 ui=" + ui)
                return
            }
            val g = UI_TO_GOOGLE[ui]
            if (g < 0) {
                // 与官方一致：抗风 / 直播在 GFPS AncMode 里本就没有对应项，面板也不给直选，
                // 所以此时不注入、面板停留在上一个可表达状态是正确行为，不是缺陷。
                Log.d(TAG, "UI 模式 " + ui + " 无 GFPS AncMode 对应（与官方一致，不注入）")
                return
            }
            val now = System.currentTimeMillis()
            if (g == lastInjected && now - lastInjectMs < INJECT_MIN_INTERVAL_MS) return
            // 能力外的模式绝不注入：否则官方面板会冒出一个点了没反应的档位
            if ((advertisedMask and modeBit(g)) == 0) {
                Log.d(TAG, "Google 模式 " + g + " 不在设备能力内，不注入（mask=0x"
                        + Integer.toHexString(advertisedMask) + "）")
                return
            }
            val payload = byteArrayOf(
                    ANC_VERSION.toByte(),
                    advertisedMask.toByte(),
                    advertisedMask.toByte(),
                    modeBit(g).toByte())
            // 官方入口按签名结构定位：本类里 (BluetoothDevice, int, byte[]) 只有这一个
            val m = mod.javaClass.declaredMethods.firstOrNull {
                it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == BluetoothDevice::class.java &&
                        it.parameterTypes[1] == Integer.TYPE &&
                        it.parameterTypes[2] == ByteArray::class.java
            } ?: run {
                Log.d(TAG, "未找到官方 NOTIFY 入口，功能降级")
                return
            }
            // 逐个别名注入：官方以地址作存储键，而它到底按经典口还是 LE 身份口查/写
            // 不可预知，两条各写一份同值、哪种查法都命中；写的是官方自己的路径，无副作用。
            lastInjectPayloadHex = hex(payload)
            lastInjectPayloadMs = now
            var n = 0
            for (a in aliasesSnapshot()) {
                val dev = remoteDevice(a) ?: continue
                m.invoke(mod, dev, CODE_NOTIFY_ANC_STATE, payload)
                n++
                Log.d(TAG, "NOTIFY 已注入官方: ui=" + ui + " google=" + g
                        + " addr=" + dev.address
                        + " payload=" + hex(payload) + " from=" + from)
            }
            if (n == 0) {
                Log.d(TAG, "跳过注入($from): 别名 " + aliasesSnapshot() + " 都取不到 BluetoothDevice")
                return
            }
            lastInjected = g
            lastInjectMs = now
            // 模式已知且已注入 —— 此时请官方发一次 GET ANC，它会被 [forwardOfficialSend]
            // 接到并用刚注入的真实状态代答，形成「官方问 -> 我们答」的完整闭环。
            probeOfficialGet(from)
        } catch (t: Throwable) {
            Log.d(TAG, "注入失败: " + unwrap(t))
        }
    }

    /**
     * 向 App 索要当前降噪模式。
     *
     * App 侧本来就有这个应答器（`AncBridge`：收到 MODE_REQUEST 就回 MODE_STATE），
     * 原来只有官方弹窗路径会问一次。GMS 重启、耳机又没断连时，模式无从得知，
     * 注入会一直静默跳过 —— 这里补上这一问，模式回来后由 MODE_STATE 接收器继续注入。
     *
     * 节流 [MODE_REQ_INTERVAL_MS]：同一次连接里问一次就够，避免反复骚扰 App。
     */
    private fun requestAppMode(from: String) {
        val now = System.currentTimeMillis()
        if (now - lastModeReqMs < MODE_REQ_INTERVAL_MS) return
        val ctx = appCtx ?: return
        lastModeReqMs = now
        try {
            val i = Intent(ACTION_MODE_REQUEST)
            i.setPackage(PKG_APP)
            ctx.sendBroadcast(i)
            Log.d(TAG, "模式未知(" + from + ")，已向 App 索要当前模式")
        } catch (t: Throwable) {
            Log.d(TAG, "索要模式失败: " + unwrap(t))
        }
    }

    // ---- 地址别名：同一副耳机在系统里可能有多个呈现地址 ----
    // 经典口（GAIA 走这个）与 LE 身份口是不同 MAC，官方按哪一个查缓存 / 写存储不可预知。
    // 全部登记为别名：门禁对每一个都放行，注入时逐个都写一遍。
    private val aliasLock = Any()
    private val addrAliases = LinkedHashSet<String>()

    /** 当前别名集合所属的耳机名（用于识别「换了一副耳机」）。 */
    private var aliasName: String? = null

    private fun aliasesSnapshot(): List<String> = synchronized(aliasLock) { addrAliases.toList() }

    /** 登记一个呈现地址；返回是否为新别名。别名只对本次连接会话有意义。 */
    private fun addAlias(a: String): Boolean {
        // 别名集合的语义是「**同一副耳机**的多个呈现地址」（经典口 / LE 身份口）。
        // 换一副耳机时必须换一组，否则 A 的状态会被写到 B 上、A 的切档请求会被
        // 转发给正连着的 B。判据用系统里的蓝牙名（数据驱动，不看 MAC 规律、不认型号）：
        // 名字不同 = 不同设备。
        val name = deviceName(a)
        synchronized(aliasLock) {
            if (name != null && aliasName != null && name != aliasName) {
                addrAliases.clear()
                aliasName = name
                // 换了一副耳机：上一副的能力宣告不能带过来（否则面板会显示
                // 新耳机根本不支持的档位）。清空后等新耳机上报自己的能力。
                advertisedMask = 0
                lastInjected = -2
                Log.d(TAG, "检测到换设备: " + name + "，已清空宣告等待能力上报")
            } else if (aliasName == null) {
                aliasName = name
            }
            if (addrAliases.contains(a)) return false
            if (addrAliases.size >= 3) addrAliases.clear()
            addrAliases.add(a)
            return true
        }
    }

    /** 系统里该地址的蓝牙名（取不到返回 null）。 */
    private fun deviceName(addr: String): String? = try {
        remoteDevice(addr)?.name?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun clearAliases() {
        synchronized(aliasLock) {
            addrAliases.clear()
            aliasName = null
        }
    }

    /** 由 MAC 取 BluetoothDevice（已配对设备，不发起任何连接动作）。 */
    private fun remoteDevice(addr: String): BluetoothDevice? {
        return try {
            val ad = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!ad.isEnabled) return null
            ad.getRemoteDevice(addr)
        } catch (t: Throwable) {
            Log.d(TAG, "getRemoteDevice fail: " + t)
            null
        }
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 3)
        for (x in b) {
            sb.append(String.format("%02X", x))
            sb.append(' ')
        }
        return sb.toString().trim()
    }
}
