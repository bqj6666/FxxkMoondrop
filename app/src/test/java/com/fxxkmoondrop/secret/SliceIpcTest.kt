package com.fxxkmoondrop.secret

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Non-UI policy and routing regressions; real Binder/grant/PendingIntent enforcement needs a device. */
class SliceIpcTest {
    @Test
    fun `system lifecycle exemption never authorizes binding or arbitrary calls`() {
        for (method in listOf("pin", "unpin", "get_permissions")) {
            assertTrue(HearableSliceProvider.isSystemLifecycleCall(1000, method))
            for (uid in listOf(0, 2000, 10123, 101000)) {
                assertFalse(HearableSliceProvider.isSystemLifecycleCall(uid, method))
            }
        }
        for (method in listOf("bind_slice", "map_slice", "map_only", "get_descendants", "", "hook_event")) {
            assertFalse(method, HearableSliceProvider.isSystemLifecycleCall(1000, method))
        }
    }

    @Test
    fun `only the exact GMS content endpoint can receive a replacement slice`() {
        val authority = "com.google.android.gms.nearby.fastpair"
        assertTrue(HearableSliceProvider.isHostUri("content", authority, "/hearable_control"))
        for (scheme in listOf(null, "file", "https")) {
            assertFalse(HearableSliceProvider.isHostUri(scheme, authority, "/hearable_control"))
        }
        for (host in listOf(null, "evil.example", "$authority.evil", HearableSliceProvider.AUTHORITY)) {
            assertFalse(HearableSliceProvider.isHostUri("content", host, "/hearable_control"))
        }
        for (path in listOf(null, "/scan", "/hearable_control/other", "/other_hearable_control")) {
            assertFalse(HearableSliceProvider.isHostUri("content", authority, path))
        }
    }

    @Test
    fun `resource context does not define the process trust boundary`() {
        val hosts = mapOf(HookIpc.GMS to 10123, HookIpc.SETTINGS to 10456)
        // GMS with module resources is still UID 10123, not the module context's UID 10789.
        assertTrue(HookIpc.allows(10123, 10123, true, hosts::getValue))
        assertTrue(HookIpc.allows(10456, 10123, true, hosts::getValue))
        assertFalse(HookIpc.allows(10789, 10123, true, hosts::getValue))
        assertFalse(HookIpc.allows(1000, 10123, true, hosts::getValue))
        val auth = source("HookIpc.kt")
        assertTrue(auth.contains("allows(Binder.getCallingUid(), Process.myUid(),"))
        assertFalse(auth.contains("context.applicationInfo.uid"))
    }

    @Test
    fun `module authenticates before grants identity clearing and action creation`() {
        val provider = source("HearableSliceProvider.kt")
        // A native provider avoids AndroidX's wrapper bypassing an overridden call method.
        assertTrue(provider.contains("import android.app.slice.SliceProvider"))
        assertFalse(provider.contains("import androidx.slice.SliceProvider"))
        val call = provider.substringAfter("override fun call(").substringBefore("override fun onBindSlice(")
        assertBefore(call, "HookIpc.enforceCaller(ctx)", "Binder.clearCallingIdentity()")
        assertBefore(call, "if (!isModuleUri(uri)) return null", "grantSlicePermission(pkg, uri)")
        assertTrue(call.contains("pkg == HookIpc.SETTINGS || pkg == HookIpc.GMS"))
        assertTrue(call.contains("getPackagesForUid(caller)"))
        assertTrue(call.contains("Binder.restoreCallingIdentity(token)"))
        val bind = provider.substringAfter("override fun onBindSlice(").substringBefore("companion object")
        assertBefore(bind, "HookIpc.enforceCaller(ctx)", "Binder.clearCallingIdentity()")
        assertBefore(bind, "Binder.clearCallingIdentity()", "buildLocal(ctx, sliceUri)")
        assertTrue(bind.contains("Binder.restoreCallingIdentity(token)"))
        assertTrue(provider.contains("@Synchronized"))
        assertTrue(provider.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(provider.contains("Intent(ctx, NotifActionReceiver::class.java)"))
        val fetch = provider.substringAfter("fun buildFor(").substringBefore("private fun buildLocal(")
        assertBefore(fetch, "HookIpc.enforceCaller(ctx)", "ctx.contentResolver.call(")
        assertFalse(fetch.contains("PendingIntent.getBroadcast"))
        assertTrue(fetch.contains("android.app.slice.Slice?"))
    }

    @Test
    fun `GMS authenticates transport caller before provider code can clear identity`() {
        val hook = source("hook/HearableControlHook.kt").substringAfter("private fun attachSliceHookFor(")
            .substringBefore("fun install(")
        assertTrue(hook.contains("cls.getMethod(\"call\", String::class.java, String::class.java,"))
        assertBefore(hook, "HookIpc.enforceCaller(ctx)", "chain.proceed()")
        assertBefore(hook, "if (!eligible) return@intercept res", "HearableSliceProvider.buildFor(")
        assertTrue(hook.contains("chain.args.getOrNull(0) == \"com.google.android.gms.nearby.fastpair\""))
        assertTrue(hook.contains("provider?.context"))
        assertTrue(hook.contains("putParcelable(\"slice\", replacement)"))
        assertFalse(hook.contains("as? android.content.Context"))
        val settings = source("XposedEntry.kt")
        assertTrue(settings.contains("HearableSliceProvider.isHostUri(uri.scheme, uri.authority, uri.path)"))
        assertFalse(settings.contains("grantSlicePermission"))
        val manager = settings.substringAfter("private fun hookSliceManager(").substringBefore("private fun sliceHasContent(")
        assertBefore(manager, "HookIpc.enforceCaller(ctx)", "chain.proceed()")
        assertBefore(manager, "if (!eligible) return@intercept res", "HearableSliceProvider.buildFor(")
    }

    @Test
    fun `preferences commands run as module only after authentication and anonymous MIME is empty`() {
        val provider = source("PrefsProvider.kt")
        val query = provider.substringAfter("override fun query(").substringBefore("override fun openFile(")
        assertBefore(query, "HookIpc.enforceCaller", "Binder.clearCallingIdentity()")
        assertBefore(query, "Binder.clearCallingIdentity()", "handleDcCmd(uri)")
        assertTrue(query.contains("Binder.restoreCallingIdentity(token)"))
        assertTrue(provider.contains("override fun getTypeAnonymous(uri: Uri): String? = null"))
        val panel = source("hook/HalfSheetPanelHook.kt")
        assertTrue(panel.contains("content://com.fxxkmoondrop.secret.prefs/dc_cmd"))
        assertFalse(panel.contains("sendBroadcast"))
    }

    private fun assertBefore(body: String, first: String, second: String) {
        assertTrue("Missing or misplaced $first before $second", body.indexOf(first) >= 0 &&
            body.indexOf(first) < body.indexOf(second))
    }

    private fun source(name: String): String {
        val path = "src/com/fxxkmoondrop/secret/$name"
        return sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
    }
}
