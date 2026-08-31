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
import android.bluetooth.BluetoothSocket
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
import java.util.ArrayDeque
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
 *
 * alpha2.27 架构重构：
 *  - 常量提取到 GaiaConstants.kt
 *  - 能力探测提取到 CapabilityProbe.kt
 *  - 包解析提取到 GaiaPacketHandler.kt
 *  - 本类保留：连接管理 + GATT 回调 + 公开 API 门面
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

    /** alpha2.31: 扩展设备控制回调（增益/LED/空间音频） */
    interface DeviceControlCallback {
        fun onGainResult(level: Int) {}
        fun onLedResult(state: Int) {}
        fun onSpatialResult(state: Int) {}
        fun onHeadTrackingResult(state: Int) {}
        fun onDeviceControlError(message: String) {}
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
    @Volatile private var connectedDeviceName: String? = null
    private var connected = false
    // alpha2.38.9: RFCOMM/SPP transport (for Classic BT devices like Pudding)
    @Volatile private var useRfcomm = false
    private var rfcommTransport: GaiaRfcommTransport? = null
    private var ancCallback: AncControlCallback? = null
    private var dcBridge: DeviceControlCallback? = null

    // alpha2.36: BLE write queue - Android only allows one write at a time
    private val gaiaWriteQueue = ArrayDeque<ByteArray>()
    @Volatile private var isGaiaWriting = false
    private val gaiaWriteDelayMs = 120L

    fun setDcBridge(cb: DeviceControlCallback?) { this.dcBridge = cb }  // alpha2.31
    private val handler = Handler(Looper.getMainLooper())

    // alpha2.27: 能力探测委托给独立状态机
    private val probe = CapabilityProbe(
        handler,
        { connected },
        { gatt != null || useRfcomm },
        { f, c, p -> writeCommand(f, c, p) }
    )

    // alpha2.27: 包解析委托给独立处理器
    private val packetHandler = GaiaPacketHandler(
        handler,
        { callback },
        { ancCallback },
        probe,
        { readAncMap() },
        { readAncGetMap() },
        { left, right -> sendBatteryBroadcast(left, right) },
        { dcBridge }
    )

    // BLE 扫描
    private var leScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    @Volatile private var scanning = false
    private var gattPendingSince = 0L
    @Volatile private var leAddrVerified = false
    @Volatile private var candidates: Array<String>? = null
    @Volatile private var candidateIdx = 0
    @Volatile private var attemptCount = 0
    @Volatile private var everConnected = false
    private val scanHits = CopyOnWriteArrayList<ScanResult>()
    @Volatile private var forceDirectConnect = false
    @Volatile private var lastConnectedAddr: String? = null
    @Volatile private var transportAutoTried = false
    @Volatile private var rfcommFallbackTried = false

    private val scanTimeout = object : Runnable {
        override fun run() {
            stopScan()
            Log.w(GaiaConstants.TAG, "scan timeout, fallback to original address")
            retryConnect()
        }
    }

    companion object {
        @JvmStatic @Synchronized
        fun getInstance(): GaiaBleClient {
            if (instance == null) instance = GaiaBleClient()
            return instance!!
        }

        @JvmStatic fun setSimConnected(v: Boolean) { simConnected = v }
        @JvmStatic fun isSimConnected(): Boolean = simConnected

        /** alpha1.4: 解析设备 LE 地址 */
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
                    Log.d(GaiaConstants.TAG, sb.toString())
                } catch (_: Exception) { }
                for (d in adapter.bondedDevices) {
                    if (d.type != BluetoothDevice.DEVICE_TYPE_LE) continue
                    val dn = d.name ?: continue
                    if (name != null && name == dn) {
                        Log.d(GaiaConstants.TAG, "resolveLeAddress: " + device.address + " -> " + d.address)
                        return d.address
                    }
                    if (DeviceMatcher.isMoondrop(lower) && DeviceMatcher.isMoondrop(dn)) {
                        Log.d(GaiaConstants.TAG, "resolveLeAddress: " + device.address + " -> " + d.address + " (name match)")
                        return d.address
                    }
                }
            } catch (e: SecurityException) {
                Log.e(GaiaConstants.TAG, "resolveLeAddress permission denied", e)
            } catch (e: Exception) {
                Log.e(GaiaConstants.TAG, "resolveLeAddress failed", e)
            }
            return null
        }

        @JvmField @Volatile var instance: GaiaBleClient? = null
        @JvmField @Volatile var cachedLeAddress: String? = null
        @JvmField @Volatile var cachedLeName: String? = null
        @JvmField @Volatile var simConnected = false
    }

    private fun transportFor(d: BluetoothDevice): Int {
        // alpha2.40.x: dual-mode TWS (BR/EDR+LE) 在服务发现阶段易被 LE 连接挤掉(status=147)。
        // 当纯 LE 持续失败时回退 TRANSPORT_AUTO 让系统自动选择；纯 LE 机型仍优先 LE。
        return if (transportAutoTried) BluetoothDevice.TRANSPORT_AUTO else BluetoothDevice.TRANSPORT_LE
    }

    @Synchronized
    fun init(ctx: Context) {
        this.context = ctx.applicationContext
        registerLeAddrReceiver()
        if (!hasKnownLeAddr()) {
            handler.postDelayed({
                Thread({
                    try {
                        val rooted = EnvProbe.isRooted()
                        val hookOk = EnvProbe.isFastPairHookActive(context)
                        Log.d(GaiaConstants.TAG, "env: root=" + rooted + " module=" + hookOk)
                        if (hookOk) {
                            AppLog.i(GaiaConstants.TAG, "env: fastpair hook alive -> app self-scan first, GMS bridge as fallback")
                            handler.postDelayed({ startGenericScan() }, 800)
                        } else {
                            AppLog.i(GaiaConstants.TAG, "env: hook not reachable -> app self-scan fallback")
                            handler.postDelayed({ startGenericScan() }, 800)
                        }
                    } catch (t: Throwable) {
                        Log.e(GaiaConstants.TAG, "env probe failed, fallback to GMS bridge", t)
                        requestRemoteScan("boot-no-cache")
                    }
                }, "env-probe").start()
            }, 3500)
        }
    }

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

    private fun requestRemoteScan(reason: String) {
        val ctx = context ?: return
        try {
            val i = Intent(GaiaConstants.ACTION_REQ_LE_SCAN)
            i.setPackage(GaiaConstants.PKG_GMS)
            i.putExtra("reason", reason)
            ctx.sendBroadcast(i)
            Log.d(GaiaConstants.TAG, "LE scan requested -> GMS side: " + reason)
            AppLog.i(GaiaConstants.TAG, "LE scan requested -> GMS side: " + reason)
        } catch (t: Throwable) {
            Log.e(GaiaConstants.TAG, "requestRemoteScan failed", t)
        }
    }

    private var leAddrReceiver: BroadcastReceiver? = null
    private fun registerLeAddrReceiver() {
        val ctx = context
        if (ctx == null || leAddrReceiver != null) return
        try {
            leAddrReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i == null) return
                    val a0 = i.getStringExtra(GaiaConstants.EXTRA_LE_ADDR) ?: return
                    val a = a0.trim().uppercase()
                    if (!a.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))) {
                        Log.w(GaiaConstants.TAG, "bad LE addr from broadcast: " + a)
                        return
                    }
                    Log.d(GaiaConstants.TAG, "LE addr from FastPairHook: " + a)
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
                        Log.d(GaiaConstants.TAG, "le addr changed while connected: " + a + " (kept current)")
                    }
                }
            }
            val f = IntentFilter(GaiaConstants.ACTION_LE_ADDR_FOUND)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(leAddrReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(leAddrReceiver, f)
            }
            Log.d(GaiaConstants.TAG, "le addr receiver registered")
        } catch (t: Throwable) {
            Log.e(GaiaConstants.TAG, "registerLeAddrReceiver failed", t)
        }
    }

    @Synchronized
    fun isConnected(): Boolean = simConnected || (connected && (gatt != null || useRfcomm))

    fun setCallback(cb: Callback?) { this.callback = cb }

    @Synchronized
    fun connect(ctx: Context, address: String?): Boolean {
        if (address == null) return false
        AppLog.i(GaiaConstants.TAG, "connect() addr=" + address + " cachedLe=" + cachedLeAddress)
        simConnected = false
        if (context == null) context = ctx.applicationContext
        registerLeAddrReceiver()
        if (cachedLeAddress == null && context != null) {
            try {
                val fd = context!!.filesDir
                Log.d(GaiaConstants.TAG, "filesDir=" + fd.absolutePath + " exists=" + fd.exists() +
                        " list=" + fd.list().contentToString())
                val f = File(fd, "gaia_le_addr.txt")
                if (f.exists()) {
                    val b = ByteArray(f.length().toInt())
                    val input = FileInputStream(f)
                    input.read(b)
                    input.close()
                    val v = String(b, Charsets.UTF_8).trim()
                    if (v.length > 0) cachedLeAddress = v
                    Log.d(GaiaConstants.TAG, "load le addr from file: exists=" + f.exists() +
                            " len=" + b.size + " val=" + cachedLeAddress)
                } else {
                    Log.d(GaiaConstants.TAG, "load le addr file absent: " + f.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(GaiaConstants.TAG, "load le addr file failed", e)
            }
            if (cachedLeAddress == null) {
                try {
                    cachedLeAddress = context!!.getSharedPreferences("cfg", 0)
                            .getString("gaia_le_addr", null)
                    if (cachedLeAddress != null) Log.d(GaiaConstants.TAG, "loaded cached LE addr: " + cachedLeAddress)
                } catch (_: Exception) { }
            }
        }
        // alpha2.42: RFCOMM/SPP 已连时直接复用，避免 detect 轮询的 connect() 断开 RFCOMM
        if (useRfcomm && connected) return true
        if (connected && address.equals(deviceAddress, ignoreCase = true)) return true
        if (!connected && gatt != null && address.equals(deviceAddress, ignoreCase = true)) {
            if (gattPendingSince > 0 && System.currentTimeMillis() - gattPendingSince > GaiaConstants.GATT_PENDING_TIMEOUT_MS) {
                Log.w(GaiaConstants.TAG, "gatt pending timeout on " + address + ", advance candidate")
                attemptCount++
                val candNow = candidates
                if (candNow == null || candNow.size <= 1 || attemptCount >= candNow.size * 2) {
                    Log.w(GaiaConstants.TAG, "all candidates failed, start generic moondrop scan")
                    clearCachedLe()
                    candidates = null
                    candidateIdx = 0
                    attemptCount = 0
                    handler.removeCallbacks(scanTimeout)
                    handler.postDelayed({ startGenericScan() }, 1000)
                } else {
                    advanceCandidate()
                }
            } else {
                return true
            }
        }
        // alpha2.42: 每次新连接尝试重置 RFCOMM 标志（默认兜底；detect 每5s触发一次 connect）
        rfcommFallbackTried = false
        disconnectInternal()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return false
            var device = adapter.getRemoteDevice(address)
            val resolved = resolveLeAddress(adapter, device)
            var addr = address
            if (resolved != null) {
                candidates = arrayOf(resolved.uppercase())
                candidateIdx = 0
                device = adapter.getRemoteDevice(resolved)
                addr = resolved
                Log.d(GaiaConstants.TAG, "using resolved LE address " + addr)
            } else {
                val targetName = device?.name
                val canUseCachedLe = cachedLeAddress != null &&
                        (cachedLeName == null || cachedLeName.equals(targetName, ignoreCase = true))
                val cand2 = candidates
                if (cand2 == null || cand2.isEmpty()) {
                    buildCandidates(addr)
                    if (canUseCachedLe && candidates != null && candidates!!.isNotEmpty()) {
                        val le = cachedLeAddress!!.uppercase()
                        val first = candidates!![0]
                        if (first != null && first.equals(le, true) && !addr.equals(le, true)) {
                            addr = le
                            device = adapter.getRemoteDevice(le)
                            Log.d(GaiaConstants.TAG, "buildCandidates->le first " + addr)
                        }
                    }
                } else {
                    var start = candidateIdx
                    if (canUseCachedLe) {
                        val le = cachedLeAddress!!.uppercase()
                        val reordered = LinkedHashSet<String>()
                        reordered.add(le)
                        for (c in cand2) { if (c != null) reordered.add(c.uppercase()) }
                        candidates = reordered.toTypedArray()
                        start = 0
                    }
                    if (start >= candidates!!.size) start = 0
                    val target = candidates!![start]
                    if (target != null && !target.equals(addr, ignoreCase = true)) {
                        device = adapter.getRemoteDevice(target)
                        addr = target
                        Log.d(GaiaConstants.TAG, "using candidate[" + start + "] " + addr)
                    }
                }
            }
            deviceAddress = addr
            leAddrVerified = false
            if (cachedLeAddress == null && resolved == null && !scanning) {
                Log.d(GaiaConstants.TAG, "no LE addr known, start name scan for " + (device.name ?: address))
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
            val candIdxForLog = candidates?.indexOfFirst { it != null && it.equals(addr, ignoreCase = true) } ?: -1
            Log.d(GaiaConstants.TAG, "connectGatt(auto) " + addr + " (cand " + candIdxForLog + "/" +
                    (candidates?.size ?: 0) + ")")
            return gatt != null
        } catch (e: SecurityException) {
            Log.e(GaiaConstants.TAG, "no BLUETOOTH_CONNECT permission", e)
            AppLog.e(GaiaConstants.TAG, "connect SecurityException(no BLUETOOTH_CONNECT): " + e)
            callback?.onError("缺少蓝牙权限")
            return false
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "connect failed", e)
            return false
        }
    }

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
        } catch (_: Exception) { false }
    }

    @Synchronized
    private fun enumKnownDevices(): MutableList<BluetoothDevice> {
        val out = ArrayList<BluetoothDevice>()
        try {
            val mgr = context?.getSystemService(Context.BLUETOOTH_SERVICE)
            if (mgr == null) return out
            val states = intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING)
            val r = (mgr as BluetoothManager)
                    .getDevicesMatchingConnectionStates(BluetoothProfile.GATT, states)
            if (r is List<*>) {
                for (o in r) { if (o is BluetoothDevice) out.add(o) }
            }
            Log.d(GaiaConstants.TAG, "known devices via getDevicesMatchingConnectionStates: " + out.size)
            for (d in out) {
                try { Log.d(GaiaConstants.TAG, "  known: " + d.address + " type=" + d.type + " name=" + d.name) }
                catch (_: Exception) { }
            }
        } catch (t: Throwable) { Log.d(GaiaConstants.TAG, "known devices enum failed: " + t) }
        return out
    }

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

    /** alpha2.28: unified BLE scan (merged startScanForLe + startGenericScan) */
    @Synchronized
    private fun startLeScan(
            matcher: (String?, String, ScanResult) -> Boolean,
            onHitImmediate: Boolean,
            timeoutMs: Long,
            onScanDone: () -> Unit
    ): Boolean {
        try {
            if (scanning) return true
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return false
            leScanner = adapter.bluetoothLeScanner
            if (leScanner == null) return false
            val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!scanning || result == null || result.device == null) return
                    var n = result.scanRecord?.deviceName
                    if (n == null) n = result.device.name
                    val addr = result.device.address
                    if (matcher(n, addr, result)) {
                        if (onHitImmediate) {
                            stopScan()
                            cachedLeAddress = addr
                            try {
                                if (context != null) {
                                    context!!.getSharedPreferences("cfg", 0)
                                            .edit().putString("gaia_le_addr", addr).commit()
                                    saveLeAddrFile(addr)
                                    Log.d(GaiaConstants.TAG, "LE address cached: " + addr)
                                }
                            } catch (_: Exception) { }
                            doConnectLe(result)
                        } else {
                            scanHits.add(result)
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.w(GaiaConstants.TAG, "scan failed code=" + errorCode)
                    stopScan()
                    retryConnect()
                }
            }
            scanning = true
            leScanner!!.startScan(null, settings, scanCallback)
            handler.postDelayed({
                if (!scanning) return@postDelayed
                stopScan()
                onScanDone()
            }, timeoutMs)
            return true
        } catch (e: SecurityException) {
            Log.e(GaiaConstants.TAG, "scan permission denied", e)
            scanning = false
            scanCallback = null
            leScanner = null
            return false
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "scan start failed", e)
            scanning = false
            scanCallback = null
            leScanner = null
            if (!onHitImmediate) retryConnect()
            return false
        }
    }

    /** Scan for LE address by target device name/prefix (connect on first hit) */
    @Synchronized
    private fun startScanForLe(target: BluetoothDevice): Boolean {
        val targetName = target.name
        val ta = target.address
        return startLeScan(
                matcher = { n, addr, _ ->
                    var match = false
                    if (n != null && targetName != null) {
                        match = n == targetName ||
                                (DeviceMatcher.isMoondrop(n) &&
                                        DeviceMatcher.isMoondrop(targetName))
                    }
                    if (!match && ta.length == 17 && addr.length == 17 &&
                            ta.substring(0, 12).equals(addr.substring(0, 12), ignoreCase = true)) {
                        match = true
                    }
                    if (match) {
                        Log.d(GaiaConstants.TAG, "scan result: " + addr + " name=" + n)
                        AppLog.d("Scan", "hit " + addr + " name=" + n + " rssi=0")
                    }
                    match
                },
                onHitImmediate = true,
                timeoutMs = 25000,
                onScanDone = { retryConnect() }
        )
    }

    /** Generic Moondrop device scan (collect all hits, pick best on timeout) */
    @Synchronized
    private fun startGenericScan() {
        scanHits.clear()
        val ok = startLeScan(
                matcher = { n, addr, result ->
                    var hit = false
                    if (!n.isNullOrEmpty() && (DeviceMatcher.isMoondrop(n) || AncProfileLib.isMoondrop(n))) {
                        hit = true
                    } else if (n.isNullOrEmpty()) {
                        if (addr.length >= 14 && prefixMatchesBonded(addr)) hit = true
                    }
                    if (hit) {
                        Log.d(GaiaConstants.TAG, "generic scan hit: " + addr + " name=" + n + " rssi=" + result.rssi)
                    }
                    hit
                },
                onHitImmediate = false,
                timeoutMs = GaiaConstants.SCAN_DURATION_MS,
                onScanDone = {
                    Log.d(GaiaConstants.TAG, "generic scan done, hits=" + scanHits.size)
                    connectFromScanHits()
                }
        )
        if (ok) Log.d(GaiaConstants.TAG, "generic moondrop scan started")
        else retryConnect()
    }

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
            var sc = 0
            if (!r.scanRecord?.deviceName.isNullOrEmpty()) sc += 50
            if (adapter != null) {
                val addr = r.device.address
                for (d in adapter.bondedDevices) {
                    if (d.address.equals(addr, ignoreCase = true)) { sc += 30; break }
                }
            }
            sc
        } catch (_: Exception) { 0 }
    }

    private fun refreshGattCache(g: BluetoothGatt) {
        try {
            g.javaClass.getMethod("refresh").invoke(g)
        } catch (_: Exception) {
            try {
                val m = g.javaClass.getDeclaredMethod("refresh")
                m.isAccessible = true
                m.invoke(g)
            } catch (_: Exception) { }
        }
    }

    @Synchronized
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning && leScanner != null) {
            try { scanCallback?.let { leScanner!!.stopScan(it) } } catch (_: Exception) { }
        }
        scanning = false
        scanCallback = null
        leScanner = null
    }

    private fun doConnectLe(result: ScanResult) {
        try {
            if (result == null || result.device == null) return
            val device = result.device
            val leAddress = device.address
            disconnectInternal()
            leAddrVerified = true
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, transportFor(device))
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, true, gattCallback)
            }
            deviceAddress = leAddress
            connected = false
            cachedLeAddress = leAddress
            cachedLeName = device.name
            try {
                context?.getSharedPreferences("cfg", 0)
                        ?.edit()?.putString("gaia_le_addr", leAddress)?.commit()
                saveLeAddrFile(leAddress)
            } catch (_: Exception) { }
            gattPendingSince = System.currentTimeMillis()
            Log.d(GaiaConstants.TAG, "connectGatt(scan-result) " + leAddress)
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "doConnectLe(scan) failed", e)
        }
    }

    private fun doConnectLe(leAddress: String) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) return
            val device = adapter.getRemoteDevice(leAddress)
            leAddrVerified = false
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, true, gattCallback)
            }
            deviceAddress = leAddress
            connected = false
            cachedLeAddress = leAddress
            cachedLeName = device.name
            saveLeAddrFile(leAddress)
            candidates = arrayOf(leAddress.uppercase())
            candidateIdx = 0
            attemptCount = 0
            Log.d(GaiaConstants.TAG, "connectGatt(LE) " + leAddress)
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "doConnectLe failed", e)
        }
    }

    private fun retryConnect() {
        var addr: String? = null
        if (cachedLeAddress != null) {
            val useCached = cachedLeName == null || deviceAddress == null || cachedLeName.equals(
                    deviceAddress.let { runCatching { BluetoothAdapter.getDefaultAdapter()
                            ?.getRemoteDevice(it)?.name }.getOrNull() }, ignoreCase = true)
            if (useCached) addr = cachedLeAddress
        }
        if (addr == null && candidates != null && candidates!!.isNotEmpty()) addr = candidates!![0]
        if (addr == null) addr = deviceAddress
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
            Log.d(GaiaConstants.TAG, "connectGatt(fallback-auto) " + addr)
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "retryConnect failed", e)
        }
        handler.postDelayed({
            val a = deviceAddress
            if (a == null || connected) return@postDelayed
            synchronized(this) { disconnectInternal() }
            connect(context!!, a)
        }, 15000)
    }

    @Synchronized
    private fun buildCandidates(bondedAddr: String?) {
        val set = LinkedHashSet<String>()
        if (cachedLeAddress != null) set.add(cachedLeAddress!!.uppercase())
        if (bondedAddr != null) set.add(bondedAddr.uppercase())
        for (r in scanHits) {
            if (r != null && r.device != null) set.add(r.device.address.uppercase())
        }
        addKnownMoondropCandidates(set)
        candidates = set.toTypedArray()
        if (candidateIdx >= candidates!!.size) candidateIdx = 0
        Log.d(GaiaConstants.TAG, "GAIA candidates: " + candidates!!.contentToString())
    }

    @Synchronized
    private fun advanceCandidate() {
        if (candidates == null || candidates!!.isEmpty()) { candidateIdx = 0; return }
        candidateIdx = (candidateIdx + 1) % candidates!!.size
        Log.d(GaiaConstants.TAG, "advance candidate -> " + candidates!![candidateIdx])
    }

    private fun saveLeAddrFile(addr: String?) {
        try {
            val ctx = context
            if (ctx == null || addr == null) return
            val f = File(ctx.filesDir, "gaia_le_addr.txt")
            val out = FileOutputStream(f)
            out.write((addr.trim() + "\n").toByteArray(Charsets.UTF_8))
            out.close()
            Log.d(GaiaConstants.TAG, "LE addr file saved: " + addr)
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "save le addr file failed", e)
        }
    }

    private fun removeLeAddrFile() {
        try { if (context == null) return; File(context!!.filesDir, "gaia_le_addr.txt").delete() }
        catch (_: Exception) { }
    }

    @Synchronized
    private fun clearCachedLe() {
        cachedLeAddress = null
        cachedLeName = null
        try {
            context?.getSharedPreferences("cfg", 0)?.edit()?.remove("gaia_le_addr")?.commit()
            removeLeAddrFile()
        } catch (_: Exception) { }
        Log.d(GaiaConstants.TAG, "LE address cache cleared")
    }

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
        try { DeviceControlBridge.reset() } catch (_: Exception) { }
        callback?.onDisconnected(addr ?: "")
    }

    private fun disconnectInternal() {
        gatt?.let {
            try { it.disconnect(); it.close() } catch (_: Exception) { }
        }
        gatt = null
        rfcommTransport?.disconnect()
        rfcommTransport = null
        useRfcomm = false
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
        probe.reset()
    }

    // alpha2.38.9: RFCOMM/SPP fallback for Classic BT devices (e.g. Pudding MD-TWS-056)
    private fun tryRfcommFallback(device: BluetoothDevice) {
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        Thread {
            val transport = GaiaRfcommTransport(
                handler,
                { packet -> packetHandler.handlePacket(packet) },
                {
                    connected = false
                    useRfcomm = false
                    rfcommTransport = null
                    try { DeviceControlBridge.reset() } catch (_: Exception) {}
                    callback?.onDisconnected(deviceAddress ?: "")
                },
                { msg -> handler.post { callback?.onError(msg) } }
            )
            if (transport.connect(device)) {
                handler.post {
                    rfcommTransport = transport
                    useRfcomm = true
                    connected = true
                    connectedDeviceName = device.name ?: connectedDeviceName
                    Log.i(GaiaConstants.TAG, "RFCOMM connected: " + connectedDeviceName)
                    AppLog.i(GaiaConstants.TAG, "protocol: RFCOMM/SPP connected (" + connectedDeviceName + ")")
                    probe.reset()
                    probe.startProbes()
                    callback?.onConnected(deviceAddress ?: "")
                    handler.postDelayed({
                        if (connected) fetchBatteryLevels()
                        if (connected) {
                            try { AncBridge.fetchAncMode() } catch (_: Exception) {}
                            try { AncBridge.sendAncStatus(probe.status()) } catch (_: Exception) {}
                            try { DeviceControlBridge.applyProfile(AncProfileLib.resolveDc(connectedDeviceName)); DeviceControlBridge.fetchAll() } catch (_: Exception) {}
                        }
                    }, 1200)
                    handler.postDelayed({
                        if (connected) {
                            try { DeviceControlBridge.fetchAll() } catch (_: Exception) {}
                        }
                    }, 4000)
                }
            } else {
                handler.post { callback?.onError("device unsupported (GAIA/9ECA/RFCOMM)") }
            }
        }.start()
    }

    fun fetchBatteryLevels() {
        if (simConnected) return
        val payload = byteArrayOf(GaiaConstants.BATTERY_LEFT.toByte(), GaiaConstants.BATTERY_RIGHT.toByte())
        writeCommand(GaiaConstants.FEATURE_BATTERY, GaiaConstants.CMD_GET_BATTERY_LEVELS, payload)
    }

    // alpha2.31: 扩展设备控制 API
    fun hasGainSupport(): Boolean = probe.hasFeature(GaiaCommands.F_DAC_GAIN)
    fun hasLedSupport(): Boolean = probe.hasFeature(GaiaCommands.F_LED)
    fun hasSpatialSupport(): Boolean = probe.hasFeature(GaiaCommands.F_SPATIAL_AUDIO)

    fun fetchGain() {
        if (simConnected) { dcBridge?.onGainResult(1); return }
        writeCommand(GaiaConstants.FEATURE_DAC_GAIN, GaiaConstants.CMD_DAC_GET_GAIN, ByteArray(0))
    }

    fun setGain(level: Int) {
        if (simConnected) { dcBridge?.onGainResult(level); return }
        writeCommand(GaiaConstants.FEATURE_DAC_GAIN, GaiaConstants.CMD_DAC_SET_GAIN, byteArrayOf(level.toByte()))
    }

    fun fetchLed() {
        if (simConnected) { dcBridge?.onLedResult(0); return }
        writeCommand(GaiaConstants.FEATURE_LED, GaiaConstants.CMD_LED_GET_STATE, ByteArray(0))
    }

    fun setLed(state: Int) {
        if (simConnected) { dcBridge?.onLedResult(state); return }
        writeCommand(GaiaConstants.FEATURE_LED, GaiaConstants.CMD_LED_SET_STATE, byteArrayOf(state.toByte()))
    }

    fun fetchSpatial() {
        if (simConnected) { dcBridge?.onSpatialResult(0); return }
        writeCommand(GaiaConstants.FEATURE_SPATIAL_AUDIO, GaiaConstants.CMD_SPATIAL_GET_STATE, ByteArray(0))
    }

    fun setSpatial(state: Int) {
        if (simConnected) { dcBridge?.onSpatialResult(state); return }
        writeCommand(GaiaConstants.FEATURE_SPATIAL_AUDIO, GaiaConstants.CMD_SPATIAL_SET_STATE, byteArrayOf(state.toByte()))
    }

    fun fetchHeadTracking() {
        if (simConnected) { dcBridge?.onHeadTrackingResult(0); return }
        writeCommand(GaiaConstants.FEATURE_SPATIAL_AUDIO, GaiaConstants.CMD_SPATIAL_GET_HEAD_TRACKING, ByteArray(0))
    }

    fun setHeadTracking(state: Int) {
        if (simConnected) { dcBridge?.onHeadTrackingResult(state); return }
        writeCommand(GaiaConstants.FEATURE_SPATIAL_AUDIO, GaiaConstants.CMD_SPATIAL_SET_HEAD_TRACKING, byteArrayOf(state.toByte()))
    }

    fun fetchAncMode(cb: AncControlCallback?) {
        this.ancCallback = cb
        if (simConnected) {
            val m = AncBridge.getCurrentMode()
            cb?.onAncModeResult(if (m >= 0) m else 1)
            return
        }
        Log.d(GaiaConstants.TAG, "fetchAncMode path=" + probe.ancPath +
                " devName=" + connectedDeviceName +
                " profile=" + AncProfileLib.matchedProfileName(connectedDeviceName))
        when (probe.ancPath) {
            GaiaCommands.ANC_PATH_ANC_V2 ->
                writeCommand(GaiaConstants.FEATURE_ANC_V2, GaiaConstants.CMD_GET_CURRENT_MODE, ByteArray(0))
            GaiaCommands.ANC_PATH_ANC_V1 ->
                writeCommand(GaiaConstants.FEATURE_ANC_V1, GaiaConstants.CMD_ANC1_GET_ANC_STATE, ByteArray(0))
            GaiaCommands.ANC_PATH_AUDIO_CURATION ->
                writeCommand(GaiaConstants.F_AUDIO_CURATION, GaiaConstants.CMD_AC_GET_MODE, ByteArray(0))
            else -> cb?.onAncError("ANC 能力未就绪")
        }
    }

    fun ancCapabilityStatus(): Int = probe.status()

    private fun readAncMap(): IntArray {
        val sp = context?.getSharedPreferences("cfg", 0)
        val custom: IntArray? = if ((sp?.getInt("anc_map_custom", 0) ?: 0) == 1) {
            val m = IntArray(4)
            for (i in 0..3) {
                val v = sp?.getInt("anc_map_" + i, AncProfileLib.DEFAULT_MAP[i]) ?: AncProfileLib.DEFAULT_MAP[i]
                m[i] = if (v in 0..5) v else AncProfileLib.DEFAULT_MAP[i]
            }
            m
        } else null
        val map = AncProfileLib.resolve(connectedDeviceName, custom)
        val src = if (custom != null) "custom" else if (AncProfileLib.matchedProfileName(connectedDeviceName) != "默认") "profile" else "default"
        AppLog.d(GaiaConstants.TAG, "ancSetMap src=" + src + " map=" + map.contentToString() +
                " profile=" + AncProfileLib.matchedProfileName(connectedDeviceName))
        return if (map.any { it !in 0..5 }) AncProfileLib.DEFAULT_MAP else map
    }

    private fun readAncGetMap(): IntArray? {
        val sp = context?.getSharedPreferences("cfg", 0)
        val customSet = (sp?.getInt("anc_map_custom", 0) ?: 0) == 1
        Log.d(GaiaConstants.TAG, "readAncGetMap devName=" + connectedDeviceName +
                " customSet=" + customSet + " ctx=" + (context != null))
        val m = AncProfileLib.resolveGetMap(connectedDeviceName, customSet)
        AppLog.d(GaiaConstants.TAG, "ancGetMap " + (m?.contentToString() ?: "null(fallback indexOf)") +
                " customSet=" + customSet)
        return m
    }

    fun getEffectiveAncMap(): IntArray = readAncMap()
    fun getConnectedDeviceName(): String? = connectedDeviceName
    fun getContext(): android.content.Context? = context

    fun setAncMode(mode: Int, cb: AncControlCallback?) {
        this.ancCallback = cb
        if (simConnected) { cb?.onAncModeResult(mode); return }
        val path = probe.ancPath
        val dev = GaiaCommands.ancDevFromUi(path, mode, readAncMap())
        if (dev < 0) {
            val reason = if (path == GaiaCommands.ANC_PATH_UNKNOWN)
                "ANC 能力尚未就绪或无 ANC 能力" else "该设备不支持此模式"
            Log.w(GaiaConstants.TAG, "setAncMode: mode " + mode + " not allowed on path " + path)
            cb?.onAncError(reason)
            return
        }
        Log.d(GaiaConstants.TAG, "setAncMode ui=" + mode + " path=" + path + " dev=" + dev)
        when (path) {
            GaiaCommands.ANC_PATH_ANC_V2 ->
                writeCommand(GaiaConstants.FEATURE_ANC_V2, GaiaConstants.CMD_SET_CURRENT_MODE, byteArrayOf(dev.toByte()))
            GaiaCommands.ANC_PATH_ANC_V1 ->
                writeCommand(GaiaConstants.FEATURE_ANC_V1, GaiaConstants.CMD_ANC1_SET_ANC_STATE, byteArrayOf(dev.toByte()))
            GaiaCommands.ANC_PATH_AUDIO_CURATION ->
                writeCommand(GaiaConstants.F_AUDIO_CURATION, GaiaConstants.CMD_AC_SET_MODE, byteArrayOf(dev.toByte()))
            else -> Log.w(GaiaConstants.TAG, "setAncMode: no write, path=" + path)
        }
    }

    /** alpha2.28: writeCommand delegates to sendGaia (single write path) */
    @Synchronized
    private fun writeCommand(feature: Int, command: Int, payload: ByteArray) {
        val packet = GaiaCommands.v3Packet(feature, command, payload)
        Log.d(GaiaConstants.TAG, "TX feature=0x" + Integer.toHexString(feature) +
                " cmd=0x" + Integer.toHexString(command) + " " + payload.contentToString())
        sendGaia(packet)
    }

    @Synchronized
    fun sendGaia(packet: ByteArray) {
        if (useRfcomm) {
            rfcommTransport?.send(packet)
            return
        }
        val g = gatt; val ch = cmdChar
        if (g == null || ch == null) { callback?.onError("GAIA 未连接"); return }
        gaiaWriteQueue.add(packet)
        if (!isGaiaWriting) {
            isGaiaWriting = true
            drainGaiaQueue()
        }
    }

    private fun drainGaiaQueue() {
        val packet = synchronized(this) { gaiaWriteQueue.poll() } ?: run {
            isGaiaWriting = false
            return
        }
        val g = gatt; val ch = cmdChar
        if (g == null || ch == null) {
            synchronized(this) { gaiaWriteQueue.clear() }
            isGaiaWriting = false
            return
        }
        try {
            ch.value = packet
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(GaiaConstants.TAG, "TX gaia " + packet.contentToString())
        } catch (e: SecurityException) { Log.e(GaiaConstants.TAG, "gaia write denied", e)
        } catch (e: Exception) { Log.e(GaiaConstants.TAG, "gaia write failed", e) }
        handler.postDelayed({ drainGaiaQueue() }, gaiaWriteDelayMs)
    }

    fun getSrcClient(): BleSourceSwitchClient? = srcClient
    fun hasSrcService(): Boolean = srcClient != null && srcClient!!.isPresent()

    fun activeProtocol(): String {
        if (useRfcomm) return "RFCOMM"
        val gaia = cmdChar != null
        val src = srcClient != null && srcClient!!.isPresent()
        return when {
            gaia && src -> "GAIA+9ECA"
            src -> "9ECA"
            gaia -> "GAIA"
            else -> "NONE"
        }
    }

    @Synchronized
    fun sendSrc(frame: ByteArray) {
        val g = gatt; val ch = srcCmdChar
        if (g == null || ch == null) { callback?.onError("设备不支持 BleSourceSwitch 服务"); return }
        try {
            ch.value = frame
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(GaiaConstants.TAG, "TX src " + frame.contentToString())
            AppLog.d(GaiaConstants.TAG, "SRC TX " + AppLog.hex(frame))
        } catch (e: SecurityException) { Log.e(GaiaConstants.TAG, "src write denied", e)
        } catch (e: Exception) { Log.e(GaiaConstants.TAG, "src write failed", e) }
    }

    /** alpha2.27: broadcast battery update to GMS process */
    private fun sendBatteryBroadcast(left: Int, right: Int) {
        val addr = deviceAddress
        val actualLeft = if (left >= 0) left else BatteryStore.getGaiaLeft(addr)
        val actualRight = if (right >= 0) right else BatteryStore.getGaiaRight(addr)
        try {
            val ctx = context ?: return
            val i = android.content.Intent(com.fxxkmoondrop.secret.hook.FastPairHookEntry.ACTION_BATTERY_UPDATE)
            i.putExtra("left", actualLeft)
            i.putExtra("right", actualRight)
            i.setPackage(com.fxxkmoondrop.secret.hook.FastPairHookEntry.PKG_GMS)
            ctx.sendBroadcast(i)
            Log.d(GaiaConstants.TAG, "battery broadcast -> GMS: l=" + actualLeft + " r=" + actualRight)
        } catch (_: Throwable) { }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(GaiaConstants.TAG, "gatt connected, discovering services")
                connectedDeviceName = g.device?.name
                if (connectedDeviceName.isNullOrEmpty()) {
                    connectedDeviceName = scanHits.firstOrNull {
                        it.device.address.equals(g.device?.address, true)
                    }?.scanRecord?.deviceName
                }
                // alpha2.29: fallback to scan-cached name (direct reconnect may have null g.device.name)
                if (connectedDeviceName.isNullOrEmpty()) {
                    connectedDeviceName = cachedLeName
                }
                Log.d(GaiaConstants.TAG, "connected name=" + connectedDeviceName +
                        " ancProfile=" + AncProfileLib.matchedProfileName(connectedDeviceName))
                refreshGattCache(g)
                AppLog.i(GaiaConstants.TAG, "GATT connected " + deviceAddress + " -> discovering services")
                gattPendingSince = 0
                if (deviceAddress != null) lastConnectedAddr = deviceAddress
                if (leAddrVerified) deviceAddress?.let {
                    saveLeAddrFile(it)
                    try {
                        context?.getSharedPreferences("cfg", 0)
                                ?.edit()?.putString("gaia_le_addr", it)?.commit()
                    } catch (_: Exception) { }
                }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(GaiaConstants.TAG, "gatt disconnected status=" + status)
                AppLog.w(GaiaConstants.TAG, "GATT disconnected status=" + status + " addr=" + deviceAddress)
                connected = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    attemptCount++
                    Log.d(GaiaConstants.TAG, "unexpected disconnect, attemptCount=" + attemptCount +
                            " candidates=" + (candidates?.size ?: 0) + " idx=" + candidateIdx)
                    if (candidates != null && candidateIdx < candidates!!.size - 1) {
                        advanceCandidate()
                        val target = candidates!![candidateIdx]
                        Log.d(GaiaConstants.TAG, "try next candidate: " + target)
                        connect(context!!, target)
                    } else if (attemptCount >= 2 && candidates != null) {
                        Log.w(GaiaConstants.TAG, "all candidates failed, start generic moondrop scan")
                        clearCachedLe()
                        candidates = null
                        candidateIdx = 0
                        attemptCount = 0
                        handler.removeCallbacks(scanTimeout)
                        handler.postDelayed({ startGenericScan() }, 500)
                    } else {
                        // alpha2.40.x: 单候选或无候选时，服务发现阶段断连(如 status=147) 不能静默卡住。
                        // 记录地址并延迟重连同一地址；首次失败后回退 TRANSPORT_AUTO（dual-mode TWS 更稳）。
                        val retryAddr = lastConnectedAddr ?: deviceAddress
                        if (retryAddr != null) {
                            if (!transportAutoTried) {
                                // alpha2.40.x: 首次失败 -> 回退 TRANSPORT_AUTO（dual-mode TWS 更稳）
                                Log.w(GaiaConstants.TAG, "solo/single-candidate disconnect(status=" + status +
                                        "), retry same addr=" + retryAddr + " -> TRANSPORT_AUTO")
                                transportAutoTried = true
                                handler.removeCallbacks(scanTimeout)
                                handler.postDelayed({
                                    if (!connected) connect(context!!, retryAddr)
                                }, 900)
                            } else if (!rfcommFallbackTried) {
                                // alpha2.42: LE + TRANSPORT_AUTO 仍失败 -> 主动尝试 RFCOMM/SPP 兜底（默认，所有设备）
                                rfcommFallbackTried = true
                                Log.w(GaiaConstants.TAG, "LE/AUTO still failing(status=" + status +
                                        "), try RFCOMM/SPP fallback (default) " + retryAddr)
                                AppLog.w(GaiaConstants.TAG, "protocol: LE/TRANSPORT_AUTO failed, trying RFCOMM/SPP fallback (default)")
                                try {
                                    val remote = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(retryAddr)
                                    if (remote != null) {
                                        tryRfcommFallback(remote)
                                    } else {
                                        handler.removeCallbacks(scanTimeout)
                                        handler.postDelayed({ if (!connected) connect(context!!, retryAddr) }, 900)
                                    }
                                } catch (e: Exception) {
                                    Log.e(GaiaConstants.TAG, "RFCOMM fallback fail: " + e)
                                    handler.removeCallbacks(scanTimeout)
                                    handler.postDelayed({ if (!connected) connect(context!!, retryAddr) }, 900)
                                }
                            } else {
                                // alpha2.42: RFCOMM 已试过仍失败 -> 本连接会话不再重试，等 detect 下轮 re-scan
                                Log.d(GaiaConstants.TAG, "RFCOMM fallback already tried, wait for next detect scan")
                            }
                        }
                    }
                }
                try { context?.let { HeadsetGate.clearConnectedMac(it) } }
                catch (e: Exception) { Log.d(GaiaConstants.TAG, "clearConnectedMac fail: " + e) }
                callback?.onDisconnected(deviceAddress ?: "")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(GaiaConstants.TAG, "service discovery failed status=" + status)
                callback?.onError("GAIA 服务发现失败")
                return
            }
            try {
                AppLog.i(GaiaConstants.TAG, "GATT services(" + (g.services?.size ?: 0) + "): " +
                        (g.services?.joinToString(" ") { it.uuid.toString() } ?: "?"))
                val service = g.getService(GaiaConstants.UUID_SERVICE)
                val hasGaia = service != null
                if (hasGaia) {
                    AppLog.i(GaiaConstants.TAG, "protocol: GAIA service present (QCC 系)")
                    transportAutoTried = false
                    if (deviceAddress != null) lastConnectedAddr = deviceAddress
                    cmdChar = service.getCharacteristic(GaiaConstants.UUID_COMMAND)
                    respChar = service.getCharacteristic(GaiaConstants.UUID_RESPONSE)
                    dataChar = service.getCharacteristic(GaiaConstants.UUID_DATA)
                    if (cmdChar == null) {
                        Log.w(GaiaConstants.TAG, "GAIA service 存在但缺 command 特征")
                    } else {
                        enableNotification(respChar)
                        enableNotification(dataChar)
                    }
                } else {
                    Log.d(GaiaConstants.TAG, "GAIA service not present（疑为蓝讯 9ECA 设备）")
                }
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
                        transportAutoTried = false
                        if (deviceAddress != null) lastConnectedAddr = deviceAddress
                        Log.d(GaiaConstants.TAG, "BleSourceSwitch(9ECA) service ready, srcClient bound")
                        AppLog.i(GaiaConstants.TAG, "protocol: 9ECA(9ECA0000) service present (蓝讯系) -> srcClient bound")
                        try { if (srcCapChar != null) g.readCharacteristic(srcCapChar) } catch (_: Exception) { }
                        try { if (srcInfoChar != null) g.readCharacteristic(srcInfoChar) } catch (_: Exception) { }
                    } else {
                        Log.d(GaiaConstants.TAG, "BleSourceSwitch service not present on this device")
                    }
                } catch (e: Exception) { Log.e(GaiaConstants.TAG, "src service lookup failed", e) }
                if (!hasGaia && !hasSrc9) {
                    Log.d(GaiaConstants.TAG, "no GATT services found, trying RFCOMM/SPP fallback")
                    AppLog.i(GaiaConstants.TAG, "protocol: no GATT services, trying RFCOMM/SPP fallback")
                    tryRfcommFallback(g.device)
                    return
                }
                connected = true
                probe.reset()
                val okAddr = deviceAddress
                if (okAddr != null) {
                    everConnected = true
                    if (leAddrVerified) {
                        cachedLeAddress = okAddr
                        try {
                            context?.getSharedPreferences("cfg", 0)
                                    ?.edit()?.putString("gaia_le_addr", okAddr)?.commit()
                        } catch (_: Exception) { }
                    }
                    cachedLeName = runCatching { g.getDevice()?.name }.getOrNull()
                    candidates = arrayOf(okAddr.uppercase())
                    candidateIdx = 0
                    attemptCount = 0
                    Log.d(GaiaConstants.TAG, "GAIA locked to " + okAddr)
                }
                Log.d(GaiaConstants.TAG, "GAIA ready")
                // alpha2.27: 能力探测委托给 CapabilityProbe
                probe.startProbes()
                callback?.onConnected(okAddr ?: "")
                // alpha2.26: 延迟发电池查询（等 descriptor 写完成）
                handler.postDelayed({
                    if (connected && gatt != null) fetchBatteryLevels()
                    if (connected && gatt != null) {
                        try { AncBridge.fetchAncMode() } catch (_: Exception) { }
                        try { AncBridge.sendAncStatus(probe.status()) } catch (_: Exception) { }
                        try { setDcBridge(DeviceControlBridge); DeviceControlBridge.applyProfile(AncProfileLib.resolveDc(connectedDeviceName)); DeviceControlBridge.fetchAll() } catch (_: Exception) { }
                    }
                }, 1200)
                // alpha2.36: probe may take up to 3.5s; re-fetch DC state after probe completes
                handler.postDelayed({
                    if (connected && gatt != null) {
                        try { DeviceControlBridge.fetchAll() } catch (_: Exception) { }
                    }
                }, 4000)
            } catch (e: Exception) {
                Log.e(GaiaConstants.TAG, "onServicesDiscovered error", e)
            }
        }

        private fun enableNotification(ch: BluetoothGattCharacteristic?) {
            if (ch == null) return
            try {
                gatt?.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(GaiaConstants.UUID_CCCD)
                if (d != null) {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt?.writeDescriptor(d)
                }
            } catch (e: SecurityException) { Log.e(GaiaConstants.TAG, "notify enable denied", e) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val value = ch.value
            if (value == null || value.size < 2) return
            if (ch === srcRespChar || ch === srcNotifyChar) {
                val sc = srcClient
                if (sc != null) sc.onCharacteristicChanged(ch, value) else packetHandler.handleSrcPacket(value)
            } else if (ch === srcCapChar || ch === srcInfoChar) {
                srcClient?.onCharacteristicRead(ch, BluetoothGatt.GATT_SUCCESS, value)
            } else {
                packetHandler.handlePacket(value)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val value = ch.value ?: return
            if (srcClient != null && (ch === srcCapChar || ch === srcInfoChar || ch === srcRespChar)) {
                srcClient?.onCharacteristicRead(ch, status, value)
                return
            }
            if (value.size >= 4) packetHandler.handlePacket(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            Log.d(GaiaConstants.TAG, "char write status=" + status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(GaiaConstants.TAG, "descriptor write status=" + status)
        }
    }
}
