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
    private var ancCallback: AncControlCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    // alpha2.27: 能力探测委托给独立状态机
    private val probe = CapabilityProbe(
        handler,
        { connected },
        { gatt != null },
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
        { left, right -> sendBatteryBroadcast(left, right) }
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
                    if (lower.contains("moondrop") && dn.lowercase().contains("moondrop")) {
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

    private fun transportFor(d: BluetoothDevice): Int = BluetoothDevice.TRANSPORT_LE

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
                            AppLog.i(GaiaConstants.TAG, "env: fastpair hook alive -> GMS bridge scan")
                            requestRemoteScan("boot-no-cache")
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
    fun isConnected(): Boolean = simConnected || (connected && gatt != null)

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
    private fun startScanForLe(target: BluetoothDevice): Boolean {
        try {
            if (scanning) return true
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return false
            leScanner = adapter.bluetoothLeScanner
            if (leScanner == null) return false
            val targetName = target.name
            val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (scanning && result != null && result.device != null) {
                        var n = result.scanRecord?.deviceName
                        if (n == null) n = result.device.name
                        val addr = result.device.address
                        Log.d(GaiaConstants.TAG, "scan result: " + addr + " name=" + n)
                        AppLog.d("Scan", "hit " + addr + " name=" + n + " rssi=" + result.rssi)
                        var match = false
                        if (n != null && targetName != null) {
                            match = n == targetName ||
                                    (n.lowercase().contains("moondrop") &&
                                            targetName.lowercase().contains("moondrop"))
                        }
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
                                    Log.d(GaiaConstants.TAG, "LE address cached: " + addr)
                                }
                            } catch (_: Exception) { }
                            doConnectLe(result)
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
            handler.postDelayed(scanTimeout, 25000)
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
                    Log.d(GaiaConstants.TAG, "radio: " + addr + " name=" + (n ?: "<null>") + " rssi=" + result.rssi)
                    var hit = false
                    if (!n.isNullOrEmpty() && DeviceMatcher.isMoondrop(n)) {
                        hit = true
                    } else if (n.isNullOrEmpty()) {
                        if (addr.length >= 14 && prefixMatchesBonded(addr)) hit = true
                    }
                    if (!hit) return
                    Log.d(GaiaConstants.TAG, "generic scan hit: " + addr + " name=" + n + " rssi=" + result.rssi)
                    scanHits.add(result)
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.w(GaiaConstants.TAG, "generic scan failed code=" + errorCode)
                    stopScan()
                    retryConnect()
                }
            }
            scanning = true
            leScanner!!.startScan(null, settings, scanCallback)
            handler.postDelayed({
                if (!scanning) return@postDelayed
                stopScan()
                Log.d(GaiaConstants.TAG, "generic scan done, hits=" + scanHits.size)
                connectFromScanHits()
            }, GaiaConstants.SCAN_DURATION_MS)
            Log.d(GaiaConstants.TAG, "generic moondrop scan started")
        } catch (e: Exception) {
            Log.e(GaiaConstants.TAG, "generic scan start failed", e)
            retryConnect()
        }
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
        }, 60000)
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
        callback?.onDisconnected(addr ?: "")
    }

    private fun disconnectInternal() {
        gatt?.let {
            try { it.disconnect(); it.close() } catch (_: Exception) { }
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
        probe.reset()
    }

    fun fetchBatteryLevels() {
        if (simConnected) return
        val payload = byteArrayOf(GaiaConstants.BATTERY_LEFT.toByte(), GaiaConstants.BATTERY_RIGHT.toByte())
        writeCommand(GaiaConstants.FEATURE_BATTERY, GaiaConstants.CMD_GET_BATTERY_LEVELS, payload)
    }

    fun fetchAncMode(cb: AncControlCallback?) {
        this.ancCallback = cb
        if (simConnected) {
            val m = AncBridge.getCurrentMode()
            cb?.onAncModeResult(if (m >= 0) m else 1)
            return
        }
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
        val m = AncProfileLib.resolveGetMap(connectedDeviceName, customSet)
        AppLog.d(GaiaConstants.TAG, "ancGetMap " + (m?.contentToString() ?: "null(fallback indexOf)") +
                " customSet=" + customSet)
        return m
    }

    fun getEffectiveAncMap(): IntArray = readAncMap()
    fun getConnectedDeviceName(): String? = connectedDeviceName

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

    @Synchronized
    private fun writeCommand(feature: Int, command: Int, payload: ByteArray) {
        val g = gatt
        val ch = cmdChar
        if (g == null || ch == null) { callback?.onError("GAIA 未连接"); return }
        try {
            val packet = GaiaCommands.v3Packet(feature, command, payload)
            ch.value = packet
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(GaiaConstants.TAG, "TX feature=0x" + Integer.toHexString(feature) +
                    " cmd=0x" + Integer.toHexString(command) + " " + payload.contentToString())
        } catch (e: SecurityException) { Log.e(GaiaConstants.TAG, "write permission denied", e)
        } catch (e: Exception) { Log.e(GaiaConstants.TAG, "write failed", e) }
    }

    @Synchronized
    fun sendGaia(packet: ByteArray) {
        val g = gatt; val ch = cmdChar
        if (g == null || ch == null) { callback?.onError("GAIA 未连接"); return }
        try {
            ch.value = packet
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(ch)
            Log.d(GaiaConstants.TAG, "TX gaia " + packet.contentToString())
        } catch (e: SecurityException) { Log.e(GaiaConstants.TAG, "gaia write denied", e)
        } catch (e: Exception) { Log.e(GaiaConstants.TAG, "gaia write failed", e) }
    }

    fun getSrcClient(): BleSourceSwitchClient? = srcClient
    fun hasSrcService(): Boolean = srcClient != null && srcClient!!.isPresent()

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
        try {
            val ctx = context ?: return
            val i = android.content.Intent(com.fxxkmoondrop.secret.hook.FastPairHookEntry.ACTION_BATTERY_UPDATE)
            i.putExtra("left", left)
            i.putExtra("right", right)
            i.setPackage(com.fxxkmoondrop.secret.hook.FastPairHookEntry.PKG_GMS)
            ctx.sendBroadcast(i)
            Log.d(GaiaConstants.TAG, "battery broadcast -> GMS: l=" + left + " r=" + right)
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
                Log.d(GaiaConstants.TAG, "connected name=" + connectedDeviceName +
                        " ancProfile=" + AncProfileLib.matchedProfileName(connectedDeviceName))
                refreshGattCache(g)
                AppLog.i(GaiaConstants.TAG, "GATT connected " + deviceAddress + " -> discovering services")
                gattPendingSince = 0
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
                        Log.d(GaiaConstants.TAG, "BleSourceSwitch(9ECA) service ready, srcClient bound")
                        AppLog.i(GaiaConstants.TAG, "protocol: 9ECA(9ECA0000) service present (蓝讯系) -> srcClient bound")
                        try { if (srcCapChar != null) g.readCharacteristic(srcCapChar) } catch (_: Exception) { }
                        try { if (srcInfoChar != null) g.readCharacteristic(srcInfoChar) } catch (_: Exception) { }
                    } else {
                        Log.d(GaiaConstants.TAG, "BleSourceSwitch service not present on this device")
                    }
                } catch (e: Exception) { Log.e(GaiaConstants.TAG, "src service lookup failed", e) }
                if (!hasGaia && !hasSrc9) {
                    Log.e(GaiaConstants.TAG, "neither GAIA nor 9ECA service found")
                    AppLog.e(GaiaConstants.TAG, "protocol: neither GAIA nor 9ECA service found - unsupported device")
                    callback?.onError("未找到 GAIA / 9ECA 服务（暂不支持该设备）")
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
                    }
                }, 1200)
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

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(GaiaConstants.TAG, "descriptor write status=" + status)
        }
    }
}
