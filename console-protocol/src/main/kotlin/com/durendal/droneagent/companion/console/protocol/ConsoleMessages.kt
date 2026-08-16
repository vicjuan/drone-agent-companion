package com.durendal.droneagent.companion.console.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ConsoleMessageDirection {
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT,
}

enum class ConsoleMessageType(
    val wireName: String,
    val direction: ConsoleMessageDirection,
    val minimumProtocolVersion: String = ConsoleProtocolModule.PROTOCOL_VERSION,
) {
    CLIENT_HELLO("client_hello", ConsoleMessageDirection.CLIENT_TO_SERVER),
    SERVER_HELLO("server_hello", ConsoleMessageDirection.SERVER_TO_CLIENT),
    RUNTIME_STATE("runtime_state", ConsoleMessageDirection.SERVER_TO_CLIENT),
    TELEMETRY("telemetry", ConsoleMessageDirection.SERVER_TO_CLIENT),
    CAPABILITY_SNAPSHOT("capability_snapshot", ConsoleMessageDirection.SERVER_TO_CLIENT),
    HEALTH("health", ConsoleMessageDirection.SERVER_TO_CLIENT),
    LEASE_ACQUIRE("lease_acquire", ConsoleMessageDirection.CLIENT_TO_SERVER),
    LEASE_RENEW("lease_renew", ConsoleMessageDirection.CLIENT_TO_SERVER),
    LEASE_RELEASE("lease_release", ConsoleMessageDirection.CLIENT_TO_SERVER),
    LEASE_STATE("lease_state", ConsoleMessageDirection.SERVER_TO_CLIENT),
    COMMAND_REQUEST("command_request", ConsoleMessageDirection.CLIENT_TO_SERVER),
    COMMAND_ACK("command_ack", ConsoleMessageDirection.SERVER_TO_CLIENT),
    COMMAND_RESULT("command_result", ConsoleMessageDirection.SERVER_TO_CLIENT),
    CONTROL_FRAME("control_frame", ConsoleMessageDirection.CLIENT_TO_SERVER),
    CONTROL_NEUTRAL("control_neutral", ConsoleMessageDirection.CLIENT_TO_SERVER),
    CONTROL_ACK("control_ack", ConsoleMessageDirection.SERVER_TO_CLIENT),
    SAFETY_EVENT("safety_event", ConsoleMessageDirection.SERVER_TO_CLIENT),
    PROTOCOL_ERROR("protocol_error", ConsoleMessageDirection.SERVER_TO_CLIENT),
    COMMISSIONING_AUTHORITY_STATE(
        "commissioning_authority_state",
        ConsoleMessageDirection.SERVER_TO_CLIENT,
        ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
    ),
    ;

    fun isAvailableIn(protocolVersion: String): Boolean =
        when (protocolVersion) {
            ConsoleProtocolModule.PROTOCOL_VERSION ->
                minimumProtocolVersion == ConsoleProtocolModule.PROTOCOL_VERSION
            ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION -> true
            else -> false
        }

    companion object {
        private val byWireName = entries.associateBy(ConsoleMessageType::wireName)

        fun fromWireName(wireName: String): ConsoleMessageType? = byWireName[wireName]
    }
}

sealed interface ConsoleClientPayload {
    fun messageType(): ConsoleMessageType
}

sealed interface ConsoleServerPayload {
    fun messageType(): ConsoleMessageType
}

data class ConsoleClientMessage(
    val messageId: String,
    val payload: ConsoleClientPayload,
) {
    val type: ConsoleMessageType get() = payload.messageType()
}

data class ConsoleServerMessage(
    val messageId: String,
    val payload: ConsoleServerPayload,
) {
    val type: ConsoleMessageType get() = payload.messageType()
}

@Serializable
data class AuthenticationPresentation(
    val scheme: String,
    val credential: String,
) {
    override fun toString(): String =
        "AuthenticationPresentation(scheme=$scheme, credential=<redacted>)"
}

@Serializable
data class ClientHelloPayload(
    val clientName: String,
    val clientVersion: String,
    val supportedProtocolVersions: List<String>,
    val authentication: AuthenticationPresentation?,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.CLIENT_HELLO
}

@Serializable
data class ServerHelloPayload(
    val sessionId: String,
    val serverVersion: String,
    val selectedProtocolVersion: String,
    val authenticationRequired: Boolean,
    val acceptedAuthenticationSchemes: List<String>,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.SERVER_HELLO
}

@Serializable
enum class AdapterKind {
    @SerialName("mock") MOCK,
    @SerialName("dji") DJI,
}

@Serializable
enum class AircraftConnectionState {
    @SerialName("disconnected") DISCONNECTED,
    @SerialName("connecting") CONNECTING,
    @SerialName("connected") CONNECTED,
    @SerialName("error") ERROR,
}

@Serializable
enum class ActuationLockState {
    @SerialName("locked") LOCKED,
    @SerialName("unlocked") UNLOCKED,
}

@Serializable
enum class OperatingProfile {
    @SerialName("localhost_development") LOCALHOST_DEVELOPMENT,
    @SerialName("hardware_commissioning") HARDWARE_COMMISSIONING,
    @SerialName("operational") OPERATIONAL,
}

@Serializable
data class RuntimeStatePayload(
    val adapter: AdapterKind,
    val aircraftConnection: AircraftConnectionState,
    val actuationLock: ActuationLockState,
    val operatingProfile: OperatingProfile,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.RUNTIME_STATE
}

@Serializable
enum class CommissioningAuthorityState {
    @SerialName("inactive") INACTIVE,
    @SerialName("active") ACTIVE,
}

@Serializable
enum class CommissioningIntent {
    @SerialName("takeoff") TAKEOFF,
    @SerialName("landing") LANDING,
    @SerialName("return_to_home") RETURN_TO_HOME,
    @SerialName("virtual_stick") VIRTUAL_STICK,
}

@Serializable
enum class CommissioningAuthorityReason {
    @SerialName("no_active_session") NO_ACTIVE_SESSION,
    @SerialName("host_revoked") HOST_REVOKED,
    @SerialName("ttl_expired") TTL_EXPIRED,
    @SerialName("operator_disconnected") OPERATOR_DISCONNECTED,
    @SerialName("observation_lost") OBSERVATION_LOST,
    @SerialName("runtime_state_changed") RUNTIME_STATE_CHANGED,
    @SerialName("server_closed") SERVER_CLOSED,
    @SerialName("audit_unavailable") AUDIT_UNAVAILABLE,
    @SerialName("deadline_unavailable") DEADLINE_UNAVAILABLE,
}

/**
 * Server-owned, recipient-relative observation of a commissioning grant.
 *
 * Decimal counters are strings so their exact Long identity survives JavaScript decoding. This
 * payload is never accepted from a client and does not replace the public runtime lock truth.
 */
@Serializable
data class CommissioningAuthorityStatePayload(
    val stateRevision: String,
    val state: CommissioningAuthorityState,
    val commissioningId: String?,
    val generation: String,
    val allowedIntents: List<CommissioningIntent>,
    val expiresInMs: Long?,
    val reason: CommissioningAuthorityReason?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.COMMISSIONING_AUTHORITY_STATE
}

@Serializable
enum class FlightState {
    @SerialName("grounded") GROUNDED,
    @SerialName("taking_off") TAKING_OFF,
    @SerialName("flying") FLYING,
    @SerialName("landing") LANDING,
    @SerialName("returning_home") RETURNING_HOME,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class TelemetryPayload(
    val sequence: Long,
    val batteryPercent: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val flightState: FlightState,
    val gimbalPitchDeg: Double?,
    val cameraRecording: Boolean?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.TELEMETRY
}

@Serializable
enum class CapabilityEvidenceStatus {
    CONFIRMED,
    LIMITED,
    UNKNOWN,
}

@Serializable
data class CapabilitySnapshotRow(
    val id: String,
    val status: CapabilityEvidenceStatus,
    val assessment: String,
)

@Serializable
data class CapabilitySnapshotPayload(
    val matrixId: String,
    val schemaVersion: Int,
    val lastUpdated: String,
    val sourceDigestSha256: String,
    val rows: List<CapabilitySnapshotRow>,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.CAPABILITY_SNAPSHOT
}

@Serializable
enum class HealthStatus {
    @SerialName("healthy") HEALTHY,
    @SerialName("degraded") DEGRADED,
    @SerialName("stopping") STOPPING,
}

@Serializable
data class HealthPayload(
    val status: HealthStatus,
    val uptimeMs: Long,
    val detail: String?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.HEALTH
}

@Serializable
data class LeaseAcquirePayload(
    val requestedTtlMs: Int,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.LEASE_ACQUIRE
}

@Serializable
data class LeaseRenewPayload(
    val leaseId: String,
    val requestedTtlMs: Int,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.LEASE_RENEW
}

@Serializable
data class LeaseReleasePayload(
    val leaseId: String,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.LEASE_RELEASE
}

@Serializable
enum class LeaseState {
    @SerialName("available") AVAILABLE,
    @SerialName("held") HELD,
    @SerialName("denied") DENIED,
    @SerialName("released") RELEASED,
    @SerialName("expired") EXPIRED,
}

@Serializable
data class LeaseStatePayload(
    val requestMessageId: String?,
    val state: LeaseState,
    val leaseId: String?,
    val holderSessionId: String?,
    val expiresInMs: Long?,
    val reason: String?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.LEASE_STATE
}

@Serializable
enum class DiscreteCommandAction {
    @SerialName("takeoff") TAKEOFF,
    @SerialName("landing") LANDING,
    @SerialName("return_to_home") RETURN_TO_HOME,
}

@Serializable
data class DiscreteCommandRequestPayload(
    val commandId: String,
    val leaseId: String,
    val action: DiscreteCommandAction,
    val ttlMs: Int,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.COMMAND_REQUEST
}

@Serializable
enum class CommandDecision {
    @SerialName("accepted") ACCEPTED,
    @SerialName("rejected") REJECTED,
}

@Serializable
data class CommandAckPayload(
    val commandId: String,
    val decision: CommandDecision,
    val reason: String?,
    val intentDigestSha256: String,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.COMMAND_ACK
}

@Serializable
enum class CommandResultStatus {
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("timed_out") TIMED_OUT,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class CommandResultPayload(
    val commandId: String,
    val status: CommandResultStatus,
    val reason: String?,
    val detail: String?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.COMMAND_RESULT
}

@Serializable
data class ControlFramePayload(
    val leaseId: String,
    val inputSequence: Long,
    val ttlMs: Int,
    val forward: Double,
    val right: Double,
    val up: Double,
    val yaw: Double,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.CONTROL_FRAME
}

@Serializable
enum class ControlNeutralReason {
    @SerialName("operator_release") OPERATOR_RELEASE,
    @SerialName("pointer_cancel") POINTER_CANCEL,
    @SerialName("window_blur") WINDOW_BLUR,
    @SerialName("page_hide") PAGE_HIDE,
}

@Serializable
data class ControlNeutralPayload(
    val leaseId: String,
    val inputSequence: Long,
    val reason: ControlNeutralReason,
) : ConsoleClientPayload {
    override fun messageType() = ConsoleMessageType.CONTROL_NEUTRAL
}

@Serializable
enum class ControlAckStatus {
    @SerialName("applied") APPLIED,
    @SerialName("rejected") REJECTED,
    @SerialName("stale") STALE,
}

@Serializable
data class ControlAckPayload(
    val leaseId: String,
    val inputSequence: Long,
    val status: ControlAckStatus,
    val reason: String?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.CONTROL_ACK
}

@Serializable
enum class SafetyAction {
    @SerialName("neutralize") NEUTRALIZE,
}

@Serializable
enum class SafetyOutcome {
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class SafetyTrigger {
    @SerialName("client_request") CLIENT_REQUEST,
    @SerialName("client_disconnect") CLIENT_DISCONNECT,
    @SerialName("lease_expired") LEASE_EXPIRED,
    @SerialName("control_ttl_expired") CONTROL_TTL_EXPIRED,
    @SerialName("actuation_readiness_lost") ACTUATION_READINESS_LOST,
    @SerialName("server_stop") SERVER_STOP,
}

@Serializable
data class SafetyEventPayload(
    val action: SafetyAction,
    val outcome: SafetyOutcome,
    val trigger: SafetyTrigger,
    val leaseId: String?,
    val lastInputSequence: Long?,
    val detail: String?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.SAFETY_EVENT
}

@Serializable
enum class ProtocolErrorCode(val wireName: String) {
    @SerialName("malformed_json") MALFORMED_JSON("malformed_json"),
    @SerialName("invalid_envelope") INVALID_ENVELOPE("invalid_envelope"),
    @SerialName("unsupported_protocol_version") UNSUPPORTED_PROTOCOL_VERSION("unsupported_protocol_version"),
    @SerialName("unknown_message_type") UNKNOWN_MESSAGE_TYPE("unknown_message_type"),
    @SerialName("wrong_message_direction") WRONG_MESSAGE_DIRECTION("wrong_message_direction"),
    @SerialName("invalid_payload") INVALID_PAYLOAD("invalid_payload"),
    @SerialName("handshake_required") HANDSHAKE_REQUIRED("handshake_required"),
    @SerialName("unexpected_message") UNEXPECTED_MESSAGE("unexpected_message"),
    @SerialName("server_unavailable") SERVER_UNAVAILABLE("server_unavailable"),
}

@Serializable
data class ProtocolErrorPayload(
    val relatedMessageId: String?,
    val code: ProtocolErrorCode,
    val detail: String?,
) : ConsoleServerPayload {
    override fun messageType() = ConsoleMessageType.PROTOCOL_ERROR
}
