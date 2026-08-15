package com.durendal.droneagent.companion.host

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One-deadline shutdown coordinator for the Android composition.
 *
 * A neutral timeout keeps the executor/audit/adapter alive so a later callback can still be
 * observed. Cleanup after confirmed neutral runs on a dedicated daemon and is also bounded by the
 * same caller deadline; a later close call can observe its eventual completion without replaying
 * any close side effect.
 */
internal class AndroidRuntimeCloser(
    private val monotonicNanos: () -> Long,
    private val stopServerWithin: (Long) -> Unit,
    private val initiateCoreStop: () -> Unit,
    private val awaitNeutral: (Long) -> Boolean,
    private val cleanupAfterNeutral: () -> Unit,
    private val cleanupExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "headless-runtime-cleanup").apply { isDaemon = true }
        },
) {
    private var stopInitiated = false
    private var closeFailed = false
    private var cleanupFuture: Future<Unit>? = null
    private var terminalResult: RuntimeCloseResult? = null

    @Synchronized
    fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        terminalResult?.let { return it }
        val deadlineNanos = shutdownDeadline(monotonicNanos(), timeoutMillis)

        if (!stopInitiated) {
            stopInitiated = true
            val serverBudget =
                (remainingMillis(deadlineNanos) / 4L)
                    .coerceIn(1L, MAX_SERVER_CLOSE_MILLIS)
            var initiationInterrupted = false
            try {
                stopServerWithin(serverBudget)
            } catch (_: InterruptedException) {
                closeFailed = true
                initiationInterrupted = true
            } catch (_: Throwable) {
                closeFailed = true
            }
            try {
                initiateCoreStop()
            } catch (_: InterruptedException) {
                closeFailed = true
                initiationInterrupted = true
            } catch (_: Throwable) {
                closeFailed = true
            }
            if (initiationInterrupted) {
                Thread.currentThread().interrupt()
                return RuntimeCloseResult.TIMED_OUT
            }
        }

        var cleanup = cleanupFuture
        if (cleanup == null) {
            val neutralBudget = remainingMillis(deadlineNanos).coerceAtLeast(1L)
            val neutralConfirmed =
                try {
                    awaitNeutral(neutralBudget)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return RuntimeCloseResult.TIMED_OUT
                } catch (_: Throwable) {
                    false
                }
            if (!neutralConfirmed) return RuntimeCloseResult.TIMED_OUT
            cleanup = cleanupExecutor.submit(Callable { cleanupAfterNeutral() })
            cleanupFuture = cleanup
        }

        val cleanupToAwait = checkNotNull(cleanup)
        val cleanupBudget = remainingMillis(deadlineNanos).coerceAtLeast(1L)
        return try {
            cleanupToAwait.get(cleanupBudget, TimeUnit.MILLISECONDS)
            complete(if (closeFailed) RuntimeCloseResult.FAILED else RuntimeCloseResult.CLOSED)
        } catch (_: TimeoutException) {
            RuntimeCloseResult.TIMED_OUT
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            RuntimeCloseResult.TIMED_OUT
        } catch (_: Throwable) {
            // Cleanup ownership remains with this closer. A later explicit safe-stop may retry
            // after a transient source/adapter failure instead of freezing a false terminal state.
            cleanupFuture = null
            RuntimeCloseResult.FAILED
        }
    }

    private fun complete(result: RuntimeCloseResult): RuntimeCloseResult {
        terminalResult = result
        cleanupExecutor.shutdown()
        return result
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remainingNanos = (deadlineNanos - monotonicNanos()).coerceAtLeast(0L)
        val wholeMillis = remainingNanos / NANOS_PER_MILLISECOND
        return wholeMillis + if (remainingNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
    }

    private fun shutdownDeadline(nowNanos: Long, timeoutMillis: Long): Long {
        val timeoutNanos =
            if (timeoutMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
                Long.MAX_VALUE
            } else {
                timeoutMillis * NANOS_PER_MILLISECOND
            }
        return if (nowNanos > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else nowNanos + timeoutNanos
    }

    private companion object {
        const val MAX_SERVER_CLOSE_MILLIS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
