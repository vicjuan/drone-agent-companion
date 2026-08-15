package com.durendal.droneagent.companion.console.protocol

import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

data class ConsoleEnvelopeHeader(
    val protocolVersion: String,
    val messageId: String,
    val type: ConsoleMessageType,
)

class ConsoleProtocolException(
    val code: ProtocolErrorCode,
) : IllegalArgumentException("Console protocol input rejected: ${code.wireName}") {
    /** Safe for a protocol_error payload; never includes the rejected wire text. */
    val safeDetail: String =
        when (code) {
            ProtocolErrorCode.MALFORMED_JSON -> "Message is not valid JSON."
            ProtocolErrorCode.INVALID_ENVELOPE -> "Envelope does not match the console protocol schema."
            ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION -> "Protocol version is not supported."
            ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE -> "Message type is not supported."
            ProtocolErrorCode.WRONG_MESSAGE_DIRECTION -> "Message type is not valid in this direction."
            ProtocolErrorCode.INVALID_PAYLOAD -> "Payload does not match the message schema."
            ProtocolErrorCode.HANDSHAKE_REQUIRED -> "Client hello must complete before this message."
            ProtocolErrorCode.AUTHENTICATION_REQUIRED -> "Authentication is required for this session."
            ProtocolErrorCode.AUTHENTICATION_FAILED -> "Authentication failed."
            ProtocolErrorCode.AUTHORIZATION_FAILED -> "The authenticated role is not authorized for this message."
            ProtocolErrorCode.UNEXPECTED_MESSAGE -> "Message is not valid in the current session state."
            ProtocolErrorCode.SERVER_UNAVAILABLE -> "Server could not safely process the message."
        }

    fun toPayload(relatedMessageId: String?): ProtocolErrorPayload =
        ProtocolErrorPayload(
            relatedMessageId = relatedMessageId,
            code = code,
            detail = safeDetail,
        )
}

/** Strict, direction-aware JSON codec for the browser-facing protocol. */
class ConsoleProtocolCodec private constructor(private val json: Json) {
    constructor() : this(STRICT_JSON)

    fun inspectEnvelope(text: String): ConsoleEnvelopeHeader {
        preflightWireText(text)
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw ConsoleProtocolException(ProtocolErrorCode.MALFORMED_JSON)
            }
        val objectValue = element as? JsonObject
            ?: throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        if (objectValue.keys != ENVELOPE_FIELDS) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        }

        val protocolVersion = objectValue.requiredString("protocolVersion")
        val messageId = objectValue.requiredString("messageId")
        val typeName = objectValue.requiredString("type")
        if (objectValue["payload"] !is JsonObject) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        }
        try {
            ConsoleMessageValidator.requireMessageId(messageId)
        } catch (_: IllegalArgumentException) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        }
        if (protocolVersion != ConsoleProtocolModule.PROTOCOL_VERSION) {
            throw ConsoleProtocolException(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION)
        }
        val type = ConsoleMessageType.fromWireName(typeName)
            ?: throw ConsoleProtocolException(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE)
        return ConsoleEnvelopeHeader(protocolVersion, messageId, type)
    }

    fun decodeClient(text: String): ConsoleClientMessage {
        val decoded = inspectAndDecode(text, ConsoleMessageDirection.CLIENT_TO_SERVER)
        val payload = decodeClientPayload(decoded.header.type, decoded.envelope.payload)
        return ConsoleClientMessage(decoded.header.messageId, payload).also(::validateClient)
    }

    fun decodeServer(text: String): ConsoleServerMessage {
        val decoded = inspectAndDecode(text, ConsoleMessageDirection.SERVER_TO_CLIENT)
        val payload = decodeServerPayload(decoded.header.type, decoded.envelope.payload)
        return ConsoleServerMessage(decoded.header.messageId, payload).also(::validateServer)
    }

    fun encodeClient(message: ConsoleClientMessage): String {
        validateClient(message)
        return encodeEnvelope(message.messageId, message.type, encodeClientPayload(message.payload))
    }

    fun encodeServer(message: ConsoleServerMessage): String {
        validateServer(message)
        return encodeEnvelope(message.messageId, message.type, encodeServerPayload(message.payload))
    }

    private fun inspectAndDecode(
        text: String,
        expectedDirection: ConsoleMessageDirection,
    ): DecodedEnvelope {
        val header = inspectEnvelope(text)
        if (header.type.direction != expectedDirection) {
            throw ConsoleProtocolException(ProtocolErrorCode.WRONG_MESSAGE_DIRECTION)
        }
        val envelope =
            try {
                json.decodeFromString<WireEnvelope>(text)
            } catch (_: Exception) {
                throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
            }
        return DecodedEnvelope(header, envelope)
    }

    private fun decodeClientPayload(
        type: ConsoleMessageType,
        payload: JsonObject,
    ): ConsoleClientPayload {
        val normalized = normalizeIntegralFields(type, payload)
        return decodePayload {
            when (type) {
                ConsoleMessageType.CLIENT_HELLO -> json.decodeFromJsonElement<ClientHelloPayload>(normalized)
                ConsoleMessageType.LEASE_ACQUIRE -> json.decodeFromJsonElement<LeaseAcquirePayload>(normalized)
                ConsoleMessageType.LEASE_RENEW -> json.decodeFromJsonElement<LeaseRenewPayload>(normalized)
                ConsoleMessageType.LEASE_RELEASE -> json.decodeFromJsonElement<LeaseReleasePayload>(normalized)
                ConsoleMessageType.COMMAND_REQUEST -> json.decodeFromJsonElement<DiscreteCommandRequestPayload>(normalized)
                ConsoleMessageType.CONTROL_FRAME -> json.decodeFromJsonElement<ControlFramePayload>(normalized)
                ConsoleMessageType.CONTROL_NEUTRAL -> json.decodeFromJsonElement<ControlNeutralPayload>(normalized)
                else -> throw ConsoleProtocolException(ProtocolErrorCode.WRONG_MESSAGE_DIRECTION)
            }
        }
    }

    private fun decodeServerPayload(
        type: ConsoleMessageType,
        payload: JsonObject,
    ): ConsoleServerPayload {
        val normalized = normalizeIntegralFields(type, payload)
        return decodePayload {
            when (type) {
                ConsoleMessageType.SERVER_HELLO -> json.decodeFromJsonElement<ServerHelloPayload>(normalized)
                ConsoleMessageType.RUNTIME_STATE -> json.decodeFromJsonElement<RuntimeStatePayload>(normalized)
                ConsoleMessageType.TELEMETRY -> json.decodeFromJsonElement<TelemetryPayload>(normalized)
                ConsoleMessageType.CAPABILITY_SNAPSHOT -> json.decodeFromJsonElement<CapabilitySnapshotPayload>(normalized)
                ConsoleMessageType.HEALTH -> json.decodeFromJsonElement<HealthPayload>(normalized)
                ConsoleMessageType.LEASE_STATE -> json.decodeFromJsonElement<LeaseStatePayload>(normalized)
                ConsoleMessageType.COMMAND_ACK -> json.decodeFromJsonElement<CommandAckPayload>(normalized)
                ConsoleMessageType.COMMAND_RESULT -> json.decodeFromJsonElement<CommandResultPayload>(normalized)
                ConsoleMessageType.CONTROL_ACK -> json.decodeFromJsonElement<ControlAckPayload>(normalized)
                ConsoleMessageType.SAFETY_EVENT -> json.decodeFromJsonElement<SafetyEventPayload>(normalized)
                ConsoleMessageType.PROTOCOL_ERROR -> json.decodeFromJsonElement<ProtocolErrorPayload>(normalized)
                else -> throw ConsoleProtocolException(ProtocolErrorCode.WRONG_MESSAGE_DIRECTION)
            }
        }
    }

    private fun encodeClientPayload(payload: ConsoleClientPayload): JsonElement =
        when (payload) {
            is ClientHelloPayload -> json.encodeToJsonElement(payload)
            is LeaseAcquirePayload -> json.encodeToJsonElement(payload)
            is LeaseRenewPayload -> json.encodeToJsonElement(payload)
            is LeaseReleasePayload -> json.encodeToJsonElement(payload)
            is DiscreteCommandRequestPayload -> json.encodeToJsonElement(payload)
            is ControlFramePayload -> json.encodeToJsonElement(payload)
            is ControlNeutralPayload -> json.encodeToJsonElement(payload)
        }

    private fun encodeServerPayload(payload: ConsoleServerPayload): JsonElement =
        when (payload) {
            is ServerHelloPayload -> json.encodeToJsonElement(payload)
            is RuntimeStatePayload -> json.encodeToJsonElement(payload)
            is TelemetryPayload -> json.encodeToJsonElement(payload)
            is CapabilitySnapshotPayload -> json.encodeToJsonElement(payload)
            is HealthPayload -> json.encodeToJsonElement(payload)
            is LeaseStatePayload -> json.encodeToJsonElement(payload)
            is CommandAckPayload -> json.encodeToJsonElement(payload)
            is CommandResultPayload -> json.encodeToJsonElement(payload)
            is ControlAckPayload -> json.encodeToJsonElement(payload)
            is SafetyEventPayload -> json.encodeToJsonElement(payload)
            is ProtocolErrorPayload -> json.encodeToJsonElement(payload)
        }

    private fun encodeEnvelope(
        messageId: String,
        type: ConsoleMessageType,
        payload: JsonElement,
    ): String {
        val encoded =
            json.encodeToString(
                WireEnvelope(
                    protocolVersion = ConsoleProtocolModule.PROTOCOL_VERSION,
                    messageId = messageId,
                    type = type.wireName,
                    payload = payload as JsonObject,
                ),
            )
        preflightWireText(encoded)
        return encoded
    }

    private fun <T> decodePayload(block: () -> T): T =
        try {
            block()
        } catch (error: ConsoleProtocolException) {
            throw error
        } catch (_: Exception) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_PAYLOAD)
        }

    private fun preflightWireText(text: String) {
        if (
            text.length > ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES ||
            exceedsUtf8ByteLimit(text, ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES)
        ) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        }

        var depth = 0
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                index += 1
                continue
            }

            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > ConsoleProtocolModule.MAX_JSON_NESTING_DEPTH) {
                        throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
                    }
                }

                '}', ']' -> if (depth > 0) depth -= 1
                '-' -> {
                    if (index + 1 >= text.length || text[index + 1] !in '0'..'9') {
                        throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
                    }
                    index = validateJsonNumberAt(text, index)
                    continue
                }

                '+', '.' ->
                    throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)

                in '0'..'9' -> {
                    index = validateJsonNumberAt(text, index)
                    continue
                }
            }
            index += 1
        }
    }

    private fun normalizeIntegralFields(
        type: ConsoleMessageType,
        payload: JsonObject,
    ): JsonObject {
        // JSON has one number type: 500, 500.0 and 5e2 are mathematically the
        // same integer. Normalize before typed decoding so Kotlin matches the
        // browser's Number.isSafeInteger semantics without accepting fractions.
        val fields = INTEGRAL_FIELDS[type].orEmpty()
        if (fields.isEmpty()) return payload
        val normalized = payload.toMutableMap()
        fields.forEach { field ->
            val value = normalized[field] ?: return@forEach
            if (value is JsonNull) return@forEach
            val primitive = value as? JsonPrimitive ?: return@forEach
            if (primitive.isString) return@forEach
            val token = primitive.content
            val integral =
                if (hasZeroCoefficient(token)) {
                    0L
                } else {
                    token.toBigDecimalOrNull()?.exactLongOrNull()
                        ?: throw ConsoleProtocolException(ProtocolErrorCode.INVALID_PAYLOAD)
                }
            normalized[field] = JsonPrimitive(integral)
        }
        return JsonObject(normalized)
    }

    private fun hasZeroCoefficient(token: String): Boolean {
        val coefficientEnd = token.indexOfFirst { it == 'e' || it == 'E' }
            .let { if (it == -1) token.length else it }
        val coefficientStart = if (token.startsWith('-')) 1 else 0
        for (index in coefficientStart until coefficientEnd) {
            val character = token[index]
            if (character != '0' && character != '.') return false
        }
        return coefficientStart < coefficientEnd
    }

    private fun BigDecimal.exactLongOrNull(): Long? =
        try {
            longValueExact()
        } catch (_: ArithmeticException) {
            null
        }

    private fun isJsonNumberCharacter(character: Char): Boolean =
        character in '0'..'9' || character == '-' || character == '+' ||
            character == '.' || character == 'e' || character == 'E'

    private fun validateJsonNumberAt(text: String, start: Int): Int {
        var end = start + 1
        while (end < text.length && isJsonNumberCharacter(text[end])) {
            end += 1
        }
        if (!JSON_NUMBER_PATTERN.matches(text.substring(start, end))) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        }
        return end
    }

    private fun exceedsUtf8ByteLimit(text: String, limit: Int): Boolean {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            bytes +=
                when {
                    character.code <= 0x7f -> 1
                    character.code <= 0x7ff -> 2
                    character.isHighSurrogate() &&
                        index + 1 < text.length &&
                        text[index + 1].isLowSurrogate() -> {
                        index += 1
                        4
                    }

                    else -> 3
                }
            if (bytes > limit) return true
            index += 1
        }
        return false
    }

    private fun validateClient(message: ConsoleClientMessage) {
        try {
            ConsoleMessageValidator.validate(message)
        } catch (_: Exception) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_PAYLOAD)
        }
    }

    private fun validateServer(message: ConsoleServerMessage) {
        try {
            ConsoleMessageValidator.validate(message)
        } catch (_: Exception) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_PAYLOAD)
        }
    }

    private fun JsonObject.requiredString(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        if (!primitive.isString) {
            throw ConsoleProtocolException(ProtocolErrorCode.INVALID_ENVELOPE)
        }
        return primitive.content
    }

    private data class DecodedEnvelope(
        val header: ConsoleEnvelopeHeader,
        val envelope: WireEnvelope,
    )

    @Serializable
    private data class WireEnvelope(
        val protocolVersion: String,
        val messageId: String,
        val type: String,
        val payload: JsonObject,
    )

    companion object {
        private val JSON_NUMBER_PATTERN =
            Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
        private val INTEGRAL_FIELDS: Map<ConsoleMessageType, Set<String>> =
            mapOf(
                ConsoleMessageType.TELEMETRY to setOf("sequence", "batteryPercent"),
                ConsoleMessageType.CAPABILITY_SNAPSHOT to setOf("schemaVersion"),
                ConsoleMessageType.HEALTH to setOf("uptimeMs"),
                ConsoleMessageType.LEASE_ACQUIRE to setOf("requestedTtlMs"),
                ConsoleMessageType.LEASE_RENEW to setOf("requestedTtlMs"),
                ConsoleMessageType.LEASE_STATE to setOf("expiresInMs"),
                ConsoleMessageType.COMMAND_REQUEST to setOf("ttlMs"),
                ConsoleMessageType.CONTROL_FRAME to setOf("inputSequence", "ttlMs"),
                ConsoleMessageType.CONTROL_NEUTRAL to setOf("inputSequence"),
                ConsoleMessageType.CONTROL_ACK to setOf("inputSequence"),
                ConsoleMessageType.SAFETY_EVENT to setOf("lastInputSequence"),
            )

        val ENVELOPE_FIELDS: Set<String> =
            setOf("protocolVersion", "messageId", "type", "payload")

        @OptIn(ExperimentalSerializationApi::class)
        internal val STRICT_JSON: Json =
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = false
                isLenient = false
                coerceInputValues = false
            }
    }
}
