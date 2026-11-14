package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * 让 **资源限定符** 跟应用自己的亮暗偏好走。
 *
 * 本应用的亮暗由 cfg.theme_mode（0 跟随系统 / 1 浅色 / 2 深色）决定，而 XML 里的
 * values-night/ 资源、以及 MaterialSwitch / Slider / BottomNavigationView 这类
 * 「主题属性驱动」的控件，默认只认 **系统** uiMode。
 *
 * 于是「应用设浅色、系统是深色」时会出现两套判断：
 *   - 我们自己算的 Palette（按 theme_mode）→ 浅色
 *   - XML 主题 / 主题属性驱动的控件（按系统）→ 深色
 * 结果是同一页里有的跟随系统、有的不跟随，看着像主题坏了。
 *
 * 在 attachBaseContext 里改写 uiMode 的 night 位，两边就都读同一份判断。
 * 「跟随系统」档不改写，保持原样。
 */
object NightMode {

    @JvmStatic
    fun wrap(base: Context): Context {
        val mode = try {
            ThemeUtil.themeMode(base)
        } catch (_: Throwable) {
            return base
        }
        val want = when (mode) {
            1 -> Configuration.UI_MODE_NIGHT_NO
            2 -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        return try {
            val cfg = Configuration(base.resources.configuration)
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or want
            base.createConfigurationContext(cfg)
        } catch (_: Throwable) {
            base
        }
    }
}

/**
 * 主题变更后「整页」重建。
 *
 * 只重建内容（rebuild body）不够：窗口背景、根容器背景、状态栏配色都在 onCreate
 * 时按当时的 Palette 定死，于是会出现「浅底 + 深卡片」这种半新半旧的状态。
 * 正确做法是 recreate()，让整页（含资源层）按新偏好重来。
 *
 * ⚠️ 记录必须是 **每个 Activity 实例自己的**，不能放全局：
 * 主题页先创建、已把全局值改成新值，主页 onResume 再比较就永远相等，于是不重建
 * ——「在主题页里生效、退回主页仍是旧配色」正是这么来的。
 */
/**
 * 平滑重建：先把窗口背景改成**新配色**，再 recreate。
 *
 * 直接 recreate() 时，窗口会先用旧主题的 windowBackground 铺一帧，
 * 于是切深/浅色时能看见「闪一下」。把背景提前换成目标 palette.surface，
 * 过渡那一帧就是新色，观感上不再闪。
 */
private fun Activity.recreateSmooth() {
    runCatching {
        val p = ThemeUtil.Palette(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.surface))
    }
    recreate()
}

abstract class ThemeAware : Activity() {
    private var appliedMode = Int.MIN_VALUE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(NightMode.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedMode = ThemeUtil.themeMode(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // 所有页面统一的入场动效（标题/卡片依次淡入 + 上移）
        M3Ui.staggerInContent(findViewById(android.R.id.content))
    }

    override fun onResume() {
        super.onResume()
        val now = ThemeUtil.themeMode(this)
        if (appliedMode != Int.MIN_VALUE && now != appliedMode) {
            appliedMode = now
            recreateSmooth()
        }
    }
}

abstract class ThemeAwareFragmentActivity : FragmentActivity() {
    private var appliedMode = Int.MIN_VALUE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(NightMode.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedMode = ThemeUtil.themeMode(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        M3Ui.staggerInContent(findViewById(android.R.id.content))
    }

    override fun onResume() {
        super.onResume()
        val now = ThemeUtil.themeMode(this)
        if (appliedMode != Int.MIN_VALUE && now != appliedMode) {
            appliedMode = now
            recreateSmooth()
        }
    }
}

/** 所有二级页的基类：统一应用亮暗偏好到资源层 + 统一入场动效 + 主题变更自动重建。 */
open class BaseActivity : ThemeAware()

/** 主界面（需要 Fragment 支持）。 */
open class BaseFragmentActivity : ThemeAwareFragmentActivity()
