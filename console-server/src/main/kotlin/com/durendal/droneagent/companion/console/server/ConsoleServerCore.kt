package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.core.control.BodyFrameVelocityCommand
import com.durendal.droneagent.core.control.CommandEnvelope
import com.durendal.droneagent.core.control.CommandSaturationGate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

data class ConsoleServerCoreConfig(
    val minimumLeaseTtlMillis: Long = ConsoleProtocolModule.MIN_LEASE_TTL_MS.toLong(),
    val maximumLeaseTtlMillis: Long = ConsoleProtocolModule.MAX_LEASE_TTL_MS.toLong(),
    val minimumCommandTtlMillis: Long = ConsoleProtocolModule.MIN_COMMAND_TTL_MS.toLong(),
    val maximumCommandTtlMillis: Long = ConsoleProtocolModule.MAX_COMMAND_TTL_MS.toLong(),
    val minimumControlTtlMillis: Long = ConsoleProtocolModule.MIN_CONTROL_TTL_MS.toLong(),
    val maximumControlTtlMillis: Long = ConsoleProtocolModule.MAX_CONTROL_TTL_MS.toLong(),
    val deadManTimeoutMillis: Long = 250L,
    /** Bounded, fail-closed command-id inventory. Current-lease receipts are never evicted. */
    val maximumCommandRecords: Int = 4_096,
    val controlEnvelope: CommandEnvelope = CommandEnvelope.PROVISIONAL,
) {
    init {
        require(minimumLeaseTtlMillis > 0 && maximumLeaseTtlMillis >= minimumLeaseTtlMillis)
        require(minimumCommandTtlMillis > 0 && maximumCommandTtlMillis >= minimumCommandTtlMillis)
        require(minimumControlTtlMillis > 0 && maximumControlTtlMillis >= minimumControlTtlMillis)
        require(deadManTimeoutMillis > 0)
        require(maximumCommandRecords > 0)
    }
}

/**
 * Transport-independent owner of console sessions, one operator lease and the server dead-man.
 * All external callbacks (audit, transport events and executor operations) happen outside [lock].
 */
class ConsoleServerCore(
    private val config: ConsoleServerCoreConfig,
    private val admission: ConsoleCommandAdmission,
    private val executor: ConsoleCommandExecutor,
    private val monotonicClock: ConsoleMonotonicClock,
    private val epochClock: ConsoleEpochClock,
    private val scheduler: ConsoleDeadlineScheduler,
    private val auditSink: ConsoleAuditSink,
    private val eventSink: ConsoleEventSink,
    initialActuationReadiness: ConsoleActuationReadinessSnapshot =
        ConsoleActuationReadinessSnapshot.UNAVAILABLE,
    initialActuationObservation: ConsoleActuationObservation =
        ConsoleActuationObservation.UNAVAILABLE,
    /** Must return a process-lifetime unique protocol identifier; production uses UUIDs. */
    private val leaseIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val commissioningLifecycle: ConsoleCommissioningLifecycle =
        ConsoleCommissioningLifecycle(),
) : AutoCloseable {
    private val lock = Any()
    /** Orders authority events without holding [lock] across transport callbacks. */
    private val commissioningEventLock = Any()
    /**
     * Linearizes calls into the executor. No code may acquire this lock while holding [lock].
     * Executor entrypoints are required to return promptly; callbacks are delivered after this
     * gate is released, including when an executor invokes them synchronously.
     */
    private val actuationDispatchLock = Any()
    private val protocolIdPattern =
        Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,${ConsoleProtocolModule.MAX_IDENTIFIER_LENGTH - 1}}$")
    private val saturationGate = CommandSaturationGate(config.controlEnvelope)
    private val sessions = mutableMapOf<String, ConsoleSession>()
    private val pendingSessions = mutableSetOf<String>()
    private val commands = linkedMapOf<String, CommandRecord>()
    private val shutdownNeutralCompleted = CountDownLatch(1)
    /**
     * Terminal, process-lifetime fence for new actuation. Unlike readiness, this gate can never be
     * reopened by a late runtime-state listener callback during shutdown.
     */
    private val actuationAdmissionClosed = AtomicBoolean(false)

    private var activeLease: ActiveLease? = null
    private var pendingLease: PendingLeaseReservation? = null
    private var nextLeaseEpoch = 0L
    private var nextControlEpoch = 0L
    private var nextNeutralToken = 0L
    private var neutralBarrierToken: Long? = null
    private var activeNeutralPlan: NeutralPlan? = null
    private var neutralInvocationToken: Long? = null
    private var neutralCompletedToken: Long? = null
    private var neutralFault: String? = null
    /** Last neutral authority which still lacks successful executor + durable audit evidence. */
    private var unresolvedNeutralContext: UnresolvedNeutralContext? = null
    private var auditFault = false
    private var activeDiscreteCommandId: String? = null
    private var actuationReadiness = initialActuationReadiness
    private var readinessEpoch = 0L
    /** Shared adapter/connection/profile truth always comes from [actuationReadiness]. */
    private var hardwareAdapterActuationReady =
        initialActuationObservation.adapterActuationReady &&
            initialActuationObservation.adapter == initialActuationReadiness.adapter &&
            initialActuationObservation.aircraftConnection == initialActuationReadiness.aircraftConnection &&
            initialActuationObservation.operatingProfile == initialActuationReadiness.operatingProfile
    private var observationEpoch = 0L
    /** A committed generation stays ineffective until its deadline task is durably attached. */
    private var commissioningActivationPending = false
    /** Capture-order revision for personalized authority observations; never used for admission. */
    private var commissioningStateRevision = 0L
    /** Exact Core session identity reserved by the currently committed generation. */
    private var commissioningOwnerSession: ConsoleSession? = null
    /** Last terminal projection is retained only for that exact still-live session identity. */
    private var lastCommissioningTerminal: CommissioningTerminalProjection? = null
    /** Blocks all lease authority until every overlapping terminal evidence owner has finished. */
    private val commissioningTransitionTokens = mutableSetOf<Long>()
    /** Fences a start reservation against a concurrent revoke/start transition. */
    private var commissioningAuthorityEpoch = 0L
    /** Required audit failed while another transition owned the safety linearization point. */
    private var auditTerminalizationDeferred = false
    private var commissioningDeadlineTask: ConsoleScheduledTask? = null
    private var commissioningScheduleEpoch = 0L
    private var shutdownNeutralToken: Long? = null
    private var shutdownNeutralSucceeded = false
    private var shutdownNeutralFinished = false
    private var shutdownEvidenceFinished = false
    private var pendingShutdownEvidenceWork: (() -> Unit)? = null
    private var shutdownRetryAttempted = false
    private var closed = false

    /**
     * Closes admission for every future lease or actuation attempt. This is deliberately limited
     * to one atomic store so a lifecycle stop callback can always invoke it without waiting for a
     * state lock, audit persistence, the executor, or an in-flight dispatch.
     */
    fun closeActuationAdmission() {
        actuationAdmissionClosed.set(true)
    }

    fun openSession(
        sessionId: String,
        clientInstanceId: String? = null,
    ): SessionOpened {
        requireValidId(sessionId, "sessionId")
        require(clientInstanceId == null || clientInstanceId.isNotBlank()) {
            "clientInstanceId must be null or non-blank"
        }
        synchronized(lock) {
            check(!closed) { "console core is closed" }
            check(sessionId !in sessions && pendingSessions.add(sessionId)) {
                "sessionId is already open"
            }
        }
        val session = ConsoleSession(sessionId, clientInstanceId)
        val audit = audit(ConsoleAuditKind.SESSION_OPENED, sessionId, outcome = "reservation_recorded")
        if (!recordRequired(audit)) {
            synchronized(lock) { pendingSessions.remove(sessionId) }
            throw ConsoleAuditUnavailableException("session audit unavailable")
        }
        synchronized(lock) {
            pendingSessions.remove(sessionId)
            check(!closed) { "console core closed while session was opening" }
            sessions[sessionId] = session
        }
        return SessionOpened(session)
    }

    /**
     * Host-only entrypoint. No console protocol, HTTP or WebSocket handler maps to this method.
     * Required start evidence is durable before the reserved generation can become effective.
     */
    fun startHardwareCommissioning(
        operatorSessionId: String,
        allowedIntents: Set<ConsoleActuationIntent>,
        ttlMillis: Long,
    ): ConsoleCommissioningStartResult {
        if (actuationAdmissionClosed.get()) return refusedCommissioningStart(ConsoleCommissioningStartDecision.SERVER_CLOSED)
        expireDueWork()
        val snapshot = synchronized(lock) {
            when {
                closed || actuationAdmissionClosed.get() -> null
                auditFault -> null
                operatorSessionId !in sessions ->
                    CommissioningStartSnapshot(
                        currentActuationObservationLocked(),
                        readinessEpoch,
                        observationEpoch,
                        commissioningAuthorityEpoch,
                        operatorSession = null,
                    )
                commissioningTransitionTokens.isNotEmpty() || activeLease != null || activeDiscreteCommandId != null ||
                    neutralBarrierToken != null || neutralFault != null -> null
                else ->
                    CommissioningStartSnapshot(
                        currentActuationObservationLocked(),
                        readinessEpoch,
                        observationEpoch,
                        commissioningAuthorityEpoch,
                        operatorSession = sessions[operatorSessionId],
                    )
            }
        } ?: return refusedCommissioningStart(
            synchronized(lock) {
                if (auditFault) ConsoleCommissioningStartDecision.AUDIT_UNAVAILABLE
                else if (closed || actuationAdmissionClosed.get()) ConsoleCommissioningStartDecision.SERVER_CLOSED
                else ConsoleCommissioningStartDecision.STATE_CHANGED
            },
        )

        // UUID generation belongs to the lifecycle, but reserveStart is deliberately outside the
        // Core lock so no injected/test factory can become a callback-under-lock hazard.
        val commissioningId = commissioningLifecycle.generateCommissioningId()
        val reservationResult =
            commissioningLifecycle.reserveStart(
                commissioningId,
                snapshot.observation,
                operatorSessionId,
                snapshot.operatorSession != null,
                allowedIntents,
                ttlMillis,
            )
        val reservation = reservationResult.reservation
            ?: return refusedCommissioningStart(checkNotNull(reservationResult.refusal))

        // Linearize a cross-profile authority fence before the required STARTED audit. Nothing
        // (including mock authority) may overtake the reservation while its causal evidence is
        // blocked. The token is handed off to commissioningActivationPending only after commit.
        val startTransitionToken = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val stillCurrent =
                    !closed && !actuationAdmissionClosed.get() && !auditFault &&
                        sessions[operatorSessionId] === snapshot.operatorSession &&
                        commissioningTransitionTokens.isEmpty() &&
                        commissioningAuthorityEpoch == snapshot.commissioningAuthorityEpoch &&
                        activeLease == null && activeDiscreteCommandId == null &&
                        neutralBarrierToken == null && neutralFault == null &&
                        readinessEpoch == snapshot.readinessEpoch &&
                        observationEpoch == snapshot.observationEpoch &&
                        commissioningPublicLockIsSafeLocked() &&
                        currentActuationObservationLocked().isEligibleForG520Commissioning()
                if (stillCurrent) beginCommissioningTransitionLocked() else null
            }
        }
        if (startTransitionToken == null) {
            commissioningLifecycle.abort(reservation)
            return refusedCommissioningStart(
                synchronized(lock) {
                    when {
                        auditFault -> ConsoleCommissioningStartDecision.AUDIT_UNAVAILABLE
                        closed || actuationAdmissionClosed.get() ->
                            ConsoleCommissioningStartDecision.SERVER_CLOSED
                        else -> ConsoleCommissioningStartDecision.STATE_CHANGED
                    }
                },
            )
        }

        val startEvidence =
            audit(
                ConsoleAuditKind.COMMISSIONING_STARTED,
                sessionId = operatorSessionId,
                subjectId = reservation.commissioningId,
                outcome = "reservation_recorded",
                reason = "hardware_commissioning",
                detail = commissioningAuditDetail(reservation),
            )
        if (!recordRequired(startEvidence)) {
            try {
                commissioningLifecycle.abort(reservation)
            } finally {
                completeCommissioningTransition(startTransitionToken)
            }
            return refusedCommissioningStart(ConsoleCommissioningStartDecision.AUDIT_UNAVAILABLE)
        }

        val commit = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val stillCurrent =
                    !closed && !actuationAdmissionClosed.get() && !auditFault &&
                        sessions[operatorSessionId] === snapshot.operatorSession &&
                        commissioningTransitionTokens.size == 1 &&
                        startTransitionToken in commissioningTransitionTokens &&
                        commissioningAuthorityEpoch == startTransitionToken &&
                        activeLease == null &&
                        activeDiscreteCommandId == null && neutralBarrierToken == null &&
                        neutralFault == null && readinessEpoch == snapshot.readinessEpoch &&
                        observationEpoch == snapshot.observationEpoch &&
                        commissioningPublicLockIsSafeLocked() &&
                        currentActuationObservationLocked().isEligibleForG520Commissioning()
                if (!stillCurrent) {
                    null
                } else {
                    commissioningLifecycle.commit(reservation, monotonicClock.nowNanos())
                        .takeIf { it.decision == ConsoleCommissioningCommitDecision.COMMITTED }
                        ?.also {
                            commissioningActivationPending = true
                            commissioningOwnerSession = checkNotNull(snapshot.operatorSession)
                            lastCommissioningTerminal = null
                        }
                }
            }
        }
        if (commit == null) {
            // Keep the reservation occupied until its required abort evidence finishes so a
            // replacement commissioning or mock authority cannot overtake the causal record.
            val abortRecorded =
                try {
                    recordRequired(
                        audit(
                            ConsoleAuditKind.COMMISSIONING_TERMINATED,
                            sessionId = operatorSessionId,
                            subjectId = reservation.commissioningId,
                            outcome = "start_aborted",
                            reason = "state_changed",
                            detail = commissioningAuditDetail(reservation),
                        ),
                    )
                } finally {
                    commissioningLifecycle.abort(reservation)
                    completeCommissioningTransition(startTransitionToken)
                }
            return refusedCommissioningStart(
                if (abortRecorded) ConsoleCommissioningStartDecision.STATE_CHANGED
                else ConsoleCommissioningStartDecision.AUDIT_UNAVAILABLE,
            )
        }
        completeCommissioningTransition(startTransitionToken)
        val session = checkNotNull(commit.session)
        when (scheduleCommissioningDeadline(session)) {
            CommissioningDeadlineAttachDecision.ATTACHED ->
                captureActiveCommissioningAuthority(session)?.let(::emitCommissioningAuthority)
            CommissioningDeadlineAttachDecision.SCHEDULER_UNAVAILABLE -> {
                terminateCommissioning(
                    expectedSession = session,
                    reason = ConsoleCommissioningTerminationReason.DEADLINE_UNAVAILABLE,
                )
                return refusedCommissioningStart(ConsoleCommissioningStartDecision.DEADLINE_UNAVAILABLE)
            }
            CommissioningDeadlineAttachDecision.EXPIRED_CURRENT -> {
                terminateCommissioning(
                    expectedSession = session,
                    reason = ConsoleCommissioningTerminationReason.TTL_EXPIRED,
                )
                return refusedCommissioningStart(ConsoleCommissioningStartDecision.STATE_CHANGED)
            }
            CommissioningDeadlineAttachDecision.STATE_CHANGED_OR_REVOKED -> {
                terminateCommissioning(
                    expectedSession = session,
                    reason = ConsoleCommissioningTerminationReason.RUNTIME_STATE_CHANGED,
                )
                return refusedCommissioningStart(ConsoleCommissioningStartDecision.STATE_CHANGED)
            }
        }
        return ConsoleCommissioningStartResult(ConsoleCommissioningStartDecision.STARTED, session)
    }

    /** Host-only explicit terminal revoke. A stale view cannot affect a replacement generation. */
    fun revokeHardwareCommissioning(
        expectedSession: ConsoleCommissioningSessionView,
    ): ConsoleCommissioningRevokeDecision {
        expireDueWork()
        val prepared =
            prepareCommissioningTermination(
                expectedSession,
                ConsoleCommissioningTerminationReason.HOST_REVOKED,
            )
        return when {
            prepared.transition != null -> {
                if (finishCommissioningTermination(checkNotNull(prepared.transition))) {
                    ConsoleCommissioningRevokeDecision.REVOKED
                } else {
                    ConsoleCommissioningRevokeDecision.REVOKED_AUDIT_UNAVAILABLE
                }
            }
            prepared.stale -> ConsoleCommissioningRevokeDecision.STALE_SESSION
            else -> ConsoleCommissioningRevokeDecision.NO_ACTIVE_SESSION
        }
    }

    /**
     * Captures a personalized, read-only authority observation for one transport-owned session.
     * Every capture receives a unique monotonic revision so a delayed event cannot overwrite a
     * newer hello snapshot. This method never grants, renews or expands authority.
     */
    fun currentCommissioningAuthorityState(sessionId: String): ConsoleCommissioningAuthorityState {
        requireValidId(sessionId, "sessionId")
        expireDueWork()
        return synchronized(lock) {
            captureCommissioningAuthorityLocked(sessionId)
        }
    }

    /**
     * Host-only adapter-ready observation. Adapter/connection/profile must exactly match the
     * browser-visible runtime snapshot; only the additional ready bit is stored independently.
     */
    fun updateActuationObservation(next: ConsoleActuationObservation): Boolean {
        var transition: CommissioningTermination? = null
        val accepted = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    val sharedTruthMatches =
                        next.adapter == actuationReadiness.adapter &&
                            next.aircraftConnection == actuationReadiness.aircraftConnection &&
                            next.operatingProfile == actuationReadiness.operatingProfile
                    val nextReady = sharedTruthMatches && next.adapterActuationReady
                    if (nextReady != hardwareAdapterActuationReady || !sharedTruthMatches) {
                        hardwareAdapterActuationReady = nextReady
                        observationEpoch = nextCounter(observationEpoch, "actuation observation epoch")
                        if (!nextReady) {
                            transition =
                                prepareCommissioningTerminationLocked(
                                    expectedSession = null,
                                    reason = ConsoleCommissioningTerminationReason.OBSERVATION_LOST,
                                ).transition
                        }
                    }
                    sharedTruthMatches
                }
            }
        }
        transition?.let(::finishCommissioningTermination)
        return accepted
    }

    /**
     * Replace host-owned runtime truth. Browser messages have no path to this method. Every actual
     * context change advances an internal epoch, revokes an active lease and enters neutral before
     * the method returns. That epoch also fences commands which are waiting on an asynchronous
     * neutral callback, including a locked -> ready ABA transition.
     */
    fun updateActuationReadiness(next: ConsoleActuationReadinessSnapshot): Boolean {
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) state@{
                if (closed || next == actuationReadiness) {
                    null
                } else {
                    val previous = actuationReadiness
                    actuationReadiness = next
                    readinessEpoch = nextCounter(readinessEpoch, "actuation readiness epoch")
                    if (hardwareAdapterActuationReady) {
                        hardwareAdapterActuationReady = false
                        observationEpoch = nextCounter(observationEpoch, "actuation observation epoch")
                    }
                    pendingLease = null

                    val commissioningTermination =
                        prepareCommissioningTerminationLocked(
                            expectedSession = null,
                            reason = ConsoleCommissioningTerminationReason.RUNTIME_STATE_CHANGED,
                        ).transition

                    if (commissioningTermination != null) {
                        return@state ReadinessTransition(
                            previous = previous,
                            next = next,
                            revokedLease = commissioningTermination.revokedLease,
                            neutral = commissioningTermination.neutral,
                            commandTermination = commissioningTermination.commandTermination,
                            commissioningTermination = commissioningTermination,
                        )
                    }

                    val actionRecord =
                        activeDiscreteCommandId?.let(commands::get)
                            ?.takeIf {
                                it.state == CommandRecordState.EXECUTING ||
                                    it.state == CommandRecordState.COMPLETING
                            }
                    val termination =
                        actionRecord?.takeIf {
                            it.state == CommandRecordState.EXECUTING && it.executionInvoked
                        }?.let {
                            terminateInvokedActionLocked(
                                it,
                                ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
                                "actuation_readiness_lost",
                            )
                        }
                    val revokedLease =
                        termination?.revokedLease ?: activeLease?.also { activeLease = null }
                    val actionLease = revokedLease ?: actionRecord?.actionLease
                    val neutral =
                        termination?.neutral ?: actionLease?.let {
                            prepareNeutralLocked(
                                it,
                                ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
                                forceBarrier = activeNeutralPlan == null,
                            )
                        }
                    ReadinessTransition(previous, next, revokedLease, neutral, termination)
                }
            }
        } ?: return synchronized(lock) { !closed }

        transition.commissioningTermination?.let { commissioning ->
            val evidenceToken = synchronized(lock) { beginCommissioningTransitionLocked() }
            return try {
                val terminalRecorded = finishCommissioningTermination(commissioning)
                val readinessRecorded =
                    recordBestEffort(
                        audit(
                            ConsoleAuditKind.ACTUATION_READINESS_CHANGED,
                            sessionId = commissioning.session.operatorSessionId,
                            leaseId = commissioning.revokedLease?.leaseId,
                            outcome = "runtime_context_changed",
                            reason = transition.next.summaryCode(),
                            detail = "previous=${transition.previous.summaryCode()}",
                        ),
                    )
                terminalRecorded && readinessRecorded
            } finally {
                completeCommissioningTransition(evidenceToken)
            }
        }

        val afterBarrierInvoked = {
            transition.revokedLease?.let(::cancelLeaseTasks)
            cancelBestEffort(transition.commandTermination?.deadlineTask)
            transition.commandTermination?.actionLease?.let(::cancelLeaseTasks)
            val affectedLease =
                transition.revokedLease ?: transition.commandTermination?.actionLease
            val recorded =
                recordBestEffort(
                    audit(
                        ConsoleAuditKind.ACTUATION_READINESS_CHANGED,
                        sessionId = affectedLease?.holderSessionId,
                        leaseId = affectedLease?.leaseId,
                        outcome = "runtime_context_changed",
                        reason = transition.next.summaryCode(),
                        detail = "previous=${transition.previous.summaryCode()}",
                    ),
                )
            transition.revokedLease?.let { lease ->
                if (synchronized(lock) { !closed }) {
                    emit(
                        lease.holderSessionId,
                        ConsoleCoreEvent.LeaseChanged(
                            ConsoleLeaseState(
                                ConsoleLeaseStatus.RELEASED,
                                lease.leaseId,
                                null,
                                null,
                                null,
                            ),
                        ),
                    )
                }
            }
            recorded
        }
        return transition.neutral?.let { plan ->
            var recorded = false
            executeNeutral(plan) { recorded = afterBarrierInvoked() }
            recorded
        } ?: afterBarrierInvoked()
    }

    fun acquireLease(sessionId: String, requestedTtlMillis: Long): ConsoleLeaseState {
        if (actuationAdmissionClosed.get()) {
            return refusedLease(sessionId, ACTUATION_ADMISSION_CLOSED_REASON)
        }
        expireDueWork()
        var selectedReservation: PendingLeaseReservation? = null
        val denial = synchronized(lock) {
            when {
                closed -> "server_closed"
                actuationAdmissionClosed.get() -> ACTUATION_ADMISSION_CLOSED_REASON
                sessionId !in sessions -> "unknown_session"
                requestedTtlMillis !in config.minimumLeaseTtlMillis..config.maximumLeaseTtlMillis -> "invalid_lease_ttl"
                auditFault -> "audit_unavailable"
                neutralFault != null -> "actuation_locked"
                activeDiscreteCommandId != null -> "discrete_action_in_progress"
                neutralBarrierToken != null -> "neutral_in_progress"
                activeLease != null -> "lease_held"
                pendingLease != null -> "lease_pending"
                else -> {
                    val authority = newLeaseAuthorityLocked(sessionId)
                    if (authority == null) {
                        "actuation_not_ready"
                    } else {
                        val reservation =
                            PendingLeaseReservation(
                                session = checkNotNull(sessions[sessionId]),
                                authority = authority,
                            )
                        selectedReservation = reservation
                        pendingLease = reservation
                        null
                    }
                }
            }
        }
        if (denial != null) return refusedLease(sessionId, denial)

        val leaseId =
            try {
                leaseIdFactory().also { requireValidId(it, "leaseId") }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (pendingLease === selectedReservation) pendingLease = null
                }
                return refusedLease(sessionId, "lease_id_unavailable")
            }
        val reservation = checkNotNull(selectedReservation)
        val candidate = synchronized(lock) {
            nextLeaseEpoch = nextCounter(nextLeaseEpoch, "lease epoch")
            nextControlEpoch = nextCounter(nextControlEpoch, "control epoch")
            ActiveLease(
                leaseId = leaseId,
                holderSessionId = sessionId,
                commissioningSession =
                    (reservation.authority as? LeaseAuthority.Commissioning)?.session,
                leaseEpoch = nextLeaseEpoch,
                deadlineNanos = 0L,
                controlEpoch = nextControlEpoch,
            )
        }
        val admittedAudit =
            audit(
                kind = ConsoleAuditKind.LEASE_ACQUIRED,
                sessionId = sessionId,
                leaseId = leaseId,
                outcome = "reservation_recorded",
            )
        if (!recordRequired(admittedAudit)) {
            synchronized(lock) {
                if (pendingLease === reservation) pendingLease = null
            }
            return refusedLease(sessionId, "audit_unavailable")
        }

        val committed = synchronized(lock) {
            if (closed || actuationAdmissionClosed.get() ||
                sessions[sessionId] !== reservation.session || activeLease != null ||
                pendingLease !== reservation || !leaseAuthorityAllowsLocked(candidate)
            ) {
                if (pendingLease === reservation) pendingLease = null
                false
            } else {
                pendingLease = null
                candidate.deadlineNanos =
                    deadlineAfter(monotonicClock.nowNanos(), requestedTtlMillis)
                candidate.deadlineAttachPending = true
                activeLease = candidate
                true
            }
        }
        if (!committed) return refusedLease(sessionId, "state_changed")
        if (!scheduleLeaseDeadline(candidate)) return refusedLease(sessionId, "lease_deadline_unavailable")
        val held = heldLease(candidate, monotonicClock.nowNanos())
        emit(sessionId, ConsoleCoreEvent.LeaseChanged(held))
        return held
    }

    fun renewLease(
        sessionId: String,
        leaseId: String,
        requestedTtlMillis: Long,
    ): ConsoleLeaseState {
        if (actuationAdmissionClosed.get()) {
            return refusedLease(sessionId, ACTUATION_ADMISSION_CLOSED_REASON)
        }
        expireDueWork()
        val selected = synchronized(lock) {
            val lease = activeLease
            when {
                closed -> null to "server_closed"
                actuationAdmissionClosed.get() -> null to ACTUATION_ADMISSION_CLOSED_REASON
                sessionId !in sessions -> null to "unknown_session"
                requestedTtlMillis !in config.minimumLeaseTtlMillis..config.maximumLeaseTtlMillis -> null to "invalid_lease_ttl"
                auditFault -> null to "audit_unavailable"
                neutralFault != null -> null to "actuation_locked"
                lease == null || lease.leaseId != leaseId || lease.holderSessionId != sessionId -> null to "not_lease_holder"
                !leaseAuthorityAllowsLocked(lease) -> null to "actuation_not_ready"
                lease.mutationPending -> null to "lease_busy"
                else -> {
                    lease.mutationPending = true
                    lease to null
                }
            }
        }
        val lease = selected.first ?: return refusedLease(sessionId, checkNotNull(selected.second))
        val renewedAudit =
            audit(
                kind = ConsoleAuditKind.LEASE_RENEWED,
                sessionId = sessionId,
                leaseId = leaseId,
                outcome = "renewal_reserved",
            )
        if (!recordRequired(renewedAudit)) {
            synchronized(lock) {
                activeLease?.takeIf { it === lease }?.mutationPending = false
            }
            return refusedLease(sessionId, "audit_unavailable")
        }

        var oldTask: ConsoleScheduledTask? = null
        var expiredTransition: LeaseTransition? = null
        var commandTermination: CommandAuthorityTermination? = null
        val committed = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val now = monotonicClock.nowNanos()
                when {
                    closed || actuationAdmissionClosed.get() || activeLease !== lease || sessionId !in sessions ||
                        !leaseAuthorityAllowsLocked(lease) -> {
                        lease.mutationPending = false
                        false
                    }
                    now >= lease.deadlineNanos -> {
                        lease.mutationPending = false
                        val invokedRecord =
                            activeDiscreteCommandId?.let(commands::get)
                                ?.takeIf {
                                    it.state == CommandRecordState.EXECUTING &&
                                        it.executionInvoked && it.actionLease === lease
                                }
                        commandTermination =
                            invokedRecord?.let {
                                terminateInvokedActionLocked(
                                    it,
                                    ConsoleSafetyTrigger.LEASE_EXPIRED,
                                    "lease_ttl_expired",
                                )
                            }
                        expiredTransition =
                            LeaseTransition(
                                lease,
                                commandTermination?.neutral
                                    ?: run {
                                        activeLease = null
                                        prepareNeutralLocked(lease, ConsoleSafetyTrigger.LEASE_EXPIRED)
                                    },
                            )
                        false
                    }
                    else -> {
                        lease.deadlineNanos = deadlineAfter(now, requestedTtlMillis)
                        lease.leaseEpoch = nextCounter(lease.leaseEpoch, "lease epoch")
                        lease.mutationPending = false
                        lease.deadlineAttachPending = true
                        oldTask = lease.leaseTask
                        lease.leaseTask = null
                        true
                    }
                }
            }
        }
        cancelBestEffort(oldTask)
        if (!committed) {
            cancelBestEffort(commandTermination?.deadlineTask)
            expiredTransition?.let(::expireLease)
            return refusedLease(
                sessionId,
                if (expiredTransition == null) "state_changed" else "lease_ttl_expired",
            )
        }
        if (!scheduleLeaseDeadline(lease)) return refusedLease(sessionId, "lease_deadline_unavailable")
        val held = heldLease(lease, monotonicClock.nowNanos())
        emit(sessionId, ConsoleCoreEvent.LeaseChanged(held))
        return held
    }

    fun releaseLease(sessionId: String, leaseId: String): ConsoleLeaseState {
        expireDueWork()
        var commandTermination: CommandAuthorityTermination? = null
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val lease = activeLease
                if (closed || sessionId !in sessions || lease == null ||
                    lease.leaseId != leaseId || lease.holderSessionId != sessionId
                ) {
                    null
                } else {
                    val invokedRecord =
                        activeDiscreteCommandId?.let(commands::get)
                            ?.takeIf {
                                it.sessionId == sessionId &&
                                    it.state == CommandRecordState.EXECUTING &&
                                    it.executionInvoked && it.actionLease === lease
                            }
                    commandTermination =
                        invokedRecord?.let {
                            terminateInvokedActionLocked(
                                it,
                                ConsoleSafetyTrigger.CLIENT_REQUEST,
                                "authority_lost",
                            )
                        }
                    if (commandTermination != null) {
                        LeaseTransition(lease, commandTermination?.neutral)
                    } else {
                        activeLease = null
                        LeaseTransition(
                            lease = lease,
                            neutral = prepareNeutralLocked(lease, ConsoleSafetyTrigger.CLIENT_REQUEST),
                        )
                    }
                }
            }
        } ?: return refusedLease(sessionId, "not_lease_holder")

        val state =
            ConsoleLeaseState(
                ConsoleLeaseStatus.RELEASED,
                leaseId,
                holderSessionId = null,
                expiresInMillis = null,
                reason = null,
            )
        val afterBarrierInvoked: () -> Unit = {
            cancelLeaseTasks(transition.lease)
            cancelBestEffort(commandTermination?.deadlineTask)
            recordBestEffort(
                audit(
                    ConsoleAuditKind.LEASE_RELEASED,
                    sessionId,
                    leaseId,
                    outcome = "released",
                ),
            )
            if (synchronized(lock) { !closed }) emit(sessionId, ConsoleCoreEvent.LeaseChanged(state))
        }
        transition.neutral?.let { executeNeutral(it, afterBarrierInvoked) } ?: afterBarrierInvoked()
        return state
    }

    fun handleDiscreteCommand(
        sessionId: String,
        command: ConsoleDiscreteCommand,
    ): ConsoleCommandAck {
        expireDueWork()
        val receivedAt = monotonicClock.nowNanos()
        val deadline = deadlineAfter(receivedAt, command.ttlMillis.coerceAtLeast(0L))
        val initialRule =
            synchronized(lock) {
                commandRuleLocked(sessionId, command.leaseId, command.action, command.ttlMillis)
            }
        val decision = admission.admit(command, initialRule)

        // A receipt is safe to replay without a current lease: no new executor side effect occurs.
        val existing = synchronized(lock) {
            commands[command.commandId]?.takeIf {
                sessionId in sessions && it.sessionId == sessionId
            }
        }
        if (existing != null) {
            if (existing.intentDigestSha256 != decision.intentDigestSha256) {
                return refusedCommand(
                    sessionId,
                    command.commandId,
                    ConsoleAdmissionDecision.Rejected(
                        "command_id_conflict",
                        null,
                        decision.intentDigestSha256,
                    ),
                )
            }
            if (existing.state == CommandRecordState.AUDITING) {
                return refusedCommand(
                    sessionId,
                    command.commandId,
                    ConsoleAdmissionDecision.Rejected(
                        "command_in_progress",
                        null,
                        decision.intentDigestSha256,
                    ),
                )
            }
            return replayCommand(sessionId, existing)
        }
        if (decision is ConsoleAdmissionDecision.Rejected) {
            return refusedCommand(sessionId, command.commandId, decision)
        }
        decision as ConsoleAdmissionDecision.Admitted

        val reservation = synchronized(lock) {
            val currentRule =
                commandRuleLocked(sessionId, command.leaseId, command.action, command.ttlMillis)
            when {
                !currentRule.accepted -> CommandReservation.Refused(checkNotNull(currentRule.reason))
                monotonicClock.nowNanos() >= deadline -> CommandReservation.Refused("command_ttl_expired")
                else -> {
                    val existing = commands[command.commandId]
                    when {
                        existing == null -> {
                            if (commands.size >= config.maximumCommandRecords) {
                                evictOldestCompletedCommandLocked()
                            }
                            if (commands.size >= config.maximumCommandRecords) {
                                return@synchronized CommandReservation.Refused("command_inventory_full")
                            }
                            val record =
                                CommandRecord(
                                    sessionId = sessionId,
                                    session = checkNotNull(sessions[sessionId]),
                                    command = command,
                                    intentDigestSha256 = decision.intentDigestSha256,
                                    authorityDecisionId = decision.authorityDecisionId,
                                    readinessEpoch = readinessEpoch,
                                    commissioningSession = checkNotNull(activeLease).commissioningSession,
                                    deadlineNanos = deadline,
                                )
                            commands[command.commandId] = record
                            CommandReservation.New(record)
                        }
                        existing.intentDigestSha256 != decision.intentDigestSha256 ->
                            CommandReservation.Refused("command_id_conflict")
                        existing.state == CommandRecordState.AUDITING ->
                            CommandReservation.Refused("command_in_progress")
                        else -> CommandReservation.Replay(existing)
                    }
                }
            }
        }
        when (reservation) {
            is CommandReservation.Refused ->
                return refusedCommand(
                    sessionId,
                    command.commandId,
                    ConsoleAdmissionDecision.Rejected(
                        reservation.reason,
                        null,
                        decision.intentDigestSha256,
                        decision.authorityDecisionId,
                    ),
                )
            is CommandReservation.Replay -> {
                return replayCommand(sessionId, reservation.record)
            }
            is CommandReservation.New -> Unit
        }

        val admittedAudit =
            audit(
                ConsoleAuditKind.COMMAND_ADMITTED,
                sessionId,
                command.leaseId,
                command.commandId,
                decision.intentDigestSha256,
                "admission_passed",
                authorityDecisionId = decision.authorityDecisionId,
            )
        if (!recordRequired(admittedAudit)) {
            synchronized(lock) { commands.remove(command.commandId, (reservation as CommandReservation.New).record) }
            return refusedCommand(
                sessionId,
                command.commandId,
                ConsoleAdmissionDecision.Rejected(
                    "audit_unavailable",
                    null,
                    decision.intentDigestSha256,
                    decision.authorityDecisionId,
                ),
            )
        }

        expireDueWork()
        val admitted =
            AdmittedDiscreteCommand(
                sessionId = sessionId,
                command = command,
                authorityDecisionId = decision.authorityDecisionId,
                intentDigestSha256 = decision.intentDigestSha256,
                admittedAtNanos = receivedAt,
                expiresAtNanos = deadline,
            )
        val ack =
            ConsoleCommandAck(
                command.commandId,
                ConsoleCommandDecision.ACCEPTED,
                null,
                decision.intentDigestSha256,
            )
        var discreteSafetyTransition: DiscreteSafetyTransition? = null
        val committed = synchronized(lock) {
            val record = commands[command.commandId]
            val rule =
                commandRuleLocked(sessionId, command.leaseId, command.action, command.ttlMillis)
            if (record !== (reservation as CommandReservation.New).record ||
                record.state != CommandRecordState.AUDITING ||
                sessions[sessionId] !== record.session || !rule.accepted ||
                !commandAuthorityAllowsLocked(record, command.action.actuationIntent) ||
                monotonicClock.nowNanos() >= deadline
            ) {
                commands.remove(command.commandId, record)
                false
            } else {
                record.state = CommandRecordState.EXECUTING
                record.ack = ack
                activeDiscreteCommandId = command.commandId
                val lease = checkNotNull(activeLease)
                val releasesLease = command.action.requiresControlRelease
                if (releasesLease) {
                        activeLease = null
                }
                val neutral =
                    checkNotNull(
                        prepareNeutralLocked(
                            lease,
                            ConsoleSafetyTrigger.CLIENT_REQUEST,
                            forceBarrier = true,
                            holdBarrierOnSuccess = true,
                        ),
                    ).copy(
                        ownerCommandId = command.commandId,
                        completion = { onDiscreteNeutralCompleted(admitted, it) },
                    ).also { activeNeutralPlan = it }
                record.barrierToken = neutral.token
                record.actionLease = lease
                discreteSafetyTransition = DiscreteSafetyTransition(lease, neutral, releasesLease)
                true
            }
        }
        if (!committed) {
            return refusedCommand(
                sessionId,
                command.commandId,
                ConsoleAdmissionDecision.Rejected(
                    "state_changed",
                    null,
                    decision.intentDigestSha256,
                    decision.authorityDecisionId,
                ),
            )
        }

        val transition = checkNotNull(discreteSafetyTransition)
        val commandRecord = (reservation as CommandReservation.New).record
        val afterBarrierInvoked = {
            if (transition.releasesLease) {
                cancelLeaseTasks(transition.lease)
            } else {
                cancelControlTask(transition.lease)
            }
            if (transition.releasesLease) {
                recordBestEffort(
                    audit(
                        ConsoleAuditKind.LEASE_RELEASED,
                        sessionId,
                        transition.lease.leaseId,
                        command.commandId,
                        outcome = "released_for_${command.action.name.lowercase()}",
                    ),
                )
                if (synchronized(lock) { !closed }) {
                    emit(
                        sessionId,
                        ConsoleCoreEvent.LeaseChanged(
                            ConsoleLeaseState(
                                ConsoleLeaseStatus.RELEASED,
                                transition.lease.leaseId,
                                null,
                                null,
                                null,
                            ),
                        ),
                    )
                }
            }
            if (synchronized(lock) { !closed }) {
                emit(sessionId, ConsoleCoreEvent.CommandAcknowledged(ack))
            }
        }
        if (!scheduleCommandDeadline(commandRecord)) {
            val expired = monotonicClock.nowNanos() >= commandRecord.deadlineNanos
            terminateCommandForDeadline(
                commandRecord,
                reason = if (expired) "command_ttl_expired" else "command_deadline_unavailable",
                timedOut = expired,
                latchUnknownActuation = false,
                afterBarrierInvoked = afterBarrierInvoked,
            )
            return ack
        }
        val pendingNeutral = synchronized(lock) {
            if (commands[command.commandId] === commandRecord &&
                commandRecord.state == CommandRecordState.EXECUTING
            ) {
                commandRecord.executionArmed = true
                commandRecord.neutralResult?.also { commandRecord.neutralResult = null }
            } else {
                null
            }
        }
        if (pendingNeutral == null) {
            executeNeutral(transition.neutral, afterBarrierInvoked)
        } else {
            afterBarrierInvoked()
            dispatchDiscreteAfterNeutral(admitted, pendingNeutral)
        }
        return ack
    }

    private fun onDiscreteNeutralCompleted(
        command: AdmittedDiscreteCommand,
        result: ConsoleExecutionResult,
    ) {
        val dispatchNow = synchronized(lock) {
            val record = commands[command.command.commandId]
            if (record == null || record.intentDigestSha256 != command.intentDigestSha256 ||
                record.state != CommandRecordState.EXECUTING
            ) {
                false
            } else if (!record.executionArmed) {
                record.neutralResult = result
                false
            } else {
                true
            }
        }
        if (dispatchNow) dispatchDiscreteAfterNeutral(command, result)
    }

    private fun dispatchDiscreteAfterNeutral(
        command: AdmittedDiscreteCommand,
        neutralResult: ConsoleExecutionResult,
    ) {
        if (neutralResult.succeeded) {
            when (val dispatch = executeDiscreteIfCurrent(command)) {
                DiscreteDispatchAttempt.INVOKED,
                DiscreteDispatchAttempt.NO_LONGER_CURRENT,
                -> Unit
                is DiscreteDispatchAttempt.Refused ->
                    completeDiscrete(
                        command,
                        ConsoleExecutionResult(false, dispatch.reason, null),
                    )
            }
        } else {
            completeDiscrete(
                command,
                ConsoleExecutionResult(false, neutralResult.reason ?: "neutral_failed", "neutralization failed"),
            )
        }
    }

    private fun executeDiscreteIfCurrent(command: AdmittedDiscreteCommand): DiscreteDispatchAttempt {
        val callback = DeferredCallback<ConsoleExecutionResult>()
        val attempt = synchronized(actuationDispatchLock) {
            val gate = synchronized(lock) {
                val record = commands[command.command.commandId]
                when {
                    record == null || record.intentDigestSha256 != command.intentDigestSha256 ||
                        record.state != CommandRecordState.EXECUTING ||
                        activeDiscreteCommandId != command.command.commandId ->
                        DiscreteDispatchAttempt.NO_LONGER_CURRENT
                    record.barrierToken != neutralBarrierToken ->
                        DiscreteDispatchAttempt.Refused("neutral_state_changed")
                    monotonicClock.nowNanos() >= command.expiresAtNanos ->
                        DiscreteDispatchAttempt.Refused("command_ttl_expired")
                    auditFault -> DiscreteDispatchAttempt.Refused("audit_unavailable")
                    neutralFault != null -> DiscreteDispatchAttempt.Refused("actuation_locked")
                    closed -> DiscreteDispatchAttempt.Refused("server_closed")
                    actuationAdmissionClosed.get() ->
                        DiscreteDispatchAttempt.Refused(ACTUATION_ADMISSION_CLOSED_REASON)
                    sessions[record.sessionId]?.let { it !== record.session } == true ->
                        DiscreteDispatchAttempt.Refused("authority_lost")
                    record.commissioningSession == null && record.readinessEpoch != readinessEpoch ->
                        DiscreteDispatchAttempt.Refused("actuation_readiness_changed")
                    !commandAuthorityAllowsLocked(record, command.command.action.actuationIntent) ->
                        DiscreteDispatchAttempt.Refused("actuation_not_ready")
                    command.command.action == ConsoleDiscreteAction.TAKEOFF &&
                        !takeoffAuthorityIsLiveLocked(record, command) ->
                        DiscreteDispatchAttempt.Refused("authority_lost")
                    else -> {
                        record.executionInvoked = true
                        DiscreteDispatchAttempt.INVOKED
                    }
                }
            }
            if (gate != DiscreteDispatchAttempt.INVOKED) {
                gate
            } else if (actuationAdmissionClosed.get()) {
                synchronized(lock) {
                    commands[command.command.commandId]
                        ?.takeIf {
                            it.intentDigestSha256 == command.intentDigestSha256 &&
                                it.state == CommandRecordState.EXECUTING
                        }?.executionInvoked = false
                }
                DiscreteDispatchAttempt.Refused(ACTUATION_ADMISSION_CLOSED_REASON)
            } else {
                try {
                    executor.executeDiscrete(command, callback::complete)
                } catch (_: Exception) {
                    callback.complete(ConsoleExecutionResult(false, "executor_failure", null))
                }
                DiscreteDispatchAttempt.INVOKED
            }
        }
        if (attempt != DiscreteDispatchAttempt.INVOKED) return attempt
        val immediate = callback.open { completeDiscrete(command, it) }
        immediate?.let { completeDiscrete(command, it) }
        return DiscreteDispatchAttempt.INVOKED
    }

    private fun takeoffAuthorityIsLiveLocked(
        record: CommandRecord,
        command: AdmittedDiscreteCommand,
    ): Boolean {
        val lease = record.actionLease ?: return false
        return command.sessionId in sessions && activeLease === lease &&
            lease.leaseId == command.command.leaseId &&
            lease.holderSessionId == command.sessionId &&
            monotonicClock.nowNanos() < lease.deadlineNanos
    }

    fun handleControlFrame(
        sessionId: String,
        frame: ConsoleControlFrame,
    ): ConsoleControlAck {
        expireDueWork()
        val receivedAt = monotonicClock.nowNanos()
        val initialRule = synchronized(lock) { controlRuleLocked(sessionId, frame) }
        val decision = admission.admit(frame, initialRule)
        if (decision is ConsoleAdmissionDecision.Rejected) {
            return refusedControl(
                sessionId,
                frame,
                decision.reason,
                decision.intentDigestSha256,
                decision.authorityDecisionId,
            )
        }
        decision as ConsoleAdmissionDecision.Admitted

        val reservation = synchronized(lock) {
            val lease = activeLease
            val rule = controlRuleLocked(sessionId, frame)
            when {
                !rule.accepted -> null to checkNotNull(rule.reason)
                lease == null -> null to "lease_unavailable"
                lease.pendingControlSequence != null -> null to "control_busy"
                else -> {
                    lease.pendingControlSequence = frame.inputSequence
                    lease to null
                }
            }
        }
        val lease = reservation.first
            ?: return refusedControl(
                sessionId,
                frame,
                checkNotNull(reservation.second),
                decision.intentDigestSha256,
                decision.authorityDecisionId,
            )

        val admittedAudit =
            audit(
                ConsoleAuditKind.CONTROL_ADMITTED,
                sessionId,
                frame.leaseId,
                frame.inputSequence.toString(),
                decision.intentDigestSha256,
                "admission_passed",
                authorityDecisionId = decision.authorityDecisionId,
            )
        if (!recordRequired(admittedAudit)) {
            synchronized(lock) {
                activeLease?.takeIf { it === lease && it.pendingControlSequence == frame.inputSequence }
                    ?.pendingControlSequence = null
            }
            return refusedControl(
                sessionId,
                frame,
                "audit_unavailable",
                decision.intentDigestSha256,
                decision.authorityDecisionId,
            )
        }

        expireDueWork()
        val effectiveTtl = min(frame.ttlMillis, config.deadManTimeoutMillis)
        val deadline = deadlineAfter(receivedAt, effectiveTtl)
        val executable = synchronized(lock) {
            val current = activeLease
            val rule = controlRuleLocked(sessionId, frame, ignorePending = true)
            if (current !== lease || current.pendingControlSequence != frame.inputSequence ||
                !rule.accepted || monotonicClock.nowNanos() >= deadline
            ) {
                if (current === lease) current.pendingControlSequence = null
                null
            } else {
                current.pendingControlSequence = null
                current.lastInputSequence = frame.inputSequence
                if (current.neutralizedControlEpoch == current.controlEpoch) {
                    nextControlEpoch = nextCounter(nextControlEpoch, "control epoch")
                    current.controlEpoch = nextControlEpoch
                    current.neutralizedControlEpoch = null
                }
                current.inputVersion = nextCounter(current.inputVersion, "control input version")
                current.controlDeadlineNanos = deadline
                val oldTask = current.controlTask
                current.controlTask = null
                val raw =
                    BodyFrameVelocityCommand(
                        forwardMps = frame.forward * config.controlEnvelope.maxForwardMps,
                        rightMps = frame.right * config.controlEnvelope.maxLateralMps,
                        upMps = frame.up * config.controlEnvelope.maxVerticalMps,
                        yawRateDegreesPerSecond = frame.yaw * config.controlEnvelope.maxYawRateDegreesPerSecond,
                    )
                CommittedControl(
                    lease = current,
                    oldTask = oldTask,
                    admitted =
                        AdmittedControlFrame(
                            sessionId,
                            frame,
                            saturationGate.clamp(raw),
                            decision.authorityDecisionId,
                            decision.intentDigestSha256,
                            receivedAt,
                            current.controlEpoch,
                        ),
                    inputVersion = current.inputVersion,
                    readinessEpoch = readinessEpoch,
                    commissioningSession = current.commissioningSession,
                )
            }
        } ?: return refusedControl(
            sessionId,
            frame,
            "state_changed",
            decision.intentDigestSha256,
            decision.authorityDecisionId,
        )

        cancelBestEffort(executable.oldTask)
        if (!scheduleControlDeadline(executable.lease, executable.inputVersion, deadline)) {
            return refusedControl(
                sessionId,
                frame,
                if (monotonicClock.nowNanos() >= deadline) {
                    "control_ttl_expired"
                } else {
                    "control_deadline_unavailable"
                },
                decision.intentDigestSha256,
                decision.authorityDecisionId,
            )
        }
        val callback = DeferredCallback<ConsoleExecutionResult>()
        var expiredLease: LeaseTransition? = null
        var expiredControl: NeutralPlan? = null
        var admissionClosedBeforeSubmit = false
        val submitted = synchronized(actuationDispatchLock) {
            val stillCurrent = synchronized(lock) {
                val now = monotonicClock.nowNanos()
                when {
                    activeLease !== executable.lease ||
                        executable.lease.inputVersion != executable.inputVersion ||
                        executable.lease.neutralizedControlEpoch == executable.admitted.controlEpoch -> false
                    now >= executable.lease.deadlineNanos -> {
                        activeLease = null
                        expiredLease =
                            LeaseTransition(
                                executable.lease,
                                prepareNeutralLocked(
                                    executable.lease,
                                    ConsoleSafetyTrigger.LEASE_EXPIRED,
                                ),
                            )
                        false
                    }
                    executable.lease.controlDeadlineNanos == null ||
                        now >= checkNotNull(executable.lease.controlDeadlineNanos) -> {
                        expiredControl =
                            prepareNeutralLocked(
                                executable.lease,
                                ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
                            )
                        false
                    }
                    !controlAuthorityAllowsLocked(executable) ||
                        neutralBarrierToken != null || activeDiscreteCommandId != null ||
                        auditFault || neutralFault != null || closed -> false
                    actuationAdmissionClosed.get() -> {
                        admissionClosedBeforeSubmit = true
                        false
                    }
                    else -> true
                }
            }
            if (!stillCurrent) {
                false
            } else if (actuationAdmissionClosed.get()) {
                admissionClosedBeforeSubmit = true
                false
            } else {
                try {
                    executor.submitControl(executable.admitted, callback::complete)
                } catch (_: Exception) {
                    callback.complete(ConsoleExecutionResult(false, "executor_failure", null))
                }
                true
            }
        }
        if (!submitted) {
            val reason =
                when {
                    expiredLease != null -> "lease_ttl_expired"
                    expiredControl != null -> "control_ttl_expired"
                    admissionClosedBeforeSubmit -> ACTUATION_ADMISSION_CLOSED_REASON
                    else -> "neutralized_before_submit"
                }
            expiredLease?.let(::expireLease)
            expiredControl?.let(::executeNeutral)
            return refusedControl(
                sessionId,
                frame,
                reason,
                decision.intentDigestSha256,
                decision.authorityDecisionId,
            )
        }
        val immediate = callback.open { completeControl(executable, it) }
        return immediate?.let { completeControl(executable, it) }
            ?: ConsoleControlAck(frame.leaseId, frame.inputSequence, ConsoleControlStatus.APPLIED, null)
    }

    fun handleControlNeutral(
        sessionId: String,
        request: ConsoleControlNeutral,
    ): ConsoleControlAck? {
        expireDueWork()
        var selectedLease: ActiveLease? = null
        var selectionReason: String? = null
        var oldTask: ConsoleScheduledTask? = null
        var plan: NeutralPlan? = null
        synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val lease = activeLease
                selectionReason =
                    when {
                        closed -> "server_closed"
                        sessionId !in sessions -> "unknown_session"
                        lease == null || lease.leaseId != request.leaseId || lease.holderSessionId != sessionId ->
                            "not_lease_holder"
                        neutralBarrierToken != null -> "neutral_in_progress"
                        lease.lastInputSequence != null &&
                            request.inputSequence <= checkNotNull(lease.lastInputSequence) -> "stale_sequence"
                        else -> {
                            selectedLease = lease
                            lease.lastInputSequence = request.inputSequence
                            lease.inputVersion = nextCounter(lease.inputVersion, "control input version")
                            oldTask = lease.controlTask
                            lease.controlTask = null
                            plan =
                                prepareNeutralLocked(
                                    lease,
                                    ConsoleSafetyTrigger.CLIENT_REQUEST,
                                    acknowledgeControl = true,
                                    clientRequestReason = request.reason,
                                )
                            null
                        }
                    }
                }
            }
        val lease = selectedLease
            ?: return refusedControl(
                sessionId,
                ConsoleControlFrame(request.leaseId, request.inputSequence, config.minimumControlTtlMillis, 0.0, 0.0, 0.0, 0.0),
                checkNotNull(selectionReason),
                null,
            )
        cancelBestEffort(oldTask)
        if (plan == null) {
            return refusedControl(
                sessionId,
                ConsoleControlFrame(request.leaseId, request.inputSequence, config.minimumControlTtlMillis, 0.0, 0.0, 0.0, 0.0),
                "neutral_state_changed",
                null,
            )
        }
        executeNeutral(checkNotNull(plan))
        return null
    }

    fun disconnect(sessionId: String) {
        var commissioningTermination: CommissioningTermination? = null
        var disconnectingSession: ConsoleSession? = null
        var disconnectNeutral: NeutralPlan? = null
        var commandTermination: CommandAuthorityTermination? = null
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) state@{
                val opened = sessions[sessionId] ?: return
                disconnectingSession = opened
                commissioningLifecycle.currentSession()
                    ?.takeIf { it.operatorSessionId == sessionId }
                    ?.let { current ->
                        commissioningTermination =
                            prepareCommissioningTerminationLocked(
                                expectedSession = current,
                                reason = ConsoleCommissioningTerminationReason.OPERATOR_DISCONNECTED,
                            ).transition
                    }
                if (commissioningTermination != null) return@state null
                val removed = checkNotNull(sessions.remove(sessionId))
                if (pendingLease?.session === removed) pendingLease = null
                if (lastCommissioningTerminal?.ownerSession === removed) {
                    lastCommissioningTerminal = null
                }
                val invokedRecord =
                    activeDiscreteCommandId?.let(commands::get)
                        ?.takeIf {
                            it.sessionId == sessionId &&
                                it.state == CommandRecordState.EXECUTING &&
                                it.executionInvoked &&
                                it.command.action == ConsoleDiscreteAction.TAKEOFF
                        }
                val continuingActionLease =
                    activeDiscreteCommandId?.let(commands::get)
                        ?.takeIf {
                            it.sessionId == sessionId &&
                                it.executionInvoked &&
                                (it.state == CommandRecordState.COMPLETING ||
                                    (it.state == CommandRecordState.EXECUTING &&
                                        it.command.action.requiresControlRelease))
                        }?.actionLease
                commandTermination =
                    invokedRecord?.let {
                        terminateInvokedActionLocked(
                            it,
                            ConsoleSafetyTrigger.CLIENT_DISCONNECT,
                            "authority_lost",
                        )
                    }
                if (commandTermination != null) {
                    disconnectNeutral = commandTermination?.neutral
                    commandTermination?.revokedLease?.let { LeaseTransition(it, disconnectNeutral) }
                } else {
                    val lease = activeLease?.takeIf { it.holderSessionId == removed.sessionId }
                    if (lease != null) activeLease = null
                    disconnectNeutral =
                        lease?.let { prepareNeutralLocked(it, ConsoleSafetyTrigger.CLIENT_DISCONNECT) }
                            ?: continuingActionLease?.let {
                                prepareNeutralLocked(it, ConsoleSafetyTrigger.CLIENT_DISCONNECT)
                            }
                            ?: activeNeutralPlan?.takeIf { it.sessionId == sessionId }
                    lease?.let { LeaseTransition(it, disconnectNeutral) }
                }
            }
        }
        commissioningTermination?.let { commissioning ->
            val evidenceToken = synchronized(lock) { beginCommissioningTransitionLocked() }
            try {
                try {
                    finishCommissioningTermination(commissioning)
                } finally {
                    synchronized(lock) {
                        val expected = checkNotNull(disconnectingSession)
                        if (sessions[sessionId] === expected) {
                            sessions.remove(sessionId)
                            if (pendingLease?.session === expected) pendingLease = null
                            if (lastCommissioningTerminal?.ownerSession === expected) {
                                lastCommissioningTerminal = null
                            }
                        }
                    }
                }
                recordBestEffort(
                    audit(
                        ConsoleAuditKind.SESSION_DISCONNECTED,
                        sessionId,
                        outcome = "disconnected",
                    ),
                )
            } finally {
                completeCommissioningTransition(evidenceToken)
            }
            return
        }
        val afterBarrierInvoked = {
            transition?.let { cancelLeaseTasks(it.lease) }
            cancelBestEffort(commandTermination?.deadlineTask)
            commandTermination?.actionLease?.let(::cancelLeaseTasks)
            recordBestEffort(audit(ConsoleAuditKind.SESSION_DISCONNECTED, sessionId, outcome = "disconnected"))
            if (transition != null) {
                recordBestEffort(
                    audit(
                        ConsoleAuditKind.LEASE_RELEASED,
                        sessionId,
                        transition.lease.leaseId,
                        outcome = "client_disconnect",
                    ),
                )
                if (synchronized(lock) { !closed }) {
                    emit(
                        sessionId,
                        ConsoleCoreEvent.LeaseChanged(
                            ConsoleLeaseState(
                                ConsoleLeaseStatus.RELEASED,
                                transition.lease.leaseId,
                                null,
                                null,
                                null,
                            ),
                        ),
                    )
                }
            }
        }
        disconnectNeutral?.let { executeNeutral(it, afterBarrierInvoked) } ?: afterBarrierInvoked()
    }

    fun currentLease(): ConsoleLeaseState? {
        expireDueWork()
        val now = monotonicClock.nowNanos()
        return synchronized(lock) {
            activeLease?.takeUnless { it.deadlineAttachPending }?.let { heldLease(it, now) }
        }
    }

    override fun close() {
        closeActuationAdmission()
        // The terminal admission fence above prevents a replacement generation while the
        // required terminal audit blocks. The helper revokes authority under dispatch -> state
        // lock and invokes fresh neutral before that audit.
        terminateCommissioning(
            reason = ConsoleCommissioningTerminationReason.SERVER_CLOSED,
        )
        var cancelledCommands: List<CommandRecord> = emptyList()
        var commandTasks: List<ConsoleScheduledTask> = emptyList()
        var stopNeutral: NeutralPlan? = null
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (closed) return
                closed = true
                pendingLease = null
                pendingSessions.clear()
                sessions.clear()
                commissioningOwnerSession = null
                lastCommissioningTerminal = null
                val invokedActionLease =
                    activeDiscreteCommandId?.let(commands::get)
                        ?.takeIf {
                            (it.state == CommandRecordState.EXECUTING ||
                                it.state == CommandRecordState.COMPLETING) && it.executionInvoked
                        }?.actionLease
                val cancelled =
                    commands.values.filter {
                        it.state == CommandRecordState.EXECUTING ||
                            it.state == CommandRecordState.TERMINATING
                    }
                cancelled.forEach { record ->
                    record.state = CommandRecordState.COMPLETED
                    record.result =
                        ConsoleCommandResult(
                            record.command.commandId,
                            ConsoleCommandOutcome.CANCELLED,
                            "server_stopped",
                            null,
                        )
                }
                cancelledCommands = cancelled
                commandTasks = cancelled.mapNotNull { it.deadlineTask }
                cancelled.forEach { it.deadlineTask = null }
                activeDiscreteCommandId = null
                val lease = activeLease
                activeLease = null
                if (invokedActionLease != null) clearCompletedNeutralBarrierLocked()
                stopNeutral =
                    invokedActionLease?.let {
                        prepareNeutralLocked(
                            it,
                            ConsoleSafetyTrigger.SERVER_STOP,
                            forceBarrier = true,
                        )
                    } ?: lease?.let { prepareNeutralLocked(it, ConsoleSafetyTrigger.SERVER_STOP) }
                        ?: activeNeutralPlan?.let { activePlan ->
                            val unresolved = unresolvedNeutralContext
                            if (neutralCompletedToken == activePlan.token &&
                                unresolved?.matches(activePlan) == true
                            ) {
                                clearCompletedNeutralBarrierLocked()
                                prepareNeutralRetryLocked(unresolved, ConsoleSafetyTrigger.SERVER_STOP)
                            } else {
                                activePlan
                            }
                        }
                        ?: unresolvedNeutralContext?.let {
                            prepareNeutralRetryLocked(it, ConsoleSafetyTrigger.SERVER_STOP)
                        }
                shutdownNeutralToken = stopNeutral?.token
                if (stopNeutral == null) {
                    shutdownNeutralSucceeded = true
                    shutdownNeutralFinished = true
                }
                lease?.let { LeaseTransition(it, stopNeutral) }
            }
        }
        val finishShutdownEvidence = {
            try {
                commandTasks.forEach(::cancelBestEffort)
                transition?.let { cancelLeaseTasks(it.lease) }
                cancelledCommands.forEach { record ->
                    recordBestEffort(
                        audit(
                            ConsoleAuditKind.COMMAND_COMPLETED,
                            record.sessionId,
                            record.command.leaseId,
                            record.command.commandId,
                            record.intentDigestSha256,
                            outcome = "cancelled",
                            reason = "server_stopped",
                            authorityDecisionId = record.authorityDecisionId,
                        ),
                    )
                }
                recordBestEffort(audit(ConsoleAuditKind.SERVER_STOPPED, outcome = "stop_initiated"))
            } finally {
                markShutdownEvidenceFinished()
            }
            Unit
        }
        val afterBarrierInvoked = {
            runWhenCommissioningTransitionsAreIdle(finishShutdownEvidence)
        }
        if (stopNeutral == null) {
            afterBarrierInvoked()
        } else if (synchronized(lock) { neutralCompletedToken == stopNeutral?.token }) {
            afterBarrierInvoked()
            markShutdownNeutralFinished(
                synchronized(lock) {
                    unresolvedNeutralContext?.matches(checkNotNull(stopNeutral)) != true
                },
            )
        } else {
            executeNeutral(checkNotNull(stopNeutral), afterBarrierInvoked)
        }
    }

    /** Runner lifecycle barrier: keep executor and audit storage alive until neutral evidence lands. */
    fun awaitShutdownNeutral(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L) { "shutdown neutral timeout must be positive" }
        val completed = shutdownNeutralCompleted.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) {
            recordBestEffort(
                audit(
                    ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED,
                    outcome = "failed",
                    reason = "shutdown_neutral_timeout",
                    detail = "server-stop neutral was not confirmed before the bounded timeout",
                ),
            )
        }
        return completed && synchronized(lock) { shutdownNeutralSucceeded }
    }

    private fun completeDiscrete(command: AdmittedDiscreteCommand, execution: ConsoleExecutionResult) {
        val result =
            ConsoleCommandResult(
                commandId = command.command.commandId,
                outcome = if (execution.succeeded) ConsoleCommandOutcome.SUCCEEDED else ConsoleCommandOutcome.FAILED,
                reason = if (execution.succeeded) null else execution.reason ?: "execution_failed",
                detail = execution.detail,
            )
        val record = synchronized(lock) { commands[command.command.commandId] } ?: return
        completeCommandRecord(record, result)
    }

    private fun completeCommandRecord(
        record: CommandRecord,
        result: ConsoleCommandResult,
        latchUnknownActuation: Boolean = false,
        preserveNeutralBarrier: Boolean = false,
    ): Boolean {
        var deadlineTask: ConsoleScheduledTask? = null
        var failureNeutral: NeutralPlan? = null
        val reserved = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (commands[record.command.commandId] !== record ||
                    record.state != CommandRecordState.EXECUTING
                ) {
                    false
                } else {
                    record.state = CommandRecordState.COMPLETING
                    deadlineTask = record.deadlineTask
                    record.deadlineTask = null
                    if (result.outcome == ConsoleCommandOutcome.FAILED && record.executionInvoked) {
                        val lease = checkNotNull(record.actionLease)
                        failureNeutral =
                            prepareNeutralLocked(
                                lease,
                                ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
                                forceBarrier = true,
                            )
                        record.barrierToken = failureNeutral?.token
                    }
                    true
                }
            }
        }
        if (!reserved) return false
        cancelBestEffort(deadlineTask)
        val finalize = {
            finalizeCommandRecord(
                record,
                result,
                latchUnknownActuation,
                preserveNeutralBarrier || failureNeutral != null,
            )
        }
        var finalized = false
        failureNeutral?.let { plan ->
            executeNeutral(plan) { finalized = finalize() }
        } ?: run {
            finalized = finalize()
        }
        return finalized
    }

    private fun finalizeCommandRecord(
        record: CommandRecord,
        result: ConsoleCommandResult,
        latchUnknownActuation: Boolean,
        preserveNeutralBarrier: Boolean,
    ): Boolean {
        val evidenceRecorded = recordBestEffort(
            audit(
                ConsoleAuditKind.COMMAND_COMPLETED,
                record.sessionId,
                record.command.leaseId,
                record.command.commandId,
                record.intentDigestSha256,
                result.outcome.name.lowercase(),
                result.reason,
                result.detail,
                authorityDecisionId = record.authorityDecisionId,
            ),
        )
        val publishedResult =
            if (evidenceRecorded) {
                result
            } else {
                ConsoleCommandResult(
                    record.command.commandId,
                    ConsoleCommandOutcome.FAILED,
                    "audit_unavailable",
                    "required completion evidence unavailable",
                )
            }
        val shouldEmit = synchronized(lock) {
            if (commands[record.command.commandId] !== record || record.state != CommandRecordState.COMPLETING) {
                false
            } else {
                record.state = CommandRecordState.COMPLETED
                record.result = publishedResult
                if (activeDiscreteCommandId == record.command.commandId) {
                    activeDiscreteCommandId = null
                }
                if (latchUnknownActuation || !evidenceRecorded) {
                    neutralFault = "discrete action outcome unknown"
                }
                val neutralStillCompleting =
                    latchUnknownActuation && neutralCompletedToken != record.barrierToken
                if (!preserveNeutralBarrier && !neutralStillCompleting &&
                    neutralBarrierToken == record.barrierToken
                ) {
                    neutralBarrierToken = null
                    activeNeutralPlan = null
                    neutralInvocationToken = null
                    neutralCompletedToken = null
                }
                !closed
            }
        }
        if (shouldEmit) emit(record.sessionId, ConsoleCoreEvent.CommandCompleted(publishedResult))
        return true
    }

    private fun completeControl(
        control: CommittedControl,
        execution: ConsoleExecutionResult,
    ): ConsoleControlAck {
        var neutral: NeutralPlan? = null
        val stillCurrent = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val lease = activeLease
                val current =
                    lease === control.lease && lease.inputVersion == control.inputVersion &&
                        lease.neutralizedControlEpoch != control.admitted.controlEpoch && !closed
                if (current && !execution.succeeded) {
                    neutral = prepareNeutralLocked(control.lease, ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED)
                }
                current
            }
        }
        val executionAck =
            when {
                !stillCurrent ->
                    ConsoleControlAck(
                        control.admitted.frame.leaseId,
                        control.admitted.frame.inputSequence,
                        ConsoleControlStatus.STALE,
                        "neutralized_before_apply",
                    )
                execution.succeeded ->
                    ConsoleControlAck(
                        control.admitted.frame.leaseId,
                        control.admitted.frame.inputSequence,
                        ConsoleControlStatus.APPLIED,
                        null,
                    )
                else ->
                    ConsoleControlAck(
                        control.admitted.frame.leaseId,
                        control.admitted.frame.inputSequence,
                        ConsoleControlStatus.REJECTED,
                        execution.reason ?: "execution_failed",
                    )
            }
        if (neutral != null) {
            var finalized = executionAck
            executeNeutral(checkNotNull(neutral)) {
                finalized = finalizeControlEvidence(control, execution, executionAck)
            }
            return finalized
        }
        return finalizeControlEvidence(control, execution, executionAck)
    }

    private fun finalizeControlEvidence(
        control: CommittedControl,
        execution: ConsoleExecutionResult,
        executionAck: ConsoleControlAck,
    ): ConsoleControlAck {
        val evidenceRecorded =
            recordBestEffort(
                audit(
                    ConsoleAuditKind.CONTROL_COMPLETED,
                    control.admitted.sessionId,
                    control.admitted.frame.leaseId,
                    control.admitted.frame.inputSequence.toString(),
                    control.admitted.intentDigestSha256,
                    outcome = executionAck.status.name.lowercase(),
                    reason = executionAck.reason,
                    detail = execution.detail,
                    authorityDecisionId = control.admitted.authorityDecisionId,
                ),
            )
        val ack =
            if (evidenceRecorded) {
                executionAck
            } else {
                ConsoleControlAck(
                    control.admitted.frame.leaseId,
                    control.admitted.frame.inputSequence,
                    ConsoleControlStatus.REJECTED,
                    "audit_unavailable",
                )
            }
        if (synchronized(lock) { !closed }) {
            emit(control.admitted.sessionId, ConsoleCoreEvent.ControlAcknowledged(ack))
        }
        return ack
    }

    /** Caller holds [lock]. Shared observation fields cannot drift from browser runtime truth. */
    private fun currentActuationObservationLocked() =
        ConsoleActuationObservation(
            adapter = actuationReadiness.adapter,
            aircraftConnection = actuationReadiness.aircraftConnection,
            adapterActuationReady = hardwareAdapterActuationReady,
            operatingProfile = actuationReadiness.operatingProfile,
        )

    /** Caller holds [lock]. Browser-visible DJI lock truth never becomes authority by itself. */
    private fun newLeaseAuthorityLocked(sessionId: String): LeaseAuthority? {
        if (commissioningTransitionTokens.isNotEmpty()) return null
        if (actuationReadiness.allowsLease(sessionId)) return LeaseAuthority.Mock
        if (commissioningActivationPending) return null
        val commissioning = commissioningLifecycle.currentSession() ?: return null
        return if (
            commissioningLifecycle.allowsLease(
                currentActuationObservationLocked(),
                commissioning,
                sessionId,
                monotonicClock.nowNanos(),
            )
        ) {
            LeaseAuthority.Commissioning(commissioning)
        } else {
            null
        }
    }

    /** Caller holds [lock]. An old lease can never float onto a replacement generation. */
    private fun leaseAuthorityAllowsLocked(
        lease: ActiveLease,
        intent: ConsoleActuationIntent? = null,
    ): Boolean {
        if (commissioningTransitionTokens.isNotEmpty()) return false
        if (lease.deadlineAttachPending) return false
        val commissioning = lease.commissioningSession
        if (commissioning == null) {
            return if (intent == null) {
                actuationReadiness.allowsLease(lease.holderSessionId)
            } else {
                actuationReadiness.allows(lease.holderSessionId, intent)
            }
        }
        if (commissioningActivationPending) return false
        return if (intent == null) {
            commissioningLifecycle.allowsLease(
                currentActuationObservationLocked(),
                commissioning,
                lease.holderSessionId,
                monotonicClock.nowNanos(),
            )
        } else {
            commissioningLifecycle.authorizes(
                currentActuationObservationLocked(),
                commissioning,
                lease.holderSessionId,
                intent,
                monotonicClock.nowNanos(),
            )
        }
    }

    private fun commandAuthorityAllowsLocked(
        record: CommandRecord,
        intent: ConsoleActuationIntent,
    ): Boolean {
        if (commissioningTransitionTokens.isNotEmpty()) return false
        if (record.actionLease?.deadlineAttachPending == true) return false
        val commissioning = record.commissioningSession
        return if (commissioning == null) {
            record.readinessEpoch == readinessEpoch &&
                actuationReadiness.allows(record.sessionId, intent)
        } else {
            !commissioningActivationPending &&
                commissioningLifecycle.authorizes(
                    currentActuationObservationLocked(),
                    commissioning,
                    record.sessionId,
                    intent,
                    monotonicClock.nowNanos(),
                )
        }
    }

    private fun controlAuthorityAllowsLocked(control: CommittedControl): Boolean {
        if (commissioningTransitionTokens.isNotEmpty()) return false
        if (control.lease.deadlineAttachPending) return false
        val commissioning = control.commissioningSession
        return if (commissioning == null) {
            control.readinessEpoch == readinessEpoch &&
                actuationReadiness.allows(
                    control.admitted.sessionId,
                    ConsoleActuationIntent.VIRTUAL_STICK,
                )
        } else {
            !commissioningActivationPending &&
                commissioningLifecycle.authorizes(
                    currentActuationObservationLocked(),
                    commissioning,
                    control.admitted.sessionId,
                    ConsoleActuationIntent.VIRTUAL_STICK,
                    monotonicClock.nowNanos(),
                )
        }
    }

    private fun commandRuleLocked(
        sessionId: String,
        leaseId: String,
        action: ConsoleDiscreteAction,
        ttlMillis: Long,
    ): CompanionAdmissionRule =
        when {
            closed -> CompanionAdmissionRule.reject("server_closed")
            actuationAdmissionClosed.get() ->
                CompanionAdmissionRule.reject(ACTUATION_ADMISSION_CLOSED_REASON)
            sessionId !in sessions -> CompanionAdmissionRule.reject("unknown_session")
            ttlMillis !in config.minimumCommandTtlMillis..config.maximumCommandTtlMillis ->
                CompanionAdmissionRule.reject("invalid_command_ttl")
            auditFault -> CompanionAdmissionRule.reject("audit_unavailable")
            neutralFault != null -> CompanionAdmissionRule.reject("actuation_locked", neutralFault)
            activeDiscreteCommandId != null -> CompanionAdmissionRule.reject("discrete_action_in_progress")
            neutralBarrierToken != null -> CompanionAdmissionRule.reject("neutral_in_progress")
            activeLease?.leaseId != leaseId || activeLease?.holderSessionId != sessionId ->
                CompanionAdmissionRule.reject("not_lease_holder")
            !leaseAuthorityAllowsLocked(checkNotNull(activeLease), action.actuationIntent) ->
                CompanionAdmissionRule.reject("actuation_not_ready")
            else -> CompanionAdmissionRule.ACCEPTED
        }

    private fun controlRuleLocked(
        sessionId: String,
        frame: ConsoleControlFrame,
        ignorePending: Boolean = false,
    ): CompanionAdmissionRule {
        val lease = activeLease
        return when {
            closed -> CompanionAdmissionRule.reject("server_closed")
            actuationAdmissionClosed.get() ->
                CompanionAdmissionRule.reject(ACTUATION_ADMISSION_CLOSED_REASON)
            sessionId !in sessions -> CompanionAdmissionRule.reject("unknown_session")
            frame.ttlMillis !in config.minimumControlTtlMillis..config.maximumControlTtlMillis ->
                CompanionAdmissionRule.reject("invalid_control_ttl")
            frame.inputSequence !in 1L..ConsoleProtocolModule.MAX_SAFE_INTEGER ->
                CompanionAdmissionRule.reject("invalid_sequence")
            !frame.axesAreNormalized() -> CompanionAdmissionRule.reject("invalid_control_axes")
            auditFault -> CompanionAdmissionRule.reject("audit_unavailable")
            neutralFault != null -> CompanionAdmissionRule.reject("actuation_locked", neutralFault)
            activeDiscreteCommandId != null -> CompanionAdmissionRule.reject("discrete_action_in_progress")
            neutralBarrierToken != null -> CompanionAdmissionRule.reject("neutral_in_progress")
            lease == null || lease.leaseId != frame.leaseId || lease.holderSessionId != sessionId ->
                CompanionAdmissionRule.reject("not_lease_holder")
            !leaseAuthorityAllowsLocked(lease, ConsoleActuationIntent.VIRTUAL_STICK) ->
                CompanionAdmissionRule.reject("actuation_not_ready")
            lease.lastInputSequence != null && frame.inputSequence <= checkNotNull(lease.lastInputSequence) ->
                CompanionAdmissionRule.reject("stale_sequence")
            !ignorePending && lease.pendingControlSequence != null ->
                CompanionAdmissionRule.reject("control_busy")
            else -> CompanionAdmissionRule.ACCEPTED
        }
    }

    private fun ConsoleControlFrame.axesAreNormalized(): Boolean =
        forward.isFinite() && forward in -1.0..1.0 &&
            right.isFinite() && right in -1.0..1.0 &&
            up.isFinite() && up in -1.0..1.0 &&
            yaw.isFinite() && yaw in -1.0..1.0

    private val ConsoleDiscreteAction.requiresControlRelease: Boolean
        get() = this == ConsoleDiscreteAction.LANDING || this == ConsoleDiscreteAction.RETURN_TO_HOME

    private fun refusedLease(sessionId: String, reason: String): ConsoleLeaseState {
        recordBestEffort(
            audit(
                ConsoleAuditKind.LEASE_REFUSED,
                sessionId = sessionId,
                outcome = "denied",
                reason = reason,
            ),
        )
        val state = ConsoleLeaseState(ConsoleLeaseStatus.DENIED, null, null, null, reason)
        emit(sessionId, ConsoleCoreEvent.LeaseChanged(state))
        return state
    }

    private fun refusedCommand(
        sessionId: String,
        commandId: String,
        decision: ConsoleAdmissionDecision.Rejected,
    ): ConsoleCommandAck {
        recordBestEffort(
            audit(
                ConsoleAuditKind.COMMAND_REFUSED,
                sessionId,
                subjectId = commandId,
                intentDigestSha256 = decision.intentDigestSha256,
                outcome = "rejected",
                reason = decision.reason,
                detail = decision.detail,
                authorityDecisionId = decision.authorityDecisionId,
            ),
        )
        val ack =
            ConsoleCommandAck(
                commandId,
                ConsoleCommandDecision.REJECTED,
                decision.reason,
                decision.intentDigestSha256,
            )
        emit(sessionId, ConsoleCoreEvent.CommandAcknowledged(ack))
        return ack
    }

    private fun refusedControl(
        sessionId: String,
        frame: ConsoleControlFrame,
        reason: String,
        digest: String?,
        authorityDecisionId: String? = null,
    ): ConsoleControlAck {
        recordBestEffort(
            audit(
                ConsoleAuditKind.CONTROL_REFUSED,
                sessionId,
                frame.leaseId,
                frame.inputSequence.toString(),
                digest,
                "rejected",
                reason,
                authorityDecisionId = authorityDecisionId,
            ),
        )
        val status = if (reason == "stale_sequence") ConsoleControlStatus.STALE else ConsoleControlStatus.REJECTED
        val ack = ConsoleControlAck(frame.leaseId, frame.inputSequence, status, reason)
        emit(sessionId, ConsoleCoreEvent.ControlAcknowledged(ack))
        return ack
    }

    private fun refusedCommissioningStart(
        decision: ConsoleCommissioningStartDecision,
    ) = ConsoleCommissioningStartResult(decision, null)

    /** Caller holds [lock]. DJI commissioning never rewrites the browser-visible lock to unlocked. */
    private fun commissioningPublicLockIsSafeLocked(): Boolean =
        actuationReadiness.adapter == AdapterKind.DJI &&
            actuationReadiness.actuationLock == ActuationLockState.LOCKED

    private fun commissioningAuditDetail(reservation: ConsoleCommissioningReservation): String =
        "generation=${reservation.generation};ttl_ms=${reservation.requestedTtlMillis};" +
            "intents=${reservation.allowedIntents.sortedBy { it.name }.joinToString(",") { it.name.lowercase() }}"

    private fun commissioningAuditDetail(session: ConsoleCommissioningSessionView): String =
        "generation=${session.generation};expires_at_nanos=${session.expiresAtMonotonicNanos};" +
            "intents=${session.allowedIntents.sortedBy { it.name }.joinToString(",") { it.name.lowercase() }}"

    private fun ConsoleCommissioningSessionView.sameCommissioningGeneration(
        other: ConsoleCommissioningSessionView,
    ): Boolean =
        commissioningId == other.commissioningId && generation == other.generation

    /** Caller holds [lock]. Every returned observation owns a unique capture-order revision. */
    private fun captureCommissioningAuthorityLocked(
        sessionId: String,
    ): ConsoleCommissioningAuthorityState {
        val recipient = checkNotNull(sessions[sessionId]) { "sessionId is not open" }
        effectiveCommissioningAuthorityLocked(recipient)?.let { return it }
        lastCommissioningTerminal
            ?.takeIf { it.ownerSession === recipient }
            ?.let { return captureTerminalCommissioningAuthorityLocked(it) }
        return ConsoleCommissioningAuthorityState(
            stateRevision = nextCommissioningStateRevisionLocked(),
            state = ConsoleCommissioningAuthorityStatus.INACTIVE,
            commissioningId = null,
            generation = 0L,
            allowedIntents = emptySet(),
            expiresInMillis = null,
            reason = ConsoleCommissioningAuthorityReason.NO_ACTIVE_SESSION,
        )
    }

    /** Caller holds [lock]. Returns null unless authority is effective for this exact session. */
    private fun effectiveCommissioningAuthorityLocked(
        recipient: ConsoleSession,
    ): ConsoleCommissioningAuthorityState? {
        if (closed || actuationAdmissionClosed.get() || auditFault ||
            commissioningActivationPending || commissioningDeadlineTask == null ||
            commissioningTransitionTokens.isNotEmpty() ||
            commissioningOwnerSession !== recipient || sessions[recipient.sessionId] !== recipient ||
            !commissioningPublicLockIsSafeLocked() ||
            !currentActuationObservationLocked().isEligibleForG520Commissioning()
        ) {
            return null
        }
        val current = commissioningLifecycle.currentSession() ?: return null
        if (current.operatorSessionId != recipient.sessionId ||
            commissioningLifecycle.expiryDecision(current, monotonicClock.nowNanos()) !=
            ConsoleCommissioningExpiryDecision.NOT_DUE
        ) {
            return null
        }
        val expiresInMillis = current.remainingTtlMillis(monotonicClock.nowNanos())
        if (expiresInMillis !in 1L..ConsoleCommissioningLifecycle.MAXIMUM_TTL_MILLIS) return null
        return ConsoleCommissioningAuthorityState(
            stateRevision = nextCommissioningStateRevisionLocked(),
            state = ConsoleCommissioningAuthorityStatus.ACTIVE,
            commissioningId = current.commissioningId,
            generation = current.generation,
            allowedIntents = current.allowedIntents,
            expiresInMillis = expiresInMillis,
            reason = null,
        )
    }

    private fun captureActiveCommissioningAuthority(
        expectedSession: ConsoleCommissioningSessionView,
    ): TargetedCommissioningAuthority? =
        synchronized(lock) {
            val owner = commissioningOwnerSession ?: return@synchronized null
            val state = effectiveCommissioningAuthorityLocked(owner) ?: return@synchronized null
            if (state.commissioningId != expectedSession.commissioningId ||
                state.generation != expectedSession.generation
            ) {
                return@synchronized null
            }
            TargetedCommissioningAuthority(owner, state)
        }

    /** Caller holds [lock]. */
    private fun captureTerminalCommissioningAuthorityLocked(
        terminal: CommissioningTerminalProjection,
    ): ConsoleCommissioningAuthorityState =
        ConsoleCommissioningAuthorityState(
            stateRevision = nextCommissioningStateRevisionLocked(),
            state = ConsoleCommissioningAuthorityStatus.INACTIVE,
            commissioningId = terminal.session.commissioningId,
            generation = terminal.session.generation,
            allowedIntents = emptySet(),
            expiresInMillis = null,
            reason = terminal.reason,
        )

    /** Caller holds [lock]. */
    private fun nextCommissioningStateRevisionLocked(): Long {
        commissioningStateRevision =
            nextCounter(commissioningStateRevision, "commissioning state revision")
        return commissioningStateRevision
    }

    private fun emitCommissioningAuthority(targeted: TargetedCommissioningAuthority) {
        synchronized(commissioningEventLock) {
            val stillCurrent = synchronized(lock) {
                sessions[targeted.ownerSession.sessionId] === targeted.ownerSession &&
                    when (targeted.state.state) {
                        ConsoleCommissioningAuthorityStatus.ACTIVE -> {
                            val current = commissioningLifecycle.currentSession()
                            current != null &&
                                !closed && !actuationAdmissionClosed.get() &&
                                commissioningOwnerSession === targeted.ownerSession &&
                                current.commissioningId == targeted.state.commissioningId &&
                                current.generation == targeted.state.generation &&
                                !commissioningActivationPending && !auditFault &&
                                commissioningDeadlineTask != null &&
                                commissioningTransitionTokens.isEmpty() &&
                                commissioningPublicLockIsSafeLocked() &&
                                currentActuationObservationLocked().isEligibleForG520Commissioning() &&
                                commissioningLifecycle.expiryDecision(current, monotonicClock.nowNanos()) ==
                                ConsoleCommissioningExpiryDecision.NOT_DUE
                        }
                        ConsoleCommissioningAuthorityStatus.INACTIVE ->
                            lastCommissioningTerminal?.let { terminal ->
                                terminal.ownerSession === targeted.ownerSession &&
                                    terminal.session.commissioningId == targeted.state.commissioningId &&
                                    terminal.session.generation == targeted.state.generation &&
                                    terminal.reason == targeted.state.reason
                            } == true
                    }
            }
            if (stillCurrent) {
                emit(
                    targeted.ownerSession.sessionId,
                    ConsoleCoreEvent.CommissioningAuthorityChanged(
                        recipientSession = targeted.ownerSession,
                        state = targeted.state,
                    ),
                )
            }
        }
    }

    private fun captureAuditUnavailableTerminal(
        transition: CommissioningTermination,
    ): TargetedCommissioningAuthority? =
        synchronized(lock) {
            val previous = lastCommissioningTerminal ?: return@synchronized null
            val originalTarget = transition.authority ?: return@synchronized null
            if (previous.ownerSession !== originalTarget.ownerSession ||
                !previous.session.sameCommissioningGeneration(transition.session)
            ) {
                return@synchronized null
            }
            val replacement =
                CommissioningTerminalProjection(
                    ownerSession = previous.ownerSession,
                    session = previous.session,
                    reason = ConsoleCommissioningAuthorityReason.AUDIT_UNAVAILABLE,
                )
            lastCommissioningTerminal = replacement
            TargetedCommissioningAuthority(
                replacement.ownerSession,
                captureTerminalCommissioningAuthorityLocked(replacement),
            )
        }

    private fun ConsoleCommissioningTerminationReason.toAuthorityReason():
        ConsoleCommissioningAuthorityReason =
        when (this) {
            ConsoleCommissioningTerminationReason.HOST_REVOKED ->
                ConsoleCommissioningAuthorityReason.HOST_REVOKED
            ConsoleCommissioningTerminationReason.TTL_EXPIRED ->
                ConsoleCommissioningAuthorityReason.TTL_EXPIRED
            ConsoleCommissioningTerminationReason.OPERATOR_DISCONNECTED ->
                ConsoleCommissioningAuthorityReason.OPERATOR_DISCONNECTED
            ConsoleCommissioningTerminationReason.OBSERVATION_LOST ->
                ConsoleCommissioningAuthorityReason.OBSERVATION_LOST
            ConsoleCommissioningTerminationReason.RUNTIME_STATE_CHANGED ->
                ConsoleCommissioningAuthorityReason.RUNTIME_STATE_CHANGED
            ConsoleCommissioningTerminationReason.SERVER_CLOSED ->
                ConsoleCommissioningAuthorityReason.SERVER_CLOSED
            ConsoleCommissioningTerminationReason.AUDIT_UNAVAILABLE ->
                ConsoleCommissioningAuthorityReason.AUDIT_UNAVAILABLE
            ConsoleCommissioningTerminationReason.DEADLINE_UNAVAILABLE ->
                ConsoleCommissioningAuthorityReason.DEADLINE_UNAVAILABLE
        }

    private fun prepareCommissioningTermination(
        expectedSession: ConsoleCommissioningSessionView?,
        reason: ConsoleCommissioningTerminationReason,
    ): PreparedCommissioningTermination =
        synchronized(actuationDispatchLock) {
            synchronized(lock) {
                prepareCommissioningTerminationLocked(expectedSession, reason)
            }
        }

    /** Caller holds actuationDispatchLock then [lock]. Authority is revoked before this returns. */
    private fun prepareCommissioningTerminationLocked(
        expectedSession: ConsoleCommissioningSessionView?,
        reason: ConsoleCommissioningTerminationReason,
    ): PreparedCommissioningTermination {
        val current = commissioningLifecycle.currentSession()
            ?: return PreparedCommissioningTermination(null, stale = false)
        if (expectedSession != null && !current.sameCommissioningGeneration(expectedSession)) {
            return PreparedCommissioningTermination(null, stale = true)
        }
        if (commissioningLifecycle.revoke(current) != ConsoleCommissioningRevokeDecision.REVOKED) {
            return PreparedCommissioningTermination(null, stale = expectedSession != null)
        }

        // This is the terminal linearization point. Every later gate sees no active generation.
        val evidenceTransitionToken = beginCommissioningTransitionLocked()
        commissioningActivationPending = false
        val terminalOwner =
            commissioningOwnerSession?.takeIf { owner ->
                owner.sessionId == current.operatorSessionId && sessions[owner.sessionId] === owner
            }
        commissioningOwnerSession = null
        val terminalProjection =
            terminalOwner?.let { owner ->
                CommissioningTerminalProjection(
                    ownerSession = owner,
                    session = current,
                    reason = reason.toAuthorityReason(),
                )
            }
        lastCommissioningTerminal = terminalProjection
        val terminalAuthority =
            terminalProjection?.let { terminal ->
                TargetedCommissioningAuthority(
                    terminal.ownerSession,
                    captureTerminalCommissioningAuthorityLocked(terminal),
                )
            }
        commissioningScheduleEpoch = nextCounter(commissioningScheduleEpoch, "commissioning schedule epoch")
        val deadlineTask = commissioningDeadlineTask
        commissioningDeadlineTask = null
        pendingLease = null

        val actionRecord =
            activeDiscreteCommandId?.let(commands::get)
                ?.takeIf {
                    it.commissioningSession?.sameCommissioningGeneration(current) == true &&
                        (it.state == CommandRecordState.EXECUTING ||
                            it.state == CommandRecordState.COMPLETING)
                }
        val commandTermination =
            actionRecord?.takeIf {
                it.state == CommandRecordState.EXECUTING && it.executionInvoked
            }?.let {
                terminateInvokedActionLocked(
                    it,
                    ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
                    "commissioning_${reason.name.lowercase()}",
                )
            }
        val cancelledCommand =
            actionRecord?.takeIf {
                it.state == CommandRecordState.EXECUTING && !it.executionInvoked
            }?.let { record ->
                record.state = CommandRecordState.COMPLETING
                val task = record.deadlineTask
                record.deadlineTask = null
                CancelledCommissioningCommand(
                    record = record,
                    result =
                        ConsoleCommandResult(
                            record.command.commandId,
                            ConsoleCommandOutcome.FAILED,
                            "commissioning_${reason.name.lowercase()}",
                            "commissioning authority ended before executor dispatch",
                        ),
                    deadlineTask = task,
                )
            }
        val revokedLease =
            commandTermination?.revokedLease
                ?: activeLease?.takeIf {
                    it.commissioningSession?.sameCommissioningGeneration(current) == true
                }?.also { activeLease = null }
        val actionLease = revokedLease ?: actionRecord?.actionLease
        if (commandTermination == null && actionLease != null) clearCompletedNeutralBarrierLocked()
        val neutral =
            commandTermination?.neutral
                ?: actionLease?.let {
                    prepareFreshNeutralLocked(
                        it,
                        ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
                    )
                }
        val neutralTransitionToken = neutral?.let { beginCommissioningTransitionLocked() }
        val terminalNeutral = neutral?.let { plan ->
            plan.copy(
                completion = { result ->
                    try {
                        plan.completion?.invoke(result)
                    } finally {
                        completeCommissioningTransition(checkNotNull(neutralTransitionToken))
                    }
                },
            ).also { wrapped ->
                if (activeNeutralPlan?.token == plan.token) activeNeutralPlan = wrapped
            }
        }
        return PreparedCommissioningTermination(
            CommissioningTermination(
                session = current,
                reason = reason,
                revokedLease = revokedLease,
                affectedLease = actionLease,
                neutral = terminalNeutral,
                commandTermination = commandTermination,
                cancelledCommand = cancelledCommand,
                deadlineTask = deadlineTask,
                evidenceTransitionToken = evidenceTransitionToken,
                neutralTransitionToken = neutralTransitionToken,
                authority = terminalAuthority,
            ),
            stale = false,
        )
    }

    private fun terminateCommissioning(
        expectedSession: ConsoleCommissioningSessionView? = null,
        reason: ConsoleCommissioningTerminationReason,
        recordTerminalAudit: Boolean = true,
    ): Boolean {
        val prepared = prepareCommissioningTermination(expectedSession, reason)
        return prepared.transition?.let {
            finishCommissioningTermination(it, recordTerminalAudit)
        } ?: false
    }

    /** Safety invocation precedes the potentially blocking required terminal audit. */
    private fun finishCommissioningTermination(
        transition: CommissioningTermination,
        recordTerminalAudit: Boolean = true,
    ): Boolean {
        var auditRecorded = true
        val afterBarrierInvoked: () -> Unit = {
            try {
                // Authority was already revoked under dispatch -> state lock, and any required
                // neutral executor call has already been invoked by executeNeutral. A blocked
                // observer can therefore delay evidence publication, never safety ordering.
                transition.authority?.let(::emitCommissioningAuthority)
                cancelBestEffort(transition.deadlineTask)
                cancelBestEffort(transition.commandTermination?.deadlineTask)
                transition.commandTermination?.actionLease?.let(::cancelLeaseTasks)
                cancelBestEffort(transition.cancelledCommand?.deadlineTask)
                transition.cancelledCommand?.record?.actionLease?.let(::cancelLeaseTasks)
                transition.revokedLease?.let(::cancelLeaseTasks)
                transition.cancelledCommand?.let { cancelled ->
                    finalizeCommandRecord(
                        cancelled.record,
                        cancelled.result,
                        latchUnknownActuation = false,
                        preserveNeutralBarrier = true,
                    )
                }
                if (recordTerminalAudit) {
                    auditRecorded = recordRequired(
                        audit(
                            ConsoleAuditKind.COMMISSIONING_TERMINATED,
                            sessionId = transition.session.operatorSessionId,
                            leaseId = transition.affectedLease?.leaseId,
                            subjectId = transition.session.commissioningId,
                            outcome = "terminated",
                            reason = transition.reason.name.lowercase(),
                            detail = commissioningAuditDetail(transition.session),
                        ),
                    )
                    if (!auditRecorded) {
                        captureAuditUnavailableTerminal(transition)
                            ?.let(::emitCommissioningAuthority)
                    }
                }
                transition.revokedLease?.let { lease ->
                    recordBestEffort(
                        audit(
                            ConsoleAuditKind.LEASE_RELEASED,
                            lease.holderSessionId,
                            lease.leaseId,
                            outcome = "commissioning_terminated",
                            reason = transition.reason.name.lowercase(),
                        ),
                    )
                    if (synchronized(lock) { !closed }) {
                        emit(
                            lease.holderSessionId,
                            ConsoleCoreEvent.LeaseChanged(
                                ConsoleLeaseState(
                                    ConsoleLeaseStatus.RELEASED,
                                    lease.leaseId,
                                    null,
                                    null,
                                    transition.reason.name.lowercase(),
                                ),
                            ),
                        )
                    }
                }
            } finally {
                completeCommissioningTransition(transition.evidenceTransitionToken)
            }
        }
        transition.neutral?.let { executeNeutral(it, afterBarrierInvoked) } ?: afterBarrierInvoked()
        return auditRecorded
    }

    /** Caller holds [lock]. */
    private fun beginCommissioningTransitionLocked(): Long {
        commissioningAuthorityEpoch =
            nextCounter(commissioningAuthorityEpoch, "commissioning authority epoch")
        check(commissioningTransitionTokens.add(commissioningAuthorityEpoch)) {
            "commissioning transition token collision"
        }
        return commissioningAuthorityEpoch
    }

    private fun completeCommissioningTransition(transitionToken: Long) {
        val drainAuditFault = synchronized(lock) {
            commissioningTransitionTokens.remove(transitionToken)
            if (commissioningTransitionTokens.isEmpty() && auditFault && auditTerminalizationDeferred) {
                auditTerminalizationDeferred = false
                true
            } else {
                false
            }
        }
        if (drainAuditFault) terminalizeAuthorityAfterAuditFault()
        drainShutdownEvidenceIfTransitionsAreIdle()
    }

    private fun runWhenCommissioningTransitionsAreIdle(work: () -> Unit) {
        val runNow = synchronized(lock) {
            if (commissioningTransitionTokens.isEmpty()) {
                true
            } else {
                check(pendingShutdownEvidenceWork == null) {
                    "shutdown evidence work is already pending"
                }
                pendingShutdownEvidenceWork = work
                false
            }
        }
        if (runNow) work()
    }

    private fun drainShutdownEvidenceIfTransitionsAreIdle() {
        val work = synchronized(lock) {
            if (commissioningTransitionTokens.isEmpty()) {
                pendingShutdownEvidenceWork.also { pendingShutdownEvidenceWork = null }
            } else {
                null
            }
        }
        work?.invoke()
    }

    private fun markShutdownEvidenceFinished() {
        val signal = synchronized(lock) {
            shutdownEvidenceFinished = true
            shutdownNeutralFinished
        }
        if (signal) shutdownNeutralCompleted.countDown()
    }

    private fun markShutdownNeutralFinished(succeeded: Boolean) {
        val signal = synchronized(lock) {
            shutdownNeutralSucceeded = succeeded
            shutdownNeutralFinished = true
            shutdownEvidenceFinished
        }
        if (signal) shutdownNeutralCompleted.countDown()
    }

    private fun scheduleCommissioningDeadline(
        session: ConsoleCommissioningSessionView,
    ): CommissioningDeadlineAttachDecision {
        val registration = synchronized(lock) {
            val current = commissioningLifecycle.currentSession()
            if (closed || actuationAdmissionClosed.get() || auditFault ||
                current?.sameCommissioningGeneration(session) != true
            ) {
                null
            } else {
                commissioningScheduleEpoch =
                    nextCounter(commissioningScheduleEpoch, "commissioning schedule epoch")
                commissioningScheduleEpoch
            }
        } ?: return CommissioningDeadlineAttachDecision.STATE_CHANGED_OR_REVOKED

        var synchronousReplacementCount = 0
        while (true) {
            val callbackState = AtomicInteger(DEADLINE_CALLBACK_SCHEDULING)
            val candidate =
                try {
                    scheduler.scheduleAt(session.expiresAtMonotonicNanos) {
                        if (callbackState.compareAndSet(
                                DEADLINE_CALLBACK_SCHEDULING,
                                DEADLINE_CALLBACK_EARLY,
                            )
                        ) {
                            return@scheduleAt
                        }
                        if (callbackState.get() == DEADLINE_CALLBACK_ATTACHED) {
                            onCommissioningDeadline(session, registration)
                        }
                    }
                } catch (_: Exception) {
                    return CommissioningDeadlineAttachDecision.SCHEDULER_UNAVAILABLE
                }
            val attached = synchronized(lock) {
                val expiry = commissioningLifecycle.expiryDecision(session, monotonicClock.nowNanos())
                val current = commissioningLifecycle.currentSession()
                val canActivate =
                    !closed && !actuationAdmissionClosed.get() && !auditFault &&
                        commissioningTransitionTokens.isEmpty() &&
                        commissioningScheduleEpoch == registration &&
                        current?.sameCommissioningGeneration(session) == true &&
                        session.operatorSessionId in sessions && commissioningPublicLockIsSafeLocked() &&
                        currentActuationObservationLocked().isEligibleForG520Commissioning() &&
                        expiry == ConsoleCommissioningExpiryDecision.NOT_DUE
                if (canActivate && callbackState.compareAndSet(
                        DEADLINE_CALLBACK_SCHEDULING,
                        DEADLINE_CALLBACK_ATTACHED,
                    )
                ) {
                    commissioningDeadlineTask = candidate
                    commissioningActivationPending = false
                    true
                } else {
                    // Prevent a callback racing cancellation from creating another replacement.
                    callbackState.compareAndSet(
                        DEADLINE_CALLBACK_SCHEDULING,
                        DEADLINE_CALLBACK_EARLY,
                    )
                    false
                }
            }
            if (attached) return CommissioningDeadlineAttachDecision.ATTACHED

            cancelBestEffort(candidate)
            val unattachedDecision = synchronized(lock) {
                if (commissioningScheduleEpoch != registration ||
                    commissioningLifecycle.currentSession()
                        ?.sameCommissioningGeneration(session) != true
                ) {
                    CommissioningDeadlineAttachDecision.STATE_CHANGED_OR_REVOKED
                } else if (
                    commissioningLifecycle.expiryDecision(session, monotonicClock.nowNanos()) ==
                    ConsoleCommissioningExpiryDecision.DUE
                ) {
                    CommissioningDeadlineAttachDecision.EXPIRED_CURRENT
                } else {
                    null
                }
            }
            if (unattachedDecision != null) return unattachedDecision
            if (callbackState.get() != DEADLINE_CALLBACK_EARLY) {
                return CommissioningDeadlineAttachDecision.STATE_CHANGED_OR_REVOKED
            }
            if (synchronousReplacementCount >= MAX_SYNCHRONOUS_DEADLINE_REPLACEMENTS) {
                return CommissioningDeadlineAttachDecision.SCHEDULER_UNAVAILABLE
            }
            synchronousReplacementCount += 1
        }
    }

    private fun onCommissioningDeadline(
        expectedSession: ConsoleCommissioningSessionView,
        registration: Long,
    ) {
        val action = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (commissioningScheduleEpoch != registration ||
                    commissioningLifecycle.currentSession()
                        ?.sameCommissioningGeneration(expectedSession) != true
                ) {
                    CommissioningDeadlineAction.STALE
                } else {
                    commissioningDeadlineTask = null
                    when (commissioningLifecycle.expiryDecision(expectedSession, monotonicClock.nowNanos())) {
                        ConsoleCommissioningExpiryDecision.DUE ->
                            CommissioningDeadlineAction.Terminate(
                                prepareCommissioningTerminationLocked(
                                    expectedSession,
                                    ConsoleCommissioningTerminationReason.TTL_EXPIRED,
                                ).transition,
                            )
                        ConsoleCommissioningExpiryDecision.NOT_DUE -> {
                            // No authority is effective while an early task is being replaced.
                            commissioningActivationPending = true
                            CommissioningDeadlineAction.RESCHEDULE
                        }
                        ConsoleCommissioningExpiryDecision.NO_ACTIVE_SESSION,
                        ConsoleCommissioningExpiryDecision.STALE_SESSION,
                        -> CommissioningDeadlineAction.STALE
                    }
                }
            }
        }
        when (action) {
            is CommissioningDeadlineAction.Terminate ->
                action.transition?.let(::finishCommissioningTermination)
            CommissioningDeadlineAction.RESCHEDULE ->
                when (scheduleCommissioningDeadline(expectedSession)) {
                    CommissioningDeadlineAttachDecision.ATTACHED ->
                        captureActiveCommissioningAuthority(expectedSession)
                            ?.let(::emitCommissioningAuthority)
                    CommissioningDeadlineAttachDecision.SCHEDULER_UNAVAILABLE ->
                        terminateCommissioning(
                            expectedSession,
                            ConsoleCommissioningTerminationReason.DEADLINE_UNAVAILABLE,
                        )
                    CommissioningDeadlineAttachDecision.EXPIRED_CURRENT ->
                        terminateCommissioning(
                            expectedSession,
                            ConsoleCommissioningTerminationReason.TTL_EXPIRED,
                        )
                    CommissioningDeadlineAttachDecision.STATE_CHANGED_OR_REVOKED -> Unit
                }
            CommissioningDeadlineAction.STALE -> Unit
        }
    }

    private fun scheduleCommandDeadline(record: CommandRecord): Boolean {
        return scheduleDeadlineWithoutInlineRecursion(
            deadlineNanos = record.deadlineNanos,
            isCurrent = {
                synchronized(lock) {
                    commands[record.command.commandId] === record &&
                        record.state == CommandRecordState.EXECUTING &&
                        monotonicClock.nowNanos() < record.deadlineNanos
                }
            },
            attach = { task, callbackState ->
                synchronized(lock) {
                    if (commands[record.command.commandId] === record &&
                        record.state == CommandRecordState.EXECUTING &&
                        monotonicClock.nowNanos() < record.deadlineNanos &&
                        callbackState.compareAndSet(
                            DEADLINE_CALLBACK_SCHEDULING,
                            DEADLINE_CALLBACK_ATTACHED,
                        )
                    ) {
                        record.deadlineTask = task
                        true
                    } else {
                        callbackState.compareAndSet(
                            DEADLINE_CALLBACK_SCHEDULING,
                            DEADLINE_CALLBACK_EARLY,
                        )
                        false
                    }
                }
            },
            onDeadline = {
                onCommandDeadline(
                    record.command.commandId,
                    record.intentDigestSha256,
                    record.deadlineNanos,
                )
            },
        )
    }

    private fun onCommandDeadline(
        commandId: String,
        digest: String,
        deadlineNanos: Long,
    ) {
        val record = synchronized(lock) {
            commands[commandId]?.takeIf {
                it.intentDigestSha256 == digest &&
                    it.deadlineNanos == deadlineNanos &&
                    it.state == CommandRecordState.EXECUTING
            }
        } ?: return
        if (monotonicClock.nowNanos() < deadlineNanos) {
            if (!scheduleCommandDeadline(record)) {
                val expired = monotonicClock.nowNanos() >= deadlineNanos
                terminateCommandForDeadline(
                    record,
                    if (expired) "command_ttl_expired" else "command_deadline_unavailable",
                    timedOut = expired,
                    latchUnknownActuation = true,
                )
            }
            return
        }
        terminateCommandForDeadline(
            record,
            "command_ttl_expired",
            timedOut = true,
            latchUnknownActuation = true,
        )
    }

    private fun terminateCommandForDeadline(
        record: CommandRecord,
        reason: String,
        timedOut: Boolean = false,
        latchUnknownActuation: Boolean = false,
        afterBarrierInvoked: () -> Unit = {},
    ) {
        val result =
            ConsoleCommandResult(
                record.command.commandId,
                if (timedOut) ConsoleCommandOutcome.TIMED_OUT else ConsoleCommandOutcome.FAILED,
                reason,
                null,
            )
        var deadlineTask: ConsoleScheduledTask? = null
        var revokedLease = false
        val actionLease: ActiveLease
        val neutral = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (commands[record.command.commandId] !== record ||
                    record.state != CommandRecordState.EXECUTING
                ) {
                    return
                }
                record.state = CommandRecordState.TERMINATING
                record.executionArmed = false
                deadlineTask = record.deadlineTask
                record.deadlineTask = null
                actionLease = checkNotNull(record.actionLease)
                if (activeLease === actionLease) {
                    activeLease = null
                    revokedLease = true
                }

                val oldToken = record.barrierToken
                val plan =
                    if (oldToken != null && neutralCompletedToken == oldToken) {
                        if (neutralBarrierToken == oldToken) {
                            neutralBarrierToken = null
                            activeNeutralPlan = null
                            neutralInvocationToken = null
                            neutralCompletedToken = null
                        }
                        checkNotNull(
                            prepareNeutralLocked(
                                actionLease,
                                ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
                                forceBarrier = true,
                            ),
                        )
                    } else {
                        activeNeutralPlan
                            ?: checkNotNull(
                                prepareNeutralLocked(
                                    actionLease,
                                    ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
                                    forceBarrier = true,
                                ),
                            )
                    }
                val deadlinePlan =
                    plan.copy(
                        holdBarrierOnSuccess = false,
                        ownerCommandId = null,
                        completion = null,
                    )
                activeNeutralPlan = deadlinePlan
                record.barrierToken = deadlinePlan.token
                deadlinePlan
            }
        }

        val finishAfterBarrier = {
            cancelBestEffort(deadlineTask)
            cancelLeaseTasks(actionLease)
            if (revokedLease) {
                recordBestEffort(
                    audit(
                        ConsoleAuditKind.LEASE_RELEASED,
                        record.sessionId,
                        actionLease.leaseId,
                        record.command.commandId,
                        outcome = "released_for_command_deadline",
                        reason = reason,
                    ),
                )
                if (synchronized(lock) { !closed }) {
                    emit(
                        record.sessionId,
                        ConsoleCoreEvent.LeaseChanged(
                            ConsoleLeaseState(
                                ConsoleLeaseStatus.RELEASED,
                                actionLease.leaseId,
                                null,
                                null,
                                null,
                            ),
                        ),
                    )
                }
            }
            runCatching(afterBarrierInvoked)
            finishTerminatingCommand(record, result, latchUnknownActuation)
        }
        executeNeutral(neutral, finishAfterBarrier)
    }

    /** Caller holds [lock]. Converts an already-invoked action into an unknown, neutralized end. */
    private fun terminateInvokedActionLocked(
        record: CommandRecord,
        trigger: ConsoleSafetyTrigger,
        reason: String,
    ): CommandAuthorityTermination {
        check(record.state == CommandRecordState.EXECUTING && record.executionInvoked)
        record.state = CommandRecordState.TERMINATING
        record.executionArmed = false
        val deadlineTask = record.deadlineTask
        record.deadlineTask = null
        val lease = checkNotNull(record.actionLease)
        val revokedLease = if (activeLease === lease) lease.also { activeLease = null } else null
        neutralFault = "discrete action outcome unknown after $reason"

        clearCompletedNeutralBarrierLocked()
        val result =
            ConsoleCommandResult(
                record.command.commandId,
                ConsoleCommandOutcome.FAILED,
                reason,
                "action outcome became unknown after server authority changed",
            )
        val plan =
            checkNotNull(
                prepareNeutralLocked(lease, trigger, forceBarrier = true),
            ).copy(
                holdBarrierOnSuccess = false,
                ownerCommandId = null,
                completion = { finishTerminatingCommand(record, result, latchUnknownActuation = true) },
            )
        activeNeutralPlan = plan
        record.barrierToken = plan.token
        return CommandAuthorityTermination(record, lease, revokedLease, plan, deadlineTask)
    }

    /** Caller holds [lock]. A completed pre-action barrier cannot satisfy a later authority loss. */
    private fun clearCompletedNeutralBarrierLocked() {
        val token = neutralBarrierToken ?: return
        if (neutralCompletedToken != token) return
        neutralBarrierToken = null
        activeNeutralPlan = null
        neutralInvocationToken = null
        neutralCompletedToken = null
    }

    private fun finishTerminatingCommand(
        record: CommandRecord,
        result: ConsoleCommandResult,
        latchUnknownActuation: Boolean,
    ) {
        val reserved = synchronized(lock) {
            if (commands[record.command.commandId] !== record ||
                record.state != CommandRecordState.TERMINATING
            ) {
                false
            } else {
                record.state = CommandRecordState.COMPLETING
                true
            }
        }
        if (!reserved) return
        val evidenceRecorded = recordBestEffort(
            audit(
                ConsoleAuditKind.COMMAND_COMPLETED,
                record.sessionId,
                record.command.leaseId,
                record.command.commandId,
                record.intentDigestSha256,
                result.outcome.name.lowercase(),
                result.reason,
                result.detail,
                authorityDecisionId = record.authorityDecisionId,
            ),
        )
        val publishedResult =
            if (evidenceRecorded) {
                result
            } else {
                ConsoleCommandResult(
                    record.command.commandId,
                    ConsoleCommandOutcome.FAILED,
                    "audit_unavailable",
                    "required terminal evidence unavailable",
                )
            }
        val shouldEmit = synchronized(lock) {
            if (commands[record.command.commandId] !== record ||
                record.state != CommandRecordState.COMPLETING
            ) {
                false
            } else {
                record.state = CommandRecordState.COMPLETED
                record.result = publishedResult
                if (activeDiscreteCommandId == record.command.commandId) {
                    activeDiscreteCommandId = null
                }
                if (latchUnknownActuation) {
                    neutralFault = "discrete action outcome unknown"
                }
                if (neutralBarrierToken == record.barrierToken &&
                    neutralCompletedToken == record.barrierToken
                ) {
                    neutralBarrierToken = null
                    activeNeutralPlan = null
                    neutralInvocationToken = null
                    neutralCompletedToken = null
                }
                !closed
            }
        }
        if (shouldEmit) emit(record.sessionId, ConsoleCoreEvent.CommandCompleted(publishedResult))
    }

    private fun scheduleLeaseDeadline(lease: ActiveLease): Boolean {
        val epoch = lease.leaseEpoch
        val scheduled =
            scheduleDeadlineWithoutInlineRecursion(
                deadlineNanos = lease.deadlineNanos,
                isCurrent = {
                    synchronized(lock) {
                        activeLease === lease && lease.leaseEpoch == epoch &&
                            lease.deadlineAttachPending && !closed &&
                            monotonicClock.nowNanos() < lease.deadlineNanos
                    }
                },
                attach = { task, callbackState ->
                    synchronized(lock) {
                        if (activeLease === lease && lease.leaseEpoch == epoch &&
                            lease.deadlineAttachPending && !closed &&
                            monotonicClock.nowNanos() < lease.deadlineNanos &&
                            callbackState.compareAndSet(
                                DEADLINE_CALLBACK_SCHEDULING,
                                DEADLINE_CALLBACK_ATTACHED,
                            )
                        ) {
                            lease.leaseTask = task
                            lease.deadlineAttachPending = false
                            true
                        } else {
                            callbackState.compareAndSet(
                                DEADLINE_CALLBACK_SCHEDULING,
                                DEADLINE_CALLBACK_EARLY,
                            )
                            false
                        }
                    }
                },
                onDeadline = { onLeaseDeadline(lease.leaseId, epoch) },
            )
        if (!scheduled) {
            failLeaseDeadline(
                lease,
                if (monotonicClock.nowNanos() >= lease.deadlineNanos) {
                    "lease_ttl_expired"
                } else {
                    "lease_deadline_unavailable"
                },
            )
        }
        return scheduled
    }

    private fun scheduleControlDeadline(
        lease: ActiveLease,
        inputVersion: Long,
        deadlineNanos: Long,
        reportFailureAck: Boolean = false,
    ): Boolean {
        val scheduled =
            scheduleDeadlineWithoutInlineRecursion(
                deadlineNanos = deadlineNanos,
                isCurrent = {
                    synchronized(lock) {
                        activeLease === lease && lease.inputVersion == inputVersion &&
                            lease.neutralizedControlEpoch != lease.controlEpoch &&
                            lease.controlDeadlineNanos == deadlineNanos && !closed &&
                            monotonicClock.nowNanos() < deadlineNanos
                    }
                },
                attach = { task, callbackState ->
                    synchronized(lock) {
                        if (activeLease === lease && lease.inputVersion == inputVersion &&
                            lease.neutralizedControlEpoch != lease.controlEpoch &&
                            lease.controlDeadlineNanos == deadlineNanos && !closed &&
                            monotonicClock.nowNanos() < deadlineNanos &&
                            callbackState.compareAndSet(
                                DEADLINE_CALLBACK_SCHEDULING,
                                DEADLINE_CALLBACK_ATTACHED,
                            )
                        ) {
                            lease.controlTask = task
                            true
                        } else {
                            callbackState.compareAndSet(
                                DEADLINE_CALLBACK_SCHEDULING,
                                DEADLINE_CALLBACK_EARLY,
                            )
                            false
                        }
                    }
                },
                onDeadline = { onControlDeadline(lease.leaseId, inputVersion, deadlineNanos) },
            )
        if (!scheduled) failControlDeadline(lease, inputVersion, reportFailureAck)
        return scheduled
    }

    /**
     * Some scheduler implementations are allowed to invoke a task before [scheduleAt] returns.
     * Convert that inline callback into one bounded replacement attempt instead of recursively
     * calling another scheduler operation on the same stack. A second inline callback fails
     * closed and lets the owning lease/command/control path neutralize its authority.
     */
    private fun scheduleDeadlineWithoutInlineRecursion(
        deadlineNanos: Long,
        isCurrent: () -> Boolean,
        attach: (ConsoleScheduledTask, AtomicInteger) -> Boolean,
        onDeadline: () -> Unit,
    ): Boolean {
        var synchronousReplacementCount = 0
        while (true) {
            val callbackState = AtomicInteger(DEADLINE_CALLBACK_SCHEDULING)
            val candidate =
                try {
                    scheduler.scheduleAt(deadlineNanos) {
                        if (callbackState.compareAndSet(
                                DEADLINE_CALLBACK_SCHEDULING,
                                DEADLINE_CALLBACK_EARLY,
                            )
                        ) {
                            return@scheduleAt
                        }
                        if (callbackState.get() == DEADLINE_CALLBACK_ATTACHED) onDeadline()
                    }
                } catch (_: Exception) {
                    return false
                }
            if (attach(candidate, callbackState)) return true

            cancelBestEffort(candidate)
            if (callbackState.get() != DEADLINE_CALLBACK_EARLY || !isCurrent()) return false
            if (synchronousReplacementCount >= MAX_SYNCHRONOUS_DEADLINE_REPLACEMENTS) return false
            synchronousReplacementCount += 1
        }
    }

    private fun failLeaseDeadline(lease: ActiveLease, reason: String) {
        var commandTermination: CommandAuthorityTermination? = null
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (activeLease !== lease) {
                    null
                } else {
                    val invokedRecord =
                        activeDiscreteCommandId?.let(commands::get)
                            ?.takeIf {
                                it.state == CommandRecordState.EXECUTING &&
                                    it.executionInvoked && it.actionLease === lease
                            }
                    commandTermination =
                        invokedRecord?.let {
                            terminateInvokedActionLocked(
                                it,
                                ConsoleSafetyTrigger.LEASE_EXPIRED,
                                reason,
                            )
                        }
                    if (commandTermination != null) {
                        LeaseTransition(lease, commandTermination?.neutral)
                    } else {
                        activeLease = null
                        LeaseTransition(lease, prepareNeutralLocked(lease, ConsoleSafetyTrigger.LEASE_EXPIRED))
                    }
                }
            }
        }
        cancelBestEffort(commandTermination?.deadlineTask)
        transition?.let { expireLease(it, reason) }
    }

    private fun failControlDeadline(
        lease: ActiveLease,
        inputVersion: Long,
        reportFailureAck: Boolean,
    ) {
        val plan = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (activeLease !== lease || lease.inputVersion != inputVersion) null
                else prepareNeutralLocked(lease, ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED)
            }
        }
        val afterBarrierInvoked = {
            if (reportFailureAck) {
                lease.lastInputSequence?.let { sequence ->
                    refusedControl(
                        lease.holderSessionId,
                        ConsoleControlFrame(
                            lease.leaseId,
                            sequence,
                            config.minimumControlTtlMillis,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                        ),
                        "control_deadline_unavailable",
                        null,
                    )
                }
            }
            Unit
        }
        plan?.let { executeNeutral(it, afterBarrierInvoked) } ?: afterBarrierInvoked()
    }

    private fun onLeaseDeadline(leaseId: String, leaseEpoch: Long) {
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val now = monotonicClock.nowNanos()
                val lease = activeLease
                if (closed || lease == null || lease.leaseId != leaseId || lease.leaseEpoch != leaseEpoch) {
                    null
                } else if (now < lease.deadlineNanos) {
                    lease.leaseTask = null
                    lease.deadlineAttachPending = true
                    LeaseDeadlineEarly(lease)
                } else {
                    val invokedRecord =
                        activeDiscreteCommandId?.let(commands::get)
                            ?.takeIf {
                                it.state == CommandRecordState.EXECUTING &&
                                    it.executionInvoked && it.actionLease === lease
                            }
                    if (invokedRecord != null) {
                        LeaseDeadlineActionTermination(
                            terminateInvokedActionLocked(
                                invokedRecord,
                                ConsoleSafetyTrigger.LEASE_EXPIRED,
                                "lease_ttl_expired",
                            ),
                        )
                    } else {
                        activeLease = null
                        LeaseDeadlineExpired(
                            LeaseTransition(
                                lease,
                                prepareNeutralLocked(lease, ConsoleSafetyTrigger.LEASE_EXPIRED),
                            ),
                        )
                    }
                }
            }
        }
        when (transition) {
            is LeaseDeadlineEarly -> scheduleLeaseDeadline(transition.lease)
            is LeaseDeadlineExpired -> expireLease(transition.transition)
            is LeaseDeadlineActionTermination -> {
                cancelBestEffort(transition.termination.deadlineTask)
                expireLease(
                    LeaseTransition(
                        transition.termination.actionLease,
                        transition.termination.neutral,
                    ),
                )
            }
            null -> Unit
        }
    }

    private fun onControlDeadline(
        leaseId: String,
        inputVersion: Long,
        deadlineNanos: Long,
    ) {
        val action = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val now = monotonicClock.nowNanos()
                val lease = activeLease
                if (closed || lease == null || lease.leaseId != leaseId || lease.inputVersion != inputVersion) {
                    null
                } else if (now < deadlineNanos) {
                    ControlDeadlineEarly(lease, inputVersion, deadlineNanos)
                } else {
                    ControlDeadlineExpired(prepareNeutralLocked(lease, ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED))
                }
            }
        }
        when (action) {
            is ControlDeadlineEarly ->
                scheduleControlDeadline(
                    action.lease,
                    action.inputVersion,
                    action.deadlineNanos,
                    reportFailureAck = true,
                )
            is ControlDeadlineExpired -> action.plan?.let(::executeNeutral)
            null -> Unit
        }
    }

    /** Synchronous fail-closed check for delayed schedulers before handling any new input. */
    private fun expireDueWork() {
        val expiredCommissioning = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val current = commissioningLifecycle.currentSession()
                if (current != null &&
                    commissioningLifecycle.expiryDecision(current, monotonicClock.nowNanos()) ==
                    ConsoleCommissioningExpiryDecision.DUE
                ) {
                    prepareCommissioningTerminationLocked(
                        current,
                        ConsoleCommissioningTerminationReason.TTL_EXPIRED,
                    ).transition
                } else {
                    null
                }
            }
        }
        expiredCommissioning?.let(::finishCommissioningTermination)

        val due = synchronized(actuationDispatchLock) {
            synchronized(lock) state@{
                val now = monotonicClock.nowNanos()
                val lease = activeLease ?: return@state null
                when {
                    now >= lease.deadlineNanos -> {
                        val invokedRecord =
                            activeDiscreteCommandId?.let(commands::get)
                                ?.takeIf {
                                    it.state == CommandRecordState.EXECUTING &&
                                        it.executionInvoked && it.actionLease === lease
                                }
                        if (invokedRecord != null) {
                            DueWork.CommandLeaseTermination(
                                terminateInvokedActionLocked(
                                    invokedRecord,
                                    ConsoleSafetyTrigger.LEASE_EXPIRED,
                                    "lease_ttl_expired",
                                ),
                            )
                        } else {
                            activeLease = null
                            DueWork.Lease(
                                LeaseTransition(
                                    lease,
                                    prepareNeutralLocked(lease, ConsoleSafetyTrigger.LEASE_EXPIRED),
                                ),
                            )
                        }
                    }
                    lease.controlDeadlineNanos != null && now >= checkNotNull(lease.controlDeadlineNanos) ->
                        DueWork.Control(prepareNeutralLocked(lease, ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED))
                    else -> null
                }
            }
        }
        when (due) {
            is DueWork.Lease -> expireLease(due.transition)
            is DueWork.CommandLeaseTermination -> {
                cancelBestEffort(due.termination.deadlineTask)
                expireLease(LeaseTransition(due.termination.actionLease, due.termination.neutral))
            }
            is DueWork.Control -> due.plan?.let(::executeNeutral)
            null -> Unit
        }
    }

    private fun expireLease(
        transition: LeaseTransition,
        reason: String = "lease_ttl_expired",
    ) {
        val afterBarrierInvoked = {
            cancelLeaseTasks(transition.lease)
            recordBestEffort(
                audit(
                    ConsoleAuditKind.LEASE_EXPIRED,
                    transition.lease.holderSessionId,
                    transition.lease.leaseId,
                    outcome = "expired",
                    reason = reason,
                ),
            )
            if (synchronized(lock) { !closed }) {
                emit(
                    transition.lease.holderSessionId,
                    ConsoleCoreEvent.LeaseChanged(
                        ConsoleLeaseState(
                            ConsoleLeaseStatus.EXPIRED,
                            transition.lease.leaseId,
                            null,
                            null,
                            reason,
                        ),
                    ),
                )
            }
        }
        transition.neutral?.let { executeNeutral(it, afterBarrierInvoked) } ?: afterBarrierInvoked()
    }

    /** Caller holds dispatch -> state locks. A terminal authority loss needs new neutral work. */
    private fun prepareFreshNeutralLocked(
        lease: ActiveLease,
        trigger: ConsoleSafetyTrigger,
    ): NeutralPlan {
        neutralBarrierToken = null
        activeNeutralPlan = null
        neutralInvocationToken = null
        neutralCompletedToken = null
        return checkNotNull(
            prepareNeutralLocked(
                lease,
                trigger,
                forceBarrier = true,
            ),
        )
    }

    private fun prepareNeutralLocked(
        lease: ActiveLease,
        trigger: ConsoleSafetyTrigger,
        acknowledgeControl: Boolean = false,
        forceBarrier: Boolean = false,
        holdBarrierOnSuccess: Boolean = false,
        clientRequestReason: ConsoleNeutralRequestReason? = null,
    ): NeutralPlan? {
        var clearedCompletedBarrier = false
        if (neutralBarrierToken != null) {
            if (neutralCompletedToken == neutralBarrierToken) {
                // A completed, held pre-action barrier only proves that control was neutral before
                // dispatch. It can never satisfy a later disconnect/readiness/expiry/stop trigger.
                clearCompletedNeutralBarrierLocked()
                clearedCompletedBarrier = true
            } else {
                return activeNeutralPlan
            }
        }
        if (!forceBarrier && !clearedCompletedBarrier && neutralFault == null &&
            lease.neutralizedControlEpoch == lease.controlEpoch
        ) {
            return null
        }
        lease.neutralizedControlEpoch = lease.controlEpoch
        lease.controlDeadlineNanos = null
        nextNeutralToken = nextCounter(nextNeutralToken, "neutral token")
        val token = nextNeutralToken
        neutralBarrierToken = token
        return NeutralPlan(
            token = token,
            leaseId = lease.leaseId,
            sessionId = lease.holderSessionId,
            lastInputSequence = lease.lastInputSequence,
            controlEpoch = lease.controlEpoch,
            trigger = trigger,
            clientRequestReason = clientRequestReason,
            acknowledgeControl = acknowledgeControl,
            holdBarrierOnSuccess = holdBarrierOnSuccess,
        ).also { activeNeutralPlan = it }
    }

    /** Caller holds [lock]; retries a failed barrier after its active lease has been revoked. */
    private fun prepareNeutralRetryLocked(
        context: UnresolvedNeutralContext,
        trigger: ConsoleSafetyTrigger,
    ): NeutralPlan {
        check(neutralBarrierToken == null) { "neutral retry requires an idle barrier" }
        nextNeutralToken = nextCounter(nextNeutralToken, "neutral token")
        val token = nextNeutralToken
        neutralBarrierToken = token
        return NeutralPlan(
            token = token,
            leaseId = context.leaseId,
            sessionId = context.sessionId,
            lastInputSequence = context.lastInputSequence,
            controlEpoch = context.controlEpoch,
            trigger = trigger,
        ).also { activeNeutralPlan = it }
    }

    private fun executeNeutral(
        plan: NeutralPlan,
        afterBarrierInvoked: () -> Unit = {},
    ) {
        val callback = DeferredCallback<ConsoleExecutionResult>()
        val invoked = synchronized(actuationDispatchLock) {
            val current = synchronized(lock) {
                if (neutralBarrierToken == plan.token && neutralInvocationToken != plan.token) {
                    neutralInvocationToken = plan.token
                    true
                } else {
                    false
                }
            }
            if (!current) {
                false
            } else {
                try {
                    executor.neutralize(
                        plan.leaseId,
                        plan.controlEpoch,
                        plan.trigger,
                        callback::complete,
                    )
                } catch (_: Exception) {
                    callback.complete(
                        ConsoleExecutionResult(false, "executor_failure", "neutralization failed"),
                    )
                }
                true
            }
        }

        var requestedAuditRecorded = false
        if (invoked) {
            requestedAuditRecorded =
                runCatching {
                    recordBestEffort(
                        audit(
                            ConsoleAuditKind.SAFETY_NEUTRAL_REQUESTED,
                            plan.sessionId,
                            plan.leaseId,
                            plan.lastInputSequence?.toString(),
                            outcome = "requested",
                            reason = plan.trigger.name.lowercase(),
                            clientRequestReason = plan.clientRequestReason?.name?.lowercase(),
                        ),
                    )
                }.getOrDefault(false)
        }
        runCatching(afterBarrierInvoked)
        if (!invoked) return

        val handler: (ConsoleExecutionResult) -> Unit = {
            completeNeutral(plan, it, requestedAuditRecorded)
        }
        val immediate = callback.open(handler)
        immediate?.let(handler)
    }

    private fun completeNeutral(
        plan: NeutralPlan,
        result: ConsoleExecutionResult,
        requestedAuditRecorded: Boolean,
    ) {
        if (synchronized(lock) { neutralBarrierToken != plan.token }) return
        val safeDetail = if (result.succeeded) result.detail else result.detail ?: "neutralization failed"
        val completionAuditRecorded =
            runCatching {
                recordBestEffort(
                    audit(
                        ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED,
                        plan.sessionId,
                        plan.leaseId,
                        plan.lastInputSequence?.toString(),
                        outcome = if (result.succeeded) "succeeded" else "failed",
                        reason = if (result.succeeded) plan.trigger.name.lowercase() else result.reason ?: "neutral_failed",
                        detail = safeDetail,
                        clientRequestReason = plan.clientRequestReason?.name?.lowercase(),
                    ),
                )
            }.getOrDefault(false)
        val confirmed = result.succeeded && requestedAuditRecorded && completionAuditRecorded
        var shutdownRetry: NeutralPlan? = null
        var signalShutdownCompletion = false
        val accepted = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (neutralBarrierToken != plan.token) {
                    false
                } else {
                    neutralCompletedToken = plan.token
                    val holdForActiveAction =
                        confirmed && plan.holdBarrierOnSuccess &&
                            plan.ownerCommandId != null &&
                            activeDiscreteCommandId == plan.ownerCommandId
                    if (confirmed) {
                        if (unresolvedNeutralContext?.matches(plan) == true) {
                            unresolvedNeutralContext = null
                        }
                    } else {
                        unresolvedNeutralContext = plan.toUnresolvedContext()
                        neutralFault =
                            if (result.succeeded) {
                                "neutralization evidence unavailable"
                            } else {
                                safeDetail ?: "neutralization failed"
                            }
                    }
                    if (!holdForActiveAction) {
                        neutralBarrierToken = null
                        activeNeutralPlan = null
                        neutralInvocationToken = null
                        neutralCompletedToken = null
                    }
                    if (closed && shutdownNeutralToken == plan.token) {
                        if (!confirmed && !shutdownRetryAttempted) {
                            shutdownRetryAttempted = true
                            val context = checkNotNull(unresolvedNeutralContext)
                            shutdownRetry =
                                prepareNeutralRetryLocked(context, ConsoleSafetyTrigger.SERVER_STOP)
                            shutdownNeutralToken = shutdownRetry?.token
                        } else {
                            shutdownNeutralSucceeded = confirmed
                            shutdownNeutralFinished = true
                            signalShutdownCompletion = shutdownEvidenceFinished
                        }
                    }
                    true
                }
            }
        }
        if (!accepted) return

        if (plan.acknowledgeControl && plan.lastInputSequence != null && synchronized(lock) { !closed }) {
            emit(
                plan.sessionId,
                ConsoleCoreEvent.ControlAcknowledged(
                    ConsoleControlAck(
                        plan.leaseId,
                        plan.lastInputSequence,
                        if (result.succeeded) ConsoleControlStatus.APPLIED else ConsoleControlStatus.REJECTED,
                        if (result.succeeded) null else result.reason ?: "neutral_failed",
                    ),
                ),
            )
        }
        emit(
            null,
            ConsoleCoreEvent.SafetyActionObserved(
                ConsoleSafetyAction(
                    plan.trigger,
                    plan.leaseId,
                    plan.lastInputSequence,
                    result.succeeded,
                    safeDetail,
                ),
            ),
        )
        val completionResult =
            if (plan.holdBarrierOnSuccess && result.succeeded &&
                (!requestedAuditRecorded || !completionAuditRecorded)
            ) {
                ConsoleExecutionResult(false, "audit_unavailable", "required evidence unavailable")
            } else {
                result
            }
        runCatching { plan.completion?.invoke(completionResult) }
        shutdownRetry?.let(::executeNeutral)
        if (signalShutdownCompletion) shutdownNeutralCompleted.countDown()
    }

    private fun cancelLeaseTasks(lease: ActiveLease) {
        val tasks = synchronized(lock) {
            listOfNotNull(lease.leaseTask, lease.controlTask).also {
                lease.leaseTask = null
                lease.controlTask = null
            }
        }
        tasks.forEach(::cancelBestEffort)
    }

    private fun cancelBestEffort(task: ConsoleScheduledTask?) {
        runCatching { task?.cancel() }
    }

    private fun cancelControlTask(lease: ActiveLease) {
        val task = synchronized(lock) { lease.controlTask.also { lease.controlTask = null } }
        cancelBestEffort(task)
    }

    private fun replayCommand(sessionId: String, record: CommandRecord): ConsoleCommandAck {
        recordBestEffort(
            audit(
                ConsoleAuditKind.COMMAND_ADMITTED,
                sessionId,
                record.command.leaseId,
                record.command.commandId,
                record.intentDigestSha256,
                "duplicate_replay",
                authorityDecisionId = record.authorityDecisionId,
            ),
        )
        if (synchronized(lock) { !closed && sessionId in sessions }) {
            emit(sessionId, ConsoleCoreEvent.CommandAcknowledged(record.ack))
            record.result?.let { emit(sessionId, ConsoleCoreEvent.CommandCompleted(it)) }
        }
        return record.ack
    }

    private fun evictOldestCompletedCommandLocked() {
        // Never forget an id that could still be admitted on the current lease: that would permit
        // a delayed replay to execute twice. Receipts from an older, boot-unique lease are safe to
        // evict because that intent can no longer pass new-command admission.
        val currentLeaseId = activeLease?.leaseId
        val completedId =
            commands.entries
                .firstOrNull {
                    it.value.state == CommandRecordState.COMPLETED &&
                        it.value.command.leaseId != currentLeaseId
                }?.key
        if (completedId != null) commands.remove(completedId)
    }

    private fun heldLease(lease: ActiveLease, now: Long): ConsoleLeaseState =
        ConsoleLeaseState(
            ConsoleLeaseStatus.HELD,
            lease.leaseId,
            lease.holderSessionId,
            remainingMillis(lease.deadlineNanos, now),
            null,
        )

    private fun audit(
        kind: ConsoleAuditKind,
        sessionId: String? = null,
        leaseId: String? = null,
        subjectId: String? = null,
        intentDigestSha256: String? = null,
        outcome: String,
        reason: String? = null,
        detail: String? = null,
        authorityDecisionId: String? = null,
        clientRequestReason: String? = null,
    ): ConsoleAuditEvent =
        ConsoleAuditEvent(
            kind = kind,
            timestampEpochMillis = epochClock.nowMillis(),
            monotonicNanos = monotonicClock.nowNanos(),
            sessionId = sessionId,
            leaseId = leaseId,
            subjectId = subjectId,
            intentDigestSha256 = intentDigestSha256,
            outcome = outcome,
            reason = reason,
            detail = detail,
            authorityDecisionId = authorityDecisionId,
            clientRequestReason = clientRequestReason,
        )

    private fun recordRequired(
        event: ConsoleAuditEvent,
        terminalizeOnFailure: Boolean = true,
    ): Boolean {
        val recorded =
            try {
                auditSink.record(event)
                true
            } catch (_: Exception) {
                false
            }
        if (!recorded) {
            val firstFailure = synchronized(lock) {
                if (auditFault) {
                    false
                } else {
                    auditFault = true
                    true
                }
            }
            if (firstFailure && terminalizeOnFailure) terminalizeAuthorityAfterAuditFault()
            return false
        }
        emit(null, ConsoleCoreEvent.AuditRecorded(event))
        return true
    }

    /** Safety work proceeds, but a missing evidence record permanently latches actuation closed. */
    private fun recordBestEffort(
        event: ConsoleAuditEvent,
        terminalizeOnFailure: Boolean = true,
    ): Boolean = recordRequired(event, terminalizeOnFailure)

    /**
     * First required-audit failure is a process-lifetime actuation fault. This transition performs
     * no required terminal audit, avoiding recursive failure; neutral invocation is still allowed
     * to make its existing best-effort safety evidence attempts.
     */
    private fun terminalizeAuthorityAfterAuditFault() {
        var commissioning: CommissioningTermination? = null
        var fallback: AuditFaultTransition? = null
        synchronized(actuationDispatchLock) {
            synchronized(lock) {
                // The in-flight terminal transition already owns its exact neutral plan and any
                // command completion callback. Re-entering here would replace that plan, lose the
                // callback, and invoke neutral twice. The audit fault itself is already latched.
                if (commissioningTransitionTokens.isNotEmpty()) {
                    auditTerminalizationDeferred = true
                    return
                }
                commissioning =
                    prepareCommissioningTerminationLocked(
                        expectedSession = null,
                        reason = ConsoleCommissioningTerminationReason.AUDIT_UNAVAILABLE,
                    ).transition
                if (commissioning == null) {
                    pendingLease = null
                    val terminatingRecord =
                        activeDiscreteCommandId?.let(commands::get)
                            ?.takeIf { it.state == CommandRecordState.TERMINATING }
                    val invokedRecord =
                        activeDiscreteCommandId?.let(commands::get)
                            ?.takeIf {
                                it.state == CommandRecordState.EXECUTING && it.executionInvoked
                            }
                    val commandTermination =
                        invokedRecord?.let {
                            terminateInvokedActionLocked(
                                it,
                                ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
                                "audit_unavailable",
                            )
                        }
                    val revokedLease =
                        commandTermination?.revokedLease ?: activeLease?.also { activeLease = null }
                    val actionLease =
                        if (terminatingRecord == null) {
                            revokedLease ?: commandTermination?.actionLease
                                ?: activeDiscreteCommandId?.let(commands::get)?.actionLease
                        } else {
                            null
                        }
                    if (commandTermination == null && actionLease != null) {
                        clearCompletedNeutralBarrierLocked()
                    }
                    // A TERMINATING record already owns a fresh neutral plan whose completion
                    // closes the command. Replacing that plan here would stale its callback and
                    // strand the record forever. The audit fault still revokes any live lease;
                    // the owning transition remains responsible for its exact neutral barrier.
                    val neutral =
                        commandTermination?.neutral ?: actionLease?.let {
                            prepareFreshNeutralLocked(
                                it,
                                ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
                            )
                        }
                    fallback =
                        AuditFaultTransition(
                            revokedLease = revokedLease,
                            commandTermination = commandTermination,
                            neutral = neutral,
                        )
                }
            }
        }

        commissioning?.let {
            finishCommissioningTermination(it, recordTerminalAudit = false)
            return
        }
        fallback?.let { transition ->
            val afterBarrierInvoked: () -> Unit = {
                cancelBestEffort(transition.commandTermination?.deadlineTask)
                transition.commandTermination?.actionLease?.let(::cancelLeaseTasks)
                transition.revokedLease?.let(::cancelLeaseTasks)
                transition.revokedLease?.let { lease ->
                    if (synchronized(lock) { !closed }) {
                        emit(
                            lease.holderSessionId,
                            ConsoleCoreEvent.LeaseChanged(
                                ConsoleLeaseState(
                                    ConsoleLeaseStatus.RELEASED,
                                    lease.leaseId,
                                    null,
                                    null,
                                    "audit_unavailable",
                                ),
                            ),
                        )
                    }
                }
            }
            transition.neutral?.let { executeNeutral(it, afterBarrierInvoked) }
                ?: afterBarrierInvoked()
        }
    }

    private fun emit(targetSessionId: String?, event: ConsoleCoreEvent) {
        runCatching { eventSink.emit(targetSessionId, event) }
    }

    private fun deadlineAfter(nowNanos: Long, ttlMillis: Long): Long {
        val nanos = if (ttlMillis > Long.MAX_VALUE / NANOS_PER_MILLI) Long.MAX_VALUE else ttlMillis * NANOS_PER_MILLI
        return if (nanos > 0L && nowNanos > Long.MAX_VALUE - nanos) Long.MAX_VALUE else nowNanos + nanos
    }

    private fun remainingMillis(deadline: Long, now: Long): Long {
        if (deadline <= now) return 0L
        val nanos = deadline - now
        return nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI == 0L) 0L else 1L
    }

    private fun nextCounter(value: Long, name: String): Long {
        check(value != Long.MAX_VALUE) { "$name exhausted" }
        return value + 1L
    }

    private fun ConsoleActuationReadinessSnapshot.summaryCode(): String =
        listOf(
            adapter?.name?.lowercase() ?: "unavailable",
            aircraftConnection.name.lowercase(),
            actuationLock.name.lowercase(),
            operatingProfile?.name?.lowercase() ?: "no_profile",
        ).joinToString(":")

    private fun requireValidId(value: String, name: String) {
        require(protocolIdPattern.matches(value)) {
            "$name must match the console protocol identifier grammar"
        }
    }

    private class ActiveLease(
        val leaseId: String,
        val holderSessionId: String,
        val commissioningSession: ConsoleCommissioningSessionView?,
        var leaseEpoch: Long,
        var deadlineNanos: Long,
        var leaseTask: ConsoleScheduledTask? = null,
        var controlTask: ConsoleScheduledTask? = null,
        var controlDeadlineNanos: Long? = null,
        var lastInputSequence: Long? = null,
        var pendingControlSequence: Long? = null,
        var controlEpoch: Long,
        var inputVersion: Long = 0L,
        var neutralizedControlEpoch: Long? = null,
        var mutationPending: Boolean = false,
        var deadlineAttachPending: Boolean = false,
    )

    private class PendingLeaseReservation(
        val session: ConsoleSession,
        val authority: LeaseAuthority,
    )

    private enum class CommandRecordState { AUDITING, EXECUTING, TERMINATING, COMPLETING, COMPLETED }

    private class CommandRecord(
        val sessionId: String,
        val session: ConsoleSession,
        val command: ConsoleDiscreteCommand,
        val intentDigestSha256: String,
        val authorityDecisionId: String,
        val readinessEpoch: Long,
        val commissioningSession: ConsoleCommissioningSessionView?,
        val deadlineNanos: Long,
        var state: CommandRecordState = CommandRecordState.AUDITING,
        var ack: ConsoleCommandAck =
            ConsoleCommandAck(
                command.commandId,
                ConsoleCommandDecision.REJECTED,
                "command_in_progress",
                intentDigestSha256,
            ),
        var result: ConsoleCommandResult? = null,
        var deadlineTask: ConsoleScheduledTask? = null,
        var barrierToken: Long? = null,
        var actionLease: ActiveLease? = null,
        var executionArmed: Boolean = false,
        var executionInvoked: Boolean = false,
        var neutralResult: ConsoleExecutionResult? = null,
    )

    private sealed interface CommandReservation {
        data class New(val record: CommandRecord) : CommandReservation
        data class Replay(val record: CommandRecord) : CommandReservation
        data class Refused(val reason: String) : CommandReservation
    }

    private sealed interface LeaseAuthority {
        data object Mock : LeaseAuthority
        class Commissioning(val session: ConsoleCommissioningSessionView) : LeaseAuthority
    }

    private sealed interface DiscreteDispatchAttempt {
        data object INVOKED : DiscreteDispatchAttempt
        data object NO_LONGER_CURRENT : DiscreteDispatchAttempt
        data class Refused(val reason: String) : DiscreteDispatchAttempt
    }

    private data class CommittedControl(
        val lease: ActiveLease,
        val oldTask: ConsoleScheduledTask?,
        val admitted: AdmittedControlFrame,
        val inputVersion: Long,
        val readinessEpoch: Long,
        val commissioningSession: ConsoleCommissioningSessionView?,
    )

    private data class NeutralPlan(
        val token: Long,
        val leaseId: String,
        val sessionId: String,
        val lastInputSequence: Long?,
        val controlEpoch: Long,
        val trigger: ConsoleSafetyTrigger,
        val clientRequestReason: ConsoleNeutralRequestReason? = null,
        val acknowledgeControl: Boolean = false,
        val holdBarrierOnSuccess: Boolean = false,
        val ownerCommandId: String? = null,
        val completion: ((ConsoleExecutionResult) -> Unit)? = null,
    )

    private data class UnresolvedNeutralContext(
        val leaseId: String,
        val sessionId: String,
        val lastInputSequence: Long?,
        val controlEpoch: Long,
    ) {
        fun matches(plan: NeutralPlan): Boolean =
            leaseId == plan.leaseId && sessionId == plan.sessionId &&
                lastInputSequence == plan.lastInputSequence && controlEpoch == plan.controlEpoch
    }

    private fun NeutralPlan.toUnresolvedContext(): UnresolvedNeutralContext =
        UnresolvedNeutralContext(
            leaseId = leaseId,
            sessionId = sessionId,
            lastInputSequence = lastInputSequence,
            controlEpoch = controlEpoch,
        )

    private data class LeaseTransition(val lease: ActiveLease, val neutral: NeutralPlan?)
    private data class DiscreteSafetyTransition(
        val lease: ActiveLease,
        val neutral: NeutralPlan,
        val releasesLease: Boolean,
    )
    private data class ReadinessTransition(
        val previous: ConsoleActuationReadinessSnapshot,
        val next: ConsoleActuationReadinessSnapshot,
        val revokedLease: ActiveLease?,
        val neutral: NeutralPlan?,
        val commandTermination: CommandAuthorityTermination? = null,
        val commissioningTermination: CommissioningTermination? = null,
    )
    private data class CommissioningStartSnapshot(
        val observation: ConsoleActuationObservation,
        val readinessEpoch: Long,
        val observationEpoch: Long,
        val commissioningAuthorityEpoch: Long,
        val operatorSession: ConsoleSession?,
    )
    private data class PreparedCommissioningTermination(
        val transition: CommissioningTermination?,
        val stale: Boolean,
    )
    private data class CommissioningTerminalProjection(
        val ownerSession: ConsoleSession,
        val session: ConsoleCommissioningSessionView,
        val reason: ConsoleCommissioningAuthorityReason,
    )
    private data class TargetedCommissioningAuthority(
        val ownerSession: ConsoleSession,
        val state: ConsoleCommissioningAuthorityState,
    )
    private data class CommissioningTermination(
        val session: ConsoleCommissioningSessionView,
        val reason: ConsoleCommissioningTerminationReason,
        val revokedLease: ActiveLease?,
        val affectedLease: ActiveLease?,
        val neutral: NeutralPlan?,
        val commandTermination: CommandAuthorityTermination?,
        val cancelledCommand: CancelledCommissioningCommand?,
        val deadlineTask: ConsoleScheduledTask?,
        val evidenceTransitionToken: Long,
        val neutralTransitionToken: Long?,
        val authority: TargetedCommissioningAuthority?,
    )
    private data class CancelledCommissioningCommand(
        val record: CommandRecord,
        val result: ConsoleCommandResult,
        val deadlineTask: ConsoleScheduledTask?,
    )
    private data class AuditFaultTransition(
        val revokedLease: ActiveLease?,
        val commandTermination: CommandAuthorityTermination?,
        val neutral: NeutralPlan?,
    )
    private enum class CommissioningDeadlineAttachDecision {
        ATTACHED,
        SCHEDULER_UNAVAILABLE,
        EXPIRED_CURRENT,
        STATE_CHANGED_OR_REVOKED,
    }
    private sealed interface CommissioningDeadlineAction {
        data object RESCHEDULE : CommissioningDeadlineAction
        data object STALE : CommissioningDeadlineAction
        data class Terminate(val transition: CommissioningTermination?) : CommissioningDeadlineAction
    }
    private data class CommandAuthorityTermination(
        val record: CommandRecord,
        val actionLease: ActiveLease,
        val revokedLease: ActiveLease?,
        val neutral: NeutralPlan,
        val deadlineTask: ConsoleScheduledTask?,
    )
    private sealed interface DueWork {
        data class Lease(val transition: LeaseTransition) : DueWork
        data class CommandLeaseTermination(val termination: CommandAuthorityTermination) : DueWork
        data class Control(val plan: NeutralPlan?) : DueWork
    }
    private data class LeaseDeadlineEarly(val lease: ActiveLease)
    private data class LeaseDeadlineExpired(val transition: LeaseTransition)
    private data class LeaseDeadlineActionTermination(val termination: CommandAuthorityTermination)
    private data class ControlDeadlineEarly(val lease: ActiveLease, val inputVersion: Long, val deadlineNanos: Long)
    private data class ControlDeadlineExpired(val plan: NeutralPlan?)

    /** Holds a synchronous executor callback until [open] runs outside the dispatch gate. */
    private class DeferredCallback<T : Any> {
        private val callbackLock = Any()
        private var open = false
        private var delivered = false
        private var pending: T? = null
        private var handler: ((T) -> Unit)? = null

        fun complete(value: T) {
            val callback = synchronized(callbackLock) {
                if (delivered) return
                if (!open) {
                    pending = value
                    null
                } else {
                    delivered = true
                    handler
                }
            }
            callback?.invoke(value)
        }

        /** Returns a callback received before opening; later callbacks go to [handler]. */
        fun open(handler: (T) -> Unit): T? =
            synchronized(callbackLock) {
                check(!open) { "callback gate is already open" }
                open = true
                val value = pending
                if (value == null) {
                    this.handler = handler
                    null
                } else {
                    pending = null
                    delivered = true
                    value
                }
            }
    }

    companion object {
        private const val ACTUATION_ADMISSION_CLOSED_REASON = "actuation_admission_closed"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_SYNCHRONOUS_DEADLINE_REPLACEMENTS = 1
        private const val DEADLINE_CALLBACK_SCHEDULING = 0
        private const val DEADLINE_CALLBACK_ATTACHED = 1
        private const val DEADLINE_CALLBACK_EARLY = 2
    }
}

class ConsoleAuditUnavailableException(message: String) : IllegalStateException(message)
