package com.durendal.droneagent.companion.host

import java.util.concurrent.atomic.AtomicBoolean

/** A fully composed runtime owned by the foreground service. */
interface HeadlessRuntime {
    /** Immediately closes command admission without blocking or throwing; ordered cleanup follows. */
    fun requestStop()

    /** Starts listeners and transports while keeping actuation locked and telemetry stopped. */
    fun start()

    /** Opens startup actuation readiness after its durable controller commit. This is stop-aware. */
    fun admitActuation()

    /**
     * Stops accepting work, returns actuation to neutral, and releases resources.
     *
     * Implementations must return within [timeoutMillis]. A timeout or failure is
     * explicit because the service must never report a graceful stop it did not
     * actually observe.
     */
    fun closeWithin(timeoutMillis: Long): RuntimeCloseResult
}

fun interface HeadlessRuntimeFactory {
    fun create(): HeadlessRuntime
}

/** Implemented by the application/composition root, not by the lifecycle shell. */
interface HeadlessRuntimeFactoryOwner {
    val headlessRuntimeFactory: HeadlessRuntimeFactory
}

enum class RuntimeCloseResult {
    CLOSED,
    TIMED_OUT,
    FAILED,
}

enum class RuntimeStartResult {
    STARTED,
    ALREADY_STARTED,
    CANCELLED,
    FAILED,
}

internal fun isSafeStopDurablyComplete(
    runtimeResult: RuntimeCloseResult,
    completionEvidenceCommitted: Boolean,
): Boolean =
    runtimeResult == RuntimeCloseResult.CLOSED && completionEvidenceCommitted

/**
 * Serializes runtime ownership independently of Android callbacks.
 *
 * The service is responsible for placing calls on its single lifecycle executor.
 * Synchronization here also protects tests and best-effort onDestroy cleanup.
 */
class HeadlessRuntimeController(
    private val factory: HeadlessRuntimeFactory,
    private val evidence: DurableLifecycleEvidence,
) {
    @Volatile private var runtime: HeadlessRuntime? = null
    private var pendingClosedOutcomeEvidence = false
    private val stopRequested = AtomicBoolean(false)

    /** Closes startup admission without waiting behind slow factory or runtime initialization. */
    fun requestStop() {
        stopRequested.set(true)
        // Implementations must close their server-owned admission gate before returning. Cleanup
        // remains on the lifecycle executor so connector/neutral/audit ordering stays serialized.
        runtime?.let(::signalRuntimeStop)
    }

    @Synchronized
    fun start(trigger: LifecycleTrigger): RuntimeStartResult {
        if (runtime != null) return RuntimeStartResult.ALREADY_STARTED

        evidence.record(LifecycleEvent.RUNTIME_START_REQUESTED, trigger)
        if (stopRequested.get()) {
            evidence.record(LifecycleEvent.RUNTIME_START_CANCELLED, LifecycleTrigger.SAFE_STOP)
            return RuntimeStartResult.CANCELLED
        }
        val candidate =
            try {
                factory.create()
            } catch (_: Throwable) {
                evidence.record(LifecycleEvent.RUNTIME_FACTORY_FAILED, trigger)
                return RuntimeStartResult.FAILED
            }
        // Retain ownership before start(): a throwing start may still have opened
        // sockets or actuation resources, and a timed-out cleanup must be retried.
        runtime = candidate
        try {
            evidence.record(LifecycleEvent.RUNTIME_FACTORY_CREATED, trigger)
        } catch (evidenceFailure: Throwable) {
            closeCancelledStartup(candidate)
            throw evidenceFailure
        }
        if (stopRequested.get()) {
            closeCancelledStartup(candidate)
            evidence.record(LifecycleEvent.RUNTIME_START_CANCELLED, LifecycleTrigger.SAFE_STOP)
            return RuntimeStartResult.CANCELLED
        }
        try {
            candidate.start()
        } catch (_: Throwable) {
            val cancelled = stopRequested.get()
            return finishIncompleteStartup(candidate, trigger, cancelled)
        }

        if (stopRequested.get()) {
            return finishIncompleteStartup(candidate, trigger, cancelled = true)
        }

        try {
            evidence.record(LifecycleEvent.RUNTIME_ADMISSION_COMMITTED, trigger)
        } catch (_: Throwable) {
            val cancelled = stopRequested.get()
            return finishIncompleteStartup(candidate, trigger, cancelled)
        }
        if (stopRequested.get()) {
            return finishIncompleteStartup(candidate, trigger, cancelled = true)
        }

        try {
            candidate.admitActuation()
        } catch (_: Throwable) {
            val cancelled = stopRequested.get()
            return finishIncompleteStartup(candidate, trigger, cancelled)
        }
        if (stopRequested.get()) {
            return finishIncompleteStartup(candidate, trigger, cancelled = true)
        }

        try {
            evidence.record(LifecycleEvent.RUNTIME_STARTED, trigger)
        } catch (_: Throwable) {
            val cancelled = stopRequested.get()
            return finishIncompleteStartup(candidate, trigger, cancelled)
        }
        if (stopRequested.get()) {
            return finishIncompleteStartup(candidate, trigger, cancelled = true)
        }
        return RuntimeStartResult.STARTED
    }

    @Synchronized
    fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        if (pendingClosedOutcomeEvidence) {
            val evidenceCommitted =
                runCatching {
                    evidence.record(
                        LifecycleEvent.RUNTIME_CLOSED,
                        LifecycleTrigger.SAFE_STOP,
                    )
                }.isSuccess
            if (!evidenceCommitted) return RuntimeCloseResult.FAILED
            pendingClosedOutcomeEvidence = false
            return RuntimeCloseResult.CLOSED
        }
        val current = runtime ?: return RuntimeCloseResult.CLOSED
        stopRequested.set(true)
        signalRuntimeStop(current)

        // Lifecycle evidence is intentionally written after the close attempt. A full or locked
        // journal must never delay connector shutdown or the runtime's neutral barrier.
        val result =
            try {
                current.closeWithin(timeoutMillis)
            } catch (_: Throwable) {
                RuntimeCloseResult.FAILED
            }

        val outcomeEvidenceCommitted = runCatching {
            evidence.record(
                when (result) {
                    RuntimeCloseResult.CLOSED -> LifecycleEvent.RUNTIME_CLOSED
                    RuntimeCloseResult.TIMED_OUT -> LifecycleEvent.RUNTIME_CLOSE_TIMED_OUT
                    RuntimeCloseResult.FAILED -> LifecycleEvent.RUNTIME_CLOSE_FAILED
                },
                LifecycleTrigger.SAFE_STOP,
            )
        }.isSuccess

        if (result == RuntimeCloseResult.CLOSED) {
            runtime = null
            if (!outcomeEvidenceCommitted) {
                pendingClosedOutcomeEvidence = true
                return RuntimeCloseResult.FAILED
            }
        }
        return result
    }

    @Synchronized
    fun isRunning(): Boolean = runtime != null

    @Synchronized
    internal fun hasPendingCloseEvidence(): Boolean = pendingClosedOutcomeEvidence

    private fun closeCancelledStartup(candidate: HeadlessRuntime) {
        signalRuntimeStop(candidate)
        val cleanup =
            runCatching { candidate.closeWithin(START_FAILURE_CLOSE_TIMEOUT_MILLIS) }
                .getOrDefault(RuntimeCloseResult.FAILED)
        if (cleanup == RuntimeCloseResult.CLOSED) runtime = null
    }

    private fun finishIncompleteStartup(
        candidate: HeadlessRuntime,
        trigger: LifecycleTrigger,
        cancelled: Boolean,
    ): RuntimeStartResult {
        signalRuntimeStop(candidate)
        val cleanup =
            runCatching { candidate.closeWithin(START_FAILURE_CLOSE_TIMEOUT_MILLIS) }
                .getOrDefault(RuntimeCloseResult.FAILED)
        if (cleanup == RuntimeCloseResult.CLOSED) runtime = null
        val cancellationObserved = cancelled || stopRequested.get()
        evidence.record(
            if (cancellationObserved) LifecycleEvent.RUNTIME_START_CANCELLED
            else LifecycleEvent.RUNTIME_START_FAILED,
            if (cancellationObserved) LifecycleTrigger.SAFE_STOP else trigger,
        )
        return if (cancellationObserved) RuntimeStartResult.CANCELLED else RuntimeStartResult.FAILED
    }

    private fun signalRuntimeStop(candidate: HeadlessRuntime) {
        // requestStop() is a best-effort emergency signal on an Android callback path. Keep the
        // controller non-throwing even if an injected implementation violates that contract;
        // ordered closeWithin() still runs on the lifecycle executor.
        runCatching { candidate.requestStop() }
    }

    private companion object {
        const val START_FAILURE_CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
