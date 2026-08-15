package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.core.control.BodyFrameVelocityCommand
import com.durendal.droneagent.core.control.CommandEnvelope
import com.durendal.droneagent.core.control.CommandSaturationGate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Must return a process-lifetime unique protocol identifier; production uses UUIDs. */
    private val leaseIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val lock = Any()
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
    private var pendingLeaseSessionId: String? = null
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
    private var shutdownNeutralToken: Long? = null
    private var shutdownNeutralSucceeded = false
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
     * Replace host-owned runtime truth. Browser messages have no path to this method. Every actual
     * context change advances an internal epoch, revokes an active lease and enters neutral before
     * the method returns. That epoch also fences commands which are waiting on an asynchronous
     * neutral callback, including a locked -> ready ABA transition.
     */
    fun updateActuationReadiness(next: ConsoleActuationReadinessSnapshot): Boolean {
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (closed || next == actuationReadiness) {
                    null
                } else {
                    val previous = actuationReadiness
                    actuationReadiness = next
                    readinessEpoch = nextCounter(readinessEpoch, "actuation readiness epoch")
                    pendingLeaseSessionId = null

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

        val afterBarrierInvoked = {
            transition.revokedLease?.let(::cancelLeaseTasks)
            transition.commandTermination?.deadlineTask?.cancel()
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
        val denial = synchronized(lock) {
            when {
                closed -> "server_closed"
                actuationAdmissionClosed.get() -> ACTUATION_ADMISSION_CLOSED_REASON
                sessionId !in sessions -> "unknown_session"
                requestedTtlMillis !in config.minimumLeaseTtlMillis..config.maximumLeaseTtlMillis -> "invalid_lease_ttl"
                auditFault -> "audit_unavailable"
                neutralFault != null -> "actuation_locked"
                !actuationReadiness.allowsLease(sessionId) -> "actuation_not_ready"
                activeDiscreteCommandId != null -> "discrete_action_in_progress"
                neutralBarrierToken != null -> "neutral_in_progress"
                activeLease != null -> "lease_held"
                pendingLeaseSessionId != null -> "lease_pending"
                else -> {
                    pendingLeaseSessionId = sessionId
                    null
                }
            }
        }
        if (denial != null) return refusedLease(sessionId, denial)

        val leaseId =
            try {
                leaseIdFactory().also { requireValidId(it, "leaseId") }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (pendingLeaseSessionId == sessionId) pendingLeaseSessionId = null
                }
                return refusedLease(sessionId, "lease_id_unavailable")
            }
        val candidate = synchronized(lock) {
            nextLeaseEpoch = nextCounter(nextLeaseEpoch, "lease epoch")
            nextControlEpoch = nextCounter(nextControlEpoch, "control epoch")
            ActiveLease(
                leaseId = leaseId,
                holderSessionId = sessionId,
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
                if (pendingLeaseSessionId == sessionId) pendingLeaseSessionId = null
            }
            return refusedLease(sessionId, "audit_unavailable")
        }

        val committed = synchronized(lock) {
            if (closed || actuationAdmissionClosed.get() || sessionId !in sessions || activeLease != null ||
                pendingLeaseSessionId != sessionId || !actuationReadiness.allowsLease(sessionId)
            ) {
                pendingLeaseSessionId = null
                false
            } else {
                pendingLeaseSessionId = null
                candidate.deadlineNanos =
                    deadlineAfter(monotonicClock.nowNanos(), requestedTtlMillis)
                activeLease = candidate
                true
            }
        }
        if (!committed) return refusedLease(sessionId, "state_changed")
        val held = heldLease(candidate, monotonicClock.nowNanos())
        emit(sessionId, ConsoleCoreEvent.LeaseChanged(held))
        if (!scheduleLeaseDeadline(candidate)) return refusedLease(sessionId, "lease_deadline_unavailable")
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
                !actuationReadiness.allowsLease(sessionId) -> null to "actuation_not_ready"
                lease == null || lease.leaseId != leaseId || lease.holderSessionId != sessionId -> null to "not_lease_holder"
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
                        !actuationReadiness.allowsLease(sessionId) -> {
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
                        oldTask = lease.leaseTask
                        lease.leaseTask = null
                        true
                    }
                }
            }
        }
        oldTask?.cancel()
        if (!committed) {
            commandTermination?.deadlineTask?.cancel()
            expiredTransition?.let(::expireLease)
            return refusedLease(
                sessionId,
                if (expiredTransition == null) "state_changed" else "lease_ttl_expired",
            )
        }
        val held = heldLease(lease, monotonicClock.nowNanos())
        emit(sessionId, ConsoleCoreEvent.LeaseChanged(held))
        if (!scheduleLeaseDeadline(lease)) return refusedLease(sessionId, "lease_deadline_unavailable")
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
        val afterBarrierInvoked = {
            cancelLeaseTasks(transition.lease)
            commandTermination?.deadlineTask?.cancel()
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
                                    command = command,
                                    intentDigestSha256 = decision.intentDigestSha256,
                                    authorityDecisionId = decision.authorityDecisionId,
                                    readinessEpoch = readinessEpoch,
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
                record.state != CommandRecordState.AUDITING || !rule.accepted ||
                record.readinessEpoch != readinessEpoch || monotonicClock.nowNanos() >= deadline
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
            terminateCommandForDeadline(
                commandRecord,
                reason = "command_deadline_unavailable",
                timedOut = false,
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
                    record.readinessEpoch != readinessEpoch ->
                        DiscreteDispatchAttempt.Refused("actuation_readiness_changed")
                    !actuationReadiness.allows(command.sessionId, command.command.action.actuationIntent) ->
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
                )
            }
        } ?: return refusedControl(
            sessionId,
            frame,
            "state_changed",
            decision.intentDigestSha256,
            decision.authorityDecisionId,
        )

        executable.oldTask?.cancel()
        if (!scheduleControlDeadline(executable.lease, executable.inputVersion, deadline)) {
            return refusedControl(
                sessionId,
                frame,
                "control_deadline_unavailable",
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
                    executable.readinessEpoch != readinessEpoch ||
                        !actuationReadiness.allows(
                            executable.admitted.sessionId,
                            ConsoleActuationIntent.VIRTUAL_STICK,
                        ) || neutralBarrierToken != null || activeDiscreteCommandId != null ||
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
        oldTask?.cancel()
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
        var disconnectNeutral: NeutralPlan? = null
        var commandTermination: CommandAuthorityTermination? = null
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                val removed = sessions.remove(sessionId) ?: return
                if (pendingLeaseSessionId == sessionId) pendingLeaseSessionId = null
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
        val afterBarrierInvoked = {
            transition?.let { cancelLeaseTasks(it.lease) }
            commandTermination?.deadlineTask?.cancel()
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
        return synchronized(lock) { activeLease?.let { heldLease(it, now) } }
    }

    override fun close() {
        closeActuationAdmission()
        var cancelledCommands: List<CommandRecord> = emptyList()
        var commandTasks: List<ConsoleScheduledTask> = emptyList()
        var stopNeutral: NeutralPlan? = null
        val transition = synchronized(actuationDispatchLock) {
            synchronized(lock) {
                if (closed) return
                closed = true
                pendingLeaseSessionId = null
                pendingSessions.clear()
                sessions.clear()
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
                if (stopNeutral == null) shutdownNeutralSucceeded = true
                lease?.let { LeaseTransition(it, stopNeutral) }
            }
        }
        val afterBarrierInvoked = {
            commandTasks.forEach(ConsoleScheduledTask::cancel)
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
            Unit
        }
        if (stopNeutral == null) {
            afterBarrierInvoked()
            shutdownNeutralCompleted.countDown()
        } else if (synchronized(lock) { neutralCompletedToken == stopNeutral?.token }) {
            afterBarrierInvoked()
            synchronized(lock) {
                shutdownNeutralSucceeded =
                    unresolvedNeutralContext?.matches(checkNotNull(stopNeutral)) != true
            }
            shutdownNeutralCompleted.countDown()
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
        deadlineTask?.cancel()
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
        var evidenceNeutral: NeutralPlan? = null
        var evidenceRevokedLease: ActiveLease? = null
        if (!evidenceRecorded) {
            synchronized(actuationDispatchLock) {
                synchronized(lock) {
                    neutralFault = "discrete action completion evidence unavailable"
                    val lease = record.actionLease
                    if (lease != null) {
                        if (activeLease === lease) {
                            activeLease = null
                            evidenceRevokedLease = lease
                        }
                        if (neutralBarrierToken == record.barrierToken &&
                            neutralCompletedToken == record.barrierToken
                        ) {
                            neutralBarrierToken = null
                            activeNeutralPlan = null
                            neutralInvocationToken = null
                            neutralCompletedToken = null
                        }
                        evidenceNeutral =
                            prepareNeutralLocked(
                                lease,
                                ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
                                forceBarrier = true,
                            )
                        record.barrierToken = evidenceNeutral?.token
                    }
                }
            }
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
                if (!preserveNeutralBarrier && evidenceNeutral == null && !neutralStillCompleting &&
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
        val afterBarrierInvoked = {
            evidenceRevokedLease?.let(::cancelLeaseTasks)
            if (shouldEmit) emit(record.sessionId, ConsoleCoreEvent.CommandCompleted(publishedResult))
        }
        evidenceNeutral?.let { executeNeutral(it, afterBarrierInvoked) } ?: afterBarrierInvoked()
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
                finalized = finalizeControlEvidence(control, execution, executionAck, neutralAlreadyInvoked = true)
            }
            return finalized
        }
        return finalizeControlEvidence(control, execution, executionAck, neutralAlreadyInvoked = false)
    }

    private fun finalizeControlEvidence(
        control: CommittedControl,
        execution: ConsoleExecutionResult,
        executionAck: ConsoleControlAck,
        neutralAlreadyInvoked: Boolean,
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
        var revokedLease: ActiveLease? = null
        var auditNeutral: NeutralPlan? = null
        if (!evidenceRecorded) {
            auditNeutral = synchronized(actuationDispatchLock) {
                synchronized(lock) {
                    if (activeLease === control.lease) {
                        activeLease = null
                        revokedLease = control.lease
                        prepareNeutralLocked(
                            control.lease,
                            ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
                            forceBarrier = activeNeutralPlan == null,
                        )
                    } else {
                        activeNeutralPlan
                    }
                }
            }
        }
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
        val emitAck = {
            revokedLease?.let(::cancelLeaseTasks)
            revokedLease?.let { lease ->
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
            if (synchronized(lock) { !closed }) {
                emit(control.admitted.sessionId, ConsoleCoreEvent.ControlAcknowledged(ack))
            }
        }
        if (auditNeutral != null && !neutralAlreadyInvoked) {
            executeNeutral(checkNotNull(auditNeutral), emitAck)
        } else {
            emitAck()
        }
        return ack
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
            !actuationReadiness.allows(sessionId, action.actuationIntent) ->
                CompanionAdmissionRule.reject("actuation_not_ready")
            activeDiscreteCommandId != null -> CompanionAdmissionRule.reject("discrete_action_in_progress")
            neutralBarrierToken != null -> CompanionAdmissionRule.reject("neutral_in_progress")
            activeLease?.leaseId != leaseId || activeLease?.holderSessionId != sessionId ->
                CompanionAdmissionRule.reject("not_lease_holder")
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
            !actuationReadiness.allows(sessionId, ConsoleActuationIntent.VIRTUAL_STICK) ->
                CompanionAdmissionRule.reject("actuation_not_ready")
            activeDiscreteCommandId != null -> CompanionAdmissionRule.reject("discrete_action_in_progress")
            neutralBarrierToken != null -> CompanionAdmissionRule.reject("neutral_in_progress")
            lease == null || lease.leaseId != frame.leaseId || lease.holderSessionId != sessionId ->
                CompanionAdmissionRule.reject("not_lease_holder")
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

    private fun scheduleCommandDeadline(record: CommandRecord): Boolean {
        val task =
            try {
                scheduler.scheduleAt(record.deadlineNanos) {
                    onCommandDeadline(record.command.commandId, record.intentDigestSha256, record.deadlineNanos)
                }
            } catch (_: Exception) {
                return false
            }
        val keep = synchronized(lock) {
            if (commands[record.command.commandId] === record && record.state == CommandRecordState.EXECUTING) {
                record.deadlineTask = task
                true
            } else {
                false
            }
        }
        if (!keep) task.cancel()
        return keep
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
                terminateCommandForDeadline(
                    record,
                    "command_deadline_unavailable",
                    timedOut = false,
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
            deadlineTask?.cancel()
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
        val task =
            try {
                scheduler.scheduleAt(lease.deadlineNanos) { onLeaseDeadline(lease.leaseId, epoch) }
            } catch (_: Exception) {
                failLeaseDeadline(lease, "lease_deadline_unavailable")
                return false
            }
        val keep = synchronized(lock) {
            if (activeLease === lease && lease.leaseEpoch == epoch && !closed) {
                lease.leaseTask = task
                true
            } else {
                false
            }
        }
        if (!keep) task.cancel()
        return keep
    }

    private fun scheduleControlDeadline(
        lease: ActiveLease,
        inputVersion: Long,
        deadlineNanos: Long,
        reportFailureAck: Boolean = false,
    ): Boolean {
        val task =
            try {
                scheduler.scheduleAt(deadlineNanos) {
                    onControlDeadline(lease.leaseId, inputVersion, deadlineNanos)
                }
            } catch (_: Exception) {
                failControlDeadline(lease, inputVersion, reportFailureAck)
                return false
            }
        val keep = synchronized(lock) {
            if (activeLease === lease && lease.inputVersion == inputVersion &&
                lease.neutralizedControlEpoch != lease.controlEpoch &&
                lease.controlDeadlineNanos == deadlineNanos && !closed
            ) {
                lease.controlTask = task
                true
            } else {
                false
            }
        }
        if (!keep) task.cancel()
        return keep
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
        commandTermination?.deadlineTask?.cancel()
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
                transition.termination.deadlineTask?.cancel()
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
                due.termination.deadlineTask?.cancel()
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
                            signalShutdownCompletion = true
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
        tasks.forEach(ConsoleScheduledTask::cancel)
    }

    private fun cancelControlTask(lease: ActiveLease) {
        val task = synchronized(lock) { lease.controlTask.also { lease.controlTask = null } }
        task?.cancel()
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

    private fun recordRequired(event: ConsoleAuditEvent): Boolean {
        val recorded =
            try {
                auditSink.record(event)
                true
            } catch (_: Exception) {
                false
            }
        if (!recorded) {
            synchronized(lock) { auditFault = true }
            return false
        }
        emit(null, ConsoleCoreEvent.AuditRecorded(event))
        return true
    }

    /** Safety work proceeds, but a missing evidence record permanently latches actuation closed. */
    private fun recordBestEffort(event: ConsoleAuditEvent): Boolean = recordRequired(event)

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
    )

    private enum class CommandRecordState { AUDITING, EXECUTING, TERMINATING, COMPLETING, COMPLETED }

    private class CommandRecord(
        val sessionId: String,
        val command: ConsoleDiscreteCommand,
        val intentDigestSha256: String,
        val authorityDecisionId: String,
        val readinessEpoch: Long,
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
    )
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
    }
}

class ConsoleAuditUnavailableException(message: String) : IllegalStateException(message)
