package com.fxxkmoondrop.secret

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 弹窗图标存储（3.0.5 起不再依赖 Root）。
 *
 * 旧实现把图标直接 cp 到 `/data/user/0/com.google.android.gms/files/moondrop_icon.png`
 * —— 那是 GMS 的私有目录，**必须 Root** 才能写；没有 Root（或 Root 被隐藏）时功能直接失效。
 *
 * 现在改为双写：
 * 1. **主路径（无需 Root）**：写进本应用 `filesDir/moondrop_icon.png`，
 *    由 exported 的 [PrefsProvider]（`content://com.fxxkmoondrop.secret.prefs/moondrop_icon`）
 *    提供给 GMS 进程里的 Hook 读取 —— 与 `show_wind` / `lang` 走的是同一条既有通道；
 * 2. **兼容路径（有 Root 时才写）**：照旧写 GMS 老位置，老安装直接生效、无需重启。
 *
 * `icon_ver` 每次改动自增，Hook 侧据此判断「图标换过了」。
 */
object IconStore {

    private const val TAG = "IconStore"

    /** 本地图标文件名。 */
    const val FILE_NAME = "moondrop_icon.png"

    /** 版本号 SP key（PrefsProvider 跨进程暴露给 Hook）。 */
    const val KEY_VER = "icon_ver"

    /** Hook 侧使用的读取 URI。 */
    const val URI = "content://com.fxxkmoondrop.secret.prefs/moondrop_icon"

    /** GMS 老路径（有 Root 时兼容写入）。 */
    const val GMS_LEGACY_PATH = "/data/user/0/com.google.android.gms/files/moondrop_icon.png"

    /** 图标上限（与旧实现一致）。 */
    const val MAX_BYTES = 1024 * 1024

    /** 本应用内的图标文件（无需 Root 即可读写）。 */
    @JvmStatic
    fun localFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /** 是否已设置自定义图标（只看本地文件，零 Root 依赖，可主线程调用）。 */
    @JvmStatic
    fun exists(ctx: Context?): Boolean {
        val c = ctx ?: return false
        return try {
            val f = localFile(c)
            f.exists() && f.length() > 0
        } catch (_: Throwable) {
            false
        }
    }

    /** 图标版本号；每次改动 +1，Hook 侧用它判断是否换过。 */
    @JvmStatic
    fun version(ctx: Context?): Int =
        ctx?.getSharedPreferences("cfg", Context.MODE_PRIVATE)?.getInt(KEY_VER, 0) ?: 0

    /**
     * 保存 PNG 字节。
     * @return true = 本地写入成功（功能可用）；GMS 兼容写入失败不影响返回值。
     */
    @JvmStatic
    fun save(ctx: Context?, png: ByteArray): Boolean {
        val c = ctx ?: return false
        if (png.isEmpty() || png.size > MAX_BYTES) return false
        return try {
            localFile(c).writeBytes(png)
            bump(c)
            Log.i(TAG, "icon saved locally: " + png.size + "B ver=" + version(c))
            writeGmsLegacyIfRooted(c, png)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "save icon failed", t)
            false
        }
    }

    /** 恢复默认：删除本地文件 + 版本 +1（Hook 需要重建）+ 有 Root 时删 GMS 老文件。 */
    @JvmStatic
    fun clear(ctx: Context?): Boolean {
        val c = ctx ?: return false
        return try {
            localFile(c).delete()
            bump(c)
            Log.i(TAG, "icon cleared, ver=" + version(c))
            clearGmsLegacyIfRooted()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "clear icon failed", t)
            false
        }
    }

    private fun bump(ctx: Context) {
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_VER, sp.getInt(KEY_VER, 0) + 1).apply()
    }

    /** 有 Root 时兼容写 GMS 老位置（纯增强；失败只记日志，不做任何提示）。 */
    private fun writeGmsLegacyIfRooted(ctx: Context, png: ByteArray) {
        Thread {
            try {
                if (!RootShell.isAvailable()) return@Thread
                val tmp = File(ctx.cacheDir, FILE_NAME)
                tmp.outputStream().use { it.write(png) }
                val cmd = "cp '" + tmp.absolutePath + "' " + GMS_LEGACY_PATH +
                        " && chown $(stat -c %u:%g /data/user/0/com.google.android.gms) " + GMS_LEGACY_PATH +
                        " && chmod 644 " + GMS_LEGACY_PATH + " && echo OK"
                val out = RootShell.exec(cmd)
                Log.i(TAG, "gms legacy write: " + (if (out != null && out.contains("OK")) "ok" else "skipped"))
            } catch (t: Throwable) {
                Log.w(TAG, "gms legacy write failed: $t")
            }
        }.start()
    }

    private fun clearGmsLegacyIfRooted() {
        Thread {
            try {
                if (!RootShell.isAvailable()) return@Thread
                RootShell.exec("rm -f " + GMS_LEGACY_PATH)
            } catch (_: Throwable) { }
        }.start()
    }
}
