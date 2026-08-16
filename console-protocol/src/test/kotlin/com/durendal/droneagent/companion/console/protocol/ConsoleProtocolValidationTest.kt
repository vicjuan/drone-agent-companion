package com.durendal.droneagent.companion.console.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsoleProtocolValidationTest {
    private val codec = ConsoleProtocolCodec()

    @Test
    fun `unknown envelope field is rejected`() {
        val text =
            """{"protocolVersion":"1.0","messageId":"hello-001","type":"client_hello","payload":{"clientName":"web-console","clientVersion":"1","supportedProtocolVersions":["1.0"],"authentication":null},"unexpected":true}"""

        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) { codec.decodeClient(text) }
    }

    @Test
    fun `missing explicit nullable field is rejected`() {
        val text =
            """{"protocolVersion":"1.0","messageId":"health-001","type":"health","payload":{"status":"healthy","uptimeMs":0}}"""

        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) { codec.decodeServer(text) }
    }

    @Test
    fun `unknown payload field including client authority is rejected`() {
        val text =
            """{"protocolVersion":"1.0","messageId":"command-001","type":"command_request","payload":{"commandId":"command-001","leaseId":"lease-001","action":"takeoff","ttlMs":5000,"authority":{"actor":"browser"}}}"""

        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) { codec.decodeClient(text) }
    }

    @Test
    fun `unknown type and unsupported version have distinct stable codes`() {
        assertCode(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE) {
            codec.decodeClient(
                """{"protocolVersion":"1.0","messageId":"message-001","type":"future_message","payload":{}}""",
            )
        }
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION) {
            codec.decodeClient(
                """{"protocolVersion":"2.0","messageId":"message-001","type":"client_hello","payload":{}}""",
            )
        }
    }

    @Test
    fun `malformed JSON does not collapse into payload error`() {
        assertCode(ProtocolErrorCode.MALFORMED_JSON) { codec.decodeClient("{not_json") }
    }

    @Test
    fun `deep unknown JSON is rejected before recursive parsing`() {
        val nested = "[".repeat(5_000) + "0" + "]".repeat(5_000)
        val text =
            """{"protocolVersion":"1.0","messageId":"lease-deep-001","type":"lease_acquire","payload":{"requestedTtlMs":500,"unknown":$nested}}"""
        require(text.toByteArray(Charsets.UTF_8).size < ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES)

        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) { codec.decodeClient(text) }
    }

    @Test
    fun `nesting preflight ignores escaped brackets inside strings`() {
        val credential = "[{\\\"}]".repeat(100)
        val message =
            ConsoleClientMessage(
                "hello-brackets-001",
                ClientHelloPayload(
                    clientName = "web-console",
                    clientVersion = "1",
                    supportedProtocolVersions = listOf("1.0"),
                    authentication = AuthenticationPresentation("fixture", credential),
                ),
            )

        assertEquals(message, codec.decodeClient(codec.encodeClient(message)))
    }

    @Test
    fun `wire byte limit is measured as UTF-8 before JSON parsing`() {
        val health =
            """{"protocolVersion":"1.0","messageId":"health-limit-001","type":"health","payload":{"status":"healthy","uptimeMs":0,"detail":null}}"""
        val exactLimit =
            health + " ".repeat(ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES - health.length)
        assertEquals(
            HealthPayload(HealthStatus.HEALTHY, 0, null),
            codec.decodeServer(exactLimit).payload,
        )
        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) { codec.decodeServer("$exactLimit ") }

        val multibyte = "界".repeat(22_000)
        val text =
            """{"protocolVersion":"1.0","messageId":"hello-large-001","type":"client_hello","payload":{"clientName":"$multibyte","clientVersion":"1","supportedProtocolVersions":["1.0"],"authentication":null}}"""
        require(text.length < ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES)
        require(text.toByteArray(Charsets.UTF_8).size > ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES)

        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) { codec.decodeClient(text) }
    }

    @Test
    fun `encoder cannot produce a wire message that its decoder must reject`() {
        val oversizedSnapshot =
            CapabilitySnapshotPayload(
                matrixId = ConsoleProtocolModule.G520_MATRIX_ID,
                schemaVersion = 1,
                lastUpdated = "2026-08-15",
                sourceDigestSha256 = "a".repeat(64),
                rows =
                    (0 until 100).map { index ->
                        CapabilitySnapshotRow(
                            id = "capability_$index",
                            status = CapabilityEvidenceStatus.UNKNOWN,
                            assessment = "x".repeat(ConsoleProtocolModule.MAX_TEXT_LENGTH),
                        )
                    },
            )

        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) {
            codec.encodeServer(ConsoleServerMessage("capability-large-001", oversizedSnapshot))
        }
    }

    @Test
    fun `JSON number grammar rejects leading zero and normalizes integral forms`() {
        fun leaseWithTtl(token: String): String =
            """{"protocolVersion":"1.0","messageId":"lease-number-001","type":"lease_acquire","payload":{"requestedTtlMs":$token}}"""

        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) { codec.decodeClient(leaseWithTtl("0500")) }
        listOf("500", "500.0", "5e2", "5E+2").forEach { token ->
            assertEquals(
                LeaseAcquirePayload(500),
                codec.decodeClient(leaseWithTtl(token)).payload,
            )
        }
        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) { codec.decodeClient(leaseWithTtl("500.5")) }
    }

    @Test
    fun `JSON number preflight rejects leading plus and decimal point forms`() {
        fun leaseWithTtl(token: String): String =
            """{"protocolVersion":"1.0","messageId":"lease-number-002","type":"lease_acquire","payload":{"requestedTtlMs":$token}}"""

        fun controlWithForward(token: String): String =
            """{"protocolVersion":"1.0","messageId":"control-number-001","type":"control_frame","payload":{"leaseId":"lease-001","inputSequence":1,"ttlMs":500,"forward":$token,"right":0,"up":0,"yaw":0}}"""

        assertCode(ProtocolErrorCode.INVALID_ENVELOPE) { codec.decodeClient(leaseWithTtl("+500")) }
        listOf(".5", "-.5", "+0.5").forEach { token ->
            assertCode(ProtocolErrorCode.INVALID_ENVELOPE) {
                codec.decodeClient(controlWithForward(token))
            }
        }
    }

    @Test
    fun `integral normalization rejects underflow and accepts exact exponent forms`() {
        fun healthWithUptime(token: String): String =
            """{"protocolVersion":"1.0","messageId":"health-number-001","type":"health","payload":{"status":"healthy","uptimeMs":$token,"detail":null}}"""

        listOf("1e-324", "1e-2147483648").forEach { token ->
            assertCode(ProtocolErrorCode.INVALID_PAYLOAD) {
                codec.decodeServer(healthWithUptime(token))
            }
        }
        mapOf(
            "0e-99999999999999999999" to 0L,
            "5e2" to 500L,
        ).forEach { (token, expected) ->
            assertEquals(
                HealthPayload(HealthStatus.HEALTHY, expected, null),
                codec.decodeServer(healthWithUptime(token)).payload,
            )
        }
    }

    @Test
    fun `integral normalization also protects telemetry battery and lease expiry`() {
        fun telemetryWithBattery(token: String): String =
            """{"protocolVersion":"1.0","messageId":"telemetry-number-001","type":"telemetry","payload":{"sequence":42,"batteryPercent":$token,"latitude":null,"longitude":null,"altitudeM":12.5,"flightState":"flying","gimbalPitchDeg":null,"cameraRecording":null}}"""

        fun heldLeaseWithExpiry(token: String): String =
            """{"protocolVersion":"1.0","messageId":"lease-state-number-001","type":"lease_state","payload":{"requestMessageId":null,"state":"held","leaseId":"lease-001","holderSessionId":"session-001","expiresInMs":$token,"reason":null}}"""

        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) {
            codec.decodeServer(telemetryWithBattery("1e-324"))
        }
        assertEquals(
            87,
            (codec.decodeServer(telemetryWithBattery("8.7e1")).payload as TelemetryPayload)
                .batteryPercent,
        )

        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) {
            codec.decodeServer(heldLeaseWithExpiry("1e-324"))
        }
        assertEquals(
            5000L,
            (codec.decodeServer(heldLeaseWithExpiry("5e3")).payload as LeaseStatePayload)
                .expiresInMs,
        )
    }

    @Test
    fun `unknown enum is rejected rather than coerced`() {
        val text =
            """{"protocolVersion":"1.0","messageId":"command-001","type":"command_request","payload":{"commandId":"command-001","leaseId":"lease-001","action":"fly_anywhere","ttlMs":5000}}"""

        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) { codec.decodeClient(text) }
    }

    @Test
    fun `control frame accepts exact boundaries and rejects unsafe values`() {
        val boundary =
            ConsoleClientMessage(
                messageId = "control-boundary-001",
                payload =
                    ControlFramePayload(
                        leaseId = "lease-001",
                        inputSequence = ConsoleProtocolModule.MAX_SAFE_INTEGER,
                        ttlMs = ConsoleProtocolModule.MAX_CONTROL_TTL_MS,
                        forward = -1.0,
                        right = 1.0,
                        up = 0.0,
                        yaw = 0.0,
                    ),
            )
        assertEquals(boundary, codec.decodeClient(codec.encodeClient(boundary)))

        assertInvalidClient(
            boundary.copy(payload = (boundary.payload as ControlFramePayload).copy(forward = 1.000_001)),
        )
        assertInvalidClient(
            boundary.copy(payload = (boundary.payload as ControlFramePayload).copy(ttlMs = 49)),
        )
        assertInvalidClient(
            boundary.copy(
                payload =
                    (boundary.payload as ControlFramePayload).copy(
                        inputSequence = ConsoleProtocolModule.MAX_SAFE_INTEGER + 1,
                    ),
            ),
        )
        assertInvalidClient(
            boundary.copy(payload = (boundary.payload as ControlFramePayload).copy(yaw = Double.NaN)),
        )
    }

    @Test
    fun `command and lease TTLs fail closed outside their ranges`() {
        assertInvalidClient(
            ConsoleClientMessage(
                "command-001",
                DiscreteCommandRequestPayload(
                    commandId = "command-001",
                    leaseId = "lease-001",
                    action = DiscreteCommandAction.TAKEOFF,
                    ttlMs = ConsoleProtocolModule.MIN_COMMAND_TTL_MS - 1,
                ),
            ),
        )
        assertInvalidClient(
            ConsoleClientMessage(
                "lease-001",
                LeaseAcquirePayload(ConsoleProtocolModule.MAX_LEASE_TTL_MS + 1),
            ),
        )
    }

    @Test
    fun `all lease states enforce exact nullable combinations`() {
        val invalid =
            listOf(
                leaseState(LeaseState.AVAILABLE).copy(leaseId = "lease-001"),
                leaseState(LeaseState.HELD).copy(leaseId = null),
                leaseState(LeaseState.DENIED).copy(leaseId = "lease-001"),
                leaseState(LeaseState.RELEASED).copy(reason = "unexpected"),
                leaseState(LeaseState.EXPIRED).copy(reason = null),
            )
        invalid.forEachIndexed { index, payload ->
            assertInvalidServer(ConsoleServerMessage("lease-state-$index", payload))
        }
    }

    @Test
    fun `authority observation accepts only exact initial active and terminal shapes`() {
        val initial =
            CommissioningAuthorityStatePayload(
                stateRevision = "9",
                state = CommissioningAuthorityState.INACTIVE,
                commissioningId = null,
                generation = "0",
                allowedIntents = emptyList(),
                expiresInMs = null,
                reason = CommissioningAuthorityReason.NO_ACTIVE_SESSION,
            )
        val active =
            CommissioningAuthorityStatePayload(
                stateRevision = "1",
                state = CommissioningAuthorityState.ACTIVE,
                commissioningId = "123e4567-e89b-42d3-a456-426614174000",
                generation = "1",
                allowedIntents =
                    listOf(
                        CommissioningIntent.LANDING,
                        CommissioningIntent.RETURN_TO_HOME,
                        CommissioningIntent.VIRTUAL_STICK,
                    ),
                expiresInMs = 30_000,
                reason = null,
            )
        val terminal =
            active.copy(
                stateRevision = "2",
                state = CommissioningAuthorityState.INACTIVE,
                allowedIntents = emptyList(),
                expiresInMs = null,
                reason = CommissioningAuthorityReason.HOST_REVOKED,
            )

        listOf(initial.copy(stateRevision = "0"), initial, active, terminal)
            .forEach(::assertValidAuthority)
    }

    @Test
    fun `authority observation rejects ambiguous identity allowlist ttl and terminal evidence`() {
        val active =
            CommissioningAuthorityStatePayload(
                stateRevision = "7",
                state = CommissioningAuthorityState.ACTIVE,
                commissioningId = "123e4567-e89b-42d3-a456-426614174000",
                generation = "4",
                allowedIntents =
                    listOf(CommissioningIntent.TAKEOFF, CommissioningIntent.VIRTUAL_STICK),
                expiresInMs = 5_000,
                reason = null,
            )
        val invalid =
            listOf(
                active.copy(stateRevision = "07"),
                active.copy(stateRevision = "9223372036854775808"),
                active.copy(generation = "00"),
                active.copy(commissioningId = active.commissioningId?.uppercase()),
                active.copy(commissioningId = "123e4567-e89b-32d3-a456-426614174000"),
                active.copy(allowedIntents = emptyList()),
                active.copy(
                    allowedIntents =
                        listOf(CommissioningIntent.VIRTUAL_STICK, CommissioningIntent.TAKEOFF),
                ),
                active.copy(
                    allowedIntents =
                        listOf(CommissioningIntent.TAKEOFF, CommissioningIntent.TAKEOFF),
                ),
                active.copy(expiresInMs = 0),
                active.copy(
                    expiresInMs =
                        ConsoleProtocolModule.MAX_COMMISSIONING_AUTHORITY_REMAINING_TTL_MS + 1,
                ),
                active.copy(reason = CommissioningAuthorityReason.HOST_REVOKED),
                active.copy(
                    state = CommissioningAuthorityState.INACTIVE,
                    allowedIntents = emptyList(),
                    expiresInMs = null,
                    reason = CommissioningAuthorityReason.NO_ACTIVE_SESSION,
                ),
                active.copy(
                    stateRevision = "0",
                    state = CommissioningAuthorityState.INACTIVE,
                    allowedIntents = emptyList(),
                    expiresInMs = null,
                    reason = CommissioningAuthorityReason.HOST_REVOKED,
                ),
                active.copy(
                    state = CommissioningAuthorityState.INACTIVE,
                    commissioningId = null,
                    allowedIntents = emptyList(),
                    expiresInMs = null,
                    reason = CommissioningAuthorityReason.TTL_EXPIRED,
                ),
            )

        invalid.forEach(::assertInvalidAuthority)
    }

    @Test
    fun `capability snapshot locks G520 identity ISO date and unique rows`() {
        val base =
            CapabilitySnapshotPayload(
                matrixId = ConsoleProtocolModule.G520_MATRIX_ID,
                schemaVersion = 1,
                lastUpdated = "2026-08-15",
                sourceDigestSha256 = "a".repeat(64),
                rows =
                    listOf(
                        CapabilitySnapshotRow(
                            id = "battery",
                            status = CapabilityEvidenceStatus.UNKNOWN,
                            assessment = "No G520 evidence.",
                        ),
                    ),
            )
        assertValidServer(base)
        assertInvalidServer(ConsoleServerMessage("capability-002", base.copy(matrixId = "pixel-stack")))
        assertInvalidServer(ConsoleServerMessage("capability-003", base.copy(lastUpdated = "2026-8-15")))
        assertInvalidServer(
            ConsoleServerMessage("capability-004", base.copy(rows = base.rows + base.rows.first())),
        )
        assertInvalidServer(
            ConsoleServerMessage(
                "capability-005",
                base.copy(rows = listOf(base.rows.first().copy(id = "a".repeat(129)))),
            ),
        )
    }

    @Test
    fun `bounded strings and negotiation collections reject oversized input`() {
        val hello =
            ClientHelloPayload(
                clientName = "x".repeat(ConsoleProtocolModule.MAX_NAME_LENGTH + 1),
                clientVersion = "1",
                supportedProtocolVersions = listOf("1.0"),
                authentication = null,
            )
        assertInvalidClient(ConsoleClientMessage("hello-001", hello))

        val tooManyVersions =
            (0 until ConsoleProtocolModule.MAX_NEGOTIATION_VALUES).map { "future-$it" } + "1.0"
        assertInvalidClient(
            ConsoleClientMessage(
                "hello-002",
                hello.copy(clientName = "web-console", supportedProtocolVersions = tooManyVersions),
            ),
        )
    }

    @Test
    fun `failed neutralization cannot be reported without a detail`() {
        val failed =
            SafetyEventPayload(
                action = SafetyAction.NEUTRALIZE,
                outcome = SafetyOutcome.FAILED,
                trigger = SafetyTrigger.CONTROL_TTL_EXPIRED,
                leaseId = "lease-001",
                lastInputSequence = 4,
                detail = null,
            )

        assertInvalidServer(ConsoleServerMessage("safety-failed-001", failed))
        assertValidServer(failed.copy(detail = "neutral executor failed"))
    }

    private fun leaseState(state: LeaseState): LeaseStatePayload =
        when (state) {
            LeaseState.AVAILABLE -> LeaseStatePayload(null, state, null, null, null, null)
            LeaseState.HELD -> LeaseStatePayload(null, state, "lease-001", "session-001", 5000, null)
            LeaseState.DENIED -> LeaseStatePayload(null, state, null, null, null, "lease_already_held")
            LeaseState.RELEASED -> LeaseStatePayload(null, state, "lease-001", null, null, null)
            LeaseState.EXPIRED -> LeaseStatePayload(null, state, "lease-001", null, null, "lease_ttl_expired")
        }

    private fun assertValidServer(payload: ConsoleServerPayload) {
        val message = ConsoleServerMessage("capability-valid-001", payload)
        assertEquals(message, codec.decodeServer(codec.encodeServer(message)))
    }

    private fun assertValidAuthority(payload: CommissioningAuthorityStatePayload) {
        val message = ConsoleServerMessage("authority-valid-001", payload)
        val encoded =
            codec.encodeServer(
                message,
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            )
        assertEquals(
            message,
            codec.decodeServer(
                encoded,
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            ),
        )
    }

    private fun assertInvalidAuthority(payload: CommissioningAuthorityStatePayload) {
        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) {
            codec.encodeServer(
                ConsoleServerMessage("authority-invalid-001", payload),
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            )
        }
    }

    private fun assertInvalidClient(message: ConsoleClientMessage) {
        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) { codec.encodeClient(message) }
    }

    private fun assertInvalidServer(message: ConsoleServerMessage) {
        assertCode(ProtocolErrorCode.INVALID_PAYLOAD) { codec.encodeServer(message) }
    }

    private fun assertCode(
        expected: ProtocolErrorCode,
        block: () -> Unit,
    ) {
        val error = assertThrows(ConsoleProtocolException::class.java, block)
        assertEquals(expected, error.code)
    }
}
