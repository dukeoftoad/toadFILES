package com.toadfiles.app

import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.ui.components.OpRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for audit v2.0.0 A1 — the op engine must be single-flight and its
 * cancel semantics must not race the op's own progress/result publication:
 *
 *  1. a new run cancels the previous op and starts only after it fully unwound;
 *  2. a cancelled op never publishes [onDone] (no result dialog for a dead op);
 *  3. [OpRunner.cancel]'s [after] callback runs only after the op stopped, so no
 *     progress tick can land after the overlay was cleared (the resurrection race).
 */
class OpRunnerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newRunner() = OpRunner(scope, Dispatchers.IO)

    /** Polls [cond] on a busy loop — the repo's standard latch-free await helper. */
    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun `a new run cancels the previous op and starts only after it fully unwound`() {
        val runner = newRunner()
        val firstStarted = CountDownLatch(1)
        val firstUnwound = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val firstDoneCount = AtomicInteger(0)
        val secondDoneCount = AtomicInteger(0)

        runner.run(
            op = { _ ->
                firstStarted.countDown()
                try {
                    // Park until the runner cancels this op; every park iteration is a
                    // cancellation checkpoint, like the real archive pipelines.
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        delay(10)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    OpResult(1, 0, 0, false)
                } finally {
                    firstUnwound.countDown()
                }
            },
            onProgress = {},
            onDone = { firstDoneCount.incrementAndGet() },
        )
        assertTrue("first op never started", firstStarted.await(5, TimeUnit.SECONDS))

        runner.run(
            op = { _ ->
                // The second op may only run after the first has fully unwound —
                // cancelAndJoin guarantees it; assert it from inside the op itself.
                assertTrue("second op started before the first unwound", firstUnwound.await(5, TimeUnit.SECONDS))
                secondStarted.countDown()
                OpResult(2, 0, 0, false)
            },
            onProgress = {},
            onDone = { secondDoneCount.incrementAndGet() },
        )

        assertTrue("first op was never cancelled/unwound", firstUnwound.await(5, TimeUnit.SECONDS))
        assertTrue("second op never started", secondStarted.await(5, TimeUnit.SECONDS))
        assertTrue("second onDone never fired", awaitTrue { secondDoneCount.get() == 1 })
        assertEquals("the cancelled first op must never publish a result", 0, firstDoneCount.get())
    }

    @Test
    fun `cancel stops the op and onDone never fires`() {
        val runner = newRunner()
        val started = CountDownLatch(1)
        val cleared = AtomicBoolean(false)
        val doneCount = AtomicInteger(0)

        runner.run(
            op = { _ ->
                started.countDown()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE")
                OpResult(1, 0, 0, false)
            },
            onProgress = {},
            onDone = { doneCount.incrementAndGet() },
        )
        assertTrue(started.await(5, TimeUnit.SECONDS))

        runner.cancel { cleared.set(true) }

        assertTrue("cancel's after() never ran", awaitTrue { cleared.get() })
        assertEquals("a cancelled op must not publish a result", 0, doneCount.get())
        assertFalse("runner must be idle after cancel", runner.isBusy)
    }

    @Test
    fun `no progress tick can land after cancel's after() ran`() {
        val runner = newRunner()
        val ticks = AtomicInteger(0)
        val clearedAtTicks = AtomicInteger(-1)

        runner.run(
            op = { onProgress ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    onProgress(com.toadfiles.app.domain.OpProgress(1, 2, "tick"))
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE")
                OpResult(1, 0, 0, false)
            },
            onProgress = { ticks.incrementAndGet() },
            onDone = {},
        )
        assertTrue("op never produced progress", awaitTrue { ticks.get() >= 3 })

        // after() runs after the op fully stopped: snapshot the tick count there and
        // prove it never moves again (the old code cleared the overlay before the op
        // stopped, so a tick landing after the clear resurrected the overlay).
        runner.cancel { clearedAtTicks.set(ticks.get()) }
        assertTrue(awaitTrue { clearedAtTicks.get() >= 0 })

        Thread.sleep(300) // any resurrecting tick would land well within this window
        assertEquals(
            "progress ticked after the overlay was cleared",
            clearedAtTicks.get(),
            ticks.get(),
        )
    }

    @Test
    fun `cancel with no running op runs after immediately`() {
        val runner = newRunner()
        val cleared = AtomicBoolean(false)
        runner.cancel { cleared.set(true) }
        assertTrue(cleared.get())
    }
}
