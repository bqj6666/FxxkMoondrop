package com.fxxkmoondrop.secret

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceProvider
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.SliceAction

/**
 * 「耳机控制」Slice 的内容来源。
 *
 * 两条使用路径：
 * 1) 作为独立 provider（authority 见 AUTHORITY）—— 供原生支持的系统兜底；
 * 2) 更关键的一条：GMS 的 hearable_control provider 对第三方耳机返回 null，
 *    我们在 GMS 进程里 hook `SliceProvider.onBindSlice`，把空结果换成 [buildFor] 的结果。
 *    这样内容是由**被系统信任的 provider** 产出的，绕开 SliceManager 对第三方 provider 的授权限制。
 *
 * ⚠️ 只对水月雨设备生效：设备名不是 Moondrop 时一律返回 null。
 */
class HearableSliceProvider : SliceProvider() {

    override fun onCreateSliceProvider(): Boolean = true

    override fun onBindSlice(sliceUri: Uri): Slice? = buildFor(context, sliceUri)

    companion object {
        const val AUTHORITY = "com.fxxkmoondrop.secret.slices"

        fun uriFor(address: String, name: String?): Uri =
            Uri.parse("content://$AUTHORITY/hearable?address=$address&name=${Uri.encode(name ?: "")}")

        /** 构建「降噪 / 关闭 / 透传」三个动作的 Slice；非水月雨返回 null */
        fun buildFor(context: Context?, sliceUri: Uri): Slice? {
            val ctx = context ?: return null
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
