package com.fxxkmoondrop.secret

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceProvider
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.SliceAction

/**
 * 蓝牙详情页「耳机控制」行的内容提供者（系统官方 SliceView 渲染）。
 *
 * ⚠️ 只对水月雨设备生效：设备名不是 Moondrop 时返回 null，
 * 绝不顶替别家耳机或系统原生支持 Hearable 的耳机。
 */
class HearableSliceProvider : SliceProvider() {

    companion object {
        const val AUTHORITY = "com.fxxkmoondrop.secret.slices"
        fun uriFor(address: String, name: String?): Uri =
            Uri.parse("content://$AUTHORITY/hearable?address=$address&name=${Uri.encode(name ?: "")}")
    }

    override fun onCreateSliceProvider(): Boolean = true

    override fun onBindSlice(sliceUri: Uri): Slice? {
        val ctx = context ?: return null
        val address = sliceUri.getQueryParameter("address")
        val name = sliceUri.getQueryParameter("name")
        if (!AncProfileLib.isMoondrop(name) && !AncProfileLib.isMoondrop(nameOf(address))) return null

        val labels = AncProfileLib.modeNamesFull(ctx)
        // androidx.slice 1.0.0 的真实 API：ListBuilder(Context, Uri[, ttl])；
        // 没有 setSliceUri/setTtl 的 Builder 链式写法（CI 编译报错实测）。
        val lb = ListBuilder(ctx, sliceUri)
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
        return lb.build()
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
