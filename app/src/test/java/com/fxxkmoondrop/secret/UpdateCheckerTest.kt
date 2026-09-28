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
    // ── 3.2.12: 预发布说明裁剪（「更新日志」与「检查更新」分工后的配套）──

    @Test
    fun `预发布说明裁掉构建元信息，只留更新日志`() {
        // CI 生成的预发布说明前半段是提交 / 触发 / 签名这些构建信息，读者不关心
        val body = listOf(
                "## 自动构建 `ci-203`",
                "",
                "- 提交：`ebf858c5`",
                "- 触发：手动（Run workflow）",
                "- 性质：**预发布**，可能包含尚未验证的改动",
                "- 签名：CN=FxxkMoondrop",
                "",
                "## 更新日志",
                "",
                "> 自上个正式版 `331-3.2.11` 以来的改动。",
                "",
                "### 3.2.12 (332)",
                "",
                "- 新增均衡器。"
        ).joinToString("\n")
        val out = UpdateChecker.stripBuildMeta(body)!!
        assertTrue("不应再出现构建元信息，实际=" + out, !out.contains("签名："))
        assertTrue("不应再出现提交号", !out.contains("ebf858c5"))
        assertTrue("应保留版本段落", out.contains("3.2.12 (332)"))
        assertTrue("应保留正文条目", out.contains("新增均衡器"))
    }

    @Test
    fun `旧格式的 CI 模板没有更新日志段，原样返回而不是清空`() {
        // 3.2.11 之前的 ci-N 说明只有 CI 模板，裁不出 `## 更新日志`。
        // 这时必须原样返回 —— 返回 null 会让「检查更新」显示「这一版没有提供更新说明」，
        // 但实际是有一段模板文字的，说法不符。
        val legacy = "## 自动构建发布（CI #199）\n\n- 提交：`a1750e0`\n- 触发：push main"
        val out = UpdateChecker.stripBuildMeta(legacy)!!
        assertTrue("应原样带回模板内容", out.contains("CI #199"))
    }

    @Test
    fun `裁剪对空输入返回 null`() {
        assertNull(UpdateChecker.stripBuildMeta(null))
        assertNull(UpdateChecker.stripBuildMeta(""))
        assertNull(UpdateChecker.stripBuildMeta("   \n  "))
        // 只有标记、标记后没有内容 -> 没有可展示的东西
        assertNull(UpdateChecker.stripBuildMeta("## 更新日志"))
    }
    // ── 3.2.12: atom 兜底（「更新日志」不能只靠 API，它会被限流打满）──

    @Test
    fun `HTML 正文转成纯文本，列表项之间不留空行`() {
        val htmlBody = "<h2>标题</h2>\n<ul>\n<li>第一项</li>\n<li>第二项</li>\n</ul>\n<p>段落</p>"
        val out = UpdateChecker.htmlToText(htmlBody)!!
        assertTrue("标题应还原成 markdown 标题，实际=" + out, out.contains("## 标题"))
        assertTrue("列表项应还原成 -，实际=" + out, out.contains("- 第一项"))
        // 关键：`</li>\n<li>` 不能变成空行，否则短列表被拆得稀稀落落
        assertTrue("列表项之间不应有空行，实际=\n" + out,
                !out.contains("- 第一项\n\n- 第二项"))
        assertTrue("段落应保留", out.contains("段落"))
        assertTrue("不应残留标签", !out.contains("<"))
    }

    @Test
    fun `实体解码时 amp 最后处理，避免二次解码`() {
        // `&amp;lt;` 是「字面量 &lt;」，只应解成 `&lt;`。
        // 若先把 &amp; 解掉就会得到 `<`，等于凭空造出一个标签
        assertEquals("&lt;", UpdateChecker.decodeEntities("&amp;lt;"))
        assertEquals("<", UpdateChecker.decodeEntities("&lt;"))
        assertEquals("a & b", UpdateChecker.decodeEntities("a &amp; b"))
    }

}
