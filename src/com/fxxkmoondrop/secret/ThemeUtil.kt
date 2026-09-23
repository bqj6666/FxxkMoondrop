package com.fxxkmoondrop.secret

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * alpha2.0: 官方 LSPosed 主题架构（VectorTheme 对位）——
 * 跟随系统/浅色/深色 主题模式 + 动态取色开关 + AMOLED 纯黑 + 种子颜色。
 * SP("cfg") 键：theme_mode(0系统/1浅/2深)、dynamic_color(bool,默认开)、amoled(bool)、seed(int)。
 *
 * alpha2.52: 色板按 Material 3 语义角色重排，修复「卡片被绑到强调色」的老问题：
 *  - card 改取中性 surfaceContainer。原先用 system_accent1_800，设备实测动态取色下
 *    卡片 = #49261E（棕）且占屏 67%，整页发棕 —— 这是"AMOLED 看起来奇怪"的真正来源。
 *  - AMOLED 改为真·纯黑：surface 与 card 同为 #000000，层级改由 1dp 描边(cardStroke)表达，
 *    不再是「黑底 + #0F0F0F 灰块」的混搭。
 *  - AMOLED 下强调容器(container)一并压暗，避免纯黑底上残留高饱和色块。
 *  - AMOLED 仅在深色主题生效，由 [amoledOn] 统一判定；浅色下不再是一个点了没反应的开关。
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

        /** AMOLED 纯黑开关（仅深色主题下生效） */
        @JvmStatic
        fun amoled(c: Context): Boolean =
                c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("amoled", false)

        /** AMOLED 是否真的生效：开着 且 当前为深色。UI 与色板统一用这个判定 */
        @JvmStatic
        fun amoledOn(c: Context): Boolean = amoled(c) && isDark(c)

        /** 种子颜色索引（动态色关闭时生效），默认 0=官方 LSPosed 粉 #f48fb1 */
        @JvmStatic
        fun seed(c: Context): Int =
                Math.max(0, Math.min(SEEDS.size - 1,
                        c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("seed", 0)))

        /** 官方种子色表（首项 = LSPosed Manager colors.xml 里的 #f48fb1） */
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

    /**
     * 一套与 Material 3 语义角色对齐的色板（深浅／动态／种子／AMOLED 全支持）。
     *
     * 角色对照：primary=primary、container=secondaryContainer（指示器/图标底/chip）、
     * surface=surface、card=surfaceContainer。
     */
    class Palette(c: Context) {
        @JvmField val dark: Boolean
        @JvmField val amoledOn: Boolean
        @JvmField val primary: Int
        @JvmField val onPrimary: Int
        @JvmField val container: Int
        @JvmField val onContainer: Int
        @JvmField val surface: Int
        @JvmField val card: Int
        /** 卡片描边色；0 表示不描边（仅 AMOLED 下启用，用于区分同为纯黑的层级） */
        @JvmField val cardStroke: Int
        @JvmField val onSurface: Int
        @JvmField val onVariant: Int
        @JvmField val outline: Int
        /** 卡片间/行间分隔线色（AMOLED 下与描边同源） */
        @JvmField val divider: Int
        /** M3 surfaceContainerHighest：开关关闭态轨道、未选中容器 */
        @JvmField val surfaceContainerHighest: Int
        @JvmField val green: Int
        @JvmField val red: Int

        init {
            dark = isDark(c)
            val on = amoled(c) && dark
            amoledOn = on

            val prim: Int
            val onPrim: Int
            var cont: Int
            var onCont: Int
            val surf: Int
            val cardC: Int

            if (dynColor(c)) {
                prim = dyn(c, "system_accent1_400", if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt())
                // 浅色下 primary 是深色，文字必须用白；深色下 primary 是浅色，文字用深色
                onPrim = if (dark) dyn(c, "system_accent1_900", 0xFF21005D.toInt())
                else 0xFFFFFFFF.toInt()
                cont = if (dark) dyn(c, "system_accent1_800", 0xFF4F378B.toInt())
                else dyn(c, "system_accent1_50", 0xFFE8DEF8.toInt())
                onCont = if (dark) dyn(c, "system_accent1_50", 0xFFEADDFF.toInt())
                else dyn(c, "system_accent1_900", 0xFF21005D.toInt())
                surf = if (dark) (if (on) 0xFF000000.toInt() else dyn(c, "system_neutral1_900", 0xFF141218.toInt()))
                else dyn(c, "system_neutral1_10", 0xFFFBF8FF.toInt())
                // 卡片走中性 surfaceContainer。绝不使用强调色，否则整页被染成强调色相
                cardC = if (dark) (if (on) 0xFF101010.toInt() else dyn(c, "system_neutral1_800", 0xFF1E1B22.toInt()))
                else 0xFFFFFFFF.toInt()
                onSurface = if (dark) dyn(c, "system_neutral1_0", 0xFFE6E0E9.toInt())
                else dyn(c, "system_neutral1_900", 0xFF1C1B1F.toInt())
            } else {
                // 种子色生成（官方 LSPosed seed 近似调色）
                val seed = seedColor(c)
                prim = if (dark) mix(seed, 0.55f, false) else seed
                onPrim = if (dark) mix(seed, 0.55f, true) else 0xFFFFFFFF.toInt()
                cont = if (dark) mix(seed, 0.60f, false) else mix(seed, 0.72f, true)
                onCont = if (dark) mix(seed, 0.60f, true) else mix(seed, 0.55f, false)
                surf = if (dark) (if (on) 0xFF000000.toInt() else 0xFF141218.toInt()) else 0xFFFBF8FF.toInt()
                cardC = if (dark) (if (on) 0xFF101010.toInt() else 0xFF1E1B22.toInt()) else 0xFFFFFFFF.toInt()
                onSurface = if (dark) 0xFFE6E0E9.toInt() else 0xFF1C1B1F.toInt()
            }

            onVariant = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
            outline = if (dark) 0xFF938F99.toInt() else 0xFF79747E.toInt()
            green = if (dark) 0xFF8FD89B.toInt() else 0xFF2E7D32.toInt()
            red = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()

            if (on) {
                // alpha2.52: 纯黑底上不放强调色容器，改用中性抬高色，
                // 避免纯黑页面上残留暖／彩色块（旧实现 = 强调色 x0.55，仍是暖棕）
                cont = 0xFF1C1C1C.toInt()
                onCont = onSurface
            }
            // alpha2.52: 对齐 org.lsposed.manager —— 卡片一律不画描边，
            // 层级由"卡片填充色 vs 页面底色"和卡间距表达（用户反馈描边"突兀"）。
            // AMOLED 下卡片改用极低抬高的 #101010：无线条、不破坏纯黑背景观感。
            cardStroke = 0
            // 卡片间/行间分隔线沿用 outline 淡线（LSPosed 用间距分隔，仅在合并卡内保留细线）
            divider = ((outline and 0x00FFFFFF) or 0x2E000000.toInt())
            // 注：Android 的 tonal palette 只提供 tone 0/10/50/100/200…/1000，没有 90。
            // 原先写 system_neutral1_90 会 getIdentifier 失败 → 永远走 fallback，
            // 等于浅色下这格从未跟随壁纸。改用最接近的有效 tone 100。
            surfaceContainerHighest = if (on) 0xFF262626.toInt()
            else if (dark) dyn(c, "system_neutral1_700", 0xFF36343B.toInt())
            else dyn(c, "system_neutral1_100", 0xFFE6E0E9.toInt())

            primary = prim
            onPrimary = onPrim
            container = cont
            onContainer = onCont
            surface = surf
            card = cardC
        }
    }
}
