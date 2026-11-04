package com.fxxkmoondrop.secret

import org.junit.Assert.assertEquals
import org.junit.Test

class FastPairSelectionTest {
    @Test fun cachedModeRequiresConnectedAndGaiaReady() {
        for (mode in -1..5) {
            assertEquals(-1, FastPairPanel.selectedMode(mode, connected = false, gaiaReady = false))
            assertEquals(-1, FastPairPanel.selectedMode(mode, connected = false, gaiaReady = true))
            assertEquals(-1, FastPairPanel.selectedMode(mode, connected = true, gaiaReady = false))
            assertEquals(mode, FastPairPanel.selectedMode(mode, connected = true, gaiaReady = true))
        }
    }
}
