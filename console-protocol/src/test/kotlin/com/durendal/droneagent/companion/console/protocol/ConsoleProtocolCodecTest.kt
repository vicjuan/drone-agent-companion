package com.durendal.droneagent.companion.console.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
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
