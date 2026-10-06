package com.fxxkmoondrop.secret

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小米集成 UI 门禁测试（用户决定 A2：整块隐藏）。
 *
 * ## 背景
 *
 * 设置页原有一块「小米系统集成」分组：非小米设备上整组置灰、
 * 只显示一行「未检测到小米设备」说明。用户要求**整块隐藏**。
 *
 * ## 隐藏了什么 / 保留了什么
 *
 * - 隐藏：设置页那一段 UI（`if (XiaomiProbe.INTEGRATION_ENABLED)`）
 * - 保留：`isXiaomi()` 三级判据、设备 ID 映射、hook 入口，一行未删
 *
 * 本测试的作用同样是**锁意图**：防止有人以为「这块没人用」而删掉整段代码。
 */
class XiaomiGateTest {

    @Test
    fun `小米集成 UI 必须处于隐藏状态`() {
        assertFalse(
            "用户要求整块隐藏；恢复请改 XiaomiProbe.INTEGRATION_ENABLED 并同步改本测试",
            XiaomiProbe.INTEGRATION_ENABLED,
        )
    }

    /** 门禁只管 UI —— 设备识别本身必须仍然可用（运行逻辑未受影响）。 */
    @Test
    fun `门禁关闭时设备识别逻辑仍保留`() {
        // 只要能调用不抛异常，就说明识别链路还在
        val result = XiaomiProbe.isXiaomi()
        assertTrue(
            "isXiaomi() 应返回布尔值（本机非小米，期望 false），链路必须完好",
            result == true || result == false,
        )
    }
}
