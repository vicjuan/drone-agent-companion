package com.durendal.droneagent.companion.console.server

/** Server-generated identity. Browser supplied client ids are metadata only. */
data class ConsoleSession(
    val sessionId: String,
    val clientInstanceId: String?,
)

data class SessionOpened(val session: ConsoleSession)

enum class ConsoleDiscreteAction {
    TAKEOFF,
    LANDING,
    RETURN_TO_HOME,
}

data class ConsoleDiscreteCommand(
    val commandId: String,
    val leaseId: String,
    val action: ConsoleDiscreteAction,
    val ttlMillis: Long,
)

data class ConsoleControlFrame(
    val leaseId: String,
    val inputSequence: Long,
    val ttlMillis: Long,
    val forward: Double,
    val right: Double,
    val up: Double,
    val yaw: Double,
)

enum class ConsoleNeutralRequestReason {
    OPERATOR_RELEASE,
    POINTER_CANCEL,
    WINDOW_BLUR,
    PAGE_HIDE,
}

data class ConsoleControlNeutral(
    val leaseId: String,
    val inputSequence: Long,
    val reason: ConsoleNeutralRequestReason,
)

enum class ConsoleLeaseStatus {
    HELD,
    DENIED,
    RELEASED,
    EXPIRED,
}

data class ConsoleLeaseState(
    val status: ConsoleLeaseStatus,
    val leaseId: String?,
    val holderSessionId: String?,
    val expiresInMillis: Long?,
    val reason: String?,
)

enum class ConsoleCommandDecision { ACCEPTED, REJECTED }

data class ConsoleCommandAck(
    val commandId: String,
    val decision: ConsoleCommandDecision,
    val reason: String?,
    val intentDigestSha256: String,
)

enum class ConsoleCommandOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }

data class ConsoleCommandResult(
    val commandId: String,
    val outcome: ConsoleCommandOutcome,
    val reason: String?,
    val detail: String?,
)

enum class ConsoleControlStatus { APPLIED, REJECTED, STALE }

data class ConsoleControlAck(
    val leaseId: String,
    val inputSequence: Long,
    val status: ConsoleControlStatus,
    val reason: String?,
)

enum class ConsoleSafetyTrigger {
    CLIENT_REQUEST,
    CLIENT_DISCONNECT,
    LEASE_EXPIRED,
    CONTROL_TTL_EXPIRED,
    ACTUATION_READINESS_LOST,
    SERVER_STOP,
}

data class ConsoleSafetyAction(
    val trigger: ConsoleSafetyTrigger,
    val leaseId: String?,
    val lastInputSequence: Long?,
    val succeeded: Boolean,
    val detail: String?,
)

sealed interface ConsoleCoreEvent {
    data class LeaseChanged(val state: ConsoleLeaseState) : ConsoleCoreEvent
    data class CommandAcknowledged(val ack: ConsoleCommandAck) : ConsoleCoreEvent
    data class CommandCompleted(val result: ConsoleCommandResult) : ConsoleCoreEvent
    data class ControlAcknowledged(val ack: ConsoleControlAck) : ConsoleCoreEvent
    data class SafetyActionObserved(val action: ConsoleSafetyAction) : ConsoleCoreEvent
    data class AuditRecorded(val audit: ConsoleAuditEvent) : ConsoleCoreEvent
}

fun interface ConsoleEventSink {
    /** [targetSessionId] is null for infrastructure/broadcast events. */
    fun emit(targetSessionId: String?, event: ConsoleCoreEvent)
}

enum class ConsoleAuditKind {
    SESSION_OPENED,
    SESSION_DISCONNECTED,
    LEASE_ACQUIRED,
    LEASE_RENEWED,
    LEASE_REFUSED,
    LEASE_RELEASED,
    LEASE_EXPIRED,
    COMMAND_ADMITTED,
    COMMAND_REFUSED,
    COMMAND_COMPLETED,
    CONTROL_ADMITTED,
    CONTROL_REFUSED,
    CONTROL_COMPLETED,
    ACTUATION_READINESS_CHANGED,
    SAFETY_NEUTRAL_REQUESTED,
    SAFETY_NEUTRAL_COMPLETED,
    SERVER_STOPPED,
}

data class ConsoleAuditEvent(
    val kind: ConsoleAuditKind,
    val timestampEpochMillis: Long,
    val monotonicNanos: Long,
    val sessionId: String?,
    val leaseId: String?,
    val subjectId: String?,
    val intentDigestSha256: String?,
    val outcome: String,
    val reason: String?,
    val detail: String?,
    val authorityDecisionId: String? = null,
    val clientRequestReason: String? = null,
)

/** Throws on persistence failure. Command/control dispatch then fails closed. */
fun interface ConsoleAuditSink {
    fun record(event: ConsoleAuditEvent)
}
