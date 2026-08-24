package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * alpha1.37: 设备适配日志收集器（多日志 ZIP 版）。
 * 收集 5 条分类日志：系统信息 / 应用与设置 / 蓝牙信息 / 运行环境 / logcat 尾部，
 * 打包为 ZIP 保存：优先写入手机根目录 /storage/emulated/0/（需 Root 复制，普通应用无法直接写公共根目录）；
 * Root 不可用时回退到应用外部私有目录 files/logs/（无需存储权限）。
 * 注意：收集内容含设备标识与系统信息（隐私），仅供用户本人调试使用。
 */
class LogCollector {
    companion object {
        /** 隐私声明（设置页弹窗复用） */
        const val PRIVACY_NOTICE = "日志将打包为 ZIP（含多分类日志），包含：设备型号与系统版本、应用与模块版本、" +
                "应用设置、Root/环境检测状态、蓝牙连接信息与系统日志等。\n\n" +
                "\u26a0\ufe0f 这些信息可能涉及设备隐私，仅供您本人调试与设备适配使用；" +
                "请勿上传至公开平台或分享给不可信的人。"

        private const val ZIP_PREFIX = "FxxkMoondrop_logs_"

        private var probeCtx: Context? = null

        /** 收集日志并打包 ZIP，返回保存路径；失败返回错误说明。须在子线程调用。 */
        @JvmStatic
        fun collect(ctx: Context): String {
            probeCtx = ctx
            AppLog.init(ctx) // alpha2.16: 确保运行日志目录就绪
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = ZIP_PREFIX + stamp
            try {
                // 1) 组装 5 条分类日志
                val names = arrayOf(
                        "01_系统信息.txt", "02_应用与设置.txt", "03_蓝牙信息.txt",
                        "04_运行环境.txt", "05_logcat.txt", "06_运行日志.txt")
                val contents = arrayOf(
                        buildSystemInfo(ctx, stamp),
                        buildAppInfo(ctx),
                        buildBleInfo(),
                        buildEnvInfo(),
                        buildLogcat(),
                        buildRuntimeLog())

                // 2) 先写入应用私有目录 files/logs/（打包 zip 的临时位置）
                var base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (base == null) base = ctx.filesDir
                val dir = File(base, "logs")
                if (!dir.exists() && !dir.mkdirs()) return "保存失败: 无法创建目录 $dir"
                val zipFile = File(dir, "$baseName.zip")

                // 3) 打包 ZIP
                val zos = ZipOutputStream(FileOutputStream(zipFile))
                try {
                    for (i in names.indices) {
                        zos.putNextEntry(ZipEntry(names[i]))
                        zos.write(contents[i].toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                } finally {
                    zos.close()
                }

                // 4) 尝试复制到手机根目录 /storage/emulated/0/（Android 11+ 普通应用不可直接写公共根，Root 可）
                val rootPath = tryCopyToPublicRoot(zipFile, "$baseName.zip")
                if (rootPath != null) return rootPath

                // 5) 回退：直接尝试写公共根目录（旧系统 Scoped Storage 之前）
                try {
                    val pub = File(Environment.getExternalStorageDirectory(), "$baseName.zip")
                    copy(zipFile, pub)
                    if (pub.exists() && pub.length() > 0) return pub.absolutePath
                } catch (_: Exception) { }

                // 6) 最终回退：私有目录
                return zipFile.absolutePath + "\n（Root 不可用，已存应用目录；分享前请自行导出）"
            } catch (e: Exception) {
                return "保存失败: $e"
            }
        }

        /** 用 Root 复制到公共根目录；失败返回 null。 */
        private fun tryCopyToPublicRoot(src: File, fileName: String): String? {
            try {
                val dest = Environment.getExternalStorageDirectory().absolutePath + "/" + fileName
                val cmd = "rm -f '$dest'; cp '${src.absolutePath}' '$dest'; chmod 644 '$dest'; ls -l '$dest'"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val r = BufferedReader(InputStreamReader(p.inputStream))
                var tail = ""
                while (true) {
                    val line = r.readLine() ?: break
                    tail = line
                }
                val rc = p.waitFor()
                if (rc == 0 && File(dest).exists() && File(dest).length() > 0) return dest
            } catch (_: Exception) { }
            return null
        }

        private fun copy(src: File, dst: File) {
            FileInputStream(src).use { i ->
                FileOutputStream(dst).use { o ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = i.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
        }

        // ── 01 系统信息 ──
        private fun buildSystemInfo(ctx: Context, stamp: String): String {
            val sb = StringBuilder()
            sb.append("==== FxxkMoondrop 设备适配日志 · 系统信息 ====\n")
            sb.append("生成时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            sb.append("代号: ").append(Build.DEVICE).append(" / ").append(Build.PRODUCT).append(" / ")
                    .append(Build.BRAND).append(" / ").append(Build.BOARD).append('\n')
            sb.append("硬件: ").append(Build.HARDWARE).append('\n')
            sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(") ").append(Build.ID).append('\n')
            sb.append("指纹: ").append(Build.FINGERPRINT).append('\n')
            sb.append("ABI: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n')
            try {
                val dm = ctx.resources.displayMetrics
                sb.append("屏幕: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                        .append(" density ").append(dm.density).append(" dpi ").append(dm.densityDpi).append('\n')
            } catch (e: Exception) {
                sb.append("屏幕: 读取失败 ").append(e).append('\n')
            }
            sb.append("语言: ").append(Locale.getDefault().toLanguageTag())
                    .append(" 时区 ").append(java.util.TimeZone.getDefault().id).append('\n')
            sb.append("==== 结束 ====\n")
            return sb.toString()
        }

        // ── 02 应用与设置 ──
        private fun buildAppInfo(ctx: Context): String {
            val sb = StringBuilder()
            sb.append("==== 应用与设置 ====\n")
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                sb.append("应用: ").append(ctx.packageName).append(" v")
                        .append(pi.versionName).append(" (").append(pi.versionCode).append(")\n")
            } catch (e: Exception) {
                sb.append("应用: 读取失败 ").append(e).append('\n')
            }
            sb.append("GMS 版本: ").append(appVersion(ctx, "com.google.android.gms")).append('\n')
            sb.append("Moondrop 官方 App: ")
                    .append(appVersion(ctx, "com.moondroplab.moondrop.moondrop_app")).append('\n')
            // alpha2.26.11: ANC 映射状态（SET/GET 生效映射 + 档案匹配 + SP 自定义），
            // 供其他型号适配诊断（抓官方 App 对比设备码时必看）
            sb.append("---- ANC 映射状态 ----\n")
            try {
                val client = GaiaBleClient.getInstance()
                sb.append("设备名: ").append(client.getConnectedDeviceName() ?: "(未连接)").append('\n')
                sb.append("匹配档案: ").append(AncProfileLib.matchedProfileName(client.getConnectedDeviceName())).append('\n')
                sb.append("生效 SET 映射(UI[关,降,透,抗]->dev): ")
                        .append(client.getEffectiveAncMap().contentToString()).append('\n')
            } catch (e: Exception) {
                sb.append("ANC 映射读取失败: ").append(e).append('\n')
            }
            sb.append("---- 应用设置 (cfg) ----\n")
            try {
                val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                val all = sp.all
                if (all.isEmpty()) sb.append("(空)\n")
                for ((k, v) in all) {
                    sb.append(k).append(" = ").append(v.toString()).append('\n')
                }
            } catch (e: Exception) {
                sb.append("读取设置失败: ").append(e).append('\n')
            }
            sb.append("==== 结束 ====\n")
            return sb.toString()
        }

        // ── 03 蓝牙信息 ──
        private fun buildBleInfo(): String {
            val sb = StringBuilder()
            sb.append("==== 蓝牙信息 ====\n")
            try {
                val ba = BluetoothAdapter.getDefaultAdapter()
                if (ba != null) {
                    sb.append("蓝牙状态: ").append(bluetoothStateName(ba.state)).append('\n')
                    val bonded = ba.bondedDevices
                    if (bonded != null) {
                        sb.append("配对设备 (").append(bonded.size).append("):\n")
                        for (d in bonded) {
                            sb.append("  ").append(d.name).append("  ").append(d.address).append('\n')
                        }
                    }
                } else {
                    sb.append("蓝牙: 本机无蓝牙适配器\n")
                }
            } catch (e: Exception) {
                sb.append("蓝牙读取失败（可能缺权限）: ").append(e).append('\n')
            }
            sb.append("==== 结束 ====\n")
            return sb.toString()
        }

        // ── 04 运行环境 ──
        private fun buildEnvInfo(): String {
            val sb = StringBuilder()
            sb.append("==== 运行环境 ====\n")
            try {
                sb.append("Root: ").append(if (EnvProbe.isRooted()) "检测到" else "未检测到").append('\n')
                sb.append("FastPairHook 模块: ")
                        .append(if (EnvProbe.isFastPairHookActive(probeCtx)) "已激活" else "未激活").append('\n')
            } catch (e: Exception) {
                sb.append("环境探测失败: ").append(e).append('\n')
            }
            sb.append("==== 结束 ====\n")
            return sb.toString()
        }

        // ── 06 应用内运行日志（alpha2.16：BLE/协议全链路，无 Root 也可收集）──
        private fun buildRuntimeLog(): String {
            val sb = StringBuilder()
            sb.append("==== 应用内运行日志 (AppLog) ====\n")
            val fp = AppLog.filePath()
            sb.append("文件: ").append(fp ?: "(未初始化/不可用)").append('\n')
            // alpha2.16.1: 导出文件全文 + 内存增量（进程重启后历史不丢）
            sb.append(AppLog.dumpAll())
            sb.append("==== 结束 ====\n")
            return sb.toString()
        }

        // ── 05 logcat 尾部 ──
        private fun buildLogcat(): String {
            val sb = StringBuilder()
            sb.append("==== logcat (tail 1000) ====\n")
            try {
                val p = Runtime.getRuntime().exec("logcat -d -t 1000")
                val r = BufferedReader(InputStreamReader(p.inputStream))
                var n = 0
                while (true) {
                    val line = r.readLine() ?: break
                    if (n >= 1200) break
                    sb.append(line).append('\n')
                    n++
                }
                p.waitFor()
            } catch (e: Exception) {
                sb.append("logcat 读取失败: ").append(e).append('\n')
            }
            sb.append("==== 结束 ====\n")
            return sb.toString()
        }

        private fun appVersion(ctx: Context, pkg: String): String {
            return try {
                val pi = ctx.packageManager.getPackageInfo(pkg, 0)
                pi.versionName + " (" + pi.versionCode + ")"
            } catch (_: PackageManager.NameNotFoundException) {
                "未安装"
            } catch (_: Exception) {
                "读取失败"
            }
        }

        private fun bluetoothStateName(state: Int): String {
            return when (state) {
                BluetoothAdapter.STATE_ON -> "已开启"
                BluetoothAdapter.STATE_OFF -> "已关闭"
                BluetoothAdapter.STATE_TURNING_ON -> "开启中"
                BluetoothAdapter.STATE_TURNING_OFF -> "关闭中"
                else -> "未知($state)"
            }
        }
    }
}
