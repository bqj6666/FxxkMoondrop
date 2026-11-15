package com.fxxkmoondrop.secret

/**
 * 设备功能表（官方 App 的 feature 枚举，值取自反编译的 r14）。
 *
 * 用途：连接后向 feature 0x00 读「支持的 feature 列表」，据此决定界面显示哪些功能块 ——
 * 换别的水月雨机型时不需要改代码，设备自己回答它支持什么。
 *
 * 展示顺序按产品逻辑固定为：降噪 → 增益 → 空间音频 → 音频处理 → 设备设置。
 * 这个顺序只影响 UI，不影响能力判定。
 */
object DeviceFeatures {

    /** feature id → 显示名（中/英）。 */
    private val NAMES: Map<Int, Pair<String, String>> = mapOf(
            0 to ("基础" to "Basic"),
            1 to ("耳机" to "Earbud"),
            2 to ("降噪" to "ANC"),
            3 to ("语音助手" to "Voice UI"),
            5 to ("音乐处理" to "Music processing"),
            8 to ("音频定制" to "Audio curation"),
            9 to ("佩戴检测" to "Earbud fit"),
            10 to ("语音处理" to "Voice processing"),
            11 to ("手势设置" to "Gestures"),
            13 to ("电量" to "Battery"),
            15 to ("增益" to "Gain"),
            16 to ("编解码器" to "Codec"),
            17 to ("光感" to "Light sensor"),
            18 to ("空间音频" to "Spatial audio"),
            19 to ("指示灯" to "LED"),
            24 to ("电源控制" to "Power control"),
            25 to ("超时关机" to "Power timeout"),
            27 to ("动圈增强" to "Dyn bass"),
            30 to ("左右声道" to "L/R channel"),
            32 to ("降噪 V2" to "ANC V2"),
            33 to ("降噪 V3" to "ANC V3"),
            34 to ("查找耳机" to "Find earbud"),
            35 to ("双麦降噪" to "Dual-mic ENC"))

    /** 展示顺序：先音频控制，再设备设置。未列出的 id 排在最后。 */
    private val ORDER = intArrayOf(
            32, 33, 2,            // 降噪
            15, 27,               // 增益 / 动圈增强
            18, 8,                // 空间音频 / 音频定制
            5, 10, 35,            // 音乐处理 / 语音处理 / 双麦
            11, 9, 17,            // 手势 / 佩戴检测 / 光感
            16, 19, 25, 24, 13,   // 编解码器 / 指示灯 / 超时 / 电源 / 电量
            34, 30, 1, 0)

    private val supported = LinkedHashSet<Int>()

    @JvmStatic
    fun onSupported(ids: IntArray) {
        supported.clear()
        ids.forEach { if (it in NAMES.keys) supported.add(it) }
        AppLog.i("DeviceFeatures", "supported=" + supported.joinToString(","))
    }

    @JvmStatic
    fun clear() = supported.clear()

    @JvmStatic
    fun supports(feature: Int): Boolean = supported.contains(feature)

    @JvmStatic
    fun name(feature: Int): String {
        val n = NAMES[feature] ?: return "0x" + Integer.toHexString(feature)
        return Lang.t(n.first, n.second)
    }

    /** 已支持的功能，按产品展示顺序排列。 */
    @JvmStatic
    fun ordered(): List<Int> {
        val out = ArrayList<Int>()
        ORDER.forEach { if (supported.contains(it)) out.add(it) }
        supported.forEach { if (!out.contains(it)) out.add(it) }
        return out
    }
}
