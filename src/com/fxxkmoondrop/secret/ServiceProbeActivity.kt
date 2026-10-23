package com.fxxkmoondrop.secret

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * GATT 服务探测：把当前耳机的服务/特征 UUID 全列出来。
 *
 * 用途：判断某型号到底有没有 9ECA（均衡器私有协议）或其它厂商服务，
 * 从而决定均衡器入口该不该出现、以及该走哪条协议。
 */
class ServiceProbeActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var box: LinearLayout
    private var gatt: BluetoothGatt? = null
    /** 供「复制」用的纯文本输出 */
    private val dump = StringBuilder()
    private val ui = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeUtil.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.applyTheme(this)
        super.onCreate(savedInstanceState)
        pal = ThemeUtil.Palette(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val sbh = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        root.setPadding(dp(12), sbh + dp(8), dp(12), dp(16))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(M3Ui.backButton(this, pal) { finish() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        val t = TextView(this)
        t.text = Lang.t("GATT 服务探测", "GATT service probe")
        t.textSize = 28f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(pal.onSurface)
        val tlp = LinearLayout.LayoutParams(0, -2, 1f); tlp.marginStart = dp(8)
        header.addView(t, tlp)
        val copyBtn = TextView(this)
        copyBtn.text = Lang.t("复制", "Copy")
        copyBtn.textSize = 14f
        copyBtn.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        copyBtn.setTextColor(pal.primary)
        copyBtn.gravity = Gravity.CENTER
        copyBtn.setPadding(dp(16), dp(12), dp(16), dp(12))
        copyBtn.background = M3Ui.pressRipple(this, null)
        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("gatt-probe", dump.toString()))
            android.widget.Toast.makeText(this, Lang.t("已复制探测结果", "Probe output copied"),
                    android.widget.Toast.LENGTH_SHORT).show()
        }
        header.addView(copyBtn, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        val sv = ScrollView(this)
        sv.addView(M3Ui.groupCard(this, pal, box), LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        M3Ui.fitSystemBars(this, pal)

        start()
    }

    private fun start() {
        val addr = intent.getStringExtra("addr")
            ?: GaiaBleClient.getInstance().deviceAddress
        if (addr.isNullOrEmpty()) { line("E", Lang.t("没有可用的设备地址", "No device address")); return }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            line("E", Lang.t("缺少 BLUETOOTH_CONNECT 权限", "Missing BLUETOOTH_CONNECT")); return
        }
        val mgr = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val ad = mgr?.adapter
        if (ad == null || !ad.isEnabled) { line("E", Lang.t("蓝牙未开启", "Bluetooth off")); return }
        line("I", Lang.t("连接 ", "Connecting ") + addr)
        val dev = try { ad.getRemoteDevice(addr) } catch (t: Throwable) { line("E", "" + t.message); return }
        gatt = if (Build.VERSION.SDK_INT >= 23)
            dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(this, false, cb)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            ui.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    line("I", Lang.t("已连接，开始服务发现", "Connected, discovering"))
                    g.discoverServices()
                } else {
                    line("W", Lang.t("连接断开", "Disconnected") + " (" + status + ")")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ui.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    line("E", Lang.t("服务发现失败", "Discovery failed") + " status=" + status); return@post
                }
                line("I", Lang.t("服务发现完成，共 ", "Discovered ") + g.services.size +
                        Lang.t(" 个服务", " services"))
                for (s in g.services) {
                    val known = when {
                        s.uuid.toString().startsWith("00001100-d102-11e1-9b23-00025b00a5a5", true) -> "GAIA"
                        s.uuid.toString().startsWith("00009eca", true) ||
                                s.uuid.toString().startsWith("9eca", true) -> "9ECA (Moondrop EQ)"
                        s.uuid.toString().startsWith("0000180f", true) -> "Battery"
                        s.uuid.toString().startsWith("0000180a", true) -> "Device Info"
                        s.uuid.toString().startsWith("00001800", true) -> "Generic Access"
                        s.uuid.toString().startsWith("00001801", true) -> "Generic Attribute"
                        s.uuid.toString().startsWith("0000180d", true) -> "Heart Rate"
                        s.uuid.toString().startsWith("0000fe", true) -> "Vendor 16-bit 0xFE.."
                        else -> ""
                    }
                    line(if (known.isNotEmpty()) "OK" else "I",
                            s.uuid.toString() + (if (known.isEmpty()) "" else "   ← " + known))
                    // 展开特征：UUID + 读写/通知属性（判断这条服务能不能下发 EQ 报文）
                    for (ch in s.characteristics) {
                        val props = buildString {
                            if (ch.properties and 0x02 != 0) append("R ")
                            if (ch.properties and 0x08 != 0) append("W ")
                            if (ch.properties and 0x04 != 0) append("WNR ")
                            if (ch.properties and 0x10 != 0) append("N ")
                            if (ch.properties and 0x20 != 0) append("I ")
                        }.trim()
                        line("I", "    └ " + ch.uuid.toString() + "   [" + props + "]")
                    }
                }
                try { g.disconnect() } catch (_: Throwable) { }
            }
        }
    }

    private fun line(level: String, msg: String) {
        val color = when (level) {
            "OK" -> pal.primary
            "E" -> pal.red
            "W" -> pal.warning
            else -> pal.onSurface
        }
        val tv = TextView(this)
        tv.text = "[" + level + "] " + msg
        tv.textSize = 12f
        tv.typeface = Typeface.MONOSPACE
        tv.setTextColor(color)
        tv.setPadding(0, dp(3), 0, dp(3))
        box.addView(tv, LinearLayout.LayoutParams(-1, -2))
        dump.append(tv.text).append('\n')
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { gatt?.close() } catch (_: Throwable) { }
        super.onDestroy()
    }
}
