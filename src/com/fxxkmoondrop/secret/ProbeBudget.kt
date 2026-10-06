package com.fxxkmoondrop.secret

import java.util.Locale

/**
 * 未收录型号的协议指纹探测**预算**。
 *
 * ## 为什么要限制
 *
 * 名字未命中水月雨特征的已连接音频设备会走「探测一次，命中就学、不中就别」
 * 的流程（`DeviceMatcher.allowProbe`）。但那条路有个致命缺口：
 * **判定失败时并不拉黑**（`RFCOMM failed without refutation -> keep probing`），
 * 而 `HeadsetDetectService` 每 5 秒轮询一次 —— 于是对同一台设备**无限重试**。
 *
 * issue #12 日志就是这个形态（同一台耳机，5~13 秒一轮，持续数分钟）：
 *
 * ```
 * 22:20:43 RFCOMM failed without refutation -> keep probing
 * 22:20:48 RFCOMM failed without refutation -> keep probing
 * 22:21:02 RFCOMM failed without refutation -> keep probing
 * ```
 *
 * ## 为什么这会打断**别的品牌**耳机
 *
 * 很多 TWS 的**头部追踪**跑在同一条 BLE 链路上，而多数耳机只接受
 * 一个 central 连接。我们反复发起的 GATT / RFCOMM 连接尝试会把那条链路
 * 抢过来或拽断 —— 用户感知就是「戴上别的耳机，头部追踪一会儿就失效」。
 *
 * ## 策略
 *
 * 给每个设备名一个**每会话**的尝试预算：用满即停，不再骚扰。
 * 不拉黑（避免误伤真正的水月雨新机型），用户点「刷新状态」时清空预算重来。
 *
 * 代价：极少数「前两次恰好抖动失败」的新机型需要用户手动点一次刷新。
 * 取舍明确 —— 产品要求是「对其他品牌耳机零影响」。
 */
internal object ProbeBudget {

    /** 每个设备每会话允许的探测次数。2 次足以穿过一次偶发抖动。 */
    const val MAX_ATTEMPTS = 2

    private val attempts = HashMap<String, Int>()

    /** 归一化设备名作键 —— 与 [DeviceMatcher] 的判定口径保持一致。 */
    fun keyOf(name: String?): String =
        name?.trim()?.lowercase(Locale.ROOT) ?: ""

    /** 该设备是否还有剩余探测预算。 */
    @Synchronized
    fun allow(name: String?): Boolean {
        val k = keyOf(name)
        if (k.isEmpty()) return false
        return (attempts[k] ?: 0) < MAX_ATTEMPTS
    }

    /** 记一次实际发起的探测。 */
    @Synchronized
    fun note(name: String?) {
        val k = keyOf(name)
        if (k.isEmpty()) return
        attempts[k] = (attempts[k] ?: 0) + 1
    }

    /** 已用次数（供测试与日志）。 */
    @Synchronized
    fun used(name: String?): Int = attempts[keyOf(name)] ?: 0

    /** 清空全部预算 —— 用户显式要求重试时调用（「刷新状态」）。 */
    @Synchronized
    fun reset() {
        attempts.clear()
    }
}
