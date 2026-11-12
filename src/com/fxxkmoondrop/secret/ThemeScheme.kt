package com.fxxkmoondrop.secret

import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/** Android-independent role policy. Compiled overlays mirror the finite seed/brightness schemes;
 * ThemeSchemeTest checks every overlay value. The app supports API 32+. */
object ThemeScheme {
    val seeds = intArrayOf(0xFFF48FB1.toInt(), 0xFF6750A4.toInt(), 0xFF4FC3F7.toInt(),
        0xFF81C784.toInt(), 0xFFFFB74D.toInt())

    data class Selection(val dark: Boolean, val wallpaper: Boolean, val seed: Int, val amoled: Boolean)

    fun select(mode: Int, systemDark: Boolean, dynamic: Boolean, sdk: Int, seed: Int,
               amoled: Boolean, systemSurface: Boolean = false): Selection {
        val dark = if (systemSurface) systemDark else when (mode) {
            1 -> false
            2 -> true
            else -> systemDark
        }
        return Selection(dark, dynamic && sdk >= 31, seed.coerceIn(seeds.indices),
            amoled && dark && !systemSurface)
    }

    fun seeded(seed: Int, dark: Boolean): Map<String, Int> {
        val s = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
        return linkedMapOf(
            "colorPrimary" to s.primary, "colorOnPrimary" to s.onPrimary,
            "colorPrimaryContainer" to s.primaryContainer, "colorOnPrimaryContainer" to s.onPrimaryContainer,
            "colorSecondary" to s.secondary, "colorOnSecondary" to s.onSecondary,
            "colorSecondaryContainer" to s.secondaryContainer, "colorOnSecondaryContainer" to s.onSecondaryContainer,
            "colorTertiary" to s.tertiary, "colorOnTertiary" to s.onTertiary,
            "colorTertiaryContainer" to s.tertiaryContainer, "colorOnTertiaryContainer" to s.onTertiaryContainer,
            "colorError" to s.error, "colorOnError" to s.onError,
            "colorErrorContainer" to s.errorContainer, "colorOnErrorContainer" to s.onErrorContainer,
            "colorSurface" to s.surface, "colorOnSurface" to s.onSurface,
            "colorSurfaceVariant" to s.surfaceVariant, "colorOnSurfaceVariant" to s.onSurfaceVariant,
            "colorSurfaceDim" to s.surfaceDim, "colorSurfaceBright" to s.surfaceBright,
            "colorSurfaceContainerLowest" to s.surfaceContainerLowest,
            "colorSurfaceContainerLow" to s.surfaceContainerLow, "colorSurfaceContainer" to s.surfaceContainer,
            "colorSurfaceContainerHigh" to s.surfaceContainerHigh,
            "colorSurfaceContainerHighest" to s.surfaceContainerHighest,
            "colorOutline" to s.outline, "colorOutlineVariant" to s.outlineVariant,
            "colorSurfaceInverse" to s.inverseSurface, "colorOnSurfaceInverse" to s.inverseOnSurface,
            "colorPrimaryInverse" to s.inversePrimary,
            "colorPrimaryFixed" to s.primaryFixed, "colorPrimaryFixedDim" to s.primaryFixedDim,
            "colorOnPrimaryFixed" to s.onPrimaryFixed, "colorOnPrimaryFixedVariant" to s.onPrimaryFixedVariant,
            "colorSecondaryFixed" to s.secondaryFixed, "colorSecondaryFixedDim" to s.secondaryFixedDim,
            "colorOnSecondaryFixed" to s.onSecondaryFixed, "colorOnSecondaryFixedVariant" to s.onSecondaryFixedVariant,
            "colorTertiaryFixed" to s.tertiaryFixed, "colorTertiaryFixedDim" to s.tertiaryFixedDim,
            "colorOnTertiaryFixed" to s.onTertiaryFixed, "colorOnTertiaryFixedVariant" to s.onTertiaryFixedVariant
        )
    }

    /** Android 12's public wallpaper tonal resources, not a reconstructed wallpaper seed.
     * Surface tones use the nearest public neutral stops available on API 31/32. */
    fun wallpaperResources(dark: Boolean): Map<String, String> = buildMap {
        fun role(name: String, palette: String, light: Int, night: Int) {
            put("color$name", "system_${palette}_${if (dark) night else light}")
        }
        for ((name, palette) in listOf("Primary" to "accent1", "Secondary" to "accent2", "Tertiary" to "accent3")) {
            role(name, palette, 600, 200)
            role("On$name", palette, 0, 800)
            role("${name}Container", palette, 100, 700)
            role("On${name}Container", palette, 900, 100)
            role("${name}Fixed", palette, 100, 100)
            role("${name}FixedDim", palette, 200, 200)
            role("On${name}Fixed", palette, 900, 900)
            role("On${name}FixedVariant", palette, 700, 700)
        }
        role("Surface", "neutral1", 10, 900)
        role("OnSurface", "neutral1", 900, 100)
        role("SurfaceVariant", "neutral2", 100, 700)
        role("OnSurfaceVariant", "neutral2", 700, 200)
        role("SurfaceDim", "neutral1", 100, 900)
        role("SurfaceBright", "neutral1", 10, 800)
        role("SurfaceContainerLowest", "neutral1", 0, 1000)
        role("SurfaceContainerLow", "neutral1", 50, 900)
        role("SurfaceContainer", "neutral1", 50, 900)
        role("SurfaceContainerHigh", "neutral1", 100, 800)
        role("SurfaceContainerHighest", "neutral1", 100, 800)
        role("Outline", "neutral2", 500, 400)
        role("OutlineVariant", "neutral2", 200, 700)
        role("SurfaceInverse", "neutral1", 800, 100)
        role("OnSurfaceInverse", "neutral1", 50, 800)
        role("PrimaryInverse", "accent1", 200, 600)
    }

    val blackSurfaces = setOf("colorSurface", "colorSurfaceDim", "colorSurfaceBright",
        "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
        "colorSurfaceContainerHigh", "colorSurfaceContainerHighest", "colorSurfaceVariant")

    private val seedSchemes by lazy {
        listOf(false, true).associateWith { dark -> seeds.map { seeded(it, dark) } }
    }
    private val successSchemes by lazy {
        listOf(false, true).associateWith { dark -> seeded(0xFF008000.toInt(), dark) }
    }
    fun success(dark: Boolean): Int = successSchemes.getValue(dark).getValue("colorPrimary")

    fun resolve(selection: Selection, wallpaperColor: (String) -> Int?): Map<String, Int> {
        val roles = seedSchemes.getValue(selection.dark)[selection.seed].toMutableMap()
        if (selection.wallpaper) {
            // Missing framework resources must not produce a half-wallpaper, half-seed scheme.
            val wallpaper = wallpaperResources(selection.dark).mapValues { wallpaperColor(it.value) }
            if (wallpaper.values.all { it != null }) wallpaper.forEach { (key, value) -> roles[key] = value!! }
        }
        if (selection.amoled) blackSurfaces.forEach { roles[it] = 0xFF000000.toInt() }
        return roles
    }
}
