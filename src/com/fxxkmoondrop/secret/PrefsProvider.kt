package com.fxxkmoondrop.secret

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.util.Log
import android.net.Uri

/**
 * alpha2.26.5: 跨进程配置读取。
 * GMS（Google 弹窗）进程与模块进程 UID 不同，无法直接读模块私有 SharedPreferences
 * （createPackageContext 可读 assets 但读不了 data 目录，静默失败导致 show_wind 恒为 true）。
 * 改用 exported ContentProvider：GMS 进程通过 content:// 查询模块配置，由系统拉起模块进程读取。
 *
 * alpha2.38.10: 新增 "lang" 分支（显示语言：0=auto 1=zh 2=en），供弹窗跨进程读取。
 *
 * 用法:
 *   content://com.fxxkmoondrop.secret.prefs/show_wind        -> _value=1/0
 *   content://com.fxxkmoondrop.secret.prefs/lang             -> _value=0/1/2
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
        val value: Int = when (key) {
            "show_wind" -> if (sp.getBoolean("show_wind", true)) 1 else 0
            "lang" -> sp.getInt("lang", 0)
            else -> return null
        }
        val c = MatrixCursor(arrayOf("_key", "_value"))
        c.addRow(arrayOf(key, value))
        return c
    }

    private fun handleDcCmd(uri: Uri): Cursor? {
        val action = uri.getQueryParameter("action") ?: return null
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
        c.addRow(arrayOf("connected", if (GaiaBleClient.getInstance().isConnected()) 1 else 0))
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
