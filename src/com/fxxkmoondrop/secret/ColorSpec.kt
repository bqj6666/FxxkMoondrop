package com.fxxkmoondrop.secret

/**
 * Material 3 色彩规范版本。
 *
 * 2021（M3 baseline）与 2025（M3 Expressive）在少数中性色/描边的 tone 上有差异：
 * Expressive 的容器更亮、描边更淡、次级文字对比略高。这里的 2025 采用该观感取向的
 * 近似值（官方未发布完整 tone 表），只影响中性色与描边，不碰 primary 系列。
 */
enum class ColorSpec(val key: String, val zh: String, val en: String) {
    SPEC_2021("2021", "Material 3 (2021)", "Material 3 (2021)"),
    SPEC_2025("2025", "Expressive (2025)", "Expressive (2025)");

    companion object {
        private const val PREF = "color_spec"

        @JvmStatic
        fun current(c: android.content.Context?): ColorSpec {
            val v = try {
                c?.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        ?.getString(PREF, null)
            } catch (_: Throwable) {
                null
            }
            return entries.firstOrNull { it.key == v } ?: SPEC_2021
        }

        @JvmStatic
        fun set(c: android.content.Context, v: ColorSpec) {
            try {
                c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        .edit().putString(PREF, v.key).commit()
            } catch (_: Throwable) {
            }
        }
    }
}
