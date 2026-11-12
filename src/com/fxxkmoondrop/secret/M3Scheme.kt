package com.fxxkmoondrop.secret

import com.materialkolor.hct.Hct

/**
 * Material 3 调色板风格（Monet 的 9 个 variant）。
 *
 * 参数取自 material-color-utilities 的 `SchemeTonalSpot` / `SchemeVibrant` / `SchemeExpressive`
 * 等官方实现：每个 variant 只是「四组色相盘」的参数不同（hue 偏移 + chroma 上限），
 * 角色 tone 表则完全一致。这里只依赖 Hct（不引 Compose），按官方参数算。
 */
enum class M3SchemeVariant(val key: String, val zh: String, val en: String) {

    TONAL_SPOT("tonal_spot", "Tonal Spot", "Tonal Spot"),
    VIBRANT("vibrant", "Vibrant", "Vibrant"),
    EXPRESSIVE("expressive", "Expressive", "Expressive"),
    FIDELITY("fidelity", "Fidelity", "Fidelity"),
    CONTENT("content", "Content", "Content"),
    NEUTRAL("neutral", "Neutral", "Neutral"),
    MONOCHROME("monochrome", "Monochrome", "Monochrome"),
    RAINBOW("rainbow", "Rainbow", "Rainbow"),
    FRUIT_SALAD("fruit_salad", "Fruit Salad", "Fruit Salad");

    /**
     * 5 组色盘的参数：hue 偏移（相对种子）与 chroma。
     * 顺序：primary / secondary / tertiary / neutral / neutralVariant。
     */
    fun spec(seedChroma: Double): DoubleArray = when (this) {
        TONAL_SPOT   -> doubleArrayOf(0.0, 36.0, 0.0, 16.0, 60.0, 24.0, 0.0, 6.0, 0.0, 8.0)
        VIBRANT      -> doubleArrayOf(0.0, 200.0, 0.0, 24.0, 60.0, 32.0, 0.0, 10.0, 0.0, 12.0)
        EXPRESSIVE   -> doubleArrayOf(240.0, 40.0, 60.0, 24.0, 15.0, 32.0, 0.0, 8.0, 0.0, 12.0)
        // Fidelity / Content：主色沿用种子自身的 chroma（保留"原色感"）
        FIDELITY     -> doubleArrayOf(0.0, seedChroma, 0.0, 16.0, 60.0, 24.0, 0.0, 6.0, 0.0, 8.0)
        CONTENT      -> doubleArrayOf(0.0, seedChroma, 0.0, 8.0, 60.0, 16.0, 0.0, 4.0, 0.0, 8.0)
        NEUTRAL      -> doubleArrayOf(0.0, 12.0, 0.0, 8.0, 60.0, 16.0, 0.0, 6.0, 0.0, 8.0)
        MONOCHROME   -> DoubleArray(10)
        // Rainbow 的色相随 tone 旋转是官方特色；这里保留高 chroma 的近似（视觉一致，实现简单）
        RAINBOW      -> doubleArrayOf(0.0, 48.0, 0.0, 16.0, 60.0, 24.0, 0.0, 6.0, 0.0, 8.0)
        FRUIT_SALAD  -> doubleArrayOf(-50.0, 48.0, -50.0, 36.0, 0.0, 36.0, 0.0, 6.0, 0.0, 8.0)
    }

    companion object {
        private const val PREF = "m3_variant"

        @JvmStatic
        fun current(c: android.content.Context?): M3SchemeVariant {
            val v = try {
                c?.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        ?.getString(PREF, null)
            } catch (_: Throwable) {
                null
            }
            return entries.firstOrNull { it.key == v } ?: TONAL_SPOT
        }

        @JvmStatic
        fun set(c: android.content.Context, v: M3SchemeVariant) {
            try {
                c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        .edit().putString(PREF, v.key).commit()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * 从种子色生成一套 M3 语义角色（tone 表按 Material 3 规范，浅色/深色各一套）。
 *
 * 只算 Palette 用得上的角色；将来要 secondary/tertiary 直接在这里补一行即可。
 */
object M3Scheme {

    /** 浅色 / 深色两套 tone（Material 3 baseline；2025 档按 Expressive 观感微调）。 */
    private fun toneOf(role: String, dark: Boolean, spec: ColorSpec): Double {
        if (spec == ColorSpec.SPEC_2025) {
            return when (role) {
                "surfaceContainer" -> if (dark) 10.0 else 96.0
                "onSurfaceVariant" -> if (dark) 75.0 else 35.0
                "outline" -> if (dark) 65.0 else 55.0
                "outlineVariant" -> if (dark) 25.0 else 85.0
                else -> toneOf2021(role, dark)
            }
        }
        return toneOf2021(role, dark)
    }

    private fun toneOf2021(role: String, dark: Boolean): Double = when (role) {
        "primary" -> if (dark) 80.0 else 40.0
        "onPrimary" -> if (dark) 20.0 else 100.0
        "primaryContainer" -> if (dark) 30.0 else 90.0
        "onPrimaryContainer" -> if (dark) 90.0 else 10.0
        "secondary" -> if (dark) 80.0 else 40.0
        "tertiary" -> if (dark) 80.0 else 40.0
        "surface" -> if (dark) 6.0 else 98.0
        "surfaceContainer" -> if (dark) 12.0 else 94.0
        "onSurface" -> if (dark) 90.0 else 10.0
        "onSurfaceVariant" -> if (dark) 80.0 else 30.0
        "outline" -> if (dark) 60.0 else 50.0
        "outlineVariant" -> if (dark) 30.0 else 80.0
        else -> if (dark) 90.0 else 10.0
    }

    private fun paletteOf(seed: Int, variant: M3SchemeVariant, dark: Boolean,
                          spec: ColorSpec): Map<String, Int> {
        val hct = Hct.fromInt(seed or 0xFF000000.toInt())
        val h = hct.hue
        val c = hct.chroma
        val args = variant.spec(c)
        fun plate(i: Int): (Double) -> Int {
            val hue = h + args[i * 2]
            val chroma = args[i * 2 + 1]
            return { t -> Hct.from(hue, chroma, t).toInt() }
        }
        val primary = plate(0)
        val secondary = plate(1)
        val tertiary = plate(2)
        val neutral = plate(3)
        val neutralVariant = plate(4)
        fun r(role: String): Int {
            val t = toneOf(role, dark, spec)
            return when (role) {
                "onSurfaceVariant", "outline", "outlineVariant" -> neutralVariant(t)
                "secondary" -> secondary(t)
                "tertiary" -> tertiary(t)
                "surface", "surfaceContainer", "onSurface" -> neutral(t)
                else -> primary(t)
            }
        }
        return linkedMapOf(
                "primary" to r("primary"),
                "onPrimary" to r("onPrimary"),
                "primaryContainer" to r("primaryContainer"),
                "onPrimaryContainer" to r("onPrimaryContainer"),
                "secondary" to r("secondary"),
                "tertiary" to r("tertiary"),
                "surface" to r("surface"),
                "surfaceContainer" to r("surfaceContainer"),
                "onSurface" to r("onSurface"),
                "onSurfaceVariant" to r("onSurfaceVariant"),
                "outline" to r("outline"),
                "outlineVariant" to r("outlineVariant"))
    }

    /** 对外入口：种子 + 风格 + 亮暗 + 规范 → 角色表（失败返回 null，调用方回退旧逻辑）。 */
    @JvmStatic
    fun roles(seed: Int, variant: M3SchemeVariant, dark: Boolean,
              spec: ColorSpec = ColorSpec.current(null)): Map<String, Int>? = try {
        paletteOf(seed, variant, dark, spec)
    } catch (_: Throwable) {
        null
    }
}
