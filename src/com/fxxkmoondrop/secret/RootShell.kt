package com.fxxkmoondrop.secret

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 命令执行器（3.0.4 引入，3.0.5 改探测缓存策略）。
 *
 * 为什么需要：旧实现四处调用全是 `Runtime.exec(arrayOf("su", "-c", ...))`，
 * 把「root 二进制叫 su」当成了硬前提。FolkPatch（APatch 分支）等变体默认把 root
 * 入口装在 **`/system/bin/kp`**（其 `uapi/scdefs.h`：`#define SU_PATH "/system/bin/kp"`、
 * `LEGACY_SU_PATH "/system/bin/su"`），`su` 只是旧版兼容路径。
 *
 * 候选顺序把 `su` 放在最前 —— Magisk / KernelSU / 旧 APatch 选中的仍是 `su`，行为与旧版一致。
 *
 * 3.0.5 缓存策略（重要）：
 * **未授权时 root 会整体隐藏**（FolkPatch 的 pathhide 等），此时所有候选都表现为
 * 「文件不存在」，和「设备根本没 root」在 App 侧**长得完全一样**。因此：
 * - **正结果**（某入口跑出 uid=0）→ 进程内永久锁定，零成本；
 * - **负结果** → 只记一个短 TTL（[NEGATIVE_TTL_MS]），TTL 内不重复 exec（避免一个 UI
 *   流程里连续探测），TTL 过后任何调用都会重新尝试 —— 用户去管理器补上授权后能自愈；
 * - 用户显式动作可调 [retryNow] 立即清掉负结果。
 *
 * ponytail: 负结果不缓存、正结果永久缓存 —— 前者怕误判，后者不会变。
 */
object RootShell {

    private const val TAG = "RootShell"

    /** 单次命令的最长等待（授权框弹出时给人留出点击时间，但不无限等）。 */
    private const val TIMEOUT_SEC = 6L

    /** 探测单个候选时的等待（比正式命令短，避免无 root 设备上反复卡顿）。 */
    private const val PROBE_TIMEOUT_SEC = 3L

    /** 负结果的有效期：期间不重复 exec，过期后自动重试（用户补授权即自愈）。 */
    private const val NEGATIVE_TTL_MS = 10_000L

    /**
     * 候选 root 入口，按优先级排列。
     * `su` 打头 —— 兼容面最广，且保证既有设备行为不变；`kp` 为 FolkPatch / APatch 默认。
     */
    @JvmField
    val CANDIDATES: Array<String> = arrayOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "kp",
        "/system/bin/kp",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su"
    )

    @Volatile private var resolved: String? = null

    /** 上次探测失败的时刻（0 = 从未探测过）。仅用于 TTL 判定，正结果不受影响。 */
    @Volatile private var lastNegativeAt = 0L

    /** 上次探测是否「找到了二进制但没拿到 root」——用于给出更准确的提示文案。 */
    @Volatile private var lastSawBinary = false

    /** 探测结果是否为 root 身份（`id` 输出含 uid=0）。抽成静态方法便于单测。 */
    @JvmStatic
    fun isRootId(output: String?): Boolean = output != null && output.contains("uid=0")

    /** 负结果是否仍在 TTL 内（此时 [isAvailable] 直接返回 false，不做 exec）。 */
    @JvmStatic
    fun isNegativeFresh(now: Long = System.currentTimeMillis()): Boolean =
        ttlFresh(lastNegativeAt, now, NEGATIVE_TTL_MS)

    /**
     * 纯函数形式的 TTL 判定，便于单测（不受内部状态影响）：
     * lastAt<=0 表示「从未失败过」，一律不算负结果新鲜 —— 否则会永久不探测。
     */
    @JvmStatic
    fun ttlFresh(lastAt: Long, now: Long, ttlMs: Long): Boolean =
        lastAt > 0L && now >= lastAt && now - lastAt < ttlMs

    /** 上一次探测是否见到了 root 二进制（提示文案用：见到 vs 完全没见到）。 */
    @JvmStatic
    fun sawBinaryLastProbe(): Boolean = lastSawBinary

    /** 是否已找到可用的 root 入口。负结果仅在 TTL 内被视为不可用。 */
    @JvmStatic
    fun isAvailable(): Boolean = binary() != null

    /** 当前选中的 root 入口（未探测到 / 负结果 TTL 内为 null）。 */
    @JvmStatic
    fun binary(): String? {
        resolved?.let { return it }
        if (isNegativeFresh()) return null
        return probe()
    }

    /**
     * 清掉负结果，下次调用立即重新探测。
     * 用于用户的显式动作：点「重新检测 Root」、切保活开关、进设置页等。
     */
    @JvmStatic
    fun retryNow() {
        synchronized(this) {
            if (resolved == null) {
                lastNegativeAt = 0L
                Log.i(TAG, "负结果已清除，下次调用重新探测")
            }
        }
    }

    /** 诊断用：连同正结果一起清空（进程内彻底重来）。 */
    @JvmStatic
    fun reset() {
        synchronized(this) {
            resolved = null
            lastNegativeAt = 0L
            lastSawBinary = false
        }
    }

    /**
     * 执行一条 root 命令，返回 stdout+stderr 合并文本；失败（无 root / 非 0 退出 / 超时）返回 null。
     */
    @JvmStatic
    fun exec(cmd: String): String? {
        val bin = binary() ?: return null
        return run(bin, cmd, TIMEOUT_SEC)?.takeIf { it.second == 0 }?.first
    }

    /** 同 [exec]，但返回退出码（只要码、不要输出时用；失败返回 -1）。 */
    @JvmStatic
    fun execExit(cmd: String): Int {
        val bin = binary() ?: return -1
        return run(bin, cmd, TIMEOUT_SEC)?.second ?: -1
    }

    /** 逐个尝试候选；命中即永久锁定，全失败则记负结果（带 TTL）。 */
    private fun probe(): String? {
        synchronized(this) {
            resolved?.let { return it }
            if (isNegativeFresh()) return null
            var sawBinary = false
            for (c in CANDIDATES) {
                val r = run(c, "id", PROBE_TIMEOUT_SEC)
                if (r != null && r.second == 0 && isRootId(r.first)) {
                    resolved = c
                    lastNegativeAt = 0L
                    lastSawBinary = true
                    Log.i(TAG, "root 入口已选定: " + c)
                    return c
                }
                // 进程能起来（拿到退出码）就说明二进制存在，只是没授权/被拒
                if (r != null) sawBinary = true
            }
            lastSawBinary = sawBinary
            lastNegativeAt = System.currentTimeMillis()
            Log.i(TAG, "本次未取得 root（见到二进制=" + sawBinary + "），" +
                    "已尝试 " + CANDIDATES.joinToString(",") + "；" +
                    (NEGATIVE_TTL_MS / 1000) + "s 后自动重试")
            return null
        }
    }

    /** 真正执行：返回 (输出, 退出码)；异常/超时返回 null。 */
    private fun run(bin: String, cmd: String, timeoutSec: Long): Pair<String, Int>? {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf(bin, "-c", cmd))
            val sb = StringBuilder()
            // stdout 与 stderr 都要读，否则缓冲区可能写满导致子进程阻塞
            val out = Thread {
                try {
                    BufferedReader(InputStreamReader(p!!.inputStream)).forEachLine { sb.append(it).append('\n') }
                } catch (_: Throwable) { }
            }
            val err = Thread {
                try {
                    BufferedReader(InputStreamReader(p!!.errorStream)).forEachLine { sb.append(it).append('\n') }
                } catch (_: Throwable) { }
            }
            out.start(); err.start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            out.join(500); err.join(500)
            if (!finished) {
                try { p.destroy() } catch (_: Throwable) { }
                Log.w(TAG, "root 命令超时（" + bin + "）：" + cmd.take(60))
                return null
            }
            Pair(sb.toString(), p.exitValue())
        } catch (t: Throwable) {
            // 该候选不存在（IOException）或无权限 —— 换下一个候选
            try { p?.destroy() } catch (_: Throwable) { }
            null
        }
    }
}
