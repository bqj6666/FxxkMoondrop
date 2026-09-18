package com.fxxkmoondrop.secret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANC 设备码映射档案 与「部分自定义残留」判定的回归测试。
 *
 * 覆盖的历史缺陷：设置页只写被编辑的那一格，未编辑的格子落库时回退到
 * **名义默认映射**（AncProfileLib.DEFAULT_MAP）而不是型号档案；用户只要碰过
 * 任意一格，其余格就被隐式写成名义顺序。GA2 / 太空漫游2 的档案与名义顺序
 * 恰好 3/4 对调，表现为「透传 ↔ 抗风按钮互换」（issue #1）。
 *
 * 纯逻辑，无 Android 依赖，`./gradlew test` 可直接运行。
 * 期望值一律由 [AncProfileLib] 的公开常量与函数推导，不写字面量映射。
 */
class AncProfileLibTest {

    /** 型号档案的匹配是「设备名含关键字」；此处用真机实测型号名作为夹具。 */
    private val knownDevice = "MOONDROP Golden Ages 2"

    /** 不命中任何档案的设备名 → resolve 回退名义默认映射。 */
    private val unknownDevice = "Some Other Headset"

    private val nominalDefault = AncProfileLib.DEFAULT_MAP

    /** 命中档案的映射（由公开 API 推导，不硬编码顺序）。 */
    private val knownProfile = AncProfileLib.resolve(knownDevice, null)

    // ==================== 能力未知时的档位兜底 ====================

    /**
     * 回归：设备详情页凭空多出「自适应」。
     *
     * 现场：GA2（走 AudioCuration，只有 0..3 四档）RFCOMM 探测失败 -> 能力未知
     * （modes 为空）-> 旧规则 `!knownModes || contains(i)` 对**所有**列都为真，
     * 于是自适应(4) 被显示出来，点了没反应。
     */
    @Test
    fun unknownCapability_doesNotRevealAdaptive() {
        val unknown = IntArray(0)
        assertFalse("能力未知时不得显示自适应(4)",
                AncProfileLib.ancColumnVisible(4, unknown, showWind = true))
        assertFalse("能力未知时不得显示直播(5)",
                AncProfileLib.ancColumnVisible(5, unknown, showWind = true))
    }

    /** 能力未知时基础四档照旧兜底显示（不误伤未知型号）。 */
    @Test
    fun unknownCapability_stillShowsBasicModes() {
        val unknown = IntArray(0)
        for (m in AncProfileLib.BASIC_UI_MODES) {
            assertTrue("能力未知时基础档 $m 应显示",
                    AncProfileLib.ancColumnVisible(m, unknown, showWind = true))
        }
    }

    /** 抗风(3) 仍受用户开关约束，能力未知与否都一样。 */
    @Test
    fun windColumn_followsUserSwitchRegardlessOfCapability() {
        assertFalse(AncProfileLib.ancColumnVisible(3, IntArray(0), showWind = false))
        assertFalse(AncProfileLib.ancColumnVisible(3, intArrayOf(0, 1, 2, 3), showWind = false))
        assertTrue(AncProfileLib.ancColumnVisible(3, IntArray(0), showWind = true))
    }

    /** 有能力证据时以设备能力为准：四档设备即便能力已知也不出现自适应。 */
    @Test
    fun knownCapability_isAuthoritative() {
        val ga2 = intArrayOf(0, 1, 2, 3)   // AudioCuration 路径
        assertFalse(AncProfileLib.ancColumnVisible(4, ga2, showWind = true))
        assertTrue(AncProfileLib.ancColumnVisible(1, ga2, showWind = true))
        // 布丁（ANC_V2 档案）声明了自适应，必须仍能显示
        val pudding = AncProfileLib.supportedAncV2UiModes("MOONDROP Pudding")!!
        assertTrue(AncProfileLib.ancColumnVisible(4, pudding, showWind = true))
        assertFalse("布丁不支持直播", AncProfileLib.ancColumnVisible(5, pudding, showWind = true))
    }

    /** 未收录型号的 ANC_V2 不再宣告自适应/直播（只有基础四档）。 */
    @Test
    fun unlistedAncV2_declaresOnlyBasicModes() {
        assertArrayEquals(AncProfileLib.BASIC_UI_MODES,
                AncProfileLib.supportedAncV2UiModes(unknownDevice) ?: AncProfileLib.BASIC_UI_MODES)
        assertFalse(AncProfileLib.BASIC_UI_MODES.contains(4))
        assertFalse(AncProfileLib.BASIC_UI_MODES.contains(5))
    }

    // ==================== 档案解析优先级 ====================

    /** 用户自定义优先级最高：原样返回，不与档案合并。 */
    @Test
    fun resolve_customHasHighestPriority() {
        val custom = nominalDefault.reversedArray()
        assertArrayEquals(custom, AncProfileLib.resolve(knownDevice, custom))
        assertArrayEquals(custom, AncProfileLib.resolve(unknownDevice, custom))
    }

    /** 未自定义时按档案解析；未命中档案则回退名义默认。 */
    @Test
    fun resolve_fallsBackToNominalDefaultForUnknownDevice() {
        assertArrayEquals(nominalDefault, AncProfileLib.resolve(unknownDevice, null))
        assertArrayEquals(nominalDefault, AncProfileLib.resolve(null, null))
    }

    /** 回归前提：本缺陷只可能出现在「档案 ≠ 名义顺序」的型号上。 */
    @Test
    fun knownProfile_differsFromNominalDefault() {
        assertNotEquals(
            "该型号档案若与名义顺序一致，此回归测试即失去意义",
            nominalDefault.toList(), knownProfile.toList()
        )
        assertEquals(nominalDefault.size, knownProfile.size)
    }

    // ==================== 残留判定 ====================

    /** 主判定：重建结果恰为名义顺序、而档案不是 → 判定为旧版残留（应清除）。 */
    @Test
    fun stalePartialCustom_detectedWhenRebuiltEqualsNominalDefault() {
        assertTrue(AncProfileLib.isStalePartialCustom(nominalDefault.copyOf(), knownProfile))
    }

    /**
     * 反向护栏：档案本身即名义顺序的型号上，用户真心想要名义顺序是正确的，
     * **绝不能**判定成残留并清除其设置。
     */
    @Test
    fun stalePartialCustom_notDetectedWhenProfileEqualsNominalDefault() {
        assertFalse(AncProfileLib.isStalePartialCustom(nominalDefault.copyOf(), nominalDefault.copyOf()))
        assertFalse(AncProfileLib.isStalePartialCustom(nominalDefault.copyOf(), AncProfileLib.resolve(unknownDevice, null)))
    }

    /** 用户确实做过完整自定义（结果 ≠ 名义顺序）→ 不得清除。 */
    @Test
    fun stalePartialCustom_notDetectedWhenUserCustomized() {
        val intentional = knownProfile.copyOf()
        assertFalse(AncProfileLib.isStalePartialCustom(intentional, knownProfile))

        val another = nominalDefault.copyOf().also { it[0] = nominalDefault[it.size - 1] }
        if (!another.contentEquals(nominalDefault)) {
            assertFalse(AncProfileLib.isStalePartialCustom(another, knownProfile))
        }
    }

    // ==================== 抗风开关可用性（详情页面板） ====================

    /** 降噪档可开抗风；抗风档仍要可用，否则用户关不掉它。 */
    @Test
    fun windSwitch_availableOnAncAndWindModes() {
        assertTrue(AncProfileLib.windSwitchAvailable(AncProfileLib.UI_MODE_ANC))
        assertTrue(AncProfileLib.windSwitchAvailable(AncProfileLib.UI_MODE_WIND))
    }

    /** 关闭/透传/自适应/直播/未知档位 -> 抗风自动失效，开关整块隐藏。 */
    @Test
    fun windSwitch_unavailableOnOtherModes() {
        for (m in intArrayOf(0, 2, 4, 5, -1)) {
            assertFalse("mode=" + m + " 不该出现抗风开关", AncProfileLib.windSwitchAvailable(m))
        }
    }

    /** 档位号与「0 关 / 1 降噪 / 2 透传 / 3 抗风」的公开顺序绑定，防漂移。 */
    @Test
    fun modeIndices_matchAnnouncedOrder() {
        assertEquals(1, AncProfileLib.UI_MODE_ANC)
        assertEquals(3, AncProfileLib.UI_MODE_WIND)
        assertEquals(4, AncProfileLib.BASIC_UI_MODES.size)
        assertTrue(AncProfileLib.BASIC_UI_MODES.contains(AncProfileLib.UI_MODE_ANC))
        assertTrue(AncProfileLib.BASIC_UI_MODES.contains(AncProfileLib.UI_MODE_WIND))
    }

    // ==================== 空间音频代理（官方 Spatializer 联动） ====================

    /** 系统判定优先；系统不可判定（null）时退回耳机端 GAIA 状态，不能把开关显示成关。 */
    @Test
    fun spatialChecked_prefersSystemThenFallsBackToGaia() {
        assertTrue(AncProfileLib.spatialChecked(systemOn = true, gaiaOn = false))
        assertFalse(AncProfileLib.spatialChecked(systemOn = false, gaiaOn = true))
        assertTrue(AncProfileLib.spatialChecked(systemOn = null, gaiaOn = true))
        assertFalse(AncProfileLib.spatialChecked(systemOn = null, gaiaOn = false))
    }

    /** 三档回读：只有「全方位」算官方头部追踪开启。 */
    @Test
    fun headTrackingOn_onlyFullMode() {
        assertFalse(AncProfileLib.headTrackingOn(0))
        assertFalse(AncProfileLib.headTrackingOn(1))
        assertTrue(AncProfileLib.headTrackingOn(2))
    }

    /** 通知栏与主界面共用的按钮顺序：降噪 / 关闭 / 通透 打头，且每个档位只出现一次。 */
    @Test
    fun ancUiOrder_startsWithAncOffTransparency() {
        assertEquals(listOf(1, 0, 2), AncProfileLib.ANC_UI_ORDER.take(3).toList())
        // 六档都要在表里，且不重复（漏一档会表现为「某档按钮静默消失」）
        assertEquals((0..5).toList(), AncProfileLib.ANC_UI_ORDER.sorted().toList())
        assertEquals(6, AncProfileLib.ANC_UI_ORDER.distinct().size)
    }

    /** 不变量：空间音频开着时读不到「关闭追踪」，除非用户手动关过。 */
    @Test
    fun displayTrackingMode_neverClosedWhileSpatialOn() {
        // 空间音频开 + 耳机端报 0 档 = 补档途中的瞬态，读作 30°，不闪「关闭」
        assertEquals(1, AncProfileLib.displayTrackingMode(
                spatialOn = true, gaiaTracking = 0, userClosedTracking = false))
        // 用户手动关过的，如实读作关闭
        assertEquals(0, AncProfileLib.displayTrackingMode(
                spatialOn = true, gaiaTracking = 0, userClosedTracking = true))
        // 已有档位照读
        assertEquals(2, AncProfileLib.displayTrackingMode(
                spatialOn = true, gaiaTracking = 2, userClosedTracking = false))
        // 空间音频关 / 档位未知：不适用
        assertEquals(-1, AncProfileLib.displayTrackingMode(
                spatialOn = false, gaiaTracking = 0, userClosedTracking = false))
        assertEquals(-1, AncProfileLib.displayTrackingMode(
                spatialOn = true, gaiaTracking = -1, userClosedTracking = false))
    }

    /** 只有「本来有档位、用户自己切到关闭」才算手动关；本来就关着再点关闭不算。 */
    @Test
    fun isManualTrackingClose_onlyWhenSwitchedFromNonZero() {
        // 从 30° / 全方位切到关闭 = 用户手动关
        assertTrue(AncProfileLib.isManualTrackingClose(pickedMode = 0, currentMode = 1))
        assertTrue(AncProfileLib.isManualTrackingClose(pickedMode = 0, currentMode = 2))
        // 本来就关着（或状态未知）时点关闭：不算用户表态
        assertFalse(AncProfileLib.isManualTrackingClose(pickedMode = 0, currentMode = 0))
        // 点非 0 档从来不算「关」
        assertFalse(AncProfileLib.isManualTrackingClose(pickedMode = 1, currentMode = 2))
        assertFalse(AncProfileLib.isManualTrackingClose(pickedMode = 2, currentMode = 1))
    }

    /** 打开空间音频时耳机端报「关闭追踪」要补档；用户手动关过则不动。 */
    @Test
    fun correctedTrackingMode_defaultsToThirtyDegrees() {
        // 空间音频开着 + 耳机端 0 档 + 用户没手动关过 = 补 30°
        assertEquals(1, AncProfileLib.correctedTrackingMode(
                spatialOn = true, gaiaTracking = 0, userClosedTracking = false))
        // 用户在软件内自己关掉的，尊重不动
        assertNull(AncProfileLib.correctedTrackingMode(
                spatialOn = true, gaiaTracking = 0, userClosedTracking = true))
        // 空间音频没开，或耳机端本来就有档位：都不动
        assertNull(AncProfileLib.correctedTrackingMode(
                spatialOn = false, gaiaTracking = 0, userClosedTracking = false))
        assertNull(AncProfileLib.correctedTrackingMode(
                spatialOn = true, gaiaTracking = 1, userClosedTracking = false))
        assertNull(AncProfileLib.correctedTrackingMode(
                spatialOn = true, gaiaTracking = 2, userClosedTracking = false))
    }

    /** 官方两个开关 -> 我们三档：空间音频关 = 关追踪，开 + 头追关 = 30°，两个都开 = 全方位。 */
    @Test
    fun trackingModeFor_mapsOfficialSwitchesToThreeLevels() {
        assertEquals(0, AncProfileLib.trackingModeFor(spatialOn = false, headTrackingOn = false))
        assertEquals(0, AncProfileLib.trackingModeFor(spatialOn = false, headTrackingOn = true))
        assertEquals(1, AncProfileLib.trackingModeFor(spatialOn = true, headTrackingOn = false))
        assertEquals(2, AncProfileLib.trackingModeFor(spatialOn = true, headTrackingOn = true))
    }

}
