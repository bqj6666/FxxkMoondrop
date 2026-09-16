package com.fxxkmoondrop.secret

import android.util.Log
import org.luckypray.dexkit.DexKitBridge

/**
 * DexKit 定位器
 *
 * 用 dex 内的**稳定特征**（日志格式串、字符串常量）反查被 R8 混淆的类；
 * 尤其是 GMS Fast Pair 弹窗里那些只剩两三个字母的短类名（`dtes`/`dthi`/`dtok`），
 * 它们随上游每次重编译都可能改名，硬编码类名迟早失效。
 *
 * 设计原则（配合铁律「不硬编码型号/版本/路径」）：
 *  - **快路径优先**：先按已知类名加载（零开销）；只有上游改版导致类名失效时，
 *    才启用 DexKit 按特征重新定位，避免每次启动都白白解一遍 dex。
 *  - **只增强、不替代**：定位失败统一回退原硬编码名，任何机型/版本都不会因为
 *    定位器失效而丢功能。
 *  - 单进程单 bridge：DexKitBridge 创建要解 dex，耗时，按 ClassLoader 缓存复用。
 *  - 结果缓存：同一 key 只查一次，查不到也缓存（含 null）。
 *
 * ⚠ 搜索范围说明：Fast Pair 的混淆类是**默认包**（无 package），
 *   因此这里**不调用 searchPackages**（限定包会把它们漏掉）；全量搜一遍只在
 *   快路径失效时才发生，且特征串都经过唯一性验证，命中即准。
 *   GMS base.apk 有 17 个 dex（约 190MB），首次兜底会比小 APK 慢，属预期。
 *
 * 依赖：`org.luckypray:dexkit`（见 app/build.gradle.kts）。
 * 文档：https://luckypray.org/DexKit/
 */
object DexKitLocator {

    private const val TAG = "FxxkMoondrop/DexKit"

    /** 定位项标识。 */
    const val DTES = "dtes"
    const val DTHI = "dthi"

    /** Hearable Controls（GFPS 消息组 0x08）· 官方 ANC 子模块。 */
    const val HEARABLE_ANC = "hearable_anc"

    /** 官方 Hearable Control 管理器（`HearableControlManager`，现名 `dwab`）：
     *  构造它并调 `h()` 即会创建 ANC 子模块 —— 这是绕开惰性注册表门禁的唯一入口。 */
    const val HEARABLE_MGR = "hearable_mgr"

    /** Chimera 模块容器（`dsmg`）：以 `c(context, cls)` 静态方法取模块内单例。 */
    const val MODULE_LOCATOR = "module_locator"

    private var bridge: DexKitBridge? = null
    private var bridgeOwner: ClassLoader? = null

    /** DexKit 彻底不可用（.so 加载失败等）后不再重试，直接回退。 */
    private var unavailable = false

    private val cache = HashMap<String, String?>()

    /** 读取缓存的定位结果（供日志/诊断展示）。 */
    @Synchronized
    fun peek(key: String): String? = cache[key]

    /**
     * 需要 **DexKit 优先**的定位项。
     *
     * 这些是新链路（Hearable Controls）独有的类名，一旦 GMS 版本变动、硬编码名字指到
     * 了别的类，硬编码优先会让错误静默通过（加载成功但成员不存在），DexKit 反而永远
     * 不执行 —— 所以这几项先跑 DexKit、硬编码只当兜底。
     *
     * [DTES] / [DTHI] 于 2026-09-16 实测后**加入本集合**：GMS 26.33.32 里 `dtes` / `dthi`
     * 这两个混淆名已被复用成完全无关的类（`dtes` 无 `f(Context,ZZ)`、字段 `c` 是
     * ConnectionListeningOptions、父类 Object），硬编码优先会「加载成功但是错类」，
     * 于是弹窗的设备名改写与图标注入三个 hook 静默空转（NoSuchMethodException 被 catch 吞）。
     * 结论：硬编码只有放在**最后**才叫兜底；放在最前面就变成了掩盖错误。
     */
    private val DEX_FIRST = setOf(HEARABLE_ANC, HEARABLE_MGR, MODULE_LOCATOR, DTES, DTHI)

    /**
     * 加载 [key] 对应的类：先按 [hardcodedName] 加载（快路径），失败才用 DexKit 按特征定位。
     *
     * 属于 [DEX_FIRST] 的项反过来：DexKit 先定位，定位不到再退回 [hardcodedName]。
     *
     * 两边都失败时抛出**原始**的 ClassNotFoundException，行为与改造前一致，
     * 外层既有的 try/catch 与失败日志不受影响。
     */
    fun resolveOrFallback(cl: ClassLoader, key: String, hardcodedName: String): Class<*> {
        if (key in DEX_FIRST) {
            val located = locate(cl, key)
            if (located != null) {
                runCatching { return Class.forName(located, true, cl) }
                    .onFailure { Log.w(TAG, "DexKit 定位 $located 但加载失败，回退 $hardcodedName", it) }
            } else {
                Log.w(TAG, "DexKit 未定位到 $key，回退硬编码 $hardcodedName")
            }
        }

        runCatching { return Class.forName(hardcodedName, true, cl) }

        val located = locate(cl, key)
        if (located != null && located != hardcodedName) {
            runCatching { return Class.forName(located, true, cl) }
                .onFailure { Log.w(TAG, "DexKit 定位 $located 但加载失败，回退 $hardcodedName", it) }
        }
        return Class.forName(hardcodedName, true, cl)
    }

    /**
     * 定位 `dtok`（Fast Pair Half Sheet 的设备数据类）。
     *
     * 该类内部几乎全是 protobuf 字段，字符串常量只有单字母枚举名，**没有可用的特征串**，
     * 所以改为「由持有它的 [DTES] 字段 `c` 的类型反推」——字段 `c:Ldtok;` 本来就是
     * 现有 hook 代码依赖的成员（`getObjectField(..., "c")`），假设一致、零新增风险。
     */
    fun resolveDtokClassFallback(cl: ClassLoader, hardcodedName: String): Class<*> {
        // 推导优先：设备数据类由 DTES 的字段 `c` 的**类型**现读，完全不依赖混淆名换代。
        // 实测硬编码 `dtok` 在 26.33.32 已退化成一个空接口（真身是 `duzc.c` 的类型 `dviu`），
        // 所以先推导、推不出来才回退硬编码。
        runCatching {
            val dtesName = locate(cl, DTES)
            if (dtesName != null) {
                val dtes = Class.forName(dtesName, false, cl)
                val t = dtes.declaredFields.firstOrNull { it.name == "c" }?.type
                if (t != null && t.name != "java.lang.Object") {
                    Log.d(TAG, "dtok 由 " + dtesName + " 字段 c 推导 -> " + t.name)
                    return t
                }
            }
        }
        Log.w(TAG, "dtok 推导失败，回退硬编码 " + hardcodedName)
        return Class.forName(hardcodedName, true, cl)
    }

    /**
     * 诊断：对全部已知定位项各跑一次 DexKit 并汇总成一行日志。
     *
     * 目的是让「兜底无处不在」可被日志验证 —— 硬编码优先的项平时不会触发定位，
     * [peek] 永远是 null，看不出 DexKit 是否真的能用。
     * 返回结果串，便于调用方顺带打印。
     */
    fun locateAllForDiagnostics(cl: ClassLoader): String {
        val keys = listOf(DTES, DTHI, HEARABLE_ANC, HEARABLE_MGR, MODULE_LOCATOR)
        val parts = keys.map { k -> k + "=" + (locate(cl, k) ?: "<未命中>") }
        val line = parts.joinToString(", ")
        Log.d(TAG, "DexKit 全量定位: " + line)
        return line
    }

    @Synchronized
    private fun ensureBridge(cl: ClassLoader): DexKitBridge? {
        if (unavailable) return null

        bridge?.let { old ->
            if (bridgeOwner === cl && old.isValid) return old
            runCatching { old.close() }
            bridge = null
            bridgeOwner = null
        }

        return try {
            // DexKit 不自带静态加载，需显式 loadLibrary（README 要求）。
            System.loadLibrary("dexkit")
            // useMemoryDexFile=false：走 apkPath 加载，兼容性最好。
            val created = DexKitBridge.create(cl, false)
            bridge = created
            bridgeOwner = cl
            cache.clear()
            Log.d(TAG, "bridge 就绪（valid=${created.isValid}）")
            created
        } catch (t: Throwable) {
            Log.w(TAG, "DexKit 初始化失败，后续全部回退硬编码类名", t)
            unavailable = true
            null
        }
    }

    /**
     * 定位 [key] 对应的实际类名。
     * @return 混淆后的真实类名；无法定位时 null。
     */
    fun locate(cl: ClassLoader, key: String): String? {
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }

        val name = try {
            val b = ensureBridge(cl) ?: return null
            val found = when (key) {
                DTES -> findDtes(b)
                DTHI -> findDthi(b)
                HEARABLE_ANC -> findHearableAnc(b)
                HEARABLE_MGR -> findHearableMgr(b)
                MODULE_LOCATOR -> findModuleLocator(b)
                else -> null
            }
            if (found != null) {
                Log.d(TAG, "定位 $key -> $found")
            } else {
                Log.d(TAG, "定位 $key 未命中，回退硬编码名")
            }
            found
        } catch (t: Throwable) {
            Log.w(TAG, "定位 $key 异常，回退硬编码名", t)
            null
        }

        synchronized(cache) { cache[key] = name }
        return name
    }

    /**
     * 在特征串命中的类里挑出**外部类**。
     *
     * 实测（GMS 26.33.32）：`ActiveNoiseCancellationModule: the ANC is already active, ...` 同时
     * 出现在外部模块类 `dvzo` 和它的匿名内部类 `ActiveNoiseCancellationModule$1` 里，
     * 而 DexKit 返回顺序把 `$1` 排在前面。取错会「类加载成功、成员全找不到」——
     * hook 静默失效，比定位失败更糟，所以这里显式排除名字含 `$` 的内部类。
     *
     * 全部候选都是内部类时返回 null，交由调用方走硬编码兜底。
     */
    private fun pickOuterClass(names: List<String>): String? =
        names.firstOrNull { !it.contains('$') }

    // ==================== 各目标的具体特征 ====================

    /**
     * `dtes`：Google Fast Pair 配对小页（DevicePairingFragment）的控制器。
     *
     * 特征串取自 2026-09 实机 GMS 26.30.32（classes8.dex）反编译结果：
     * `DevicePairingFragment: set account key for incremental pairing` 在全 dex 中**只命中该类**。
     * （同类候选 `... show setup: %s` 命中 3 个类、`... onConnectClick` 命中 5 个类，都不能用。）
     *
     * 目标成员（可用于校验定位是否正确）：`f(Context, boolean, boolean)`、字段 `c`（dtok）。
     */
    private fun findDtes(b: DexKitBridge): String? =
        b.findClass {
            matcher {
                usingStrings("DevicePairingFragment: set account key for incremental pairing")
            }
        }.map { it.name }.let { pickOuterClass(it) }

    /**
     * `dthi`：Fast Pair Half Sheet 的模块 Fragment 基类（弹窗图标注入点）。
     *
     * 特征串取自 2026-09 实机 GMS 26.30.32（classes8.dex）：
     * `HalfSheetModuleFragment: update user account to update UI and create intent` 全 dex 唯一。
     *
     * 目标成员：`O(ImageView, dtok)`、`q(ImageView, dtok, boolean)`。
     */
    private fun findDthi(b: DexKitBridge): String? =
        b.findClass {
            matcher {
                usingStrings("HalfSheetModuleFragment: update user account to update UI and create intent")
            }
        }.map { it.name }.let { pickOuterClass(it) }

    /**
     * 官方 Hearable Control 的 ANC 子模块（`ActiveNoiseCancellationModule`，现名 `dvzo`）。
     *
     * 特征串取自 2026-09 实机 GMS 26.33.32（classes8.dex）的 **baksmali 单类**结果：
     * `ActiveNoiseCancellationModule: Failed to getHearableControlSetting!` 只在 `dvzo.smali` 里。
     *
     * ⚠️ 选串时踩过的坑：原先用的 `... the ANC is already active, skip updating.` 虽然也只有一处，
     * 但它落在 `dvzo` 的**匿名内部类** `ActiveNoiseCancellationModule$1` 里（baksmali 才拆得开，
     * jadx 会把匿名类内联进 dvzo.java，看着像在外部类里）。取错会让 hook 全部落空，
     * 所以这里用 baksmali 逐类核对过归属，并配合 [pickOuterClass] 双保险。
     *
     * 目标成员：构造函数（捕获实例）、继承自 `dwaa` 的 `m(String, ixpv, byte[])`（唯一发包出口）。
     */
    private fun findHearableAnc(b: DexKitBridge): String? =
        b.findClass {
            matcher {
                usingStrings("ActiveNoiseCancellationModule: Failed to getHearableControlSetting!")
            }
        }.map { it.name }.let { pickOuterClass(it) }

    /**
     * 官方 Hearable Control 管理器（`HearableControlManager`，现名 `dwab`）。
     *
     * 特征串取自 2026-09 实机 GMS 26.33.32（classes.dex）反编译结果：
     * `HearableControlManager: failed to get bluetoothAdapter!` 在全 17 个 dex 中只命中该类。
     *
     * 目标成员：4 参构造函数 `(Context, duow, dtyp, iofx)`、`h()`（初始化，内部创建 ANC 子模块）。
     */
    private fun findHearableMgr(b: DexKitBridge): String? =
        b.findClass {
            matcher {
                usingStrings("HearableControlManager: failed to get bluetoothAdapter!")
            }
        }.map { it.name }.let { pickOuterClass(it) }

    /**
     * Chimera 模块容器（`dsmg`）。
     *
     * 特征串取自 2026-09 实机 GMS 26.33.32（classes.dex）：
     * `LocatorContext must not return null Locator: ` 在全 17 个 dex 中只命中该类。
     */
    private fun findModuleLocator(b: DexKitBridge): String? =
        b.findClass {
            matcher {
                usingStrings("LocatorContext must not return null Locator: ")
            }
        }.map { it.name }.let { pickOuterClass(it) }

}
