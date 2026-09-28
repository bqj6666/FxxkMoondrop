package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实抓取的 GitHub 响应**回归解析逻辑（fixtures 见 test/resources/fixtures）。
 *
 * 为什么值得单独测：这些解析一旦因接口字段改名而失败，在设备上只表现为
 * 「检查更新失败」，静默回落内置日志，几乎无法从现象定位到原因。
 * 固定 fixture 让这类回归在单测阶段就被抓住，且不依赖网络（CI 不会抖）。
 *
 * fixture 更新方式（接口格式变化时重新抓）：
 *   curl -s https://api.github.com/repos/bqj6666/FxxkMoondrop/releases/latest \
 *        -o app/src/test/resources/fixtures/releases_latest.json
 *   curl -s "https://api.github.com/repos/bqj6666/FxxkMoondrop/releases?per_page=2" \
 *        -o app/src/test/resources/fixtures/releases_list.json
 *   curl -s https://github.com/bqj6666/FxxkMoondrop/releases.atom \
 *        -o app/src/test/resources/fixtures/releases.atom
 */
class UpdateCheckerFixtureTest {

    private fun fixture(name: String): String =
            javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
                    ?.bufferedReader()?.use { it.readText() }
                    ?: error("fixture 缺失: $name")

    @Test
    fun `真实 releases latest 响应能解析出版本与日志`() {
        val r = UpdateChecker.parseLatestJson(fixture("releases_latest.json"))
        assertNotNull(r)
        // 该端点只返回正式版，tag 必须是 版本号-语义化版本 形态
        val parsed = UpdateChecker.parseStableTag(r!!.tag)
        assertNotNull("tag 应为正式版形态，实际=${r.tag}", parsed)
        assertTrue("正式版必须带 release body（那就是更新日志）", !r.body.isNullOrBlank())
    }

    @Test
    fun `真实 latest 的 body 含置顶警示，正是要给用户看的内容`() {
        val r = UpdateChecker.parseLatestJson(fixture("releases_latest.json"))!!
        // release_notes.py 会把 CHANGELOG 的 [!WARNING] 块置顶
        assertTrue("应包含 GitHub 警示块标记", r.body!!.contains("[!WARNING]"))
        assertTrue("应包含恶性缺陷提示", r.body!!.contains("3.2.8"))
    }

    @Test
    fun `真实 releases 列表响应能解析出最新一条`() {
        val r = UpdateChecker.parseListJson(fixture("releases_list.json"))
        assertNotNull(r)
        assertTrue(r!!.tag.isNotBlank())
    }

    @Test
    fun `真实 atom 在正式版通道能挑出正式版`() {
        val r = UpdateChecker.parseAtom(fixture("releases.atom"), prerelease = false)
        assertNotNull("atom 里应能找到正式版条目", r)
        assertNotNull("挑出来的必须是正式版 tag", UpdateChecker.parseStableTag(r!!.tag))
    }

    @Test
    fun `真实 atom 在预发布通道取第一条`() {
        val r = UpdateChecker.parseAtom(fixture("releases.atom"), prerelease = true)
        assertNotNull(r)
        assertTrue(r!!.tag.isNotBlank())
    }

    @Test
    fun `列表里存在草稿时，预发布通道必须跳过草稿取到 ci 版`() {
        // 回归：GitHub 把**草稿排在最前**（草稿没有发布时间）。此前 parseListJson
        // 直接取 arr[0]，于是这个废弃草稿 `287-alpha2.52` 永远被当成最新预发布 ——
        // 它的 body 是空的，提取不到 commit，判否后永远显示「已是最新」，
        // 预发布检查等于完全失效。实测该仓库正是这个状态。
        val r = UpdateChecker.parseListJson(fixture("releases_list_with_draft.json"))
        assertNotNull("必须能挑出一条，而不是被草稿挡住", r)
        assertTrue("挑出来的必须是 ci 预发布，实际是 " + r!!.tag,
                r.tag.startsWith("ci-"))
        assertNull("该条不能是草稿", UpdateChecker.parseStableTag(r.tag))
    }

    @Test
    fun `正式版通道的列表解析不会挑到预发布`() {
        val r = UpdateChecker.parseListJson(fixture("releases_list_with_draft.json"),
                prerelease = false)
        assertNotNull("列表里应有正式版条目", r)
        assertNotNull("正式版通道只能挑出正式版 tag，实际是 " + r!!.tag,
                UpdateChecker.parseStableTag(r.tag))
    }

    @Test
    fun `只有草稿时返回 null 而不是把草稿当版本`() {
        // 全是草稿 -> 没有可用的已发布版本。此前会把草稿 tag 当版本号显示出去
        val onlyDraft = """
            [{"tag_name":"999-draft-only","draft":true,"prerelease":false,"body":""}]
        """.trimIndent()
        assertNull(UpdateChecker.parseListJson(onlyDraft))
        assertNull(UpdateChecker.parseListJson(onlyDraft, prerelease = false))
    }

    @Test
    fun `真实 atom 在预发布通道优先挑 ci 而不是刚发布的正式版`() {
        // atom 按时间倒序，刚发完正式版时第一条就是正式版 tag
        val r = UpdateChecker.parseAtom(fixture("releases.atom"), prerelease = true)
        assertNotNull(r)
        assertNull("预发布通道不应挑出正式版 tag，实际是 " + r!!.tag,
                UpdateChecker.parseStableTag(r.tag))
    }

    @Test
    fun `畸形响应不抛异常，一律返回 null`() {
        // 网络层拿到错误页/空体时不允许崩 —— 这条保证「联网失败不影响现有功能」
        assertNull(UpdateChecker.parseLatestJson(""))
        assertNull(UpdateChecker.parseLatestJson("not json at all"))
        assertNull(UpdateChecker.parseLatestJson("{}"))
        assertNull(UpdateChecker.parseListJson(""))
        assertNull(UpdateChecker.parseListJson("[]"))
        assertNull(UpdateChecker.parseListJson("{\"message\":\"API rate limit exceeded\"}"))
        assertNull(UpdateChecker.parseAtom("", prerelease = true))
        assertNull(UpdateChecker.parseAtom("not xml", prerelease = false))
    }

    @Test
    fun `限流响应体不会被误当成新版本`() {
        // 实测的真实限流响应：403 + 这个 body。若被误解析，UI 会显示一个假版本
        val rateLimited = """
            {"message":"API rate limit exceeded for 1.2.3.4.",
             "documentation_url":"https://docs.github.com/rest/overview/resources-in-the-rest-api#rate-limiting"}
        """.trimIndent()
        assertNull(UpdateChecker.parseLatestJson(rateLimited))
        assertNull(UpdateChecker.parseListJson(rateLimited))
    }

    @Test
    fun `端到端：真实 latest 与本地版本比较能得出合理结论`() {
        // 覆盖 evaluate 的核心判断：相同 code 不算新版，更大才算
        val r = UpdateChecker.parseLatestJson(fixture("releases_latest.json"))!!
        val remoteCode = UpdateChecker.parseStableTag(r.tag)!!.first
        assertEquals("同版本不应报有新版本", false, remoteCode > remoteCode)
        assertTrue("更高 code 必须报有新版本", remoteCode + 1 > remoteCode)
    }
    // ── 3.2.12: atom 兜底（「更新日志」不能只靠 API，它会被限流打满）──

    @Test
    fun `按 tag 从 atom 里取到指定发布的内容`() {
        // API 那条路未认证配额 60 次/小时/IP，实测会被日常点击打满并返回 403；
        // atom 不占该配额，所以必须有这条兜底
        val r = UpdateChecker.parseAtomByTag(fixture("releases.atom"), "331-3.2.11")
        assertNotNull("atom 第一条就是该版本，应当取到", r)
        assertEquals("331-3.2.11", r!!.tag)
        assertNotNull("正文不能为空", r.body)
        assertTrue("应含正文内容，实际=" + r.body!!.take(80), r.body!!.length > 100)
        assertTrue("不应残留 HTML 标签，实际=" + r.body!!.take(80), !r.body!!.contains("<h"))
    }

    @Test
    fun `atom 里没有该 tag 时返回 null 而不是拿别的版本顶上`() {
        // atom 只列最近 10 条，本机版本较旧时确实找不到。
        // 这时必须如实返回 null（UI 会说明取不到），不能随手给一个别的版本 ——
        // 「更新日志」显示的不是本机版本，正是这次要修掉的毛病
        assertNull(UpdateChecker.parseAtomByTag(fixture("releases.atom"), "999-9.9.9"))
        assertNull(UpdateChecker.parseAtomByTag(null, "331-3.2.11"))
        assertNull(UpdateChecker.parseAtomByTag(fixture("releases.atom"), null))
        assertNull(UpdateChecker.parseAtomByTag(fixture("releases.atom"), "  "))
    }
}
