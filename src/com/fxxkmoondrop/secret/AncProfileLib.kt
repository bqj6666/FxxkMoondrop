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

    val ANC_MODE_NAMES: Array<String> = arrayOf("关闭", "降噪", "透传", "抗风")
    val ANC_MODE_NAMES_FULL: Array<String> = arrayOf("关闭", "降噪", "透传", "抗风", "自适应", "直播")

    /** 按当前语言返回 4 档模式名（显示层用）。zh 保留原中文，en 用英文。 */
    fun modeNames(ctx: android.content.Context): Array<String> =
        if (Lang.isZh(ctx)) ANC_MODE_NAMES else arrayOf("Off", "ANC", "Transparency", "Wind")

    /** 按当前语言返回 6 档完整模式名（显示层用）。 */
    fun modeNamesFull(ctx: android.content.Context): Array<String> =
        if (Lang.isZh(ctx)) ANC_MODE_NAMES_FULL else arrayOf("Off", "ANC", "Transparency", "Wind", "Adaptive", "Live")

    /** 按当前语言返回档位增益标签（低/中/高）。 */
    fun gainLabels(ctx: android.content.Context): List<String> =
        if (Lang.isZh(ctx)) listOf("低", "中", "高") else listOf("Low", "Mid", "High")

    /** 按当前语言返回空间音频追踪标签（关闭追踪/30°/全方位）。 */
    fun trackingLabels(ctx: android.content.Context): Array<String> =
        if (Lang.isZh(ctx)) arrayOf("关闭追踪", "30°", "全方位") else arrayOf("Off", "30°", "Surround")

    private class Profile(val nameKey: String, val map: IntArray, val getMap: IntArray? = null)

    /** 型号档案表：设备名关键字（大写匹配，contains）→ 实测设备码映射。
     *  新型号实测确认后按同样格式追加；未实测型号一律走 DEFAULT_MAP。 */
    private val PROFILES: List<Profile> = listOf(
        // 梦回二 / Golden Ages 2 —— 2026-08-24 官方 App 抓包 + 08-25 真机双向实测：
        // SET（UI[关,降,透,抗] → dev）：1=关闭 2=降噪 4=透传 3=抗风
        // GET（dev → UI）：0=关闭 1=降噪 2=透传 3=抗风 —— 固件读回是 0-based 直传，与 SET 枚举不同！
        Profile("GOLDEN AGES 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3))
        ,
        // 太空漫游2 / Space Travel 2 (BT8932F, 中科蓝讯 9ECA) —— 2026-09-01 真机双向实测：
        // SET（UI[关,降,透,抗] → dev）：1=关闭 2=降噪 4=透传 3=抗风（同 GA2，非默认顺序）
        // GET（dev → UI）：0=关闭 1=降噪 2=透传 3=抗风（0-based 直传）
        Profile("SPACE TRAVEL 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3))
    )

    /**
     * 解析生效映射。
     * @param deviceName 当前连接设备名（广播名/系统名，可为 null）
     * @param custom 用户自定义映射（null = 未自定义）；自定义优先级最高
     */
    /**
     * 判断一份自定义映射是否为旧版本「部分写入」留下的历史残留。
     *
     * 旧设置页只写被编辑的那一格，未编辑的格子落库时回退到**名义默认映射**而不是型号档案；
     * 于是用户只要碰过任意一格，其余格就被隐式写成 [DEFAULT_MAP] 的顺序
     * （GA2 / 太空漫游2 的档案与名义顺序恰好 3/4 对调，表现为「透传↔抗风互换」）。
     *
     * 判定特征：重建出的映射恰好 == [DEFAULT_MAP]，而当前型号档案 != [DEFAULT_MAP]。
     * 注意不可反向放宽 —— 档案与名义顺序一致的型号上，用户真心想要名义顺序是对的，
     * 那种情况必须保留用户设置、绝不能清。
     */
    fun isStalePartialCustom(rebuilt: IntArray, profileMap: IntArray): Boolean =
        rebuilt.contentEquals(DEFAULT_MAP) && !profileMap.contentEquals(DEFAULT_MAP)

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

    // ============================================================
    // ANC_V2 路径（GAIA feature 0x20）型号档案 —— 3.0.3
    //
    // 为什么单独一张表：ANC_V2 的**设备码语义与 AudioCuration 不同**，且各型号也不同。
    // 官方/第三方实测（PuddingPods，https://github.com/lingbai-rong/PuddingPods）：
    //   布丁 TX `00 1D 40 04 <mode>`，mode = 00 关闭 / 01 自适应降噪 / 02 通透 / 03 抗风噪 / 04 基础降噪
    // 而旧实现对该路径做**恒等映射**，于是：
    //   UI「降噪」(1) -> dev 1 = 自适应降噪（发错档）
    //   UI「自适应」(4) -> dev 4 = 基础降噪（发错档，且与降噪互换）
    // 本表把 UI 档位显式映射到正确设备码，同时让「自适应」这一档真正可用
    // （官方面板 / 通知 / 弹窗的 adaptive 按钮都走 supportedUiModes + 本表）。
    //
    // setMap 下标 = UI 档位（0关 1降 2透 3抗 4自适应 5直播），值 = 设备码，-1 = 该档位设备不支持。
    // getMap 下标 = 设备码，值 = UI 档位（固件读回方向）。
    // 未命中的型号一律回退恒等映射（保持旧行为，不猜）。
    // ============================================================

    private class AncV2Profile(val nameKey: String, val setMap: IntArray, val getMap: IntArray)

    private val ANC_V2_PROFILES: List<AncV2Profile> = listOf(
        AncV2Profile(
            nameKey = "PUDDING",
            // UI[关,降,透,抗,自适应,直播] -> dev
            setMap = intArrayOf(0, 4, 2, 3, 1, -1),
            // dev -> UI（0关 1自适应 2透 3抗 4降）
            getMap = intArrayOf(0, 4, 2, 3, 1)
        )
    )

    private fun matchAncV2(deviceName: String?): AncV2Profile? {
        val n = deviceName?.uppercase()?.trim()
        if (!n.isNullOrEmpty()) {
            for (p in ANC_V2_PROFILES) if (n.contains(p.nameKey)) return p
        }
        return null
    }

    /** ANC_V2 的 SET 映射（UI -> dev）；未命中型号返回 null，调用方回退恒等映射。 */
    fun resolveAncV2Set(deviceName: String?): IntArray? = matchAncV2(deviceName)?.setMap

    /** ANC_V2 的 GET 映射（dev -> UI）；未命中型号返回 null，调用方回退恒等映射。 */
    fun resolveAncV2Get(deviceName: String?): IntArray? = matchAncV2(deviceName)?.getMap

    /** ANC_V2 档案实际支持的 UI 档位（setMap 里非 -1 的槽位）；未命中型号返回 null = 不限制。 */
    fun supportedAncV2UiModes(deviceName: String?): IntArray? {
        val m = matchAncV2(deviceName)?.setMap ?: return null
        val out = ArrayList<Int>(m.size)
        for (ui in m.indices) if (m[ui] >= 0) out.add(ui)
        return out.toIntArray()
    }

    /**
     * 能力未知时按型号档案兜底宣告的 UI 档位（0 关 / 1 降噪 / 2 透传 / 3 抗风）。
     *
     * 只含**各 ANC 路径通用**的基础档：自适应(4)、直播(5) 属于固件新增能力，
     * 没有能力证据就不该出现 —— 否则 4 档设备会多出点了没反应的空档位。
     */
    val BASIC_UI_MODES: IntArray get() = intArrayOf(0, 1, 2, 3)

    /**
     * 控制面板里某一档列是否该显示（纯逻辑，便于单测）。
     *
     * [knownUiModes] 为空 = 能力未知 -> 只兜底显示 [BASIC_UI_MODES]；
     * 非空 = 以设备实际能力为准。抗风(3) 另受用户开关 [showWind] 约束。
     */
    fun ancColumnVisible(uiMode: Int, knownUiModes: IntArray, showWind: Boolean): Boolean {
        if (uiMode == 3 && !showWind) return false
        return if (knownUiModes.isEmpty()) BASIC_UI_MODES.contains(uiMode)
        else knownUiModes.contains(uiMode)
    }

    /** 降噪档的 UI 档位号（顺序固定：0 关 / 1 降噪 / 2 透传 / 3 抗风）。 */
    const val UI_MODE_ANC = 1

    /** 抗风档的 UI 档位号。 */
    const val UI_MODE_WIND = 3

    /**
     * 抗风开关此刻是否可用（纯逻辑，便于单测）。
     *
     * 抗风是「降噪」的加强档，只服务 [UI_MODE_ANC] <-> [UI_MODE_WIND] 这一对：
     * 降噪档时可开；抗风档时仍要可用（否则关不掉）；切到关闭/透传/自适应后自动失效、整块隐藏。
     */
    fun windSwitchAvailable(ancMode: Int): Boolean =
            ancMode == UI_MODE_ANC || ancMode == UI_MODE_WIND

    // ==================== 空间音频 / 头部追踪（官方 Spatializer 代理） ====================

    /**
     * 空间音频开关的勾选态。
     *
     * 系统侧（官方 Spatializer，也就是官方那个开关所用的同一套 API）判定优先；
     * 系统侧不可判定（spatializer 不可用 / 设备未路由，[systemOn] = null）时退回耳机端 GAIA 状态，
     * 这样开关不会因为系统偶尔不认设备就显示成关。
     */
    fun spatialChecked(systemOn: Boolean?, gaiaOn: Boolean): Boolean = systemOn ?: gaiaOn

    /**
     * 官方那两个开关 -> 我们耳机端的三档（0 = 关闭追踪，1 = 30°，2 = 全方位）。
     *
     * 空间音频关 = 关闭追踪；空间音频开而头部追踪关 = 30°；两个都开 = 全方位。
     * 官方只有两态，所以三档里的「30°」就是它的「空间音频开、头部追踪关」这一态。
     */
    fun trackingModeFor(spatialOn: Boolean, headTrackingOn: Boolean): Int =
            if (!spatialOn) 0 else if (headTrackingOn) 2 else 1

    /**
     * 降噪各档按钮的出场顺序（通知栏按钮与 App 主界面按钮共用这一份）。
     *
     * 降噪 / 关闭 / 通透 打头，自适应、直播、抗风随后 —— 对齐官方 GFPS Hearable Controls 通知。
     * 只描述顺序，不描述可用性：哪些档位真的出现仍由设备能力与显示偏好决定。
     */
    val ANC_UI_ORDER: IntArray = intArrayOf(1, 0, 2, 4, 5, 3)

    /** 三档回读成官方头部追踪那个开关的勾选态：只有「全方位」算开启。 */
    fun headTrackingOn(trackingMode: Int): Boolean = trackingMode == 2

    /**
     * 面板 / 官方行该读到的追踪档位（-1 = 不适用）。
     *
     * 不变量：空间音频开着时不会是「关闭追踪」—— 除非用户手动关过。
     * 所以空间音频开而耳机端报 0 档时读作 30°（那是补档途中的瞬态，不该闪一下「关闭」）。
     */
    fun displayTrackingMode(spatialOn: Boolean, gaiaTracking: Int, userClosedTracking: Boolean): Int =
            when {
                !spatialOn -> -1
                gaiaTracking !in 0..2 -> -1
                gaiaTracking == 0 && userClosedTracking -> 0
                gaiaTracking == 0 -> trackingModeFor(spatialOn = true, headTrackingOn = false)
                else -> gaiaTracking
            }

    /**
     * 这次三档点击算不算「用户手动关掉追踪」。
     *
     * 只有「本来就在 30° / 全方位，用户自己切到关闭追踪」才算 —— 之后不再自动补档。
     * 本来就已是关闭状态时再点一次关闭是无操作，不算表态，别把默认状态当成用户的选择。
     */
    fun isManualTrackingClose(pickedMode: Int, currentMode: Int): Boolean =
            pickedMode == 0 && currentMode != 0

    /**
     * 打开空间音频后，耳机端报来的追踪模式是「关闭」时要补上的档位；null = 不动。
     *
     * 打开空间音频就该有一档追踪：此刻官方头部追踪开关必然是关的（耳机端就是 0 档），
     * 对应我们的 30°。用户在软件内自己把追踪关掉过（[userClosedTracking]）则尊重他的选择，不补。
     */
    fun correctedTrackingMode(spatialOn: Boolean, gaiaTracking: Int, userClosedTracking: Boolean): Int? =
            if (spatialOn && gaiaTracking == 0 && !userClosedTracking)
                trackingModeFor(spatialOn = true, headTrackingOn = false)
            else null

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
        val gainMap: IntArray = intArrayOf(2, 1, 0),
        val gainLabels: List<String> = listOf("低", "中", "高"),
        val trackingLabels: Array<String> = arrayOf("关闭追踪", "30°", "全方位")
    )

    private val DC_PROFILES: List<DcProfile> = listOf(
        // 梦回二 / Golden Ages 2: 支持空间音频+增益, 不支持 LED
        DcProfile("GOLDEN AGES 2", hasSpatial = true, hasGain = true, hasLed = false, gainCount = 3, gainMap = intArrayOf(2, 1, 0), gainLabels = listOf("低", "中", "高"),
        trackingLabels = arrayOf("关闭追踪", "30°", "全方位")),

        // 布丁 PUDDING (MD-TWS-056): 增益+指示灯, 无空间音频
        // GAIA V4 over RFCOMM/SPP; ANC 走 ANC_V2，档位映射见 [ANC_V2_PROFILES]（非恒等）
        // 协议来源: https://github.com/lingbai-rong/PuddingPods
        // 增益 0x00=低/0x01=中/0x02=高（恒等映射）; 指示灯 0x00=关/0x01=开
        DcProfile("PUDDING", hasSpatial = false, hasGain = true, hasLed = true, gainCount = 3, gainMap = intArrayOf(0, 1, 2), gainLabels = listOf("低", "中", "高"),
        trackingLabels = arrayOf("关闭追踪", "30°", "全方位")),

        // 太空漫游2 / Space Travel 2 (BT8932F, 中科蓝讯): 无空间音频, 三档增益
        // 2026-09-01 真机实测: 增益 0x00=高/0x01=中/0x02=低（反向），gainMap[低,中,高]=[2,1,0]
        // ANC 走 AudioCuration 通道，实测 [1,2,4,3]（同 GA2），见上层 PROFILES
        DcProfile("SPACE TRAVEL 2", hasSpatial = false, hasGain = true, hasLed = false, gainCount = 3, gainMap = intArrayOf(2, 1, 0), gainLabels = listOf("低", "中", "高"),
        trackingLabels = arrayOf("关闭追踪", "30°", "全方位")),

        // 猫饼 Nekocake (9ECA 蓝讯系): 【预置，待实测】空间音频=否, 三档增益, 无 LED
        // 与 SPACE TRAVEL 2 同为 9ECA0000 BleSourceSwitch 蓝讯主控, 增益恒等 0/1/2 = 低/中/高
        DcProfile("NEKOCAKE", hasSpatial = false, hasGain = true, hasLed = false, gainCount = 3, gainMap = intArrayOf(0, 1, 2), gainLabels = listOf("低", "中", "高"),
        trackingLabels = arrayOf("关闭追踪", "30°", "全方位")),

        // 音乐胶囊 Pill (9ECA 蓝讯系): 【预置，待实测】空间音频=否, 三档增益, 无 LED
        DcProfile("PILL", hasSpatial = false, hasGain = true, hasLed = false, gainCount = 3, gainMap = intArrayOf(0, 1, 2), gainLabels = listOf("低", "中", "高"),
        trackingLabels = arrayOf("关闭追踪", "30°", "全方位")),

        // 猫咖 MOCA (独立新品, 2026-08-31 真机日志+用户确认): 无空间音频, 三档增益, 有 LED, 支持抗风噪
        // DcProfile 暂无入耳检测字段(架构不支持, 未实现); gainMap 恒等 0/1/2 = 低/中/高 【预置，待实测】
        DcProfile("MOCA", hasSpatial = false, hasGain = true, hasLed = true, gainCount = 3, gainMap = intArrayOf(0, 1, 2), gainLabels = listOf("低", "中", "高"),
        trackingLabels = arrayOf("关闭追踪", "30°", "全方位")),
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

    /** 是否为 Moondrop 设备：设备名含 "MOONDROP"，或命中任一已知 DC 型号档案。用于限定蓝牙详情页面板注入。 */
    fun isMoondrop(deviceName: String?): Boolean {
        val n = deviceName?.uppercase()?.trim() ?: return false
        if (n.contains("MOONDROP")) return true
        for (p in DC_PROFILES) {
            if (n.contains(p.nameKey)) return true
        }
        return false
    }
}
