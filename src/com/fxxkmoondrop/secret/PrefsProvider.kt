package com.fxxkmoondrop.secret

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
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

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val key = uri.lastPathSegment ?: return null
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

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.fxxkmoondrop.pref"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
