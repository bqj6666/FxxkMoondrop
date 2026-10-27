package com.fxxkmoondrop.secret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Process
import android.net.Uri

/** Authentication uses the host's Binder UID, never a module Context's package name. */
internal object HookIpc {
    const val APP = "com.fxxkmoondrop.secret"
    const val GMS = "com.google.android.gms"
    const val SETTINGS = "com.android.settings"
    const val PERMISSION = "$APP.permission.HOOK_IPC"
    const val EVENT = "hook_event"

    fun enforceCaller(context: Context, allowSettings: Boolean = true) {
        if (!allows(Binder.getCallingUid(), Process.myUid(), allowSettings) { pkg ->
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(pkg, 0).uid
            }) throw SecurityException("Untrusted hook IPC caller")
    }

    internal fun allows(uid: Int, ownUid: Int, allowSettings: Boolean, installedUid: (String) -> Int): Boolean {
        if (uid == ownUid) return true
        return (if (allowSettings) listOf(GMS, SETTINGS) else listOf(GMS)).any { pkg ->
            try { uid == installedUid(pkg) } catch (_: Exception) { false }
        }
    }

    fun sendToApp(context: Context, intent: Intent) {
        context.contentResolver.call(Uri.parse("content://$APP.prefs"), EVENT, intent.action, intent.extras)
    }

    // The permission also protects same-app broadcasts on API 26–32, before NOT_EXPORTED exists.
    fun registerInternal(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter, PERMISSION, null)
        }
    }
}
