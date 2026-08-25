package com.fxxkmoondrop.secret

import android.os.Handler
import android.util.Log

/**
 * GAIA V3 数据包解析器（alpha2.27 从 GaiaBleClient 提取）。
 *
 * 职责：
 * - 解析 GAIA V3 响应包，分发到 BatteryStore / AncBridge / AncControlCallback
 * - 解析 Moondrop 私有 9ECA 响应帧（handleSrcPacket）
 * - 解析 ANC 模式回包（parseAncMode）和电量回包（parseBatteryLevels）
 *
 * 不持有连接状态，纯解析+回调分发。
 *
 * @param handler 主线程 Handler（回调投递）
 * @param callbackProvider GAIA 回调（连接/断开/电量/ANC模式/错误）
 * @param ancCallbackProvider ANC 控制回调（模式结果/错误）
 * @param probe 能力探测状态机（读取 ancPath）
 * @param ancMapProvider SET 方向映射 provider
 * @param ancGetMapProvider GET 方向映射 provider（nullable）
 */
class GaiaPacketHandler(
    private val handler: Handler,
    private val callbackProvider: () -> GaiaBleClient.Callback?,
    private val ancCallbackProvider: () -> GaiaBleClient.AncControlCallback?,
    private val probe: CapabilityProbe,
    private val ancMapProvider: () -> IntArray,
    private val ancGetMapProvider: () -> IntArray?,
    private val batteryBroadcastSender: (Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "GaiaPacketHandler"
    }

    /** 解析 GAIA V3 数据包（由 onCharacteristicChanged / onCharacteristicRead 调用） */
    fun handlePacket(value: ByteArray) {
        try {
            Log.d(TAG, "RX raw " + AppLog.hex(value))
            AppLog.d(TAG, "GAIA RX " + AppLog.hex(value))
            val vendor = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            val cmdValue = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
            if (vendor != GaiaConstants.GAIA_VENDOR) {
                Log.w(TAG, "RX non-gaia vendor=0x" + Integer.toHexString(vendor) +
                        " raw=" + AppLog.hex(value))
                return
            }
            val feature = (cmdValue shr 9) and 0x7F
            val type = (cmdValue shr 7) and 0x03
            val command = cmdValue and 0x7F
            val payload = if (value.size > 4) value.copyOfRange(4, value.size) else ByteArray(0)
            Log.d(TAG, "RX feature=0x" + Integer.toHexString(feature) +
                    " type=" + type + " cmd=0x" + Integer.toHexString(command) +
                    " " + payload.contentToString())

            if (feature == GaiaConstants.FEATURE_BATTERY && command == GaiaConstants.CMD_GET_BATTERY_LEVELS) {
                parseBatteryLevels(payload)
            } else if (feature == GaiaConstants.FEATURE_BASIC && command == GaiaConstants.CMD_GET_SUPPORTED_FEATURES) {
                probe.handleFeatureResponse(payload)
            } else if (feature == GaiaConstants.FEATURE_BASIC &&
                    (command == GaiaConstants.CMD_GET_VARIANT || command == GaiaConstants.CMD_GET_APP_VERSION)) {
                // 设备型号/版本（仅记录日志，不驱动状态）
                try {
                    val v = String(payload, Charsets.UTF_8).trim { it <= ' ' }
                    if (v.isNotEmpty()) Log.d(TAG, "device info: $v")
                } catch (_: Exception) { }
            } else if ((feature == GaiaConstants.FEATURE_ANC_V2 || feature == GaiaConstants.F_AUDIO_CURATION) &&
                    (command == GaiaConstants.CMD_GET_CURRENT_MODE || command == GaiaConstants.CMD_SET_CURRENT_MODE ||
                            command == GaiaConstants.CMD_AC_GET_SWITCH_CONF || command == GaiaConstants.CMD_AC_SET_SWITCH_CONF ||
                            command == 0x01)) {
                // 0x01 = notification MODE_CHANGE
                probe.handleAncProbeResponse(feature, command)

                // cmd=41/42 返回的是 settledActions 字节流，与 cmd=3 的 Mode 单字节语义不同。
                // 仅记录原始字节做证据，不调用 parseAncMode（避免用 V1 映射误判降噪状态）。
                if (command == GaiaConstants.CMD_AC_GET_SWITCH_CONF || command == GaiaConstants.CMD_AC_SET_SWITCH_CONF) {
                    Log.d(TAG, "ANC switch conf RX cmd=0x" + Integer.toHexString(command) +
                            " payload=" + payload.contentToString() + " hex=" + AppLog.hex(payload))
                } else {
                    parseAncMode(payload)
                }
            } else if (feature == GaiaConstants.FEATURE_ANC_V1 &&
                    (command == GaiaConstants.CMD_ANC1_GET_ANC_STATE || command == GaiaConstants.CMD_ANC1_SET_ANC_STATE)) {
                parseAncMode(payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handlePacket error", e)
        }
    }

    /** 解析 Moondrop 私有协议响应帧：[0xA5][0x01][frameType][msgId][seq][len][payload] */
    fun handleSrcPacket(value: ByteArray) {
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

    private fun parseBatteryLevels(payload: ByteArray) {
        if (payload.size < 2) return
        var i = 0
        while (i + 1 < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val level = payload[i + 1].toInt() and 0xFF
            Log.d(TAG, "battery id=" + id + " level=" + level)
            callbackProvider()?.let { cb ->
                handler.post { cb.onBatteryLevel(id, level) }
            }
            // alpha2.27: 同步刷新 GMS 弹窗电量（左右耳分离）
            try {
                batteryBroadcastSender(if (id == 1) level else -1, if (id == 2) level else -1)
            } catch (_: Throwable) { }
            i += 2
        }
    }

    private fun parseAncMode(payload: ByteArray) {
        if (payload.isEmpty()) return
        val dev = payload[0].toInt() and 0xFF
        val path = if (probe.ancPath == GaiaCommands.ANC_PATH_UNKNOWN)
            GaiaCommands.ANC_PATH_AUDIO_CURATION else probe.ancPath
        val mode = GaiaCommands.ancUiFromDev(path, dev, ancMapProvider(), ancGetMapProvider())
        Log.d(TAG, "anc mode path=" + probe.ancPath + " dev=" + dev + " ui=" + mode)
        AppLog.d(TAG, "ancModeParse path=" + probe.ancPath + " dev=" + dev + " ui=" + mode)
        if (mode >= 0) {
            ancCallbackProvider()?.let { cb ->
                handler.post { cb.onAncModeResult(mode) }
            }
        }
    }
}
