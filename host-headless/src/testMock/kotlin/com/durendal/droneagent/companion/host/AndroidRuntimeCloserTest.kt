package com.durendal.droneagent.companion.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}
