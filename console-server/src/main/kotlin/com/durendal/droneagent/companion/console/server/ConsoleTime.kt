package com.durendal.droneagent.companion.console.server

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max

fun interface ConsoleMonotonicClock {
    fun nowNanos(): Long
}

fun interface ConsoleEpochClock {
    fun nowMillis(): Long
}

fun interface ConsoleScheduledTask {
    fun cancel()
}

fun interface ConsoleDeadlineScheduler {
    fun scheduleAt(deadlineNanos: Long, task: () -> Unit): ConsoleScheduledTask
}

class JdkConsoleDeadlineScheduler(
    private val clock: ConsoleMonotonicClock,
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "console-safety-deadline").apply { isDaemon = true }
        },
) : ConsoleDeadlineScheduler, AutoCloseable {
    override fun scheduleAt(deadlineNanos: Long, task: () -> Unit): ConsoleScheduledTask {
        val delay = max(0L, deadlineNanos - clock.nowNanos())
        val future = executor.schedule(task, delay, TimeUnit.NANOSECONDS)
        return ConsoleScheduledTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
