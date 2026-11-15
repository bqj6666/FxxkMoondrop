package com.fxxkmoondrop.secret

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 能力探测（调试模式手动触发）。
 *
 * 目的：给新耳机测绘「哪些功能真的会回包」，结果只用于展示与人工判断，
 * 不参与连接流程、不影响界面显示逻辑。
 *
 * 设计约束：
 * - 只发只读命令（读状态），不发任何设置命令
 * - 一条一条发，间隔 [GAP_MS]，避免瞬间灌帧
 * - 用户在调试页点击才启动；不自动运行
 */
object FeatureProbe {

    /** 单条探测项。[experimental] 表示命令号尚未被实测确认。 */
    class Item(val feature: Int, val command: Int, val labelZh: String,
               val labelEn: String, val experimental: Boolean = false)

    const val UNKNOWN = 0
    const val WAITING = 1
    const val OK = 2
    const val TIMEOUT = 3

    private const val GAP_MS = 1200L

    private val items = listOf(
            Item(GaiaCommands.F_CODEC, GaiaCommands.CODEC_LDAC, "LDAC", "LDAC"),
            Item(GaiaCommands.F_CODEC, GaiaCommands.CODEC_LHDC, "LHDC", "LHDC"),
            Item(GaiaCommands.F_CODEC, GaiaCommands.CODEC_LC3, "LC3", "LC3"),
            Item(GaiaConstants.FEATURE_BATTERY, GaiaConstants.CMD_GET_BATTERY_LEVELS,
                    "电量", "Battery"),
            Item(GaiaCommands.F_ANC_V2, GaiaConstants.CMD_GET_CURRENT_MODE,
                    "降噪 V2 状态", "ANC V2 state"),
            Item(19, 0x02, "指示灯", "LED", experimental = true),
            Item(9, 0x02, "佩戴检测", "In-ear detect", experimental = true))

    private val status = HashMap<Int, Int>()
    private var running = false
    private var index = 0

    /** 当前正在等待回包的 feature（-1 = 无）。按 feature 匹配而不是按命令号/下标，
     *  因为设备回包的命令号未必等于请求命令号，且回包可能晚于窗口。 */
    private var pending = -1
    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次启动失败的原因（null = 正常）。 */
    var lastError: String? = null

    /** 状态变化回调（UI 用来刷新）。 */
    var listener: (() -> Unit)? = null

    @JvmStatic
    fun list(): List<Item> = items

    @JvmStatic
    fun stateOf(feature: Int): Int = status[feature] ?: UNKNOWN

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun label(item: Item): String = Lang.t(item.labelZh, item.labelEn)

    /** 开始一轮探测。已在运行时忽略。 */
    @JvmStatic
    fun start() {
        if (running) return
        // 没有活动链路时不要盲发：直接给出提示，避免"全部无响应"的误判
        if (!GaiaBleClient.getInstance().isConnected()) {
            lastError = Lang.t("耳机未连接，先连上再探测", "Earbuds not connected")
            listener?.invoke()
            return
        }
        lastError = null
        running = true
        index = 0
        status.clear()
        listener?.invoke()
        step()
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        listener?.invoke()
    }

    private fun step() {
        val i = index
        if (i >= items.size) {
            running = false
            listener?.invoke()
            return
        }
        val it = items[i]
        pending = it.feature
        status[it.feature] = WAITING
        listener?.invoke()
        runCatching {
            GaiaBleClient.getInstance().writeRaw(it.feature, it.command, ByteArray(0))
        }
        val startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed({
            // 本轮窗口内没等到回包 → 记为超时
            if (status[it.feature] != OK) status[it.feature] = TIMEOUT
            if (pending == it.feature) pending = -1
            index++
            listener?.invoke()
            if (running) {
                handler.postDelayed({ if (running) step() }, 0L)
            }
            AppLog.i("FeatureProbe", "probe " + it.labelZh + " waited=" +
                    (SystemClock.elapsedRealtime() - startedAt) + "ms state=" + status[it.feature])
        }, GAP_MS)
    }

    /** 收到任意 GAIA 回包时调用；命中当前等待项则记为支持。 */
    @JvmStatic
    fun onPacket(feature: Int, command: Int) {
        if (!running) return
        if (feature == pending) {
            status[feature] = OK
            listener?.invoke()
        }
    }
}
