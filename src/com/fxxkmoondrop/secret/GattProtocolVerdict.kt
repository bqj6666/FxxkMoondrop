package com.fxxkmoondrop.secret

/**
 * GATT 服务发现结果的裁定。
 *
 * ## 为什么需要它
 *
 * 「服务列表为空」与「有服务但缺 GAIA/9ECA」看起来都归结为
 * `!hasGaia && !hasSrc9`，但**语义完全不同**：
 *
 * - 有服务、缺协议  -> 协议指纹**证伪**，可以拉黑（唯一合法依据）
 * - 服务列表为空    -> **传输层异常**（GATT 未就绪 / Android GATT 缓存为空 /
 *                      连到了错误的 LE 地址），不能据此判断设备支不支持协议
 *
 * ## issue #12 的真实事故
 *
 * 水月雨「知更鸟 Robin」(`ROBIN'S EARPHONES`) 反复出现：
 *
 * ```
 * GATT connected 24:11:14:B5:7B:76 -> discovering services
 * GATT services(0):                                  <- 空！且距连接仅数十毫秒
 * no GAIA/9ECA in GATT -> rejected: ROBIN'S EARPHONES <- 被永久拉黑
 * ```
 *
 * 一次空列表就把设备拉黑，此后连探测机会都没有，表现为
 * 「除电量外全部不可用」。这与 `GaiaBleClient` 内既有注释
 * （传输层失败不能判断设备是否支持协议）自相矛盾。
 */
internal object GattProtocolVerdict {

    enum class Verdict {
        /** 协议指纹命中：GAIA 或 9ECA 服务存在。 */
        SUPPORTED,

        /** 服务发现确有结果、但两个协议服务都没有 —— 可拉黑。 */
        REFUTED,

        /** 服务列表为空 —— 数据不足，继续探测，**不得**拉黑。 */
        INCONCLUSIVE,
    }

    /**
     * @param serviceCount 本次服务发现返回的服务数量
     * @param hasGaia      是否找到 GAIA 服务
     * @param hasSrc9      是否找到 9ECA(蓝讯) 服务
     */
    fun decide(serviceCount: Int, hasGaia: Boolean, hasSrc9: Boolean): Verdict = when {
        hasGaia || hasSrc9 -> Verdict.SUPPORTED
        serviceCount <= 0 -> Verdict.INCONCLUSIVE
        else -> Verdict.REFUTED
    }
}
