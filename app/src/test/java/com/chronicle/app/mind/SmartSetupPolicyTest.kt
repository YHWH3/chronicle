package com.chronicle.app.mind

import androidx.work.NetworkType
import org.junit.Assert.*
import org.junit.Test

class SmartSetupPolicyTest {
    @Test fun `Today setup respects Basic cloud and Advanced custom choices`() {
        assertTrue(smartSetupAllowed(com.chronicle.app.data.MindBackend.AUTO, SmartModels.DEFAULT_ID))
        assertTrue(smartSetupAllowed(com.chronicle.app.data.MindBackend.ON_DEVICE, SmartModels.DEFAULT_ID))
        listOf(com.chronicle.app.data.MindBackend.QUIET_PARSER, com.chronicle.app.data.MindBackend.OPENROUTER,
            com.chronicle.app.data.MindBackend.CUSTOM).forEach { assertFalse(smartSetupAllowed(it, SmartModels.DEFAULT_ID)) }
        assertFalse(smartSetupAllowed(com.chronicle.app.data.MindBackend.ON_DEVICE, "custom"))
    }
    @Test fun `failure remains visible during retry backoff and Wi-Fi wait is explicit`() {
        assertEquals(SmartSetupPhase.FAILED, transferPhase(ModelState.Failed("offline"), true, false, false))
        assertEquals(SmartSetupPhase.WAITING, transferPhase(ModelState.NotInstalled, true, false, false))
        assertEquals(SmartSetupPhase.DOWNLOADING, transferPhase(ModelState.Connecting, true, false, true))
    }
    @Test fun `offer requires a note sufficient RAM and a missing model`() {
        assertTrue(smartOfferVisible(1, true, false, 0, 100))
        assertFalse(smartOfferVisible(0, true, false, 0, 100))
        assertFalse(smartOfferVisible(1, false, false, 0, 100))
        assertFalse(smartOfferVisible(1, true, true, 0, 100))
    }
    @Test fun `Not now hides for seven days`() {
        val week = 7L * 24 * 60 * 60 * 1000
        assertFalse(smartOfferVisible(1, true, false, 1, week))
        assertTrue(smartOfferVisible(1, true, false, 1, week + 1))
    }
    @Test fun `mobile opt-in changes network constraint and battery protection remains`() {
        val wifi = ModelDownloads.constraints(false)
        val mobile = ModelDownloads.constraints(true)
        assertEquals(NetworkType.UNMETERED, wifi.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, mobile.requiredNetworkType)
        assertTrue(wifi.requiresBatteryNotLow())
        assertTrue(mobile.requiresBatteryNotLow())
        assertEquals("model-llm/qwen2_5_1_5b", ModelDownloads.name(SmartModels.all.first().packId))
    }
}
