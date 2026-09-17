package com.fxxkmoondrop.secret

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/**
 * 自动检查模块 App 各项必要权限，缺失时告知用户并可跳转授权。
 * 含 Root / FastPairHook(LSPosed) 运行模式项；GAIA 直连项为动态真实状态。
 * 不检查悬浮窗：本模块不创建悬浮窗，故不申请该权限。
 */
class PermissionChecker {
    companion object {
        // 跳转动作类型
        const val ACTION_NONE = 0      // 仅提示
        const val ACTION_RUNTIME = 1   // 运行时权限（requestPermissions）
        const val ACTION_BATTERY = 3   // 电池优化设置页
        /** 3.0.5: 清掉 root / hook 的负结果并重新探测（未授权时 root 会整体隐藏，必须给重试入口）。 */
        const val ACTION_ROOT_RECHECK = 4

        /** 返回全部检查项 */
        @JvmStatic
        fun checkAll(ctx: Context): List<Item> {
            val list = ArrayList<Item>()

            // 1. 蓝牙权限（Android 12+ 运行时）
            val btOk = Build.VERSION.SDK_INT < 31 ||
                    (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED)
            list.add(Item(Lang.t(ctx, "蓝牙权限", "Bluetooth permission"),
                    btOk,
                    if (btOk) Lang.t(ctx, "已授予", "Granted") else Lang.t(ctx, "未授予，无法扫描 / 连接耳机", "Not granted, cannot scan / connect earbuds"),
                    ACTION_RUNTIME, 1))

            // 2. 通知权限（Android 13+ 运行时）
            val notifOk = Build.VERSION.SDK_INT < 33 ||
                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            list.add(Item(Lang.t(ctx, "通知权限", "Notification permission"),
                    notifOk,
                    if (notifOk) Lang.t(ctx, "已授予", "Granted") else Lang.t(ctx, "未授予，耳机弹窗通知将无法显示", "Not granted, earbuds popup notifications won\'t show"),
                    ACTION_RUNTIME, 2))

            // 3. 电池优化白名单（建议项）
            // 说明：原本还有一项「悬浮窗权限」，但本模块从不创建悬浮窗 ——
            // 弹窗是 GMS 进程内的既有窗口，受 GMS 自身权限约束，与本应用无关；
            // 该权限已从清单移除，检查项一并删除。
            // 4. 电池优化白名单（建议项）
            val battOk = try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } catch (_: Exception) {
                true
            }
            list.add(Item(Lang.t(ctx, "电池优化白名单", "Battery optimization whitelist"),
                    battOk,
                    if (battOk) Lang.t(ctx, "已加入", "Exempted") else Lang.t(ctx, "未加入，点按一键申请（否则后台可能被系统冻结）",
                            "Not exempted, tap to request (otherwise background may be frozen)"),
                    ACTION_BATTERY, 0))

            // 3.5 Root 权限（3.0.5 新增，带显式重试入口）
            //
            // 为什么必须有重试：未在 Root 管理器里授权时，root 会**整体隐藏**
            // （FolkPatch 的 pathhide 等），App 侧看到的和「设备根本没 root」一模一样，
            // 因此失败绝不能当结论。用户在管理器里补上授权后点这一项即可重新检测。
            val rootBin = RootShell.binary()
            val rootOk = rootBin != null || EnvProbe.isRooted()
            list.add(Item(Lang.t(ctx, "Root 权限", "Root access"), rootOk,
                    when {
                        rootOk -> Lang.t(ctx, "可用，入口=" + (rootBin ?: "su"),
                                "Available, entry=" + (rootBin ?: "su"))
                        RootShell.sawBinaryLastProbe() -> Lang.t(ctx,
                                "已找到 root 入口但未取得授权。请在 Root 管理器中授权后点击此项重新检测",
                                "Root entry found but not granted. Authorize it in your root manager, then tap here to re-check")
                        else -> Lang.t(ctx,
                                "未检测到 root 入口。若设备已 root：请先在 Root 管理器中把本应用加入授权列表，再点击此项重新检测",
                                "No root entry detected. If rooted: allow this app in your root manager first, then tap here to re-check")
                    },
                    ACTION_ROOT_RECHECK, 0))

            // 4. 运行模式（显示项，非缺失项）
            //
            // 3.0.5: 三态 —— 只看 root 会把「有模块无 root」误报成无 Root 模式，
            // 而 GMS 桥接与官方面板注入其实由 LSPosed 模块承担，不需要 App 有 root。
            val rooted = rootOk
            // 这里用缓存值（不阻塞）；未探测过时视为未知，落到「仅 root」分支。
            val hookCached = EnvProbe.hookActiveCached()
            list.add(Item(Lang.t(ctx, "运行模式", "Run mode"), true,
                    when {
                        rooted && hookCached == true -> Lang.t(ctx,
                                "Root 模式：GMS 桥接与官方面板注入可用，Root 增强功能也可用",
                                "Root mode: GMS bridge & official panel injection available, plus root enhancements")
                        hookCached == true -> Lang.t(ctx,
                                "模块模式：GMS 桥接与官方面板注入可用；Root 增强功能不可用",
                                "Module mode: GMS bridge & official panel injection available; root enhancements unavailable")
                        rooted -> Lang.t(ctx,
                                "Root 模式：Root 增强功能可用；FastPairHook 模块未激活，将使用内置自扫",
                                "Root mode: root enhancements available; FastPairHook inactive, built-in scan will be used")
                        else -> Lang.t(ctx,
                                "无 Root 模式：仅通知栏与主界面控制降噪（GAIA 直连，无需 Root）",
                                "No-root mode: noise cancellation from notification & main UI only (GAIA direct)")
                    },
                    ACTION_NONE, 0))

            // 5. FastPairHook 模块（LSPosed）
            val hookActive = EnvProbe.isFastPairHookActive(ctx)
            list.add(Item(Lang.t(ctx, "FastPairHook 模块（LSPosed）", "FastPairHook module (LSPosed)"),
                    hookActive,
                    if (hookActive) Lang.t(ctx, "已激活（GMS 扫描桥接正常）", "Active (GMS scan bridging OK)")
                    else Lang.t(ctx, "未激活；纯净环境将自动使用内置自扫", "Not active; clean env will use built-in scan"),
                    ACTION_NONE, 0))

            // 6. GAIA 直连链路（动态真实状态，不恒 true）
            val gaiaOk: Boolean
            val gaiaDetail: String
            if (hookActive) {
                gaiaOk = true
                gaiaDetail = Lang.t(ctx, "FastPairHook 桥接：LE 地址发现 → GAIA 直连", "FastPairHook bridge: LE discover → GAIA direct")
            } else if (!rooted) {
                gaiaOk = true
                gaiaDetail = Lang.t(ctx, "备用模式：应用内置 BLE 自扫（无需 Root）", "Fallback: built-in BLE scan (no Root needed)")
            } else {
                gaiaOk = false
                gaiaDetail = Lang.t(ctx, "检测到 Root 但模块未激活；请启用 FastPairHook 或使用纯净环境", "Root detected but module not active; enable FastPairHook or use clean env")
            }
            list.add(Item(Lang.t(ctx, "GAIA 直连", "GAIA direct link"), gaiaOk, gaiaDetail, ACTION_NONE, 0))

            return list
        }

        /** 缺失项数量 */
        @JvmStatic
        fun countMissing(items: List<Item>): Int {
            var n = 0
            for (it in items) if (!it.ok) n++
            return n
        }
    }

    class Item(
        @JvmField val name: String,
        @JvmField val ok: Boolean,
        @JvmField val detail: String,
        @JvmField val action: Int,       // 缺失时的修复动作
        @JvmField val requestCode: Int   // 运行时权限请求码
    )
}
