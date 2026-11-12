package com.fxxkmoondrop.secret

/**
 * 调色标准：决定 [ThemeUtil.Palette] 用哪套算法产出 M3 语义角色。
 *
 * 三档的差别只在「角色怎么取」，亮暗、AMOLED、动态取色这些开关仍然独立生效。
 */
enum class ColorStandard(val key: String) {

    /**
     * M3 官方标准：用 HCT 调色板生成完整角色。
     *
     * materialkolor 的 `SchemeTonalSpot` 与我们既有的种子色/壁纸色对接，
     * 按规范 tone 取值（浅色 primary=40、container=90、surface=98、onSurface=10…；
     * 深色 80/30/6/90…），这是 Material 官方「从 seed 派生整套配色」的算法。
     */
    M3("m3"),

    /**
     * Android 12 系统 tonal palette：直接读 `system_accent1_*` / `system_neutral1_*`。
     *
     * 色相跟壁纸一致，但系统只暴露 0/10/50/100/200…1000 这些档，
     * 没有 M3 角色需要的 40/80/90/30/98/96，只能就近取 —— 明度会有偏差。
     */
    SYSTEM("system"),

    /** 经典：种子色 + 手工混色（3.3.1 及以前的观感）。 */
    CLASSIC("classic");

    companion object {
        private const val PREF = "color_standard"

        @JvmStatic
        fun current(c: android.content.Context?): ColorStandard {
            val v = try {
                c?.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        ?.getString(PREF, null)
            } catch (_: Throwable) {
                null
            }
            return entries.firstOrNull { it.key == v } ?: M3
        }

        @JvmStatic
        fun set(c: android.content.Context, s: ColorStandard) {
            try {
                c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        .edit().putString(PREF, s.key).commit()
            } catch (_: Throwable) {
            }
        }
    }
}
