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

    // ---------- fullySucceeded (v1.7.4 audit: delete-after gate) ----------

    @Test
    fun `fullySucceeded requires successes and no failures`() {
        assertTrue(OpResult(succeeded = 3, skipped = 0, failed = 0).fullySucceeded)
        assertFalse(OpResult(succeeded = 0, skipped = 0, failed = 0).fullySucceeded)
        assertFalse(OpResult(succeeded = 2, skipped = 0, failed = 1).fullySucceeded)
        assertFalse(OpResult(succeeded = 0, skipped = 2, failed = 0).fullySucceeded)
    }

    @Test
    fun `fullySucceeded is false when cancelled even with successes`() {
        // A cancelled run rolls back / stays incomplete: the sources must survive.
        assertFalse(OpResult(succeeded = 2, skipped = 0, failed = 0, cancelled = true).fullySucceeded)
    }

    @Test
    fun `skips alone do not spoil a fully successful run`() {
        // Skipped collisions are expected outcomes (replace off), not errors.
        assertTrue(OpResult(succeeded = 2, skipped = 1, failed = 0).fullySucceeded)
    }

    // ---------- passwordRequired (v1.8.0 password prompt gate) ----------

    @Test
    fun `passwordRequired defaults to false`() {
        // Every pre-v1.8.0 constructor call site stays valid and unchanged.
        assertFalse(OpResult(succeeded = 1, skipped = 0, failed = 0).passwordRequired)
        assertFalse(OpResult(succeeded = 0, skipped = 0, failed = 1, failures = listOf("x")).passwordRequired)
    }

    @Test
    fun `passwordRequired is carried when set and does not affect the summary`() {
        val r = OpResult(succeeded = 0, skipped = 0, failed = 1, failures = listOf("Password required"), passwordRequired = true)
        assertTrue(r.passwordRequired)
        assertEquals("0 done, 1 failed", r.summary)
        assertFalse(r.fullySucceeded)
    }
}
