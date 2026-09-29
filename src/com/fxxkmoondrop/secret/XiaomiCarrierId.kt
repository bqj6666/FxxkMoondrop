package com.fxxkmoondrop.secret

import java.util.Locale

/**
 * 耳机形态 → MiLink 载体 Device ID 映射。
 *
 * ## 为什么要「形态」而不是「型号」
 *
 * HyperOS 的 MiLink 侧按 Device ID 决定是否放行高级耳机能力、以及重建设备类型。
 * 我们不能写入自己的真实型号 ID（系统里没有），于是需要一个**能通过 MiLink 原生
 * 准入流程的载体值**。这个值的存在意义只是「让链路跑通」，**不代表任何小米型号
 * 的真实身份**。
 *
 * 因此映射键刻意选**形态**（TWS / 头戴）而不是型号：
 *  - 型号数量多且会变，做成表就是硬编码，且每加一个型号都要重新验证；
 *  - 形态只有少数几种，且 MiLink 关心的本来就是「这是 TWS 还是头戴」。
 *
 * 思路借鉴自 `silverpoetry/HyperEars` 的 `MiLinkCarrierIdentity`（AGPL-3.0，
 * 仅参考设计，代码为本项目独立实现）。其原注释要点：
 * > 这些 ID 是 compatibility carriers, not model identity.
 * > adapters must never depend on the Xiaomi model represented by this value.
 *
 * ## 两条纪律
 *
 *  1. **未登记形态返回 null，绝不兜底。** 返回 null 时上层降级为「不伪装」，
 *     核心 ANC / 电量仍走我们自己的 RFCOMM 链路，不影响任何功能。
 *     反之若兜底成 TWS 的 ID，头戴设备就会冒用 TWS 身份去走 MiLink 准入。
 *  2. **只在 MiLink / HyperOS 进程内生效，不写系统数据库。**
 *
 * ## 数据来源
 *
 *  `01010607`  TWS —— PuddingPods 文档（PUDDING.md）与 HyperEars 一致，互为印证
 *  `01013A04`  头戴 —— HyperEars 标注为小米 O70C 系列；**我们只用它当载体**，
 *                 适配逻辑不得依赖「O70C」这个含义。
 */
class XiaomiCarrierId private constructor() {

    /** 耳机形态。`UNKNOWN` 表示未能判定，映射时不给 ID。 */
    enum class FormFactor { TWS, HEADPHONES, UNKNOWN;

        companion object {
            /** 解析形态名；无法识别一律 [UNKNOWN]，不做模糊匹配。 */
            fun parse(raw: String?): FormFactor {
                val n = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                    ?: return UNKNOWN
                return when (n) {
                    "tws", "真无线" -> TWS
                    "headphones", "headset", "头戴" -> HEADPHONES
                    else -> UNKNOWN
                }
            }
        }
    }

    companion object {
        /**
         * 形态 → 载体 ID。**单一数据源。**
         *
         * 刻意写成 `when` 而非 map：新增形态时编译器会强制补齐分支，
         * 漏了就是编译错误，而不是运行时静默拿不到 ID。
         */
        @JvmStatic
        fun forFormFactor(formFactor: FormFactor): String? = when (formFactor) {
            FormFactor.TWS -> "01010607"
            FormFactor.HEADPHONES -> "01013A04"
            // 纪律 1：不给兜底。宁可拿不到载体而降级，也不串台。
            FormFactor.UNKNOWN -> null
        }

        /** 按形态名查载体 ID；无法识别返回 null。 */
        @JvmStatic
        fun parse(raw: String?): String? = forFormFactor(FormFactor.parse(raw))

        /** 已登记的映射（供测试自检：格式、唯一性、完整性）。 */
        @JvmStatic
        fun registered(): Map<FormFactor, String> =
            FormFactor.entries
                .mapNotNull { ff -> forFormFactor(ff)?.let { ff to it } }
                .toMap()
    }
}
