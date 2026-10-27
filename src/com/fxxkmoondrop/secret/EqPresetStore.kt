package com.fxxkmoondrop.secret

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地自定义均衡器预设。
 *
 * 与耳机里的三个预设槽位（0A 01 / 0A 02 / 0A 03）完全无关：这里只把当前曲线
 * （预增益 + 每段频点/增益/Q）存成 App 自己的命名快照，支持改名、删除、一键应用。
 * 存储走 SharedPreferences 里的一段 JSON——曲线只有几十个浮点数，没必要上数据库。
 *
 * 编码/解码与命名去重都是纯函数，便于 JVM 单测；只有 list/save/rename/delete 需要 Context。
 */
object EqPresetStore {

    private const val PREFS = "eq_presets"
    private const val KEY = "items"

    const val MAX_NAME_LEN = 24
    const val MAX_COUNT = 50

    data class Preset(
            val id: String,
            val name: String,
            val preampDb: Float,
            val bands: List<MoondropEq.Band>,
            val createdAt: Long)

    // ───────────────────────── 纯函数 ─────────────────────────

    fun encode(list: List<Preset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            val bands = JSONArray()
            p.bands.forEach { b ->
                bands.put(JSONObject()
                        .put("f", b.freq)
                        .put("g", b.gainDb.toDouble())
                        .put("q", b.q.toDouble()))
            }
            arr.put(JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("preamp", p.preampDb.toDouble())
                    .put("bands", bands)
                    .put("at", p.createdAt))
        }
        return arr.toString()
    }

    /** 任何一段坏数据都整体丢弃该条，绝不让一条烂记录毁掉整个预设表。 */
    fun decode(json: String?): List<Preset> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val bandsJson = o.optJSONArray("bands") ?: return@mapNotNull null
                val bands = (0 until bandsJson.length()).mapNotNull { j ->
                    val b = bandsJson.optJSONObject(j) ?: return@mapNotNull null
                    MoondropEq.Band(b.optInt("f", 1000),
                            b.optDouble("g", 0.0).toFloat(),
                            b.optDouble("q", 1.0).toFloat())
                }
                if (bands.isEmpty()) return@mapNotNull null
                Preset(o.optString("id", ""), o.optString("name", ""),
                        o.optDouble("preamp", 0.0).toFloat(), bands, o.optLong("at", 0L))
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 名字去重：同名不覆盖，改成「名字 (2)」「名字 (3)」——用户存两次同样的曲线时，
     * 丢一份比多一份更让人恼火。
     */
    fun uniqueName(want: String, taken: Collection<String>): String {
        val base = want.trim().ifBlank { "我的预设" }.take(MAX_NAME_LEN)
        if (taken.none { it.equals(base, ignoreCase = true) }) return base
        var n = 2
        while (n < 100) {
            val suffix = " ($n)"
            val head = base.take((MAX_NAME_LEN - suffix.length).coerceAtLeast(1))
            val candidate = head + suffix
            if (taken.none { it.equals(candidate, ignoreCase = true) }) return candidate
            n++
        }
        return base
    }

    fun fromProfile(name: String, id: String, pr: MoondropEq.Profile, now: Long): Preset =
            Preset(id, name, pr.preampDb, pr.bands.map { MoondropEq.Band(it.freq, it.gainDb, it.q) }, now)

    /** 每次都给一份深拷贝：编辑器里拖滑块会直接改 Band 对象，共享引用会污染存档。 */
    fun toProfile(p: Preset): MoondropEq.Profile =
            MoondropEq.Profile(p.preampDb,
                    p.bands.map { MoondropEq.Band(it.freq, it.gainDb, it.q) }.toMutableList())

    // ───────────────────────── 存储 ─────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Preset> = decode(prefs(ctx).getString(KEY, null))

    fun save(ctx: Context, name: String, pr: MoondropEq.Profile): Preset? {
        val cur = list(ctx)
        if (cur.size >= MAX_COUNT) return null
        val now = System.currentTimeMillis()
        val p = fromProfile(uniqueName(name, cur.map { it.name }),
                UUID.randomUUID().toString(), pr, now)
        write(ctx, cur + p)
        return p
    }

    fun rename(ctx: Context, id: String, name: String): Boolean {
        val cur = list(ctx).toMutableList()
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) return false
        val others = cur.filterIndexed { k, _ -> k != i }.map { it.name }
        cur[i] = cur[i].copy(name = uniqueName(name, others))
        write(ctx, cur)
        return true
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, list(ctx).filterNot { it.id == id })
    }

    private fun write(ctx: Context, list: List<Preset>) {
        prefs(ctx).edit().putString(KEY, encode(list)).apply()
    }
}
