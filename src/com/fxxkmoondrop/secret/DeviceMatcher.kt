package com.fxxkmoondrop.secret

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 设备识别门禁。
 *
 * 判定优先顺序（命中任意一项即视为目标设备）：
 *  1. 型号关键字白名单（中英文别名）
 *  2. [AncProfileLib] 的 DC 型号档案关键字
 *  3. **协议指纹学习结果**：曾由 GAIA / 9ECA 服务发现确认过的设备名
 *
 * 第 3 层为「未收录型号」而设。报告显示：型号名不含 `moondrop` / `水月雨` 等
 * 关键字的耳机（例如系统里显示为 `Robin's Earphones` 的知更鸟）会被名字门禁
 * 直接拦死，连接链一步都不会启动。
 *
 * 因此新增 [allowProbe]：名字未命中时仍允许对**当前处于已连接音频 profile 的
 * 设备**发起一次协议指纹探测，由服务发现给出最终裁定 ——
 * 通过则 [learn] 记住该设备名并持久化，此后全链路照常放行；
 * 失败则 [reject] 记入拒绝名单，不再重复探测。
 */
class DeviceMatcher {
    companion object {
        private const val TAG = "DeviceMatcher"

        /** 型号关键字（小写比较，contains 匹配）。新增已知型号在此追加。 */
        private val KNOWN_KEYS = listOf(
            "moondrop",
            "golden ages",
            "goldenages",
            "水月雨",
            "robin",
            "知更鸟",
            "wf-1000xm5",
        )

        private const val PREF_LEARNED = "learned_device_names"
        private const val PREF_REJECTED = "rejected_device_names"
        private const val SEP = "\n"

        /** 曾通过服务发现确认的设备名（小写）。 */
        private var learned: MutableSet<String> = LinkedHashSet()

        /** 已证伪、不再探测的设备名（小写）。 */
        private var rejected: MutableSet<String> = LinkedHashSet()

        @Volatile private var loaded = false

        private fun norm(name: String?): String? =
                name?.lowercase(Locale.ROOT)?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * 名字是否属于目标设备（原语义 + 已学习结果）。
         * 兼容既有调用点，无需改动。
         */
        @JvmStatic
        fun isMoondrop(name: String?): Boolean {
            if (name == null) return false
            val n = name.lowercase(Locale.ROOT)
            if (KNOWN_KEYS.any { n.contains(it) }) return true
            if (AncProfileLib.isMoondrop(name)) return true
            return learned.contains(n.trim())
        }

        /**
         * 是否允许对该设备发起一次协议指纹探测。
         *
         * 调用方只应在「设备当前处于已连接音频 profile」时调用 ——
         * 探测范围由调用方天然收敛为当前活跃的那台音频设备，不会扩散到
         * 配对表里的其他设备（车机、其他耳机、手环）。
         */
        @JvmStatic
        fun allowProbe(name: String?): Boolean {
            if (isMoondrop(name)) return true
            val n = norm(name) ?: return false
            return !rejected.contains(n)
        }

        /** 服务发现确认该设备存在 GAIA / 9ECA 服务：记住它，此后全链路放行。 */
        @JvmStatic
        fun learn(ctx: Context?, name: String?) {
            val n = norm(name) ?: return
            rejected.remove(n)
            if (!learned.add(n)) return
            Log.d(TAG, "learned device name: " + n)
            persist(ctx)
        }

        /** 服务发现确认该设备既无 GAIA 也无 9ECA：记入拒绝名单，避免反复探测。 */
        @JvmStatic
        fun reject(ctx: Context?, name: String?) {
            val n = norm(name) ?: return
            if (learned.contains(n)) return
            if (!rejected.add(n)) return
            Log.d(TAG, "rejected device name: " + n)
            persist(ctx)
        }

        /**
         * 清空「探测失败」名单，允许对未收录型号重新探测一次。
         *
         * 供主界面「刷新状态」按钮调用 —— 用户主动刷新即明确要求重试，
         * 此前若因一次探测失败（如服务发现抖动）被判为不受支持的设备，
         * 会在这里获得重新走一遍协议指纹探测的机会。
         */
        @JvmStatic
        @Synchronized
        fun clearRejected(ctx: Context?) {
            if (rejected.isEmpty()) return
            val n = rejected.size
            rejected.clear()
            persist(ctx)
            Log.d(TAG, "rejected cleared (" + n + " names) -> re-probe allowed")
        }

        /** 启动时从 prefs 恢复学习 / 拒绝名单。多次调用只生效一次。 */
        @JvmStatic
        @Synchronized
        fun loadPersisted(ctx: Context?) {
            if (loaded) return
            val c = ctx ?: return
            try {
                val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                learned = splitToSet(sp.getString(PREF_LEARNED, null))
                rejected = splitToSet(sp.getString(PREF_REJECTED, null))
                loaded = true
                Log.d(TAG, "persisted loaded: learned=" + learned.size +
                        " rejected=" + rejected.size)
            } catch (_: Exception) { }
        }

        private fun splitToSet(s: String?): MutableSet<String> {
            if (s.isNullOrEmpty()) return LinkedHashSet()
            val out = LinkedHashSet<String>()
            for (p in s.split(SEP)) {
                val v = p.trim()
                if (v.isNotEmpty()) out.add(v)
            }
            return out
        }

        @Synchronized
        private fun persist(ctx: Context?) {
            val c = ctx ?: return
            try {
                c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                        .putString(PREF_LEARNED, learned.joinToString(SEP))
                        .putString(PREF_REJECTED, rejected.joinToString(SEP))
                        .apply()
            } catch (_: Exception) { }
        }
    }
}
