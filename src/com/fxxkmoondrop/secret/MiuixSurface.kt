package com.fxxkmoondrop.secret

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemeColorSpec

/**
 * HyperOS 观感轨的渲染器。
 *
 * ## 隔离的理由
 *
 * Miuix 与 Compose 全是 `@Composable`，生成代码依赖 Compose 编译器插件。
 * 隔离在这一个文件里，好处是 **Material 轨完全不碰 Compose 加载路径**：
 * 本文件不被引用时，R8 会把整棵 Compose/Miuix 树当死代码裁掉
 * （P1 阶段实测：开 R8 后产物里 miuix / androidx.compose 均为 0 处匹配）。
 * 将来若要回退 Miuix，删这一个文件即可。
 *
 * ## 亮暗正交
 *
 * 亮暗由既有的 [ThemeUtil]（`theme_mode` 0/1/2）决定，风格由 [UiStyle] 决定，
 * 二者互不干扰 —— 这是 P1 定下的设计。Miuix 的 [ColorSchemeMode] 恰好也是
 * System/Light/Dark 三档，与我们既有的 `theme_mode` 同构，因此直接对应，
 * 不引入第二套亮暗逻辑。
 *
 * 设计思路参考 `silverpoetry/HyperEars` 的 `ui/theme`（AGPL-3.0，仅参考
 * 「亮暗 ⊥ 风格」「切换不丢设置」两点设计，代码为本项目独立实现）。
 */
object MiuixSurface {

    /** 当前是否应走 Miuix 轨。非 MIUIX 时调用方继续用现有 M3 界面。 */
    @JvmStatic
    fun enabled(c: android.content.Context?): Boolean =
            UiStyle.current(c) == UiStyle.MIUIX

    /**
     * 亮暗偏好 → Miuix 的 [ColorSchemeMode]。
     *
     * 复用 [ThemeUtil.themeMode]（0 跟随系统 / 1 浅色 / 2 深色），
     * 映射到 Miuix 的 System/Light/Dark。这里只做**值**的翻译，
     * 「实际是不是深色」仍由 Miuix 依据系统配置判定（System 档就是如此），
     * 避免我们与 Miuix 各算一套导致不一致。
     */
    @JvmStatic
    fun colorSchemeMode(c: android.content.Context?): ColorSchemeMode {
        val mode = try {
            c?.let { ThemeUtil.themeMode(it) } ?: 0
        } catch (_: Throwable) {
            0
        }
        return when (mode) {
            1 -> ColorSchemeMode.Light
            2 -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }
    }

    /**
     * 界面缩放（仅 Miuix 轨支持）。存储值可能损坏，统一经 [UiStyle.clampScale]
     * 挡掉 NaN/Inf —— 直接传给 setScaleX 会让渲染崩掉。
     */
    @JvmStatic
    fun scale(c: android.content.Context?): Float {
        val ctx = c ?: return UiStyle.DEFAULT_SCALE
        return UiStyle.clampScale(
                try {
                    ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                            .getFloat(UiStyle.MIUIX.scaleKey, UiStyle.DEFAULT_SCALE)
                } catch (_: Throwable) {
                    UiStyle.DEFAULT_SCALE
                }
        )
    }

    /**
     * 探测 Compose/Miuix 运行时是否真的可加载。
     *
     * 用于**诊断**而非静默降级：若 Miuix 轨选了但运行时缺失，用户需要知道
     * 「为什么界面没变」，而不是看到毫无变化却无处可查。
     */
    @JvmStatic
    fun runtimeAvailable(): Boolean = try {
        Class.forName("top.yukonga.miuix.kmp.theme.MiuixTheme")
        Class.forName("androidx.compose.runtime.Composer")
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * HyperOS 轨的 Compose 容器。
 *
 * 抽出来单独一层，是为了让「用 Miuix 渲染」这件事在代码上只有一个入口：
 * 将来加设置页 Miuix 化、加弹窗 Miuix 化，都套这一层，
 * 不必各自重复处理 ThemeController 与缩放。
 */
@Composable
internal fun MiuixSurface(
    colorSchemeMode: ColorSchemeMode,
    content: @Composable () -> Unit,
) {
    // ⚠️ Miuix 0.9.2 的 MiuixTheme 第一重载只接受 ThemeController，
    // 没有「直接传 colorSchemeMode + isDynamicColor」的便捷重载 ——
    // 这一点是读 miuix-ui-android-0.9.2-sources.jar 的 MiuixTheme.kt 确认的，
    // 不要凭印象改回 colorSchemeMode = ... 的写法。
    //
    // keyColor 传 null：让 Miuix 用自己的默认配色。动态取色（壁纸取色）由我们
    // 既有的 ThemeUtil 管理（种子色 + 动态取色开关），与 Miuix 的 Monet 系是两套
    // 机制，两套同时生效会互相覆盖。
    MiuixTheme(
        controller = ThemeController(
            colorSchemeMode = colorSchemeMode,
            colorSpec = ThemeColorSpec.Spec2021,
        )
    ) {
        content()
    }
}

/**
 * P2 的探针界面：证明 HyperOS 轨真的能渲染。
 *
 * 刻意做得很薄 —— 这一步只验证「Miuix + Compose 能在本项目编译并运行」，
 * 完整的设置页 / 弹窗 Miuix 化在后续步骤展开。
 *
 * 存在这个 composable 还有一个**必要**的副作用：它让 Compose/Miuix 有了
 * 真实调用点，R8 不会再把整棵树裁掉。在此之前开 R8，得到的是
 * 「体积很小但 HyperOS 轨是空的」产物。
 */
@Composable
internal fun MiuixTrackProbe(summary: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "HyperOS 观感轨",
                style = MiuixTheme.textStyles.title1,
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
