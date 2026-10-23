package com.fxxkmoondrop.secret.hook

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule

/**
 * Fast Pair 半屏：注入自绘降噪面板。
 *
 * 为什么不用原生卡片：半屏内容来自 GMS 的 Slice（RemoteViews），里面没有 Material 组件，
 * 做不出「按下圆角形变」。这里改成 hook 半屏 Fragment 的生命周期，往它自己的视图树塞一层真实 View。
 *
 * 注入点由 DexKitLocator 的 `dthi` 规则定位（特征串全 dex 唯一），
 * 点击下发复用蓝牙详情面板同一条跨进程通道 content://…prefs/dc_cmd。
 */
object HalfSheetPanelHook {

    private const val TAG = "FxxkMoondrop/HalfSheet"

    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = com.fxxkmoondrop.secret.DexKitLocator.resolveOrFallback(
                    cl, com.fxxkmoondrop.secret.DexKitLocator.DTHI, "dthi")
            val m = cls.methods.firstOrNull { it.name == "onViewCreated" }
                    ?: cls.methods.firstOrNull { it.name == "onCreateView" }
            if (m == null) {
                Log.d(TAG, "no lifecycle method on " + cls.name)
                return
            }
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val r = chain.proceed()
                try {
                    val root = com.fxxkmoondrop.secret.HookHelper.callMethod(chain.thisObject, "getView")
                            as? ViewGroup
                    if (root != null && root.findViewWithTag<View>(
                                    com.fxxkmoondrop.secret.FastPairPanel.TAG) == null) {
                        val ctx = root.context
                        val panel = com.fxxkmoondrop.secret.FastPairPanel.buildView(ctx) { mode ->
                            sendAnc(ctx, mode)
                        }
                        panel.tag = com.fxxkmoondrop.secret.FastPairPanel.TAG
                        root.addView(panel, ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT))
                        Log.d(TAG, "panel injected")
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "inject fail: " + t)
                }
                r
            }
            Log.d(TAG, "hook installed on " + cls.name + "#" + m.name)
        } catch (t: Throwable) {
            Log.d(TAG, "install failed: " + t)
        }
    }

    private fun sendAnc(ctx: Context, mode: Int) {
        try {
            val uri = Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd").buildUpon()
                    .appendQueryParameter("action", "set_anc")
                    .appendQueryParameter("mode", mode.toString())
                    .build()
            ctx.contentResolver.query(uri, null, null, null, null)?.close()
        } catch (t: Throwable) {
            Log.d(TAG, "sendAnc fail: " + t)
        }
    }
}
