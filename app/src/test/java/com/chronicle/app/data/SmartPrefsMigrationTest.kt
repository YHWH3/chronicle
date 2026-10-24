package com.chronicle.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPrefsMigrationTest {
    @Test fun `existing custom on-device model remains selected on upgrade`() {
        assertEquals("custom", migratedSmartModelId(null, "ON_DEVICE"))
    }
    @Test fun `new catalog selection and default Qwen survive migration`() {
        assertEquals("llama3_2_1b", migratedSmartModelId("llama3_2_1b", "ON_DEVICE"))
        assertEquals(com.chronicle.app.mind.SmartModels.DEFAULT_ID, migratedSmartModelId(null, "QUIET_PARSER"))
        assertEquals(com.chronicle.app.mind.SmartModels.DEFAULT_ID, migratedSmartModelId(null, null))
    }
    @Test fun `silent old default and missing prefs migrate to Auto`() {
        assertEquals(MindBackend.AUTO, migratedMindBackend(null, null))
        assertEquals(MindBackend.AUTO, migratedMindBackend("QUIET_PARSER", null))
        assertEquals(MindBackend.AUTO, migratedMindBackend("QUIET_PARSER", false))
    }
    @Test fun `explicit Basic and existing nondefault choices survive`() {
        assertEquals(MindBackend.QUIET_PARSER, migratedMindBackend("QUIET_PARSER", true))
        listOf(MindBackend.ON_DEVICE, MindBackend.CUSTOM, MindBackend.OPENROUTER).forEach {
            assertEquals(it, migratedMindBackend(it.name, null))
            assertEquals(it, migratedMindBackend(it.name, true))
        }
    }
}
