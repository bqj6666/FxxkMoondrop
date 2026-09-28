package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateChecker 的纯逻辑测试。
 *
 * 只覆盖不依赖 Android 运行时的部分：tag 解析、commit 提取、atom 解析、下载链接构造。
 * 网络与 JSON 解析不在此测（依赖 Android 运行时，属实测范围，见设计文档 §10）。
 */
class UpdateCheckerTest {

    // ── tag 解析：正式版 ────────────────────────────────────────────

    @Test
    fun `正式版 tag 解析出版本号`() {
        val r = UpdateChecker.parseStableTag("330-3.2.10")
        assertEquals(330, r?.first)
        assertEquals("3.2.10", r?.second)
    }

    @Test
    fun `三位版本号同样解析`() {
        val r = UpdateChecker.parseStableTag("329-3.2.9")
        assertEquals(329, r?.first)
        assertEquals("3.2.9", r?.second)
    }

    @Test
    fun `历史四位版本号可解析`() {
        val r = UpdateChecker.parseStableTag("288-alpha2.53")
        assertEquals(288, r?.first)
        assertEquals("alpha2.53", r?.second)
    }

    // ── tag 解析：非正式版一律不认 ──────────────────────────────────

    @Test
    fun `ci 预发布 tag 不算正式版`() {
        assertNull(UpdateChecker.parseStableTag("ci-199"))
    }

    @Test
    fun `空与畸形 tag 返回 null`() {
        assertNull(UpdateChecker.parseStableTag(null))
        assertNull(UpdateChecker.parseStableTag(""))
        assertNull(UpdateChecker.parseStableTag("3.2.10"))      // 没有 code-
        assertNull(UpdateChecker.parseStableTag("abc-3.2.10"))  // code 不是数字
        assertNull(UpdateChecker.parseStableTag("330"))         // 没有版本名
    }

    // ── commit 提取（预发布通道用）──────────────────────────────────

    @Test
    fun `从 CI release body 提取 commit`() {
        val body = "## 自动构建发布（CI #199）\n\n- 提交：<code>a1750e0dc9381ae64aded5766c41418335059c30</code>\n- 触发：push main"
        assertEquals("a1750e0dc9381ae64aded5766c41418335059c30", UpdateChecker.extractCommit(body))
    }

    @Test
    fun `atom 里被 HTML 转义的 commit 也能提取`() {
        val body = "&lt;code&gt;a1750e0dc9381ae64aded5766c41418335059c30&lt;/code&gt;"
        assertEquals("a1750e0dc9381ae64aded5766c41418335059c30", UpdateChecker.extractCommit(body))
    }

    @Test
    fun `英文 commit 措辞同样支持`() {
        val body = "Commit: `deadbeefcafe1234`"
        assertEquals("deadbeefcafe1234", UpdateChecker.extractCommit(body))
    }

    @Test
    fun `无 commit 时返回 null`() {
        assertNull(UpdateChecker.extractCommit(null))
        assertNull(UpdateChecker.extractCommit(""))
        assertNull(UpdateChecker.extractCommit("## 自动构建发布（CI #1）\n\n- 触发：push main"))
    }

    // ── commit 比对 ────────────────────────────────────────────────

    @Test
    fun `commit 相同则不是新版`() {
        val sha = "a1750e0dc9381ae64aded5766c41418335059c30"
        assertFalse(UpdateChecker.isCommitNewer(sha, sha))
        assertFalse(UpdateChecker.isCommitNewer(sha, sha.substring(0, 7)))  // 本地可能只有短 sha
    }

    @Test
    fun `commit 不同则是新版`() {
        assertTrue(UpdateChecker.isCommitNewer(
                "a1750e0dc9381ae64aded5766c41418335059c30",
                "200cfe43cb2e893a4fafcad33af1a78a16f415c0"))
    }

    @Test
    fun `任一侧缺失时不敢断言有新版本`() {
        // 本地拿不到 commit（例如 version-control-info 缺失）时保守判「无新版」，
        // 宁可漏报也不要误报——误报会让用户白白下载一次。
        assertFalse(UpdateChecker.isCommitNewer(null, "a1750e0"))
        assertFalse(UpdateChecker.isCommitNewer("a1750e0", null))
        assertFalse(UpdateChecker.isCommitNewer("a1750e0", ""))
    }

    // ── 下载链接构造（不请求 API）──────────────────────────────────

    @Test
    fun `下载链接按 tag 直接构造`() {
        assertEquals(
                "https://github.com/bqj6666/FxxkMoondrop/releases/download/330-3.2.10/app-release.apk",
                UpdateChecker.downloadUrl("330-3.2.10"))
    }

    @Test
    fun `预发布 tag 同样能构造下载链接`() {
        assertEquals(
                "https://github.com/bqj6666/FxxkMoondrop/releases/download/ci-199/app-release.apk",
                UpdateChecker.downloadUrl("ci-199"))
    }

    // ── atom 兜底解析 ──────────────────────────────────────────────

    private val atomSample = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>FxxkMoondrop CI build</title>
            <id>tag:github.com,2008:Repository/1339943709/ci-199</id>
            <link rel="alternate" type="text/html" href="https://github.com/bqj6666/FxxkMoondrop/releases/tag/ci-199"/>
            <content type="html">&lt;code&gt;a1750e0dc9381ae64aded5766c41418335059c30&lt;/code&gt;</content>
          </entry>
          <entry>
            <title>FxxkMoondrop 330-3.2.10</title>
            <id>tag:github.com,2008:Repository/1339943709/330-3.2.10</id>
            <link rel="alternate" type="text/html" href="https://github.com/bqj6666/FxxkMoondrop/releases/tag/330-3.2.10"/>
            <content type="html">&lt;h2&gt;更新日志&lt;/h2&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `正式版通道在 atom 里跳过 ci 取最新正式版`() {
        // 实测风险：CI 预发布会刷屏，正式版可能被挤出前 10 —— 因此必须能跳过 ci-*。
        val r = UpdateChecker.parseAtom(atomSample, prerelease = false)
        assertEquals("330-3.2.10", r?.tag)
    }

    @Test
    fun `预发布通道在 atom 里取第一条（通常就是最新 ci）`() {
        val r = UpdateChecker.parseAtom(atomSample, prerelease = true)
        assertEquals("ci-199", r?.tag)
    }

    @Test
    fun `atom 为空或无可用条目时返回 null`() {
        assertNull(UpdateChecker.parseAtom(null, prerelease = false))
        assertNull(UpdateChecker.parseAtom("", prerelease = false))
        assertNull(UpdateChecker.parseAtom("<feed></feed>", prerelease = true))
        // 正式版通道：只有 ci 条目时不该硬塞一个预发布给用户
        val onlyCi = atomSample.substringBefore("<entry>") +
                "<entry><id>tag:github.com,2008:Repository/1/ci-1</id></entry></feed>"
        assertNull(UpdateChecker.parseAtom(onlyCi, prerelease = false))
    }
}
