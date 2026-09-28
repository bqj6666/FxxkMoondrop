package com.fxxkmoondrop.secret

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 联网检查更新（3.2.11 新增）。
 *
 * 设计文档：`docs/superpowers/specs/2026-09-28-github-update-check-design.md`
 *
 * 职责边界：只负责「拿到远端最新版信息并与本地比较」。不碰 UI、不发通知、
 * 不写开关以外的 SP。UI 侧只消费它返回的结果。
 *
 * 数据源与降级顺序（设计文档 §5.2）：
 *   1. api.github.com  —— 主路径，精准，但未认证限流 60 次/小时/IP（实测会撞到 403）
 *   2. releases.atom   —— 兜底，不占 API 配额（实测 API 403 期间仍 200）
 *   3. null            —— 检查失败，UI 明确告知「无法获取」（3.2.11 起不再有内置日志兜底）
 *
 * 零新增依赖：只用系统自带的 HttpURLConnection 与 org.json。
 *
 * 硬要求：**联网失败绝不影响任何现有功能** —— 所有异常一律吞掉，
 * 不弹提示、不写错误通知、不阻塞 UI。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val REPO = "https://github.com/bqj6666/FxxkMoondrop"
    private const val API_LATEST = "https://api.github.com/repos/bqj6666/FxxkMoondrop/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/bqj6666/FxxkMoondrop/releases?per_page=30"
    private const val ATOM = "https://github.com/bqj6666/FxxkMoondrop/releases.atom"

    private const val ASSET = "app-release.apk"

    /** 检查结果缓存时长。手动检查（force）绕过。 */
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    private const val SP = "cfg"
    private const val KEY_ENABLED = "net_update_check"
    private const val KEY_CHANNEL = "update_channel"
    private const val KEY_CACHE_AT = "net_update_cache_at"
    private const val KEY_CACHE_TAG = "net_update_cache_tag"
    private const val KEY_CACHE_BODY = "net_update_cache_body"
    private const val KEY_CACHE_CHANNEL = "net_update_cache_channel"

    enum class Channel { STABLE, PRERELEASE }

    data class RemoteRelease(val tag: String, val body: String?)

    data class UpdateInfo(
            val tag: String,
            val versionName: String?,
            val versionCode: Int?,
            val commitSha: String?,
            val notes: String?,
            val downloadUrl: String,
            val hasUpdate: Boolean,
            val fromCache: Boolean = false)

    sealed class Result {
        /** 联网开关关闭：未发起任何请求。 */
        object Disabled : Result()

        /**
         * 已是最新版本。
         *
         * [notes] 是该版本的联网更新说明 —— 已是最新时远端 latest 就是本机这版，
         * 它的 body 正是「本版改了什么」。拿不到时为 null（如预发布通道，ci-N 的
         * body 是 CI 模板，不能当日志展示）。
         */
        data class UpToDate(val currentLabel: String,
                            val notes: String? = null) : Result()

        /** 有新版本。 */
        data class Available(val info: UpdateInfo) : Result()

        /** 检查失败（离线 / 限流 / 解析失败）。UI 端自行决定是静默还是提示。 */
        object Failed : Result()
    }

    // ── 开关与通道（与设置页、引导页共用同一份 SP）──────────────────

    /** 联网检查更新是否开启。**默认关**（设计文档 §9.1 记录了该取舍的代价）。 */
    @JvmStatic
    fun isEnabled(ctx: Context): Boolean =
            prefs(ctx).getBoolean(KEY_ENABLED, false)

    @JvmStatic
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 更新通道，默认正式版。 */
    @JvmStatic
    fun channel(ctx: Context): Channel =
            if (prefs(ctx).getInt(KEY_CHANNEL, 0) == 1) Channel.PRERELEASE else Channel.STABLE

    @JvmStatic
    fun setChannel(ctx: Context, ch: Channel) {
        prefs(ctx).edit().putInt(KEY_CHANNEL, if (ch == Channel.PRERELEASE) 1 else 0).apply()
    }

    private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ── 纯逻辑（单元测试覆盖，不依赖 Android 运行时）─────────────────

    /**
     * 解析正式版 tag：`330-3.2.10` -> (330, "3.2.10")。
     * 非正式版（`ci-199`、畸形、空）返回 null。
     */
    @JvmStatic
    fun parseStableTag(tag: String?): Pair<Int, String>? {
        if (tag.isNullOrBlank()) return null
        val m = Regex("^(\\d+)-(.+)$").find(tag.trim()) ?: return null
        val code = m.groupValues[1].toIntOrNull() ?: return null
        val name = m.groupValues[2].trim()
        if (name.isEmpty()) return null
        return code to name
    }

    /**
     * 从 release body 里提取构建 commit。
     *
     * CI 模板写的是 `提交：<code>sha</code>`，atom 兜底里是 HTML 转义后的同形内容。
     * 先解码实体、剥掉标签再匹配，两种形态都能吃下。
     */
    @JvmStatic
    fun extractCommit(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val plain = body
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace(Regex("<[^>]*>"), " ")
        Regex("(?i)(?:提交|commit)[^0-9a-fA-F]{0,10}([0-9a-fA-F]{7,40})")
                .find(plain)?.let { return it.groupValues[1].lowercase() }
        // 无标记时退一步找裸的完整 sha
        return Regex("\\b([0-9a-fA-F]{40})\\b").find(plain)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * 远端 commit 是否比本地新。
     *
     * 任一侧缺失时**保守判否** —— 宁可漏报也不误报，误报会让用户白下载一次。
     * 短 sha 视为同一提交（本地可能只拿到前 7 位）。
     */
    @JvmStatic
    fun isCommitNewer(remote: String?, local: String?): Boolean {
        if (remote.isNullOrBlank() || local.isNullOrBlank()) return false
        val r = remote.trim().lowercase()
        val l = local.trim().lowercase()
        if (r == l) return false
        if (r.startsWith(l) || l.startsWith(r)) return false
        return true
    }

    /** APK 下载链接：直接构造，不请求 API（实测 302 跳 CDN，不占配额）。 */
    @JvmStatic
    fun downloadUrl(tag: String): String = "$REPO/releases/download/$tag/$ASSET"

    /**
     * 解析 releases.atom 兜底源。
     *
     * 正式版通道会跳过 `ci-*`：实测 CI 预发布会把正式版挤出前 10 条
     * （`330-3.2.10` 一度被挤到第 6 位），所以不能盲取第一条。
     */
    @JvmStatic
    fun parseAtom(xml: String?, prerelease: Boolean): RemoteRelease? {
        if (xml.isNullOrBlank()) return null
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { it.groupValues[1] }.toList()

        fun tagOf(e: String) =
                Regex("Repository/\\d+/([^<\\s]+)").find(e)?.groupValues?.get(1)

        fun releaseOf(e: String): RemoteRelease? {
            val tag = tagOf(e) ?: return null
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                    .find(e)?.groupValues?.get(1)
            return RemoteRelease(tag, content)
        }

        // 预发布通道**优先**挑非正式版 tag（ci-N）。atom 按时间倒序，
        // 刚发完正式版时第一条就是正式版；不筛的话预发布通道会拿正式版的 commit
        // 去比，得出「有新版本」并给出正式版下载链接 —— 与用户所选通道不符。
        // 一条 ci 都没有时（项目刚起步或 atom 被正式版挤满）回退取第一条，
        // 宁可能力退化，也不要让用户看到「检查失败」。
        if (prerelease) {
            for (e in entries) {
                if (tagOf(e)?.let { parseStableTag(it) == null } == true) {
                    releaseOf(e)?.let { return it }
                }
            }
            return entries.firstOrNull()?.let { releaseOf(it) }
        }

        for (e in entries) {
            val tag = tagOf(e) ?: continue
            if (parseStableTag(tag) == null) continue
            return releaseOf(e)
        }
        return null
    }

    // ── 联网检查（阻塞，必须离开主线程）────────────────────────────

    /**
     * 检查更新。会阻塞，**绝不可在主线程调用**。
     *
     * @param force 用户的显式「检查更新」：绕过 6 小时缓存。
     */
    @JvmStatic
    fun check(ctx: Context, force: Boolean): Result {
        val app = ctx.applicationContext
        if (!isEnabled(app)) return Result.Disabled          // 开关关：不建任何连接

        val ch = channel(app)
        val cached = readCache(app, ch, force)
        val remote = cached ?: run {
            val r = fetchRemote(ch)
            if (r != null) writeCache(app, ch, r)
            r
        } ?: return Result.Failed

        return try {
            evaluate(app, ch, remote, fromCache = cached != null)
        } catch (t: Throwable) {
            Log.d(TAG, "evaluate failed: " + t)
            Result.Failed
        }
    }

    private fun evaluate(app: Context, ch: Channel, remote: RemoteRelease,
                         fromCache: Boolean): Result {
        val url = downloadUrl(remote.tag)
        return when (ch) {
            Channel.STABLE -> {
                val parsed = parseStableTag(remote.tag) ?: return Result.Failed
                val (rCode, rName) = parsed
                val localCode = localVersionCode(app)
                val has = rCode > localCode
                if (!has) return Result.UpToDate(remote.tag, remote.body)
                Result.Available(UpdateInfo(
                        tag = remote.tag,
                        versionName = rName,
                        versionCode = rCode,
                        commitSha = null,
                        notes = remote.body,      // 正式版 body 由 release_notes.py 渲染，含置顶警示
                        downloadUrl = url,
                        hasUpdate = true,
                        fromCache = fromCache))
            }
            Channel.PRERELEASE -> {
                val rSha = extractCommit(remote.body)
                val lSha = localCommit(app)
                if (!isCommitNewer(rSha, lSha)) return Result.UpToDate(remote.tag)
                Result.Available(UpdateInfo(
                        tag = remote.tag,
                        versionName = null,
                        versionCode = null,
                        commitSha = rSha,
                        // ci-N 的 body 是 CI 模板（只有 commit），不能当日志展示
                        notes = null,
                        downloadUrl = url,
                        hasUpdate = true,
                        fromCache = fromCache))
            }
        }
    }

    /**
     * 解析 `/releases/latest` 响应。
     *
     * 抽成纯函数是为了能用**真实抓取的响应**做回归 —— 接口字段名变更会立刻被测出来，
     * 而这类失败在设备上只表现为「检查更新失败」，很难定位。
     */
    @JvmStatic
    fun parseLatestJson(text: String?): RemoteRelease? = try {
        if (text.isNullOrBlank()) null else {
            val o = JSONObject(text)
            val tag = o.optString("tag_name").ifBlank { null }
            if (tag == null) null else RemoteRelease(tag, o.optString("body").ifBlank { null })
        }
    } catch (_: Throwable) { null }

    /**
     * 解析 `/releases` 列表响应，挑出该通道的最新一条。
     *
     * 这里曾经直接取 `arr[0]`，而 **GitHub 把草稿排在最前**（草稿没有发布时间，
     * 排序结果是它们先于所有已发布项）。仓库里只要存在一个很久以前存下的草稿，
     * 预发布通道就会永远取到它：草稿的 body 是空的 -> 提取不到 commit ->
     * `isCommitNewer(null, local)` 保守判否 -> 永远显示「已是最新」。
     * 实测该仓库的 `287-alpha2.52` 草稿正是如此，预发布检查因此完全失效。
     *
     * 所以：草稿一律跳过，再按通道筛 prerelease 标记。
     * @param prerelease true = 预发布通道（只要 prerelease=true 的条目）
     */
    @JvmStatic
    fun parseListJson(text: String?, prerelease: Boolean = true): RemoteRelease? = try {
        if (text.isNullOrBlank()) null else {
            val arr = JSONArray(text)
            var found: RemoteRelease? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft", false)) continue
                if (o.optBoolean("prerelease", false) != prerelease) continue
                val tag = o.optString("tag_name").ifBlank { null } ?: continue
                found = RemoteRelease(tag, o.optString("body").ifBlank { null })
                break
            }
            found
        }
    } catch (_: Throwable) { null }

    /** API 主路径 -> atom 兜底 -> null。 */
    private fun fetchRemote(ch: Channel): RemoteRelease? {
        apiRelease(ch)?.let { return it }
        Log.d(TAG, "api unavailable, fallback to atom")
        return atomRelease(ch)
    }

    private fun apiRelease(ch: Channel): RemoteRelease? = try {
        val url = if (ch == Channel.STABLE) API_LATEST else API_LIST
        val text = httpGet(url)
        if (ch == Channel.STABLE) parseLatestJson(text)
        else parseListJson(text, prerelease = true)
    } catch (t: Throwable) {
        Log.d(TAG, "api fetch failed: " + t)
        null
    }

    private fun atomRelease(ch: Channel): RemoteRelease? = try {
        parseAtom(httpGet(ATOM), ch == Channel.PRERELEASE)
    } catch (t: Throwable) {
        Log.d(TAG, "atom fetch failed: " + t)
        null
    }

    /** 一次 GET。任何异常都返回 null —— 联网失败不允许影响其它功能。 */
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // 不带 token、不带 cookie、不发送任何设备信息（设计文档 §8）
                setRequestProperty("Accept", "application/vnd.github+json, application/atom+xml")
                setRequestProperty("User-Agent", "FxxkMoondrop")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "http $code for $url")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "http failed: " + t)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    // ── 缓存 ──────────────────────────────────────────────────────

    private fun readCache(app: Context, ch: Channel, force: Boolean): RemoteRelease? {
        if (force) return null
        val p = prefs(app)
        if (p.getInt(KEY_CACHE_CHANNEL, -1) != (if (ch == Channel.PRERELEASE) 1 else 0)) return null
        val at = p.getLong(KEY_CACHE_AT, 0L)
        if (android.os.SystemClock.elapsedRealtime() - at > TTL_MS) return null
        val tag = p.getString(KEY_CACHE_TAG, null) ?: return null
        return RemoteRelease(tag, p.getString(KEY_CACHE_BODY, null))
    }

    private fun writeCache(app: Context, ch: Channel, r: RemoteRelease) {
        try {
            prefs(app).edit()
                    .putLong(KEY_CACHE_AT, android.os.SystemClock.elapsedRealtime())
                    .putInt(KEY_CACHE_CHANNEL, if (ch == Channel.PRERELEASE) 1 else 0)
                    .putString(KEY_CACHE_TAG, r.tag)
                    .putString(KEY_CACHE_BODY, r.body)
                    .apply()
        } catch (_: Throwable) { }
    }

    // ── 本地版本信息 ───────────────────────────────────────────────

    private fun localVersionCode(app: Context): Int = try {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0).versionCode
    } catch (_: Throwable) {
        Int.MAX_VALUE   // 拿不到就当作最新，避免误报
    }

    /**
     * 本地构建 commit：读 APK 内的 `META-INF/version-control-info.textproto`。
     *
     * 已知局限：本地增量构建时 Gradle 可能跳过 version-control-info 的生成，
     * 导致该文件滞后一次提交，预发布通道可能误报「有新版本」。CI 全新构建准确。
     */
    private fun localCommit(app: Context): String? = try {
        val src = app.packageManager.getApplicationInfo(app.packageName, 0).sourceDir
        java.util.zip.ZipFile(src).use { z ->
            val e = z.getEntry("META-INF/version-control-info.textproto") ?: return null
            val text = z.getInputStream(e).bufferedReader().use { it.readText() }
            Regex("revision:\\s*\"([0-9a-fA-F]+)\"").find(text)?.groupValues?.get(1)?.lowercase()
        }
    } catch (t: Throwable) {
        Log.d(TAG, "localCommit failed: " + t)
        null
    }
}
