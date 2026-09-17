package com.fxxkmoondrop.secret

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.util.Log
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * alpha2.26.5: 跨进程配置读取。
 * GMS（Google 弹窗）进程与模块进程 UID 不同，无法直接读模块私有 SharedPreferences
 * （createPackageContext 可读 assets 但读不了 data 目录，静默失败导致 show_wind 恒为 true）。
 * 改用 exported ContentProvider：GMS 进程通过 content:// 查询模块配置，由系统拉起模块进程读取。
 *
 * alpha2.38.10: 新增 "lang" 分支（显示语言：0=auto 1=zh 2=en），供弹窗跨进程读取。
 *
 * 3.0.5: 新增 moondrop_icon —— 用 openFile() 把应用内 filesDir/moondrop_icon.png
 * 提供给 GMS 进程的 Hook 读取，于是「弹窗自定义图标」**不再需要 Root**
 * （旧实现要往 /data/user/0/com.google.android.gms/files/ 写文件，只有 Root 才能做）。
 * 同时暴露 icon_ver，供 Hook 判断图标是否换过。
 *
 * 用法:
 *   content://com.fxxkmoondrop.secret.prefs/show_wind        -> _value=1/0
 *   content://com.fxxkmoondrop.secret.prefs/lang             -> _value=0/1/2
 *   content://com.fxxkmoondrop.secret.prefs/icon_ver         -> _value=<int>
 *   content://com.fxxkmoondrop.secret.prefs/moondrop_icon    -> openFile() 读 PNG 字节
 */
class PrefsProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        CtrlBus.bind(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val key = uri.lastPathSegment ?: return null
        if (key == "dc_cmd") return handleDcCmd(uri)
        val sp = context?.getSharedPreferences("cfg", Context.MODE_PRIVATE) ?: return null
        val value: Int = when {
            key == "lang" -> sp.getInt("lang", 0)
            key == IconStore.KEY_VER -> sp.getInt(IconStore.KEY_VER, 0)
            // 第 3 项：分类功能开关。`feat_*` 一律按布尔读，默认**开**（保持既有行为，
            // 用户显式关掉才停用对应功能），供 hook 侧跨进程判定。
            key == "show_wind" || key.startsWith("feat_") ->
                if (sp.getBoolean(key, true)) 1 else 0
            else -> return null
        }
        val c = MatrixCursor(arrayOf("_key", "_value"))
        c.addRow(arrayOf(key, value))
        return c
    }

    /**
     * 3.0.5: 图标文件对外只读。GMS 进程的 Hook 直接 openInputStream 读 PNG 字节，
     * 走的是系统 ContentResolver 通道，与 show_wind / lang 同一条链路。
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val key = uri.lastPathSegment
        if (key != "moondrop_icon") return super.openFile(uri, mode)
        val ctx = context ?: return null
        val f = IconStore.localFile(ctx)
        if (!f.exists() || f.length() <= 0) return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            Log.w("PrefsProvider", "openFile icon failed: $t")
            null
        }
    }

    private fun handleDcCmd(uri: Uri): Cursor? {
        val action = uri.getQueryParameter("action") ?: return null
        val g = GaiaBleClient.getInstance()
        try {
            when (action) {
                "set_anc" -> {
                    val mode = uri.getQueryParameter("mode")?.toIntOrNull() ?: -1
                    AncBridge.setAncMode(mode)
                }
                "set_spatial" -> {
                    val enabled = uri.getQueryParameter("enabled")?.toBoolean() ?: false
                    DeviceControlBridge.setSpatialEnabled(enabled)
                }
                "set_tracking" -> {
                    val mode = uri.getQueryParameter("mode")?.toIntOrNull() ?: -1
                    DeviceControlBridge.setTrackingMode(mode)
                }
                "set_gain" -> {
                    val level = uri.getQueryParameter("level")?.toIntOrNull() ?: -1
                    DeviceControlBridge.setGain(level)
                }
                "set_led" -> {
                    val state = uri.getQueryParameter("state")?.toIntOrNull() ?: -1
                    DeviceControlBridge.setLed(state)
                }
            }
        } catch (th: Throwable) { Log.e("PrefsProvider", "dc_cmd err", th) }
        val c = MatrixCursor(arrayOf("_key", "_value"))
        c.addRow(arrayOf("anc", AncBridge.getCurrentMode()))
        c.addRow(arrayOf("spatial", if (DeviceControlBridge.isSpatialOn()) 1 else 0))
        c.addRow(arrayOf("headTracking", DeviceControlBridge.spatialUiMode()))
        c.addRow(arrayOf("gain", DeviceControlBridge.getGainLevel()))
        c.addRow(arrayOf("led", DeviceControlBridge.getLedState()))
        c.addRow(arrayOf("connected", if (g.isConnected()) 1 else 0))
        // 第 2 项：GAIA 就绪（服务发现完成，命令真的发得出去）。面板可交互判据用它。
        c.addRow(arrayOf("gaia", if (g.isGaiaReady()) 1 else 0))
        // 第 1 项：本设备实际支持的 UI 档位（能力探测驱动，数据驱动多设备适配）。
        // 以逗号分隔；空串 = 能力未知，UI 侧退化为「按型号档案」显示。
        c.addRow(arrayOf("modes", g.supportedUiModes().joinToString(",")))
        // 抗风档的显示偏好：由模块进程读自己的 cfg，Settings 进程直接读不到那份私有偏好，
        // 所以走这条通道带过去，免得详情页把用户「隐藏抗风」的选择忽略掉。
        val windOn = try {
            context?.getSharedPreferences("cfg", Context.MODE_PRIVATE)?.getBoolean("show_wind", true) ?: true
        } catch (_: Throwable) { true }
        c.addRow(arrayOf("showWind", if (windOn) 1 else 0))
        return c
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.fxxkmoondrop.pref"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        /** dc_cmd 跨进程 uri */
        const val DC_CMD_URI = "content://com.fxxkmoondrop.secret.prefs/dc_cmd"
    }
}
