package com.fxxkmoondrop.secret

import android.app.PendingIntent
import android.app.slice.SliceManager
import android.app.slice.SliceProvider
import android.app.slice.SliceSpec
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceConvert
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.SliceAction

/**
 * 「耳机控制」Slice 的内容来源。
 *
 * 两条使用路径：
 * 1) 作为独立 provider（authority 见 AUTHORITY）—— 供原生支持的系统兜底；
 * 2) 更关键的一条：GMS 的 hearable_control provider 对第三方耳机返回 null，
 *    GMS 的 bind_slice 入口及 Settings 的 bindSlice 兜底通过 [buildFor] 读取模块切片。
 *    两端校验真实 UID；动作始终由模块创建，不借用宿主的 PendingIntent 身份。
 *
 * ⚠️ 只对水月雨设备生效：设备名不是 Moondrop 时一律返回 null。
 */
class HearableSliceProvider : SliceProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = requireNotNull(context)
        // System lifecycle calls carry no controls; the framework checks their UID again.
        if (!isSystemLifecycleCall(Binder.getCallingUid(), method)) HookIpc.enforceCaller(ctx)
        if (method == "bind_slice") {
            val uri = extras?.getParcelable<Uri>("slice_uri") ?: return null
            if (!isModuleUri(uri)) return null
            val caller = Binder.getCallingUid()
            val token = Binder.clearCallingIdentity()
            try {
                // Only the provider owner can grant slices. A Settings-context self-grant cannot.
                for (pkg in ctx.packageManager.getPackagesForUid(caller).orEmpty()) {
                    if (pkg == HookIpc.SETTINGS || pkg == HookIpc.GMS) {
                        ctx.getSystemService(SliceManager::class.java).grantSlicePermission(pkg, uri)
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
        return super.call(method, arg, extras)
    }

    @Synchronized // AndroidX's builder specs are process-global, not thread-local.
    override fun onBindSlice(sliceUri: Uri, supportedSpecs: Set<SliceSpec>): android.app.slice.Slice? {
        val ctx = requireNotNull(context)
        HookIpc.enforceCaller(ctx)
        if (!isModuleUri(sliceUri)) return null
        // Authenticate before dropping identity; PendingIntents must be created by the module UID.
        val token = Binder.clearCallingIdentity()
        try {
            androidx.slice.SliceProvider.setSpecs(SliceConvert.wrap(supportedSpecs))
            return SliceConvert.unwrap(buildLocal(ctx, sliceUri))
        } finally {
            androidx.slice.SliceProvider.setSpecs(null)
            Binder.restoreCallingIdentity(token)
        }
    }

    companion object {
        const val AUTHORITY = "com.fxxkmoondrop.secret.slices"

        fun uriFor(address: String, name: String?): Uri =
            Uri.parse("content://$AUTHORITY/hearable").buildUpon()
                .appendQueryParameter("address", address)
                .appendQueryParameter("name", name ?: "").build()

        internal fun isSystemLifecycleCall(uid: Int, method: String): Boolean =
            uid == Process.SYSTEM_UID && method in setOf("pin", "unpin", "get_permissions")

        private fun isModuleUri(uri: Uri): Boolean =
            uri.scheme == "content" && uri.authority == AUTHORITY && uri.path == "/hearable"

        internal fun isHostUri(scheme: String?, authority: String?, path: String?): Boolean =
            scheme == "content" && authority == "com.google.android.gms.nearby.fastpair" &&
                    path == "/hearable_control"

        /** Hosts fetch module-owned actions instead of minting unusable host-owned PendingIntents. */
        fun buildFor(context: Context?, sliceUri: Uri): android.app.slice.Slice? {
            val ctx = context ?: return null
            HookIpc.enforceCaller(ctx)
            if (!isHostUri(sliceUri.scheme, sliceUri.authority, sliceUri.path)) return null
            val address = sliceUri.getQueryParameter("address") ?: return null
            val extras = Bundle().apply {
                putParcelable("slice_uri", uriFor(address, sliceUri.getQueryParameter("name")))
                putParcelableArrayList("supported_specs", arrayListOf(
                    SliceSpec("androidx.slice.LIST", 1), SliceSpec("androidx.slice.BASIC", 1)))
            }
            return ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), "bind_slice", null, extras)
                ?.getParcelable("slice")
        }

        /** 构建「降噪 / 关闭 / 透传」三个动作的 Slice；非水月雨返回 null */
        private fun buildLocal(ctx: Context, sliceUri: Uri): Slice? {
            val address = sliceUri.getQueryParameter("address")
            val name = sliceUri.getQueryParameter("name")
            if (!AncProfileLib.isMoondrop(name) && !AncProfileLib.isMoondrop(nameOf(address))) return null

            val labels = AncProfileLib.modeNamesFull(ctx)
            val lb = ListBuilder(ctx, sliceUri)
            // 必须给 header：只有 action 的 Slice 在 SliceView 里可能被量成 0 高度
            // （实测：bindSlice 返回 hasContent=true，但界面上什么都不显示）。
            val cur = AncBridge.getCurrentMode()
            // SAM 转换在这版 androidx 上不生效（lambda 被 catch 吞掉 -> 只有 action，SliceView 量成 0 高）
            // 直接用 HeaderBuilder 实例
            val hb = ListBuilder.HeaderBuilder(lb, sliceUri)
            hb.setTitle(labels.getOrNull(cur) ?: "降噪控制")
            lb.setHeader(hb)
            for (m in intArrayOf(1, 0, 2)) {
                val pi = PendingIntent.getBroadcast(
                    ctx, 2000 + m,
                    Intent(ctx, NotifActionReceiver::class.java)
                        .setAction(DeviceNotif.ACTION_ANC)
                        .putExtra(DeviceNotif.EXTRA_MODE, m)
                        .setPackage(ctx.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val action = SliceAction.create(
                    pi, IconCompat.createWithResource(ctx, iconOf(m)), m + 1,
                    labels.getOrNull(m) ?: "")
                lb.addAction(action)
            }
            val built = lb.build()
            runCatching {
                val types = built.items.joinToString(",") { it.format ?: "?" }
                android.util.Log.i("FxxkMoondropProvider",
                        "slice built items=" + built.items.size + " types=" + types + " uri=" + sliceUri)
            }
            return built
        }

        private fun nameOf(address: String?): String? {
            if (address.isNullOrEmpty()) return null
            return try {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)?.name
            } catch (_: Throwable) { null }
        }

        private fun iconOf(mode: Int): Int = when (mode) {
            0 -> R.drawable.ic_anc_off
            1 -> R.drawable.ic_anc_on
            else -> R.drawable.ic_anc_passthrough
        }
    }
}
