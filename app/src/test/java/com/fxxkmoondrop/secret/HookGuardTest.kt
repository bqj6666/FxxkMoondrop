package com.fxxkmoondrop.secret

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Executable

/**
 * HookGuard 的 JVM 单测：验证「按目标方法返回类型回落安全值」这张表。
 *
 * 这条逻辑防的是**宿主进程（GMS / Settings / SystemUI）被 Hook 返回值解包异常打死**，
 * 属于崩溃级防线，值得留一个可跑的检查。
 */
class HookGuardTest {

    /** 最小 Chain 桩：本逻辑只读 executable，其余方法不参与。 */
    private class StubChain(private val exe: Executable) : XposedInterface.Chain {
        override fun getExecutable(): Executable = exe
        override fun getThisObject(): Any? = null
        override fun getArgs(): MutableList<Any?> = ArrayList()
        override fun getArg(index: Int): Any? = null
        override fun proceed(): Any? = null
        override fun proceed(args: Array<out Any?>): Any? = null
        override fun proceedWith(value: Any): Any? = null
        override fun proceedWith(value: Any, args: Array<out Any?>): Any? = null
    }

    @Suppress("unused")
    private object Targets {
        @JvmStatic fun boolTarget(): Boolean = true
        @JvmStatic fun intTarget(): Int = 7
        @JvmStatic fun voidTarget() {}
        @JvmStatic fun stringTarget(): String = "x"
    }

    private fun chain(method: String): XposedInterface.Chain =
        StubChain(Targets::class.java.getDeclaredMethod(method))

    /** void 方法：null 就是正确返回值，不能被替换成别的。 */
    @Test
    fun nullSafe_voidStaysNull() {
        assertNull(HookGuard.nullSafe(chain("voidTarget")))
    }

    /** 引用类型：null 合法，原样返回。 */
    @Test
    fun nullSafe_referenceStaysNull() {
        assertNull(HookGuard.nullSafe(chain("stringTarget")))
    }

    /** 基本类型 boolean：必须回落 false，绝不能是 null（否则宿主解包即崩）。 */
    @Test
    fun nullSafe_booleanFallsBackToFalse() {
        assertEquals(false, HookGuard.nullSafe(chain("boolTarget")))
    }

    /** 基本类型 int：回落 0。 */
    @Test
    fun nullSafe_intFallsBackToZero() {
        assertEquals(0, HookGuard.nullSafe(chain("intTarget")))
    }

    /** 类型匹配时原样返回，不做替换。 */
    @Test
    fun safe_matchingValuePassesThrough() {
        assertEquals(true, HookGuard.safe(chain("boolTarget"), true))
        assertEquals(42, HookGuard.safe(chain("intTarget"), 42))
        val s = "keep"
        assertSame(s, HookGuard.safe(chain("stringTarget"), s))
    }

    /** 类型不匹配（如给 int 方法返回 Boolean）：回落该类型默认值，不产出非法返回值。 */
    @Test
    fun safe_typeMismatchFallsBackToDefault() {
        assertEquals(0, HookGuard.safe(chain("intTarget"), true))
        assertEquals(false, HookGuard.safe(chain("boolTarget"), 1))
    }

    /** 拿不到 chain（异常路径）：保守返回 null，不抛异常。 */
    @Test
    fun nullSafe_nullChainIsSafe() {
        assertNull(HookGuard.nullSafe(null))
    }

    /** 构造器（Executable 但非 Method）：无返回值，null 正确。 */
    @Test
    fun nullSafe_constructorIsTreatedAsVoid() {
        val ctor = Targets::class.java.declaredConstructors.first()
        assertNull(HookGuard.nullSafe(StubChain(ctor)))
    }
}
