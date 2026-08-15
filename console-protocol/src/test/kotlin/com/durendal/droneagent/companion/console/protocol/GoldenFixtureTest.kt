package com.durendal.droneagent.companion.console.protocol

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenFixtureTest {
    private val repoRoot = Path.of(requireNotNull(System.getProperty("companion.repoRoot")))
    private val contractRoot = repoRoot.resolve("contracts/console-protocol/v1")
    private val fixtureRoot = contractRoot.resolve("fixtures")
    private val codec = ConsoleProtocolCodec()
    private val manifest =
        ConsoleProtocolCodec.STRICT_JSON.decodeFromString<FixtureManifest>(
            Files.readString(contractRoot.resolve("manifest.json"), StandardCharsets.UTF_8),
        )

    @Test
    fun `manifest declares the exact v1 envelope and direction inventories`() {
        assertEquals(1, manifest.schemaVersion)
        assertEquals(ConsoleProtocolModule.PROTOCOL_VERSION, manifest.protocolVersion)
        assertEquals(
            listOf("protocolVersion", "messageId", "type", "payload"),
            manifest.envelopeFields,
        )
        assertEquals(
            FixtureLimits(
                maxWireMessageUtf8Bytes = ConsoleProtocolModule.MAX_WIRE_MESSAGE_UTF8_BYTES,
                maxJsonNestingDepth = ConsoleProtocolModule.MAX_JSON_NESTING_DEPTH,
                maxSafeInteger = ConsoleProtocolModule.MAX_SAFE_INTEGER,
                identifierMaxLength = ConsoleProtocolModule.MAX_IDENTIFIER_LENGTH,
                nameMaxLength = ConsoleProtocolModule.MAX_NAME_LENGTH,
                credentialMaxLength = ConsoleProtocolModule.MAX_CREDENTIAL_LENGTH,
                textMaxLength = ConsoleProtocolModule.MAX_TEXT_LENGTH,
                negotiationMaxItems = ConsoleProtocolModule.MAX_NEGOTIATION_VALUES,
                capabilityMaxRows = ConsoleProtocolModule.MAX_CAPABILITY_ROWS,
                leaseTtlMs =
                    IntegerRange(
                        ConsoleProtocolModule.MIN_LEASE_TTL_MS,
                        ConsoleProtocolModule.MAX_LEASE_TTL_MS,
                    ),
                commandTtlMs =
                    IntegerRange(
                        ConsoleProtocolModule.MIN_COMMAND_TTL_MS,
                        ConsoleProtocolModule.MAX_COMMAND_TTL_MS,
                    ),
                controlTtlMs =
                    IntegerRange(
                        ConsoleProtocolModule.MIN_CONTROL_TTL_MS,
                        ConsoleProtocolModule.MAX_CONTROL_TTL_MS,
                    ),
            ),
            manifest.limits,
        )
        assertEquals(
            ConsoleMessageType.entries
                .filter { it.direction == ConsoleMessageDirection.CLIENT_TO_SERVER }
                .map(ConsoleMessageType::wireName),
            manifest.clientMessageTypes,
        )
        assertEquals(
            ConsoleMessageType.entries
                .filter { it.direction == ConsoleMessageDirection.SERVER_TO_CLIENT }
                .map(ConsoleMessageType::wireName),
            manifest.serverMessageTypes,
        )
        assertEquals(
            ConsoleMessageType.entries.map(ConsoleMessageType::wireName).toSet(),
            manifest.fixtures.map(FixtureEntry::type).toSet(),
        )
        assertEquals(
            ProtocolErrorCode.entries.map(ProtocolErrorCode::wireName),
            manifest.protocolErrorCodes,
        )
    }

    @Test
    fun `manifest locks every and only canonical fixture`() {
        val listedPaths = manifest.fixtures.map(FixtureEntry::path)
        assertEquals(listedPaths.sorted(), listedPaths)
        assertEquals(listedPaths.size, listedPaths.toSet().size)

        val actualPaths =
            Files.list(fixtureRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .map { "fixtures/${it.fileName}" }
                    .collect(Collectors.toSet())
            }
        assertEquals(actualPaths, listedPaths.toSet())

        manifest.fixtures.forEach { entry ->
            val relative = Path.of(entry.path)
            assertFalse(relative.isAbsolute)
            val resolved = contractRoot.resolve(relative).normalize()
            assertTrue(resolved.startsWith(fixtureRoot))
            assertEquals(entry.sha256, sha256(Files.readAllBytes(resolved)))
        }
    }

    @Test
    fun `canonical lock deterministically hashes only the manifest`() {
        val line =
            Files.readString(contractRoot.resolve("CANONICAL.sha256"), StandardCharsets.UTF_8)
                .trimEnd()
        val parts = line.split(Regex("\\s+"), limit = 2)
        assertEquals(2, parts.size)
        assertEquals("manifest.json", parts[1])
        assertEquals(
            sha256(Files.readAllBytes(contractRoot.resolve(parts[1]))),
            parts[0],
        )
    }

    @Test
    fun `Kotlin decodes and round-trips every cross-language fixture`() {
        manifest.fixtures.forEach { entry ->
            val text = Files.readString(contractRoot.resolve(entry.path), StandardCharsets.UTF_8)
            val header = codec.inspectEnvelope(text)
            assertEquals(entry.type, header.type.wireName)
            when (entry.direction) {
                "client_to_server" -> {
                    assertEquals(ConsoleMessageDirection.CLIENT_TO_SERVER, header.type.direction)
                    val first = codec.decodeClient(text)
                    assertEquals(first, codec.decodeClient(codec.encodeClient(first)))
                }

                "server_to_client" -> {
                    assertEquals(ConsoleMessageDirection.SERVER_TO_CLIENT, header.type.direction)
                    val first = codec.decodeServer(text)
                    assertEquals(first, codec.decodeServer(codec.encodeServer(first)))
                }

                else -> error("Unknown fixture direction")
            }
        }
    }

    @Test
    fun `fixtures cover all three discrete actions and keep virtual stick separate`() {
        val commandActions =
            manifest.fixtures
                .filter { it.type == ConsoleMessageType.COMMAND_REQUEST.wireName }
                .map { entry ->
                    val message =
                        codec.decodeClient(
                            Files.readString(contractRoot.resolve(entry.path), StandardCharsets.UTF_8),
                        )
                    (message.payload as DiscreteCommandRequestPayload).action
                }.toSet()
        assertEquals(DiscreteCommandAction.entries.toSet(), commandActions)

        val controlFixture = manifest.fixtures.single { it.type == "control_frame" }
        val frame =
            codec.decodeClient(
                Files.readString(contractRoot.resolve(controlFixture.path), StandardCharsets.UTF_8),
            ).payload as ControlFramePayload
        assertEquals(7L, frame.inputSequence)
        assertEquals(250, frame.ttlMs)
    }

    @Test
    fun `capability fixture remains an all UNKNOWN G520 snapshot`() {
        val entry = manifest.fixtures.single { it.type == "capability_snapshot" }
        val snapshot =
            codec.decodeServer(
                Files.readString(contractRoot.resolve(entry.path), StandardCharsets.UTF_8),
            ).payload as CapabilitySnapshotPayload

        assertEquals(ConsoleProtocolModule.G520_MATRIX_ID, snapshot.matrixId)
        assertEquals(17, snapshot.rows.size)
        assertTrue(snapshot.rows.all { it.status == CapabilityEvidenceStatus.UNKNOWN })
    }

    @Test
    fun `golden messages avoid wall-clock timestamps`() {
        manifest.fixtures.forEach { entry ->
            val element =
                ConsoleProtocolCodec.STRICT_JSON.parseToJsonElement(
                    Files.readString(contractRoot.resolve(entry.path), StandardCharsets.UTF_8),
                )
            assertFalse(entry.path, hasUnstableTimestampKey(element))
        }
    }

    @Test
    fun `accepted command ack binds the complete takeoff intent digest`() {
        val requestEntry = manifest.fixtures.single { it.path.endsWith("command_request_takeoff.json") }
        val ackEntry = manifest.fixtures.single { it.path.endsWith("command_ack_accepted.json") }
        val request =
            codec.decodeClient(
                Files.readString(contractRoot.resolve(requestEntry.path), StandardCharsets.UTF_8),
            ).payload as DiscreteCommandRequestPayload
        val ack =
            codec.decodeServer(
                Files.readString(contractRoot.resolve(ackEntry.path), StandardCharsets.UTF_8),
            ).payload as CommandAckPayload

        assertEquals(request.commandId, ack.commandId)
        assertEquals(ConsoleIntentDigest.sha256(request), ack.intentDigestSha256)
    }

    private fun hasUnstableTimestampKey(element: JsonElement): Boolean =
        when (element) {
            is JsonObject ->
                element.keys.any { it == "timestamp" || it == "capturedAt" || it == "observedAt" } ||
                    element.values.any(::hasUnstableTimestampKey)
            is JsonArray -> element.any(::hasUnstableTimestampKey)
            else -> false
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class FixtureManifest(
        val schemaVersion: Int,
        val protocolVersion: String,
        val envelopeFields: List<String>,
        val limits: FixtureLimits,
        val clientMessageTypes: List<String>,
        val serverMessageTypes: List<String>,
        val protocolErrorCodes: List<String>,
        val fixtures: List<FixtureEntry>,
    )

    @Serializable
    private data class FixtureLimits(
        val maxWireMessageUtf8Bytes: Int,
        val maxJsonNestingDepth: Int,
        val maxSafeInteger: Long,
        val identifierMaxLength: Int,
        val nameMaxLength: Int,
        val credentialMaxLength: Int,
        val textMaxLength: Int,
        val negotiationMaxItems: Int,
        val capabilityMaxRows: Int,
        val leaseTtlMs: IntegerRange,
        val commandTtlMs: IntegerRange,
        val controlTtlMs: IntegerRange,
    )

    @Serializable
    private data class IntegerRange(
        val minimum: Int,
        val maximum: Int,
    )

    @Serializable
    private data class FixtureEntry(
        val path: String,
        val direction: String,
        val type: String,
        val sha256: String,
    )
}
