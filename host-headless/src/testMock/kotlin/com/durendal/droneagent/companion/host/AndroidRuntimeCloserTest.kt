package com.durendal.droneagent.companion.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRuntimeCloserTest {
    @Test
    fun `neutral timeout keeps resources alive and late confirmation closes exactly once`() {
        var neutralAttempts = 0
        var cleanupCalls = 0
        val closer =
            closer(
                awaitNeutral = {
                    neutralAttempts++
                    neutralAttempts > 1
                },
                cleanup = { cleanupCalls++ },
            )

        assertEquals(RuntimeCloseResult.TIMED_OUT, closer.closeWithin(25L))
        assertEquals(0, cleanupCalls)
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(250L))
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(250L))
        assertEquals(2, neutralAttempts)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `repeated unconfirmed neutral never becomes a false closed result`() {
        var cleanupCalls = 0
        val closer = closer(awaitNeutral = { false }, cleanup = { cleanupCalls++ })

        assertEquals(RuntimeCloseResult.TIMED_OUT, closer.closeWithin(10L))
        assertEquals(RuntimeCloseResult.TIMED_OUT, closer.closeWithin(10L))
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun `transient neutral observation failure does not poison a confirmed retry`() {
        var neutralAttempts = 0
        var cleanupCalls = 0
        val closer =
            closer(
                awaitNeutral = {
                    neutralAttempts++
                    if (neutralAttempts == 1) error("synthetic neutral observation failure")
                    true
                },
                cleanup = { cleanupCalls++ },
            )

        assertEquals(RuntimeCloseResult.TIMED_OUT, closer.closeWithin(25L))
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(250L))
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(250L))
        assertEquals(2, neutralAttempts)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `interrupted neutral waiter restores the caller interrupt`() {
        val neverConfirmed = CountDownLatch(1)
        val closer =
            closer(
                awaitNeutral = {
                    neverConfirmed.await()
                    true
                },
                cleanup = { error("cleanup must not run without confirmed neutral") },
            )
        val result = AtomicReference<RuntimeCloseResult>()
        val interruptRestored = AtomicBoolean(false)
        val waiter = Thread {
            Thread.currentThread().interrupt()
            result.set(closer.closeWithin(250L))
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }.also(Thread::start)
        waiter.join(2_000L)

        assertTrue("interrupted neutral wait must remain bounded", !waiter.isAlive)
        assertEquals(RuntimeCloseResult.TIMED_OUT, result.get())
        assertTrue("closer must restore the caller interrupt", interruptRestored.get())
    }

    @Test
    fun `interrupted server stop restores interrupt after core stop is attempted`() {
        val interruptibleStop = CountDownLatch(1)
        var coreStopCalls = 0
        var neutralCalls = 0
        var cleanupCalls = 0
        val closer =
            AndroidRuntimeCloser(
                monotonicNanos = System::nanoTime,
                stopServerWithin = { interruptibleStop.await() },
                initiateCoreStop = { coreStopCalls++ },
                awaitNeutral = {
                    neutralCalls++
                    true
                },
                cleanupAfterNeutral = { cleanupCalls++ },
            )
        val outcome = invokePreInterrupted(closer)

        assertEquals(RuntimeCloseResult.TIMED_OUT, outcome.result)
        assertTrue(outcome.interruptRestored)
        assertEquals(1, coreStopCalls)
        assertEquals(0, neutralCalls)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun `interrupted core stop restores interrupt before neutral wait`() {
        val interruptibleStop = CountDownLatch(1)
        var neutralCalls = 0
        var cleanupCalls = 0
        val closer =
            AndroidRuntimeCloser(
                monotonicNanos = System::nanoTime,
                stopServerWithin = {},
                initiateCoreStop = { interruptibleStop.await() },
                awaitNeutral = {
                    neutralCalls++
                    true
                },
                cleanupAfterNeutral = { cleanupCalls++ },
            )
        val outcome = invokePreInterrupted(closer)

        assertEquals(RuntimeCloseResult.TIMED_OUT, outcome.result)
        assertTrue(outcome.interruptRestored)
        assertEquals(0, neutralCalls)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun `server and neutral share one absolute deadline budget`() {
        var nowNanos = 0L
        var serverBudget = 0L
        var neutralBudget = 0L
        val closer =
            AndroidRuntimeCloser(
                monotonicNanos = { nowNanos },
                stopServerWithin = { budget ->
                    serverBudget = budget
                    nowNanos += 20_000_000L
                },
                initiateCoreStop = {},
                awaitNeutral = { budget ->
                    neutralBudget = budget
                    true
                },
                cleanupAfterNeutral = {},
            )

        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(100L))
        assertEquals(25L, serverBudget)
        assertEquals(80L, neutralBudget)
    }

    @Test
    fun `blocking cleanup returns timed out and a later call observes completion`() {
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val closer =
            closer(
                awaitNeutral = { true },
                cleanup = {
                    cleanupStarted.countDown()
                    releaseCleanup.await()
                },
            )

        val startedAt = System.nanoTime()
        assertEquals(RuntimeCloseResult.TIMED_OUT, closer.closeWithin(25L))
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(cleanupStarted.await(1L, TimeUnit.SECONDS))
        assertTrue("close must respect its bounded wait", elapsedMillis < 500L)

        releaseCleanup.countDown()
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(1_000L))
    }

    @Test
    fun `failed cleanup attempt retains ownership for an explicit retry`() {
        var cleanupCalls = 0
        val closer =
            closer(
                awaitNeutral = { true },
                cleanup = {
                    cleanupCalls++
                    if (cleanupCalls == 1) error("synthetic transient cleanup failure")
                },
            )

        assertEquals(RuntimeCloseResult.FAILED, closer.closeWithin(1_000L))
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(1_000L))
        assertEquals(RuntimeCloseResult.CLOSED, closer.closeWithin(1_000L))
        assertEquals(2, cleanupCalls)
    }

    private fun closer(
        awaitNeutral: (Long) -> Boolean,
        cleanup: () -> Unit,
    ) = AndroidRuntimeCloser(
        monotonicNanos = System::nanoTime,
        stopServerWithin = { _ -> },
        initiateCoreStop = {},
        awaitNeutral = awaitNeutral,
        cleanupAfterNeutral = cleanup,
    )

    private fun invokePreInterrupted(closer: AndroidRuntimeCloser): InterruptedCloseOutcome {
        val result = AtomicReference<RuntimeCloseResult>()
        val interruptRestored = AtomicBoolean(false)
        val waiter = Thread {
            Thread.currentThread().interrupt()
            result.set(closer.closeWithin(250L))
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }.also(Thread::start)
        waiter.join(2_000L)
        assertTrue("interrupted close must remain bounded", !waiter.isAlive)
        return InterruptedCloseOutcome(checkNotNull(result.get()), interruptRestored.get())
    }

    private data class InterruptedCloseOutcome(
        val result: RuntimeCloseResult,
        val interruptRestored: Boolean,
    )
}
