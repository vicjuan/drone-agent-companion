package com.durendal.droneagent.companion.console.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleProtocolCodecTest {
    private val codec = ConsoleProtocolCodec()

    @Test
    fun `client hello round-trips with explicit null authentication`() {
        val message =
            ConsoleClientMessage(
                messageId = "hello-001",
                payload =
                    ClientHelloPayload(
                        clientName = "web-console",
                        clientVersion = "0.1.0",
                        supportedProtocolVersions = listOf("1.0"),
                        authentication = null,
                    ),
            )

        val encoded = codec.encodeClient(message)
        val objectValue = ConsoleProtocolCodec.STRICT_JSON.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("protocolVersion", "messageId", "type", "payload"),
            objectValue.keys,
        )
        assertTrue(objectValue.getValue("payload").jsonObject.getValue("authentication") is JsonNull)
        assertEquals(message, codec.decodeClient(encoded))
    }

    @Test
    fun `server telemetry round-trips with every unavailable reading explicit`() {
        val message =
            ConsoleServerMessage(
                messageId = "telemetry-001",
                payload =
                    TelemetryPayload(
                        sequence = 1,
                        batteryPercent = null,
                        latitude = null,
                        longitude = null,
                        altitudeM = null,
                        flightState = FlightState.UNKNOWN,
                        gimbalPitchDeg = null,
                        cameraRecording = null,
                    ),
            )

        val encoded = codec.encodeServer(message)
        val payload =
            ConsoleProtocolCodec.STRICT_JSON
                .parseToJsonElement(encoded)
                .jsonObject
                .getValue("payload")
                .jsonObject
        listOf(
            "batteryPercent",
            "latitude",
            "longitude",
            "altitudeM",
            "gimbalPitchDeg",
            "cameraRecording",
        ).forEach { field -> assertTrue("$field must be explicit", payload.getValue(field) is JsonNull) }
        assertEquals(message, codec.decodeServer(encoded))
    }

    @Test
    fun `v1_1 authority state round-trips with exact fields and decimal strings`() {
        val message =
            ConsoleServerMessage(
                messageId = "authority-active-001",
                payload =
                    CommissioningAuthorityStatePayload(
                        stateRevision = "9007199254740992",
                        state = CommissioningAuthorityState.ACTIVE,
                        commissioningId = "123e4567-e89b-42d3-a456-426614174000",
                        generation = "9223372036854775807",
                        allowedIntents =
                            listOf(
                                CommissioningIntent.TAKEOFF,
                                CommissioningIntent.VIRTUAL_STICK,
                            ),
                        expiresInMs = 5_000,
                        reason = null,
                    ),
            )

        val encoded =
            codec.encodeServer(
                message,
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            )
        val envelope = ConsoleProtocolCodec.STRICT_JSON.parseToJsonElement(encoded).jsonObject
        val payload = envelope.getValue("payload").jsonObject
        assertEquals("1.1", envelope.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("commissioning_authority_state", envelope.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf(
                "stateRevision",
                "state",
                "commissioningId",
                "generation",
                "allowedIntents",
                "expiresInMs",
                "reason",
            ),
            payload.keys.toList(),
        )
        assertTrue(payload.getValue("stateRevision").jsonPrimitive.isString)
        assertTrue(payload.getValue("generation").jsonPrimitive.isString)
        assertEquals(
            message,
            codec.decodeServer(
                encoded,
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            ),
        )
    }

    @Test
    fun `v1_0 inventory cannot encode or decode the v1_1 authority type`() {
        val message =
            ConsoleServerMessage(
                "authority-initial-001",
                CommissioningAuthorityStatePayload(
                    stateRevision = "0",
                    state = CommissioningAuthorityState.INACTIVE,
                    commissioningId = null,
                    generation = "0",
                    allowedIntents = emptyList(),
                    expiresInMs = null,
                    reason = CommissioningAuthorityReason.NO_ACTIVE_SESSION,
                ),
            )

        assertProtocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE) {
            codec.encodeServer(message)
        }
        val v1Text =
            """{"protocolVersion":"1.0","messageId":"authority-initial-001","type":"commissioning_authority_state","payload":{"stateRevision":"0","state":"inactive","commissioningId":null,"generation":"0","allowedIntents":[],"expiresInMs":null,"reason":"no_active_session"}}"""
        assertProtocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE) {
            codec.decodeServer(v1Text)
        }

        val v11Text =
            codec.encodeServer(
                message,
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            )
        assertProtocolError(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION) {
            codec.decodeServer(v11Text)
        }
        assertProtocolError(ProtocolErrorCode.WRONG_MESSAGE_DIRECTION) {
            codec.decodeClient(
                v11Text,
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            )
        }
    }

    @Test
    fun `server hello selected version must match the explicit codec version`() {
        val v11Hello =
            ConsoleServerMessage(
                "hello-server-v11-001",
                ServerHelloPayload(
                    sessionId = "session-v11-001",
                    serverVersion = "0.1.0",
                    selectedProtocolVersion = "1.1",
                    authenticationRequired = false,
                    acceptedAuthenticationSchemes = emptyList(),
                ),
            )

        val encoded = codec.encodeServer(v11Hello, "1.1")
        assertEquals(v11Hello, codec.decodeServer(encoded, "1.1"))
        assertProtocolError(ProtocolErrorCode.INVALID_PAYLOAD) {
            codec.encodeServer(v11Hello, "1.0")
        }
    }

    @Test
    fun `client hello is accepted only in the v1_0 bootstrap envelope`() {
        val clientHello =
            ConsoleClientMessage(
                "hello-client-v11-001",
                ClientHelloPayload(
                    clientName = "web-console",
                    clientVersion = "0.1.0",
                    supportedProtocolVersions = listOf("1.1", "1.0"),
                    authentication = null,
                ),
            )

        assertEquals(clientHello, codec.decodeClient(codec.encodeClient(clientHello)))
        assertProtocolError(ProtocolErrorCode.INVALID_PAYLOAD) {
            codec.encodeClient(clientHello, "1.1")
        }
        val wrongEnvelope =
            """{"protocolVersion":"1.1","messageId":"hello-client-v11-001","type":"client_hello","payload":{"clientName":"web-console","clientVersion":"0.1.0","supportedProtocolVersions":["1.1","1.0"],"authentication":null}}"""
        assertProtocolError(ProtocolErrorCode.INVALID_PAYLOAD) {
            codec.decodeClient(wrongEnvelope, "1.1")
        }
    }

    @Test
    fun `transport can inspect version type and id before payload decoding`() {
        val text =
            """{"protocolVersion":"1.0","messageId":"control-invalid-001","type":"control_frame","payload":{"not":"decoded yet"}}"""

        assertEquals(
            ConsoleEnvelopeHeader(
                protocolVersion = "1.0",
                messageId = "control-invalid-001",
                type = ConsoleMessageType.CONTROL_FRAME,
            ),
            codec.inspectEnvelope(text),
        )
        assertProtocolError(ProtocolErrorCode.INVALID_PAYLOAD) { codec.decodeClient(text) }
    }

    @Test
    fun `directional decoder rejects a valid message from the other direction`() {
        val serverHealth =
            """{"protocolVersion":"1.0","messageId":"health-001","type":"health","payload":{"status":"healthy","uptimeMs":1,"detail":null}}"""

        assertProtocolError(ProtocolErrorCode.WRONG_MESSAGE_DIRECTION) {
            codec.decodeClient(serverHealth)
        }
    }

    @Test
    fun `codec errors expose stable code without echoing rejected input`() {
        val secret = "do-not-reflect-this-credential"
        val text =
            """{"protocolVersion":"1.0","messageId":"command-001","type":"command_request","payload":{"commandId":"command-001","leaseId":"lease-001","action":"takeoff","ttlMs":5000,"authority":"$secret"}}"""

        val error =
            assertThrows(ConsoleProtocolException::class.java) {
                codec.decodeClient(text)
            }
        assertEquals(ProtocolErrorCode.INVALID_PAYLOAD, error.code)
        assertFalse(error.message.orEmpty().contains(secret))
        assertFalse(error.safeDetail.contains(secret))
        assertEquals(
            ProtocolErrorPayload(
                relatedMessageId = "command-001",
                code = ProtocolErrorCode.INVALID_PAYLOAD,
                detail = "Payload does not match the message schema.",
            ),
            error.toPayload("command-001"),
        )
    }

    @Test
    fun `all stable protocol error codes produce safe server messages`() {
        ProtocolErrorCode.entries.forEachIndexed { index, code ->
            val exception = ConsoleProtocolException(code)
            val message =
                ConsoleServerMessage(
                    messageId = "protocol-error-$index",
                    payload = exception.toPayload("related-message-001"),
                )

            assertTrue(exception.safeDetail.isNotBlank())
            assertEquals(message, codec.decodeServer(codec.encodeServer(message)))
        }
    }

    @Test
    fun `authentication presentation does not expose credential through toString`() {
        val credential = "fixture-secret-that-must-not-be-logged"
        val presentation = AuthenticationPresentation("bearer", credential)

        assertFalse(presentation.toString().contains(credential))
        assertTrue(presentation.toString().contains("<redacted>"))
    }

    private fun assertProtocolError(
        expected: ProtocolErrorCode,
        block: () -> Unit,
    ) {
        val error = assertThrows(ConsoleProtocolException::class.java, block)
        assertEquals(expected, error.code)
    }
}
