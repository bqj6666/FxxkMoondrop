package com.fxxkmoondrop.secret

/**
 * 官方服务命令库 —— 完整逆向自 Moondrop 官方 App 内嵌高通 gaiaclient（GAIA V3 / QTiL）
 * 以及 Moondrop 私有 BleSourceSwitch 协议（9ECAxxxx 服务）。
 *
 * 全部命令/枚举/帧构造均已对照官方源码逐条核实，供上层（ANC、风噪、EQ、蓝牙协议、
 * 音源切换等）后续调用。纯字节构造，无 Android 依赖。
 */
object GaiaCommands {

    // ============================================================
    // 1. QTiL Feature ID（GAIA V3 commandValue = (feature<<9)|(type<<7)|cmd）
    // ============================================================
    const val F_BASIC = 0
    const val F_EARBUD = 1
    const val F_ANC = 2            // ANC V1
    const val F_VOICE_UI = 3
    const val F_DEBUG = 4
    const val F_MUSIC_PROCESSING = 5 // EQ
    const val F_UPGRADE = 6
    const val F_HANDSET_SERVICE = 7
    const val F_AUDIO_CURATION = 8   // 风噪/透传/自动透传/防啸叫/噪声识别
    const val F_EARBUD_FIT = 9
    const val F_VOICE_PROCESSING = 10
    const val F_GESTURE_CONFIGURATION = 11 // 触控手势
    const val F_STATISTICS = 12
    const val F_BATTERY = 13
    const val F_VOICE = 14
    const val F_DAC_GAIN = 15
    const val F_CODEC_TYPE = 16      // LC3/LDAC/LHDC 蓝牙协议
    const val F_LIGHT_SENSOR = 17
    const val F_SPATIAL_AUDIO = 18   // 空间音频/头部追踪
    const val F_LED = 19
    const val F_ONEBRINGTWO = 20
    const val F_BT_ADDRESS = 21
    const val F_TOUCHV2 = 22
    const val F_AUDIO_RESOURCE = 23
    const val F_POWER_CONTROL = 24   // 关机
    const val F_POWER_TIMEOUT = 25
    const val F_TOUCHV3 = 26
    const val F_DYBASS = 27          // 动态低音
    const val F_AUDIO_FILE_STORAGE = 29
    const val F_LR_CHANNEL = 30      // 左右声道反转
    const val F_ANC_V2 = 32          // ANC V2

    // ============================================================
    // 2. BASIC(0)
    // ============================================================
    const val C_BASIC_GET_GAIA_VERSION = 0
    const val C_BASIC_GET_SUPPORTED_FEATURES = 1
    const val C_BASIC_GET_SUPPORTED_FEATURES_NEXT = 2
    const val C_BASIC_GET_SERIAL_NUMBER = 3
    const val C_BASIC_GET_VARIANT = 4
    const val C_BASIC_GET_APPLICATION_VERSION = 5
    const val C_BASIC_REGISTER_NOTIFICATION = 7
    const val C_BASIC_CANCEL_NOTIFICATION = 8
    const val C_BASIC_DATA_TRANSFER_SETUP = 9
    const val C_BASIC_DATA_TRANSFER_GET = 10
    const val C_BASIC_GET_EARBUD_COLOR = 18
    const val C_BASIC_GET_EARBUD_LANG = 19
    const val C_BASIC_GET_EARBUD_SN_L = 20
    const val C_BASIC_GET_EARBUD_SN_R = 21
    const val C_BASIC_GET_TWS_CONNECTION_STATUS = 22

    // ============================================================
    // 3. EARBUD(1)
    // ============================================================
    const val C_EARBUD_GET_WHAT_PRIMARY_IS = 0
    const val C_EARBUD_GET_SECONDARY_SERIAL_NUMBER = 1
    const val C_EARBUD_GET_CURRENT_BUTTON_ACTION = 2
    const val C_EARBUD_SET_EARDBUD_BUTTON_ACTION = 3

    // ============================================================
    // 4. ANC V1(2)
    // ============================================================
    const val C_ANC1_GET_ANC_STATE = 1
    const val C_ANC1_SET_ANC_STATE = 2
    const val C_ANC1_GET_NUM_ANC_MODES = 3
    const val C_ANC1_GET_CURRENT_ANC_MODE = 4
    const val C_ANC1_SET_ANC_MODE = 5
    const val C_ANC1_GET_CONFIGURED_LEAKTHROUGH_GAIN = 6
    const val C_ANC1_SET_LEAKTHROUGH_GAIN = 7

    // ============================================================
    // 5. AUDIO_CURATION(8)：风噪/透传/自动透传/防啸叫/噪声识别
    // ============================================================
    const val C_AC_V1_GET_AC_STATE = 0
    const val C_AC_V1_SET_AC_STATE = 1
    const val C_AC_V1_GET_MODES_COUNT = 2
    const val C_AC_V1_GET_CURRENT_MODE = 3
    const val C_AC_V1_SET_MODE = 4
    const val C_AC_V1_GET_GAIN = 5
    const val C_AC_V1_SET_GAIN = 6
    const val C_AC_V1_GET_TOGGLE_CONFIGURATION_COUNT = 7
    const val C_AC_V1_GET_TOGGLE_CONFIGURATION = 8
    const val C_AC_V1_SET_TOGGLE_CONFIGURATION = 9
    const val C_AC_V1_GET_SCENARIO_CONFIGURATION = 10
    const val C_AC_V1_SET_SCENARIO_CONFIGURATION = 11
    const val C_AC_V1_GET_DEMO_SUPPORT = 12
    const val C_AC_V1_GET_DEMO_STATE = 13
    const val C_AC_V1_SET_DEMO_STATE = 14
    const val C_AC_V1_GET_ADAPTATION_STATE = 15
    const val C_AC_V1_SET_ADAPTATION = 16
    const val C_AC_V2_GET_LEAKTHROUGH_GAIN_CONFIGURATION = 17
    const val C_AC_V2_GET_LEAKTHROUGH_GAIN_STEP = 18
    const val C_AC_V2_SET_LEAKTHROUGH_GAIN_STEP = 19
    const val C_AC_V2_GET_LEFT_RIGHT_BALANCE = 20
    const val C_AC_V2_SET_LEFT_RIGHT_BALANCE = 21
    const val C_AC_V2_GET_WIND_NOISE_REDUCTION_SUPPORT = 22
    const val C_AC_V2_GET_WIND_NOISE_DETECTION_STATE = 23
    const val C_AC_V2_SET_WIND_NOISE_DETECTION_STATE = 24
    const val C_AC_V4_GET_AUTO_TRANSPARENCY_SUPPORT = 25
    const val C_AC_V4_GET_AUTO_TRANSPARENCY_STATE = 26
    const val C_AC_V4_SET_AUTO_TRANSPARENCY_STATE = 27
    const val C_AC_V4_GET_AUTO_TRANSPARENCY_RELEASE_TIME = 28
    const val C_AC_V4_SET_AUTO_TRANSPARENCY_RELEASE_TIME = 29
    const val C_AC_V5_GET_HOWLING_DETECTION_SUPPORT = 30
    const val C_AC_V5_GET_HOWLING_DETECTION_STATE = 31
    const val C_AC_V5_SET_HOWLING_DETECTION_STATE = 32
    const val C_AC_V5_GET_FEEDBACK_GAIN = 33
    const val C_AC_V6_GET_NOISE_ID_SUPPORT = 34
    const val C_AC_V6_GET_NOISE_ID_STATE = 35
    const val C_AC_V6_SET_NOISE_ID_STATE = 36
    const val C_AC_V6_GET_NOISE_ID_CATEGORY = 37
    const val C_AC_V7_GET_ADVERSE_ACOUSTIC_HANDLER_SUPPORT = 38
    const val C_AC_V7_GET_ADVERSE_ACOUSTIC_HANDLER_STATE = 39
    const val C_AC_V7_SET_ADVERSE_ACOUSTIC_HANDLER_STATE = 40
    const val C_AC_V7_GET_CURRENT_ANC_SWITCH_CONF = 41
    const val C_AC_V7_SET_ANC_SWITCH_CONF = 42

    // ============================================================
    // 6. GESTURE_CONFIGURATION(11)：触控手势自定义
    // ============================================================
    const val C_GEST_GET_NUMBER_OF_TOUCHPADS = 0
    const val C_GEST_GET_SUPPORTED_GESTURES = 1
    const val C_GEST_GET_SUPPORTED_CONTEXTS = 2
    const val C_GEST_GET_SUPPORTED_ACTIONS = 3
    const val C_GEST_GET_CONFIGURATION_FOR_GESTURE = 4
    const val C_GEST_SET_CONFIGURATION_FOR_GESTURE = 5
    const val C_GEST_RESET_CONFIGURATION_TO_DEFAULT = 6

    // ============================================================
    // 7. BATTERY(13)
    // ============================================================
    const val C_BATT_GET_SUPPORTED_BATTERIES = 0
    const val C_BATT_GET_BATTERY_LEVELS = 1

    // ============================================================
    // 8. MUSIC_PROCESSING(5)：EQ
    // ============================================================
    const val C_EQ_GET_EQ_STATE = 0
    const val C_EQ_GET_AVAILABLE_EQ_PRE_SETS = 1
    const val C_EQ_GET_EQ_SET = 2
    const val C_EQ_SET_EQ_SET = 3
    const val C_EQ_GET_USER_SET_NUMBER_OF_BANDS = 4
    const val C_EQ_GET_USER_SET_CONFIGURATION = 5
    const val C_EQ_SET_USER_SET_CONFIGURATION = 6
    const val C_EQ_SET_USER_SET_STORAGE = 7
    const val C_EQ_SET_NV_ID = 8

    // ============================================================
    // 9. CODEC_TYPE(16)：蓝牙协议（LC3/LDAC/LHDC）
    // ============================================================
    const val C_CODEC_GET_LC3_STATE = 1
    const val C_CODEC_GET_LDAC_STATE = 2
    const val C_CODEC_SET_LC3_STATE = 3
    const val C_CODEC_SET_LDAC_STATE = 4
    const val C_CODEC_GET_LHDC_STATE = 5
    const val C_CODEC_SET_LHDC_STATE = 6

    // ============================================================
    // 10. 其他功能 feature
    // ============================================================
    const val C_DAC_GET_GAIN = 1
    const val C_DAC_SET_GAIN = 2
    const val C_SPATIAL_GET_SPATIAL_AUDIO_STATE = 1
    const val C_SPATIAL_SET_SPATIAL_AUDIO_STATE = 2
    const val C_SPATIAL_GET_HEAD_TRACKING_STATE = 3
    const val C_SPATIAL_SET_HEAD_TRACKING_AUDIO_STATE = 4
    const val C_LED_GET_LED_STATE = 1
    const val C_LED_SET_LED_STATE = 2
    const val C_DYBASS_GET_DYBASS_STATE = 1
    const val C_DYBASS_SET_DYBASS_STATE = 2
    const val C_LR_GET_IF_LR_CHANNEL_REVERSED = 1
    const val C_LR_SET_IF_LR_CHANNEL_REVERSED = 2
    const val C_POWER_SET_PWR_OFF = 1

    // ============================================================
    // 11. ANC V2(32)（Moondrop AncV2Handler 使用的版本）
    // ============================================================
    const val C_ANC2_GET_CURRENT_MODE = 3
    const val C_ANC2_SET_CURRENT_MODE = 4
    const val C_ANC2_GET_CURRENT_ANC_SWITCH_CONF = 41
    const val C_ANC2_SET_ANC_SWITCH_CONF = 42

    // ANC V2 模式（官方 AncV2Handler 常量）
    const val ANC2_MODE_OFF = 0
    const val ANC2_MODE_ON = 1
    const val ANC2_MODE_TRANSPARENT = 2
    const val ANC2_MODE_ANTI_WIND = 3
    const val ANC2_MODE_ADAPTIVE = 4
    const val ANC2_MODE_LIVE = 5

    // ANC 开关配置字段（SWITCH_CONF payload 顺序）
    @JvmField
    val ANC2_SWITCH_CONF_FIELDS = arrayOf("TP", "ORDER", "STATE", "ANC_ON", "ANC_OFF")

    // ANC V1 状态
    const val ANC1_STATE_DISABLE = 0
    const val ANC1_STATE_ENABLE = 1

    // 风噪检测状态（WindNoiseReductionState）
    const val WIND_NOT_DETECTED = 0
    const val WIND_DETECTED = 1

    // 耳塞位置（LeftRightBalance）
    const val POS_LEFT = 0
    const val POS_RIGHT = 1

    // ============================================================
    // 12. GAIA V3 包构造
    //    [vendor 2B BE][commandValue 2B BE][payload]
    //    commandValue = (feature << 9) | (type << 7) | command
    // ============================================================
    const val GAIA_VENDOR = 0x1D
    const val TYPE_COMMAND = 0
    const val TYPE_NOTIFICATION = 1
    const val TYPE_RESPONSE = 2

    @JvmStatic
    fun v3Packet(feature: Int, command: Int, payload: ByteArray?): ByteArray =
            v3PacketVendor(GAIA_VENDOR, feature, TYPE_COMMAND, command, payload)

    @JvmStatic
    fun v3PacketVendor(vendor: Int, feature: Int, type: Int, command: Int, payload: ByteArray?): ByteArray {
        val cmdValue = (feature shl 9) or (type shl 7) or (command and 0x7F)
        val p = payload ?: ByteArray(0)
        val out = ByteArray(4 + p.size)
        out[0] = ((vendor shr 8) and 0xFF).toByte()
        out[1] = (vendor and 0xFF).toByte()
        out[2] = ((cmdValue shr 8) and 0xFF).toByte()
        out[3] = (cmdValue and 0xFF).toByte()
        if (p.isNotEmpty()) System.arraycopy(p, 0, out, 4, p.size)
        return out
    }

    // ============================================================
    // 13. Moondrop 私有 BleSourceSwitch 协议（9ECAxxxx）
    //     帧: [0xA5][0x01][frameType][commandId][seq][payloadLen][payload<=14]
    // ============================================================
    const val SRC_SERVICE = "9eca0000-7f3a-4f32-9a38-a91b2c6e0100"
    const val SRC_COMMAND = "9eca0001-7f3a-4f32-9a38-a91b2c6e0100"
    const val SRC_RESPONSE = "9eca0002-7f3a-4f32-9a38-a91b2c6e0100"
    const val SRC_NOTIFICATION = "9eca0003-7f3a-4f32-9a38-a91b2c6e0100"
    const val SRC_CAPABILITY = "9eca0004-7f3a-4f32-9a38-a91b2c6e0100"
    const val SRC_FW_INFO = "9eca0005-7f3a-4f32-9a38-a91b2c6e0100"
    const val SRC_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    const val SRC_FRAME_COMMAND = 1
    const val SRC_FRAME_RESPONSE = 2
    const val SRC_FRAME_NOTIFICATION = 3

    // 音源（BleSourceSwitchFrames.SourceId）
    const val SRC_ID_BLUETOOTH = 0
    const val SRC_ID_USB_AUDIO = 1
    const val SRC_ID_WIRELESS_2_4G = 2
    const val SRC_ID_AUX_LINE_IN = 3
    const val SRC_ID_OPTICAL_SPDIF = 4
    const val SRC_ID_COAXIAL = 5
    const val SRC_ID_HDMI_ARC = 6
    const val SRC_ID_LOCAL_PLAYER = 7
    const val SRC_ID_AUTO_SELECT = 127
    const val SRC_ID_NO_SIGNAL = 254
    const val SRC_ID_UNKNOWN = 255

    // 设置选项（SetOption 位掩码）
    const val SRC_OPT_PERSIST_DEFAULT = 1
    const val SRC_OPT_MUTE_DURING_SWITCH = 4
    const val SRC_OPT_NO_BT_AUTO_RESUME = 8

    // 命令（BleSourceCommand）
    const val SRC_CMD_GET_AUDIO_SOURCE = 1
    const val SRC_CMD_SET_AUDIO_SOURCE = 2
    const val SRC_CMD_GET_CAPABILITY = 3
    const val SRC_CMD_GET_FW_VERSION = 4
    const val SRC_CMD_GET_DEVICE_VOLUME = 5
    const val SRC_CMD_SET_DEVICE_VOLUME = 6
    const val SRC_CMD_GET_PRESET_EQ = 7
    const val SRC_CMD_SET_PRESET_EQ = 8
    const val SRC_CMD_GET_PEQ_CONFIG = 9
    const val SRC_CMD_SET_PEQ_PREGAIN = 10
    const val SRC_CMD_GET_PEQ_POINT = 11
    const val SRC_CMD_SET_PEQ_POINT = 12
    const val SRC_CMD_COMMIT_PEQ = 13
    const val SRC_CMD_GET_MIC_GAIN = 14
    const val SRC_CMD_SET_MIC_GAIN = 15
    const val SRC_CMD_GET_EARBUD_COLOR = 18
    const val SRC_CMD_GET_EARBUD_LANGUAGE = 19
    const val SRC_CMD_GET_EARBUD_SN_LEFT = 20
    const val SRC_CMD_GET_EARBUD_SN_RIGHT = 21
    const val SRC_CMD_PING = 127

    // 通知 ID（BleSourceNotificationId）
    const val SRC_NOTIF_AUDIO_SOURCE_CHANGED = 129
    const val SRC_NOTIF_SWITCH_STATE_CHANGED = 130
    const val SRC_NOTIF_CAPABILITY_CHANGED = 131
    const val SRC_NOTIF_VOLUME_CHANGED = 133
    const val SRC_NOTIF_PRESET_EQ_CHANGED = 134
    const val SRC_NOTIF_PEQ_COMMITTED = 135
    const val SRC_NOTIF_MIC_GAIN_CHANGED = 136

    /** 构造私有协议命令帧 */
    @JvmStatic
    fun srcCommand(cmd: Int, seq: Int, payload: ByteArray?): ByteArray {
        val p = payload ?: ByteArray(0)
        require(p.size <= 14) { "payload > 14 bytes" }
        val out = ByteArray(6 + p.size)
        out[0] = 0xA5.toByte()
        out[1] = 0x01
        out[2] = SRC_FRAME_COMMAND.toByte()
        out[3] = cmd.toByte()
        out[4] = seq.toByte()
        out[5] = p.size.toByte()
        if (p.isNotEmpty()) System.arraycopy(p, 0, out, 6, p.size)
        return out
    }

    /** 查询当前音源 */
    @JvmStatic fun srcGetAudioSource(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_AUDIO_SOURCE, seq, null)

    /** 切换音源：sourceId + options + fade(0-60)，官方默认 options=0 fade=5 */
    @JvmStatic
    fun srcSetAudioSource(seq: Int, sourceId: Int, options: Int, fadeSec0: Int): ByteArray {
        var fadeSec = fadeSec0
        if (fadeSec < 0) fadeSec = 0
        if (fadeSec > 60) fadeSec = 60
        return srcCommand(SRC_CMD_SET_AUDIO_SOURCE, seq,
                byteArrayOf(sourceId.toByte(), options.toByte(), fadeSec.toByte()))
    }

    /** 切换音源（默认选项：不持久化、不静音、自动恢复） */
    @JvmStatic fun srcSetAudioSource(seq: Int, sourceId: Int): ByteArray =
            srcSetAudioSource(seq, sourceId, 0, 5)

    /** 查询能力页 */
    @JvmStatic fun srcGetCapability(seq: Int, page: Int): ByteArray =
            srcCommand(SRC_CMD_GET_CAPABILITY, seq, byteArrayOf(page.toByte()))

    /** 查询固件版本 */
    @JvmStatic fun srcGetFirmwareVersion(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_FW_VERSION, seq, null)

    /** 查询设备音量 */
    @JvmStatic fun srcGetDeviceVolume(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_DEVICE_VOLUME, seq, null)

    /** 设置设备音量（left,right 0-255，mute 0/1） */
    @JvmStatic fun srcSetDeviceVolume(seq: Int, left: Int, right: Int, mute: Int): ByteArray =
            srcCommand(SRC_CMD_SET_DEVICE_VOLUME, seq,
                    byteArrayOf(left.toByte(), right.toByte(), mute.toByte()))

    /** 查询预设 EQ 列表 */
    @JvmStatic fun srcGetPresetEq(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_PRESET_EQ, seq, null)

    /** 设置预设 EQ（presetIndex） */
    @JvmStatic fun srcSetPresetEq(seq: Int, presetIndex: Int): ByteArray =
            srcCommand(SRC_CMD_SET_PRESET_EQ, seq, byteArrayOf(presetIndex.toByte()))

    /** 查询 PEQ 配置 */
    @JvmStatic fun srcGetPeqConfig(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_PEQ_CONFIG, seq, null)

    /** 设置 PEQ 前置增益 */
    @JvmStatic fun srcSetPeqPregain(seq: Int, pregain: Int): ByteArray =
            srcCommand(SRC_CMD_SET_PEQ_PREGAIN, seq, byteArrayOf(pregain.toByte()))

    /** 查询 PEQ 点 */
    @JvmStatic fun srcGetPeqPoint(seq: Int, point: Int): ByteArray =
            srcCommand(SRC_CMD_GET_PEQ_POINT, seq, byteArrayOf(point.toByte()))

    /** 设置 PEQ 点（官方 setPoint: index, frequency, gain, q, filterType） */
    @JvmStatic fun srcSetPeqPoint(seq: Int, index: Int, freq: Int, gain: Int, q: Int, filterType: Int): ByteArray =
            srcCommand(SRC_CMD_SET_PEQ_POINT, seq, byteArrayOf(
                    index.toByte(), freq.toByte(), gain.toByte(), q.toByte(), filterType.toByte()))

    /** 提交 PEQ */
    @JvmStatic fun srcCommitPeq(seq: Int, type: Int): ByteArray =
            srcCommand(SRC_CMD_COMMIT_PEQ, seq, byteArrayOf(type.toByte()))

    /** 查询麦克风增益 */
    @JvmStatic fun srcGetMicGain(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_MIC_GAIN, seq, null)

    /** 设置麦克风增益 */
    @JvmStatic fun srcSetMicGain(seq: Int, gain: Int): ByteArray =
            srcCommand(SRC_CMD_SET_MIC_GAIN, seq, byteArrayOf(gain.toByte()))

    /** 查询耳机颜色/语言/SN */
    @JvmStatic fun srcGetEarbudColor(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_EARBUD_COLOR, seq, null)
    @JvmStatic fun srcGetEarbudLanguage(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_EARBUD_LANGUAGE, seq, null)
    @JvmStatic fun srcGetEarbudSnLeft(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_EARBUD_SN_LEFT, seq, null)
    @JvmStatic fun srcGetEarbudSnRight(seq: Int): ByteArray = srcCommand(SRC_CMD_GET_EARBUD_SN_RIGHT, seq, null)

    /** Ping */
    @JvmStatic fun srcPing(seq: Int): ByteArray = srcCommand(SRC_CMD_PING, seq, null)

    // ============================================================
    // 14. 便捷高层封装（对 GaiaBleClient 暴露的语义化方法）
    // ============================================================

    /** 查询当前 ANC V2 模式 */
    @JvmStatic fun anc2GetMode(): ByteArray = v3Packet(F_ANC_V2, C_ANC2_GET_CURRENT_MODE, null)

    /** 设置 ANC V2 模式：0=关闭 1=降噪 3=抗风噪 */
    @JvmStatic fun anc2SetMode(mode: Int): ByteArray =
            v3Packet(F_ANC_V2, C_ANC2_SET_CURRENT_MODE, byteArrayOf(mode.toByte()))

    /** 查询 ANC 开关配置 */
    @JvmStatic fun anc2GetSwitchConf(): ByteArray = v3Packet(F_ANC_V2, C_ANC2_GET_CURRENT_ANC_SWITCH_CONF, null)

    /** 设置 ANC 开关配置：STATE + ANC_ON + ANC_OFF + TP + ORDER 五个字段 */
    @JvmStatic fun anc2SetSwitchConf(conf5: ByteArray): ByteArray =
            v3Packet(F_ANC_V2, C_ANC2_SET_ANC_SWITCH_CONF, conf5)

    /** 风噪：查询当前检测状态（AUDIO_CURATION V2） */
    @JvmStatic fun windGetDetectionState(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_GET_WIND_NOISE_DETECTION_STATE, null)

    /** 风噪：设置检测开关（state: 0=关 1=开） */
    @JvmStatic fun windSetDetectionState(state: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_SET_WIND_NOISE_DETECTION_STATE, byteArrayOf(state.toByte()))

    /** 风噪：查询是否支持 */
    @JvmStatic fun windGetSupport(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_GET_WIND_NOISE_REDUCTION_SUPPORT, null)

    /** 透传增益步进 */
    @JvmStatic fun acGetLeakthroughGainStep(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_GET_LEAKTHROUGH_GAIN_STEP, null)
    @JvmStatic fun acSetLeakthroughGainStep(step: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_SET_LEAKTHROUGH_GAIN_STEP, byteArrayOf(step.toByte()))

    /** 左右平衡：[position(0=左,1=右)][gain] */
    @JvmStatic fun acGetLeftRightBalance(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_GET_LEFT_RIGHT_BALANCE, null)
    @JvmStatic fun acSetLeftRightBalance(position: Int, gain: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V2_SET_LEFT_RIGHT_BALANCE,
                    byteArrayOf(position.toByte(), gain.toByte()))

    /** 自动透传 */
    @JvmStatic fun acGetAutoTransparencySupport(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V4_GET_AUTO_TRANSPARENCY_SUPPORT, null)
    @JvmStatic fun acGetAutoTransparencyState(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V4_GET_AUTO_TRANSPARENCY_STATE, null)
    @JvmStatic fun acSetAutoTransparencyState(state: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V4_SET_AUTO_TRANSPARENCY_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun acGetAutoTransparencyReleaseTime(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V4_GET_AUTO_TRANSPARENCY_RELEASE_TIME, null)
    @JvmStatic fun acSetAutoTransparencyReleaseTime(time: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V4_SET_AUTO_TRANSPARENCY_RELEASE_TIME, byteArrayOf(time.toByte()))

    /** 防啸叫 */
    @JvmStatic fun acGetHowlingSupport(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V5_GET_HOWLING_DETECTION_SUPPORT, null)
    @JvmStatic fun acGetHowlingState(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V5_GET_HOWLING_DETECTION_STATE, null)
    @JvmStatic fun acSetHowlingState(state: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V5_SET_HOWLING_DETECTION_STATE, byteArrayOf(state.toByte()))

    /** 噪声识别 */
    @JvmStatic fun acGetNoiseIdSupport(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V6_GET_NOISE_ID_SUPPORT, null)
    @JvmStatic fun acGetNoiseIdState(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V6_GET_NOISE_ID_STATE, null)
    @JvmStatic fun acSetNoiseIdState(state: Int): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V6_SET_NOISE_ID_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun acGetNoiseIdCategory(): ByteArray =
            v3Packet(F_AUDIO_CURATION, C_AC_V6_GET_NOISE_ID_CATEGORY, null)

    /** 蓝牙协议（CODEC_TYPE）：LC3/LDAC/LHDC 开关（state 0/1） */
    @JvmStatic fun codecGetLc3(): ByteArray = v3Packet(F_CODEC_TYPE, C_CODEC_GET_LC3_STATE, null)
    @JvmStatic fun codecSetLc3(state: Int): ByteArray =
            v3Packet(F_CODEC_TYPE, C_CODEC_SET_LC3_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun codecGetLdac(): ByteArray = v3Packet(F_CODEC_TYPE, C_CODEC_GET_LDAC_STATE, null)
    @JvmStatic fun codecSetLdac(state: Int): ByteArray =
            v3Packet(F_CODEC_TYPE, C_CODEC_SET_LDAC_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun codecGetLhdc(): ByteArray = v3Packet(F_CODEC_TYPE, C_CODEC_GET_LHDC_STATE, null)
    @JvmStatic fun codecSetLhdc(state: Int): ByteArray =
            v3Packet(F_CODEC_TYPE, C_CODEC_SET_LHDC_STATE, byteArrayOf(state.toByte()))

    /** EQ（MUSIC_PROCESSING） */
    @JvmStatic fun eqGetState(): ByteArray = v3Packet(F_MUSIC_PROCESSING, C_EQ_GET_EQ_STATE, null)
    @JvmStatic fun eqGetAvailablePresets(): ByteArray =
            v3Packet(F_MUSIC_PROCESSING, C_EQ_GET_AVAILABLE_EQ_PRE_SETS, null)
    @JvmStatic fun eqGetSet(): ByteArray = v3Packet(F_MUSIC_PROCESSING, C_EQ_GET_EQ_SET, null)
    @JvmStatic fun eqSetSet(preset: Int): ByteArray =
            v3Packet(F_MUSIC_PROCESSING, C_EQ_SET_EQ_SET, byteArrayOf(preset.toByte()))
    @JvmStatic fun eqGetUserBandCount(): ByteArray =
            v3Packet(F_MUSIC_PROCESSING, C_EQ_GET_USER_SET_NUMBER_OF_BANDS, null)
    @JvmStatic fun eqGetUserConfig(): ByteArray =
            v3Packet(F_MUSIC_PROCESSING, C_EQ_GET_USER_SET_CONFIGURATION, null)

    /** 手势（GESTURE_CONFIGURATION） */
    @JvmStatic fun gestGetTouchpadCount(): ByteArray =
            v3Packet(F_GESTURE_CONFIGURATION, C_GEST_GET_NUMBER_OF_TOUCHPADS, null)
    @JvmStatic fun gestGetSupportedGestures(): ByteArray =
            v3Packet(F_GESTURE_CONFIGURATION, C_GEST_GET_SUPPORTED_GESTURES, null)
    @JvmStatic fun gestGetSupportedContexts(): ByteArray =
            v3Packet(F_GESTURE_CONFIGURATION, C_GEST_GET_SUPPORTED_CONTEXTS, null)
    @JvmStatic fun gestGetSupportedActions(): ByteArray =
            v3Packet(F_GESTURE_CONFIGURATION, C_GEST_GET_SUPPORTED_ACTIONS, null)
    @JvmStatic fun gestGetConfig(gesture: Int, context: Int): ByteArray =
            v3Packet(F_GESTURE_CONFIGURATION, C_GEST_GET_CONFIGURATION_FOR_GESTURE,
                    byteArrayOf(gesture.toByte(), context.toByte()))
    @JvmStatic fun gestResetDefault(): ByteArray =
            v3Packet(F_GESTURE_CONFIGURATION, C_GEST_RESET_CONFIGURATION_TO_DEFAULT, null)

    /** 空间音频 / 头部追踪 */
    @JvmStatic fun spatialGetState(): ByteArray = v3Packet(F_SPATIAL_AUDIO, C_SPATIAL_GET_SPATIAL_AUDIO_STATE, null)
    @JvmStatic fun spatialSetState(state: Int): ByteArray =
            v3Packet(F_SPATIAL_AUDIO, C_SPATIAL_SET_SPATIAL_AUDIO_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun spatialGetHeadTracking(): ByteArray =
            v3Packet(F_SPATIAL_AUDIO, C_SPATIAL_GET_HEAD_TRACKING_STATE, null)
    @JvmStatic fun spatialSetHeadTracking(state: Int): ByteArray =
            v3Packet(F_SPATIAL_AUDIO, C_SPATIAL_SET_HEAD_TRACKING_AUDIO_STATE, byteArrayOf(state.toByte()))

    /** 动态低音 / LED / 左右声道 / 关机 */
    @JvmStatic fun dybassGet(): ByteArray = v3Packet(F_DYBASS, C_DYBASS_GET_DYBASS_STATE, null)
    @JvmStatic fun dybassSet(state: Int): ByteArray =
            v3Packet(F_DYBASS, C_DYBASS_SET_DYBASS_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun ledGet(): ByteArray = v3Packet(F_LED, C_LED_GET_LED_STATE, null)
    @JvmStatic fun ledSet(state: Int): ByteArray =
            v3Packet(F_LED, C_LED_SET_LED_STATE, byteArrayOf(state.toByte()))
    @JvmStatic fun lrChannelGet(): ByteArray = v3Packet(F_LR_CHANNEL, C_LR_GET_IF_LR_CHANNEL_REVERSED, null)
    @JvmStatic fun lrChannelSet(reversed: Int): ByteArray =
            v3Packet(F_LR_CHANNEL, C_LR_SET_IF_LR_CHANNEL_REVERSED, byteArrayOf(reversed.toByte()))
    @JvmStatic fun powerOff(): ByteArray = v3Packet(F_POWER_CONTROL, C_POWER_SET_PWR_OFF, null)

    /** 设备信息（BASIC） */
    @JvmStatic fun basicGetGaiaVersion(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_GAIA_VERSION, null)
    @JvmStatic fun basicGetSupportedFeatures(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_SUPPORTED_FEATURES, null)
    @JvmStatic fun basicGetSerialNumber(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_SERIAL_NUMBER, null)
    @JvmStatic fun basicGetVariant(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_VARIANT, null)
    @JvmStatic fun basicGetAppVersion(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_APPLICATION_VERSION, null)
    @JvmStatic fun basicGetEarbudColor(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_EARBUD_COLOR, null)
    @JvmStatic fun basicGetEarbudLang(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_EARBUD_LANG, null)
    @JvmStatic fun basicGetEarbudSnL(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_EARBUD_SN_L, null)
    @JvmStatic fun basicGetEarbudSnR(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_EARBUD_SN_R, null)
    @JvmStatic fun basicGetTwsStatus(): ByteArray = v3Packet(F_BASIC, C_BASIC_GET_TWS_CONNECTION_STATUS, null)

    // ============================================================
    // 14. 能力探测与 ANC 路径选择（alpha2.14：跨型号自适应）
    //     GET_SUPPORTED_FEATURES 响应 = 32-bit word 序列（BE），
    //     word i 覆盖 feature 32*i .. 32*i+31，bit(feature) = feature % 32
    // ============================================================
    const val ANC_PATH_AUDIO_CURATION = 8   // feature F_AUDIO_CURATION（GA2 现行路径）
    const val ANC_PATH_ANC_V1 = 2           // feature F_ANC
    const val ANC_PATH_ANC_V2 = 32          // feature F_ANC_V2
    const val ANC_PATH_UNKNOWN = -1

    /** 解析 GET_SUPPORTED_FEATURES 响应位图 -> 支持的 feature id 集合 */
    @JvmStatic
    fun parseSupportedFeatures(payload: ByteArray?): Set<Int> {
        val p = payload ?: return emptySet()
        val features = HashSet<Int>()
        var wordIdx = 0
        var i = 0
        while (i + 3 < p.size) {
            val word = ((p[i].toInt() and 0xFF) shl 24) or
                    ((p[i + 1].toInt() and 0xFF) shl 16) or
                    ((p[i + 2].toInt() and 0xFF) shl 8) or
                    (p[i + 3].toInt() and 0xFF)
            for (bit in 0 until 32) {
                if (word and (1 shl bit) != 0) features.add(wordIdx * 32 + bit)
            }
            wordIdx++
            i += 4
        }
        return features
    }

    /** alpha2.22: 能力位图是否被截断（payload 长度非 4 的倍数，末尾 feature word 会丢失）。
     *  截断时该能力不完整，不应据此选择 ANC 路径（A1 健壮性）。 */
    @JvmStatic
    fun isFeaturePayloadTruncated(payload: ByteArray?): Boolean {
        val p = payload ?: return false
        return p.size % 4 != 0
    }

    /** 由能力集合推导 ANC 路径（优先级：AudioCuration > ANC V2 > ANC V1 > 未知） */
    @JvmStatic
    fun ancPathFrom(features: Set<Int>): Int = when {
        F_AUDIO_CURATION in features -> ANC_PATH_AUDIO_CURATION
        F_ANC_V2 in features -> ANC_PATH_ANC_V2
        F_ANC in features -> ANC_PATH_ANC_V1
        else -> ANC_PATH_UNKNOWN
    }

    /** 设备模式 -> UI 模式（0关 1降噪 2透传 3抗风；按 ANC 路径区分语义）。
     *  alpha2.26.2: AudioCuration 官方编码为 1-based（1=关/2=降噪/3=透传/4=抗风），
     *  不再硬编码恒等映射；custom 为用户自定义映射（index=UI模式，value=设备码）。 */
    @JvmStatic
    fun ancUiFromDev(path: Int, dev: Int, custom: IntArray? = null): Int = when (path) {
        ANC_PATH_ANC_V2 -> if (dev in 0..5) dev else -1    // ANC_V2 恒等映射（官方 AncV2Handler 0-5 直传）
        ANC_PATH_ANC_V1 -> if (dev == 0) 0 else 1
        ANC_PATH_AUDIO_CURATION -> {
            val map = custom ?: DEFAULT_ANC_MAP
            map.indexOf(dev)
        }
        else -> -1 // ANC_PATH_UNKNOWN：无ANC
    }

    /** UI 模式 -> 设备模式（负数 = 该路径不支持此 UI 模式）。custom 同上，null 用官方默认。 */
    @JvmStatic
    fun ancDevFromUi(path: Int, ui: Int, custom: IntArray? = null): Int = when (path) {
        ANC_PATH_ANC_V2 -> if (ui in 0..5) ui else -1     // 恒等映射；超范围返回 -1（禁止发送）
        ANC_PATH_ANC_V1 -> if (ui == 0) 0 else 1
        ANC_PATH_AUDIO_CURATION -> {
            val map = custom ?: DEFAULT_ANC_MAP
            if (ui in 0..3) map[ui] else -1
        }
        else -> -1 // ANC_PATH_UNKNOWN：能力未就绪/无ANC，禁止发送
    }

    /** alpha2.26.2: AudioCuration 官方默认映射（1=关/2=降噪/3=透传/4=抗风）。
     *  GA2 实测：发 1 得关、发 2 得降噪——恒等(0,1,2,3)整体错位 +1，官方枚举为 1-based。 */
    private val DEFAULT_ANC_MAP = intArrayOf(1, 2, 3, 4)
}
