package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RootShell 的 JVM 单测。
 *
 * 只测**纯逻辑**（候选表内容与 uid 判定），不实际 exec —— 构建机上没有 su，
 * 去跑进程探测只会得到一个不稳定的测试。真正不可单测的是「哪个入口可用」，
 * 那部分靠真机日志里的「入口=」诊断字段确认。
 */
class RootShellTest {

    /** 与 RootShell.NEGATIVE_TTL_MS 对应的测试常量（改实现时同步）。 */
    private val NEGATIVE_TTL = 10_000L

    /** `su` 必须排在最前：保证既有 Magisk / KSU 设备选中的入口不变。 */
    @Test
    fun candidates_preferPlainSu() {
        assertEquals("su", RootShell.CANDIDATES.first())
    }

    /** FolkPatch / APatch 的默认入口必须在内，否则等于没修。 */
    @Test
    fun candidates_includeKpEntryPoints() {
        assertTrue(RootShell.CANDIDATES.contains("kp"))
        assertTrue(RootShell.CANDIDATES.contains("/system/bin/kp"))
    }

    /** 传统路径仍在候选里（回归保护）。 */
    @Test
    fun candidates_keepLegacyPaths() {
        assertTrue(RootShell.CANDIDATES.contains("/system/bin/su"))
        assertTrue(RootShell.CANDIDATES.contains("/system/xbin/su"))
    }

    @Test
    fun candidates_haveNoBlanksOrDuplicates() {
        assertTrue(RootShell.CANDIDATES.none { it.isBlank() })
        assertEquals(RootShell.CANDIDATES.size, RootShell.CANDIDATES.toSet().size)
    }

    /**
     * 关键回归：失败**不能**变成永久结论。
     *
     * 未授权时 root 会整体隐藏（FolkPatch pathhide），App 侧看到的和「没 root」完全一样；
     * 若把负结果永久缓存，用户去管理器授权后仍然一直不可用 —— 这正是 3.0.5 要修的行为。
     * 这里直接验证 TTL 判定本身：刚失败 -> 新鲜（不重复 exec）；TTL 过期 -> 允许重试。
     */
    @Test
    fun negativeResult_hasTtlAndCanBeRetried() {
        val t0 = 1_000_000L
        // TTL 内视为「负结果新鲜」-> 调用方不重复探测
        assertTrue(RootShell.ttlFresh(t0, t0 + 1, NEGATIVE_TTL))
        assertTrue(RootShell.ttlFresh(t0, t0 + 5_000, NEGATIVE_TTL))
        // 边界：正好到 TTL 即视为过期（可重试，自愈路径）
        assertFalse(RootShell.ttlFresh(t0, t0 + NEGATIVE_TTL, NEGATIVE_TTL))
        assertFalse(RootShell.ttlFresh(t0, t0 + NEGATIVE_TTL + 1, NEGATIVE_TTL))
        // 从未失败过（lastAt<=0）绝不算「新鲜」，否则会永久不探测
        assertFalse(RootShell.ttlFresh(0L, t0, NEGATIVE_TTL))
        // 时钟回拨（now < lastAt）不误判为新鲜
        assertFalse(RootShell.ttlFresh(t0, t0 - 1, NEGATIVE_TTL))
    }

    /**
     * 未做过探测时，不得被判成「负结果新鲜」（否则会永久不探测）。
     *
     * 注意：这里**不要**调 `isAvailable()` / `binary()` —— 那会真的去 exec 探测；
     * 构建机上恰好存在 `su` 时还会误判成绿。单测只覆盖纯逻辑，入口可用性由真机日志的
     * 「入口=」字段确认。
     */
    @Test
    fun freshInstance_isNotNegativeFresh() {
        RootShell.reset()
        assertFalse(RootShell.isNegativeFresh())
    }

    /** uid 判定：只认 uid=0，其他一律不认。 */
    @Test
    fun isRootId_acceptsOnlyUidZero() {
        assertTrue(RootShell.isRootId("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(RootShell.isRootId("uid=0(root) gid=0(root) context=u:r:su:s0"))
        assertFalse(RootShell.isRootId("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootShell.isRootId("uid=10123(u0_a123)"))
        assertFalse(RootShell.isRootId(""))
        assertFalse(RootShell.isRootId(null))
    }
}
