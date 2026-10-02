package com.chronicle.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreDeviceTest {

    @Test
    fun keystoreSecretsRoundTripAcrossStoreRecreation() {
        val store = SecretStore.create()
        assertNotNull(store)
        val first = requireNotNull(store).encrypt("chronicle-device-test")
        val second = store.encrypt("chronicle-device-test")
        assertNotEquals(first, second)
        val reopened = SecretStore.create()
        assertNotNull(reopened)
        assertEquals("chronicle-device-test", requireNotNull(reopened).decrypt(first))
        assertEquals("chronicle-device-test", reopened.decrypt(second))
    }
}
