package com.fxxkmoondrop.secret

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * alpha2.16: 应用内运行日志（面向无 Root 用户手机收集场景）。
 *
 * 背景：Logcat 在 Android 4.1+ 普通应用只能读取自己进程的日志，
 * 把 APK 发给别人（他人手机可能无 Root）测试时，LogCollector 的 05_logcat.txt
 * 无法保证完整；因此将 BLE 扫描/连接/GATT 服务发现/GAIA 帧/9ECA 帧/错误等
 * 关键路径全部写入本日志器：
 *  - 内存环形缓冲（默认 2000 条），导出快照用
 *  - 文件 filesDir/appslog/runtime.log（默认 ≤512KB，超限轮转 runtime.1.log）
 * 线程安全；init() 幂等，MainActivity / HeadsetDetectService / collect 前均可调用。
 */
object AppLog {
    const val MAX_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 512L * 1024

    private val lock = Any()
    private val buf = LinkedList<String>()
    private var dir: File? = null
    private var file: File? = null

    /** 初始化（幂等）；传入任意 Context（application）即可。 */
    @JvmStatic
    fun init(ctx: Context) {
        synchronized(lock) {
            if (dir != null) return
            val d = File(ctx.filesDir, "appslog")
            try {
                if (!d.exists() && !d.mkdirs()) {
                    // alpha2.16.1: 目录创建失败必须显式失败，避免伪装成功导致日志只有内存
                    dir = null
                    file = null
                    buf.addLast(stamp() + " [W] [AppLog] init failed: cannot create " + d.absolutePath)
                    return
                }
                dir = d
                file = File(d, "runtime.log")
            } catch (e: Exception) {
                dir = null
                file = null
                buf.addLast(stamp() + " [W] [AppLog] init exception: " + e)
            }
        }
    }

    @JvmStatic fun d(tag: String, msg: String) = write("D", tag, msg)
    @JvmStatic fun i(tag: String, msg: String) = write("I", tag, msg)
    @JvmStatic fun w(tag: String, msg: String) = write("W", tag, msg)
    @JvmStatic fun e(tag: String, msg: String) = write("E", tag, msg)

    private fun write(level: String, tag: String, msg: String) {
        val line = stamp() + " [" + level + "] [" + tag + "] " + msg
        synchronized(lock) {
            buf.addLast(line)
            while (buf.size > MAX_ENTRIES) buf.removeFirst()
            val ff = file ?: return
            try {
                if (ff.length() > MAX_FILE_BYTES) rotate(ff)
                FileOutputStream(ff, true).use { out ->
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) { }
        }
    }

    private fun rotate(ff: File) {
        val old = File(ff.parentFile, "runtime.1.log")
        try {
            if (old.exists()) old.delete()
            ff.renameTo(old)
        } catch (_: Exception) { }
    }

    /** 内存快照（导出 ZIP 用） */
    @JvmStatic
    fun snapshot(): String = synchronized(lock) { buf.joinToString("\n") }

    /**
     * alpha2.16.1: 完整导出（历史 + 当前）。
     * 返回 runtime.log 与 runtime.1.log（轮转）全文，再附加内存中尚未落盘的新条目（按行去重）。
     * 进程重启后内存为空时，历史日志仍可从文件恢复，不会丢失。
     */
    @JvmStatic
    fun dumpAll(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            val f = file
            val fileText = buildString {
                if (f != null) {
                    try {
                        if (f.exists() && f.length() > 0) append(f.readText(Charsets.UTF_8))
                        // 轮转后的上一份（保留最近一次）
                        val old = File(f.parentFile, "runtime.1.log")
                        if (old.exists() && old.length() > 0) {
                            if (isNotEmpty() && !endsWith("\n")) append('\n')
                            append(old.readText(Charsets.UTF_8))
                        }
                    } catch (_: Exception) { }
                }
            }
            sb.append("----- 文件日志 (runtime.log" + (f?.let { " ${it.length()}B" } ?: "") + ", 含轮转) -----\n")
            if (fileText.isEmpty()) sb.append("(无)\n")
            else sb.append(fileText).append(if (fileText.endsWith("\n")) "" else "\n")
            sb.append("----- 内存中未落盘条目 (最近 ").append(MAX_ENTRIES).append(" 条) -----\n")
            val fileLines = if (fileText.isEmpty()) emptySet() else fileText.lineSequence().toHashSet()
            val memOnly = buf.filter { it !in fileLines }
            if (memOnly.isEmpty()) sb.append("(无)\n")
            else sb.append(memOnly.joinToString("\n")).append('\n')
            return sb.toString()
        }
    }

    /** 当前日志文件路径（可能为 null） */
    @JvmStatic
    fun filePath(): String? = synchronized(lock) { file?.absolutePath }

    /** 字节数组 → "AA BB CC" 十六进制（帧日志用） */
    @JvmStatic
    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 3)
        for (x in b) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", x.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
