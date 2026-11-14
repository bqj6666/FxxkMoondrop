package com.fxxkmoondrop.secret

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

/**
 * 关于页图标背后的加载动画 —— 直接用官方 Compose `LoadingIndicator`
 * （M3 Expressive 的形变圆形，非环形进度条），而不是自绘形状。
 *
 * 颜色取应用图标主色；ComposeView 自己会从视图树拿 LifecycleOwner / SavedStateRegistryOwner，
 * 因此可以直接塞进现有 View 层级里。
 */
object LoadingBadge {

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @JvmStatic
    fun create(ctx: Context, color: Int, sizeDp: Int = 104): ComposeView {
        val view = ComposeView(ctx)
        val arcColor = Color(color)
        val box = sizeDp.dp
        view.setContent {
            MaterialTheme {
                Indicator(arcColor, box)
            }
        }
        view.layoutParams = ViewGroup.LayoutParams(
                (sizeDp * ctx.resources.displayMetrics.density).toInt(),
                (sizeDp * ctx.resources.displayMetrics.density).toInt())
        return view
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun Indicator(color: Color, size: androidx.compose.ui.unit.Dp) {
        LoadingIndicator(
                color = color,
                modifier = Modifier.size(size))
    }
}
