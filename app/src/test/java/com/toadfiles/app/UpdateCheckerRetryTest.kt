package com.toadfiles.app

import com.toadfiles.app.data.UpdateCheckException
import com.toadfiles.app.data.UpdateCheckRateLimitException
import com.toadfiles.app.data.UpdateCheckRetryableException
import com.toadfiles.app.data.GithubReleaseChecker
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The v1.7.1 connection-hardening policy, exercised without any network:
 * one retry on transient trouble (plain IOException / HTTP 5xx), never on
 * rate limits (403), other 4xx or malformed payloads — and every surfaced
 * failure carries a ready-to-show message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckerRetryTest {

    private class Counter {
        var calls = 0
            private set

        fun tick() { calls++ }
    }

    @Test
    fun `transient IOException is retried once and then succeeds`() {
        val counter = Counter()
        val result = GithubReleaseChecker.runWithRetry(2) { attempt ->
            counter.tick()
            if (attempt == 0) throw IOException("connection reset") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, counter.calls) // 1 fail + 1 retry
    }

    @Test
    fun `exhausted retries rethrow the last transient IOException`() {
        try {
            GithubReleaseChecker.runWithRetry(2) { _ -> throw IOException("no route to host") }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("no route to host", e.message)
        }
    }

    @Test
    fun `success on the first attempt never hits the retry path`() {
        val calls = intArrayOf(0)
        val out = GithubReleaseChecker.runWithRetry(2) { calls[0]++; "done" }
        assertEquals("done", out)
        assertEquals(1, calls[0])
    }

    @Test
    fun `rate limit 403 is never retried`() {
        val calls = intArrayOf(0)
        try {
            GithubReleaseChecker.runWithRetry(2) {
                calls[0]++
                throw UpdateCheckRateLimitException()
            }
            fail("expected rate-limit exception")
        } catch (e: UpdateCheckRateLimitException) {
            assertTrue(e.message!!.contains("rate limit"))
        }
        assertEquals(1, calls[0])
    }

    @Test
    fun `HTTP 5xx is retryable and surfaces its friendly message`() {
        val calls = intArrayOf(0)
        try {
            GithubReleaseChecker.runWithRetry(2) {
                calls[0]++
                throw UpdateCheckRetryableException(503)
            }
            fail("expected retryable exception")
        } catch (e: UpdateCheckRetryableException) {
            assertTrue(e.message!!.contains("503"))
            assertTrue(e.message!!.contains("try again"))
        }
        assertEquals(2, calls[0]) // 5xx IS retried
    }

    @Test
    fun `other 4xx is permanent and not retried`() {
        val calls = intArrayOf(0)
        try {
            GithubReleaseChecker.runWithRetry(2) {
                calls[0]++
                throw GithubReleaseChecker.exceptionFor(404)
            }
            fail("expected UpdateCheckException")
        } catch (e: UpdateCheckException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals(1, calls[0])
    }

    @Test
    fun `malformed payload JSON is not retried`() {
        val calls = intArrayOf(0)
        try {
            GithubReleaseChecker.runWithRetry(2) {
                calls[0]++
                throw JSONException("bad payload")
            }
            fail("expected JSONException")
        } catch (_: JSONException) {
        }
        assertEquals(1, calls[0])
    }

    @Test
    fun `cancellation is propagated untouched, never retried`() {
        val calls = intArrayOf(0)
        try {
            GithubReleaseChecker.runWithRetry(2) {
                calls[0]++
                throw CancellationException("user left About")
            }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(1, calls[0])
    }

    // ---------- status-code classification ----------

    @Test
    fun `exceptionFor maps 403, 5xx and other 4xx to distinct friendly types`() {
        val rate = GithubReleaseChecker.exceptionFor(403)
        assertTrue(rate is UpdateCheckRateLimitException)
        assertTrue(rate.message!!.contains("rate limit"))

        val server = GithubReleaseChecker.exceptionFor(502)
        assertTrue(server is UpdateCheckRetryableException)
        assertTrue(server.message!!.contains("502"))

        val other = GithubReleaseChecker.exceptionFor(418)
        assertTrue(other is UpdateCheckException && other !is UpdateCheckRetryableException)
        assertTrue(other.message!!.contains("418"))
    }
}
