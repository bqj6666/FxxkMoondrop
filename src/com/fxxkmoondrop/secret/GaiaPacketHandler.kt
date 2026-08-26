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
    private val batteryBroadcastSender: (Int, Int) -> Unit,
    private val deviceControlCallbackProvider: () -> GaiaBleClient.DeviceControlCallback?
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
            } else if (feature == GaiaConstants.FEATURE_DAC_GAIN &&
                    (command == GaiaConstants.CMD_DAC_GET_GAIN || command == GaiaConstants.CMD_DAC_SET_GAIN)) {
                if (payload.isNotEmpty()) {
                    val level = payload[0].toInt() and 0xFF
                    Log.d(TAG, "DAC gain RX level=" + level)
                    AppLog.d(TAG, "dacGain=" + level)
                    deviceControlCallbackProvider()?.let { cb ->
                        handler.post { cb.onGainResult(level) }
                    }
                }
            } else if (feature == GaiaConstants.FEATURE_LED &&
                    (command == GaiaConstants.CMD_LED_GET_STATE || command == GaiaConstants.CMD_LED_SET_STATE)) {
                if (payload.isNotEmpty()) {
                    val state = payload[0].toInt() and 0xFF
                    Log.d(TAG, "LED RX state=" + state)
                    AppLog.d(TAG, "ledState=" + state)
                    deviceControlCallbackProvider()?.let { cb ->
                        handler.post { cb.onLedResult(state) }
                    }
                }
            } else if (feature == GaiaConstants.FEATURE_SPATIAL_AUDIO) {
                when (command) {
                    GaiaConstants.CMD_SPATIAL_GET_STATE, GaiaConstants.CMD_SPATIAL_SET_STATE -> {
                        if (payload.isNotEmpty()) {
                            val state = payload[0].toInt() and 0xFF
                            Log.d(TAG, "spatial RX state=" + state)
                            AppLog.d(TAG, "spatialState=" + state)
                            deviceControlCallbackProvider()?.let { cb ->
                                handler.post { cb.onSpatialResult(state) }
                            }
                        }
                    }
                    GaiaConstants.CMD_SPATIAL_GET_HEAD_TRACKING, GaiaConstants.CMD_SPATIAL_SET_HEAD_TRACKING -> {
                        if (payload.isNotEmpty()) {
                            val state = payload[0].toInt() and 0xFF
                            Log.d(TAG, "head tracking RX state=" + state)
                            AppLog.d(TAG, "headTracking=" + state)
                            deviceControlCallbackProvider()?.let { cb ->
                                handler.post { cb.onHeadTrackingResult(state) }
                            }
                        }
                    }
                }
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
        var leftLevel = -1
        var rightLevel = -1
        var i = 0
        while (i + 1 < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val level = payload[i + 1].toInt() and 0xFF
            Log.d(TAG, "battery id=" + id + " level=" + level)
            when (id) {
                1 -> leftLevel = level
                2 -> rightLevel = level
            }
            callbackProvider()?.let { cb ->
                handler.post { cb.onBatteryLevel(id, level) }
            }
            i += 2
        }
        // alpha2.31: 缺失组件保留旧值，避免电量显示跳动
        try {
            batteryBroadcastSender(leftLevel, rightLevel)
        } catch (_: Throwable) { }
    }

    private fun parseAncMode(payload: ByteArray) {
        if (payload.isEmpty()) return
        val dev = payload[0].toInt() and 0xFF
        val path = if (probe.ancPath == GaiaCommands.ANC_PATH_UNKNOWN)
            GaiaCommands.ANC_PATH_AUDIO_CURATION else probe.ancPath
        val setMap = ancMapProvider()
        val getMap = ancGetMapProvider()
        val mode = GaiaCommands.ancUiFromDev(path, dev, setMap, getMap)
        Log.d(TAG, "anc mode path=" + probe.ancPath + " dev=" + dev + " ui=" + mode +
                " setMap=" + setMap.contentToString() +
                " getMap=" + (getMap?.contentToString() ?: "null"))
        AppLog.d(TAG, "ancModeParse path=" + probe.ancPath + " dev=" + dev + " ui=" + mode)
        if (mode >= 0) {
            ancCallbackProvider()?.let { cb ->
                handler.post { cb.onAncModeResult(mode) }
            }
        }
    }
}
