package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import com.fxxkmoondrop.secret.hook.FastPairHookEntry
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed 入口：设置页注入 + Moondrop App silent launch + ANC 命令路由 + 蓝牙 A2DP 感知。
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        if (PKG_SETTINGS == loadPackageParam.packageName) {
            hookSettings(loadPackageParam)
        } else if (PKG_MOONDROP == loadPackageParam.packageName) {
            hookMoondrop(loadPackageParam)
        } else if (PKG_BLUETOOTH == loadPackageParam.packageName) {
            hookBluetooth(loadPackageParam)
        } else if (PKG_GMS == loadPackageParam.packageName) {
            FastPairHookEntry().handleLoadPackage(loadPackageParam)
        }
    }

    /** v3.17: hook 系统蓝牙 A2DP 连接状态：连接->静默拉起 Moondrop，断开->停止进程。 */
    private fun hookBluetooth(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        try {
            val deviceCls = XposedHelpers.findClass(
                    "com.android.bluetooth.a2dp.A2dpService\$A2dpDevice", loadPackageParam.classLoader)
            XposedHelpers.findAndHookMethod(
                    "com.android.bluetooth.a2dp.A2dpService", loadPackageParam.classLoader,
                    "onProfileConnectionStateChanged", deviceCls, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handleA2dpState(param)
                        }
                    })
            XposedBridge.log("[FxxkMoondrop] bluetooth A2dpService hooked (3-arg)")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] bluetooth hook 3-arg failed: " + th)
        }
        try {
            val deviceCls = XposedHelpers.findClass(
                    "com.android.bluetooth.a2dp.A2dpService\$A2dpDevice", loadPackageParam.classLoader)
            XposedHelpers.findAndHookMethod(
                    "com.android.bluetooth.a2dp.A2dpService", loadPackageParam.classLoader,
                    "onProfileConnectionStateChanged", deviceCls, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handleA2dpState(param)
                        }
                    })
            XposedBridge.log("[FxxkMoondrop] bluetooth A2dpService hooked (4-arg)")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] bluetooth hook 4-arg failed: " + th)
        }
    }

    private fun handleA2dpState(param: XC_MethodHook.MethodHookParam) {
        try {
            val device = param.args[0] ?: return
            val to = param.args[2] as? Int ?: return
            var name: String? = null
            try { name = XposedHelpers.callMethod(device, "getName") as String? } catch (_: Throwable) { }
            if (name == null) {
                try { name = XposedHelpers.getObjectField(device, "mName") as String? } catch (_: Throwable) { }
            }
            if (name == null) return
            if (!name.lowercase().contains("moondrop")) return
            if (to == 2) {
                XposedBridge.log("[FxxkMoondrop] A2DP connected: " + name + " -> silent launch + wake service")
                execSilent("am start -n " + PKG_MOONDROP + "/.MainActivity --ez fxxk_silent true --exclude-from-recents")
                execSilent("am broadcast -a com.fxxkmoondrop.secret.BT_EVENT --es evt connected")
            } else if (to == 0) {
                XposedBridge.log("[FxxkMoondrop] A2DP disconnected: " + name + " -> stop app + wake service")
                execSilent("am force-stop " + PKG_MOONDROP)
                execSilent("am broadcast -a com.fxxkmoondrop.secret.BT_EVENT --es evt disconnected")
            }
        } catch (th: Throwable) {
            XposedBridge.log(th)
        }
    }

    private fun execSilent(cmd: String) {
        try {
            XposedBridge.log("[FxxkMoondrop] exec: " + cmd)
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            p.waitFor()
        } catch (th: Throwable) {
            XposedBridge.log(th)
        }
    }

    private fun hookSettings(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        try {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(methodHookParam: MethodHookParam) {
                    try {
                        val thisObj = methodHookParam.thisObject
                        if (!XposedHelpers.findClass(CLS_CONN_PREFS, loadPackageParam.classLoader)
                                        .isInstance(thisObj)) return
                        val prefScreen = XposedHelpers.callMethod(thisObj, "getPreferenceScreen") ?: return
                        if (XposedHelpers.callMethod(prefScreen, "findPreference", KEY_ENTRY) != null) return
                        val context = XposedHelpers.callMethod(thisObj, "getContext") as? Context ?: return
                        val pref = XposedHelpers.findClass("androidx.preference.Preference",
                                loadPackageParam.classLoader)
                                .getConstructor(Context::class.java).newInstance(context)
                        XposedHelpers.callMethod(pref, "setKey", KEY_ENTRY)
                        XposedHelpers.callMethod(pref, "setTitle", "Moondrop 耳机控制")
                        XposedHelpers.callMethod(pref, "setSummary", "FxxkMoondrop：降噪切换 / 耳机功能 / 连接弹窗")
                        val intent = Intent()
                        intent.setClassName(PKG_APP, "com.fxxkmoondrop.secret.MainActivity")
                        XposedHelpers.callMethod(pref, "setIntent", intent)
                        XposedHelpers.callMethod(prefScreen, "addPreference", pref)
                        XposedBridge.log("[FxxkMoondrop] entry injected into ConnectionPreferences")
                    } catch (th: Throwable) {
                        XposedBridge.log(th)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    loadPackageParam.classLoader, "onCreatePreferences",
                    Bundle::class.java, String::class.java, hook)
            XposedBridge.log("[FxxkMoondrop] hookSettings: DashboardFragment.onCreatePreferences hooked")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] hookSettings failed: " + th)
        }
    }

    private fun hookMoondrop(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        // v3.16: silent launch -- fxxk_silent=true 时抑制 UI，仅让 GAIA 通讯服务后台初始化
        try {
            val h1 = object : XC_MethodHook() {
                override fun beforeHookedMethod(methodHookParam: MethodHookParam) {
                    try {
                        val activity = methodHookParam.thisObject as Activity
                        val intent = activity.intent
                        if (intent != null && intent.getBooleanExtra("fxxk_silent", false)) {
                            // android.R 内置主题，避免模块资源缺失导致崩溃（v3.16 bug 修复）
                            activity.setTheme(android.R.style.Theme_Translucent_NoTitleBar)
                            activity.window.addFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                            try {
                                activity.finishAndRemoveTask()
                            } catch (_: Throwable) {
                                activity.finish()
                            }
                            XposedBridge.log("[FxxkMoondrop] silent launch: UI suppressed, GAIA service initializing")
                        }
                    } catch (th: Throwable) {
                        XposedBridge.log(th)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                    "com.moondroplab.moondrop.moondrop_app.MainActivity",
                    loadPackageParam.classLoader, "onCreate", Bundle::class.java, h1)
            XposedBridge.log("[FxxkMoondrop] MainActivity.onCreate hooked (silent launch)")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] silent launch hook failed: " + th)
        }
        try {
            val h2 = object : XC_MethodHook() {
                override fun afterHookedMethod(methodHookParam: MethodHookParam) {
                    try {
                        val context = methodHookParam.thisObject as? Context ?: return
                        registerCmdReceiver(context.applicationContext, loadPackageParam)
                    } catch (th: Throwable) {
                        XposedBridge.log(th)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                    "android.app.Application", loadPackageParam.classLoader,
                    "onCreate", h2)
            XposedBridge.log("[FxxkMoondrop] Application.onCreate hooked")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] Application hook failed: " + th)
        }
        try {
            val h3 = object : XC_MethodHook() {
                override fun afterHookedMethod(methodHookParam: MethodHookParam) {
                    try {
                        broadcastAncMode(2, methodHookParam.args[0] as Int)
                    } catch (th: Throwable) {
                        XposedBridge.log(th)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                    CLS_ANCV2_SUB, loadPackageParam.classLoader, "onAncMode",
                    Int::class.javaPrimitiveType, h3)
            XposedBridge.log("[FxxkMoondrop] onAncMode hooked")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] onAncMode hook failed: " + th)
        }
        try {
            val h4 = object : XC_MethodHook() {
                override fun beforeHookedMethod(methodHookParam: MethodHookParam) {
                    try {
                        val ui = methodHookParam.args[0] as Int
                        val v2 = uiToAncV2(ui)
                        if (v2 < 0) {
                            methodHookParam.result = null
                            XposedBridge.log("[FxxkMoondrop] setCurrentMode ignore ui=" + ui)
                            return
                        }
                        methodHookParam.args[0] = v2
                        XposedBridge.log("[FxxkMoondrop] setCurrentMode ui=" + ui + " -> ancV2=" + v2)
                    } catch (th: Throwable) {
                        XposedBridge.log(th)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                    "com.qualcomm.qti.gaiaclient.core.gaia.qtil.plugins.v3.V3AncV2Plugin",
                    loadPackageParam.classLoader, "setCurrentMode",
                    Int::class.javaPrimitiveType, h4)
            XposedBridge.log("[FxxkMoondrop] setCurrentMode hooked")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] setCurrentMode hook failed: " + th)
        }
        try {
            val h5 = object : XC_MethodHook() {
                override fun afterHookedMethod(methodHookParam: MethodHookParam) {
                    try {
                        val objAC = methodHookParam.args[0] ?: return
                        val objValue = methodHookParam.args[1] ?: return
                        if ((objAC as Enum<*>).name != "MODE") return
                        val iValue = XposedHelpers.callMethod(objValue, "getValue") as Int
                        XposedBridge.log("[FxxkMoondrop] AC onInfo MODE value=" + iValue)
                        broadcastAncMode(1, iValue)
                    } catch (th: Throwable) {
                        XposedBridge.log(th)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                    "com.qualcomm.qti.gaiaclient.repository.audiocuration.AudioCurationRepositoryImpl\$1",
                    loadPackageParam.classLoader, "onInfo",
                    XposedHelpers.findClass(CLS_ACINFO, loadPackageParam.classLoader),
                    Any::class.java, h5)
            XposedBridge.log("[FxxkMoondrop] AudioCuration onInfo hooked")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] AudioCuration onInfo hook failed: " + th)
        }
    }

    fun registerCmdReceiver(context: Context, loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        moondropCtx = context
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context2: Context, intent: Intent) {
                val stringExtra = intent.getStringExtra("cmd")
                val intExtra = intent.getIntExtra("mode", -1)
                try {
                    when (stringExtra) {
                        "set_anc_v2_mode" -> setAncV2Mode(loadPackageParam, intExtra)
                        "get_anc_v2_mode" -> fetchAncV2Mode(loadPackageParam)
                        "set_anc_v1_mode" -> setAncV1Mode(loadPackageParam, intExtra)
                        "get_anc_v1_mode" -> fetchAncV1Mode(loadPackageParam)
                        "ping" -> {
                            XposedBridge.log("[FxxkMoondrop] ping received")
                            try {
                                context2.sendBroadcast(Intent("com.fxxkmoondrop.PONG"))
                            } catch (thp: Throwable) {
                                XposedBridge.log(thp)
                            }
                        }
                    }
                } catch (th: Throwable) {
                    XposedBridge.log(th)
                }
            }
        }
        val intentFilter = IntentFilter("com.fxxkmoondrop.ACTION_CMD")
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(broadcastReceiver, intentFilter)
            }
            XposedBridge.log("[FxxkMoondrop] cmd receiver registered")
        } catch (th: Throwable) {
            XposedBridge.log("[FxxkMoondrop] registerReceiver failed: " + th)
        }
    }

    fun setAncV2Mode(loadPackageParam: XC_LoadPackage.LoadPackageParam, i: Int) {
        if (i < 0 || i >= 6) return
        val qtil = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_GAIA_SERVICE, loadPackageParam.classLoader),
                "getQtilManager") ?: run {
            XposedBridge.log("[FxxkMoondrop] QtilManager null, fallback to AncV1")
            setAncV1Mode(loadPackageParam, i)
            return
        }
        val plugin = XposedHelpers.callMethod(qtil, "getAncV2Plugin") ?: run {
            XposedBridge.log("[FxxkMoondrop] AncV2Plugin null, fallback to AncV1")
            setAncV1Mode(loadPackageParam, i)
            return
        }
        // 直接传 UI 模式，由 V3AncV2Plugin.setCurrentMode 钩子统一映射为 ANC_V2 协议值
        XposedHelpers.callMethod(plugin, "setCurrentMode", i)
        XposedBridge.log("[FxxkMoondrop] setAncV2Mode ui=" + i + " (mapped in plugin hook)")
    }

    fun fetchAncV2Mode(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val qtil = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_GAIA_SERVICE, loadPackageParam.classLoader),
                "getQtilManager") ?: return
        val plugin = XposedHelpers.callMethod(qtil, "getAncV2Plugin") ?: return
        XposedHelpers.callMethod(plugin, "fetchCurrentMode")
    }

    fun setAncV1Mode(loadPackageParam: XC_LoadPackage.LoadPackageParam, i: Int) {
        val v1 = mapToAncV1(i)
        if (v1 < 0) {
            XposedBridge.log("[FxxkMoondrop] setAncV1Mode ignore mode " + i)
            return
        }
        val app = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_GAIA_APP, loadPackageParam.classLoader),
                "getInstance") ?: return
        val repo = XposedHelpers.getObjectField(app, "audioCurationRepository") ?: return
        XposedHelpers.callMethod(repo, "setMode", moondropCtx, v1)
        XposedBridge.log("[FxxkMoondrop] setAncV1Mode ui=" + i + " v1=" + v1)
    }

    fun fetchAncV1Mode(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val app = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_GAIA_APP, loadPackageParam.classLoader),
                "getInstance") ?: return
        val repo = XposedHelpers.getObjectField(app, "audioCurationRepository") ?: return
        val acModeEnum = (XposedHelpers.findClass(CLS_ACINFO, loadPackageParam.classLoader) as Class<*>)
                .enumConstants?.first { (it as Enum<*>).name == "MODE" } ?: return
        XposedHelpers.callMethod(repo, "fetchACInfo", moondropCtx, acModeEnum)
    }

    fun broadcastAncMode(version: Int, mode: Int) {
        try {
            val ctx = moondropCtx ?: return
            val intent = Intent("com.fxxkmoondrop.ACTION_ANC_MODE")
            intent.setPackage(PKG_APP)
            intent.putExtra("version", version)
            intent.putExtra("mode", mode)
            ctx.sendBroadcast(intent)
            XposedBridge.log("[FxxkMoondrop] ANC mode broadcast v" + version + " mode=" + mode)
        } catch (th: Throwable) {
            XposedBridge.log(th)
        }
    }

    companion object {
        const val ACTION_ANC_MODE = "com.fxxkmoondrop.ACTION_ANC_MODE"
        const val ACTION_CMD = "com.fxxkmoondrop.ACTION_CMD"
        const val CMD_GET_ANC_V1 = "get_anc_v1_mode"
        const val CMD_GET_ANC_V2 = "get_anc_v2_mode"
        const val CMD_SET_ANC_V1 = "set_anc_v1_mode"
        const val CMD_SET_ANC_V2 = "set_anc_v2_mode"
        const val KEY_ENTRY = "fxxk_moondrop_entry"
        const val PKG_APP = "com.fxxkmoondrop.secret"
        const val PKG_MOONDROP = "com.moondroplab.moondrop.moondrop_app"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_GMS = "com.google.android.gms"

        private const val CLS_ACINFO = "com.qualcomm.qti.gaiaclient.core.data.ACInfo"
        private const val CLS_ANCV2_SUB = "com.moondroplab.moondrop.moondrop_app.native.handlers.AncV2Handler\$ancV2Subscriber\$1"
        private const val CLS_CONN_PREFS = "com.android.settings.connecteddevice.AdvancedConnectedDeviceDashboardFragment"
        private const val CLS_GAIA_APP = "com.moondroplab.moondrop.moondrop_app.GaiaClientApplication"
        private const val CLS_GAIA_SERVICE = "com.qualcomm.qti.gaiaclient.core.GaiaClientService"

        @JvmField
        var moondropCtx: Context? = null

        /** 模式映射（用户实测 AncV2Plugin 枚举：1=关闭, 2=降噪, 4=透传）
         * App 按钮语义：0=关闭, 1=降噪, 2=透传, 3=抗风, 4=自适应, 5=直播
         * AncV1 映射：关闭→1, 降噪→2, 透传→4, 抗风/自适应→3 */
        @JvmStatic
        private fun mapToAncV1(ui: Int): Int = when (ui) {
            0 -> 1
            1 -> 2
            2 -> 4
            3 -> 3
            4 -> 3
            else -> -1
        }

        /** ANC_V2 协议值 = App UI 值（用户实测确认直通正确）：
         * 0=关闭, 1=降噪, 2=透传；抗风/自适应/直播(3/4/5) 不支持 -> 忽略 */
        @JvmStatic
        private fun uiToAncV2(ui: Int): Int = if (ui in 0..2) ui else -1
    }
}
