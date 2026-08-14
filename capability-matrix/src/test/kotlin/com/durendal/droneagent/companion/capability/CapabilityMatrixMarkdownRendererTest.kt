package com.durendal.droneagent.companion.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMatrixMarkdownRendererTest {
    @Test
    fun `markdown table cells escape pipes backslashes and newlines`() {
        assertEquals(
            "one \\| two<br>three \\\\ four",
            CapabilityMatrixMarkdownRenderer.escapeCell("one | two\nthree \\ four"),
        )
    }

    @Test
    fun `evidence review table includes device set issue summary and raw reference`() {
        val record =
            CapabilityEvidenceRecord(
                id = "renderer-evidence",
                recordKind = EvidenceRecordKind.EVIDENCE,
                environment = EvidenceEnvironment.G520_HARDWARE,
                capturedAt = "2026-08-15T01:02:03Z",
                commit = "abcdef0123456789abcdef0123456789abcdef01",
                operator = "renderer-test-operator",
                runtimeProfile = "renderer-test-profile",
                deviceSetId = "device-set:6f0b65f0-48b3-4e16-970a-a2358572f8b1",
                rawLogLocation =
                    "evidence://bundle/6f0b65f0-48b3-4e16-970a-a2358572f8b1/raw/session.jsonl",
                trackingIssue = 9,
                outcome = EvidenceOutcome.FAIL,
                summary = "First-hand failure remains UNKNOWN.",
            )
        val base = CapabilityMatrixLoader.loadBundled()
        val document =
            base.copy(
                rows =
                    base.rows.map { row ->
                        if (row.id == "takeoff_actuation") {
                            row.copy(
                                status = G520CapabilityStatus.UNKNOWN,
                                evidenceRefs = listOf(record.id),
                            )
                        } else {
                            row
                        }
                    },
                evidenceRecords = listOf(record),
            )

        val rendered = CapabilityMatrixMarkdownRenderer.render(document)
        assertTrue(rendered.contains(record.deviceSetId!!))
        assertTrue(rendered.contains("#9"))
        assertTrue(rendered.contains(record.summary))
        assertTrue(rendered.contains(record.rawLogLocation))
    }
}
