package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import androidx.core.view.WindowCompat

class ThemeUtil {
    companion object {
        private const val SP = "cfg"
        // A module package context inside GMS/Settings cannot read the module's private cfg.
        private fun isApp(c: Context) = c.packageName == XposedEntry.PKG_APP &&
            c.applicationInfo.uid == android.os.Process.myUid()
        private fun prefs(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)
        private fun systemDark(c: Context) =
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @JvmStatic fun themeMode(c: Context): Int = if (isApp(c)) prefs(c).getInt("theme_mode", 0) else 0
        @JvmStatic fun dynColor(c: Context): Boolean = !isApp(c) || prefs(c).getBoolean("dynamic_color", true)
        @JvmStatic fun amoled(c: Context): Boolean = isApp(c) && prefs(c).getBoolean("amoled", false)
        @JvmStatic fun amoledOn(c: Context): Boolean = amoled(c) && isDark(c)
        @JvmStatic fun seed(c: Context): Int = if (isApp(c)) prefs(c).getInt("seed", 0).coerceIn(SEEDS.indices) else 0
        @JvmStatic fun seedColor(c: Context): Int = SEEDS[seed(c)]
        @JvmField val SEEDS = ThemeScheme.seeds
        @JvmStatic fun seedName(c: Context, index: Int): String = Lang.t(c,
            arrayOf("粉色", "紫色", "蓝色", "绿色", "橙色")[index],
            arrayOf("Pink", "Purple", "Blue", "Green", "Orange")[index])

        private fun selection(c: Context, systemSurface: Boolean = false): ThemeScheme.Selection {
            // Notifications belong to System UI, not the app's overridden configuration.
            val dark = if (systemSurface) systemDark(c.applicationContext) else systemDark(c)
            return ThemeScheme.select(themeMode(c), dark, dynColor(c), Build.VERSION.SDK_INT,
                seed(c), amoled(c), systemSurface)
        }
        @JvmStatic fun isDark(c: Context): Boolean = selection(c).dark

        private fun wallpaperColor(c: Context, name: String): Int? {
            if (Build.VERSION.SDK_INT < 31) return null
            val id = c.resources.getIdentifier(name, "color", "android")
            return if (id == 0) null else try { c.getColor(id) } catch (_: Resources.NotFoundException) { null }
        }

        /** Only app Activities call this. Never change an injected Settings/GMS context. */
        @JvmStatic fun attach(c: Context): Context {
            val config = Configuration(c.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark(c)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            return c.createConfigurationContext(config)
        }

        /** Module resources and a local theme only; never apply module R IDs to host resources. */
        @JvmStatic fun moduleUiContext(host: Context): Context {
            val base = host.createPackageContext(XposedEntry.PKG_APP, Context.CONTEXT_IGNORE_SECURITY)
                .createConfigurationContext(Configuration(host.resources.configuration))
            return android.view.ContextThemeWrapper(base, R.style.Theme_FxxkMoondrop).also {
                applyTheme(it)
            }
        }

        @JvmStatic fun applyTheme(context: Context) {
            val s = selection(context)
            val styles = if (s.dark) intArrayOf(
                R.style.ThemeOverlay_FxxkMoondrop_Seed0_Dark, R.style.ThemeOverlay_FxxkMoondrop_Seed1_Dark,
                R.style.ThemeOverlay_FxxkMoondrop_Seed2_Dark, R.style.ThemeOverlay_FxxkMoondrop_Seed3_Dark,
                R.style.ThemeOverlay_FxxkMoondrop_Seed4_Dark
            ) else intArrayOf(
                R.style.ThemeOverlay_FxxkMoondrop_Seed0_Light, R.style.ThemeOverlay_FxxkMoondrop_Seed1_Light,
                R.style.ThemeOverlay_FxxkMoondrop_Seed2_Light, R.style.ThemeOverlay_FxxkMoondrop_Seed3_Light,
                R.style.ThemeOverlay_FxxkMoondrop_Seed4_Light
            )
            context.theme.applyStyle(styles[s.seed], true)
            if (s.wallpaper && ThemeScheme.wallpaperResources(s.dark).values.all { wallpaperColor(context, it) != null }) {
                context.theme.applyStyle(if (s.dark) R.style.ThemeOverlay_FxxkMoondrop_Wallpaper_Dark
                    else R.style.ThemeOverlay_FxxkMoondrop_Wallpaper_Light, true)
            }
            if (s.amoled) context.theme.applyStyle(R.style.ThemeOverlay_FxxkMoondrop_Amoled, true)
        }

        @JvmStatic fun systemBars(activity: Activity, pal: Palette) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !pal.dark
                isAppearanceLightNavigationBars = !pal.dark
            }
        }

        private fun roles(c: Context, s: ThemeScheme.Selection, systemSurface: Boolean): Map<String, Int> {
            val roles = ThemeScheme.resolve(s) { wallpaperColor(c, it) }
            if (isApp(c) || systemSurface) return roles
            // Read host attributes by host resource name, never module R IDs or host-private cfg.
            // Hosts without Material attributes use the same system-wallpaper/tonal fallback.
            return roles.mapValues { (name, fallback) ->
                val id = c.resources.getIdentifier(name, "attr", c.packageName)
                val value = TypedValue()
                if (id != 0 && c.theme.resolveAttribute(id, value, true) &&
                    value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data
                else fallback
            }
        }
    }

    /** One scheme for hand-colored Views; the numeric/resource overlay tests keep XML in lockstep. */
    class Palette(c: Context, systemSurface: Boolean = false) {
        private val selection = selection(c, systemSurface)
        private val roles = roles(c, selection, systemSurface)
        @JvmField val dark = selection.dark
        @JvmField val amoledOn = selection.amoled
        @JvmField val primary = roles.getValue("colorPrimary")
        @JvmField val onPrimary = roles.getValue("colorOnPrimary")
        @JvmField val container = roles.getValue("colorSecondaryContainer")
        @JvmField val onContainer = roles.getValue("colorOnSecondaryContainer")
        @JvmField val surface = roles.getValue("colorSurface")
        @JvmField val card = roles.getValue("colorSurfaceContainer")
        @JvmField val cardStroke = roles.getValue("colorOutlineVariant")
        @JvmField val onSurface = roles.getValue("colorOnSurface")
        @JvmField val onVariant = roles.getValue("colorOnSurfaceVariant")
        @JvmField val outline = roles.getValue("colorOutline")
        @JvmField val divider = roles.getValue("colorOutlineVariant")
        @JvmField val surfaceContainerHighest = roles.getValue("colorSurfaceContainerHighest")
        @JvmField val green = ThemeScheme.success(dark)
        @JvmField val red = roles.getValue("colorError")
        @JvmField val warning = roles.getValue("colorTertiary")
    }
}
