package com.fxxkmoondrop.secret

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 命令执行器（3.0.4）。
 *
 * 为什么需要：旧实现四处调用全是 `Runtime.exec(arrayOf("su", "-c", ...))`，
 * 把「root 二进制叫 su」当成了硬前提。FolkPatch（APatch 分支，也有 KSU 系变体）
 * 默认把 root 入口装在 **`/system/bin/kp`**（反编译其 `uapi/scdefs.h`：
 * `#define SU_PATH "/system/bin/kp"`、`LEGACY_SU_PATH "/system/bin/su"`，
 * `APatchApp.DEFAULT_SU_PATH = "/system/bin/kp"`），`su` 只是旧版兼容路径。
 * 这类设备上：识别不到 root + 就算识别到命令也发不出去 —— 两个症状同一根因。
 *
 * 本类按候选顺序探测并**缓存第一个可用者**：`su` 永远排在最前，
 * 因此 Magisk / KernelSU / APatch 老设备的行为与旧版完全一致（选中的还是 su）。
 *
 * ponytail: 逐个 exec 探测、命中即缓存；失败的候选只在进程内记住一次，
 * 避免每次调用都白跑一轮。需要重新探测时调 [reset]。
 */
object RootShell {

    private const val TAG = "RootShell"

    /** 单次命令的最长等待（授权框弹出时给人留出点击时间，但不无限等）。 */
    private const val TIMEOUT_SEC = 6L

    /** 探测单个候选时的等待（比正式命令短，避免无 root 设备上反复卡顿）。 */
    private const val PROBE_TIMEOUT_SEC = 3L

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
    private val probed = AtomicBoolean(false)

    /** 探测结果是否为 root 身份（`id` 输出含 uid=0）。抽成静态方法便于单测。 */
    @JvmStatic
    fun isRootId(output: String?): Boolean = output != null && output.contains("uid=0")

    /** 是否已找到可用的 root 入口（结果进程内缓存）。 */
    @JvmStatic
    fun isAvailable(): Boolean = resolve() != null

    /** 当前选中的 root 入口（未探测到时为 null）。 */
    @JvmStatic
    fun binary(): String? = resolve()

    /** 诊断用：清空探测缓存，下次调用重新逐个尝试。 */
    @JvmStatic
    fun reset() {
        resolved = null
        probed.set(false)
    }

    /**
     * 执行一条 root 命令，返回 stdout+stderr 合并文本；失败（无 root / 非 0 退出 / 超时）返回 null。
     */
    @JvmStatic
    fun exec(cmd: String): String? {
        val bin = resolve() ?: return null
        return run(bin, cmd, TIMEOUT_SEC)?.takeIf { it.second == 0 }?.first
    }

    /** 同 [exec]，但返回退出码（只要码、不要输出时用；失败返回 -1）。 */
    @JvmStatic
    fun execExit(cmd: String): Int {
        val bin = resolve() ?: return -1
        return run(bin, cmd, TIMEOUT_SEC)?.second ?: -1
    }

    private fun resolve(): String? {
        resolved?.let { return it }
        if (probed.get()) return null
        synchronized(this) {
            resolved?.let { return it }
            if (probed.get()) return null
            for (c in CANDIDATES) {
                val r = run(c, "id", PROBE_TIMEOUT_SEC)
                if (r != null && r.second == 0 && isRootId(r.first)) {
                    resolved = c
                    Log.i(TAG, "root 入口已选定: " + c)
                    probed.set(true)
                    return c
                }
            }
            probed.set(true)
            Log.i(TAG, "未找到可用 root 入口（已尝试 " + CANDIDATES.joinToString(",") + "）")
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
