package com.fxxkmoondrop.secret

import android.content.Context
import java.util.Locale

/**
 * 界面风格轨（与亮暗正交）。
 *
 * ## 二维设计
 *
 * 主题由两个**互不依赖**的维度决定，任意组合都合法：
 *
 * ```
 *   亮暗  ThemeUtil.themeMode(c)   0 跟随系统 / 1 浅色 / 2 深色   ← 沿用既有实现
 *   风格  UiStyle                  MATERIAL / MIUIX
 * ```
 *
 * 所以「Material 浅色」「Miuix 深色」「Miuix 跟随系统」都是正常状态，
 * 不是一个下拉框里的四个互斥选项。
 *
 * ## 为什么默认 MATERIAL
 *
 * 存量用户升级后看到的界面必须与上一版**完全一致**。Material 轨就是现有的
 * View + Material 1.14 实现，不引入任何新依赖；只有用户主动切到 Miuix 轨，
 * 才会触碰 Compose / Miuix 相关代码路径。
 *
 * ## 切换不丢设置
 *
 * 借鉴 `silverpoetry/HyperEars`（AGPL-3.0，仅参考该设计思路）的做法：
 * 各轨的专属偏好各有独立键，切换只是「换一个渲染器 + 换一组键」，
 * **不清空任何既有值**。于是用户从 Miuix 切回 Material 再切回来，
 * 之前调好的 Miuix 模糊、悬浮导航栏、界面缩放都还在。
 *
 * 反之如果把两套设置塞进同一个键互相对齐，切一次就丢一次。
 */
enum class UiStyle(
    /** 存在 SP 里的键值名。刻意用字符串名而非序号：以后增删档位不会错位。 */
    val storedKey: String,
) {
    /** 现有 View + Material 1.14 实现，默认轨，零新依赖。 */
    MATERIAL("MATERIAL"),

    /** HyperOS 观感轨（Compose + Miuix），P1 期只落偏好存储，渲染器在 P2 实装。 */
    MIUIX("MIUIX");

    // ── Miuix 轨专属键。Material 轨沿用既有 cfg 键，不新增。──
    val navigatBlurKey: String get() = "ui_miuix_blur"
    val floatingNavKey: String get() = "ui_miuix_floating_nav"
    val scaleKey: String get() = "ui_miuix_scale"

    /** 本轨独占的偏好键。用于自检「两轨不串键」。 */
    fun prefKeys(): List<String> = when (this) {
        MATERIAL -> emptyList()      // 全部沿用既有 cfg 键
        MIUIX -> listOf(navigatBlurKey, floatingNavKey, scaleKey)
    }

    companion object {
        private const val SP = "cfg"
        private const val KEY_STYLE = "ui_style"

        const val MIN_SCALE = 0.9f
        const val DEFAULT_SCALE = 1.0f
        const val MAX_SCALE = 1.1f

        /**
         * 解析存储值；**任何异常输入都退回 [MATERIAL]**。
         *
         * 退回而非抛异常是刻意的：SP 里的值可能被旧版本、用户手改或迁移写坏，
         * 主题读不出来不该让整个设置页崩掉，最坏情况只是回到默认观感。
         */
        @JvmStatic
        fun fromStored(raw: String?): UiStyle {
            val n = raw?.trim()?.uppercase(Locale.ROOT) ?: return MATERIAL
            return entries.firstOrNull { it.storedKey == n } ?: MATERIAL
        }

        /** 当前风格。缺省 = [MATERIAL]。 */
        @JvmStatic
        fun current(c: Context?): UiStyle {
            val ctx = c ?: return MATERIAL
            return try {
                fromStored(ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                        .getString(KEY_STYLE, null))
            } catch (_: Throwable) {
                MATERIAL
            }
        }

        /** 写入风格。 */
        @JvmStatic
        fun set(c: Context?, style: UiStyle) {
            val ctx = c ?: return
            try {
                ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                        .edit().putString(KEY_STYLE, style.storedKey).apply()
            } catch (_: Throwable) { /* 写不进去就用默认轨，不影响使用 */ }
        }

        /**
         * 界面缩放钳制。
         *
         * NaN / Inf 必须挡住：直接传给 `View.setScaleX` 会让渲染崩掉，
         * 而 SP 被写坏时出现这种值的概率并不低。
         */
        @JvmStatic
        fun clampScale(value: Float): Float {
            if (!value.isFinite()) return DEFAULT_SCALE
            return value.coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }
}
