package com.fxxkmoondrop.secret.ui.miuix

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * [MiuixHostFragment] 的构造约束测试。
 *
 * ## 为什么需要这个测试
 *
 * 3.2.13 出过一次真实崩溃：用户从 Miuix 切回 Material 时闪退。
 * 根因是 `MiuixHostFragment` 写成了带参构造
 * （`class MiuixHostFragment(private val screen: Screen)`）：
 *
 * ```
 * Unable to instantiate fragment sp0: could not find Fragment constructor
 * Caused by: java.lang.NoSuchMethodException: sp0.<init> []
 * ```
 *
 * 首次创建由 `MainActivity` 直接 `new`，没问题；但 `recreate()` 之后
 * `FragmentManager` 要**自己**重建 Fragment，它只认**无参构造**。
 * 页面参数必须走 `arguments`，否则必崩。
 *
 * 这个约束靠 code review 记不住，用测试钉死。
 */
class MiuixHostFragmentConstructorTest {

    /**
     * 必须存在**公开无参构造**。
     *
     * FragmentManager 的反射路径是 `clazz.getConstructor().newInstance()`，
     * 找不到无参构造就直接抛 `NoSuchMethodException: <init> []`。
     */
    @Test
    fun `必须有公开无参构造`() {
        val ctor: Constructor<*> = MiuixHostFragment::class.java.getConstructor()
        assertNotNull("FragmentManager 需要无参构造来重建 Fragment", ctor)
    }

    /** 无参构造必须 public，否则 FragmentManager 反射拿不到。 */
    @Test
    fun `无参构造必须 public`() {
        val ctor = MiuixHostFragment::class.java.getDeclaredConstructor()
        assertTrue(
            "无参构造必须是 public，FragmentManager 反射要求可访问",
            Modifier.isPublic(ctor.modifiers),
        )
    }

    /**
     * 页面参数**不能**是构造参数。
     *
     * 这是本测试的核心：防止有人为了「传参方便」改回带参构造。
     */
    @Test
    fun `页面参数不能走构造参数`() {
        val ctors = MiuixHostFragment::class.java.constructors
        assertTrue(
            "只应存在无参构造；带参构造会让 recreate() 崩溃。" +
                    "页面请用 newInstance(screen) + arguments。当前构造：" +
                    ctors.joinToString { c -> c.parameterTypes.joinToString(prefix = "(") + ")" },
            ctors.size == 1 && ctors[0].parameterCount == 0,
        )
    }

    /** 每个 Screen 常量都能被解析回来（arguments 存的是名字）。 */
    @Test
    fun `Screen 枚举名可往返`() {
        for (s in MiuixHostFragment.Screen.entries) {
            assertNotNull("Screen.$s 应能 valueOf 回来", MiuixHostFragment.Screen.valueOf(s.name))
        }
    }
}
