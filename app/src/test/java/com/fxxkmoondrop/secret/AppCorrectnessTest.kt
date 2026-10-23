package com.fxxkmoondrop.secret

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AppCorrectnessTest {
    @After fun resetProfile() {
        DeviceControlBridge.applyProfile(AncProfileLib.DEFAULT_DC, null)
        DeviceControlBridge.reset()
    }

    @Test fun profileEditsAndResetTakeEffectWithoutReconnect() {
        val values = mutableMapOf<String, Any>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt", "getString" -> values[args!![0]] ?: args[1]
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
        val profile = AncProfileLib.DEFAULT_DC
        DeviceControlBridge.applyProfile(profile, prefs)
        DeviceControlBridge.onGainResult(2)
        assertEquals(0, DeviceControlBridge.getGainLevel())

        values["gain_map_0"] = -1
        values["gain_map_1"] = 2
        values["track_label_1"] = "Custom tracking"
        DeviceControlBridge.applyProfile(profile, prefs)
        assertNull(DeviceControlBridge.gainDeviceCode(0))
        assertEquals(2, DeviceControlBridge.gainDeviceCode(1))
        assertEquals(1, DeviceControlBridge.getGainLevel())
        assertEquals("Custom tracking", DeviceControlBridge.trackingLabels()[1])

        values.clear()
        DeviceControlBridge.applyProfile(profile, prefs)
        assertEquals(2, DeviceControlBridge.gainDeviceCode(0))
        assertEquals(0, DeviceControlBridge.getGainLevel())
        assertArrayEquals(profile.trackingLabels, DeviceControlBridge.trackingLabels())
    }

    @Test fun hiddenGainRejectsCommandsWithoutRenumberingVisibleLevels() {
        DeviceControlBridge.applyProfile(AncProfileLib.DEFAULT_DC.copy(gainMap = intArrayOf(-1, 1, 0)), null)
        DeviceControlBridge.onGainResult(1)
        val version = DeviceControlBridge.getVersion()
        for (index in listOf(-1, 0, 3, 255)) {
            assertNull(DeviceControlBridge.gainDeviceCode(index))
            DeviceControlBridge.setGain(index)
        }
        assertEquals(version, DeviceControlBridge.getVersion())
        assertEquals(1, DeviceControlBridge.getGainLevel())
        assertEquals(1, DeviceControlBridge.gainDeviceCode(1))
        assertEquals(0, DeviceControlBridge.gainDeviceCode(2))
        DeviceControlBridge.onGainResult(0)
        assertEquals(2, DeviceControlBridge.getGainLevel())
        DeviceControlBridge.onGainResult(-1)
        assertEquals(-1, DeviceControlBridge.getGainLevel())
    }
}
