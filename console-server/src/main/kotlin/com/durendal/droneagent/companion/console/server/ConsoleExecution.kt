package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.core.control.SaturatedCommand

data class ConsoleExecutionResult(
    val succeeded: Boolean,
    /** Stable, already-sanitized operator-facing code; never a Throwable message. */
    val reason: String? = null,
    /** Already sanitized, operator-safe text; never place raw Throwable messages here. */
    val detail: String? = null,
) {
    init {
        require(reason == null || SAFE_REASON.matches(reason)) {
            "reason must be null or a stable snake_case code"
        }
        require(!succeeded || reason == null) { "successful execution cannot carry a failure reason" }
        require(detail == null || (detail.isNotBlank() && detail.length <= 1_024)) {
            "detail must be null or operator-safe text up to 1024 characters"
        }
    }

    companion object {
        private val SAFE_REASON = Regex("^[a-z][a-z0-9_]{0,127}$")
    }
}

data class AdmittedDiscreteCommand(
    val sessionId: String,
    val command: ConsoleDiscreteCommand,
    val authorityDecisionId: String,
    val intentDigestSha256: String,
    val admittedAtNanos: Long,
    /** Executor must refuse to begin the action after this monotonic deadline. */
    val expiresAtNanos: Long,
)

data class AdmittedControlFrame(
    val sessionId: String,
    val frame: ConsoleControlFrame,
    val command: SaturatedCommand,
    val authorityDecisionId: String,
    val intentDigestSha256: String,
    val admittedAtNanos: Long,
    /** Monotonic core epoch fenced by [ConsoleCommandExecutor.neutralize]. */
    val controlEpoch: Long,
)

/**
 * Companion-owned action boundary. RETURN_TO_HOME is intentionally owned here:
 * the vendor mock currently exposes no vendor-neutral RTH action port.
 */
fun interface ConsoleAircraftActionPort {
    fun execute(
        command: AdmittedDiscreteCommand,
        callback: (ConsoleExecutionResult) -> Unit,
    )
}

/**
 * Transport-independent execution boundary; runner/host choose the mock or hardware adapter.
 *
 * Every entrypoint must establish its submission/barrier synchronously and return promptly. The
 * completion callback may be synchronous or asynchronous and must be invoked at most once. The
 * core serializes entrypoint invocation, but never holds its state lock while calling an executor.
 */
interface ConsoleCommandExecutor {
    fun executeDiscrete(
        command: AdmittedDiscreteCommand,
        callback: (ConsoleExecutionResult) -> Unit,
    )

    fun submitControl(
        frame: AdmittedControlFrame,
        callback: (ConsoleExecutionResult) -> Unit,
    )

    /**
     * Linearization barrier for [leaseId]/[controlEpoch]. Once invoked, every queued, concurrent,
     * or later [submitControl] with an epoch <= [controlEpoch] must be rejected and must never
     * apply a non-neutral command. A greater epoch may start a fresh generation after completion.
     */
    fun neutralize(
        leaseId: String,
        controlEpoch: Long,
        trigger: ConsoleSafetyTrigger,
        callback: (ConsoleExecutionResult) -> Unit,
    )
}
