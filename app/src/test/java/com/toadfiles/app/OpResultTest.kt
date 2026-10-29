package com.toadfiles.app

import com.toadfiles.app.domain.OpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpResultTest {

    @Test
    fun `summary shows done count only when nothing else happened`() {
        assertEquals("3 done", OpResult(succeeded = 3, skipped = 0, failed = 0).summary)
    }

    @Test
    fun `summary includes skipped and failed counts when present`() {
        assertEquals(
            "2 done, 1 skipped, 1 failed",
            OpResult(succeeded = 2, skipped = 1, failed = 1).summary,
        )
    }

    @Test
    fun `summary appends cancelled marker`() {
        val r = OpResult(succeeded = 1, skipped = 0, failed = 0, cancelled = true)
        assertEquals("1 done (cancelled)", r.summary)
        assertTrue(r.cancelled)
    }

    @Test
    fun `zero-success result is allowed and reported honestly`() {
        val r = OpResult(succeeded = 0, skipped = 0, failed = 2, failures = listOf("a.txt: error", "b.txt: error"))
        assertEquals("0 done, 2 failed", r.summary)
        assertEquals(2, r.failures.size)
        assertFalse(r.cancelled)
    }
}
