package com.fxxkmoondrop.secret

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import com.fxxkmoondrop.secret.hook.FastPairHookEntry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** UID policy and non-UI routing checks; Android's Binder/permission enforcement needs device testing. */
class HookIpcTest {
    private val installed = mapOf(HookIpc.GMS to 10123, HookIpc.SETTINGS to 1000)

    @Test
    fun `only own UID and installed host UIDs may read preferences state icons and commands`() {
        for (uid in listOf(10456, 10123, 1000)) {
            assertTrue(HookIpc.allows(uid, 10456, true, installed::getValue))
        }
        for (uid in listOf(0, 2000, 10124, 110123)) {
            assertFalse(HookIpc.allows(uid, 10456, true, installed::getValue))
        }
        assertFalse(HookIpc.allows(1000, 10456, false, installed::getValue))
        assertTrue(HookIpc.allows(10123, 10456, false, installed::getValue))
    }

    @Test
    fun `missing hidden or changed host packages fail closed without caching UIDs`() {
        assertFalse(HookIpc.allows(10123, 10456, true) { throw SecurityException("hidden") })
        assertFalse(HookIpc.allows(10123, 10456, true) { throw IllegalArgumentException("missing") })
        assertFalse(HookIpc.allows(10123, 10456, true) { installed.getValue(it) + 1 })
        assertTrue(HookIpc.allows(10456, 10456, true) { error("own UID needs no lookup") })
    }

    @Test
    fun `provider checks identity before every data and event entry point`() {
        val provider = source("PrefsProvider.kt")
        for ((entry, operation) in listOf(
            "override fun query(" to "val key =",
            "override fun openFile(" to "val key =",
            "override fun call(" to "require(method",
            "override fun getType(" to "return \"vnd",
        )) {
            val body = provider.substringAfter(entry)
            val guard = body.indexOf("HookIpc.enforceCaller")
            assertTrue(entry, guard >= 0 && guard < body.indexOf(operation))
        }
        assertTrue(provider.contains("HookIpc.enforceCaller(ctx, allowSettings = false)"))
        assertTrue(provider.contains("arg in HOOK_EVENTS"))
        val auth = source("HookIpc.kt")
        assertTrue(auth.contains("Binder.getCallingUid()"))
        assertTrue(auth.contains("getApplicationInfo(pkg, 0).uid"))
        assertFalse(auth.contains("callingPackage"))
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:protectionLevel=\"signature\""))
        assertTrue(manifest.contains("<uses-permission android:name=\"${HookIpc.PERMISSION}\""))
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.contains(HookIpc.GMS))
        assertTrue(queries.contains(HookIpc.SETTINGS))
    }

    @Test
    fun `hook custom receivers require module signature and cannot bypass the provider`() {
        val fastPair = source("hook/FastPairHookEntry.kt")
        val hearable = source("hook/HearableControlHook.kt")
        assertFalse(fastPair.contains(".sendBroadcast("))
        assertFalse(hearable.contains(".sendBroadcast("))
        assertTrue(fastPair.contains("senderPermission: String? = HookIpc.PERMISSION"))
        assertTrue(fastPair.contains("registerReceiver(r, f, senderPermission, null, flag)"))
        assertTrue(fastPair.contains("registerReceiver(r, f, senderPermission, null)"))
        assertEquals(1, Regex("senderPermission = null").findAll(fastPair).count())
        assertTrue(fastPair.contains("reg(ctx, btReceiver, acl, exportedFlag, senderPermission = null)"))
        val service = source("HeadsetDetectService.kt")
        assertFalse(service.contains("f.addAction(\"com.fxxkmoondrop.secret.FASTPAIR_"))
        assertFalse(service.contains("f.addAction(AncBridge.ACTION_FP_MODE_REQUEST)"))
        assertTrue(source("GaiaBleClient.kt").contains("HookIpc.registerInternal(ctx, leAddrReceiver, f)"))
        assertTrue(source("HookIpc.kt").contains("PERMISSION, null, Context.RECEIVER_NOT_EXPORTED"))
        assertTrue(source("HookIpc.kt").contains("registerReceiver(receiver, filter, PERMISSION, null)"))
    }

    @Test
    fun `ACL events use authenticated app popup policy and private broadcasts have destinations`() {
        val fastPair = source("hook/FastPairHookEntry.kt")
        val acl = fastPair.substringAfter("val acl = IntentFilter(").substringBefore("reg(ctx, btReceiver")
        assertTrue(acl.contains("sendLeAddr("))
        assertTrue(acl.contains("acl.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)"))
        assertTrue(acl.contains("HookIpc.sendToApp(context, Intent(intent.action)"))
        assertTrue(acl.contains("intent.action == BluetoothDevice.ACTION_ACL_CONNECTED"))
        assertTrue(acl.contains("var name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)"))
        assertTrue(acl.contains("var address = intent.getStringExtra(\"android.bluetooth.device.extra.ADDRESS\")"))
        assertTrue(acl.contains("if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {\n" +
            "                        if (name.isNullOrEmpty()) name = sDataProvider?.getDeviceName()\n" +
            "                        if (!DeviceMatcher.isMoondrop(name)) return\n" +
            "                    }"))
        assertTrue(acl.indexOf("callMethod(dev, \"getAddress\")") < acl.indexOf("callMethod(dev, \"getName\")"))
        val provider = source("PrefsProvider.kt")
        for (action in listOf("BluetoothDevice.ACTION_ACL_CONNECTED", "BluetoothDevice.ACTION_ACL_DISCONNECTED")) {
            assertTrue(provider.substringAfter("private val HOOK_EVENTS").contains(action))
            assertTrue(provider.substringAfter("when (arg)").substringBefore("HeadsetReceiver().onReceive").contains(action))
        }
        assertFalse(acl.contains("postShow("))
        assertFalse(acl.contains("postDelayed("))
        assertTrue(source("PopupGate.kt").contains("i.setPackage(HookIpc.GMS)"))
        assertTrue(source("HeadsetGate.kt").contains("Intent(ACTION_MAC_UPDATED).setPackage(HookIpc.APP)"))
        assertTrue(source("PrefsProvider.kt").contains("Intent(arg).setPackage(HookIpc.APP)"))
        assertTrue(source("OverviewFragment.kt").contains("HookIpc.registerInternal(requireContext(), macUpdReceiver,"))
    }

    @Test
    fun `losing scan lock does not latch scanning and a later request can acquire it`() {
        val hook = FastPairHookEntry()
        FastPairHookEntry.sLeScanning = false
        var acquisitions = 0
        try {
            assertFalse(hook.beginLeScan { acquisitions++; false })
            assertFalse(FastPairHookEntry.sLeScanning)
            assertTrue(hook.beginLeScan { acquisitions++; true })
            assertTrue(FastPairHookEntry.sLeScanning)
            assertFalse(hook.beginLeScan { error("already scanning") })
            assertEquals(2, acquisitions)
            val body = source("hook/FastPairHookEntry.kt").substringAfter("if (!beginLeScan(::acquireScanLock))")
            assertFalse(body.substringBefore("lockAcquired = true").contains("releaseScanLock()"))
            assertTrue(body.contains("if (lockAcquired) {"))
        } finally {
            FastPairHookEntry.sLeScanning = false
        }
    }

    @Test
    fun `profile callback closes once before or after waiter timeout even when reads fail`() {
        for (late in listOf(false, true)) for (readFails in listOf(false, true)) {
            val latch = CountDownLatch(1)
            var closes = 0
            val proxy = object : BluetoothProfile {
                override fun getConnectedDevices(): List<BluetoothDevice> {
                    if (readFails) throw SecurityException("Bluetooth permission revoked")
                    return emptyList()
                }
                override fun getDevicesMatchingConnectionStates(states: IntArray?) = emptyList<BluetoothDevice>()
                override fun getConnectionState(device: BluetoothDevice?) = BluetoothProfile.STATE_DISCONNECTED
            }
            val listener = HeadsetGate.proxyListener(AtomicReference<String?>(), latch) { profile, received ->
                assertEquals(BluetoothProfile.A2DP, profile)
                assertSame(proxy, received)
                closes++
            }
            if (late) assertFalse(latch.await(0, TimeUnit.MILLISECONDS))
            listener.onServiceConnected(BluetoothProfile.A2DP, proxy)
            assertTrue(latch.await(0, TimeUnit.MILLISECONDS))
            assertEquals(1, closes)
        }
    }

    private fun source(name: String) = repoFile("src/com/fxxkmoondrop/secret/$name").readText()
    private fun repoFile(path: String): File = sequenceOf(File(path), File("../$path"))
        .first { it.isFile }
}
