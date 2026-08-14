package com.durendal.droneagent.companion.capability

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityEvidenceValidationTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("companion.repoRoot")))
    private val loader = CapabilityMatrixLoader()
    private val canonical: G520CapabilityMatrixDocument =
        loader.load(
            Files.readString(
                repoRoot.resolve("config/capability-matrix/g520-stack.json"),
                StandardCharsets.UTF_8,
            ),
        )

    @Test
    fun `strict loader rejects unknown JSON fields`() {
        val malformed =
            Files.readString(
                repoRoot.resolve("config/capability-matrix/g520-stack.json"),
                StandardCharsets.UTF_8,
            ).replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"unexpected\": true,")

        assertThrows(SerializationException::class.java) { loader.load(malformed) }
    }

    @Test
    fun `target identity cannot be replaced with another stack`() {
        val wrongTarget =
            canonical.copy(
                matrixId = "pixel-stack",
                targetStack = canonical.targetStack.copy(host = "Pixel 8 Pro"),
            )

        assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(wrongTarget)
        }
    }

    @Test
    fun `core projection cannot bypass evidence validation`() {
        val invalid =
            canonical.copy(
                rows =
                    canonical.rows.map { row ->
                        row.copy(
                            status =
                                if (row.id == "battery") {
                                    G520CapabilityStatus.CONFIRMED
                                } else {
                                    G520CapabilityStatus.UNKNOWN
                                },
                            evidenceRefs = emptyList(),
                        )
                    },
                evidenceRecords = emptyList(),
            )

        assertThrows(IllegalArgumentException::class.java) { invalid.toCoreMatrix() }
    }

    @Test
    fun `mock evidence cannot promote a hardware capability`() {
        val evidence = evidence(environment = EvidenceEnvironment.MOCK, outcome = EvidenceOutcome.PASS)
        val promoted = promote("takeoff_actuation", G520CapabilityStatus.CONFIRMED, evidence)

        val error = assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(promoted)
        }
        assertTrue(error.message!!.contains("first-hand G520 evidence"))
    }

    @Test
    fun `emulator evidence cannot promote a hardware capability`() {
        val evidence =
            evidence(
                id = "emulator-pass",
                environment = EvidenceEnvironment.ANDROID_EMULATOR,
                outcome = EvidenceOutcome.PASS,
                trackingIssue = 2,
            )
        val promoted = promote("headless_boot_service", G520CapabilityStatus.CONFIRMED, evidence)

        val error = assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(promoted)
        }
        assertTrue(error.message!!.contains("first-hand G520 evidence"))
    }

    @Test
    fun `G520 evidence requires an opaque device-set ID`() {
        val incomplete =
            evidence(
                environment = EvidenceEnvironment.G520_HARDWARE,
                outcome = EvidenceOutcome.PASS,
            )
        val promoted = promote("aircraft_connection", G520CapabilityStatus.CONFIRMED, incomplete)

        assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(promoted)
        }
    }

    @Test
    fun `evidence timestamp must be an RFC3339 instant`() {
        val malformed =
            evidence(
                environment = EvidenceEnvironment.MOCK,
                outcome = EvidenceOutcome.FAIL,
            ).copy(capturedAt = "2026-08-15")
        val document = attachUnknownEvidence("takeoff_actuation", malformed)

        assertThrows(DateTimeParseException::class.java) {
            CapabilityMatrixValidator.validate(document)
        }
    }

    @Test
    fun `evidence commit must be a full lowercase SHA`() {
        val malformed =
            evidence(
                environment = EvidenceEnvironment.MOCK,
                outcome = EvidenceOutcome.FAIL,
            ).copy(commit = "ABC123")
        val document = attachUnknownEvidence("takeoff_actuation", malformed)

        assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(document)
        }
    }

    @Test
    fun `raw evidence reference rejects query credentials`() {
        val malformed =
            evidence(
                environment = EvidenceEnvironment.MOCK,
                outcome = EvidenceOutcome.FAIL,
            ).copy(rawLogLocation = "$validRawEvidence?token=secret")
        val document = attachUnknownEvidence("takeoff_actuation", malformed)

        assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(document)
        }
    }

    @Test
    fun `raw evidence reference rejects path traversal`() {
        val malformed =
            evidence(
                environment = EvidenceEnvironment.MOCK,
                outcome = EvidenceOutcome.FAIL,
            ).copy(rawLogLocation = validRawEvidence.replace("/raw/", "/raw/../"))
        val document = attachUnknownEvidence("takeoff_actuation", malformed)

        assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(document)
        }
    }

    @Test
    fun `CONFIRMED accepts matching first-hand G520 PASS evidence`() {
        val firstHand =
            evidence(
                environment = EvidenceEnvironment.G520_HARDWARE,
                outcome = EvidenceOutcome.PASS,
                deviceSetId = validDeviceSetId,
            )

        CapabilityMatrixValidator.validate(
            promote("aircraft_connection", G520CapabilityStatus.CONFIRMED, firstHand),
        )
    }

    @Test
    fun `LIMITED requires a matching first-hand LIMITED outcome`() {
        val pass =
            evidence(
                environment = EvidenceEnvironment.G520_HARDWARE,
                outcome = EvidenceOutcome.PASS,
                deviceSetId = validDeviceSetId,
                trackingIssue = 7,
            )

        val error = assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(
                promote("stream_capability", G520CapabilityStatus.LIMITED, pass),
            )
        }
        assertTrue(error.message!!.contains("matching first-hand G520 evidence"))
    }

    @Test
    fun `production matrix rejects TEMPLATE records`() {
        val template =
            evidence(
                environment = EvidenceEnvironment.G520_HARDWARE,
                outcome = EvidenceOutcome.PASS,
                deviceSetId = validDeviceSetId,
            ).copy(recordKind = EvidenceRecordKind.TEMPLATE)

        assertThrows(IllegalArgumentException::class.java) {
            CapabilityMatrixValidator.validate(
                promote("aircraft_connection", G520CapabilityStatus.CONFIRMED, template),
            )
        }
    }

    @Test
    fun `evidence examples cover mock emulator and hardware but remain templates`() {
        val examples =
            loader.loadTemplates(
                Files.readString(
                    repoRoot.resolve("docs/evidence/capability-record.examples.json"),
                    StandardCharsets.UTF_8,
                ),
            )

        assertTrue(examples.templates.all { it.recordKind == EvidenceRecordKind.TEMPLATE })
        assertTrue(examples.templates.map { it.environment }.toSet() == EvidenceEnvironment.entries.toSet())
    }

    private fun promote(
        rowId: String,
        status: G520CapabilityStatus,
        record: CapabilityEvidenceRecord,
    ): G520CapabilityMatrixDocument =
        canonical.copy(
            rows =
                canonical.rows.map { row ->
                    if (row.id == rowId) {
                        row.copy(status = status, evidenceRefs = listOf(record.id))
                    } else {
                        row
                    }
                },
            evidenceRecords = listOf(record),
        )

    private fun attachUnknownEvidence(
        rowId: String,
        record: CapabilityEvidenceRecord,
    ): G520CapabilityMatrixDocument =
        canonical.copy(
            rows =
                canonical.rows.map { row ->
                    if (row.id == rowId) row.copy(evidenceRefs = listOf(record.id)) else row
                },
            evidenceRecords = listOf(record),
        )

    private fun evidence(
        id: String = "test-evidence",
        environment: EvidenceEnvironment,
        outcome: EvidenceOutcome,
        deviceSetId: String? = null,
        trackingIssue: Int = 9,
    ): CapabilityEvidenceRecord =
        CapabilityEvidenceRecord(
            id = id,
            recordKind = EvidenceRecordKind.EVIDENCE,
            environment = environment,
            capturedAt = "2026-08-15T01:02:03Z",
            commit = "abcdef0123456789abcdef0123456789abcdef01",
            operator = "test-operator",
            runtimeProfile = "test-profile",
            deviceSetId = deviceSetId,
            rawLogLocation = validRawEvidence,
            trackingIssue = trackingIssue,
            outcome = outcome,
            summary = "Test evidence record.",
        )

    private val validDeviceSetId = "device-set:6f0b65f0-48b3-4e16-970a-a2358572f8b1"
    private val validRawEvidence =
        "evidence://bundle/6f0b65f0-48b3-4e16-970a-a2358572f8b1/raw/session.jsonl"
}
