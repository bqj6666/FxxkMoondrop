package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GAIA V3 over BLE GATT 客户端（自实现，不依赖官方 App）。
 * 协议逆向自官方 App 内置高通 gaiaclient：
 *  - Service: 00001100-d102-11e1-9b23-00025b00a5a5
 *  - Command: 00001101-... (write) / Response: 00001102-... (notify) / Data: 00001103-... (notify)
 *  - 包格式: [vendor 2B BE][commandValue 2B BE][payload]  (官方 BytesUtils.setUINT16 = Big Endian)
 *  - commandValue = (feature << 9) | (type << 7) | command
 *  - vendor=0x1D, BATTERY feature=0x0D, ANC_V2 feature=0x20
 */
class GaiaBleClient private constructor() {

    interface Callback {
        fun onConnected(address: String)
        fun onDisconnected(address: String)
        fun onBatteryLevel(batteryId: Int, level: Int)
        fun onAncMode(mode: Int)
        fun onError(message: String)
    }

    interface AncControlCallback {
        fun onAncModeResult(mode: Int)
        fun onAncError(message: String)
    }

    private var context: Context? = null
    private var callback: Callback? = null
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var respChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var srcCmdChar: BluetoothGattCharacteristic? = null
    private var srcRespChar: BluetoothGattCharacteristic? = null
    private var srcNotifyChar: BluetoothGattCharacteristic? = null
    private var srcCapChar: BluetoothGattCharacteristic? = null
    private var srcInfoChar: BluetoothGattCharacteristic? = null
    private var srcClient: BleSourceSwitchClient? = null
    var deviceAddress: String? = null
    private var connected = false
    private var ancCallback: AncControlCallback? = null

    /** alpha2.14: ANC 协议路径（feature id），能力探测后确定；-1=未探测/未知 */
    @Volatile private var ancPath = GaiaCommands.ANC_PATH_UNKNOWN

    /** alpha2.14: 能力探测只在每次成功连接后发送一次（防重复） */
    @Volatile private var featureProbeSent = false

    /** alpha2.14: 设备型号/版本字符串（GET_VARIANT / GET_APP_VERSION 响应） */
    @Volatile private var deviceInfo = ""
    private val handler = Handler(Looper.getMainLooper())

    // alpha1.4: BLE 扫描解析 LE 地址（独立 LE 身份地址设备，bonded 列表只有 BR/EDR 地址）
    private var leScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    @Volatile private var scanning = false
    /** alpha1.17: connectGatt 发起时间戳——autoConnect 挂起超时强制重连（LE 不广播死锁修复） */
    private var gattPendingSince = 0L
    /** alpha1.18: GAIA 连接候选轮换——GA2 双耳双地址（bonded 主地址 / 缓存 LE / 已知备用 LE） */
    @Volatile private var candidates: Array<String>? = null
    @Volatile private var candidateIdx = 0
    @Volatile private var attemptCount = 0
    @Volatile private var everConnected = false
    /** alpha1.23: 通用 Moondrop 扫描命中列表（任意地址水月雨耳机都能连） */
    private val scanHits = CopyOnWriteArrayList<ScanResult>()
    /** alpha1.17: 挂起超时后强制直接建链（autoConnect=false） */
    @Volatile private var forceDirectConnect = false

    private val scanTimeout = object : Runnable {
        override fun run() {
            stopScan()
            Log.w(TAG, "scan timeout, fallback to original address")
            retryConnect()
        }
    }

    /** 模拟连接模式：无耳机时 UI 测试用，不真实走 BLE（模拟连接按钮设置） */
    companion object {
        @JvmStatic
        @Synchronized
        fun getInstance(): GaiaBleClient {
            if (instance == null) instance = GaiaBleClient()
            return instance!!
        }

        @JvmStatic
        fun setSimConnected(v: Boolean) {
            simConnected = v
        }

        @JvmStatic
        fun isSimConnected(): Boolean = simConnected

        /** alpha1.4: 解析设备 LE 地址：BR/EDR 地址无法用于 GAIA BLE 连接时，
         *  在已配对设备中查找同名 LE 设备；找不到返回 null */
        @JvmStatic
        fun resolveLeAddress(adapter: BluetoothAdapter?, device: BluetoothDevice?): String? {
            try {
                if (device == null || adapter == null) return null
                if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return device.address
                val name = device.name
                val lower = (name ?: "").lowercase()
                try {
                    val sb = StringBuilder("resolveLeAddress scan: dev=" + device.address +
                            " type=" + device.type + " name=" + name + " | bonded:")
                    for (d in adapter.bondedDevices) {
                        sb.append(" [").append(d.address).append(" type=").append(d.type)
                                .append(" name=").append(d.name).append("]")
                    }
                    Log.d(TAG, sb.toString())
                } catch (_: Exception) { }
                for (d in adapter.bondedDevices) {
                    if (d.type != BluetoothDevice.DEVICE_TYPE_LE) continue
                    val dn = d.name ?: continue
                    if (name != null && name == dn) {
                        Log.d(TAG, "resolveLeAddress: " + device.address + " -> " + d.address)
                        return d.address
                    }
                    if (lower.contains("moondrop") && dn.lowercase().contains("moondrop")) {
                        Log.d(TAG, "resolveLeAddress: " + device.address + " -> " + d.address + " (name match)")
                        return d.address
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "resolveLeAddress permission denied", e)
            } catch (e: Exception) {
                Log.e(TAG, "resolveLeAddress failed", e)
            }
            return null
        }

        /** 构造 GAIA V3 数据包 */
        @JvmStatic
        private fun buildPacket(feature: Int, type: Int, command: Int, payload: ByteArray?): ByteArray {
            val cmdValue = (feature shl 9) or (type shl 7) or (command and 0x7F)
            val len = 4 + (payload?.size ?: 0)
            val packet = ByteArray(len)
            packet[0] = ((GAIA_VENDOR shr 8) and 0xFF).toByte()
            packet[1] = (GAIA_VENDOR and 0xFF).toByte()
            packet[2] = ((cmdValue shr 8) and 0xFF).toByte()
            packet[3] = (cmdValue and 0xFF).toByte()
            if (payload != null && payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, 4, payload.size)
            }
            return packet
        }


        const val TAG = "GaiaBleClient"
        const val PKG_GMS = "com.google.android.gms"
        const val GATT_PENDING_TIMEOUT_MS = 12000L
        const val SCAN_DURATION_MS = 8000L
        val UUID_SERVICE = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
        val UUID_COMMAND = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
        val UUID_RESPONSE = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
        val UUID_DATA = UUID.fromString("00001103-d102-11e1-9b23-00025b00a5a5")
        val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val GAIA_VENDOR = 0x1D
        const val FEATURE_BASIC = 0x00
        const val FEATURE_BATTERY = 0x0D
        const val FEATURE_ANC_V2 = 0x20
        const val FEATURE_ANC_V1 = 0x02
        const val F_AUDIO_CURATION = 0x08
        const val CMD_AC_GET_MODE = 0x03
        const val CMD_AC_SET_MODE = 0x04
        const val TYPE_COMMAND = 0
        const val TYPE_NOTIFICATION = 1
        const val TYPE_RESPONSE = 2
        const val CMD_GET_BATTERY_LEVELS = 0x01
        const val CMD_GET_CURRENT_MODE = 0x03
        const val CMD_SET_CURRENT_MODE = 0x04
        const val CMD_GET_SUPPORTED_FEATURES = 0x01
        const val CMD_GET_VARIANT = 0x04
        const val CMD_GET_APP_VERSION = 0x05
        const val CMD_ANC1_GET_ANC_STATE = 0x01
        const val CMD_ANC1_SET_ANC_STATE = 0x02
        const val BATTERY_LEFT = 1
        const val BATTERY_RIGHT = 2
        const val BATTERY_CASE = 3
        const val ACTION_LE_ADDR_FOUND = "com.fxxkmoondrop.secret.ACTION_LE_ADDR_FOUND"
        const val ACTION_REQ_LE_SCAN = "com.fxxkmoondrop.secret.ACTION_REQ_LE_SCAN"
        const val EXTRA_LE_ADDR = "addr"

        @JvmField @Volatile
        var instance: GaiaBleClient? = null

        @JvmField @Volatile
        var cachedLeAddress: String? = null // 上次成功解析的 LE 地址

        /** 模拟连接模式：无耳机时 UI 测试用（静态，跨组件共享） */
        @JvmField @Volatile
        var simConnected = false
    }

    /** alpha1.25: 传输选择——DUAL 设备优先 BR/EDR（高通 GAIA 在 BR/EDR 亦有 GATT），
     *  纯 LE 设备（扫描命中/独立 LE 身份）走 LE；动态按系统设备类型，无硬编码 */
    /** alpha2.12: DUAL 设备强制 BREDR 会导致 GATT status=135（Google FastPair 已占用 BR/EDR GATT，连接被拒）。
     * 统一走 TRANSPORT_LE（GAIA over BLE 主线，双模耳机 LE 通道仍可用）。 */
    private fun transportFor(d: BluetoothDevice): Int = BluetoothDevice.TRANSPORT_LE

    @Synchronized
    fun init(ctx: Context) {
        this.context = ctx.applicationContext
        registerLeAddrReceiver()
        if (!hasKnownLeAddr()) {
            /**** alpha1.34: 环境决策——纯净环境（无 Root 且无 FastPairHook）启用应用内置自扫（备用模式）；
             *  否则沿用 FastPairHook GMS 桥接（REQ -> GMS 扫描）。探测在子线程完成，不阻塞调用方。 ****/
            /**** 探测含 PING 等待，放到工作线程，避免阻塞主线程；
             *  延迟 3.5s 启动：避开应用冷启动主线程繁忙期（PONG 投递被延迟 3s+），确保探测准确 ****/
            handler.postDelayed({
                Thread({
                    try {
                        val rooted = EnvProbe.isRooted()
                        val hookOk = EnvProbe.isFastPairHookActive(context)
                        Log.d(TAG, "env: root=" + rooted + " module=" + hookOk)
                        if (hookOk) {
                            AppLog.i(TAG, "env: fastpair hook alive -> GMS bridge scan")
                            requestRemoteScan("boot-no-cache")
                        } else {
                            AppLog.i(TAG, "env: hook not reachable -> app self-scan fallback")
                            handler.postDelayed({ startGenericScan() }, 800)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "env probe failed, fallback to GMS bridge", t)
                        requestRemoteScan("boot-no-cache")
                    }
                }, "env-probe").start()
            }, 3500)
        }
    }

    /** alpha1.32: 是否已有已知 LE 地址（文件/SP），无则请求 FastPairHook 侧扫描发现（动态，零硬编码） */
    @Synchronized
    private fun hasKnownLeAddr(): Boolean {
        if (cachedLeAddress != null) return true
        val ctx = context ?: return false
        try {
            val f = File(ctx.filesDir, "gaia_le_addr.txt")
            if (f.exists() && f.length() > 10) return true
        } catch (_: Throwable) { }
        try {
            val v = ctx.getSharedPreferences("cfg", 0).getString("gaia_le_addr", null)
            if (v != null && v.length > 10) return true
        } catch (_: Throwable) { }
        return false
    }

    /** alpha1.32: 请求 FastPairHook（GMS 进程，BLE 扫描未被 ColorOS 拦截）扫描发现耳机 LE 地址 */
    private fun requestRemoteScan(reason: String) {
        val ctx = context ?: return
        try {
            val i = Intent(ACTION_REQ_LE_SCAN)
            i.setPackage(PKG_GMS)
            i.putExtra("reason", reason)
            ctx.sendBroadcast(i)
            Log.d(TAG, "LE scan requested -> GMS side: " + reason)
            AppLog.i(TAG, "LE scan requested -> GMS side: " + reason)
        } catch (t: Throwable) {
            Log.e(TAG, "requestRemoteScan failed", t)
        }
    }

    /** alpha1.32: 接收 FastPairHook 推送的 LE 地址（发现→自学习→持久化→连接） */
    private var leAddrReceiver: BroadcastReceiver? = null
    private fun registerLeAddrReceiver() {
        val ctx = context
        if (ctx == null || leAddrReceiver != null) return
        try {
            leAddrReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i == null) return
                    val a0 = i.getStringExtra(EXTRA_LE_ADDR) ?: return
                    val a = a0.trim().uppercase()
                    if (!a.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))) {
                        Log.w(TAG, "bad LE addr from broadcast: " + a)
                        return
                    }
                    Log.d(TAG, "LE addr from FastPairHook: " + a)
                    cachedLeAddress = a
                    saveLeAddrFile(a)
                    try {
                        context?.getSharedPreferences("cfg", 0)
                                ?.edit()?.putString("gaia_le_addr", a)?.commit()
                    } catch (_: Throwable) { }
                    if (!connected) {
                        candidates = arrayOf(a)
                        candidateIdx = 0
                        attemptCount = 0
                        connect(context!!, a)
                    } else if (!a.equals(deviceAddress, ignoreCase = true)) {
                        Log.d(TAG, "le addr changed while connected: " + a + " (kept current)")
                    }
                }
            }
            val f = IntentFilter(ACTION_LE_ADDR_FOUND)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(leAddrReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(leAddrReceiver, f)
            }
            Log.d(TAG, "le addr receiver registered")
        } catch (t: Throwable) {
            Log.e(TAG, "registerLeAddrReceiver failed", t)
        }
    }

    @Synchronized
    fun isConnected(): Boolean = simConnected || (connected && gatt != null)

    fun setCallback(cb: Callback?) {
        this.callback = cb
    }

    /** 连接指定地址耳机的 GAIA 服务 */
    @Synchronized
    fun connect(ctx: Context, address: String?): Boolean {
        if (address == null) return false
        AppLog.i(TAG, "connect() addr=" + address + " cachedLe=" + cachedLeAddress)
        simConnected = false // 真实连接接管
        if (context == null) context = ctx.applicationContext
        registerLeAddrReceiver() // alpha1.32: 兜底注册（幂等）
        // alpha1.4/1.30: 提前加载持久化 LE 地址缓存（优先 files 文件，无缓存歧义）
        if (cachedLeAddress == null && context != null) {
            try {
                val fd = context!!.filesDir
                Log.d(TAG, "filesDir=" + fd.absolutePath + " exists=" + fd.exists() +
                        " list=" + fd.list().contentToString())
                val f = File(fd, "gaia_le_addr.txt")
                if (f.exists()) {
                    val b = ByteArray(f.length().toInt())
                    val input = FileInputStream(f)
                    input.read(b)
                    input.close()
                    val v = String(b, Charsets.UTF_8).trim()
                    if (v.length > 0) cachedLeAddress = v
                    Log.d(TAG, "load le addr from file: exists=" + f.exists() +
                            " len=" + b.size + " val=" + cachedLeAddress)
                } else {
                    Log.d(TAG, "load le addr file absent: " + f.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "load le addr file failed", e)
            }
            if (cachedLeAddress == null) {
                try {
                    cachedLeAddress = context!!.getSharedPreferences("cfg", 0)
                            .getString("gaia_le_addr", null)
                    if (cachedLeAddress != null) Log.d(TAG, "loaded cached LE addr: " + cachedLeAddress)
                } catch (_: Exception) { }
            }
        }
        if (connected && address.equals(deviceAddress, ignoreCase = true)) return true
        // alpha1.18: autoConnect 挂起超过阈值 → 推进到下一个候选地址（双耳双地址轮换）
        if (!connected && gatt != null && address.equals(deviceAddress, ignoreCase = true)) {
            if (gattPendingSince > 0 && System.currentTimeMillis() - gattPendingSince > GATT_PENDING_TIMEOUT_MS) {
                Log.w(TAG, "gatt pending timeout on " + address + ", advance candidate")
                attemptCount++
                val candNow = candidates
                if (candNow == null || attemptCount >= candNow.size * 2 + 2) {
                    // alpha1.23: 长时间连不上 → 清缓存 + 启动通用 Moondrop 扫描（不再死循环）
                    Log.w(TAG, "all candidates failed, start generic moondrop scan")
                    clearCachedLe()
                    candidates = null
                    candidateIdx = 0
                    attemptCount = 0
                    handler.removeCallbacks(scanTimeout)
                    handler.postDelayed({ startGenericScan() }, 1000)
                } else {
                    advanceCandidate()
                }
                disconnectInternal()
            } else {
                return true
            }
        } else if (!connected && gatt != null) {
            disconnectInternal()
        }
        disconnectInternal()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return false
            var device = adapter.getRemoteDevice(address)
            // alpha1.18: 双模/独立地址耳机优先解析 bonded 同名 LE 地址（GAIA over BLE）
            val resolved = resolveLeAddress(adapter, device)
            var addr = address
            if (resolved != null) {
                candidates = arrayOf(resolved.uppercase())
                candidateIdx = 0
                device = adapter.getRemoteDevice(resolved)
                addr = resolved
                Log.d(TAG, "using resolved LE address " + addr)
            } else {
                // alpha1.18: 构建候选队列：bonded 主地址 → 缓存 LE 地址 → 已知备用 LE 地址
                val cand2 = candidates
                if (cand2 == null || cand2.isEmpty()) {
                    buildCandidates(addr)
                } else {
                    if (candidateIdx >= cand2.size) candidateIdx = 0
                    val target = cand2[candidateIdx]
                    if (target != null && !target.equals(addr, ignoreCase = true)) {
                        device = adapter.getRemoteDevice(target)
                        addr = target
                        Log.d(TAG, "using candidate[" + candidateIdx + "] " + addr)
                    }
                }
            }
            deviceAddress = addr
            // alpha2.17: DUAL 设备 bonded 无独立 LE 条目 -> 启动按名称扫描发现真实 LE 地址（自愈闭环）
            if (cachedLeAddress == null && resolved == null && !scanning) {
                Log.d(TAG, "no LE addr known, start name scan for " + (device.name ?: address))
                handler.removeCallbacks(scanTimeout)
                startScanForLe(device)
            }
            gattPendingSince = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, transportFor(device))
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, true, gattCallback)
            }
            Log.d(TAG, "connectGatt(auto) " + addr + " (cand " + candidateIdx + "/" +
                    (candidates?.size ?: 0) + ")")
            return gatt != null
        } catch (e: SecurityException) {
            Log.e(TAG, "no BLUETOOTH_CONNECT permission", e)
            AppLog.e(TAG, "connect SecurityException(no BLUETOOTH_CONNECT): " + e)
            callback?.onError("缺少蓝牙权限")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            return false
        }
    }

    /** alpha1.4: 启动 BLE 扫描，寻找与目标设备同名的 LE 广播（独立 LE 身份地址设备） */
    @Synchronized
    private fun startScanForLe(target: BluetoothDevice): Boolean {
        try {
            if (scanning) return true
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return false
            leScanner = adapter.bluetoothLeScanner
            if (leScanner == null) return false
            val targetName = target.name
            val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (scanning && result != null && result.device != null) {
                        var n = result.scanRecord?.deviceName
                        if (n == null) n = result.device.name
                        val addr = result.device.address
                        Log.d(TAG, "scan result: " + addr + " name=" + n)
                        AppLog.d("Scan", "hit " + addr + " name=" + n + " rssi=" + result.rssi)
                        var match = false
                        if (n != null && targetName != null) {
                            match = n == targetName ||
                                    (n.lowercase().contains("moondrop") &&
                                            targetName.lowercase().contains("moondrop"))
                        }
                        // 地址前缀相同也算候选（独立 LE 身份地址可能不带名字广播）
                        val ta = target.address
                        if (!match && ta.length == 17 && addr.length == 17 &&
                                ta.substring(0, 12).equals(addr.substring(0, 12), ignoreCase = true)) {
                            match = true
                        }
                        if (match) {
                            stopScan()
                            cachedLeAddress = addr
                            try {
                                if (context != null) {
                                    context!!.getSharedPreferences("cfg", 0)
                                            .edit().putString("gaia_le_addr", addr).commit()
                                    saveLeAddrFile(addr)
                                    Log.d(TAG, "LE address cached: " + addr)
                                }
                            } catch (_: Exception) { }
                            doConnectLe(result)
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "scan failed code=" + errorCode)
                    stopScan()
                    retryConnect()
                }
            }
            scanning = true
            leScanner!!.startScan(null, settings, scanCallback)
            handler.postDelayed(scanTimeout, 25000)
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "scan permission denied", e)
            scanning = false
            scanCallback = null
            leScanner = null
            return false
        } catch (e: Exception) {
            Log.e(TAG, "scan start failed", e)
            scanning = false
            scanCallback = null
            leScanner = null
            return false
        }
    }

    /*** alpha1.24: 广播地址前缀与 bonded 中 isMoondrop 设备是否一致（动态取系统 bonded，无硬编码） */
    @Synchronized
    private fun prefixMatchesBonded(address: String?): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || address == null || address.length < 12) return false
            val pre = address.substring(0, 12).uppercase()
            for (d in adapter.bondedDevices) {
                val n = d.name
                if (n == null || !DeviceMatcher.isMoondrop(n)) continue
                val da = d.address
                if (da != null && da.length >= 12 &&
                        da.substring(0, 12).uppercase() == pre) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /*** alpha1.27: 反射枚举系统已知设备（hidden API getDevicesMatchingConnectionStates）——
     *  动态获取 GA2 等机型的 LE 地址（避免硬编码；ColorOS 拦截第三方扫描时此路可用） */
    @Synchronized
    private fun enumKnownDevices(): MutableList<BluetoothDevice> {
        val out = ArrayList<BluetoothDevice>()
        try {
            val mgr = context?.getSystemService(Context.BLUETOOTH_SERVICE)
            if (mgr == null) return out
            // 公开 API（API 23+）：按连接状态枚举已知设备
            val states = intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING)
            val r = (mgr as BluetoothManager)
                    .getDevicesMatchingConnectionStates(BluetoothProfile.GATT, states)
            if (r is List<*>) {
                for (o in r) {
                    if (o is BluetoothDevice) out.add(o)
                }
            }
            Log.d(TAG, "known devices via getDevicesMatchingConnectionStates: " + out.size)
            for (d in out) {
                try {
                    Log.d(TAG, "  known: " + d.address + " type=" + d.type +
                            " name=" + d.name)
                } catch (_: Exception) { }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "known devices enum failed: " + t)
        }
        return out
    }

    /*** alpha1.27: 已知设备命中 Moondrop 的（LE 或 DUAL 均可）加入候选（动态，无硬编码） */
    @Synchronized
    private fun addKnownMoondropCandidates(set: LinkedHashSet<String>) {
        try {
            for (d in enumKnownDevices()) {
                val n = d.name
                if (n == null || !DeviceMatcher.isMoondrop(n)) continue
                val a = d.address
                if (a != null) set.add(a.uppercase())
            }
        } catch (_: Exception) { }
    }

    /*** alpha1.23: 通用 Moondrop BLE 扫描——任何名字含 moondrop/golden ages/水月雨 的耳机都收集 */
    @Synchronized
    private fun startGenericScan() {
        try {
            if (scanning) return
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return
            leScanner = adapter.bluetoothLeScanner
            if (leScanner == null) return
            scanHits.clear()
            val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result == null || result.device == null || result.device.address == null) return
                    var n = result.scanRecord?.deviceName
                    if (n == null) n = result.device.name
                    val addr = result.device.address
                    Log.d(TAG, "radio: " + addr + " name=" + (n ?: "<null>") + " rssi=" + result.rssi)
                    var hit = false
                    if (!n.isNullOrEmpty() && DeviceMatcher.isMoondrop(n)) {
                        hit = true
                    } else if (n.isNullOrEmpty()) {
                        // alpha1.24: 广播不带名字时，用 bonded isMoondrop 设备的地址前缀匹配
                        if (addr.length >= 14 && prefixMatchesBonded(addr)) hit = true
                    }
                    if (!hit) return
                    Log.d(TAG, "generic scan hit: " + addr + " name=" + n +
                            " rssi=" + result.rssi)
                    scanHits.add(result)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "generic scan failed code=" + errorCode)
                    stopScan()
                    retryConnect()
                }
            }
            scanning = true
            leScanner!!.startScan(null, settings, scanCallback)
            handler.postDelayed({
                if (!scanning) return@postDelayed
                stopScan()
                Log.d(TAG, "generic scan done, hits=" + scanHits.size)
                connectFromScanHits()
            }, SCAN_DURATION_MS)
            Log.d(TAG, "generic moondrop scan started")
        } catch (e: Exception) {
            Log.e(TAG, "generic scan start failed", e)
            retryConnect()
        }
    }

    /*** alpha1.23: 扫描结果按优先级排序后连接（golden ages 优先、bonded 次之） */
    @Synchronized
    private fun connectFromScanHits() {
        if (scanHits.isEmpty()) {
            retryConnect()
            requestRemoteScan("scan-miss")
            return
        }
        val sorted = ArrayList(scanHits)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        sorted.sortWith { a, b -> scoreHit(b, adapter) - scoreHit(a, adapter) }
        candidates = Array(sorted.size) { sorted[it].device.address.uppercase() }
        candidateIdx = 0
        attemptCount = 0
        doConnectLe(sorted[0])
    }

    private fun scoreHit(r: ScanResult, adapter: BluetoothAdapter?): Int {
        return try {
            var n0 = r.scanRecord?.deviceName
            if (n0 == null) n0 = r.device.name
            val n = (n0 ?: "").lowercase()
            var sc = 0
            if (n.contains("golden ages") || n.contains("goldenages")) sc += 100
            else if (n.contains("moondrop")) sc += 40
            if (adapter != null) {
                val addr = r.device.address
                for (d in adapter.bondedDevices) {
                    if (d.address.equals(addr, ignoreCase = true)) {
                        sc += 30
                        break
                    }
                }
            }
            sc
        } catch (_: Exception) {
            0
        }
    }

    @Synchronized
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning && leScanner != null) {
            try {
                scanCallback?.let { leScanner!!.stopScan(it) }
            } catch (_: Exception) { }
        }
        scanning = false
        scanCallback = null
        leScanner = null
    }

    /** 用扫描到的 LE 地址直接连接 */
    /*** alpha1.23: 用扫描结果原始设备连接（保留正确地址类型，解决 PUBLIC/RANDOM 错配超时） */
    private fun doConnectLe(result: ScanResult) {
        try {
            if (result == null || result.device == null) return
            val device = result.device
            val leAddress = device.address
            disconnectInternal()
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, transportFor(device))
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, true, gattCallback)
            }
            deviceAddress = leAddress
            connected = false
            cachedLeAddress = leAddress
            try {
                context?.getSharedPreferences("cfg", 0)
                        ?.edit()?.putString("gaia_le_addr", leAddress)?.commit()
                saveLeAddrFile(leAddress)
            } catch (_: Exception) { }
            gattPendingSince = System.currentTimeMillis()
            Log.d(TAG, "connectGatt(scan-result) " + leAddress)
        } catch (e: Exception) {
            Log.e(TAG, "doConnectLe(scan) failed", e)
        }
    }

    private fun doConnectLe(leAddress: String) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return
            val device = adapter.getRemoteDevice(leAddress)
            // alpha2.17: direct connect（auto=false），已知地址立即直连
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, true, gattCallback)
            }
            deviceAddress = leAddress
            connected = false
            // alpha1.18: 扫描命中即锁定该候选
            cachedLeAddress = leAddress
            saveLeAddrFile(leAddress)
            candidates = arrayOf(leAddress.uppercase())
            candidateIdx = 0
            attemptCount = 0
            Log.d(TAG, "connectGatt(LE) " + leAddress)
        } catch (e: Exception) {
            Log.e(TAG, "doConnectLe failed", e)
        }
    }

    /** 扫描失败/超时后回退到原地址连接 */
    private fun retryConnect() {
        val addr = if (candidates != null && candidates!!.isNotEmpty()) candidates!![0] else deviceAddress
        if (addr == null) return
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return
            val device = adapter.getRemoteDevice(addr)
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, true, gattCallback)
            }
            Log.d(TAG, "connectGatt(fallback-auto) " + addr)
        } catch (e: Exception) {
            Log.e(TAG, "retryConnect failed", e)
        }
        // alpha1.4: 60 秒后再次尝试扫描（耳机广播窗口可能已过）
        handler.postDelayed({
            val a = deviceAddress
            if (a == null || connected) return@postDelayed
            synchronized(this) {
                disconnectInternal()
            }
            connect(context!!, a)
        }, 60000)
    }

    /*** alpha1.18: 构建 GAIA 连接候选队列（去重，bonded 主地址优先） */
    @Synchronized
    private fun buildCandidates(bondedAddr: String?) {
        val set = LinkedHashSet<String>()
        // alpha2.20: 已学习 LE 地址优先于 bonded 主地址；GA2 双模机开盖可秒连 GAIA 通道，
        // 避免先拿 TRANSPORT_LE 连 BR 主地址白等 GATT_PENDING_TIMEOUT(12s) 再轮换。
        if (cachedLeAddress != null) set.add(cachedLeAddress!!.uppercase())
        if (bondedAddr != null) set.add(bondedAddr.uppercase())
        for (r in scanHits) {
            if (r != null && r.device != null) set.add(r.device.address.uppercase())
        }
        addKnownMoondropCandidates(set)
        candidates = set.toTypedArray()
        if (candidateIdx >= candidates!!.size) candidateIdx = 0
        Log.d(TAG, "GAIA candidates: " + candidates!!.contentToString())
    }

    /*** alpha1.18: 推进到下一个候选地址（循环） */
    @Synchronized
    private fun advanceCandidate() {
        if (candidates == null || candidates!!.isEmpty()) {
            candidateIdx = 0
            return
        }
        candidateIdx = (candidateIdx + 1) % candidates!!.size
        Log.d(TAG, "advance candidate -> " + candidates!![candidateIdx])
    }

    /*** alpha1.32: 应用自学习 LE 地址并持久化到 files/gaia_le_addr.txt（无需外部注入） */
    private fun saveLeAddrFile(addr: String?) {
        try {
            val ctx = context
            if (ctx == null || addr == null) return
            val f = File(ctx.filesDir, "gaia_le_addr.txt")
            val out = FileOutputStream(f)
            out.write((addr.trim() + "\n").toByteArray(Charsets.UTF_8))
            out.close()
            Log.d(TAG, "LE addr file saved: " + addr)
        } catch (e: Exception) {
            Log.e(TAG, "save le addr file failed", e)
        }
    }

    private fun removeLeAddrFile() {
        try {
            if (context == null) return
            File(context!!.filesDir, "gaia_le_addr.txt").delete()
        } catch (_: Exception) { }
    }

    /*** alpha1.18: 清除 LE 地址缓存（内存+持久化） */
    @Synchronized
    private fun clearCachedLe() {
        cachedLeAddress = null
        try {
            context?.getSharedPreferences("cfg", 0)
                    ?.edit()?.remove("gaia_le_addr")?.commit()
            removeLeAddrFile()
        } catch (_: Exception) { }
        Log.d(TAG, "LE address cache cleared")
    }

        /** alpha1.4: 强制断开并重新连接（前台刷新时用，重新走 LE 地址解析/扫描） */
    @Synchronized
    fun forceReconnect(ctx: Context, address: String?) {
        disconnectInternal()
        deviceAddress = null
        connected = false
        connect(ctx, address)
    }

    @Synchronized
    fun disconnect() {
        val addr = deviceAddress
        disconnectInternal()
        deviceAddress = null
        connected = false
        callback?.onDisconnected(addr ?: "")
    }

    private fun disconnectInternal() {
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) { }
        }
        gatt = null
        cmdChar = null
        respChar = null
        dataChar = null
        srcCmdChar = null
        srcRespChar = null
        srcNotifyChar = null
        srcCapChar = null
        srcInfoChar = null
        srcClient?.clear()
        srcClient = null
        // alpha2.19: 断开后重置能力探测标志与 ANC 路径，使每次重连都重新执行能力探测
        // （修复 ancPath 永远停留在 -1 导致降噪控制"时好时坏"、切后台后失联）
        featureProbeSent = false
        ancPath = GaiaCommands.ANC_PATH_UNKNOWN
    }

    /** 请求左右耳电量 */
    fun fetchBatteryLevels() {
        if (simConnected) return // 模拟电量由 BatteryStore 直填
        // alpha1.5: 官方 App（qti gaiaclient V3BatteryPlugin）实证：
        // feature=0x0D(BATTERY), cmd=0x01, payload=[1,2]（LEFT=1, RIGHT=2）
        val payload = byteArrayOf(BATTERY_LEFT.toByte(), BATTERY_RIGHT.toByte())
        writeCommand(FEATURE_BATTERY, CMD_GET_BATTERY_LEVELS, payload)
    }

    /** 查询当前 ANC 模式（V2） */
    fun fetchAncMode(cb: AncControlCallback?) {
        this.ancCallback = cb
        if (simConnected) {
            val m = AncBridge.getCurrentMode()
            cb?.onAncModeResult(if (m >= 0) m else 1)
            return
        }
        // alpha2.14: 按能力探测结果选择 ANC 查询路径（未知时回退 AudioCuration，兼容 GA2）
        when (ancPath) {
            GaiaCommands.ANC_PATH_ANC_V2 ->
                writeCommand(FEATURE_ANC_V2, CMD_GET_CURRENT_MODE, ByteArray(0))
            GaiaCommands.ANC_PATH_ANC_V1 ->
                writeCommand(FEATURE_ANC_V1, CMD_ANC1_GET_ANC_STATE, ByteArray(0))
            else -> writeCommand(F_AUDIO_CURATION, CMD_AC_GET_MODE, ByteArray(0))
        }
    }

    /** 设置 ANC 模式（入参为 UI 模式：0=关闭 1=降噪 2=透传 3=抗风 4=自适应）
     *  alpha2.14: 按能力探测结果映射到对应协议路径（AudioCuration / ANC V2 / ANC V1） */
    fun setAncMode(mode: Int, cb: AncControlCallback?) {
        this.ancCallback = cb
        if (simConnected) {
            cb?.onAncModeResult(mode) // 模拟链路：直接回显 UI 模式
            return
        }
        val dev = GaiaCommands.ancDevFromUi(ancPath, mode)
        if (dev < 0) {
            Log.w(TAG, "setAncMode: mode " + mode + " unsupported on path " + ancPath)
            cb?.onAncError("该设备不支持此模式")
            return
        }
        Log.d(TAG, "setAncMode ui=" + mode + " path=" + ancPath + " dev=" + dev)
        when (ancPath) {
            GaiaCommands.ANC_PATH_ANC_V2 ->
                writeCommand(FEATURE_ANC_V2, CMD_SET_CURRENT_MODE, byteArrayOf(dev.toByte()))
            GaiaCommands.ANC_PATH_ANC_V1 ->
                writeCommand(FEATURE_ANC_V1, CMD_ANC1_SET_ANC_STATE, byteArrayOf(dev.toByte()))
            else ->
                // AudioCuration(V1)：GA2 实证 setMode 有效但设备通常不回 ACK（乐观更新已由 AncBridge 负责）
                writeCommand(F_AUDIO_CURATION, CMD_AC_SET_MODE, byteArrayOf(dev.toByte()))
        }
    }

    @Synchronized
    private fun writeCommand(feature: Int, command: Int, payload: ByteArray) {
        val g = gatt
        val ch = cmdChar
        if (g == null || ch == null) {
            callback?.onError("GAIA 未连接")
            return
        }
        try {
            val packet = GaiaCommands.v3Packet(feature, command, payload)
            ch.value = packet
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(TAG, "TX feature=0x" + Integer.toHexString(feature) +
                    " cmd=0x" + Integer.toHexString(command) + " " + payload.contentToString())
        } catch (e: SecurityException) {
            Log.e(TAG, "write permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    /** 通用发送：发送任意 GaiaCommands 构造的 GAIA V3 包（风噪/蓝牙协议/EQ/手势等） */
    @Synchronized
    fun sendGaia(packet: ByteArray) {
        val g = gatt
        val ch = cmdChar
        if (g == null || ch == null) {
            callback?.onError("GAIA 未连接")
            return
        }
        try {
            ch.value = packet
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(TAG, "TX gaia " + packet.contentToString())
        } catch (e: SecurityException) {
            Log.e(TAG, "gaia write denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "gaia write failed", e)
        }
    }

    /** alpha2.15: 9ECA 事务客户端（蓝讯系设备；null=无 9ECA 服务） */
    fun getSrcClient(): BleSourceSwitchClient? = srcClient

    /** alpha2.15: 设备是否带 9ECA0000 服务（蓝讯系自动识别结果） */
    fun hasSrcService(): Boolean = srcClient != null && srcClient!!.isPresent()

    /** alpha2.15: 当前生效协议标识（GAIA / 9ECA / GAIA+9ECA / NONE） */
    fun activeProtocol(): String {
        val gaia = cmdChar != null
        val src = srcClient != null && srcClient!!.isPresent()
        return when {
            gaia && src -> "GAIA+9ECA"
            src -> "9ECA"
            gaia -> "GAIA"
            else -> "NONE"
        }
    }

    /** 通用发送：发送 Moondrop 私有协议帧（音源切换/EQ/MIC/SN，需设备支持 9ECA 服务） */
    @Synchronized
    fun sendSrc(frame: ByteArray) {
        val g = gatt
        val ch = srcCmdChar
        if (g == null || ch == null) {
            callback?.onError("设备不支持 BleSourceSwitch 服务")
            return
        }
        try {
            ch.value = frame
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(TAG, "TX src " + frame.contentToString())
            AppLog.d(TAG, "SRC TX " + AppLog.hex(frame))
        } catch (e: SecurityException) {
            Log.e(TAG, "src write denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "src write failed", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "gatt connected, discovering services")
                AppLog.i(TAG, "GATT connected " + deviceAddress + " -> discovering services")
                gattPendingSince = 0
                deviceAddress?.let {
                    saveLeAddrFile(it)
                    try {
                        context?.getSharedPreferences("cfg", 0)
                                ?.edit()?.putString("gaia_le_addr", it)?.commit()
                    } catch (_: Exception) { }
                }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "gatt disconnected status=" + status)
                AppLog.w(TAG, "GATT disconnected status=" + status + " addr=" + deviceAddress)
                connected = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // alpha1.26/1.29: 连接立即失败也计数；先轮换完所有候选，再扫描兜底
                    attemptCount++
                    Log.d(TAG, "unexpected disconnect, attemptCount=" + attemptCount +
                            " candidates=" + (candidates?.size ?: 0) +
                            " idx=" + candidateIdx)
                    if (candidates != null && candidateIdx < candidates!!.size - 1) {
                        advanceCandidate()
                        val target = candidates!![candidateIdx]
                        Log.d(TAG, "try next candidate: " + target)
                        connect(context!!, target)
                    } else if (attemptCount >= 2 && candidates != null) {
                        Log.w(TAG, "all candidates failed, start generic moondrop scan")
                        clearCachedLe()
                        candidates = null
                        candidateIdx = 0
                        attemptCount = 0
                        handler.removeCallbacks(scanTimeout)
                        handler.postDelayed({ startGenericScan() }, 500)
                    }
                }
                // alpha1.40: GATT 断开（无论正常/异常）即清 HeadsetGate MAC 缓存，
                // 否则主界面 realConnected 依赖旧缓存，降噪面板不隐藏、模拟区不显示
                try {
                    context?.let { HeadsetGate.clearConnectedMac(it) }
                } catch (e: Exception) {
                    Log.d(TAG, "clearConnectedMac fail: " + e)
                }
                callback?.onDisconnected(deviceAddress ?: "")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "service discovery failed status=" + status)
                callback?.onError("GAIA 服务发现失败")
                return
            }
            try {
                AppLog.i(TAG, "GATT services(" + (g.services?.size ?: 0) + "): " +
                        (g.services?.joinToString(" ") { it.uuid.toString() } ?: "?"))
                // alpha2.15: 自动识别协议——按 GATT 服务指纹路由，不依赖型号名：
                // GAIA 服务（QCC 系）与 9ECA0000（蓝讯系）可单独或同时存在
                val service = g.getService(UUID_SERVICE)
                val hasGaia = service != null
                if (hasGaia) {
                    AppLog.i(TAG, "protocol: GAIA service present (QCC 系)")
                    cmdChar = service.getCharacteristic(UUID_COMMAND)
                    respChar = service.getCharacteristic(UUID_RESPONSE)
                    dataChar = service.getCharacteristic(UUID_DATA)
                    if (cmdChar == null) {
                        Log.w(TAG, "GAIA service 存在但缺 command 特征")
                    } else {
                        enableNotification(respChar)
                        enableNotification(dataChar)
                    }
                } else {
                    Log.d(TAG, "GAIA service not present（疑为蓝讯 9ECA 设备）")
                }
                // Moondrop 私有 BleSourceSwitch 协议服务（9ECA0000，音源切换/EQ/MIC/设备信息）
                var hasSrc9 = false
                try {
                    val srcSvc = g.getService(UUID.fromString(GaiaCommands.SRC_SERVICE))
                    if (srcSvc != null) {
                        srcCmdChar = srcSvc.getCharacteristic(UUID.fromString(GaiaCommands.SRC_COMMAND))
                        srcRespChar = srcSvc.getCharacteristic(UUID.fromString(GaiaCommands.SRC_RESPONSE))
                        srcNotifyChar = srcSvc.getCharacteristic(UUID.fromString(GaiaCommands.SRC_NOTIFICATION))
                        srcCapChar = srcSvc.getCharacteristic(UUID.fromString(GaiaCommands.SRC_CAPABILITY))
                        srcInfoChar = srcSvc.getCharacteristic(UUID.fromString(GaiaCommands.SRC_FW_INFO))
                        srcClient = BleSourceSwitchClient(handler, null)
                        srcClient?.bind(g, srcCmdChar, srcRespChar, srcNotifyChar, srcCapChar, srcInfoChar)
                        enableNotification(srcRespChar)
                        enableNotification(srcNotifyChar)
                        hasSrc9 = true
                        Log.d(TAG, "BleSourceSwitch(9ECA) service ready, srcClient bound")
                        AppLog.i(TAG, "protocol: 9ECA(9ECA0000) service present (蓝讯系) -> srcClient bound")

                        // 直读能力页/固件信息（9ECA0004/0005），回调由 srcClient 解析
                        try { if (srcCapChar != null) g.readCharacteristic(srcCapChar) } catch (_: Exception) { }
                        try { if (srcInfoChar != null) g.readCharacteristic(srcInfoChar) } catch (_: Exception) { }
                    } else {
                        Log.d(TAG, "BleSourceSwitch service not present on this device")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "src service lookup failed", e)
                }
                if (!hasGaia && !hasSrc9) {
                    Log.e(TAG, "neither GAIA nor 9ECA service found")
                    AppLog.e(TAG, "protocol: neither GAIA nor 9ECA service found - unsupported device")
                    callback?.onError("未找到 GAIA / 9ECA 服务（暂不支持该设备）")
                    return
                }
                connected = true
                // alpha2.19: 每次成功建立新 GATT 会话都重新执行能力探测（防止复用旧状态跳过探测）
                featureProbeSent = false
                // alpha1.18: 记住成功连接的地址（双耳双地址轮换后锁定）
                val okAddr = deviceAddress
                if (okAddr != null) {
                    everConnected = true
                    cachedLeAddress = okAddr
                    candidates = arrayOf(okAddr.uppercase())
                    candidateIdx = 0
                    attemptCount = 0
                    try {
                        context?.getSharedPreferences("cfg", 0)
                                ?.edit()?.putString("gaia_le_addr", okAddr)?.commit()
                    } catch (_: Exception) { }
                    Log.d(TAG, "GAIA locked to " + okAddr)
                }
                Log.d(TAG, "GAIA ready")
                // alpha2.14: 能力探测——GET_SUPPORTED_FEATURES 决定 ANC 路径等跨型号适配
                try {
                    if (!featureProbeSent) {
                        featureProbeSent = true
                        writeCommand(FEATURE_BASIC, CMD_GET_SUPPORTED_FEATURES, ByteArray(0))
                        writeCommand(FEATURE_BASIC, CMD_GET_VARIANT, ByteArray(0))
                        // alpha2.19: 探测超时自愈——若 2 秒内 ancPath 仍未知（响应丢失），
                        // 说明设备未回复能力位图，主动重发一次，避免 ancPath 卡死在 -1
                        handler.postDelayed({
                            if (connected && gatt != null && ancPath == GaiaCommands.ANC_PATH_UNKNOWN) {
                                Log.w(TAG, "capability probe timeout, re-send GET_SUPPORTED_FEATURES")
                                writeCommand(FEATURE_BASIC, CMD_GET_SUPPORTED_FEATURES, ByteArray(0))
                                writeCommand(FEATURE_BASIC, CMD_GET_VARIANT, ByteArray(0))
                            }
                        }, 2000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "capability probe failed", e)
                }
                callback?.onConnected(deviceAddress ?: "")
            } catch (e: SecurityException) {
                Log.e(TAG, "permission denied", e)
            }
        }

        private fun enableNotification(ch: BluetoothGattCharacteristic?) {
            if (ch == null) return
            try {
                gatt?.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(UUID_CCCD)
                if (d != null) {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt?.writeDescriptor(d)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "notify enable denied", e)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val value = ch.value
            if (value == null || value.size < 2) return
            if (ch === srcRespChar || ch === srcNotifyChar) {
                val sc = srcClient
                if (sc != null) sc.onCharacteristicChanged(ch, value) else handleSrcPacket(value)
            } else if (ch === srcCapChar || ch === srcInfoChar) {
                srcClient?.onCharacteristicRead(ch, BluetoothGatt.GATT_SUCCESS, value)
            } else {
                handlePacket(value)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val value = ch.value ?: return
            if (srcClient != null && (ch === srcCapChar || ch === srcInfoChar || ch === srcRespChar)) {
                srcClient?.onCharacteristicRead(ch, status, value)
                return
            }
            if (value.size >= 4) handlePacket(value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "descriptor write status=" + status)
        }
    }

    /** 解析 Moondrop 私有协议响应帧：[0xA5][0x01][frameType][msgId][seq][len][payload] */
    private fun handleSrcPacket(value: ByteArray) {
        try {
            AppLog.d(TAG, "SRC RX " + AppLog.hex(value))
            if ((value[0].toInt() and 0xFF) != 0xA5 || (value[1].toInt() and 0xFF) != 0x01) return
            val frameType = value[2].toInt() and 0xFF
            val msgId = value[3].toInt() and 0xFF
            val seq = value[4].toInt() and 0xFF
            val len = value[5].toInt() and 0xFF
            val payload = if (value.size > 6)
                value.copyOfRange(6, Math.min(value.size, 6 + len)) else ByteArray(0)
            Log.d(TAG, "SRC RX type=" + frameType + " msg=0x" + Integer.toHexString(msgId) +
                    " seq=" + seq + " " + payload.contentToString())
        } catch (e: Exception) {
            Log.e(TAG, "src packet error", e)
        }
    }

    /** 解析 GAIA V3 数据包 */
    private fun handlePacket(value: ByteArray) {
        try {
            AppLog.d(TAG, "GAIA RX " + AppLog.hex(value))
            val vendor = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            val cmdValue = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
            if (vendor != GAIA_VENDOR) return
            val feature = (cmdValue shr 9) and 0x7F
            val type = (cmdValue shr 7) and 0x03
            val command = cmdValue and 0x7F
            val payload = if (value.size > 4) value.copyOfRange(4, value.size) else ByteArray(0)
            Log.d(TAG, "RX feature=0x" + Integer.toHexString(feature) +
                    " type=" + type + " cmd=0x" + Integer.toHexString(command) +
                    " " + payload.contentToString())

            if (feature == FEATURE_BATTERY && command == CMD_GET_BATTERY_LEVELS) {
                parseBatteryLevels(payload)
            } else if (feature == FEATURE_BASIC && command == CMD_GET_SUPPORTED_FEATURES) {
                // alpha2.14: 能力位图 -> ANC 路径
                val feats = GaiaCommands.parseSupportedFeatures(payload)
                ancPath = GaiaCommands.ancPathFrom(feats)
                Log.d(TAG, "capabilities: " + feats.sorted().joinToString(",") +
                        " -> ancPath=" + ancPath)
            } else if (feature == FEATURE_BASIC &&
                    (command == CMD_GET_VARIANT || command == CMD_GET_APP_VERSION)) {
                try {
                    val v = String(payload, Charsets.UTF_8).trim { it <= ' ' }
                    if (v.isNotEmpty()) deviceInfo = v
                    Log.d(TAG, "device info: " + deviceInfo)
                } catch (_: Exception) { }
            } else if ((feature == FEATURE_ANC_V2 || feature == F_AUDIO_CURATION) &&
                    (command == CMD_GET_CURRENT_MODE || command == CMD_SET_CURRENT_MODE ||
                            command == 0x01)) {
                // 0x01 = notification MODE_CHANGE（AudioCuration/ANC_V2 均为 cmd=1）
                parseAncMode(payload)
            } else if (feature == FEATURE_ANC_V1 &&
                    (command == CMD_ANC1_GET_ANC_STATE || command == CMD_ANC1_SET_ANC_STATE)) {
                parseAncMode(payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handlePacket error", e)
        }
    }

    private fun parseBatteryLevels(payload: ByteArray) {
        if (payload.size < 2) return
        var i = 0
        while (i + 1 < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val level = payload[i + 1].toInt() and 0xFF
            Log.d(TAG, "battery id=" + id + " level=" + level)
            callback?.let { cb ->
                handler.post {
                    cb.onBatteryLevel(id, level)
                }
            }
            i += 2
        }
    }

    private fun parseAncMode(payload: ByteArray) {
        if (payload.isEmpty()) return
        val dev = payload[0].toInt() and 0xFF
        // alpha2.14: 按 ANC 路径映射（ANC V2: 0关/1降噪/3抗风；ANC V1: 0关/1开；AC: 1关/2降噪/4透传/3抗风）
        val mode = GaiaCommands.ancUiFromDev(ancPath, dev)
        Log.d(TAG, "anc mode path=" + ancPath + " dev=" + dev + " ui=" + mode)
        if (mode >= 0) {
            ancCallback?.let { cb ->
                handler.post {
                    cb.onAncModeResult(mode)
                }
            }
        }
    }
}
