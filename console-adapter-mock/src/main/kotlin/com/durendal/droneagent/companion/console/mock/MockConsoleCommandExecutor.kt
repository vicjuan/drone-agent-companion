package com.durendal.droneagent.companion.console.mock

import com.durendal.droneagent.actuation.ActuationCommandFrame
import com.durendal.droneagent.actuation.ActuationOperationResult
import com.durendal.droneagent.actuation.CommandSubmissionStatus
import com.durendal.droneagent.actuation.ControlGeneration
import com.durendal.droneagent.actuation.ControlProducer
import com.durendal.droneagent.actuation.NeutralizationReason
import com.durendal.droneagent.actuation.TakeoffActionResult
import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger

/**
 * Mock-only execution adapter. Takeoff, landing and continuous control delegate to adapter-mock;
 * RTH delegates to the explicitly injected companion simulation port. The same RTH instance must
 * also be injected into the snapshot provider so the simulated transition remains observable.
 * None of these successes upgrade the G520 capability evidence matrix.
 */
class MockConsoleCommandExecutor(
    private val agent: MockDroneAgent,
    private val monotonicClock: ConsoleMonotonicClock,
    private val returnToHome: ObservableMockReturnToHomePort,
) : ConsoleCommandExecutor, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var nextPortGeneration = 0L
    private var highestFencedControlEpoch = 0L
    private var activeLeaseId: String? = null
    private var activeGeneration: ControlGeneration? = null

    override fun executeDiscrete(
        command: AdmittedDiscreteCommand,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        if (monotonicClock.nowNanos() >= command.expiresAtNanos) {
            callback(ConsoleExecutionResult(false, "command_ttl_expired"))
            return
        }
        when (command.command.action) {
            ConsoleDiscreteAction.TAKEOFF -> {
                returnToHome.clear()
                executeTakeoff(callback)
            }
            ConsoleDiscreteAction.LANDING -> {
                returnToHome.clear()
                callback(
                    if (agent.startAutoLanding()) {
                        ConsoleExecutionResult(
                            true,
                            detail = "Simulated landing request accepted by adapter-mock.",
                        )
                    } else {
                        ConsoleExecutionResult(false, "mock_landing_rejected")
                    },
                )
            }

            ConsoleDiscreteAction.RETURN_TO_HOME ->
                runCatching { returnToHome.execute(command, callback) }
                    .getOrElse { callback(ConsoleExecutionResult(false, "mock_rth_executor_failure")) }
        }
    }

    override fun submitControl(
        frame: AdmittedControlFrame,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        val result =
            synchronized(lock) {
                when {
                    closed -> ConsoleExecutionResult(false, "mock_executor_closed")
                    frame.controlEpoch <= highestFencedControlEpoch ->
                        ConsoleExecutionResult(false, "control_epoch_fenced")
                    activeLeaseId != null && activeLeaseId != frame.frame.leaseId ->
                        ConsoleExecutionResult(false, "different_lease_active")
                    else -> submitControlLocked(frame)
                }
            }
        callback(result)
    }

    override fun neutralize(
        leaseId: String,
        controlEpoch: Long,
        trigger: ConsoleSafetyTrigger,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        val result =
            synchronized(lock) {
                highestFencedControlEpoch = maxOf(highestFencedControlEpoch, controlEpoch)
                val generation = activeGeneration
                when {
                    closed -> ConsoleExecutionResult(false, "mock_executor_closed")
                    activeLeaseId == null || generation == null -> ConsoleExecutionResult(true)
                    activeLeaseId != leaseId -> {
                        // A delayed barrier for an older lease must never neutralize a newer lease.
                        ConsoleExecutionResult(true)
                    }
                    else -> neutralizeActiveLocked(generation, trigger)
                }
            }
        callback(result)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            activeGeneration?.let { generation ->
                agent.actuation.neutralize(generation, NeutralizationReason.LIFECYCLE_LOSS)
                agent.actuation.deactivate(generation) {}
            }
            activeLeaseId = null
            activeGeneration = null
            closed = true
        }
    }

    private fun executeTakeoff(callback: (ConsoleExecutionResult) -> Unit) {
        when (val initialized = agent.takeoff.initialize()) {
            is TakeoffActionResult.Failure -> {
                callback(
                    ConsoleExecutionResult(
                        false,
                        "mock_takeoff_${initialized.error.kind.name.lowercase()}",
                    ),
                )
            }
            is TakeoffActionResult.Success ->
                agent.takeoff.startTakeoff { result ->
                    callback(
                        when (result) {
                            is TakeoffActionResult.Success ->
                                ConsoleExecutionResult(
                                    true,
                                    detail = "Simulated takeoff request accepted by adapter-mock.",
                                )
                            is TakeoffActionResult.Failure ->
                                ConsoleExecutionResult(
                                    false,
                                    "mock_takeoff_${result.error.kind.name.lowercase()}",
                                )
                        },
                    )
                }
        }
    }

    private fun submitControlLocked(frame: AdmittedControlFrame): ConsoleExecutionResult {
        val generation =
            activeGeneration ?: run {
                check(nextPortGeneration != Long.MAX_VALUE) { "mock control generation exhausted" }
                nextPortGeneration += 1L
                val next = ControlGeneration(nextPortGeneration)
                var activation: ActuationOperationResult? = null
                agent.actuation.activate(next) { result -> activation = result }
                val completed = activation
                    ?: return ConsoleExecutionResult(false, "mock_activation_callback_missing")
                if (!completed.succeeded) {
                    return ConsoleExecutionResult(false, "mock_activation_rejected")
                }
                activeLeaseId = frame.frame.leaseId
                activeGeneration = next
                next
            }
        val status =
            agent.actuation.submit(
                ActuationCommandFrame(
                    command = frame.command,
                    producer = ControlProducer.MANUAL,
                    generation = generation,
                    commandSequence = frame.frame.inputSequence,
                    createdAtNanos = frame.admittedAtNanos,
                    inputSequence = frame.frame.inputSequence,
                ),
            )
        return if (status == CommandSubmissionStatus.SUBMITTED) {
            ConsoleExecutionResult(true)
        } else {
            ConsoleExecutionResult(false, "mock_control_${status.name.lowercase()}")
        }
    }

    private fun neutralizeActiveLocked(
        generation: ControlGeneration,
        trigger: ConsoleSafetyTrigger,
    ): ConsoleExecutionResult {
        val neutralStatus = agent.actuation.neutralize(generation, trigger.toNeutralizationReason())
        var deactivation: ActuationOperationResult? = null
        agent.actuation.deactivate(generation) { result -> deactivation = result }
        activeLeaseId = null
        activeGeneration = null
        val deactivated = deactivation?.succeeded == true
        return if (neutralStatus == CommandSubmissionStatus.SUBMITTED || deactivated) {
            ConsoleExecutionResult(true)
        } else {
            ConsoleExecutionResult(false, "mock_neutral_${neutralStatus.name.lowercase()}")
        }
    }

    private fun ConsoleSafetyTrigger.toNeutralizationReason(): NeutralizationReason =
        when (this) {
            ConsoleSafetyTrigger.CLIENT_REQUEST -> NeutralizationReason.PRODUCER_RELEASED
            ConsoleSafetyTrigger.CLIENT_DISCONNECT -> NeutralizationReason.LIFECYCLE_LOSS
            ConsoleSafetyTrigger.LEASE_EXPIRED -> NeutralizationReason.AUTHORITY_LOSS
            ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED -> NeutralizationReason.WATCHDOG
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST -> NeutralizationReason.AUTHORITY_LOSS
            ConsoleSafetyTrigger.SERVER_STOP -> NeutralizationReason.LIFECYCLE_LOSS
        }
}
