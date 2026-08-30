package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.fxxkmoondrop.secret.hook.FastPairHookEntry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed API 102 入口：设置页注入 + Moondrop App silent launch + ANC 命令路由 + 蓝牙 A2DP 感知 + FastPair 弹窗。
 */
class XposedEntry : XposedModule() {

    companion object {
        const val TAG = "FxxkMoondrop"
        const val ACTION_ANC_MODE = "com.fxxkmoondrop.ACTION_ANC_MODE"
        const val ACTION_CMD = "com.fxxkmoondrop.ACTION_CMD"
        const val CMD_GET_ANC_V1 = "get_anc_v1_mode"
        const val CMD_GET_ANC_V2 = "get_anc_v2_mode"
        const val CMD_SET_ANC_V1 = "set_anc_v1_mode"
        const val CMD_SET_ANC_V2 = "set_anc_v2_mode"
        const val CMD_DC_SPATIAL = "set_spatial"
        const val CMD_DC_TRACKING = "set_tracking"
        const val CMD_DC_GAIN = "set_gain"
        const val CMD_DC_LED = "set_led"
        const val KEY_ENTRY = "fxxk_moondrop_entry"
        const val PKG_APP = "com.fxxkmoondrop.secret"
        const val PKG_MOONDROP = "com.moondroplab.moondrop.moondrop_app"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_GMS = "com.google.android.gms"

        private const val CLS_ACINFO = "com.qualcomm.qti.gaiaclient.core.data.ACInfo"
        private const val CLS_ANCV2_SUB = "com.moondroplab.moondrop.moondrop_app.native.handlers.AncV2Handler\$ancV2Subscriber\$1"
        private const val CLS_CONN_PREFS = "com.android.settings.connecteddevice.AdvancedConnectedDeviceDashboardFragment"
        private const val CLS_BT_DEVICE_DETAILS = "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment"
        private const val CLS_GAIA_APP = "com.moondroplab.moondrop.moondrop_app.GaiaClientApplication"
        private const val CLS_GAIA_SERVICE = "com.qualcomm.qti.gaiaclient.core.GaiaClientService"

        @JvmField
        var moondropCtx: Context? = null

        /** alpha2.39: 设备详情页注入时缓存 Preference 实例 -> 设备名（IdentityHashMap，绑定阶段读取）。 */
        private val deviceNameByPref = java.util.IdentityHashMap<Any, String>()

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

    private val fastPairHook = FastPairHookEntry()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.d(TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        val cl = param.classLoader
        Log.d(TAG, "onPackageReady: $pkg")
        when (pkg) {
            PKG_SETTINGS -> { hookSettings(cl); hookDeviceDetailsPanel(cl) }
            PKG_MOONDROP -> hookMoondrop(cl)
            PKG_BLUETOOTH -> hookBluetooth(cl)
            PKG_GMS -> fastPairHook.onGmsLoaded(this, cl)
        }
    }

    // ==================== Bluetooth A2DP ====================

    /** v3.17: hook 系统蓝牙 A2DP 连接状态：连接->静默拉起 Moondrop，断开->停止进程。 */
    private fun hookBluetooth(cl: ClassLoader) {
        try {
            val deviceCls = Class.forName("com.android.bluetooth.a2dp.A2dpService\$A2dpDevice", true, cl)
            val serviceCls = Class.forName("com.android.bluetooth.a2dp.A2dpService", true, cl)
            // 3-arg variant
            try {
                val m = serviceCls.getDeclaredMethod("onProfileConnectionStateChanged",
                        deviceCls, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                hook(m).intercept { chain ->
                    handleA2dpState(chain.thisObject, chain.args)
                    chain.proceed()
                }
                Log.d(TAG, "bluetooth A2dpService hooked (3-arg)")
            } catch (th: Throwable) {
                Log.d(TAG, "bluetooth hook 3-arg failed: $th")
            }
            // 4-arg variant
            try {
                val m = serviceCls.getDeclaredMethod("onProfileConnectionStateChanged",
                        deviceCls, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType)
                hook(m).intercept { chain ->
                    handleA2dpState(chain.thisObject, chain.args)
                    chain.proceed()
                }
                Log.d(TAG, "bluetooth A2dpService hooked (4-arg)")
            } catch (th: Throwable) {
                Log.d(TAG, "bluetooth hook 4-arg failed: $th")
            }
        } catch (th: Throwable) {
            Log.d(TAG, "bluetooth hook init failed: $th")
        }
    }

    private fun handleA2dpState(thisObj: Any?, args: List<Any?>) {
        try {
            val device = args[0] ?: return
            val to = args[2] as? Int ?: return
            var name: String? = null
            try { name = HookHelper.callMethod(device, "getName") as String? } catch (_: Throwable) { }
            if (name == null) {
                try { name = HookHelper.getObjectField(device, "mName") as String? } catch (_: Throwable) { }
            }
            if (name == null) return
            if (!name.lowercase().contains("moondrop")) return
            if (to == 2) {
                Log.d(TAG, "A2DP connected: $name -> silent launch + wake service")
                execSilent("am start -n $PKG_MOONDROP/.MainActivity --ez fxxk_silent true --exclude-from-recents")
                execSilent("am broadcast -a com.fxxkmoondrop.secret.BT_EVENT --es evt connected")
            } else if (to == 0) {
                Log.d(TAG, "A2DP disconnected: $name -> stop app + wake service")
                execSilent("am force-stop $PKG_MOONDROP")
                execSilent("am broadcast -a com.fxxkmoondrop.secret.BT_EVENT --es evt disconnected")
            }
        } catch (th: Throwable) {
            Log.e(TAG, "handleA2dpState error", th)
        }
    }

    private fun execSilent(cmd: String) {
        try {
            Log.d(TAG, "exec: $cmd")
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            p.waitFor()
        } catch (th: Throwable) {
            Log.e(TAG, "execSilent error", th)
        }
    }

    // ==================== Settings injection ====================

    private fun hookSettings(cl: ClassLoader) {
        try {
            val fragCls = Class.forName("com.android.settings.dashboard.DashboardFragment", true, cl)
            val m = fragCls.getDeclaredMethod("onCreatePreferences", Bundle::class.java, String::class.java)
            hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val thisObj = chain.thisObject
                    val connPrefsCls = Class.forName(CLS_CONN_PREFS, true, cl)
                    if (!connPrefsCls.isInstance(thisObj)) return@intercept null
                    val prefScreen = HookHelper.callMethod(thisObj, "getPreferenceScreen") ?: return@intercept null
                    if (HookHelper.callMethod(prefScreen, "findPreference", KEY_ENTRY) != null) return@intercept null
                    val context = HookHelper.callMethod(thisObj, "getContext") as? Context ?: return@intercept null
                    val prefCls = Class.forName("androidx.preference.Preference", true, cl)
                    val pref = prefCls.getConstructor(Context::class.java).newInstance(context)
                    HookHelper.callMethod(pref, "setKey", KEY_ENTRY)
                    // alpha2.38.10+: Settings 注入条目按语言偏好显示（跨进程只读 PrefsProvider）
                    val zh = langZh(context)
                    HookHelper.callMethod(pref, "setTitle",
                            if (zh) "Moondrop 耳机控制" else "Moondrop Headset Control")
                    HookHelper.callMethod(pref, "setSummary",
                            if (zh) "FxxkMoondrop：降噪切换 / 耳机功能 / 连接弹窗"
                            else "FxxkMoondrop: ANC switch / headset features / pairing popup")
                    val intent = Intent()
                    intent.setClassName(PKG_APP, "com.fxxkmoondrop.secret.MainActivity")
                    HookHelper.callMethod(pref, "setIntent", intent)
                    HookHelper.callMethod(prefScreen, "addPreference", pref)
                    Log.d(TAG, "entry injected into ConnectionPreferences")
                } catch (th: Throwable) {
                    Log.e(TAG, "settings inject error", th)
                }
            }
            Log.d(TAG, "hookSettings: DashboardFragment.onCreatePreferences hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "hookSettings failed: $th")
        }
    }

    /** alpha2.38.10+: Settings 进程跨进程只读语言偏好（与 FastPairHookEntry.langZh 同源逻辑）。 */
    private fun langZh(ctx: Context): Boolean {
        return try {
            val cur = ctx.contentResolver.query(
                    android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/lang"),
                    null, null, null, null)
            var m = 0
            if (cur != null) {
                try { if (cur.moveToFirst()) m = cur.getInt(cur.getColumnIndexOrThrow("_value")) } finally { cur.close() }
            }
            when (m) { 1 -> true; 2 -> false; else -> java.util.Locale.getDefault().language.startsWith("zh") }
        } catch (t: Throwable) {
            java.util.Locale.getDefault().language.startsWith("zh")
        }
    }


    /**
     * alpha2.39: 蓝牙设备详情页注入（仅 Settings 进程）。向 BluetoothDeviceDetailsFragment 的
     * PreferenceScreen 追加 Preference，并通过 hook onBindViewHolder 把该条目 itemView 渲染成
     * ControlPanel（降噪卡片 + 功能控制卡片）。不动主界面/现有注入/Moondrop App/构建脚本。
     */
    private fun hookDeviceDetailsPanel(cl: ClassLoader) {
        try {
            val prefCls = Class.forName("androidx.preference.Preference", true, cl)
            val bindM = prefCls.getDeclaredMethod("onBindViewHolder",
                Class.forName("androidx.preference.PreferenceViewHolder", true, cl))
            hook(bindM).intercept { chain ->
                try {
                    val pref = chain.thisObject
                    val key = HookHelper.callMethod(pref, "getKey") as? String
                    if (key != DeviceDetailsPanel.KEY) {
                        chain.proceed()
                        return@intercept null
                    }
                    val holder = chain.args[0]
                    val itemView = HookHelper.getObjectField(holder, "itemView") as? android.view.ViewGroup
                    if (itemView != null && itemView.findViewWithTag<android.view.View>("fxxk_device_panel") == null) {
                        val ctx = itemView.context
                        val deviceName = deviceNameByPref[pref]
                        lateinit var panel: android.view.View
                        panel = DeviceDetailsPanel.buildView(ctx, deviceName) { cmd ->
                            sendDeviceCommand(ctx, cmd)
                            val st = fetchDcState(ctx, deviceName)
                            if (st != null) DeviceDetailsPanel.refresh(panel, st)
                        }
                        itemView.removeAllViews()
                        itemView.addView(panel, android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                        val st0 = fetchDcState(ctx, deviceName)
                        if (st0 != null) DeviceDetailsPanel.refresh(panel, st0)
                        // alpha2.39: 跨进程自动刷新（推模式）。模块端状态变化 -> notifyChange -> 此处重新拉取并刷新。
                        panel.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                            private var unregisterKey: android.net.Uri? = null
                            private var observer: android.database.ContentObserver? = null
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                try {
                                    val uri = android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd")
                                    unregisterKey = uri
                                    val ob = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                                        override fun onChange(selfChange: Boolean) {
                                            try {
                                                val st = fetchDcState(ctx, deviceName)
                                                if (st != null) DeviceDetailsPanel.refresh(panel, st)
                                            } catch (th: Throwable) { Log.e(TAG, "panel observer error", th) }
                                        }
                                    }
                                    observer = ob
                                    ctx.contentResolver.registerContentObserver(uri, false, ob)
                                } catch (th: Throwable) { Log.e(TAG, "register observer error", th) }
                            }
                            override fun onViewDetachedFromWindow(v: android.view.View) {
                                try {
                                    val ob = observer ?: return
                                    ctx.contentResolver.unregisterContentObserver(ob)
                                    unregisterKey = null; observer = null
                                } catch (th: Throwable) { Log.e(TAG, "unregister observer error", th) }
                            }
                        })
                    }
                } catch (th: Throwable) {
                    Log.e(TAG, "device panel bind error", th)
                }
                chain.proceed()
            }

            val fragCls = Class.forName("com.android.settings.dashboard.DashboardFragment", true, cl)
            val m = fragCls.getDeclaredMethod("onCreatePreferences", Bundle::class.java, String::class.java)
            hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val thisObj = chain.thisObject
                    val devCls = Class.forName(CLS_BT_DEVICE_DETAILS, true, cl)
                    if (!devCls.isInstance(thisObj)) return@intercept null
                    val prefScreen = HookHelper.callMethod(thisObj, "getPreferenceScreen") ?: return@intercept null
                    if (HookHelper.callMethod(prefScreen, "findPreference", DeviceDetailsPanel.KEY) != null) return@intercept null
                    val context = HookHelper.callMethod(thisObj, "getContext") as? Context ?: return@intercept null
                    val pref = prefCls.getConstructor(Context::class.java).newInstance(context)
                    HookHelper.callMethod(pref, "setKey", DeviceDetailsPanel.KEY)
                    val deviceName = (HookHelper.callMethod(thisObj, "getDeviceName") as? String)
                        ?: run {
                            val cd = HookHelper.getObjectField(thisObj, "cachedDevice")
                            cd?.let { HookHelper.callMethod(it, "getName") as? String }
                        }
                    deviceNameByPref[pref] = deviceName ?: ""
                    // alpha2.39: 只在 Moondrop 设备显示面板，其他蓝牙设备不注入
                    if (!AncProfileLib.isMoondrop(deviceName)) return@intercept null
                    val zh = langZh(context)
                    HookHelper.callMethod(pref, "setTitle", if (zh) "Moondrop 耳机控制" else "Moondrop Headset Control")
                    HookHelper.callMethod(pref, "setSummary", if (zh) "降噪 / 空间音频 / 增益 / 指示灯" else "ANC / spatial / gain / LED")
                    HookHelper.callMethod(prefScreen, "addPreference", pref)
                    Log.d(TAG, "device details panel injected")
                } catch (th: Throwable) {
                    Log.e(TAG, "device details inject error", th)
                }
            }
            // alpha2.39: 蓝牙详情页用 displayOrder 白名单控制 pref 可见性，把面板 key 注入白名单，避免被移进 invisible_profile_category 隐藏。
            try {
                val cfgCls = Class.forName("com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment", true, cl)
                val orderM = cfgCls.getDeclaredMethod("updatePreferenceOrder")
                hook(orderM).intercept { chain ->
                    try {
                        val thisObj = chain.thisObject
                        val displayOrder = HookHelper.getObjectField(thisObj, "displayOrder") as? List<*>
                        if (displayOrder != null && !displayOrder.contains(DeviceDetailsPanel.KEY)) {
                            val nl = ArrayList<Any?>()
                            nl.addAll(displayOrder)
                            nl.add(DeviceDetailsPanel.KEY)
                            HookHelper.setObjectField(thisObj, "displayOrder", nl)
                        }
                    } catch (th: Throwable) { Log.e(TAG, "displayOrder inject error", th) }
                    chain.proceed()
                }
                Log.d(TAG, "hookDeviceDetailsPanel: updatePreferenceOrder hooked")
            } catch (th: Throwable) {
                Log.d(TAG, "updatePreferenceOrder hook failed: $th")
            }
            Log.d(TAG, "hookDeviceDetailsPanel: BluetoothDeviceDetailsFragment hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "hookDeviceDetailsPanel failed: $th")
        }
    }

    /**
     * alpha2.39: 设备详情页控件命令下发（占位）。Settings 进程无法直接调用模块进程的
     * AncBridge/DeviceControlBridge，故通过 ACTION_CMD 广播发到模块进程处理；具体 DC
     * 命令分支由后续增量扩展（保留原 ANC 命令路径）。
     */
    private fun sendDeviceCommand(ctx: Context, cmd: DeviceDetailsPanel.Command) {
        try {
            val b = android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd").buildUpon()
            when (cmd) {
                is DeviceDetailsPanel.Command.SetAncMode -> {
                    b.appendQueryParameter("action", "set_anc")
                    b.appendQueryParameter("mode", cmd.mode.toString())
                }
                is DeviceDetailsPanel.Command.SetSpatialEnabled -> {
                    b.appendQueryParameter("action", "set_spatial")
                    b.appendQueryParameter("enabled", cmd.enabled.toString())
                }
                is DeviceDetailsPanel.Command.SetTrackingMode -> {
                    b.appendQueryParameter("action", "set_tracking")
                    b.appendQueryParameter("mode", cmd.mode.toString())
                }
                is DeviceDetailsPanel.Command.SetGain -> {
                    b.appendQueryParameter("action", "set_gain")
                    b.appendQueryParameter("level", cmd.level.toString())
                }
                is DeviceDetailsPanel.Command.SetLed -> {
                    b.appendQueryParameter("action", "set_led")
                    b.appendQueryParameter("state", cmd.state.toString())
                }
            }
            ctx.contentResolver.query(b.build(), null, null, null, null)?.close()
            Log.d(TAG, "device command sent: ${cmd::class.simpleName}")
        } catch (th: Throwable) {
            Log.e(TAG, "sendDeviceCommand error", th)
        }
    }

    /** 读取模块进程当前 DC/ANC 状态（跨进程，走 PrefsProvider.dc_cmd）。 */
    private fun fetchDcState(ctx: Context, deviceName: String?): ControlPanel.State? {
        try {
            val b = android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd")
                .buildUpon().appendQueryParameter("action", "fetch")
            val cur = ctx.contentResolver.query(b.build(), null, null, null, null) ?: return null
            var anc = 0; var spatial = 0; var headTracking = -1; var gain = 0; var led = 0; var connected = 0
            cur.use {
                while (it.moveToNext()) {
                    val k = it.getString(0)
                    val v = it.getInt(1)
                    when (k) {
                        "anc" -> anc = v
                        "spatial" -> spatial = v
                        "headTracking" -> headTracking = v
                        "gain" -> gain = v
                        "led" -> led = v
                        "connected" -> connected = v
                    }
                }
            }
            val profile = AncProfileLib.resolveDc(deviceName)
            return ControlPanel.State(
                connected = connected == 1,
                ancMode = anc,
                spatialOn = spatial == 1,
                spatialUiMode = headTracking,
                gainLevel = gain,
                ledOn = led == 1,
                hasSpatial = profile.hasSpatial,
                hasGain = profile.hasGain,
                hasLed = profile.hasLed
            )
        } catch (th: Throwable) {
            Log.e(TAG, "fetchDcState error", th)
            return null
        }
    }

    // ==================== Moondrop App hooks ====================

    private fun hookMoondrop(cl: ClassLoader) {
        // v3.16: silent launch -- fxxk_silent=true 时抑制 UI
        try {
            val cls = Class.forName("com.moondroplab.moondrop.moondrop_app.MainActivity", true, cl)
            val m = cls.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(m).intercept { chain ->
                try {
                    val activity = chain.thisObject as Activity
                    val intent = activity.intent
                    if (intent != null && intent.getBooleanExtra("fxxk_silent", false)) {
                        activity.setTheme(android.R.style.Theme_Translucent_NoTitleBar)
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        try {
                            activity.finishAndRemoveTask()
                        } catch (_: Throwable) {
                            activity.finish()
                        }
                        Log.d(TAG, "silent launch: UI suppressed, GAIA service initializing")
                    }
                } catch (th: Throwable) {
                    Log.e(TAG, "silent launch hook err", th)
                }
                chain.proceed()
            }
            Log.d(TAG, "MainActivity.onCreate hooked (silent launch)")
        } catch (th: Throwable) {
            Log.d(TAG, "silent launch hook failed: $th")
        }

        // Application.onCreate -> register cmd receiver
        try {
            val m = Class.forName("android.app.Application", true, cl).getDeclaredMethod("onCreate")
            hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val context = chain.thisObject as? Context ?: return@intercept null
                    registerCmdReceiver(context.applicationContext, cl)
                } catch (th: Throwable) {
                    Log.e(TAG, "Application.onCreate hook err", th)
                }
            }
            Log.d(TAG, "Application.onCreate hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "Application hook failed: $th")
        }

        // onAncMode -> broadcast
        try {
            val cls = Class.forName(CLS_ANCV2_SUB, true, cl)
            val m = cls.getDeclaredMethod("onAncMode", Int::class.javaPrimitiveType)
            hook(m).intercept { chain ->
                chain.proceed()
                try {
                    broadcastAncMode(2, chain.args[0] as Int)
                } catch (th: Throwable) {
                    Log.e(TAG, "onAncMode hook err", th)
                }
            }
            Log.d(TAG, "onAncMode hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "onAncMode hook failed: $th")
        }

        // setCurrentMode -> UI to ANC_V2 mapping
        try {
            val cls = Class.forName("com.qualcomm.qti.gaiaclient.core.gaia.qtil.plugins.v3.V3AncV2Plugin", true, cl)
            val m = cls.getDeclaredMethod("setCurrentMode", Int::class.javaPrimitiveType)
            hook(m).intercept { chain ->
                try {
                    val ui = chain.args[0] as Int
                    val v2 = uiToAncV2(ui)
                    if (v2 < 0) {
                        Log.d(TAG, "setCurrentMode ignore ui=$ui")
                        return@intercept null  // short-circuit: don't call original
                    }
                    chain.args[0] = v2
                    Log.d(TAG, "setCurrentMode ui=$ui -> ancV2=$v2")
                } catch (th: Throwable) {
                    Log.e(TAG, "setCurrentMode hook err", th)
                }
                chain.proceed()
            }
            Log.d(TAG, "setCurrentMode hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "setCurrentMode hook failed: $th")
        }

        // AudioCuration onInfo -> broadcast
        try {
            val acInfoCls = Class.forName(CLS_ACINFO, true, cl)
            val cls = Class.forName("com.qualcomm.qti.gaiaclient.repository.audiocuration.AudioCurationRepositoryImpl\$1", true, cl)
            val m = cls.getDeclaredMethod("onInfo", acInfoCls, Any::class.java)
            hook(m).intercept { chain ->
                chain.proceed()
                try {
                    val objAC = chain.args[0] ?: return@intercept null
                    val objValue = chain.args[1] ?: return@intercept null
                    if ((objAC as Enum<*>).name != "MODE") return@intercept null
                    val iValue = HookHelper.callMethod(objValue, "getValue") as Int
                    Log.d(TAG, "AC onInfo MODE value=$iValue")
                    broadcastAncMode(1, iValue)
                } catch (th: Throwable) {
                    Log.e(TAG, "AudioCuration onInfo hook err", th)
                }
            }
            Log.d(TAG, "AudioCuration onInfo hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "AudioCuration onInfo hook failed: $th")
        }
    }

    // ==================== ANC command routing ====================

    fun registerCmdReceiver(context: Context, cl: ClassLoader) {
        moondropCtx = context
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context2: Context, intent: Intent) {
                val stringExtra = intent.getStringExtra("cmd")
                val intExtra = intent.getIntExtra("mode", -1)
                val boolExtra = intent.getBooleanExtra("enabled", false)
                val levelExtra = intent.getIntExtra("level", -1)
                val stateExtra = intent.getIntExtra("state", -1)
                try {
                    when (stringExtra) {
                        "set_anc_v2_mode" -> setAncV2Mode(cl, intExtra)
                        "get_anc_v2_mode" -> fetchAncV2Mode(cl)
                        "set_anc_v1_mode" -> setAncV1Mode(cl, intExtra)
                        "get_anc_v1_mode" -> fetchAncV1Mode(cl)
                        "set_spatial" -> {
                            Log.d(TAG, "set_spatial enabled=" + boolExtra)
                            DeviceControlBridge.setSpatialEnabled(boolExtra)
                            DeviceControlBridge.fetchSpatial()
                        }
                        "set_tracking" -> {
                            Log.d(TAG, "set_tracking mode=" + intExtra)
                            DeviceControlBridge.setTrackingMode(intExtra)
                            DeviceControlBridge.fetchHeadTracking()
                        }
                        "set_gain" -> {
                            Log.d(TAG, "set_gain level=" + levelExtra)
                            DeviceControlBridge.setGain(levelExtra)
                            DeviceControlBridge.fetchGain()
                        }
                        "set_led" -> {
                            Log.d(TAG, "set_led state=" + stateExtra)
                            DeviceControlBridge.setLed(stateExtra)
                            DeviceControlBridge.fetchLed()
                        }
                        "ping" -> {
                            Log.d(TAG, "ping received")
                            try {
                                context2.sendBroadcast(Intent("com.fxxkmoondrop.PONG"))
                            } catch (thp: Throwable) {
                                Log.e(TAG, "pong fail", thp)
                            }
                        }
                    }
                } catch (th: Throwable) {
                    Log.e(TAG, "cmd receiver error", th)
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
            Log.d(TAG, "cmd receiver registered")
        } catch (th: Throwable) {
            Log.d(TAG, "registerReceiver failed: $th")
        }
    }

    fun setAncV2Mode(cl: ClassLoader, i: Int) {
        if (i < 0 || i >= 6) return
        val gaiaSvcCls = Class.forName(CLS_GAIA_SERVICE, true, cl)
        val qtil = HookHelper.callStaticMethod(gaiaSvcCls, "getQtilManager") ?: run {
            Log.d(TAG, "QtilManager null, fallback to AncV1")
            setAncV1Mode(cl, i)
            return
        }
        val plugin = HookHelper.callMethod(qtil, "getAncV2Plugin") ?: run {
            Log.d(TAG, "AncV2Plugin null, fallback to AncV1")
            setAncV1Mode(cl, i)
            return
        }
        HookHelper.callMethod(plugin, "setCurrentMode", i)
        Log.d(TAG, "setAncV2Mode ui=$i (mapped in plugin hook)")
    }

    fun fetchAncV2Mode(cl: ClassLoader) {
        val gaiaSvcCls = Class.forName(CLS_GAIA_SERVICE, true, cl)
        val qtil = HookHelper.callStaticMethod(gaiaSvcCls, "getQtilManager") ?: return
        val plugin = HookHelper.callMethod(qtil, "getAncV2Plugin") ?: return
        HookHelper.callMethod(plugin, "fetchCurrentMode")
    }

    fun setAncV1Mode(cl: ClassLoader, i: Int) {
        val v1 = mapToAncV1(i)
        if (v1 < 0) {
            Log.d(TAG, "setAncV1Mode ignore mode $i")
            return
        }
        val gaiaAppCls = Class.forName(CLS_GAIA_APP, true, cl)
        val app = HookHelper.callStaticMethod(gaiaAppCls, "getInstance") ?: return
        val repo = HookHelper.getObjectField(app, "audioCurationRepository") ?: return
        HookHelper.callMethod(repo, "setMode", moondropCtx, v1)
        Log.d(TAG, "setAncV1Mode ui=$i v1=$v1")
    }

    fun fetchAncV1Mode(cl: ClassLoader) {
        val gaiaAppCls = Class.forName(CLS_GAIA_APP, true, cl)
        val app = HookHelper.callStaticMethod(gaiaAppCls, "getInstance") ?: return
        val repo = HookHelper.getObjectField(app, "audioCurationRepository") ?: return
        val acInfoCls = Class.forName(CLS_ACINFO, true, cl)
        val acModeEnum = acInfoCls.enumConstants?.first { (it as Enum<*>).name == "MODE" } ?: return
        HookHelper.callMethod(repo, "fetchACInfo", moondropCtx, acModeEnum)
    }

    fun broadcastAncMode(version: Int, mode: Int) {
        try {
            val ctx = moondropCtx ?: return
            val intent = Intent("com.fxxkmoondrop.ACTION_ANC_MODE")
            intent.setPackage(PKG_APP)
            intent.putExtra("version", version)
            intent.putExtra("mode", mode)
            ctx.sendBroadcast(intent)
            Log.d(TAG, "ANC mode broadcast v$version mode=$mode")
        } catch (th: Throwable) {
            Log.e(TAG, "broadcastAncMode error", th)
        }
    }
}
