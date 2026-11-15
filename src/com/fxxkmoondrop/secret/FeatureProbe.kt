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
               val labelEn: String, val experimental: Boolean = false,
               /** true = 不直接写 GAIA，而是走应用自己的读取通道（如 ANC 按 ancPath 选择）。 */
               val viaApp: Boolean = false)

    const val UNKNOWN = 0
    const val WAITING = 1
    const val OK = 2
    const val TIMEOUT = 3

    private const val GAP_MS = 2500L

    // 只放「已知安全」的只读命令：实测连发未知命令会让耳机侧断链。
    // 未确认语义的功能（指示灯 19、佩戴检测 9）不在这里探测 —— 需要时再单独验证。
    // 只放「已实测会正常回包」的项。
    // LHDC / LC3 的命令号（0x10 族 6 与 3）是推测值：实测发出去后耳机立即断链，
    // 说明设备不接受，暂不放入探测列表；等从官方抓包里确认后再补。
    private val items = listOf(
            Item(GaiaCommands.F_CODEC, GaiaCommands.CODEC_LDAC, "LDAC", "LDAC"),
            Item(GaiaCommands.F_CODEC, GaiaCommands.CODEC_LHDC, "LHDC", "LHDC"),
            Item(GaiaCommands.F_CODEC, GaiaCommands.CODEC_LC3, "LC3", "LC3"),
            Item(GaiaConstants.FEATURE_BATTERY, GaiaConstants.CMD_GET_BATTERY_LEVELS,
                    "电量", "Battery"),
            Item(GaiaCommands.F_ANC_V2, GaiaConstants.CMD_GET_CURRENT_MODE,
                    "降噪状态", "ANC state", viaApp = true))

    private val status = HashMap<Int, Int>()
    private var running = false
    private var index = 0

    /** 最近发出的请求：key = (feature shl 8) or command，value = 发送时刻。 */
    private val sentAt = HashMap<Int, Long>()
    private const val MATCH_WINDOW_MS = 3000L

    private fun keyOf(feature: Int, command: Int): Int = (feature shl 8) or (command and 0xFF)

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


    /**
     * 单条探测：只发这一条命令，等一个窗口后结算。
     *
     * 批量探测会被不支持的命令打断链路（实测 LHDC 那条发出去后 GAIA 立即断开），
     * 因此改成一条一条点：出问题只影响这一条，不会连累整轮。
     */
    @JvmStatic
    fun probeOne(item: Item) {
        if (running) return
        if (!GaiaBleClient.getInstance().isConnected()) {
            lastError = Lang.t("耳机未连接，先连上再探测", "Earbuds not connected")
            listener?.invoke()
            return
        }
        lastError = null
        running = true
        pending = item.feature
        status[item.feature] = WAITING
        listener?.invoke()
        sentAt[keyOf(item.feature, item.command)] = SystemClock.elapsedRealtime()
        runCatching {
            if (item.viaApp) {
                // 走应用既有通道：ANC 会按 ancPath 自动选正确 feature，不硬编码
                AncBridge.fetchAncMode()
            } else {
                GaiaBleClient.getInstance().writeRaw(item.feature, item.command, ByteArray(0))
            }
        }
        handler.postDelayed({
            if (status[item.feature] != OK) status[item.feature] = TIMEOUT
            if (pending == item.feature) pending = -1
            running = false
            listener?.invoke()
            AppLog.i("FeatureProbe", "single probe " + item.labelZh + " state=" + status[item.feature])
        }, GAP_MS)
    }

    private fun step() {
        val i = index
        if (i >= items.size) {
            running = false
            listener?.invoke()
            return
        }
        val it = items[i]
        if (!GaiaBleClient.getInstance().isConnected()) {
            lastError = Lang.t("链路已断开，探测已停止", "Link lost, probe stopped")
            running = false
            pending = -1
            listener?.invoke()
            return
        }
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
        val now = SystemClock.elapsedRealtime()
        // 精确匹配 (feature, command)：同一 feature 下的不同命令必须区分开，
        // 否则 codec 的三项会互相顶替（feature 16 的任意回包都被算成"支持"）。
        val k = keyOf(feature, command)
        val at = sentAt[k]
        val hit = at != null && now - at <= MATCH_WINDOW_MS
        // viaApp 项无法预知设备用哪个 command 回包：只看 feature 且限窗口
        val viaAppHit = feature == pending
        if (hit || viaAppHit) {
            status[feature] = OK
            listener?.invoke()
        }
    }
}
