package com.durendal.droneagent.companion.console.protocol

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleProtocolV11ContractTest {
    private val repoRoot = Path.of(requireNotNull(System.getProperty("companion.repoRoot")))
    private val contractRoot = repoRoot.resolve("contracts/console-protocol/v1.1")
    private val fixtureRoot = contractRoot.resolve("fixtures")
    private val codec = ConsoleProtocolCodec()
    private val manifest =
        ConsoleProtocolCodec.STRICT_JSON.decodeFromString<DeltaManifest>(
            Files.readString(contractRoot.resolve("manifest.json"), StandardCharsets.UTF_8),
        )

    @Test
    fun `v1_1 manifest is an exact server-only delta over frozen v1_0`() {
        assertEquals(1, manifest.schemaVersion)
        assertEquals("1.1", manifest.protocolVersion)
        assertEquals(ConsoleProtocolModule.PROTOCOL_VERSION, manifest.baseProtocolVersion)
        assertEquals(
            ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION,
            manifest.bootstrapProtocolVersion,
        )
        assertEquals(
            listOf("protocolVersion", "messageId", "type", "payload"),
            manifest.envelopeFields,
        )
        assertEquals(emptyList<String>(), manifest.addedClientMessageTypes)
        assertEquals(
            listOf(ConsoleMessageType.COMMISSIONING_AUTHORITY_STATE.wireName),
            manifest.addedServerMessageTypes,
        )
        assertFalse(
            ConsoleMessageType.COMMISSIONING_AUTHORITY_STATE.isAvailableIn(
                ConsoleProtocolModule.PROTOCOL_VERSION,
            ),
        )
        assertTrue(
            ConsoleMessageType.COMMISSIONING_AUTHORITY_STATE.isAvailableIn(
                ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION,
            ),
        )

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
            manifest.commissioningAuthorityState.payloadFields,
        )
        assertEquals(listOf("inactive", "active"), manifest.commissioningAuthorityState.states)
        assertEquals(
            listOf("takeoff", "landing", "return_to_home", "virtual_stick"),
            manifest.commissioningAuthorityState.intents,
        )
        assertEquals(
            listOf(
                "no_active_session",
                "host_revoked",
                "ttl_expired",
                "operator_disconnected",
                "observation_lost",
                "runtime_state_changed",
                "server_closed",
                "audit_unavailable",
                "deadline_unavailable",
            ),
            manifest.commissioningAuthorityState.reasons,
        )
        assertEquals(
            listOf("stateRevision", "generation"),
            manifest.commissioningAuthorityState.canonicalDecimalStringFields,
        )
        assertEquals(
            LongRangeContract(
                ConsoleProtocolModule.MIN_COMMISSIONING_AUTHORITY_REMAINING_TTL_MS,
                ConsoleProtocolModule.MAX_COMMISSIONING_AUTHORITY_REMAINING_TTL_MS,
            ),
            manifest.commissioningAuthorityState.remainingTtlMs,
        )
        assertTrue(manifest.sessionOrdering.serverHelloBeforeInitialAuthorityState)
        assertTrue(manifest.sessionOrdering.terminalAuthorityStateBeforeTransportCloseWhenWritable)
    }

    @Test
    fun `delta locks the unchanged v1_0 canonical base`() {
        val v1Root = repoRoot.resolve("contracts/console-protocol/v1")
        val v1ManifestDigest = sha256(Files.readAllBytes(v1Root.resolve("manifest.json")))
        val canonicalLine =
            Files.readString(v1Root.resolve("CANONICAL.sha256"), StandardCharsets.UTF_8)
                .trimEnd()

        assertEquals(manifest.baseCanonicalSha256, v1ManifestDigest)
        assertEquals("${manifest.baseCanonicalSha256}  manifest.json", canonicalLine)
    }

    @Test
    fun `delta manifest locks every fixture and Kotlin round-trips its declared version`() {
        val listedPaths = manifest.fixtures.map(DeltaFixture::path)
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
            val fixture = contractRoot.resolve(entry.path).normalize()
            assertTrue(fixture.startsWith(fixtureRoot))
            assertEquals(entry.sha256, sha256(Files.readAllBytes(fixture)))
            val text = Files.readString(fixture, StandardCharsets.UTF_8)
            val header = codec.inspectEnvelope(text, entry.protocolVersion)
            assertEquals(entry.protocolVersion, header.protocolVersion)
            assertEquals(entry.type, header.type.wireName)
            when (entry.direction) {
                "client_to_server" -> {
                    val decoded = codec.decodeClient(text, entry.protocolVersion)
                    assertEquals(
                        decoded,
                        codec.decodeClient(
                            codec.encodeClient(decoded, entry.protocolVersion),
                            entry.protocolVersion,
                        ),
                    )
                }

                "server_to_client" -> {
                    val decoded = codec.decodeServer(text, entry.protocolVersion)
                    assertEquals(
                        decoded,
                        codec.decodeServer(
                            codec.encodeServer(decoded, entry.protocolVersion),
                            entry.protocolVersion,
                        ),
                    )
                }

                else -> error("Unknown fixture direction")
            }
        }
    }

    @Test
    fun `fixtures distinguish bootstrap nonzero neutral active and terminal observations`() {
        val clientHello =
            decodeClientFixture("client_hello_v1_1_support.json").payload as ClientHelloPayload
        assertEquals(listOf("1.1", "1.0"), clientHello.supportedProtocolVersions)
        assertEquals(
            "1.1",
            ConsoleProtocolModule.selectProtocolVersion(clientHello.supportedProtocolVersions),
        )

        val serverHello =
            decodeServerFixture("server_hello_v1_1.json").payload as ServerHelloPayload
        assertEquals("1.1", serverHello.selectedProtocolVersion)

        val initial =
            decodeServerFixture("commissioning_authority_initial_nonzero_revision.json")
                .payload as CommissioningAuthorityStatePayload
        val active =
            decodeServerFixture("commissioning_authority_active.json")
                .payload as CommissioningAuthorityStatePayload
        val terminal =
            decodeServerFixture("commissioning_authority_terminal_host_revoked.json")
                .payload as CommissioningAuthorityStatePayload

        assertTrue(initial.stateRevision.toLong() > 0)
        assertEquals(CommissioningAuthorityState.INACTIVE, initial.state)
        assertEquals(null, initial.commissioningId)
        assertEquals("0", initial.generation)
        assertEquals(CommissioningAuthorityReason.NO_ACTIVE_SESSION, initial.reason)

        assertEquals(CommissioningAuthorityState.ACTIVE, active.state)
        assertTrue(active.allowedIntents.isNotEmpty())
        assertEquals(null, active.reason)

        assertEquals(CommissioningAuthorityState.INACTIVE, terminal.state)
        assertEquals(active.commissioningId, terminal.commissioningId)
        assertEquals(active.generation, terminal.generation)
        assertTrue(terminal.allowedIntents.isEmpty())
        assertEquals(CommissioningAuthorityReason.HOST_REVOKED, terminal.reason)
        assertTrue(initial.stateRevision.toLong() < active.stateRevision.toLong())
        assertTrue(active.stateRevision.toLong() < terminal.stateRevision.toLong())
    }

    @Test
    fun `v1_1 canonical lock deterministically hashes only the delta manifest`() {
        val line =
            Files.readString(contractRoot.resolve("CANONICAL.sha256"), StandardCharsets.UTF_8)
                .trimEnd()
        val parts = line.split(Regex("\\s+"), limit = 2)
        assertEquals(
            listOf(
                sha256(Files.readAllBytes(contractRoot.resolve("manifest.json"))),
                "manifest.json",
            ),
            parts,
        )
    }

    private fun decodeClientFixture(fileName: String): ConsoleClientMessage {
        val entry = manifest.fixtures.single { it.path == "fixtures/$fileName" }
        return codec.decodeClient(
            Files.readString(contractRoot.resolve(entry.path), StandardCharsets.UTF_8),
            entry.protocolVersion,
        )
    }

    private fun decodeServerFixture(fileName: String): ConsoleServerMessage {
        val entry = manifest.fixtures.single { it.path == "fixtures/$fileName" }
        return codec.decodeServer(
            Files.readString(contractRoot.resolve(entry.path), StandardCharsets.UTF_8),
            entry.protocolVersion,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class DeltaManifest(
        val schemaVersion: Int,
        val protocolVersion: String,
        val baseProtocolVersion: String,
        val baseCanonicalSha256: String,
        val bootstrapProtocolVersion: String,
        val envelopeFields: List<String>,
        val addedClientMessageTypes: List<String>,
        val addedServerMessageTypes: List<String>,
        val commissioningAuthorityState: AuthorityContract,
        val sessionOrdering: SessionOrderingContract,
        val fixtures: List<DeltaFixture>,
    )

    @Serializable
    private data class AuthorityContract(
        val payloadFields: List<String>,
        val states: List<String>,
        val intents: List<String>,
        val reasons: List<String>,
        val canonicalDecimalStringFields: List<String>,
        val remainingTtlMs: LongRangeContract,
    )

    @Serializable
    private data class LongRangeContract(
        val minimum: Long,
        val maximum: Long,
    )

    @Serializable
    private data class SessionOrderingContract(
        val serverHelloBeforeInitialAuthorityState: Boolean,
        val terminalAuthorityStateBeforeTransportCloseWhenWritable: Boolean,
    )

    @Serializable
    private data class DeltaFixture(
        val path: String,
        val protocolVersion: String,
        val direction: String,
        val type: String,
        val sha256: String,
    )
}
