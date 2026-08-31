package com.fxxkmoondrop.secret

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.HashSet

@Suppress("DEPRECATION")
class HeadsetDetectService : Service() {
    companion object {
        @JvmField
        @Volatile
        var RUNNING = false

        private const val TAG = "MoondropHeadset"
        private const val POLL_MS = 5000L
        private const val SLEEP_MS = 30000L // 自动休眠：无连接 30 秒后自停

        /** alpha1.17: 静态共享（服务进程与主界面同进程），供 HeadsetGate 复用已连接的 proxy
         *  ColorOS 上 BluetoothManager.getConnectedDevices(A2DP/HEADSET) 抛
         *  IllegalArgumentException("Profile not supported")，但 profile proxy 可用。 */
        @JvmField
        var sSharedA2dp: BluetoothProfile? = null
        @JvmField
        var sSharedHeadset: BluetoothProfile? = null

        /** alpha1.17: 用服务已连接的 profile proxy 查找 Moondrop 耳机 MAC（ColorOS 兼容，
         *  resolveLeAddress 同款路径，pollConnected 实测可用）。find 顺序 A2DP->HEADSET。 */
        @JvmStatic
        fun findMoondropMacWithProxy(): String? {
            try {
                sSharedA2dp?.let {
                    val m = scanProxy(it.connectedDevices)
                    if (m != null) return m
                }
                sSharedHeadset?.let {
                    val m = scanProxy(it.connectedDevices)
                    if (m != null) return m
                }
            } catch (e: Exception) {
                Log.w(TAG, "findMoondropMacWithProxy fail: $e")
            }
            return null
        }

        private fun scanProxy(devs: List<BluetoothDevice>?): String? {
            if (devs == null) return null
            for (d in devs) {
                val n = d.name
                if (n != null && DeviceMatcher.isMoondrop(n)) return d.address
            }
            return null
        }
    }

    private var receiver: HeadsetReceiver? = null
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var a2dpReady = false
    private var headsetReady = false
    private var lastSeen = HashSet<String>()
    private var lastMoondropMs = 0L // 上次检测到 Moondrop 连接的时间戳

    private val sleepCheck = Runnable {
        if (lastMoondropMs > 0 && System.currentTimeMillis() - lastMoondropMs > SLEEP_MS) {
            Log.i(TAG, "no Moondrop for 30s, auto-sleep")
            RUNNING = false
            stopSelf()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy as BluetoothA2dp
                a2dpReady = true
                sSharedA2dp = proxy
            } else if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                headsetReady = true
                sSharedHeadset = proxy
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                a2dpReady = false
                sSharedA2dp = null
            } else if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                headsetReady = false
                sSharedHeadset = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RUNNING = true
        Log.i(TAG, "service created (multi-path detect)")
        AppLog.init(this)
        AppLog.i(TAG, "HeadsetDetectService created (multi-path detect)")
        AncBridge.bind(this) // alpha1.20: 绑定广播 Context（模式状态同步通道）
        GaiaBleClient.getInstance().init(this) // alpha1.32: 注册 LE 地址 receiver + 无缓存时请求发现

        receiver = HeadsetReceiver()
        val f = IntentFilter()
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        f.addAction("com.fxxkmoondrop.secret.FASTPAIR_MODE_CHANGED") // alpha1.15: FastPair 弹窗模式回调
        f.addAction("com.fxxkmoondrop.secret.FASTPAIR_SHEET_CLOSED") // alpha2.7: GMS 弹窗关闭 -> 模拟状态恢复
        f.addAction(AncBridge.ACTION_FP_MODE_REQUEST) // alpha1.20: 弹窗请求当前模式（高亮同步）
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, f)
            }
            registered = true
            Log.i(TAG, "receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "register failed: $e")
        }

        if (hasBtPermission()) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                try {
                    adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
                    adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
                } catch (e: Exception) {
                    Log.e(TAG, "proxy failed: $e")
                }
            }
        }

        handler.postDelayed(pollRunnable, POLL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        // v3.17: 处理蓝牙 hook 组件发来的 BT_EVENT（连接/断开事件）
        if (intent != null && BootReceiver.ACTION_BT_EVENT == intent.action) {
            val evt = intent.getStringExtra("evt")
            Log.i(TAG, "BT_EVENT: $evt")
            AppLog.i(TAG, "BT_EVENT: " + evt)
            if (evt == "connected" || evt == "disconnected") {
                // 立即触发一次轮询（连接/断开事件已经由 hook 处理弹窗，轮询增量同步）
                pollConnected()
            }
        }
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enable", true)) {
            AliveReceiver.scheduleNext(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RUNNING = false
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enable", true)) {
            AliveReceiver.cancel(this)
        }
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(sleepCheck)
        if (registered) {
            try { unregisterReceiver(receiver) } catch (_: Exception) { }
            registered = false
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                val ap = a2dpProxy
                if (a2dpReady && ap != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, ap)
                val hp = headsetProxy
                if (headsetReady && hp != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, hp)
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private var lastGaiaFetchMs = 0L

    private val gaiaCallback = object : GaiaBleClient.Callback {
        override fun onConnected(address: String) {
            Log.i(TAG, "GAIA connected: $address")
            AppLog.i(TAG, "GAIA connected: " + address + " protocol=" + GaiaBleClient.getInstance().activeProtocol())
            lastGaiaFetchMs = 0
            // alpha1.4: 延迟等 notification descriptor 写完后请求电量/降噪模式
            handler.postDelayed({
                GaiaBleClient.getInstance().fetchBatteryLevels()
                AncBridge.fetchAncMode()
            }, 800)
        }

        override fun onDisconnected(address: String) {
            Log.i(TAG, "GAIA disconnected: $address")
            AppLog.w(TAG, "GAIA disconnected: " + address)
        }

        override fun onBatteryLevel(batteryId: Int, level: Int) {
            val addr = GaiaBleClient.getInstance().deviceAddress ?: return
            BatteryStore.setGaiaLevel(addr, batteryId, level)
            // alpha2.38: battery refresh handled via ACTION_BATTERY_UPDATE broadcast
            // alpha1.12: 左右耳电量就绪 → 弹连接窗（延迟弹窗）
            PopupGate.flushPendingIfReady()
            // alpha1.4: 通知主界面刷新电量显示
            try {
                val bi = Intent("com.fxxkmoondrop.secret.STATE_UPDATED")
                bi.setPackage("com.fxxkmoondrop.secret")
                sendBroadcast(bi)
            } catch (_: Exception) { }
        }

        override fun onAncMode(mode: Int) {
            AncBridge.notifyAncMode(mode)
            // alpha1.4: 通知主界面刷新降噪高亮
            try {
                val bi = Intent("com.fxxkmoondrop.secret.STATE_UPDATED")
                bi.setPackage("com.fxxkmoondrop.secret")
                sendBroadcast(bi)
            } catch (_: Exception) { }
        }

        override fun onError(message: String) {
            Log.w(TAG, "GAIA error: $message")
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollConnected()
            // alpha1.0: GAIA 已连接时每 30 秒刷新一次电量
            val gaia = GaiaBleClient.getInstance()
            if (gaia.isConnected()) {
                val now = System.currentTimeMillis()
                if (now - lastGaiaFetchMs > 30000) {
                    lastGaiaFetchMs = now
                    gaia.fetchBatteryLevels()
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun pollConnected() {
        try {
            if (!hasBtPermission()) return
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return

            val all = ArrayList<BluetoothDevice>()
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (bm != null) {
                try { all.addAll(bm.getConnectedDevices(BluetoothProfile.GATT)) } catch (_: Exception) { }
            }
            val ap = a2dpProxy
            if (a2dpReady && ap != null) {
                try { all.addAll(ap.connectedDevices) } catch (_: Exception) { }
            }
            val hp = headsetProxy
            if (headsetReady && hp != null) {
                try { all.addAll(hp.connectedDevices) } catch (_: Exception) { }
            }

            val now = HashSet<String>()
            for (d in all) {
                val n = d.name
                if (n != null && DeviceMatcher.isMoondrop(n)) {
                    now.add(d.address + "|" + n)
                }
            }

            // v3.16/v3.17: 检测到 Moondrop 连接时，静默拉起 GAIA 通讯服务
            var hasMoondrop = false
            for (key in now) {
                val idx = key.indexOf('|')
                val addr = key.substring(0, idx)
                val nm = key.substring(idx + 1)
                PopupGate.tryShowConnectedDeferred(this, addr, nm)
                hasMoondrop = true
                // alpha2.18: 同族设备（前缀12位相同）GAIA 已连时不重复发起（双地址自我反馈防护）
                val gaia = GaiaBleClient.getInstance()
                if (gaia.isConnected()) {
                    val cur = gaia.deviceAddress
                    if (cur != null && cur.length >= 12 && addr.length >= 12 &&
                            cur.substring(0, 12).equals(addr.substring(0, 12), ignoreCase = true)) {
                        AppLog.i(TAG, "gaia already connected to " + cur + ", skip reconnect (" + addr + ")")
                        continue
                    }
                }
                // alpha1.0: 直连 GAIA BLE 读左右耳电量 / ANC（不再依赖官方 App）
                // alpha1.4: 优先解析 LE 地址（GAIA over BLE），避免连 BR/EDR 地址失败
                var gaiaAddr: String? = null
                try { gaiaAddr = GaiaBleClient.resolveLeAddress(adapter, adapter.getRemoteDevice(addr)) } catch (_: Exception) { }
                if (gaiaAddr == null) gaiaAddr = addr
                AppLog.i(TAG, "detect: headset " + nm + " " + addr + " -> gaia conn " + gaiaAddr)
                // alpha1.4: 已连接/等待连接中不重复发起（地址比较在 LE 缓存下会误判）
                if (!gaia.isConnected() && gaia.deviceAddress == null) {
                    gaia.setCallback(gaiaCallback)
                    gaia.connect(this, gaiaAddr)
                } else if (!gaia.isConnected()) {
                    gaia.setCallback(gaiaCallback)
                    gaia.connect(this, gaia.deviceAddress)
                }
            }

            // v3.17: 断开时 force-stop Moondrop App
            for (key in lastSeen) {
                if (!now.contains(key)) {
                    val idx = key.indexOf('|')
                    val addr = key.substring(0, idx)
                    val nm = key.substring(idx + 1)
                    PopupGate.tryShowDisconnected(this, addr, nm)
                    AppLog.w(TAG, "headset disconnected: " + nm + " " + addr)
                    // alpha1.0: 断开 GAIA 直连
                    GaiaBleClient.getInstance().disconnect()
                    BatteryStore.clearGaia(addr)
                }
            }

            // 更新 Moondrop 连接时间戳，用于自动休眠检测
            if (hasMoondrop) {
                lastMoondropMs = System.currentTimeMillis()
                handler.removeCallbacks(sleepCheck)
            } else if (lastMoondropMs > 0) {
                handler.postDelayed(sleepCheck, SLEEP_MS)
            }
            if (hasMoondrop) {
                // alpha1.14fix1: 真实耳机在位 → 退出 GAIA 模拟态（防模拟连接遗留导致降噪/电量假更新）
                GaiaBleClient.setSimConnected(false)
            }
            lastSeen = now
        } catch (e: Exception) {
            Log.e(TAG, "poll failed: $e")
        }
    }
}
