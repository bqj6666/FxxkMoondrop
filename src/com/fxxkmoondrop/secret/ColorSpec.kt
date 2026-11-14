package com.fxxkmoondrop.secret

/**
 * 色彩规范：M3 语义角色取哪一套 tone 表。
 *
 * 只影响颜色（中性色、描边、容器层级），不影响形状、动效、组件尺寸。
 * Material 3 = 2021 baseline 的 tone 表；Expressive = 2025 的 tone 表
 * （容器更亮、描边更淡、次级文字对比略高）。官方未发布完整 tone 表，
 * 2025 采用该观感取向的近似值。
 */
enum class ColorSpec(val key: String, val zh: String, val en: String) {
    SPEC_2021("2021", "Material 3", "Material 3"),
    SPEC_2025("2025", "Expressive", "Expressive");

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
            return entries.firstOrNull { it.key == v } ?: SPEC_2025
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
