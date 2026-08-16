package com.durendal.droneagent.companion.console.dji

import com.durendal.droneagent.actuation.ActuationCommandFrame
import com.durendal.droneagent.actuation.ActuationOperationCallback
import com.durendal.droneagent.actuation.ActuationOperationResult
import com.durendal.droneagent.actuation.AircraftActionFailureKind
import com.durendal.droneagent.actuation.CommandSubmissionStatus
import com.durendal.droneagent.actuation.ControlGeneration
import com.durendal.droneagent.actuation.ControlProducer
import com.durendal.droneagent.actuation.FlightControlCommandPort
import com.durendal.droneagent.actuation.FlightControlPortSnapshotListener
import com.durendal.droneagent.actuation.FlightControlPortState
import com.durendal.droneagent.actuation.LandingActionPort
import com.durendal.droneagent.actuation.LandingActionResult
import com.durendal.droneagent.actuation.LandingActionSnapshot
import com.durendal.droneagent.actuation.LandingActionSnapshotListener
import com.durendal.droneagent.actuation.NeutralizationReason
import com.durendal.droneagent.actuation.PhysicalRcTakeoverListener
import com.durendal.droneagent.actuation.PhysicalRcTakeoverPort
import com.durendal.droneagent.actuation.PhysicalRcTakeoverPortState
import com.durendal.droneagent.actuation.PhysicalRcTakeoverSnapshotListener
import com.durendal.droneagent.actuation.PhysicalRcTakeoverStartResult
import com.durendal.droneagent.actuation.ReturnToHomeActionPort
import com.durendal.droneagent.actuation.ReturnToHomeActionResult
import com.durendal.droneagent.actuation.ReturnToHomeActionSnapshot
import com.durendal.droneagent.actuation.ReturnToHomeActionSnapshotListener
import com.durendal.droneagent.actuation.ReturnToHomeState
import com.durendal.droneagent.actuation.TakeoffActionPort
import com.durendal.droneagent.actuation.TakeoffActionFailureKind
import com.durendal.droneagent.actuation.TakeoffActionResult
import com.durendal.droneagent.actuation.TakeoffActionSnapshotListener
import com.durendal.droneagent.adapter.dji.DjiDroneAgent
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import com.durendal.droneagent.core.model.FlightMode
import com.durendal.droneagent.core.model.FlightStateTelemetry
import com.durendal.droneagent.core.model.Telemetry
import com.durendal.droneagent.core.telemetry.TelemetryListener
import com.durendal.droneagent.core.telemetry.TelemetrySource
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

fun interface DjiActuationReadinessListener {
    fun onReadinessChanged()
}

/** Read-only readiness notification consumed by the DJI host composition. */
interface DjiActuationReadinessSource {
    fun isReady(): Boolean

    fun addReadinessListener(listener: DjiActuationReadinessListener)

    fun removeReadinessListener(listener: DjiActuationReadinessListener)
}

/** Testable, vendor-neutral view of the five ports owned by one [DjiDroneAgent]. */
internal class DjiConsoleActuationPorts(
    val takeoff: TakeoffActionPort,
    val landing: LandingActionPort,
    val rth: ReturnToHomeActionPort,
    val flightControl: FlightControlCommandPort,
    val physicalRcTakeover: PhysicalRcTakeoverPort,
) {
    companion object {
        fun from(agent: DjiDroneAgent): DjiConsoleActuationPorts =
            DjiConsoleActuationPorts(
                takeoff = agent.actuationPorts.takeoff,
                landing = agent.actuationPorts.landing,
                rth = agent.actuationPorts.rth,
                flightControl = agent.actuationPorts.flightControl,
                physicalRcTakeover = agent.actuationPorts.physicalRcTakeover,
            )
    }
}

/**
 * Production DJI executor used only behind [com.durendal.droneagent.companion.console.server.ConsoleServerCore].
 *
 * Core owns commissioning, lease admission and durable evidence. This class owns the last local
 * actuation boundary: it maps an already-admitted command to the ports of exactly one
 * [DjiDroneAgent], fences every control epoch synchronously, and permanently yields to physical
 * RC-N3 input. Vendor callbacks may be synchronous or asynchronous; all operator callbacks are
 * completed at most once and never while the executor serialization lock is held.
 */
class DjiConsoleCommandExecutor internal constructor(
    private val ports: DjiConsoleActuationPorts,
    private val telemetrySource: TelemetrySource,
    private val telemetryRefresh: () -> Unit = {},
    private val monotonicClock: ConsoleMonotonicClock,
    private val actuationClockNanos: () -> Long,
    private val readinessPollingEnabled: Boolean,
    private val onPhysicalRcTakeover: (String) -> Unit,
) : ConsoleCommandExecutor, DjiActuationReadinessSource, AutoCloseable {
    constructor(
        agent: DjiDroneAgent,
        monotonicClock: ConsoleMonotonicClock,
        onPhysicalRcTakeover: (String) -> Unit = {},
    ) : this(
        ports = DjiConsoleActuationPorts.from(agent),
        telemetrySource = agent.telemetry,
        telemetryRefresh = agent.telemetry::refreshTakeoffReadiness,
        monotonicClock = monotonicClock,
        actuationClockNanos = System::nanoTime,
        readinessPollingEnabled = true,
        onPhysicalRcTakeover = onPhysicalRcTakeover,
    )

    private val dispatchLock = ReentrantLock(true)
    private val deferredCallbacks = ArrayDeque<() -> Unit>()
    private var dispatchDepth = 0

    private val readinessListeners = LinkedHashSet<DjiActuationReadinessListener>()
    private var lastPublishedReadiness: Boolean? = null
    private var closed = false
    private var physicalTakeoverLatched = false
    private var landingConfirmationFaultLatched = false
    private var rcStarted = false
    private var rcStartAttempts = 0
    private var nextRcStartAttemptNanos = 0L
    private var pollingHealthy = !readinessPollingEnabled
    private var rcSnapshotListenerInstalled = false
    private var physicalTakeoverListenerInstalled = false
    private var flightSnapshotListenerInstalled = false
    private var takeoffSnapshotListenerInstalled = false
    private var landingSnapshotListenerInstalled = false
    private var rthSnapshotListenerInstalled = false
    private var telemetryListenerInstalled = false
    private var takeoffObservationInitialized = false
    private var landingObservationInitialized = false
    private var rthObservationInitialized = false

    private var highestFencedControlEpoch = 0L
    private var nextPortGenerationValue = 0L
    private var activeControl: ActiveControl? = null
    private var activeDiscrete: ActiveDiscrete? = null

    private val readinessExecutor: ScheduledExecutorService? =
        if (readinessPollingEnabled) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dji-console-readiness").apply { isDaemon = true }
            }
        } else {
            null
        }
    private var readinessPoll: ScheduledFuture<*>? = null

    private val rcSnapshotListener =
        PhysicalRcTakeoverSnapshotListener {
            serialized { refreshReadinessLocked() }
        }
    private val flightSnapshotListener =
        FlightControlPortSnapshotListener {
            serialized { refreshReadinessLocked() }
        }
    private val takeoffSnapshotListener =
        TakeoffActionSnapshotListener {
            serialized { refreshReadinessLocked() }
        }
    private val landingSnapshotListener =
        LandingActionSnapshotListener { snapshot ->
            serialized {
                observeLandingSnapshotLocked(snapshot)
                refreshReadinessLocked()
            }
        }
    private val rthSnapshotListener =
        ReturnToHomeActionSnapshotListener { snapshot ->
            serialized {
                observeRthSnapshotLocked(snapshot)
                refreshReadinessLocked()
            }
        }
    private val physicalTakeoverListener =
        PhysicalRcTakeoverListener { reason ->
            serialized { handlePhysicalTakeoverLocked(reason) }
        }
    private val telemetryListener =
        TelemetryListener { telemetry ->
            serialized {
                observeTelemetryLocked(telemetry)
                refreshReadinessLocked()
            }
        }

    init {
        serialized {
            installPortListenersLocked()
            installTelemetryListenerLocked()
            initializeActionObservationsLocked()
            startPhysicalRcRailLocked()
            installReadinessPollLocked()
            refreshReadinessLocked(force = true)
        }
    }

    override fun isReady(): Boolean {
        return serialized {
            installPortListenersLocked()
            installTelemetryListenerLocked()
            initializeActionObservationsLocked()
            startPhysicalRcRailLocked()
            val ready = computeReadinessLocked()
            publishReadinessLocked(ready)
            ready
        }
    }

    override fun addReadinessListener(listener: DjiActuationReadinessListener) {
        serialized {
            if (closed) {
                deferLocked { listener.onReadinessChanged() }
            } else if (readinessListeners.add(listener)) {
                deferLocked { listener.onReadinessChanged() }
            }
        }
    }

    override fun removeReadinessListener(listener: DjiActuationReadinessListener) {
        serialized { readinessListeners.remove(listener) }
    }

    override fun executeDiscrete(
        command: AdmittedDiscreteCommand,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        val completion = OnceExecutionCallback(callback)
        serialized {
            reconcileDiscreteLocked()
            val now = safeConsoleNowLocked()
            when {
                closed -> completeLocked(completion, failure("dji_executor_closed"))
                physicalTakeoverLatched -> completeLocked(completion, failure("dji_physical_rc_takeover"))
                !computeReadinessLocked() -> completeLocked(completion, failure("dji_actuation_not_ready"))
                now == null || now >= command.expiresAtNanos ->
                    completeLocked(completion, failure("dji_command_expired"))
                activeDiscrete != null -> completeLocked(completion, failure("dji_discrete_busy"))
                activeControl != null -> completeLocked(completion, failure("dji_control_busy"))
                else -> {
                    val active = ActiveDiscrete(command.command.action, completion)
                    if (command.command.action == ConsoleDiscreteAction.LANDING) {
                        initializeLandingTelemetryBaselineLocked(active)
                    } else if (command.command.action == ConsoleDiscreteAction.RETURN_TO_HOME) {
                        initializeRthBaselineLocked(active)
                    }
                    activeDiscrete = active
                    when (command.command.action) {
                        ConsoleDiscreteAction.TAKEOFF -> startTakeoffLocked(command, active)
                        ConsoleDiscreteAction.LANDING -> startLandingLocked(command, active)
                        ConsoleDiscreteAction.RETURN_TO_HOME -> startReturnToHomeLocked(command, active)
                    }
                }
            }
        }
    }

    override fun submitControl(
        frame: AdmittedControlFrame,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        val completion = OnceExecutionCallback(callback)
        serialized {
            val pending = pendingControlLocked(frame, completion)
            if (pending == null) {
                return@serialized
            }

            val current = activeControl
            when {
                current == null -> activateControlLocked(pending)
                current.leaseId != frame.frame.leaseId || current.controlEpoch != frame.controlEpoch ->
                    completeLocked(completion, failure("dji_control_busy"))
                current.phase == ControlPhase.DEACTIVATING ->
                    completeLocked(completion, failure("dji_control_deactivating"))
                current.phase == ControlPhase.ACTIVATING -> {
                    current.pending?.let {
                        completeLocked(it.completion, failure("dji_control_superseded"))
                    }
                    current.pending = pending
                }
                else -> submitToActiveControlLocked(current, pending)
            }
        }
    }

    override fun neutralize(
        leaseId: String,
        controlEpoch: Long,
        trigger: ConsoleSafetyTrigger,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        val completion = OnceExecutionCallback(callback)
        serialized {
            val barrier = NeutralBarrier(completion)
            highestFencedControlEpoch = maxOf(highestFencedControlEpoch, controlEpoch)
            installPortListenersLocked()
            initializeActionObservationsLocked()
            reconcileDiscreteLocked()
            val observedActions = observedDiscreteActionsLocked()
            activeDiscrete?.let { discrete ->
                val part = addNeutralPartLocked(barrier)
                discrete.cancellationSettlements += { succeeded ->
                    completeNeutralPartLocked(
                        barrier = barrier,
                        part = part,
                        succeeded = succeeded,
                        failureReason = "dji_discrete_cancel_failed",
                    )
                }
            }
            cancelDiscreteActionsLocked(
                reason = "dji_neutralized",
                observedActions = observedActions,
                observedSettlementFactory = { _: ConsoleDiscreteAction ->
                    val part = addNeutralPartLocked(barrier)
                    val settlement: (Boolean) -> Unit = { succeeded ->
                        completeNeutralPartLocked(
                            barrier = barrier,
                            part = part,
                            succeeded = succeeded,
                            failureReason = "dji_observed_action_cancel_failed",
                        )
                    }
                    settlement
                },
            )

            val current = activeControl
            if (
                current == null ||
                    current.leaseId != leaseId ||
                    current.controlEpoch > controlEpoch
            ) {
                if (current == null) {
                    val flightState =
                        runCatching { ports.flightControl.currentSnapshot().state }.getOrNull()
                    if (flightState != FlightControlPortState.IDLE) {
                        val part = addNeutralPartLocked(barrier)
                        completeNeutralPartLocked(
                            barrier = barrier,
                            part = part,
                            succeeded = false,
                            failureReason = "dji_unowned_control_active",
                        )
                    }
                }
                sealNeutralBarrierLocked(barrier)
                return@serialized
            }

            val controlPart = addNeutralPartLocked(barrier)
            val controlCompletion =
                OnceExecutionCallback { result ->
                    serialized {
                        completeNeutralPartLocked(
                            barrier = barrier,
                            part = controlPart,
                            succeeded = result.succeeded,
                            failureReason = result.reason ?: "dji_control_neutral_failed",
                        )
                    }
                }
            beginControlShutdownLocked(
                active = current,
                reason = trigger.toNeutralizationReason(),
                pendingFailure = "dji_control_neutralized",
                completion = controlCompletion,
            )
            sealNeutralBarrierLocked(barrier)
        }
    }

    override fun close() {
        serialized {
            if (closed) return@serialized
            closed = true
            highestFencedControlEpoch = Long.MAX_VALUE

            refreshReadinessLocked(force = true)
            reconcileDiscreteLocked()
            cancelDiscreteActionsLocked(
                reason = "dji_executor_closed",
                observedActions = observedDiscreteActionsLocked(),
            )
            activeControl?.let {
                beginControlShutdownLocked(
                    active = it,
                    reason = NeutralizationReason.LIFECYCLE_LOSS,
                    pendingFailure = "dji_executor_closed",
                    completion = null,
                )
            }

            if (rcSnapshotListenerInstalled) {
                runCatching { ports.physicalRcTakeover.removeSnapshotListener(rcSnapshotListener) }
            }
            if (physicalTakeoverListenerInstalled) {
                runCatching {
                    ports.physicalRcTakeover.removePhysicalRcTakeoverListener(physicalTakeoverListener)
                }
            }
            if (flightSnapshotListenerInstalled) {
                runCatching { ports.flightControl.removeSnapshotListener(flightSnapshotListener) }
            }
            if (takeoffSnapshotListenerInstalled) {
                runCatching { ports.takeoff.removeSnapshotListener(takeoffSnapshotListener) }
            }
            if (landingSnapshotListenerInstalled) {
                runCatching { ports.landing.removeSnapshotListener(landingSnapshotListener) }
            }
            if (rthSnapshotListenerInstalled) {
                runCatching { ports.rth.removeSnapshotListener(rthSnapshotListener) }
            }
            if (telemetryListenerInstalled) {
                runCatching { telemetrySource.removeListener(telemetryListener) }
            }
            runCatching { ports.physicalRcTakeover.stop() }

            readinessPoll?.cancel(false)
            readinessPoll = null
            val listeners = readinessListeners.toList()
            readinessListeners.clear()
            listeners.forEach { listener -> deferLocked { listener.onReadinessChanged() } }
        }
        readinessExecutor?.shutdownNow()
    }

    private fun startTakeoffLocked(
        command: AdmittedDiscreteCommand,
        active: ActiveDiscrete,
    ) {
        val initialized =
            runCatching { ports.takeoff.initialize() }
                .getOrElse {
                    failDiscreteLocked(active, "dji_takeoff_executor_failure")
                    return
                }
        if (initialized is TakeoffActionResult.Failure) {
            failDiscreteLocked(active, "dji_takeoff_${initialized.error.kind.name.lowercase()}")
            return
        }
        if (!discreteStillAdmissibleLocked(command)) {
            failDiscreteLocked(active, "dji_command_expired")
            return
        }

        try {
            ports.takeoff.startTakeoff { result ->
                serialized {
                    finishDiscreteLocked(
                        active,
                        when (result) {
                            is TakeoffActionResult.Success ->
                                success("DJI MSDK accepted the takeoff request.")
                            is TakeoffActionResult.Failure ->
                                failure("dji_takeoff_${result.error.kind.name.lowercase()}")
                        },
                        outcomeUnknown =
                            (result as? TakeoffActionResult.Failure)
                                ?.error
                                ?.kind
                                ?.mayHaveActuated() == true,
                    )
                }
            }
        } catch (_: Throwable) {
            handleDiscreteInvocationThrowLocked(active, "dji_takeoff_executor_failure")
        }
    }

    private fun startLandingLocked(
        command: AdmittedDiscreteCommand,
        active: ActiveDiscrete,
    ) {
        val initialized =
            runCatching { ports.landing.initialize() }
                .getOrElse {
                    failDiscreteLocked(active, "dji_landing_executor_failure")
                    return
                }
        if (initialized is LandingActionResult.Failure) {
            failDiscreteLocked(active, "dji_landing_${initialized.error.kind.name.lowercase()}")
            return
        }
        if (!discreteStillAdmissibleLocked(command)) {
            failDiscreteLocked(active, "dji_command_expired")
            return
        }

        try {
            ports.landing.start { result ->
                serialized {
                    finishDiscreteLocked(
                        active,
                        when (result) {
                            is LandingActionResult.Success ->
                                success("DJI MSDK accepted the landing request.")
                            is LandingActionResult.Failure ->
                                failure("dji_landing_${result.error.kind.name.lowercase()}")
                        },
                        outcomeUnknown =
                            (result as? LandingActionResult.Failure)
                                ?.error
                                ?.kind
                                ?.mayHaveActuated() == true,
                    )
                }
            }
        } catch (_: Throwable) {
            handleDiscreteInvocationThrowLocked(active, "dji_landing_executor_failure")
        }
    }

    private fun startReturnToHomeLocked(
        command: AdmittedDiscreteCommand,
        active: ActiveDiscrete,
    ) {
        val initialized =
            runCatching { ports.rth.initialize() }
                .getOrElse {
                    failDiscreteLocked(active, "dji_rth_executor_failure")
                    return
                }
        if (initialized is ReturnToHomeActionResult.Failure) {
            failDiscreteLocked(active, "dji_rth_${initialized.error.kind.name.lowercase()}")
            return
        }
        if (!discreteStillAdmissibleLocked(command)) {
            failDiscreteLocked(active, "dji_command_expired")
            return
        }

        try {
            ports.rth.start { result ->
                serialized {
                    finishDiscreteLocked(
                        active,
                        when (result) {
                            is ReturnToHomeActionResult.Success ->
                                success("DJI MSDK accepted the Return-to-Home request.")
                            is ReturnToHomeActionResult.Failure ->
                                failure("dji_rth_${result.error.kind.name.lowercase()}")
                        },
                        outcomeUnknown =
                            (result as? ReturnToHomeActionResult.Failure)
                                ?.error
                                ?.kind
                                ?.mayHaveActuated() == true,
                    )
                }
            }
        } catch (_: Throwable) {
            handleDiscreteInvocationThrowLocked(active, "dji_rth_executor_failure")
        }
    }

    private fun discreteStillAdmissibleLocked(command: AdmittedDiscreteCommand): Boolean {
        val now = safeConsoleNowLocked() ?: return false
        return !closed &&
            !physicalTakeoverLatched &&
            now < command.expiresAtNanos &&
            computeReadinessLocked()
    }

    private fun failDiscreteLocked(active: ActiveDiscrete, reason: String) {
        finishDiscreteLocked(active, failure(reason))
    }

    private fun finishDiscreteLocked(
        active: ActiveDiscrete,
        result: ConsoleExecutionResult,
        outcomeUnknown: Boolean = false,
    ) {
        if (activeDiscrete !== active || active.startCallbackArrived) return
        active.startCallbackArrived = true
        if (active.cancelRequested) {
            if (result.succeeded) {
                active.accepted = true
                requestPendingDiscreteCancellationLocked(active)
            } else if (outcomeUnknown && !active.cancelRequestInFlight) {
                requestPendingDiscreteCancellationLocked(active)
            } else if (!outcomeUnknown && !active.cancelRequestInFlight) {
                activeDiscrete = null
                settleDiscreteCancellationLocked(active, true)
            }
            return
        }
        if (result.succeeded) {
            active.accepted = true
        } else if (outcomeUnknown) {
            active.outcomeUnknown = true
            active.cancelRequested = true
            requestPendingDiscreteCancellationLocked(active)
        } else {
            activeDiscrete = null
        }
        completeLocked(active.completion, result)
        if (activeDiscrete === active) reconcileDiscreteLocked()
    }

    private fun handleDiscreteInvocationThrowLocked(
        active: ActiveDiscrete,
        reason: String,
    ) {
        if (activeDiscrete !== active) return
        if (!active.startCallbackArrived) {
            finishDiscreteLocked(active, failure(reason), outcomeUnknown = true)
            return
        }
        if (active.accepted && !active.cancelRequested) {
            active.cancelRequested = true
            requestPendingDiscreteCancellationLocked(active)
        }
    }

    private fun reconcileDiscreteLocked() {
        val active = activeDiscrete ?: return
        if ((!active.accepted && !active.outcomeUnknown) || active.cancelRequested) return
        when (active.action) {
            ConsoleDiscreteAction.TAKEOFF -> Unit
            ConsoleDiscreteAction.LANDING -> {
                runCatching { telemetrySource.latest() }.getOrNull()?.let(::observeTelemetryLocked)
                val snapshot = runCatching { ports.landing.currentSnapshot() }.getOrNull() ?: return
                observeLandingSnapshotLocked(snapshot)
            }
            ConsoleDiscreteAction.RETURN_TO_HOME -> {
                val snapshot = runCatching { ports.rth.currentSnapshot() }.getOrNull() ?: return
                observeRthSnapshotLocked(snapshot)
            }
        }
    }

    private fun initializeLandingTelemetryBaselineLocked(active: ActiveDiscrete) {
        val flightState = runCatching { telemetrySource.latest() }.getOrNull()?.flightState ?: return
        active.landingBaselineIsFlyingObservedAtNanos = flightState.isFlyingObservedAtNanos
        active.landingBaselineModeObservedAtNanos = flightState.modeObservedAtNanos
        active.landingObservedFlying = flightState.isFlying == true
    }

    private fun initializeRthBaselineLocked(active: ActiveDiscrete) {
        active.rthBaselineState = runCatching { ports.rth.currentSnapshot() }.getOrNull()?.state
        active.rthBaselineModeObservedAtNanos =
            runCatching { telemetrySource.latest() }
                .getOrNull()
                ?.flightState
                ?.modeObservedAtNanos
                ?: 0L
    }

    private fun observeTelemetryLocked(telemetry: Telemetry) {
        val active = activeDiscrete ?: return
        val flightState = telemetry.flightState ?: return
        if (
            active.action == ConsoleDiscreteAction.RETURN_TO_HOME &&
                flightState.mode == FlightMode.RETURNING_HOME &&
                flightState.modeObservedAtNanos > active.rthBaselineModeObservedAtNanos
        ) {
            active.observedActive = true
        }
        if (active.action != ConsoleDiscreteAction.LANDING) return
        if (
            flightState.isFlying == true &&
                flightState.isFlyingObservedAtNanos >= active.landingBaselineIsFlyingObservedAtNanos &&
                flightState.isFlyingObservedAtNanos > 0L
        ) {
            active.landingObservedFlying = true
        }
        val groundedAfterAdmission =
            flightState.isFlying == false &&
                flightState.mode == FlightMode.ON_GROUND &&
                flightState.isFlyingObservedAtNanos > active.landingBaselineIsFlyingObservedAtNanos &&
                flightState.modeObservedAtNanos > active.landingBaselineModeObservedAtNanos
        if (
            !active.cancelRequested &&
                active.startCallbackArrived &&
                active.accepted &&
                active.landingObservedFlying &&
                groundedAfterAdmission
        ) {
            activeDiscrete = null
        }
    }

    private fun observeLandingSnapshotLocked(snapshot: LandingActionSnapshot) {
        val active = activeDiscrete?.takeIf { it.action == ConsoleDiscreteAction.LANDING } ?: return
        if (snapshot.landingActive == true || snapshot.confirmationNeeded == true) {
            active.observedActive = true
        }
        if (
            snapshot.confirmationNeeded == true &&
                active.startCallbackArrived &&
                active.accepted &&
                !active.cancelRequested &&
                !active.confirmationAttempted
        ) {
            requestLandingConfirmationLocked(active)
        }
        if (
            !active.cancelRequested &&
                active.startCallbackArrived &&
                (active.accepted || active.outcomeUnknown) &&
                snapshot.aircraftConnected == true &&
                snapshot.pendingAction == null &&
                snapshot.landingActive == false &&
                active.observedActive
        ) {
            activeDiscrete = null
        }
    }

    private fun requestLandingConfirmationLocked(active: ActiveDiscrete) {
        if (
            activeDiscrete !== active ||
                active.action != ConsoleDiscreteAction.LANDING ||
                active.cancelRequested ||
                active.confirmationAttempted
        ) {
            return
        }
        active.confirmationAttempted = true
        active.confirmationRequestInFlight = true
        try {
            ports.landing.confirm { result ->
                serialized {
                    finishLandingConfirmationLocked(
                        active = active,
                        succeeded = result is LandingActionResult.Success,
                    )
                }
            }
        } catch (_: Throwable) {
            finishLandingConfirmationLocked(active, succeeded = false)
        }
    }

    private fun finishLandingConfirmationLocked(
        active: ActiveDiscrete,
        succeeded: Boolean,
    ) {
        if (
            activeDiscrete !== active ||
                active.action != ConsoleDiscreteAction.LANDING ||
                !active.confirmationRequestInFlight
        ) {
            return
        }
        active.confirmationRequestInFlight = false
        if (active.cancelRequested) {
            requestPendingDiscreteCancellationLocked(active)
            return
        }
        if (succeeded) return

        landingConfirmationFaultLatched = true
        refreshReadinessLocked(force = true)
        active.cancelRequested = true
        requestPendingDiscreteCancellationLocked(active)
    }

    private fun observeRthSnapshotLocked(snapshot: ReturnToHomeActionSnapshot) {
        val active =
            activeDiscrete?.takeIf { it.action == ConsoleDiscreteAction.RETURN_TO_HOME } ?: return
        if (
            snapshot.state == ReturnToHomeState.RETURNING_TO_HOME ||
            snapshot.state == ReturnToHomeState.LANDING
        ) {
            active.observedActive = true
        }
        val terminalState =
            (snapshot.state == ReturnToHomeState.COMPLETED &&
                (active.observedActive ||
                    active.rthBaselineState?.let { it != ReturnToHomeState.COMPLETED } == true)) ||
                (active.observedActive && snapshot.state == ReturnToHomeState.IDLE)
        if (
            !active.cancelRequested &&
                active.startCallbackArrived &&
                (active.accepted || active.outcomeUnknown) &&
                snapshot.aircraftConnected == true &&
                snapshot.pendingAction == null &&
                terminalState
        ) {
            activeDiscrete = null
        }
    }

    private fun pendingControlLocked(
        frame: AdmittedControlFrame,
        completion: OnceExecutionCallback,
    ): PendingControl? {
        val deadline = controlDeadlineNanos(frame)
        val now = safeConsoleNowLocked()
        val reason =
            when {
                closed -> "dji_executor_closed"
                physicalTakeoverLatched -> "dji_physical_rc_takeover"
                frame.controlEpoch <= highestFencedControlEpoch -> "dji_control_epoch_fenced"
                deadline == null || now == null || now >= deadline -> "dji_control_expired"
                activeDiscrete != null -> "dji_discrete_busy"
                !computeReadinessLocked() -> "dji_actuation_not_ready"
                else -> null
            }
        if (reason != null) {
            completeLocked(completion, failure(reason))
            return null
        }
        return PendingControl(frame, checkNotNull(deadline), completion)
    }

    private fun activateControlLocked(pending: PendingControl) {
        val generation = nextPortGenerationLocked()
        if (generation == null) {
            completeLocked(pending.completion, failure("dji_control_generation_exhausted"))
            return
        }
        val active =
            ActiveControl(
                leaseId = pending.frame.frame.leaseId,
                controlEpoch = pending.frame.controlEpoch,
                generation = generation,
                phase = ControlPhase.ACTIVATING,
                pending = pending,
            )
        activeControl = active

        try {
            ports.flightControl.activate(
                generation,
                ActuationOperationCallback { result ->
                    serialized { onControlActivatedLocked(active, result) }
                },
            )
        } catch (_: Throwable) {
            if (activeControl === active) {
                if (active.phase == ControlPhase.ACTIVATING) {
                    activeControl = null
                    active.pending = null
                    completeLocked(pending.completion, failure("dji_activation_executor_failure"))
                } else if (active.phase == ControlPhase.ACTIVE) {
                    beginControlShutdownLocked(
                        active = active,
                        reason = NeutralizationReason.FAULT,
                        pendingFailure = "dji_activation_executor_failure",
                        completion = null,
                    )
                }
            }
            refreshReadinessLocked()
        }
    }

    private fun onControlActivatedLocked(
        active: ActiveControl,
        result: ActuationOperationResult,
    ) {
        if (activeControl !== active || active.phase != ControlPhase.ACTIVATING) return
        val pending = active.pending
        if (!result.succeeded) {
            activeControl = null
            active.pending = null
            pending?.let {
                val kind = result.failure?.kind?.name?.lowercase() ?: "unknown"
                completeLocked(it.completion, failure("dji_activation_$kind"))
            }
            refreshReadinessLocked()
            return
        }

        val now = safeConsoleNowLocked()
        if (
            pending == null ||
                closed ||
                physicalTakeoverLatched ||
                pending.frame.controlEpoch <= highestFencedControlEpoch ||
                now == null ||
                now >= pending.deadlineNanos ||
                !computeReadinessLocked()
        ) {
            active.pending = null
            pending?.let { completeLocked(it.completion, failure("dji_control_expired_or_revoked")) }
            beginControlShutdownLocked(
                active = active,
                reason = NeutralizationReason.AUTHORITY_LOSS,
                pendingFailure = "dji_control_expired_or_revoked",
                completion = null,
            )
            return
        }

        active.phase = ControlPhase.ACTIVE
        active.pending = null
        submitToActiveControlLocked(active, pending)
        refreshReadinessLocked()
    }

    private fun submitToActiveControlLocked(
        active: ActiveControl,
        pending: PendingControl,
    ) {
        val now = safeConsoleNowLocked()
        if (
            activeControl !== active ||
                active.phase != ControlPhase.ACTIVE ||
                closed ||
                physicalTakeoverLatched ||
                active.controlEpoch <= highestFencedControlEpoch ||
                now == null ||
                now >= pending.deadlineNanos ||
                !computeReadinessLocked()
        ) {
            completeLocked(pending.completion, failure("dji_control_expired_or_revoked"))
            return
        }

        val createdAtNanos = runCatching(actuationClockNanos).getOrNull()
        if (createdAtNanos == null || createdAtNanos < 0L) {
            completeLocked(pending.completion, failure("dji_control_clock_unavailable"))
            return
        }
        val vendorFrame =
            ActuationCommandFrame(
                command = pending.frame.command,
                producer = ControlProducer.MANUAL,
                generation = active.generation,
                commandSequence = pending.frame.frame.inputSequence,
                createdAtNanos = createdAtNanos,
                inputSequence = pending.frame.frame.inputSequence,
            )
        val status =
            runCatching { ports.flightControl.submit(vendorFrame) }
                .getOrElse {
                    completeLocked(pending.completion, failure("dji_control_executor_failure"))
                    return
                }
        if (status == CommandSubmissionStatus.SUBMITTED) {
            completeLocked(
                pending.completion,
                success("DJI MSDK admitted the Virtual Stick frame to its sender."),
            )
        } else {
            completeLocked(
                pending.completion,
                failure("dji_control_${status.name.lowercase()}"),
            )
        }
    }

    private fun beginControlShutdownLocked(
        active: ActiveControl,
        reason: NeutralizationReason,
        pendingFailure: String,
        completion: OnceExecutionCallback?,
    ) {
        if (activeControl !== active) {
            completion?.let { completeLocked(it, success("DJI control generation already closed.")) }
            return
        }
        if (active.phase == ControlPhase.DEACTIVATING) {
            completion?.let { active.shutdownCompletions += it }
            return
        }

        active.pending?.let { completeLocked(it.completion, failure(pendingFailure)) }
        active.pending = null
        completion?.let { active.shutdownCompletions += it }

        val shouldNeutralize = active.phase == ControlPhase.ACTIVE
        active.phase = ControlPhase.DEACTIVATING
        active.neutralSubmitted =
            if (shouldNeutralize) {
                runCatching {
                    ports.flightControl.neutralize(active.generation, reason) ==
                        CommandSubmissionStatus.SUBMITTED
                }.getOrDefault(false)
            } else {
                false
            }

        try {
            ports.flightControl.deactivate(
                active.generation,
                ActuationOperationCallback { result ->
                    serialized { finishControlShutdownLocked(active, result) }
                },
            )
        } catch (_: Throwable) {
            finishControlShutdownLocked(active, result = null)
        }
        refreshReadinessLocked()
    }

    private fun finishControlShutdownLocked(
        active: ActiveControl,
        result: ActuationOperationResult?,
    ) {
        if (activeControl !== active || active.phase != ControlPhase.DEACTIVATING) return
        activeControl = null
        val failureKind = result?.failure?.kind?.name?.lowercase() ?: "executor_failure"
        val completionResult = when {
            result?.succeeded == true ->
                success("DJI Virtual Stick generation neutralized and released.")
            active.neutralSubmitted ->
                success("DJI Virtual Stick neutral command was submitted; release was not confirmed.")
            else -> failure("dji_deactivation_$failureKind")
        }
        active.shutdownCompletions.forEach { completeLocked(it, completionResult) }
        active.shutdownCompletions.clear()
        refreshReadinessLocked()
    }

    private fun nextPortGenerationLocked(): ControlGeneration? {
        val watermark =
            runCatching { ports.flightControl.currentSnapshot().generationWatermark.value }
                .getOrNull() ?: return null
        val current = maxOf(nextPortGenerationValue, watermark)
        if (current == Long.MAX_VALUE) return null
        nextPortGenerationValue = current + 1L
        return ControlGeneration(nextPortGenerationValue)
    }

    private fun controlDeadlineNanos(frame: AdmittedControlFrame): Long? {
        if (frame.admittedAtNanos < 0L || frame.expiresAtNanos < frame.admittedAtNanos) return null
        return frame.expiresAtNanos
    }

    private fun cancelDiscreteActionsLocked(
        reason: String,
        observedActions: Set<ConsoleDiscreteAction> = observedDiscreteActionsLocked(),
        observedSettlementFactory: ((ConsoleDiscreteAction) -> ((Boolean) -> Unit))? = null,
    ) {
        val pending = activeDiscrete
        if (pending != null && !pending.cancelRequested) {
            pending.cancelRequested = true
            completeLocked(pending.completion, failure(reason))
            requestPendingDiscreteCancellationLocked(pending)
        } else if (pending?.cancelRequested == true && pending.startCallbackArrived) {
            requestPendingDiscreteCancellationLocked(pending)
        }

        val cancelTakeoff =
            pending?.action != ConsoleDiscreteAction.TAKEOFF &&
                ConsoleDiscreteAction.TAKEOFF in observedActions
        val cancelLanding =
            pending?.action != ConsoleDiscreteAction.LANDING &&
                ConsoleDiscreteAction.LANDING in observedActions
        val cancelRth =
            pending?.action != ConsoleDiscreteAction.RETURN_TO_HOME &&
                ConsoleDiscreteAction.RETURN_TO_HOME in observedActions

        if (cancelTakeoff) {
            requestObservedDiscreteCancellationLocked(
                ConsoleDiscreteAction.TAKEOFF,
                observedSettlementFactory?.invoke(ConsoleDiscreteAction.TAKEOFF),
            )
        }
        if (cancelLanding) {
            requestObservedDiscreteCancellationLocked(
                ConsoleDiscreteAction.LANDING,
                observedSettlementFactory?.invoke(ConsoleDiscreteAction.LANDING),
            )
        }
        if (cancelRth) {
            requestObservedDiscreteCancellationLocked(
                ConsoleDiscreteAction.RETURN_TO_HOME,
                observedSettlementFactory?.invoke(ConsoleDiscreteAction.RETURN_TO_HOME),
            )
        }
    }

    private fun observedDiscreteActionsLocked(): Set<ConsoleDiscreteAction> =
        buildSet {
            val observedFlightMode = trustedFlightStateLocked()?.mode
            val takeoff =
                if (takeoffObservationInitialized && takeoffSnapshotListenerInstalled) {
                    runCatching { ports.takeoff.currentSnapshot() }.getOrNull()
                } else {
                    null
                }
            if (
                takeoff == null ||
                    !takeoff.observing ||
                    takeoff.aircraftConnected != true ||
                    observedFlightMode == null ||
                    observedFlightMode == FlightMode.TAKING_OFF ||
                    takeoff.takeoffMayBeActive ||
                    takeoff.pendingAction != null
            ) {
                add(ConsoleDiscreteAction.TAKEOFF)
            }

            val landing =
                if (landingObservationInitialized && landingSnapshotListenerInstalled) {
                    runCatching { ports.landing.currentSnapshot() }.getOrNull()
                } else {
                    null
                }
            if (
                landing == null ||
                    !landing.observing ||
                    landing.aircraftConnected != true ||
                    observedFlightMode == null ||
                    observedFlightMode == FlightMode.LANDING ||
                    landing.confirmationNeeded == null ||
                    landing.landingActive == true ||
                    landing.confirmationNeeded == true ||
                    landing.pendingAction != null
            ) {
                add(ConsoleDiscreteAction.LANDING)
            }

            val rth =
                if (rthObservationInitialized && rthSnapshotListenerInstalled) {
                    runCatching { ports.rth.currentSnapshot() }.getOrNull()
                } else {
                    null
                }
            if (
                rth == null ||
                    !rth.observing ||
                    rth.aircraftConnected != true ||
                    observedFlightMode == null ||
                    observedFlightMode == FlightMode.RETURNING_HOME ||
                    rth.state == ReturnToHomeState.UNKNOWN ||
                    rth.pendingAction != null ||
                    rth.state == ReturnToHomeState.RETURNING_TO_HOME ||
                    rth.state == ReturnToHomeState.LANDING
            ) {
                add(ConsoleDiscreteAction.RETURN_TO_HOME)
            }
        }

    private fun trustedFlightStateLocked(): FlightStateTelemetry? {
        val state = runCatching { telemetrySource.latest() }.getOrNull()?.flightState ?: return null
        val now = runCatching(actuationClockNanos).getOrNull()?.takeIf { it >= 0L } ?: return null
        fun isFresh(observedAtNanos: Long): Boolean =
            observedAtNanos > 0L &&
                now >= observedAtNanos &&
                now - observedAtNanos <= MAX_FLIGHT_STATE_AGE_NANOS
        return state.takeIf {
            it.mode != FlightMode.UNKNOWN &&
                it.isFlying != null &&
                isFresh(it.modeObservedAtNanos) &&
                isFresh(it.isFlyingObservedAtNanos)
        }
    }

    private fun refreshTelemetryOutsideLock() {
        check(!dispatchLock.isHeldByCurrentThread) { "telemetry refresh must run outside executor lock" }
        runCatching(telemetryRefresh)
    }

    private fun requestPendingDiscreteCancellationLocked(active: ActiveDiscrete) {
        if (
            activeDiscrete !== active ||
                !active.cancelRequested ||
                active.cancelRequestInFlight ||
                active.confirmationRequestInFlight
        ) {
            return
        }
        active.cancelRequestInFlight = true
        when (active.action) {
            ConsoleDiscreteAction.TAKEOFF ->
                runCatching {
                    ports.takeoff.stopTakeoff { result ->
                        serialized {
                            onDiscreteCancellationResultLocked(
                                active,
                                result is TakeoffActionResult.Success,
                            )
                        }
                    }
                }.onFailure {
                    active.cancelRequestInFlight = false
                    if (active.startCallbackArrived) settleDiscreteCancellationLocked(active, false)
                }
            ConsoleDiscreteAction.LANDING ->
                runCatching {
                    ports.landing.cancel { result ->
                        serialized {
                            onDiscreteCancellationResultLocked(
                                active,
                                result is LandingActionResult.Success,
                            )
                        }
                    }
                }.onFailure {
                    active.cancelRequestInFlight = false
                    if (active.startCallbackArrived) settleDiscreteCancellationLocked(active, false)
                }
            ConsoleDiscreteAction.RETURN_TO_HOME ->
                runCatching {
                    ports.rth.cancel { result ->
                        serialized {
                            onDiscreteCancellationResultLocked(
                                active,
                                result is ReturnToHomeActionResult.Success,
                            )
                        }
                    }
                }.onFailure {
                    active.cancelRequestInFlight = false
                    if (active.startCallbackArrived) settleDiscreteCancellationLocked(active, false)
                }
        }
    }

    private fun onDiscreteCancellationResultLocked(
        active: ActiveDiscrete,
        succeeded: Boolean,
    ) {
        if (activeDiscrete !== active) return
        active.cancelRequestInFlight = false
        if (succeeded) {
            activeDiscrete = null
            settleDiscreteCancellationLocked(active, true)
        } else if (active.startCallbackArrived) {
            settleDiscreteCancellationLocked(active, false)
        }
    }

    private fun requestObservedDiscreteCancellationLocked(
        action: ConsoleDiscreteAction,
        settlement: ((Boolean) -> Unit)?,
    ) {
        val invoked =
            runCatching {
                when (action) {
                    ConsoleDiscreteAction.TAKEOFF ->
                        ports.takeoff.stopTakeoff { result ->
                            serialized { settlement?.invoke(result is TakeoffActionResult.Success) }
                        }
                    ConsoleDiscreteAction.LANDING ->
                        ports.landing.cancel { result ->
                            serialized { settlement?.invoke(result is LandingActionResult.Success) }
                        }
                    ConsoleDiscreteAction.RETURN_TO_HOME ->
                        ports.rth.cancel { result ->
                            serialized {
                                settlement?.invoke(result is ReturnToHomeActionResult.Success)
                            }
                        }
                }
            }.isSuccess
        if (!invoked) settlement?.invoke(false)
    }

    private fun settleDiscreteCancellationLocked(
        active: ActiveDiscrete,
        succeeded: Boolean,
    ) {
        val callbacks = active.cancellationSettlements.toList()
        active.cancellationSettlements.clear()
        callbacks.forEach { it(succeeded) }
    }

    private fun handlePhysicalTakeoverLocked(reason: String) {
        if (closed || physicalTakeoverLatched) return
        reconcileDiscreteLocked()
        physicalTakeoverLatched = true
        refreshReadinessLocked(force = true)
        cancelDiscreteActionsLocked(
            reason = "dji_physical_rc_takeover",
            observedActions = observedDiscreteActionsLocked(),
        )
        activeControl?.let {
            highestFencedControlEpoch = maxOf(highestFencedControlEpoch, it.controlEpoch)
            beginControlShutdownLocked(
                active = it,
                reason = NeutralizationReason.AUTHORITY_LOSS,
                pendingFailure = "dji_physical_rc_takeover",
                completion = null,
            )
        }
        deferLocked { onPhysicalRcTakeover(reason.take(MAX_TAKEOVER_REASON_LENGTH)) }
    }

    private fun installPortListenersLocked() {
        if (closed) return
        if (!rcSnapshotListenerInstalled) {
            rcSnapshotListenerInstalled =
                runCatching {
                    ports.physicalRcTakeover.addSnapshotListener(rcSnapshotListener)
                }.isSuccess
        }
        if (!physicalTakeoverListenerInstalled) {
            physicalTakeoverListenerInstalled =
                runCatching {
                    ports.physicalRcTakeover.addPhysicalRcTakeoverListener(physicalTakeoverListener)
                }.isSuccess
        }
        if (!flightSnapshotListenerInstalled) {
            flightSnapshotListenerInstalled =
                runCatching { ports.flightControl.addSnapshotListener(flightSnapshotListener) }.isSuccess
        }
        if (!takeoffSnapshotListenerInstalled) {
            takeoffSnapshotListenerInstalled =
                runCatching { ports.takeoff.addSnapshotListener(takeoffSnapshotListener) }.isSuccess
        }
        if (!landingSnapshotListenerInstalled) {
            landingSnapshotListenerInstalled =
                runCatching { ports.landing.addSnapshotListener(landingSnapshotListener) }.isSuccess
        }
        if (!rthSnapshotListenerInstalled) {
            rthSnapshotListenerInstalled =
                runCatching { ports.rth.addSnapshotListener(rthSnapshotListener) }.isSuccess
        }
    }

    private fun installTelemetryListenerLocked() {
        if (closed || telemetryListenerInstalled) return
        telemetryListenerInstalled =
            runCatching { telemetrySource.addListener(telemetryListener) }.isSuccess
    }

    private fun initializeActionObservationsLocked() {
        if (closed) return
        if (!takeoffObservationInitialized) {
            takeoffObservationInitialized =
                runCatching { ports.takeoff.initialize() is TakeoffActionResult.Success }
                    .getOrDefault(false)
        }
        if (!landingObservationInitialized) {
            landingObservationInitialized =
                runCatching { ports.landing.initialize() is LandingActionResult.Success }
                    .getOrDefault(false)
        }
        if (!rthObservationInitialized) {
            rthObservationInitialized =
                runCatching { ports.rth.initialize() is ReturnToHomeActionResult.Success }
                    .getOrDefault(false)
        }
    }

    private fun startPhysicalRcRailLocked() {
        if (closed || rcStarted) return
        val now = safeConsoleNowLocked() ?: return
        if (now < nextRcStartAttemptNanos) return
        if (rcStartAttempts < Long.MAX_VALUE) rcStartAttempts += 1
        rcStarted =
            runCatching { ports.physicalRcTakeover.start() }
                .getOrNull() is PhysicalRcTakeoverStartResult.Started
        if (!rcStarted) {
            nextRcStartAttemptNanos =
                if (now > Long.MAX_VALUE - RC_START_RETRY_NANOS) Long.MAX_VALUE
                else now + RC_START_RETRY_NANOS
        }
    }

    private fun installReadinessPollLocked() {
        val executor = readinessExecutor ?: return
        pollingHealthy =
            try {
                readinessPoll =
                    executor.scheduleAtFixedRate(
                        {
                            refreshTelemetryOutsideLock()
                            serialized {
                                if (!closed) {
                                    installPortListenersLocked()
                                    installTelemetryListenerLocked()
                                    initializeActionObservationsLocked()
                                    startPhysicalRcRailLocked()
                                    refreshReadinessLocked()
                                }
                            }
                        },
                        READINESS_POLL_MILLIS,
                        READINESS_POLL_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                true
            } catch (_: Throwable) {
                false
            }
    }

    private fun computeReadinessLocked(): Boolean {
        if (
            closed ||
                physicalTakeoverLatched ||
                landingConfirmationFaultLatched ||
                !rcStarted ||
                !pollingHealthy ||
                !rcSnapshotListenerInstalled ||
                !physicalTakeoverListenerInstalled ||
                !flightSnapshotListenerInstalled ||
                !takeoffSnapshotListenerInstalled ||
                !landingSnapshotListenerInstalled ||
                !rthSnapshotListenerInstalled ||
                !telemetryListenerInstalled ||
                !takeoffObservationInitialized ||
                !landingObservationInitialized ||
                !rthObservationInitialized
        ) {
            return false
        }
        val rc = runCatching { ports.physicalRcTakeover.currentSnapshot() }.getOrNull() ?: return false
        val flight = runCatching { ports.flightControl.currentSnapshot() }.getOrNull() ?: return false
        val takeoff = runCatching { ports.takeoff.currentSnapshot() }.getOrNull() ?: return false
        val landing = runCatching { ports.landing.currentSnapshot() }.getOrNull() ?: return false
        val rth = runCatching { ports.rth.currentSnapshot() }.getOrNull() ?: return false
        val flightState = trustedFlightStateLocked() ?: return false
        val ownedAction = activeDiscrete?.action
        val ownedControl = activeControl
        val flightControlConsistent =
            if (ownedControl == null) {
                flight.state == FlightControlPortState.IDLE
            } else {
                flight.generation == ownedControl.generation &&
                    flight.state != FlightControlPortState.FAULTED &&
                    flight.state != FlightControlPortState.RELEASED
            }
        val conflictingTakeoff =
            (takeoff.takeoffMayBeActive ||
                takeoff.pendingAction != null ||
                flightState.mode == FlightMode.TAKING_OFF) &&
                ownedAction != ConsoleDiscreteAction.TAKEOFF
        val conflictingLanding =
            (landing.landingActive == true ||
                landing.confirmationNeeded == true ||
                landing.pendingAction != null ||
                flightState.mode == FlightMode.LANDING) &&
                ownedAction != ConsoleDiscreteAction.LANDING
        val conflictingRth =
            (rth.pendingAction != null ||
                rth.state == ReturnToHomeState.RETURNING_TO_HOME ||
                rth.state == ReturnToHomeState.LANDING ||
                flightState.mode == FlightMode.RETURNING_HOME) &&
                ownedAction != ConsoleDiscreteAction.RETURN_TO_HOME
        return rc.state == PhysicalRcTakeoverPortState.RUNNING &&
            rc.observationReady &&
            rc.neutral == true &&
            flightControlConsistent &&
            takeoff.observing &&
            takeoff.aircraftConnected == true &&
            takeoff.startSupported == true &&
            takeoff.stopSupported == true &&
            !conflictingTakeoff &&
            landing.observing &&
            landing.aircraftConnected == true &&
            landing.startSupported == true &&
            landing.cancelSupported == true &&
            landing.confirmSupported == true &&
            landing.confirmationNeeded != null &&
            !conflictingLanding &&
            rth.observing &&
            rth.aircraftConnected == true &&
            rth.startSupported == true &&
            rth.cancelSupported == true &&
            rth.state != ReturnToHomeState.UNKNOWN &&
            !conflictingRth &&
            flightState.isFlying != null &&
            flightState.mode != FlightMode.UNKNOWN
    }

    private fun refreshReadinessLocked(force: Boolean = false) {
        publishReadinessLocked(computeReadinessLocked(), force)
    }

    private fun publishReadinessLocked(
        ready: Boolean,
        force: Boolean = false,
    ) {
        if (!force && lastPublishedReadiness == ready) return
        lastPublishedReadiness = ready
        readinessListeners.toList().forEach { listener ->
            deferLocked { listener.onReadinessChanged() }
        }
    }

    private fun safeConsoleNowLocked(): Long? =
        runCatching { monotonicClock.nowNanos() }
            .getOrNull()
            ?.takeIf { it >= 0L }

    private fun completeLocked(
        completion: OnceExecutionCallback,
        result: ConsoleExecutionResult,
    ) {
        if (completion.claim()) deferLocked { completion.callback(result) }
    }

    private fun addNeutralPartLocked(barrier: NeutralBarrier): NeutralPart {
        check(!barrier.sealed) { "neutral barrier is already sealed" }
        barrier.pendingParts += 1
        return NeutralPart()
    }

    private fun completeNeutralPartLocked(
        barrier: NeutralBarrier,
        part: NeutralPart,
        succeeded: Boolean,
        failureReason: String,
    ) {
        if (part.completed) return
        part.completed = true
        barrier.pendingParts -= 1
        if (!succeeded && barrier.failureReason == null) barrier.failureReason = failureReason
        finishNeutralBarrierIfReadyLocked(barrier)
    }

    private fun sealNeutralBarrierLocked(barrier: NeutralBarrier) {
        barrier.sealed = true
        finishNeutralBarrierIfReadyLocked(barrier)
    }

    private fun finishNeutralBarrierIfReadyLocked(barrier: NeutralBarrier) {
        if (!barrier.sealed || barrier.pendingParts != 0) return
        val result =
            barrier.failureReason?.let(::failure)
                ?: success("DJI actuation barrier completed.")
        completeLocked(barrier.completion, result)
    }

    private fun deferLocked(callback: () -> Unit) {
        check(dispatchLock.isHeldByCurrentThread) { "deferred callbacks require executor serialization" }
        deferredCallbacks.addLast(callback)
    }

    private fun <T> serialized(block: () -> T): T {
        var outcome: Result<T>? = null
        var callbacks: List<() -> Unit> = emptyList()
        dispatchLock.lock()
        dispatchDepth += 1
        try {
            outcome = runCatching(block)
        } finally {
            dispatchDepth -= 1
            if (dispatchDepth == 0 && deferredCallbacks.isNotEmpty()) {
                callbacks = buildList(deferredCallbacks.size) {
                    while (deferredCallbacks.isNotEmpty()) add(deferredCallbacks.removeFirst())
                }
            }
            dispatchLock.unlock()
        }
        callbacks.forEach { runCatching(it) }
        return checkNotNull(outcome).getOrThrow()
    }

    private fun ConsoleSafetyTrigger.toNeutralizationReason(): NeutralizationReason =
        when (this) {
            ConsoleSafetyTrigger.CLIENT_REQUEST -> NeutralizationReason.PRODUCER_RELEASED
            ConsoleSafetyTrigger.CLIENT_DISCONNECT,
            ConsoleSafetyTrigger.SERVER_STOP,
            -> NeutralizationReason.LIFECYCLE_LOSS
            ConsoleSafetyTrigger.LEASE_EXPIRED,
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
            -> NeutralizationReason.AUTHORITY_LOSS
            ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED -> NeutralizationReason.WATCHDOG
        }

    private class ActiveDiscrete(
        val action: ConsoleDiscreteAction,
        val completion: OnceExecutionCallback,
        var cancelRequested: Boolean = false,
        var cancelRequestInFlight: Boolean = false,
        var startCallbackArrived: Boolean = false,
        var accepted: Boolean = false,
        var outcomeUnknown: Boolean = false,
        var observedActive: Boolean = false,
        var confirmationAttempted: Boolean = false,
        var confirmationRequestInFlight: Boolean = false,
        var landingBaselineIsFlyingObservedAtNanos: Long = 0L,
        var landingBaselineModeObservedAtNanos: Long = 0L,
        var landingObservedFlying: Boolean = false,
        var rthBaselineState: ReturnToHomeState? = null,
        var rthBaselineModeObservedAtNanos: Long = 0L,
        val cancellationSettlements: MutableList<(Boolean) -> Unit> = mutableListOf(),
    )

    private data class PendingControl(
        val frame: AdmittedControlFrame,
        val deadlineNanos: Long,
        val completion: OnceExecutionCallback,
    )

    private class ActiveControl(
        val leaseId: String,
        val controlEpoch: Long,
        val generation: ControlGeneration,
        var phase: ControlPhase,
        var pending: PendingControl?,
        var neutralSubmitted: Boolean = false,
        val shutdownCompletions: MutableList<OnceExecutionCallback> = mutableListOf(),
    )

    private enum class ControlPhase {
        ACTIVATING,
        ACTIVE,
        DEACTIVATING,
    }

    private class NeutralBarrier(
        val completion: OnceExecutionCallback,
        var pendingParts: Int = 0,
        var sealed: Boolean = false,
        var failureReason: String? = null,
    )

    private class NeutralPart(
        var completed: Boolean = false,
    )

    private class OnceExecutionCallback(
        val callback: (ConsoleExecutionResult) -> Unit,
    ) {
        private val completed = AtomicBoolean(false)

        fun claim(): Boolean = completed.compareAndSet(false, true)
    }

    private companion object {
        const val READINESS_POLL_MILLIS = 100L
        const val RC_START_RETRY_NANOS = 750_000_000L
        const val MAX_FLIGHT_STATE_AGE_NANOS = 2_000_000_000L
        const val MAX_TAKEOVER_REASON_LENGTH = 256

        fun success(detail: String): ConsoleExecutionResult =
            ConsoleExecutionResult(succeeded = true, detail = detail)

        fun failure(reason: String): ConsoleExecutionResult =
            ConsoleExecutionResult(succeeded = false, reason = reason)

        fun AircraftActionFailureKind.mayHaveActuated(): Boolean =
            this == AircraftActionFailureKind.DISCONNECTED ||
                this == AircraftActionFailureKind.TIMEOUT ||
                this == AircraftActionFailureKind.INTERNAL_ERROR

        fun TakeoffActionFailureKind.mayHaveActuated(): Boolean =
            this == TakeoffActionFailureKind.DISCONNECTED ||
                this == TakeoffActionFailureKind.TIMEOUT
    }
}
