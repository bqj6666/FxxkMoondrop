package com.fxxkmoondrop.secret

import android.os.Handler
import android.util.Log

/**
 * ANC 能力探测状态机（alpha2.27 从 GaiaBleClient 提取）。
 *
 * 每次成功建立 GATT 会话后，发送阶梯探测包确定 ANC 协议路径：
 * 1. GET_SUPPORTED_FEATURES → 能力位图 → ancPath（主路径）
 * 2. AudioCuration / ANC_V2 各发 GET_MODE + GET_SWITCH_CONF（备用探测，靠回包确认路径）
 * 3. 超时自愈：阶梯重发一次
 *
 * 状态流转：UNKNOWN → (探测中) → ANC_V2 / ANC_V1 / AUDIO_CURATION / UNKNOWN(截断/无ANC)
 */
class CapabilityProbe(
    private val handler: Handler,
    private val isConnected: () -> Boolean,
    private val isGattReady: () -> Boolean,
    private val writeCommand: (Int, Int, ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "CapabilityProbe"
        private const val PROBE_DELAY_BASE = 400L
        private const val PROBE_DELAY_STEP = 250L
        private const val PROBE_TIMEOUT_MS = 3500L
    }

    @Volatile var ancPath: Int = GaiaCommands.ANC_PATH_UNKNOWN
        private set

    @Volatile var probeDone: Boolean = false
        private set

    @Volatile var probeTruncated: Boolean = false
        private set

    @Volatile var acProbeActive: Boolean = false
        private set

    @Volatile var featureProbeSent: Boolean = false
        private set

    /** alpha2.31: 完整能力位图（用于增益/LED/空间音频等按需查询） */
    @Volatile var features: Set<Int> = emptySet()
        private set

    /** 重置全部探测状态（断开/重连时调用） */
    fun reset() {
        featureProbeSent = false
        ancPath = GaiaCommands.ANC_PATH_UNKNOWN
        probeDone = false
        probeTruncated = false
        acProbeActive = false
        features = emptySet()
    }

    /** 能力状态：0=探测中 1=有ANC 2=无ANC/截断 */
    fun status(): Int = when {
        !probeDone -> 0
        ancPath != GaiaCommands.ANC_PATH_UNKNOWN -> 1
        else -> 2
    }

    /** alpha2.31: 查询设备是否支持指定 GAIA feature bit */
    fun hasFeature(id: Int): Boolean = id in features

    /** 启动能力探测阶梯（在 onServicesDiscovered 成功后调用） */
    fun startProbes() {
        if (featureProbeSent) return
        featureProbeSent = true

        writeCommand(GaiaConstants.FEATURE_BASIC, GaiaConstants.CMD_GET_SUPPORTED_FEATURES, ByteArray(0))
        writeCommand(GaiaConstants.FEATURE_BASIC, GaiaConstants.CMD_GET_VARIANT, ByteArray(0))

        if (!acProbeActive) {
            acProbeActive = true
            val probePairs = arrayOf(
                intArrayOf(GaiaConstants.F_AUDIO_CURATION, GaiaConstants.CMD_AC_GET_MODE),
                intArrayOf(GaiaConstants.F_AUDIO_CURATION, GaiaConstants.CMD_AC_GET_SWITCH_CONF),
                intArrayOf(GaiaConstants.FEATURE_ANC_V2, GaiaConstants.CMD_GET_CURRENT_MODE),
                intArrayOf(GaiaConstants.FEATURE_ANC_V2, GaiaConstants.CMD_AC_GET_SWITCH_CONF)
            )
            for (i in probePairs.indices) {
                val f = probePairs[i][0]
                val c = probePairs[i][1]
                handler.postDelayed({
                    if (isConnected() && isGattReady()) writeCommand(f, c, ByteArray(0))
                }, PROBE_DELAY_BASE + i * PROBE_DELAY_STEP)
            }
        }

        // 超时自愈：阶梯重发
        handler.postDelayed({
            if (isConnected() && isGattReady() && ancPath == GaiaCommands.ANC_PATH_UNKNOWN) {
                Log.w(TAG, "capability probe timeout, re-send")
                writeCommand(GaiaConstants.FEATURE_BASIC, GaiaConstants.CMD_GET_SUPPORTED_FEATURES, ByteArray(0))
                writeCommand(GaiaConstants.FEATURE_BASIC, GaiaConstants.CMD_GET_VARIANT, ByteArray(0))
                if (!acProbeActive) {
                    acProbeActive = true
                    val pairs = arrayOf(
                        intArrayOf(GaiaConstants.F_AUDIO_CURATION, GaiaConstants.CMD_AC_GET_MODE),
                        intArrayOf(GaiaConstants.F_AUDIO_CURATION, GaiaConstants.CMD_AC_GET_SWITCH_CONF),
                        intArrayOf(GaiaConstants.FEATURE_ANC_V2, GaiaConstants.CMD_GET_CURRENT_MODE),
                        intArrayOf(GaiaConstants.FEATURE_ANC_V2, GaiaConstants.CMD_AC_GET_SWITCH_CONF)
                    )
                    for (i in pairs.indices) {
                        val f = pairs[i][0]
                        val c = pairs[i][1]
                        handler.postDelayed({
                            if (isConnected() && isGattReady()) writeCommand(f, c, ByteArray(0))
                        }, PROBE_DELAY_BASE + i * PROBE_DELAY_STEP)
                    }
                }
                handler.postDelayed({
                    if (!probeDone) {
                        probeDone = true
                        try { AncBridge.sendAncStatus(status()) } catch (_: Exception) { }
                    }
                }, PROBE_TIMEOUT_MS)
            }
        }, PROBE_TIMEOUT_MS)
    }

    /** 处理 GET_SUPPORTED_FEATURES 响应（能力位图） */
    fun handleFeatureResponse(payload: ByteArray) {
        val truncated = GaiaCommands.isFeaturePayloadTruncated(payload)
        probeTruncated = truncated
        val feats = GaiaCommands.parseSupportedFeatures(payload)
        features = feats  // alpha2.31: 存完整 feature set 供 hasFeature 查询
        if (truncated) {
            ancPath = GaiaCommands.ANC_PATH_UNKNOWN
            Log.w(TAG, "capability payload truncated (len=${payload.size}), keep ANC unknown")
        } else {
            ancPath = GaiaCommands.ancPathFrom(feats)
        }
        probeDone = true
        try { AncBridge.sendAncStatus(status()) } catch (_: Exception) { }
        Log.d(TAG, "capabilities: ${feats.sorted().joinToString(",")} truncated=$truncated -> ancPath=$ancPath")
    }

    /** 处理 ANC 探测响应（AudioCuration / ANC_V2 回包确认路径）。
     *  返回 true 表示已处理此响应（路径已确定）。 */
    fun handleAncProbeResponse(feature: Int, command: Int): Boolean {
        if (!acProbeActive || ancPath != GaiaCommands.ANC_PATH_UNKNOWN) return false

        var probeOk = false
        if (feature == GaiaConstants.FEATURE_ANC_V2 && (command == GaiaConstants.CMD_GET_CURRENT_MODE ||
                command == GaiaConstants.CMD_SET_CURRENT_MODE ||
                command == GaiaConstants.CMD_AC_GET_SWITCH_CONF ||
                command == GaiaConstants.CMD_AC_SET_SWITCH_CONF || command == 0x01)) {
            ancPath = GaiaCommands.ANC_PATH_ANC_V2
            probeOk = true
            Log.d(TAG, "ANC_V2 probe answered -> path=ANC_V2")
        } else if (feature == GaiaConstants.F_AUDIO_CURATION &&
                (command == GaiaConstants.CMD_AC_GET_SWITCH_CONF || command == GaiaConstants.CMD_AC_GET_MODE)) {
            ancPath = GaiaCommands.ANC_PATH_AUDIO_CURATION
            probeOk = true
            Log.d(TAG, "AudioCuration probe answered -> path=AUDIO_CURATION")
        }

        if (probeOk) {
            acProbeActive = false
            probeDone = true
            try { AncBridge.sendAncStatus(status()) } catch (_: Exception) { }
        }
        return probeOk
    }
}
