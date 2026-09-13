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

    private var bridge: DexKitBridge? = null
    private var bridgeOwner: ClassLoader? = null

    /** DexKit 彻底不可用（.so 加载失败等）后不再重试，直接回退。 */
    private var unavailable = false

    private val cache = HashMap<String, String?>()

    /** 读取缓存的定位结果（供日志/诊断展示）。 */
    @Synchronized
    fun peek(key: String): String? = cache[key]

    /**
     * 先按 [hardcodedName] 加载（快路径），失败才用 DexKit 按特征定位。
     *
     * 两边都失败时抛出**原始**的 ClassNotFoundException，行为与改造前一致，
     * 外层既有的 try/catch 与失败日志不受影响。
     */
    fun resolveOrFallback(cl: ClassLoader, key: String, hardcodedName: String): Class<*> {
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
        runCatching { return Class.forName(hardcodedName, true, cl) }

        runCatching {
            val dtesName = locate(cl, DTES) ?: return@runCatching
            val dtes = Class.forName(dtesName, false, cl)
            val f = dtes.declaredFields.firstOrNull { it.name == "c" } ?: return@runCatching
            val t = f.type
            if (t.name != "java.lang.Object") {
                Log.d(TAG, "dtok 由 dtes 字段 c 反推 -> ${t.name}")
                return t
            }
        }
        return Class.forName(hardcodedName, true, cl)
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
        }.firstOrNull()?.name

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
        }.firstOrNull()?.name
}
