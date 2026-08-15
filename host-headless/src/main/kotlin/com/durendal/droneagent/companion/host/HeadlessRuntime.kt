package com.durendal.droneagent.companion.host

/** A fully composed runtime owned by the foreground service. */
interface HeadlessRuntime {
    /** Starts listeners and command handling. This method must fail closed. */
    fun start()

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
    private var runtime: HeadlessRuntime? = null
    private var pendingClosedOutcomeEvidence = false

    @Synchronized
    fun start(trigger: LifecycleTrigger): RuntimeStartResult {
        if (runtime != null) return RuntimeStartResult.ALREADY_STARTED

        evidence.record(LifecycleEvent.RUNTIME_START_REQUESTED, trigger)
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
            candidate.start()
        } catch (_: Throwable) {
            // start() may have partially allocated resources.
            val cleanup =
                runCatching { candidate.closeWithin(START_FAILURE_CLOSE_TIMEOUT_MILLIS) }
                    .getOrDefault(RuntimeCloseResult.FAILED)
            if (cleanup == RuntimeCloseResult.CLOSED) runtime = null
            evidence.record(LifecycleEvent.RUNTIME_START_FAILED, trigger)
            return RuntimeStartResult.FAILED
        }

        try {
            evidence.record(LifecycleEvent.RUNTIME_STARTED, trigger)
        } catch (evidenceFailure: Throwable) {
            // A runtime without durable lifecycle evidence is not admitted.
            val cleanup =
                runCatching { candidate.closeWithin(START_FAILURE_CLOSE_TIMEOUT_MILLIS) }
                    .getOrDefault(RuntimeCloseResult.FAILED)
            if (cleanup == RuntimeCloseResult.CLOSED) runtime = null
            throw evidenceFailure
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

    private companion object {
        const val START_FAILURE_CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
