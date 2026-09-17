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

        /** 官方详情页「耳机控制」切片（SlicePreference）key；本 ROM 没给它设 URI，官方那行是空的。 */
        private const val KEY_SLICE_CONTROL = "bt_extra_control"

        /** 官方详情页的加载占位行（取自官方 bluetooth_device_details_fragment.xml）。 */
        private const val KEY_LOADING = "loading_pref"

        /** 官方「操作按钮」那一行的 key（蓝牙详情页官方控件自己的键，用它当排序锚点）。 */
        private const val KEY_ANCHOR_ROWS = "action_buttons"
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
            PKG_SETTINGS -> { hookSettings(cl); hookDeviceDetailsPanel(cl); hookDetailProfileVisibility(cl) }
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
            if (!DeviceMatcher.isMoondrop(name)) return
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
                    if (!connPrefsCls.isInstance(thisObj)) return@intercept HookGuard.nullSafe(chain)
                    val prefScreen = HookHelper.callMethod(thisObj, "getPreferenceScreen") ?: return@intercept HookGuard.nullSafe(chain)
                    if (HookHelper.callMethod(prefScreen, "findPreference", KEY_ENTRY) != null) return@intercept HookGuard.nullSafe(chain)
                    val context = HookHelper.callMethod(thisObj, "getContext") as? Context ?: return@intercept HookGuard.nullSafe(chain)
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
            try {
                val startM = fragCls.getDeclaredMethod("onStart")
                hook(startM).intercept { chain ->
                    chain.proceed()
                    try {
                        val thisObj = chain.thisObject
                        if (AncProfileLib.isMoondrop(detailDeviceName(thisObj))) showOfficialExtras(cl, thisObj)
                    } catch (th: Throwable) { Log.e(TAG, "detail extras onStart error", th) }
                    null
                }
                Log.d(TAG, "hookSettings: DashboardFragment.onStart hooked")
            } catch (th: Throwable) { Log.d(TAG, "onStart hook failed: $th") }
            try {
                val resM = fragCls.getDeclaredMethod("onResume")
                hook(resM).intercept { chain ->
                    chain.proceed()
                    try {
                        val thisObj = chain.thisObject
                        if (AncProfileLib.isMoondrop(detailDeviceName(thisObj))) showOfficialExtras(cl, thisObj)
                    } catch (th: Throwable) { Log.e(TAG, "detail extras onResume error", th) }
                    null
                }
                Log.d(TAG, "hookSettings: DashboardFragment.onResume hooked")
            } catch (th: Throwable) { Log.d(TAG, "onResume hook failed: $th") }
            try {
                // 详情页销毁时摘掉页面级状态观察者，避免观察者挂在已结束的页面上不放。
                // Settings 的 DashboardFragment 自己没覆盖 onDestroy，声明式查找会直接抛；
                // 回退到含父类的方法查找，找不到就退化为"只清页面级观察者"的其它时机。
                val destroyM = try {
                    fragCls.getDeclaredMethod("onDestroy")
                } catch (e: NoSuchMethodException) {
                    fragCls.getMethod("onDestroy")
                }
                hook(destroyM).intercept { chain ->
                    chain.proceed()
                    try {
                        if (chain.thisObject === lastDetailFragment) {
                            lastDetailFragment = null
                            clearDetailWatch()
                        }
                    } catch (th: Throwable) { Log.d(TAG, "detail watch cleanup failed: $th") }
                    null
                }
                Log.d(TAG, "hookSettings: DashboardFragment.onDestroy hooked")
            } catch (th: Throwable) { Log.d(TAG, "onDestroy hook failed: $th") }
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
        // 第 3 项：分类功能开关。用户可在设置里关掉本面板；默认开。
        if (!featEnabled(cl, "feat_detail_panel")) {
            Log.d(TAG, "hookDeviceDetailsPanel: disabled by setting")
            return
        }
        try {
            val prefCls = Class.forName("androidx.preference.Preference", true, cl)
            val bindM = prefCls.getDeclaredMethod("onBindViewHolder",
                Class.forName("androidx.preference.PreferenceViewHolder", true, cl))
            hook(bindM).intercept { chain ->
                try {
                    val pref = chain.thisObject
                    val key = HookHelper.callMethod(pref, "getKey") as? String
                    val holder0 = chain.args[0]
                    val iv0 = HookHelper.getObjectField(holder0, "itemView") as? android.view.ViewGroup
                    if (key != DeviceDetailsPanel.KEY) {
                        // 列表重排会把行视图回收给别的条目，我们挂上去的面板会跟着残留，
                        // 于是同一条面板会在别的位置再画一份（表现为重复行/大片空白）。
                        // 换绑到别的条目时把残留的面板摘掉，只保留本条目自己那一行。
                        try {
                            iv0?.findViewWithTag<android.view.View>("fxxk_device_panel")?.let { iv0.removeView(it) }
                            // 被我们藏起来的孩子恢复可见：紧接着官方自己的绑定会重设各自该有的可见性。
                            if (iv0 != null) for (ci in 0 until iv0.childCount) {
                                iv0.getChildAt(ci).visibility = android.view.View.VISIBLE
                            }
                        } catch (th: Throwable) { Log.d(TAG, "strip stale panel failed: $th") }
                        chain.proceed()
                        return@intercept HookGuard.nullSafe(chain)
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
                        // 官方行自带的子视图只「藏」不「删」：同一个行视图会被适配器回收给别的条目用，
                        // 删掉后别人重新绑定时找不到自己的控件，整行就变空白（实测「实时字幕」就是这样）。
                        for (ci in 0 until itemView.childCount) {
                            val child = itemView.getChildAt(ci)
                            if (child.tag != "fxxk_device_panel") child.visibility = android.view.View.GONE
                        }
                        itemView.addView(panel, android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                        val st0 = fetchDcState(ctx, deviceName)
                        if (st0 != null) DeviceDetailsPanel.refresh(panel, st0)
                        // 详情页重排后适配器可能给同一个条目留下残留行，于是面板被画两遍。
                        // 等视图落位后按父容器清一次重复：只保留第一行，其余折叠成 0 高度。
                        val rowsRoot = itemView.rootView
                        panel.post { normalizePanelRows(rowsRoot) }
                        panel.postDelayed({ normalizePanelRows(rowsRoot) }, 600)
                        panel.postDelayed({ normalizePanelRows(rowsRoot) }, 1500)
                        // alpha2.39: 跨进程自动刷新（推模式）。模块端状态变化 -> notifyChange -> 此处重新拉取并刷新。
                        panel.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                            private var unregisterKey: android.net.Uri? = null
                            private var observer: android.database.ContentObserver? = null
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                try {
                                    // 新挂上来的实例可能还带着默认（全显）状态，先按最近状态自刷一次。
                                    DeviceDetailsPanel.refreshFromLatest(v)
                                    val uri = android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd")
                                    unregisterKey = uri
                                    val ob = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                                        override fun onChange(selfChange: Boolean) {
                                            try {
                                                val st = fetchDcState(ctx, deviceName)
                                                if (st != null) {
                                                    // 官方重排会挪行、旧行被回收，同一页可能残留多份面板：
                                                    // 闭包里那份可能已脱离视图树，必须从当前页面的窗口根遍历刷全。
                                                    val pageRoot = (HookHelper.callMethod(
                                                            lastDetailFragment, "getView") as? android.view.View)
                                                            ?.rootView ?: itemView.rootView
                                                    DeviceDetailsPanel.refreshAll(pageRoot, st)
                                                }
                                                // 连接/就绪状态变了：切片与官方加载行跟着切换
                                                applyControlSliceState(cl, ctx, lastDetailFragment)
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
                    if (!devCls.isInstance(thisObj)) return@intercept HookGuard.nullSafe(chain)
                    val prefScreen = HookHelper.callMethod(thisObj, "getPreferenceScreen") ?: return@intercept HookGuard.nullSafe(chain)
                    if (HookHelper.callMethod(prefScreen, "findPreference", DeviceDetailsPanel.KEY) != null) return@intercept HookGuard.nullSafe(chain)
                    val context = HookHelper.callMethod(thisObj, "getContext") as? Context ?: return@intercept HookGuard.nullSafe(chain)
                    val pref = prefCls.getConstructor(Context::class.java).newInstance(context)
                    HookHelper.callMethod(pref, "setKey", DeviceDetailsPanel.KEY)
                    val deviceName = (HookHelper.callMethod(thisObj, "getDeviceName") as? String)
                        ?: run {
                            val cd = HookHelper.getObjectField(thisObj, "cachedDevice")
                            cd?.let { HookHelper.callMethod(it, "getName") as? String }
                        }
                    deviceNameByPref[pref] = deviceName ?: ""
                    // alpha2.39: 只在 Moondrop 设备显示面板，其他蓝牙设备不注入
                    if (!AncProfileLib.isMoondrop(deviceName)) return@intercept HookGuard.nullSafe(chain)
                    val zh = langZh(context)
                    HookHelper.callMethod(pref, "setTitle", if (zh) "Moondrop 耳机控制" else "Moondrop Headset Control")
                    HookHelper.callMethod(pref, "setSummary", if (zh) "降噪 / 空间音频 / 增益 / 指示灯" else "ANC / spatial / gain / LED")
                    HookHelper.callMethod(prefScreen, "addPreference", pref)
                    Log.d(TAG, "device details panel injected")
                } catch (th: Throwable) {
                    Log.e(TAG, "device details inject error", th)
                }
            }
            // 官方蓝牙详情页每次重排后，把我们的几行摆到官方位置（见 placeOurRows）。
            try {
                val cfgCls = Class.forName("com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment", true, cl)
                val orderM = cfgCls.getDeclaredMethod("updatePreferenceOrder")
                hook(orderM).intercept { chain ->
                    chain.proceed()
                    // 官方 updatePreferenceOrder 会把「不在它名单里」的项整体挪进
                    // invisible_profile_category（该分类自身 visible=false），于是官方自己的行
                    // （含 HD 音频所属的蓝牙配置分类、相关工具等）全被藏掉。
                    // 这里只在它跑完之后让那个分类显形，不搬动任何子项、不代管任何一行的可见性。
                    // 注意：不能在这个 pass 里直接改可见性 —— 官方此刻正在重排条目，
                    // 中途插入层级/可见性变化会让适配器留下不再重绑的残留行（面板被画两遍）。
                    // 因此延到本 pass 结束、主线程下一次消息再应用（含把我们自己的行摆回官方位置）。
                    try {
                        val thisObj = chain.thisObject
                        if (AncProfileLib.isMoondrop(detailDeviceName(thisObj))) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { showOfficialExtras(cl, thisObj) }
                                catch (th: Throwable) { Log.e(TAG, "detail extras error", th) }
                            }
                        }
                    } catch (th: Throwable) { Log.e(TAG, "detail extras error", th) }
                    null
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
     * 让官方详情页里被藏起来的那些行有机会渲染。
     *
     * 只做一件最小的事：该分类里已经有可见子项时，把分类本身显示出来；不搬动子项、不代管各行的可见性
     * —— 每行出不出来仍由官方各自的 controller 决定。
     */
    private fun showOfficialExtras(cl: ClassLoader, thisObj: Any?) {
        val cat = HookHelper.callMethod(thisObj, "getInvisiblePrefCategory") ?: return
        lastDetailFragment = thisObj
        // 「耳机控制」切片行：官方没给它设 URI，本 ROM 也没设，内容为空时会渲染成空行。
        // 这里用设备自带元数据里的切片地址挂上官方控制器，挂得上才有内容、才放它出来。
        val ctx = HookHelper.callMethod(thisObj, "getContext") as? Context
        val sliceUri = controlSliceUri(cl, ctx, detailDevice(thisObj))
        val screenObj = HookHelper.callMethod(thisObj, "getPreferenceScreen")
        if (sliceUri != null) attachControlSlice(cl, ctx, screenObj, sliceUri)
        // 三行（切片 / 官方加载行 / 我们的面板）的显示与位置统一走 applyControlSliceState。
        applyControlSliceState(cl, ctx, thisObj)
        watchDetailState(cl, ctx, thisObj)
        if (!hasVisibleChild(cat)) return
        HookHelper.callMethod(cat, "setVisible", true)
        val rowsAnchor = HookHelper.callMethod(thisObj, "getView") as? android.view.View
        rowsAnchor?.postDelayed({ normalizePanelRows(rowsAnchor) }, 1200)

        showGroupsWithVisibleChild(cl, cat)
        // 官方重排 + 本次显形之后，适配器可能留下没被回收的残留行：它们在列表里就是
        // 一大片空白，把后面的行（含我们的面板）整体往下挤。让列表按适配器内容重新布局一次，
        // 残留行会被回收、行距恢复；重排是异步的，所以再补两次。
        // 官方重排后列表会留下没被回收的残留行，在屏幕上就是一大片空白，把后面的行
        // （含我们的面板）整体往下挤。只让列表重新布局一次、不动适配器：
        // notifyDataSetChanged 会把官方「实时字幕」那类自绘条目重新膨胀成空白卡片，实测不能用。
        val hostView = HookHelper.callMethod(thisObj, "getView") as? android.view.View
        repairListOrder(hostView)
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        ui.postDelayed({ repairListOrder(hostView) }, 400)
        ui.postDelayed({ repairListOrder(hostView) }, 1200)
    }

    /** 最近一次渲染详情页的 fragment（状态推送到达时用它重新判可见性）。 */
    @Volatile private var lastDetailFragment: Any? = null

    /** 详情页那一级的跨进程状态观察者（面板隐藏时也生效），只保留最新一条。 */
    @Volatile private var lastDetailObserver: android.database.ContentObserver? = null
    @Volatile private var lastDetailObserverCtx: Context? = null

    /** 详情页那一级的蓝牙状态广播接收（适配器开关 / 设备链路断开）。 */
    @Volatile private var lastBtReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var lastBtReceiverCtx: Context? = null

    /**
     * 「耳机控制」切片与官方加载行的状态 / 位置。
     *
     * 官方本来的设计就是：配置没加载完时，用自己那个加载行（loading_pref）占位；加载完再换成真实行。
     * 这里沿用同一套官方组件，只按本机真实状态决定谁出来：
     *  - 设备元数据里没有控制切片地址（机型不支持）-> 切片和加载行都不出现，不留一条永远转的进度条；
     *  - 有地址但本机还没就绪（GAIA 未完成服务发现，命令发不出去）-> 加载行原地占位，切片收起；
     *  - 就绪 -> 切片出来（位置由 [placeOurRows] 固定：官方操作按钮那一行之后）。
     * 位置沿用官方布局：两者都挂在屏幕根、不塞进不可见分类，也不额外搬动别的行。
     */
    private fun applyControlSliceState(cl: ClassLoader, ctx: Context?, thisObj: Any?) {
        if (ctx == null || thisObj == null) return
        try {
            val screen = HookHelper.callMethod(thisObj, "getPreferenceScreen") ?: return
            // 状态只有两个来源：设备元数据（机型是否带控制切片）+ 模块进程的实时连接状态。
            // 不写死机型、不写死地址。
            val hasSlice = controlSliceUri(cl, ctx, detailDevice(thisObj)) != null
            val st = fetchDcState(ctx, detailDeviceName(thisObj))
            val connected = st?.connected == true
            val ready = st?.gaiaReady == true
            val cat = HookHelper.callMethod(thisObj, "getInvisiblePrefCategory")
            val slicePref = HookHelper.callMethod(screen, "findPreference", KEY_SLICE_CONTROL)
            val loading = HookHelper.callMethod(screen, "findPreference", KEY_LOADING)
            val panelPref = HookHelper.callMethod(screen, "findPreference", DeviceDetailsPanel.KEY)

            // 三行的显示规则统一在 DetailRows（规则本身有单测），位置由 placeOurRows 摆。
            val vis = DetailRows.visibility(hasSlice, connected, ready)

            for ((pref, visible) in listOf(
                    slicePref to vis.slice,
                    loading to vis.loading,
                    panelPref to vis.panel)) {
                if (pref == null) continue
                // 官方把它们塞在不可见分类里，要按官方位置渲染就得先挂回屏幕根。
                if (visible && HookHelper.callMethod(pref, "getParent") !== screen) {
                    HookHelper.callMethod(cat, "removePreference", pref)
                    HookHelper.callMethod(screen, "addPreference", pref)
                }
                HookHelper.callMethod(pref, "setVisible", visible)
            }
            placeOurRows(screen)
            Log.d(TAG, "detail rows: hasSlice=$hasSlice connected=$connected ready=$ready" +
                    " slice=${vis.slice} loading=${vis.loading} panel=${vis.panel}")
        } catch (th: Throwable) { Log.e(TAG, "applyControlSliceState failed", th) }
    }

    /**
     * 在详情页 fragment 层面盯住模块进程的状态推送。
     *
     * 面板行自己那条观察者只在「面板可见」时才会注册；耳机没连上时面板是隐藏的，
     * 就没人盯着状态了 —— 连上之后页面会一直停在隐藏状态，得重开才恢复。
     * 这里挂一条与页面同级的观察者补上，页面重建时先摘掉上一条，避免重复注册。
     */
    private fun watchDetailState(cl: ClassLoader, ctx: Context?, thisObj: Any?) {
        if (ctx == null || thisObj == null) return
        try {
            clearDetailWatch()
            val ob = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    try { applyControlSliceState(cl, ctx, thisObj) }
                    catch (th: Throwable) { Log.e(TAG, "detail state observer error", th) }
                }
            }
            ctx.contentResolver.registerContentObserver(
                    android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd"), false, ob)
            lastDetailObserver = ob
            lastDetailObserverCtx = ctx.applicationContext

            // 耳机断开时模块进程不一定会推送状态（适配器被关掉时连断连回调都收不到），
            // 所以这里自己听系统的蓝牙广播补一刀：适配器开关、设备链路断开都重算一次可见性。
            clearBtWatch()
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    try { applyControlSliceState(cl, ctx, thisObj) }
                    catch (th: Throwable) { Log.e(TAG, "detail bt broadcast error", th) }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            lastBtReceiver = receiver
            lastBtReceiverCtx = ctx.applicationContext
        } catch (th: Throwable) { Log.e(TAG, "watchDetailState failed", th) }
    }

    /**
     * 把我们的几行摆到官方位置：官方操作按钮 -> 官方降噪切片 -> 官方加载行 -> 我们的面板。
     *
     * 为什么要自己摆：官方那次重排会把「不在它名单里的项」收进它自己生成的分类，于是我们的行会跟着
     * 那个分类在页面上漂（有时紧跟官方操作按钮、有时沉到底部）。这里在官方重排跑完之后把这几行摘成
     * 屏幕根的直接子项，并按官方「操作按钮」那一行的 order 依次给号 —— PreferenceGroup 本来就按
     * order 决定插入位置；只动我们自己的行，不碰官方任何一行。
     * 锚点是官方自己的 key（[KEY_ANCHOR_ROWS]）；拿不到就什么都不做，保持官方原样。
     */
    private fun placeOurRows(screen: Any?) {
        if (screen == null) return
        try {
            val anchor = HookHelper.callMethod(screen, "findPreference", KEY_ANCHOR_ROWS) ?: return
            val base = (HookHelper.callMethod(anchor, "getOrder") as? Int) ?: return
            var next = base + 1
            for (key in listOf(KEY_SLICE_CONTROL, KEY_LOADING, DeviceDetailsPanel.KEY)) {
                val pref = HookHelper.callMethod(screen, "findPreference", key) ?: continue
                val parent = HookHelper.callMethod(pref, "getParent")
                if (parent != null) HookHelper.callMethod(parent, "removePreference", pref)
                HookHelper.callMethod(pref, "setOrder", next)
                HookHelper.callMethod(screen, "addPreference", pref)
                next++
            }
        } catch (th: Throwable) { Log.d(TAG, "placeOurRows failed: $th") }
    }

    /** 摘掉详情页那一级的蓝牙广播接收。 */
    private fun clearBtWatch() {
        val r = lastBtReceiver ?: return
        try { lastBtReceiverCtx?.unregisterReceiver(r) } catch (_: Throwable) {}
        lastBtReceiver = null
        lastBtReceiverCtx = null
    }

    /** 摘掉详情页那一级的状态观察者与蓝牙广播（页面销毁 / 重新挂之前调用）。 */
    private fun clearDetailWatch() {
        clearBtWatch()
        val ob = lastDetailObserver ?: return
        try { lastDetailObserverCtx?.contentResolver?.unregisterContentObserver(ob) } catch (_: Throwable) {}
        lastDetailObserver = null
        lastDetailObserverCtx = null
    }

    /**
     * 让「面板行」收敛成一份，并保证它自己不被藏起来。
     *
     * 详情页的行视图会被适配器回收复用：面板可能被带到别的行上（重复行/空行）。这里**不看**上一次
     * 绑定的行，而是问适配器：这一行现在承载的是不是我们的条目；
     *  - 是 -> 保证整行与面板都可见（官方重排可能让我们被顺带藏掉）；
     *  - 不是却残留着面板视图 -> 把那块残留从该行摘掉、把被我们藏起来的孩子放出来，
     *    但不动该行自己的可见性（官方行不能被我们藏）。
     */
    private fun normalizePanelRows(anchor: android.view.View?) {
        try {
            val rv = findRecyclerById(anchor) ?: return
            val ad = HookHelper.callMethod(rv, "getAdapter") ?: return
            var kept = 0
            var stripped = 0
            for (i in 0 until rv.childCount) {
                val row = rv.getChildAt(i) as? android.view.ViewGroup ?: continue
                val pv = row.findViewWithTag<android.view.View>("fxxk_device_panel")
                val pos = (HookHelper.callMethod(rv, "getChildAdapterPosition", row) as? Int) ?: -1
                val item = if (pos >= 0) HookHelper.callMethod(ad, "getItem", pos) else null
                if ((HookHelper.callMethod(item, "getKey") as? String) == DeviceDetailsPanel.KEY) {
                    row.visibility = android.view.View.VISIBLE
                    pv?.visibility = android.view.View.VISIBLE
                    kept++
                } else if (pv != null) {
                    row.removeView(pv)
                    for (ci in 0 until row.childCount) row.getChildAt(ci).visibility = android.view.View.VISIBLE
                    stripped++
                }
            }
            if (kept != 1 || stripped > 0) Log.d(TAG, "panel rows kept=$kept stripped=$stripped")
        } catch (th: Throwable) { Log.d(TAG, "normalizePanelRows failed: $th") }
    }

    /** 让详情页列表重新布局一次，回收官方重排留下的残留行（不动适配器）。 */
    private fun repairListOrder(view: android.view.View?) {
        try {
            val rv = findRecyclerById(view) ?: return
            rv.requestLayout()
            Log.d(TAG, "list relayout n=" + rv.childCount)
        } catch (th: Throwable) { Log.d(TAG, "repairListOrder failed: " + th) }
    }

    /**
     * 递归把「自己有可见子项、但自己被藏了」的分组显示出来。
     *
     * 官方把子项藏进分组的同时也会把分组自身设为不可见（实时字幕所在的「相关工具」就是这样），
     * 只显示最外层分类的话这些行仍然出不来。判断只看官方自己的可见性，不指定任何具体项。
     */
    private fun showGroupsWithVisibleChild(cl: ClassLoader, group: Any?) {
        val groupCls = try {
            Class.forName("androidx.preference.PreferenceGroup", true, cl)
        } catch (th: Throwable) { return }
        val n = (HookHelper.callMethod(group, "getPreferenceCount") as? Int) ?: return
        for (i in 0 until n) {
            val child = HookHelper.callMethod(group, "getPreference", i) ?: continue
            if (!groupCls.isInstance(child)) continue
            if (!hasVisibleChild(child)) continue
            HookHelper.callMethod(child, "setVisible", true)
            showGroupsWithVisibleChild(cl, child)
        }
    }

    /** 按 id 找详情页那一个 RecyclerView，避免抓到切片内部的列表。 */
    private fun findRecyclerById(root: android.view.View?): android.view.ViewGroup? {
        val v = root ?: return null
        if (v is android.view.ViewGroup) {
            val id = v.id
            if (id != -1) {
                try {
                    if (v.resources.getResourceEntryName(id) == "recycler_view") return v
                } catch (_: Throwable) {}
            }
            for (i in 0 until v.childCount) findRecyclerById(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 这个 PreferenceGroup 是否已有可见子项。 */
    private fun hasVisibleChild(group: Any?): Boolean {
        val n = (HookHelper.callMethod(group, "getPreferenceCount") as? Int) ?: return false
        for (i in 0 until n) {
            val c = HookHelper.callMethod(group, "getPreference", i) ?: continue
            if (HookHelper.callMethod(c, "isVisible") == true) return true
        }
        return false
    }

    /**
     * 设备元数据里的「耳机控制」切片地址。
     *
     * 元数据里的 `view_width` 是空的，GMS 对空宽度直接返回空切片；这里用本机屏幕宽度补齐
     * （实测 view_width=屏幕宽度时才拿到内容），地址本身仍来自设备元数据，不写死。
     */
    private fun controlSliceUri(cl: ClassLoader, ctx: Context?, device: Any?): android.net.Uri? {
        if (ctx == null || device == null) return null
        return try {
            val bt = Class.forName("com.android.settingslib.bluetooth.BluetoothUtils", true, cl)
            val raw = HookHelper.callStaticMethod(bt, "getFastPairCustomizedField", device,
                    "HEARABLE_CONTROL_SLICE_WITH_WIDTH") as? String
            if (raw.isNullOrEmpty()) return null
            val w = ctx.resources.displayMetrics.widthPixels
            android.net.Uri.parse(raw.replace("view_width=", "view_width=$w"))
        } catch (th: Throwable) {
            Log.d(TAG, "controlSliceUri failed: $th")
            null
        }
    }

    /**
     * 把「耳机控制」切片挂到 SlicePreference 上，走官方同一套流程：
     * 控制器 setSliceUri -> displayPreference -> onStart。
     */
    private fun attachControlSlice(cl: ClassLoader, ctx: Context?, screen: Any?, uri: android.net.Uri) {
        if (ctx == null || screen == null) return
        try {
            val ctrl = Class.forName("com.android.settings.slices.SlicePreferenceController", true, cl)
                    .getConstructor(Context::class.java, String::class.java)
                    .newInstance(ctx, KEY_SLICE_CONTROL)
            HookHelper.callMethod(ctrl, "setSliceUri", uri)
            HookHelper.callMethod(ctrl, "displayPreference", screen)
            HookHelper.callMethod(ctrl, "onStart")
            Log.d(TAG, "control slice attached: $uri")
        } catch (th: Throwable) { Log.e(TAG, "attachControlSlice failed", th) }
    }

    /** 详情页当前的 BluetoothDevice（cachedDevice.getDevice）。 */
    private fun detailDevice(thisObj: Any?): Any? =
            HookHelper.callMethod(HookHelper.getObjectField(thisObj, "cachedDevice"), "getDevice")

    /** 详情页当前的设备名（先用官方 getDeviceName，退回 cachedDevice.getName）。 */
    private fun detailDeviceName(thisObj: Any?): String? {
        val n = HookHelper.callMethod(thisObj, "getDeviceName") as? String
        if (!n.isNullOrEmpty()) return n
        return HookHelper.callMethod(HookHelper.getObjectField(thisObj, "cachedDevice"), "getName") as? String
    }

    /**
     * 别让 ROM 把耳机详情页的官方档位行藏掉（对本机表现为隐藏 通话音频 / 媒体音频 / HD 音频）。
     *
     * 官方隐藏名单有两个来源，都是官方 API，我们只是对 Moondrop 设备不去填：
     * 1) BluetoothFeatureProvider.getInvisibleProfilePreferenceKeys（ROM 侧额外名单，参数里就带设备）；
     * 2) BluetoothDetailsProfilesController.setInvisibleProfiles（来自设备设置配置的 INVISIBLE_PROFILES）。
     * 行的可见性仍由官方自己的判断决定 —— 例如「HD 音频」只在 A2DP 可用且设备报告支持可选编解码器时出现，
     * 也就是连接着才会有，我们不另立规则。
     */
    private fun hookDetailProfileVisibility(cl: ClassLoader) {
        try {
            val implCls = Class.forName("com.android.settings.bluetooth.BluetoothFeatureProviderImpl", true, cl)
            val devCls = Class.forName("android.bluetooth.BluetoothDevice", true, cl)
            val m = implCls.getDeclaredMethod("getInvisibleProfilePreferenceKeys", Context::class.java, devCls)
            hook(m).intercept { chain ->
                val dev = chain.args.getOrNull(1)
                val name = HookHelper.callMethod(dev, "getName") as? String
                if (AncProfileLib.isMoondrop(name)) {
                    return@intercept HookGuard.safe(chain, java.util.Collections.emptySet<String>())
                }
                chain.proceed()
            }
            Log.d(TAG, "hookDetailProfileVisibility: provider hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "hookDetailProfileVisibility provider failed: $th")
        }
        try {
            val ctrlCls = Class.forName("com.android.settings.bluetooth.BluetoothDetailsProfilesController", true, cl)
            val m = ctrlCls.getDeclaredMethod("setInvisibleProfiles", List::class.java)
            hook(m).intercept { chain ->
                val name = HookHelper.callMethod(
                        HookHelper.getObjectField(chain.thisObject, "mCachedDevice"), "getName") as? String
                if (AncProfileLib.isMoondrop(name)) {
                    return@intercept HookGuard.safe(chain, null)
                }
                chain.proceed()
            }
            Log.d(TAG, "hookDetailProfileVisibility: profiles controller hooked")
        } catch (th: Throwable) {
            Log.d(TAG, "hookDetailProfileVisibility controller failed: $th")
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
    /**
     * 第 3 项：读取设置页里的 `feat_*` 分类开关（跨进程读 App 的 SP）。
     * 读不到时返回 true（保守：保持既有行为，不因读取失败而静默停用功能）。
     */
    private fun featEnabled(cl: ClassLoader, key: String): Boolean {
        return try {
            val app = HookHelper.callStaticMethod(
                    Class.forName("android.app.ActivityThread", true, cl), "currentApplication")
            val ctx = (app as? Context)?.createPackageContext(
                    "com.fxxkmoondrop.secret", Context.CONTEXT_IGNORE_SECURITY) ?: return true
            val cur = ctx.contentResolver.query(
                    android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/" + key),
                    null, null, null, null) ?: return true
            cur.use {
                if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow("_value")) == 1 else true
            }
        } catch (t: Throwable) {
            true
        }
    }

    private fun fetchDcState(ctx: Context, deviceName: String?): ControlPanel.State? {
        try {
            val b = android.net.Uri.parse("content://com.fxxkmoondrop.secret.prefs/dc_cmd")
                .buildUpon().appendQueryParameter("action", "fetch")
            val cur = ctx.contentResolver.query(b.build(), null, null, null, null) ?: return null
            var anc = 0; var spatial = 0; var headTracking = -1; var gain = 0; var led = 0; var connected = 0
            var gaia = 0; var modes = IntArray(0); var showWind = true
            cur.use {
                while (it.moveToNext()) {
                    val k = it.getString(0)
                    if (k == "modes") {
                        // 第 1 项：设备实际支持的档位（逗号分隔）；空串 = 能力未知
                        modes = it.getString(1)?.split(',')
                                ?.mapNotNull { x -> x.trim().toIntOrNull() }?.toIntArray() ?: IntArray(0)
                        continue
                    }
                    val v = it.getInt(1)
                    when (k) {
                        "anc" -> anc = v
                        "spatial" -> spatial = v
                        "headTracking" -> headTracking = v
                        "gain" -> gain = v
                        "led" -> led = v
                        "connected" -> connected = v
                        "gaia" -> gaia = v
                        "showWind" -> showWind = v == 1
                    }
                }
            }
            val profile = AncProfileLib.resolveDc(deviceName)
            return ControlPanel.State(
                connected = connected == 1,
                gaiaReady = gaia == 1,
                modes = modes,
                showWind = showWind,
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
                    val context = chain.thisObject as? Context ?: return@intercept HookGuard.nullSafe(chain)
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
                        return@intercept HookGuard.nullSafe(chain)  // short-circuit: don't call original
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
                    val objAC = chain.args[0] ?: return@intercept HookGuard.nullSafe(chain)
                    val objValue = chain.args[1] ?: return@intercept HookGuard.nullSafe(chain)
                    if ((objAC as Enum<*>).name != "MODE") return@intercept HookGuard.nullSafe(chain)
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
