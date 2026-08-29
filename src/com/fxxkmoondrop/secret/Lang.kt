package com.fxxkmoondrop.secret

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * alpha2.38.10: 显示层中英文切换。
 *
 * 语言偏好存在 "cfg" SharedPreferences 的 "lang" 键：0=跟随系统(auto) 1=中文 2=English。
 * 显示文字统一走 [t]/[t3] 取当前语言；弹窗(注入 GMS 进程)通过 PrefsProvider 跨进程读 lang。
 *
 * 仅翻"显示文字"，不改任何蓝牙/ANC/图标/逻辑；GMS 原生按钮(确定/关闭)不动。
 */
object Lang {

    private const val LANG_AUTO = 0
    private const val LANG_ZH = 1
    private const val LANG_EN = 2

    /** 当前语言：true=中文 false=英文。进程内缓存，首次/切语言后刷新。 */
    @Volatile
    private var zh: Boolean = true

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** 读原始语言偏好：0=auto 1=zh 2=en */
    fun mode(ctx: Context): Int = sp(ctx).getInt("lang", LANG_AUTO)

    /** 系统是否为中文（auto 时使用） */
    private fun systemZh(): Boolean =
        Locale.getDefault().language.startsWith("zh")

    /** 解析当前是否中文 */
    fun isZh(ctx: Context): Boolean = when (mode(ctx)) {
        LANG_ZH -> true
        LANG_EN -> false
        else -> systemZh()
    }

    /** 刷新进程内缓存（界面构造/弹窗注入前调用） */
    fun refresh(ctx: Context) {
        zh = isZh(ctx)
    }

    /** 无格式化翻译：zh=中文原文，en=英文原文。需在调用前已 refresh(ctx)。 */
    fun t(zh: String, en: String): String = if (this.zh) zh else en

    /** 带 context 的翻译（内部先 refresh）：适合弹窗/无固定 Activity 的注入点。 */
    fun t(ctx: Context, zh: String, en: String): String {
        refresh(ctx)
        return t(zh, en)
    }

    /** 数值拼接翻译：用于 "左耳 92%  ·  右耳 88%" 这种含数字的文案。 */
    fun tf(zhFmt: String, enFmt: String, vararg args: Any): String {
        val fmt = if (zh) zhFmt else enFmt
        return String.format(Locale.US, fmt, *args)
    }
}
