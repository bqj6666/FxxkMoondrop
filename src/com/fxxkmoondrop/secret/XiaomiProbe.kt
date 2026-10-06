package com.fxxkmoondrop.secret

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.lang.reflect.Method
import java.util.Locale

/**
 * 小米 / HyperOS 设备识别门禁。
 *
 * 存在的意义：**非小米设备上，一个字节都不执行小米集成相关代码。**
 * 设置页的「系统集成」分组、设备 ID 映射、系统入口 hook，全部以本类的
 * [isXiaomi] 为总闸门。
 *
 * ## 为什么三级判据都不依赖 root
 *
 * 模块支持无 root 运行（见 [EnvProbe.isRooted]），因此检测手段一旦依赖
 * `su`，无 root 的小米用户就永远拿不到这些功能 —— 恰好是最需要它的群体被
 * 挡在门外。所以三级判据全部只用普通 App 权限即可读取：
 *
 *  1. **品牌**（[Build.BRAND] / [Build.MANUFACTURER]）：小米系出厂即写入，
 *     可靠性 100%，且最快，命中即短路返回。
 *  2. **系统属性**（`ro.miui.ui.version.name` / `ro.mi.os.version.name`）：
 *     走 `android.os.SystemProperties` 反射，纯 Java 层可读，**无需 root**。
 *  3. **包存在性**（`com.miui.securitycenter` 等）：`PackageManager` 直接可见，
 *     无需任何权限。
 *
 * 判据之间是**或**关系：任一命中即认定小米。都不命中就是非小米。
 *
 * 之所以还要 L2 / L3 两级冗余：部分魔改 / 第三方 ROM 会改写 [Build.BRAND]，
 * 只信品牌会漏判；而反过来，用包名做子串匹配又容易把「作用域里出现
 * `com.xiaomi.bluetooth`」这类无关信号误当成本机是小米。
 *
 * ## 缓存策略
 *
 * 与 [EnvProbe] 对齐：
 *  - **正结果永久缓存** —— 设备不会中途从「非小米」变成「小米」。
 *  - **负结果 30 秒 TTL**（[NEGATIVE_TTL_MS]）—— 冷启动时系统属性和包表
 *    可能尚未就绪，过早定死「非小米」会造成不可恢复的误判。
 *  - [retry] 无条件清空缓存，供设置页「重新检测」使用。
 */
class XiaomiProbe private constructor() {

    companion object {
        /**
         * 小米系统性集成的**总开关**（A2：整块隐藏）。
         *
         * ## 为什么停用
         *
         * 用户决定：小米集成尚未在真机验证过（本机非小米设备），
         * 且设置页里那块「小米系统集成」在非小米机上空占一整屏、
         * 只显示一行置灰说明，属于无效信息。于是整块隐藏。
         *
         * ## 保留了什么
         *
         * `isXiaomi()` 的三级判据、设备 ID 映射、hook 入口**全部保留未删**，
         * 只是设置页不再渲染该分组。恢复 = 把本常量改回 true。
         *
         * ## 注意
         *
         * 这只是 UI 门禁。真正调用小米集成代码的路径本来就被
         * `isXiaomi()` 挡着（非小米设备一个字节都不执行），
         * 所以关掉它不会改变任何运行行为，只是少显示一块 UI。
         */
        const val INTEGRATION_ENABLED = false

        private const val TAG = "FxxkMoondrop/Xiaomi"

        /** 负结果缓存时长：与 EnvProbe 保持同一量级。 */
        private const val NEGATIVE_TTL_MS = 30_000L

        private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco", "black sesame")

        /** MIUI 时代与 HyperOS 时代的版本属性，两个都认。 */
        private val MIUI_PROPS = listOf(
            "ro.miui.ui.version.name",
            "ro.mi.os.version.name",
        )

        /** 小米系独有包。命中任意一个即可确认。 */
        private val XIAOMI_PACKAGES = listOf(
            "com.miui.securitycenter",      // 安全中心（MIUI 起就有）
            "com.miui.home",               // 桌面
            "com.xiaomi.micloud",          // 小米云
            "com.mi.android.globalFileexplorer",  // 文件管理
        )

        // ── 缓存 ──
        @Volatile private var cachedResult: Boolean? = null
        @Volatile private var cachedAt: Long = 0L

        /**
         * 纯逻辑判定，不碰任何 Android API —— 与 [XiaomiProbeTest] 同源。
         *
         * @param brandLower 已小写化的品牌；**必须**是精确匹配而非子串，
         *   否则「XiaomiFake」「notxiaomi」这类名字会误判。
         * @param props 系统属性表（由调用方反射取得）
         * @param hasPackages 包存在性查询（由调用方经 PackageManager 实现）
         */
        @JvmStatic
        fun evaluate(
            brandLower: String?,
            props: Map<String, String>,
            hasPackages: (String) -> Boolean,
        ): Boolean {
            if (brandLower != null && XIAOMI_BRANDS.contains(brandLower)) return true
            for (key in MIUI_PROPS) {
                if (!props[key].isNullOrBlank()) return true
            }
            // 包判据是最后一级，抛异常不能让整个检测崩掉 —— 降级为「这一级没命中」。
            // 品牌与属性两级已足以在真机上确认，丢 L3 只是少一层冗余。
            for (pkg in XIAOMI_PACKAGES) {
                val hit = try {
                    hasPackages(pkg)
                } catch (t: Throwable) {
                    false
                }
                if (hit) return true
            }
            return false
        }

        /** 本机是否小米 / HyperOS 设备。门禁总入口。 */
        @JvmStatic
        fun isXiaomi(): Boolean {
            cachedResult?.let {
                // 正结果永久有效
                if (it) return true
                if (System.currentTimeMillis() - cachedAt < NEGATIVE_TTL_MS) return false
            }
            val r = runCatching { detect() }.getOrElse { e ->
                // 探测本身失败时保守判「非小米」：宁可功能不出现，也不给非小米设备挂 hook
                Log.w(TAG, "小米检测异常，保守判为非小米", e)
                false
            }
            cachedResult = r
            cachedAt = System.currentTimeMillis()
            Log.i(TAG, "小米设备检测结果 = $r")
            return r
        }

        /** 清空缓存并重新检测（设置页「重新检测」）。 */
        @JvmStatic
        fun retry(): Boolean {
            cachedResult = null
            cachedAt = 0L
            return isXiaomi()
        }

        /** 仅供设置页展示：本次判定命中的是哪一级、依据是什么。 */
        @JvmStatic
        fun diagnose(): String {
            val brand = norm(Build.BRAND)
            val mfr = norm(Build.MANUFACTURER)
            val props = readProps()
            val propHit = MIUI_PROPS.filter { !props[it].isNullOrBlank() }
            val pkgHits = XIAOMI_PACKAGES.filter { packageExists(it) }
            val brandHit = brand in XIAOMI_BRANDS
            return when {
                brandHit -> "小米设备（品牌命中：brand=$brand manufacturer=$mfr）"
                propHit.isNotEmpty() -> "小米设备（系统属性命中：${propHit.joinToString()}）"
                pkgHits.isNotEmpty() -> "小米设备（独有包命中：${pkgHits.joinToString()}）"
                else -> "非小米设备（brand=$brand manufacturer=$mfr，未命中任何小米特征）"
            }
        }

        private fun norm(s: String?): String? =
                s?.lowercase(Locale.ROOT)?.trim()?.takeIf { it.isNotEmpty() }

        /** 三级判据实跑。包查询需要一个 Context，取不到就跳过 L3。 */
        private fun detect(): Boolean {
            val brand = norm(Build.BRAND) ?: norm(Build.MANUFACTURER)
            if (brand != null && XIAOMI_BRANDS.contains(brand)) return true

            val props = readProps()
            for (key in MIUI_PROPS) {
                if (!props[key].isNullOrBlank()) return true
            }

            val pm = appPackageManager()
            if (pm != null) {
                for (pkg in XIAOMI_PACKAGES) {
                    if (packageExists(pkg, pm)) return true
                }
            }
            return false
        }

        /**
         * 反射读 `android.os.SystemProperties.get(String)`。
         *
         * 该类属于 [android.os] 隐藏 API，但 `get` 是 @UnsupportedAppUsage 之外的
         * 长期稳定方法，在各厂商 ROM 上均可反射调用；失败返回空表，
         * 由上层降级到 L3 判定，不影响正确性。
         */
        private fun readProps(): Map<String, String> {
            val out = HashMap<String, String>(4)
            val get: Method = try {
                Class.forName("android.os.SystemProperties").getDeclaredMethod("get", String::class.java)
            } catch (t: Throwable) {
                Log.w(TAG, "SystemProperties 不可用，跳过属性判据", t)
                return out
            }
            for (key in MIUI_PROPS) {
                try {
                    out[key] = get.invoke(null, key) as? String ?: ""
                } catch (t: Throwable) {
                    Log.w(TAG, "读取属性 $key 失败", t)
                }
            }
            return out
        }

        private fun packageExists(pkg: String, pm: PackageManager? = appPackageManager()): Boolean {
            val m = pm ?: return false
            return try {
                m.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * 取应用自身 Context 的 PackageManager。
         *
         * 模块多数代码运行在宿主进程（system_server / GMS / Settings）里，那些
         * 进程未必持有我们的 Context；此时返回 null，L3 判据跳过 ——
         * 品牌与属性两级在真机上已经足够。
         */
        private fun appPackageManager(): PackageManager? = try {
            val app = XiaomiProbe::class.java.classLoader
                    ?.takeIf { it != ClassLoader.getSystemClassLoader() }
                    ?.let { cl ->
                        runCatching {
                            cl.loadClass("android.app.AppGlobals")
                                    .getDeclaredMethod("getInitialApplication")
                                    .invoke(null) as? Context
                        }.getOrNull()
                    }
            app?.packageManager
        } catch (t: Throwable) {
            Log.w(TAG, "取 PackageManager 失败，跳过包判据", t)
            null
        }
    }
}
