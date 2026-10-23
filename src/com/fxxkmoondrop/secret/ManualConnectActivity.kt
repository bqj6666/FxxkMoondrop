package com.fxxkmoondrop.secret

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 手动连接设备（二级页面）。
 *
 * 为什么单独一页：连接是个**有过程**的操作（发起 → BLE 链路 → GAIA 服务发现），
 * 弹窗里只能给一句 toast，失败了也不知道断在哪一步。这里把每一步都摆出来。
 */
class ManualConnectActivity : Activity() {

    private lateinit var pal: ThemeUtil.Palette
    private lateinit var statusBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    private var targetName: String = ""
    private var targetAddr: String = ""
    private var startedAt = 0L
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = ThemeUtil.Palette(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pal.surface)
        val statusBarH = resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        root.setPadding(dp(16), statusBarH + dp(8), dp(16), dp(24))

        // 顶栏：返回 + 标题（M3：48dp 触摸区 + 22sp 标题）
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(M3Ui.arrowBack(this, pal) { finish() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(this)
        title.text = Lang.t("手动连接设备", "Connect a device")
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setTextColor(pal.onSurface)
        val tlp = LinearLayout.LayoutParams(0, -2, 1f)
        tlp.marginStart = dp(8)
        header.addView(title, tlp)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // 状态区（连接过程中显示）
        statusBox = LinearLayout(this)
        statusBox.orientation = LinearLayout.VERTICAL
        statusBox.visibility = View.GONE
        root.addView(statusBox, LinearLayout.LayoutParams(-1, -2))

        // 设备列表
        root.addView(M3Ui.sectionTitle(this, pal, Lang.t("已配对设备", "Bonded devices")),
                LinearLayout.LayoutParams(-1, -2))
        val rows = ArrayList<View>()
        val bonded = try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        } catch (_: Throwable) { emptyList() }
        if (bonded.isEmpty()) {
            rows.add(M3Ui.listRow(this, pal, R.drawable.ic_bluetooth,
                    Lang.t("没有已配对的设备", "No bonded devices"),
                    Lang.t("请先在系统设置里配对耳机", "Pair the earbuds in system settings first"),
                    null, null))
        } else {
            for (d in bonded) {
                val n = try { d.name } catch (_: Throwable) { null } ?: "?"
                val a = try { d.address } catch (_: Throwable) { null } ?: "?"
                rows.add(M3Ui.listRow(this, pal, R.drawable.ic_bluetooth, n, a,
                        M3Ui.chevron(this, pal.onVariant)) {
                    startActivity(Intent(this, ConnectLogActivity::class.java)
                            .putExtra(ConnectLogActivity.EXTRA_NAME, n)
                            .putExtra(ConnectLogActivity.EXTRA_ADDR, a))
                })
            }
        }
        root.addView(M3Ui.groupCard(this, pal, *rows.toTypedArray()),
                LinearLayout.LayoutParams(-1, -2))

        val sv = android.widget.ScrollView(this)
        sv.addView(root)
        setContentView(sv)
        M3Ui.fitSystemBars(this, pal)
    }

    private fun startConnect(name: String, addr: String) {
        targetName = name
        targetAddr = addr
        startedAt = System.currentTimeMillis()
        finished = false

        statusBox.removeAllViews()
        statusBox.visibility = View.VISIBLE
        statusBox.addView(M3Ui.sectionTitle(this, pal, Lang.t("连接过程", "Progress")),
                LinearLayout.LayoutParams(-1, -2))
        step(Lang.t("正在连接 ", "Connecting ") + name + " …", pal.primary, spinner = true)
        step(Lang.t("地址：", "Address: ") + addr, pal.onVariant)

        try {
            startService(Intent(this, HeadsetDetectService::class.java)
                    .setAction(HeadsetDetectService.ACTION_MANUAL_CONTROL))
        } catch (_: Throwable) { }
        val started = try { GaiaBleClient.getInstance().connect(this, addr) } catch (_: Throwable) { false }
        if (!started) {
            fail(Lang.t("无法发起连接：蓝牙未开启 / 缺少权限 / 设备不在范围内",
                    "Could not start: Bluetooth off, missing permission, or device out of range"))
            return
        }
        poll()
    }

    /** 每 500ms 看一次真实状态，把过程逐步写出来 */
    private fun poll() {
        if (finished) return
        val g = GaiaBleClient.getInstance()
        val connected = try { g.isConnected() } catch (_: Throwable) { false }
        val ready = try { g.isGaiaReady() } catch (_: Throwable) { false }
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000

        statusBox.removeAllViews()
        statusBox.addView(M3Ui.sectionTitle(this, pal, Lang.t("连接过程", "Progress")),
                LinearLayout.LayoutParams(-1, -2))
        step(Lang.t("发起连接请求", "Connection requested") + "  " + targetAddr, pal.onVariant, ok = true)
        step(Lang.t("建立 BLE 链路", "Establish BLE link"), if (connected) pal.primary else pal.onVariant,
                ok = connected, pending = !connected)
        step(Lang.t("GAIA 服务发现", "GAIA service discovery"),
                if (ready) pal.primary else pal.onVariant, ok = ready, pending = connected && !ready)

        if (ready) {
            finishOk(Lang.t("已连接：", "Connected: ") + targetName)
            return
        }
        if (elapsed >= 20) {
            fail(if (connected)
                Lang.t("链路已建立，但 GAIA 未就绪：可能不是水月雨设备，或固件未开放 GAIA 服务",
                        "Link established but GAIA not ready: not a Moondrop device, or GAIA is disabled in firmware")
            else
                Lang.t("超时未建立 BLE 链路：设备可能已连接其它主机、处于充电仓，或距离过远",
                        "Timed out before the BLE link: device may be connected elsewhere, in the case, or too far"))
            return
        }
        ui.postDelayed({ poll() }, 500)
    }

    private fun finishOk(msg: String) {
        finished = true
        step(msg, pal.primary, ok = true)
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun fail(reason: String) {
        finished = true
        step(Lang.t("失败：", "Failed: ") + reason, pal.red, fail = true)
    }

    /** 一行步骤：圆点 + 文本（简单、稳定，不依赖 Material 组件在 Activity 外表现） */
    private fun step(text: String, color: Int, ok: Boolean = false, fail: Boolean = false,
                     pending: Boolean = false, spinner: Boolean = false) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val mark = TextView(this)
        mark.text = when {
            ok -> "✓"
            fail -> "✗"
            pending -> "…"
            else -> "•"
        }
        mark.textSize = 16f
        mark.setTextColor(color)
        row.addView(mark, LinearLayout.LayoutParams(dp(24), -2))
        if (spinner) {
            val ind = com.google.android.material.loadingindicator.LoadingIndicator(this)
            ind.indicatorSize = dp(18)
            ind.setIndicatorColor(color)
            row.addView(ind, LinearLayout.LayoutParams(dp(18), dp(18)))
            row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        }
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(if (ok || fail) color else pal.onSurface)
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        statusBox.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        finished = true
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
