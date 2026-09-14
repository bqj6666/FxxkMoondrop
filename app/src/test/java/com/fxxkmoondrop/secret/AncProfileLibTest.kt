package com.fxxkmoondrop.secret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
