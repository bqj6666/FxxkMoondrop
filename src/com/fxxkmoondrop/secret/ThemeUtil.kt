package com.fxxkmoondrop.secret

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * alpha2.0: 官方 LSPosed 主题架构（VectorTheme 对位）——
 * 跟随系统/浅色/深色 主题模式 + 动态取色开关 + AMOLED 纯黑 + 种子颜色。
 * SP("cfg") 键：theme_mode(0系统/1浅/2深)、dynamic_color(bool,默认开)、amoled(bool)、seed(int)。
 */
class ThemeUtil {
    companion object {
        private const val SP = "cfg"

        /** 主题模式：0=跟随系统 1=浅色 2=深色 */
        @JvmStatic
        fun themeMode(c: Context): Int =
                c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("theme_mode", 0)

        /** 动态取色（跟随壁纸），默认开 */
        @JvmStatic
        fun dynColor(c: Context): Boolean =
                c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("dynamic_color", true)

        /** AMOLED 纯黑（深色下 surface 用纯黑） */
        @JvmStatic
        fun amoled(c: Context): Boolean =
                c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("amoled", false)

        /** 种子颜色索引（动态色关闭时生效），默认 0=官方 LSPosed 粉 #f48fb1 */
        @JvmStatic
        fun seed(c: Context): Int =
                Math.max(0, Math.min(SEEDS.size - 1,
                        c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("seed", 0)))

        /** 官方种子色表（首项 = LSPosed Manager 的 #f48fb1） */
        @JvmField
        val SEEDS = intArrayOf(
                0xFFF48FB1.toInt(), 0xFF6750A4.toInt(), 0xFF4FC3F7.toInt(),
                0xFF81C784.toInt(), 0xFFFFB74D.toInt())
        @JvmField
        val SEED_NAMES = arrayOf("粉色", "紫色", "蓝色", "绿色", "橙色")

        @JvmStatic
        fun seedColor(c: Context): Int = SEEDS[seed(c)]

        /** 深色判定：主题模式优先，跟随系统时读系统 uiMode */
        @JvmStatic
        fun isDark(c: Context): Boolean {
            return when (themeMode(c)) {
                1 -> false
                2 -> true
                else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            }
        }

        /** Android 12+ 系统莫奈动态色（动态色开启时）；关闭或低版本回退 fallback */
        @JvmStatic
        fun dyn(c: Context, resName: String, fallback: Int): Int {
            if (dynColor(c) && Build.VERSION.SDK_INT >= 31) {
                try {
                    val id = c.resources.getIdentifier(resName, "color", "android")
                    if (id != 0) return c.resources.getColor(id, c.theme)
                } catch (_: Exception) { }
            }
            return fallback
        }

        /** 与白色/黑色按比例混合（官方种子调色近似） */
        private fun mix(color: Int, t: Float, towardWhite: Boolean): Int {
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            val k = if (towardWhite) t else (1 - t)
            val R = Math.round(r * k + (if (towardWhite) 255 * (1 - k) else 0f))
            val G = Math.round(g * k + (if (towardWhite) 255 * (1 - k) else 0f))
            val B = Math.round(b * k + (if (towardWhite) 255 * (1 - k) else 0f))
            return 0xFF000000.toInt() or (R shl 16) or (G shl 8) or B
        }
    }

    /** 一套与官方 Material3 Expressive 一致的色板（深浅/动态/种子/AMOLED 全支持） */
    class Palette(c: Context) {
        @JvmField val dark: Boolean = isDark(c)
        @JvmField val primary: Int
        @JvmField val onPrimary: Int
        @JvmField val container: Int
        @JvmField val onContainer: Int
        @JvmField val surface: Int
        @JvmField val card: Int
        @JvmField val onSurface: Int
        @JvmField val onVariant: Int
        @JvmField val outline: Int
        @JvmField val green: Int
        @JvmField val red: Int

        init {
            val dyn = dynColor(c)
            // AMOLED 独立于动态取色：深色模式下开关即生效（纯黑 surface / 近黑 card）
            val amoled = amoled(c)
            if (dyn) {
                primary = dyn(c, "system_accent1_400", if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
                onPrimary = dyn(c, "system_accent1_900", 0xFF21005D.toInt())
                container = if (dark) dyn(c, "system_accent1_800", 0xFF4F378B.toInt())
                else dyn(c, "system_accent1_50", 0xFFE8DEF8.toInt())
                onContainer = if (dark) dyn(c, "system_accent1_50", 0xFFEADDFF.toInt())
                else dyn(c, "system_accent1_900", 0xFF21005D.toInt())
                surface = if (dark) (if (amoled) 0xFF000000.toInt() else dyn(c, "system_neutral1_900", 0xFF141218.toInt()))
                else dyn(c, "system_neutral1_10", 0xFFFBF8FF.toInt())
                card = if (dark) (if (amoled) 0xFF0F0F0F.toInt() else dyn(c, "system_accent1_800", 0xFF1E1B22.toInt()))
                else 0xFFFFFFFF.toInt()
                onSurface = if (dark) dyn(c, "system_neutral1_0", 0xFFE6E0E9.toInt())
                else dyn(c, "system_neutral1_900", 0xFF1C1B1F.toInt())
            } else {
                // 种子色生成（官方 LSPosed seed 近似调色）
                val seed = seedColor(c)
                primary = if (dark) mix(seed, 0.55f, false) else seed
                onPrimary = if (dark) mix(seed, 0.55f, true) else 0xFFFFFFFF.toInt()
                container = if (dark) mix(seed, 0.60f, false) else mix(seed, 0.72f, true)
                onContainer = if (dark) mix(seed, 0.60f, true) else mix(seed, 0.55f, false)
                surface = if (dark) (if (amoled) 0xFF000000.toInt() else 0xFF141218.toInt()) else 0xFFFBF8FF.toInt()
                card = if (dark) (if (amoled) 0xFF0F0F0F.toInt() else 0xFF1E1B22.toInt()) else 0xFFFFFFFF.toInt()
                onSurface = if (dark) 0xFFE6E0E9.toInt() else 0xFF1C1B1F.toInt()
            }
            onVariant = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
            outline = if (dark) 0xFF938F99.toInt() else 0xFF79747E.toInt()
            green = if (dark) 0xFF8FD89B.toInt() else 0xFF2E7D32.toInt()
            red = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
        }
    }
}
