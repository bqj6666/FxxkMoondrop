package com.fxxkmoondrop.secret

/**
 * Codec 状态（feature 0x10）。
 *
 * 官方 App 的用法：同一命令号既用于读状态（无载荷），也用于设置（单字节 1/0）。
 * 回包解析后按 codec 号缓存，界面据此显示开关状态；未回包即视为「未知/不支持」。
 */
object CodecState {

    const val LC3 = GaiaCommands.CODEC_LC3
    const val LDAC = GaiaCommands.CODEC_LDAC
    const val LHDC = GaiaCommands.CODEC_LHDC

    /** codec 号 → 最近一次读到的状态（true=开）。 */
    private val states = HashMap<Int, Boolean>()

    /** codec 号 → 是否收到过回包（收到过即认为该机型支持此项）。 */
    private val known = HashSet<Int>()

    @JvmStatic
    fun onResponse(command: Int, payload: ByteArray) {
        if (payload.isEmpty()) return
        states[command] = payload[0].toInt() != 0
        known.add(command)
        AppLog.i("CodecState", "codec " + command + " -> " + (payload[0].toInt() != 0))
    }

    @JvmStatic
    fun isKnown(codec: Int): Boolean = known.contains(codec)

    @JvmStatic
    fun isOn(codec: Int): Boolean = states[codec] ?: false

    @JvmStatic
    fun clear() {
        states.clear()
        known.clear()
    }
}
