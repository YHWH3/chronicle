package com.chronicle.app.core

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeSuspendTest {

    @Test
    fun `success maps to Ok`() = runTest {
        val result = safeSuspend { 42 }
        assertEquals(OperationResult.Ok(42), result)
    }

    @Test
    fun `failure maps to Failed`() = runTest {
        val result = safeSuspend<Int> { throw IOException("disk gone\nstack noise") }
        assertTrue(result is OperationResult.Failed)
        assertEquals(
            "disk gone",
            sanitizeReason((result as OperationResult.Failed).error),
        )
    }

    @Test
    fun `cancellation propagates instead of being captured`() = runTest {
        var rethrown = false
        try {
            safeSuspend<Unit> { throw CancellationException("scope going away") }
        } catch (ce: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    @Test
    fun `sanitizeReason falls back to class name and caps length`() {
        assertEquals("IOException", sanitizeReason(IOException()))
        val long = "x".repeat(500)
        assertEquals(120, sanitizeReason(IOException(long)).length)
    }
}
