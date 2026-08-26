package com.fxxkmoondrop.secret

/**
 * alpha2.26.9: ANC 设备码型号档案库。
 *
 * 背景：GAIA AudioCuration 通道的"设备码 → 功能"映射在不同型号上可能不同。
 * 梦回二（Golden Ages 2）于 2026-08-24 用官方 App 实测（用户提供）：
 *   设备码 1 = 关闭降噪，2 = 打开降噪，3 = 抗风噪，4 = 透传。
 * 而 AC 名义编码顺序为 1=关 / 2=降噪 / 3=透传 / 4=抗风——3 与 4 顺序相反。
 *
 * UI 模式顺序固定：0=关闭  1=降噪  2=透传  3=抗风。
 * 档案值 = 每个 UI 模式应发送的设备码（IntArray(4)）。
 *
 * 适用范围：仅 GAIA 协议（AudioCuration 路径，ancPath=8）的 QCC 系耳机。
 * 蓝讯/中科系（9ECA0000 BleSourceSwitch）不走 GaiaCommands.ancDevFromUi/ancUiFromDev，
 * 本档案库不参与、绝不混用。
 *
 * 生效优先级：用户自定义（设置页 anc_map_*，anc_map_custom=1）> 型号档案 > 默认映射。
 */
object AncProfileLib {

    /** 默认映射：AC 名义编码 1=关 / 2=降噪 / 3=透传 / 4=抗风。未实测型号回退用。 */
    val DEFAULT_MAP: IntArray = intArrayOf(1, 2, 3, 4)

    private class Profile(val nameKey: String, val map: IntArray, val getMap: IntArray? = null)

    /** 型号档案表：设备名关键字（大写匹配，contains）→ 实测设备码映射。
     *  新型号实测确认后按同样格式追加；未实测型号一律走 DEFAULT_MAP。 */
    private val PROFILES: List<Profile> = listOf(
        // 梦回二 / Golden Ages 2 —— 2026-08-24 官方 App 抓包 + 08-25 真机双向实测：
        // SET（UI[关,降,透,抗] → dev）：1=关闭 2=降噪 4=透传 3=抗风
        // GET（dev → UI）：0=关闭 1=降噪 2=透传 3=抗风 —— 固件读回是 0-based 直传，与 SET 枚举不同！
        Profile("GOLDEN AGES 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3))
    )

    /**
     * 解析生效映射。
     * @param deviceName 当前连接设备名（广播名/系统名，可为 null）
     * @param custom 用户自定义映射（null = 未自定义）；自定义优先级最高
     */
    fun resolve(deviceName: String?, custom: IntArray?): IntArray {
        if (custom != null) return custom
        val n = deviceName?.uppercase()?.trim()
        if (!n.isNullOrEmpty()) {
            for (p in PROFILES) {
                if (n.contains(p.nameKey)) return p.map
            }
        }
        return DEFAULT_MAP
    }

    /**
     * 解析 GET 方向（设备码 → UI 模式）映射。
     * @param deviceName 当前连接设备名
     * @param customSet 用户是否自定义了 SET 映射；自定义时 GET 枚举不可知，返回 null 回退 indexOf 反查
     * @return 型号档案的 getMap（dev 下标 → UI 值）；无档案/未知型号返回 null
     */
    fun resolveGetMap(deviceName: String?, customSet: Boolean): IntArray? {
        // alpha2.30: GET 映射由固件决定，与用户自定义 SET 映射无关。
        // 即使 customSet=true，只要设备匹配型号档案就用档案 getMap。
        // 仅当无匹配档案时返回 null（回退 indexOf）。
        val n = deviceName?.uppercase()?.trim()
        if (!n.isNullOrEmpty()) {
            for (p in PROFILES) {
                if (n.contains(p.nameKey)) return p.getMap
            }
        }
        return null
    }

    /** 当前连接命中的档案名（调试/设置页展示用）；未命中返回 "默认" */
    fun matchedProfileName(deviceName: String?): String {
        val n = deviceName?.uppercase()?.trim()
        if (!n.isNullOrEmpty()) {
            for (p in PROFILES) {
                if (n.contains(p.nameKey)) return p.nameKey
            }
        }
        return "默认"
    }

    /**
     * alpha2.32: 扩展设备控制（DC）能力档案。
     * 按型号记录空间音频/增益/LED 支持情况。
     * 优先使用档案；档案未命中时回退到 GAIA 能力探测（CapabilityProbe.hasFeature）。
     */
    data class DcProfile(
        val nameKey: String,
        val hasSpatial: Boolean,
        val hasGain: Boolean,
        val hasLed: Boolean,
        val gainCount: Int = 3,
        val gainMap: IntArray = intArrayOf(0, 1, 2),
        val gainLabels: List<String> = listOf("低", "中", "高")
    )

    private val DC_PROFILES: List<DcProfile> = listOf(
        // 梦回二 / Golden Ages 2: 支持空间音频+增益, 不支持 LED
        DcProfile("GOLDEN AGES 2", hasSpatial = true, hasGain = true, hasLed = false, gainCount = 3, gainMap = intArrayOf(2, 1, 0), gainLabels = listOf("低", "中", "高"))
    )

    /** 默认 DC 档案：全部不支持（未知型号保守策略） */
    val DEFAULT_DC = DcProfile("默认", hasSpatial = false, hasGain = false, hasLed = false)

    fun resolveDc(deviceName: String?): DcProfile {
        val n = deviceName?.uppercase()?.trim()
        if (!n.isNullOrEmpty()) {
            for (p in DC_PROFILES) {
                if (n.contains(p.nameKey)) return p
            }
        }
        return DEFAULT_DC
    }
}
