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
