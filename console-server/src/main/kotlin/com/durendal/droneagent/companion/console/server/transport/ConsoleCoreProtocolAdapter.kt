package com.durendal.droneagent.companion.console.server.transport

import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.CommandAckPayload
import com.durendal.droneagent.companion.console.protocol.CommandDecision
import com.durendal.droneagent.companion.console.protocol.CommandResultPayload
import com.durendal.droneagent.companion.console.protocol.CommandResultStatus
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleServerPayload
import com.durendal.droneagent.companion.console.protocol.ControlAckPayload
import com.durendal.droneagent.companion.console.protocol.ControlAckStatus
import com.durendal.droneagent.companion.console.protocol.ControlFramePayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralPayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralReason
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import com.durendal.droneagent.companion.console.protocol.HealthPayload
import com.durendal.droneagent.companion.console.protocol.LeaseAcquirePayload
import com.durendal.droneagent.companion.console.protocol.LeaseReleasePayload
import com.durendal.droneagent.companion.console.protocol.LeaseRenewPayload
import com.durendal.droneagent.companion.console.protocol.LeaseState
import com.durendal.droneagent.companion.console.protocol.LeaseStatePayload
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload
import com.durendal.droneagent.companion.console.protocol.SafetyAction
import com.durendal.droneagent.companion.console.protocol.SafetyEventPayload
import com.durendal.droneagent.companion.console.protocol.SafetyOutcome
import com.durendal.droneagent.companion.console.protocol.SafetyTrigger
import com.durendal.droneagent.companion.console.protocol.ServerHelloPayload
import com.durendal.droneagent.companion.console.protocol.TelemetryPayload
import com.durendal.droneagent.companion.console.server.ConsoleCommandDecision
import com.durendal.droneagent.companion.console.server.ConsoleCommandOutcome
import com.durendal.droneagent.companion.console.server.ConsoleControlFrame
import com.durendal.droneagent.companion.console.server.ConsoleControlNeutral
import com.durendal.droneagent.companion.console.server.ConsoleControlStatus
import com.durendal.droneagent.companion.console.server.ConsoleCoreEvent
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleEventSink
import com.durendal.droneagent.companion.console.server.ConsoleLeaseState
import com.durendal.droneagent.companion.console.server.ConsoleLeaseStatus
import com.durendal.droneagent.companion.console.server.ConsoleNeutralRequestReason
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import com.durendal.droneagent.companion.console.server.ConsoleServerCore

/** Live snapshots are operational state and remain separate from hardware evidence truth. */
interface ConsoleSnapshotProvider {
    fun runtimeState(): RuntimeStatePayload

    fun capabilitySnapshot(): CapabilitySnapshotPayload

    fun health(): HealthPayload

    fun latestTelemetry(): TelemetryPayload?
}

/**
 * Maps the strict browser protocol onto [ConsoleServerCore]. The provider indirection lets the
 * composition root wire the core's asynchronous [ConsoleEventSink] back into this adapter without
 * making the safety core depend on transport types.
 */
class ConsoleCoreProtocolAdapter(
    private val coreProvider: () -> ConsoleServerCore,
    private val snapshots: ConsoleSnapshotProvider,
    private val serverVersion: String,
    private val emitPayload: (targetSessionId: String?, payload: ConsoleServerPayload) -> Boolean,
) : ConsoleClientSessionHandler, ConsoleEventSink {
    private val leaseRequestMessageId = ThreadLocal<String?>()

    init {
        require(serverVersion.isNotBlank()) { "serverVersion must not be blank" }
    }

    override fun onSessionOpened(sessionId: String) = Unit

    override fun onClientMessage(
        sessionId: String,
        message: ConsoleClientMessage,
    ) {
        when (val payload = message.payload) {
            is ClientHelloPayload -> handleHello(sessionId, payload)
            is LeaseAcquirePayload ->
                withLeaseRequest(message.messageId) {
                    coreProvider().acquireLease(sessionId, payload.requestedTtlMs.toLong())
                }

            is LeaseRenewPayload ->
                withLeaseRequest(message.messageId) {
                    coreProvider().renewLease(
                        sessionId,
                        payload.leaseId,
                        payload.requestedTtlMs.toLong(),
                    )
                }

            is LeaseReleasePayload ->
                withLeaseRequest(message.messageId) {
                    coreProvider().releaseLease(sessionId, payload.leaseId)
                }

            is DiscreteCommandRequestPayload ->
                coreProvider().handleDiscreteCommand(
                    sessionId,
                    ConsoleDiscreteCommand(
                        commandId = payload.commandId,
                        leaseId = payload.leaseId,
                        action = payload.action.toCore(),
                        ttlMillis = payload.ttlMs.toLong(),
                    ),
                )

            is ControlFramePayload ->
                coreProvider().handleControlFrame(
                    sessionId,
                    ConsoleControlFrame(
                        leaseId = payload.leaseId,
                        inputSequence = payload.inputSequence,
                        ttlMillis = payload.ttlMs.toLong(),
                        forward = payload.forward,
                        right = payload.right,
                        up = payload.up,
                        yaw = payload.yaw,
                    ),
                )

            is ControlNeutralPayload ->
                coreProvider().handleControlNeutral(
                    sessionId,
                    ConsoleControlNeutral(
                        leaseId = payload.leaseId,
                        inputSequence = payload.inputSequence,
                        reason = payload.reason.toCore(),
                    ),
                )
        }
    }

    override fun onSessionClosed(
        sessionId: String,
        reason: String,
    ) {
        coreProvider().disconnect(sessionId)
    }

    override fun emit(
        targetSessionId: String?,
        event: ConsoleCoreEvent,
    ) {
        when (event) {
            is ConsoleCoreEvent.LeaseChanged -> {
                val requestMessageId = leaseRequestMessageId.get()
                if (event.state.status == ConsoleLeaseStatus.DENIED) {
                    // A denial describes only the requesting client; publishing it would overwrite
                    // the actual global holder state in observers.
                    emitPayload(
                        requireNotNull(targetSessionId) { "lease denial requires a target session" },
                        event.state.toProtocol(requestMessageId),
                    )
                } else {
                    // Preserve synchronous request correlation for the requester, then publish a
                    // correlation-free global truth so every ready browser sees holder changes,
                    // forced releases and expiry. Async transitions have no ThreadLocal request and
                    // therefore need only the broadcast.
                    if (targetSessionId != null && requestMessageId != null) {
                        val delivered =
                            emitPayload(
                                targetSessionId,
                                event.state.toProtocol(requestMessageId),
                            )
                        // A failed HELD delivery synchronously closes that holder and can re-enter
                        // the core to publish RELEASED. Broadcasting the stale outer HELD after
                        // that re-entrant release would invert global truth for every observer.
                        if (!delivered && event.state.status == ConsoleLeaseStatus.HELD) return
                    }
                    emitPayload(null, event.state.toProtocol(requestMessageId = null))
                }
            }

            is ConsoleCoreEvent.CommandAcknowledged ->
                emitPayload(
                    requireNotNull(targetSessionId) { "command ack requires a target session" },
                    CommandAckPayload(
                        commandId = event.ack.commandId,
                        decision =
                            when (event.ack.decision) {
                                ConsoleCommandDecision.ACCEPTED -> CommandDecision.ACCEPTED
                                ConsoleCommandDecision.REJECTED -> CommandDecision.REJECTED
                            },
                        reason = event.ack.reason,
                        intentDigestSha256 = event.ack.intentDigestSha256,
                    ),
                )

            is ConsoleCoreEvent.CommandCompleted ->
                emitPayload(
                    requireNotNull(targetSessionId) { "command result requires a target session" },
                    CommandResultPayload(
                        commandId = event.result.commandId,
                        status =
                            when (event.result.outcome) {
                                ConsoleCommandOutcome.SUCCEEDED -> CommandResultStatus.SUCCEEDED
                                ConsoleCommandOutcome.FAILED -> CommandResultStatus.FAILED
                                ConsoleCommandOutcome.TIMED_OUT -> CommandResultStatus.TIMED_OUT
                                ConsoleCommandOutcome.CANCELLED -> CommandResultStatus.CANCELLED
                            },
                        reason = event.result.reason,
                        detail = event.result.detail,
                    ),
                )

            is ConsoleCoreEvent.ControlAcknowledged ->
                emitPayload(
                    requireNotNull(targetSessionId) { "control ack requires a target session" },
                    ControlAckPayload(
                        leaseId = event.ack.leaseId,
                        inputSequence = event.ack.inputSequence,
                        status = event.ack.status.toProtocol(),
                        reason = event.ack.reason,
                    ),
                )

            is ConsoleCoreEvent.SafetyActionObserved ->
                emitPayload(
                    null,
                    SafetyEventPayload(
                            action = SafetyAction.NEUTRALIZE,
                            outcome =
                                if (event.action.succeeded) {
                                    SafetyOutcome.SUCCEEDED
                                } else {
                                    SafetyOutcome.FAILED
                                },
                            trigger = event.action.trigger.toProtocol(),
                            leaseId = event.action.leaseId,
                            lastInputSequence = event.action.lastInputSequence,
                            detail = event.action.detail,
                    ),
                )

            is ConsoleCoreEvent.AuditRecorded -> Unit
        }
    }

    fun publishTelemetry(payload: TelemetryPayload): Boolean = emitPayload(null, payload)

    fun publishRuntimeState(payload: RuntimeStatePayload): Boolean = emitPayload(null, payload)

    fun publishHealth(payload: HealthPayload): Boolean = emitPayload(null, payload)

    private fun handleHello(
        sessionId: String,
        payload: ClientHelloPayload,
    ) {
        if (payload.authentication != null) {
            throw ConsoleSessionProtocolException(ProtocolErrorCode.UNEXPECTED_MESSAGE)
        }
        coreProvider().openSession(
            sessionId = sessionId,
            clientInstanceId = "${payload.clientName}:${payload.clientVersion}",
        )
        requireEmit(
            sessionId,
            ServerHelloPayload(
                sessionId = sessionId,
                serverVersion = serverVersion,
                selectedProtocolVersion = "1.0",
                authenticationRequired = false,
                acceptedAuthenticationSchemes = emptyList(),
            ),
        )
        requireEmit(sessionId, snapshots.runtimeState())
        requireEmit(sessionId, snapshots.capabilitySnapshot())
        requireEmit(sessionId, snapshots.health())
        snapshots.latestTelemetry()?.let { requireEmit(sessionId, it) }
        val lease = coreProvider().currentLease()
        requireEmit(
            sessionId,
            lease?.toProtocol(requestMessageId = null)
                ?: LeaseStatePayload(
                    requestMessageId = null,
                    state = LeaseState.AVAILABLE,
                    leaseId = null,
                    holderSessionId = null,
                    expiresInMs = null,
                    reason = null,
                ),
        )
    }

    private inline fun <T> withLeaseRequest(
        requestMessageId: String,
        action: () -> T,
    ): T {
        check(leaseRequestMessageId.get() == null) { "nested lease request correlation" }
        leaseRequestMessageId.set(requestMessageId)
        return try {
            action()
        } finally {
            leaseRequestMessageId.remove()
        }
    }

    private fun requireEmit(
        targetSessionId: String,
        payload: ConsoleServerPayload,
    ) {
        check(emitPayload(targetSessionId, payload)) { "console transport unavailable" }
    }

    private fun ConsoleLeaseState.toProtocol(requestMessageId: String?): LeaseStatePayload =
        LeaseStatePayload(
            requestMessageId = requestMessageId,
            state =
                when (status) {
                    ConsoleLeaseStatus.HELD -> LeaseState.HELD
                    ConsoleLeaseStatus.DENIED -> LeaseState.DENIED
                    ConsoleLeaseStatus.RELEASED -> LeaseState.RELEASED
                    ConsoleLeaseStatus.EXPIRED -> LeaseState.EXPIRED
                },
            leaseId = leaseId,
            holderSessionId = holderSessionId,
            expiresInMs = expiresInMillis,
            reason = reason,
        )

    private fun DiscreteCommandAction.toCore(): ConsoleDiscreteAction =
        when (this) {
            DiscreteCommandAction.TAKEOFF -> ConsoleDiscreteAction.TAKEOFF
            DiscreteCommandAction.LANDING -> ConsoleDiscreteAction.LANDING
            DiscreteCommandAction.RETURN_TO_HOME -> ConsoleDiscreteAction.RETURN_TO_HOME
        }

    private fun ControlNeutralReason.toCore(): ConsoleNeutralRequestReason =
        when (this) {
            ControlNeutralReason.OPERATOR_RELEASE -> ConsoleNeutralRequestReason.OPERATOR_RELEASE
            ControlNeutralReason.POINTER_CANCEL -> ConsoleNeutralRequestReason.POINTER_CANCEL
            ControlNeutralReason.WINDOW_BLUR -> ConsoleNeutralRequestReason.WINDOW_BLUR
            ControlNeutralReason.PAGE_HIDE -> ConsoleNeutralRequestReason.PAGE_HIDE
        }

    private fun ConsoleControlStatus.toProtocol(): ControlAckStatus =
        when (this) {
            ConsoleControlStatus.APPLIED -> ControlAckStatus.APPLIED
            ConsoleControlStatus.REJECTED -> ControlAckStatus.REJECTED
            ConsoleControlStatus.STALE -> ControlAckStatus.STALE
        }

    private fun ConsoleSafetyTrigger.toProtocol(): SafetyTrigger =
        when (this) {
            ConsoleSafetyTrigger.CLIENT_REQUEST -> SafetyTrigger.CLIENT_REQUEST
            ConsoleSafetyTrigger.CLIENT_DISCONNECT -> SafetyTrigger.CLIENT_DISCONNECT
            ConsoleSafetyTrigger.LEASE_EXPIRED -> SafetyTrigger.LEASE_EXPIRED
            ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED -> SafetyTrigger.CONTROL_TTL_EXPIRED
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST -> SafetyTrigger.ACTUATION_READINESS_LOST
            ConsoleSafetyTrigger.SERVER_STOP -> SafetyTrigger.SERVER_STOP
        }
}
