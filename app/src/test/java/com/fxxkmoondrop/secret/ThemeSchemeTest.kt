package com.fxxkmoondrop.secret

import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.utils.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ThemeSchemeTest {
    @Test fun selectionMatrix() {
        for (system in listOf(false, true)) for (mode in 0..2)
            for (dynamic in listOf(false, true)) for (sdk in listOf(26, 30, 31, 32, 36))
                for (amoled in listOf(false, true)) for (seed in -1..5) {
                    val s = ThemeScheme.select(mode, system, dynamic, sdk, seed, amoled)
                    val dark = when (mode) { 1 -> false; 2 -> true; else -> system }
                    assertEquals(dark, s.dark)
                    assertEquals(dynamic && sdk >= 31, s.wallpaper)
                    assertEquals(seed.coerceIn(0..4), s.seed)
                    assertEquals(amoled && dark, s.amoled)
                    val notification = ThemeScheme.select(mode, system, dynamic, sdk, seed, amoled, true)
                    assertEquals(system, notification.dark)
                    assertFalse(notification.amoled)
                }
    }

    private fun contrast(a: Int, b: Int): Double {
        val y1 = ColorUtils.xyzFromArgb(a)[1] + 5.0
        val y2 = ColorUtils.xyzFromArgb(b)[1] + 5.0
        return maxOf(y1, y2) / minOf(y1, y2)
    }

    private fun assertContrast(roles: Map<String, Int>) {
        val pairs = listOf("Primary", "Secondary", "Tertiary", "Error").flatMap {
            listOf("colorOn$it" to "color$it", "colorOn${it}Container" to "color${it}Container")
        } + listOf("Primary", "Secondary", "Tertiary").flatMap {
            listOf("colorOn${it}Fixed" to "color${it}Fixed", "colorOn${it}FixedVariant" to "color${it}FixedDim")
        } + listOf("colorOnSurface" to "colorSurface", "colorOnSurfaceVariant" to "colorSurfaceContainerHighest",
            "colorPrimary" to "colorSurface", "colorPrimary" to "colorSurfaceContainerHigh",
            "colorTertiary" to "colorSurface", "colorTertiary" to "colorSurfaceContainer",
            "colorOnSurfaceInverse" to "colorSurfaceInverse")
        for ((fg, bg) in pairs) assertTrue("$fg on $bg: ${contrast(roles.getValue(fg), roles.getValue(bg))}",
            contrast(roles.getValue(fg), roles.getValue(bg)) >= 4.5)
    }

    @Test fun seedAndAmoledContrast() {
        for (seed in ThemeScheme.seeds.indices) for (dark in listOf(false, true)) for (amoled in listOf(false, true)) {
            val s = ThemeScheme.select(if (dark) 2 else 1, !dark, false, 36, seed, amoled)
            val roles = ThemeScheme.resolve(s) { error("Dynamic-off must never read wallpaper") }
            assertContrast(roles)
            assertTrue(contrast(ThemeScheme.success(dark), roles.getValue("colorSurface")) >= 4.5)
            if (s.amoled) ThemeScheme.blackSurfaces.forEach { assertEquals(0xFF000000.toInt(), roles[it]) }
        }
    }

    @Test fun preAndroid12DynamicUsesSelectedSeedWithoutWallpaperAccess() {
        for (sdk in listOf(26, 30)) for (seed in ThemeScheme.seeds.indices) for (dark in listOf(false, true)) {
            val s = ThemeScheme.select(0, dark, true, sdk, seed, false)
            assertEquals(ThemeScheme.seeded(ThemeScheme.seeds[seed], dark),
                ThemeScheme.resolve(s) { error("API $sdk must not read wallpaper") })
        }
    }

    @Test fun wallpaperUsesActualTonesAndFallsBackAtomically() {
        for (seed in ThemeScheme.seeds) for (dark in listOf(false, true)) for (amoled in listOf(false, true)) {
            val hct = Hct.fromInt(seed)
            val palettes = mapOf("accent1" to TonalPalette.fromHueAndChroma(hct.hue, 48.0),
                "accent2" to TonalPalette.fromHueAndChroma(hct.hue, 16.0),
                "accent3" to TonalPalette.fromHueAndChroma(hct.hue + 60.0, 24.0),
                "neutral1" to TonalPalette.fromHueAndChroma(hct.hue, 4.0),
                "neutral2" to TonalPalette.fromHueAndChroma(hct.hue, 8.0))
            val s = ThemeScheme.select(if (dark) 2 else 1, false, true, 31, 0, amoled)
            val read: (String) -> Int = { name ->
                val (_, palette, tone) = name.split('_')
                palettes.getValue(palette).tone(100 - tone.toInt() / 10)
            }
            val roles = ThemeScheme.resolve(s, read)
            assertContrast(roles)
            assertEquals(read(ThemeScheme.wallpaperResources(dark).getValue("colorPrimary")), roles["colorPrimary"])
            assertEquals(ThemeScheme.resolve(s.copy(wallpaper = false)) { null },
                ThemeScheme.resolve(s) { if (it == "system_accent1_600" || it == "system_accent1_200") null else read(it) })
        }
    }

    /** These are numeric resource-mapping tests, not widget/layout or source-text UI tests.
     * Regenerate after changing the scheme: UPDATE_THEME_OVERLAYS=1 ./gradlew :app:testDebugUnitTest
     * --tests '*ThemeSchemeTest.compiledOverlaysMatchRoles' --rerun-tasks (plus local AAPT2 override). */
    @Test fun compiledOverlaysMatchRoles() {
        val base = File("src/main/res")
        val seedStyles = linkedMapOf<String, Map<String, String>>()
        for (dark in listOf(false, true)) for (seed in ThemeScheme.seeds.indices) {
            seedStyles["ThemeOverlay.FxxkMoondrop.Seed$seed.${if (dark) "Dark" else "Light"}"] =
                ThemeScheme.seeded(ThemeScheme.seeds[seed], dark).mapValues { "#%08X".format(it.value) }
        }
        seedStyles["ThemeOverlay.FxxkMoondrop.Amoled"] = ThemeScheme.blackSurfaces.associateWith { "#FF000000" } +
            ("elevationOverlayEnabled" to "false")
        checkResources(File(base, "values/theme_schemes.xml"), seedStyles)
        val dynamicStyles = listOf(false, true).associate { dark ->
            "ThemeOverlay.FxxkMoondrop.Wallpaper.${if (dark) "Dark" else "Light"}" to
                ThemeScheme.wallpaperResources(dark).mapValues { "@android:color/${it.value}" }
        }
        checkResources(File(base, "values-v31/theme_schemes.xml"), dynamicStyles)
    }

    private fun checkResources(file: File, styles: Map<String, Map<String, String>>) {
        if (System.getenv("UPDATE_THEME_OVERLAYS") == "1") {
            file.parentFile!!.mkdirs()
            file.writeText(buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by ThemeSchemeTest from MaterialKolor 5.0.1. See ThemeScheme.kt. -->\n<resources>\n")
                styles.forEach { (name, roles) ->
                    append("    <style name=\"$name\" parent=\"\">\n")
                    roles.forEach { (role, value) -> append("        <item name=\"$role\">$value</item>\n") }
                    append("    </style>\n")
                }
                append("</resources>\n")
            })
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("style")
        assertEquals(styles.size, nodes.length)
        for (i in 0 until nodes.length) {
            val style = nodes.item(i) as Element
            val expected = styles.getValue(style.getAttribute("name"))
            val items = style.getElementsByTagName("item")
            val actual = (0 until items.length).associate { n ->
                val item = items.item(n) as Element
                item.getAttribute("name") to item.textContent
            }
            assertEquals(expected, actual)
        }
    }
}
