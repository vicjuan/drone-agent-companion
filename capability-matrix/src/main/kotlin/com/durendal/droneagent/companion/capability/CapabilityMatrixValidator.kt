package com.durendal.droneagent.companion.capability

import com.durendal.droneagent.core.capability.Capability
import java.time.Instant
import java.time.LocalDate

/** Fail-closed validation for status promotion and evidence provenance. */
object CapabilityMatrixValidator {
    private val rowIdPattern = Regex("^[a-z][a-z0-9_]*$")
    private val evidenceIdPattern = Regex("^[a-z0-9][a-z0-9._-]*$")
    private val commitPattern = Regex("^[0-9a-f]{40}$")
    private val deviceSetIdPattern =
        Regex("^device-set:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private const val TEMPLATE_DEVICE_SET_ID = "device-set:00000000-0000-4000-8000-000000000000"
    private val rawEvidencePattern =
        Regex("^evidence://bundle/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")
    private const val TEMPLATE_RAW_EVIDENCE_PREFIX =
        "evidence://bundle/00000000-0000-4000-8000-000000000000/"

    fun validate(document: G520CapabilityMatrixDocument) {
        require(document.schemaVersion == 1) { "Unsupported matrix schemaVersion: ${document.schemaVersion}" }
        require(document.matrixId == G520TargetIdentity.MATRIX_ID) {
            "Unexpected target matrixId: ${document.matrixId}"
        }
        require(document.targetStack.aircraft == G520TargetIdentity.AIRCRAFT) {
            "Unexpected targetStack.aircraft: ${document.targetStack.aircraft}"
        }
        require(document.targetStack.remoteController == G520TargetIdentity.REMOTE_CONTROLLER) {
            "Unexpected targetStack.remoteController: ${document.targetStack.remoteController}"
        }
        require(document.targetStack.host == G520TargetIdentity.HOST) {
            "Unexpected targetStack.host: ${document.targetStack.host}"
        }
        val lastUpdated = LocalDate.parse(document.lastUpdated)

        val rowIds = document.rows.map(G520CapabilityRow::id)
        require(rowIds.size == rowIds.toSet().size) { "Duplicate capability row IDs are not allowed" }
        require(rowIds.toSet() == RequiredG520Capabilities.rowIds) {
            val missing = RequiredG520Capabilities.rowIds - rowIds.toSet()
            val unexpected = rowIds.toSet() - RequiredG520Capabilities.rowIds
            "Capability inventory mismatch; missing=$missing unexpected=$unexpected"
        }
        val vendorKeys = Capability.entries.map(Capability::key).toSet()
        require(rowIds.count { it in vendorKeys } == vendorKeys.size) {
            "Every upstream core capability must appear exactly once"
        }

        document.rows.forEach(::validateRowShape)

        val evidenceById = document.evidenceRecords.associateBy(CapabilityEvidenceRecord::id)
        require(evidenceById.size == document.evidenceRecords.size) {
            "Duplicate evidence record IDs are not allowed"
        }
        document.evidenceRecords.forEach { record ->
            require(record.recordKind == EvidenceRecordKind.EVIDENCE) {
                "Production matrix cannot contain TEMPLATE record ${record.id}"
            }
            validateEvidenceShape(record)
            require(!Instant.parse(record.capturedAt).atOffset(java.time.ZoneOffset.UTC).toLocalDate().isAfter(lastUpdated)) {
                "Evidence ${record.id} is newer than matrix lastUpdated"
            }
        }

        val allReferences = document.rows.flatMap(G520CapabilityRow::evidenceRefs)
        document.rows.forEach { row ->
            val evidence = row.evidenceRefs.map { reference ->
                requireNotNull(evidenceById[reference]) {
                    "Capability ${row.id} references missing evidence $reference"
                }
            }
            validatePromotion(row, evidence)
        }
        val referencedIds = allReferences.toSet()
        require(evidenceById.keys == referencedIds) {
            "Evidence records must be referenced by a capability row; orphaned=${evidenceById.keys - referencedIds}"
        }
    }

    fun validateTemplates(templateSet: CapabilityEvidenceTemplateSet) {
        require(templateSet.schemaVersion == 1) {
            "Unsupported evidence template schemaVersion: ${templateSet.schemaVersion}"
        }
        require(templateSet.templates.isNotEmpty()) { "Evidence template set must not be empty" }
        require(templateSet.templates.map(CapabilityEvidenceRecord::id).toSet().size == templateSet.templates.size) {
            "Duplicate evidence template IDs are not allowed"
        }
        templateSet.templates.forEach { template ->
            require(template.recordKind == EvidenceRecordKind.TEMPLATE) {
                "Example ${template.id} must be marked TEMPLATE"
            }
            validateEvidenceShape(template)
        }
        require(templateSet.templates.map(CapabilityEvidenceRecord::environment).toSet() == EvidenceEnvironment.entries.toSet()) {
            "Examples must cover MOCK, ANDROID_EMULATOR and G520_HARDWARE"
        }
    }

    private fun validateRowShape(row: G520CapabilityRow) {
        require(rowIdPattern.matches(row.id)) { "Invalid capability row ID: ${row.id}" }
        require(row.title.isNotBlank()) { "Capability ${row.id} has blank title" }
        require(row.assessment.isNotBlank()) { "Capability ${row.id} has blank assessment" }
        require(row.verificationMethod.isNotBlank()) {
            "Capability ${row.id} has blank verificationMethod"
        }
        require(row.trackingIssues.isNotEmpty() && row.trackingIssues.all { it > 0 }) {
            "Capability ${row.id} must reference at least one valid tracking issue"
        }
        require(row.trackingIssues.size == row.trackingIssues.toSet().size) {
            "Capability ${row.id} has duplicate tracking issues"
        }
        require(row.evidenceRefs.size == row.evidenceRefs.toSet().size) {
            "Capability ${row.id} has duplicate evidence references"
        }
    }

    private fun validateEvidenceShape(record: CapabilityEvidenceRecord) {
        require(evidenceIdPattern.matches(record.id)) { "Invalid evidence record ID: ${record.id}" }
        Instant.parse(record.capturedAt)
        require(commitPattern.matches(record.commit)) {
            "Evidence ${record.id} must contain a lowercase 40-character commit SHA"
        }
        require(record.operator.isNotBlank()) { "Evidence ${record.id} has blank operator" }
        require(record.runtimeProfile.isNotBlank()) { "Evidence ${record.id} has blank runtimeProfile" }
        require(rawEvidencePattern.matches(record.rawLogLocation)) {
            "Evidence ${record.id} must use an opaque evidence://bundle reference without credentials or query data"
        }
        require(
            record.rawLogLocation
                .substringAfter("evidence://bundle/")
                .substringAfter('/')
                .split('/')
                .none { it == "." || it == ".." },
        ) {
            "Evidence ${record.id} raw reference cannot contain dot path segments"
        }
        require(record.trackingIssue > 0) { "Evidence ${record.id} has invalid trackingIssue" }
        require(record.summary.isNotBlank()) { "Evidence ${record.id} has blank summary" }
        if (record.recordKind == EvidenceRecordKind.EVIDENCE) {
            require(record.commit.any { it != '0' }) {
                "Evidence ${record.id} cannot use the all-zero template commit"
            }
            require(record.operator != "example-operator") {
                "Evidence ${record.id} cannot use the template operator"
            }
            require(!record.rawLogLocation.startsWith(TEMPLATE_RAW_EVIDENCE_PREFIX)) {
                "Evidence ${record.id} cannot use the template evidence bundle"
            }
        }

        if (record.environment == EvidenceEnvironment.G520_HARDWARE) {
            require(record.deviceSetId != null && deviceSetIdPattern.matches(record.deviceSetId)) {
                "G520 evidence ${record.id} requires an opaque version-4 deviceSetId"
            }
            if (record.recordKind == EvidenceRecordKind.EVIDENCE) {
                require(record.deviceSetId != TEMPLATE_DEVICE_SET_ID) {
                    "G520 evidence ${record.id} cannot use the template deviceSetId"
                }
            }
        } else {
            require(record.deviceSetId == null) {
                "${record.environment} evidence ${record.id} must not claim a hardware device set"
            }
        }
    }

    private fun validatePromotion(
        row: G520CapabilityRow,
        evidence: List<CapabilityEvidenceRecord>,
    ) {
        require(evidence.all { it.trackingIssue in row.trackingIssues }) {
            "Capability ${row.id} cites evidence from an unrelated tracking issue"
        }
        if (row.status == G520CapabilityStatus.UNKNOWN) return

        require(evidence.isNotEmpty()) {
            "Capability ${row.id} cannot be ${row.status} without evidence"
        }
        val requiredOutcome =
            when (row.status) {
                G520CapabilityStatus.CONFIRMED -> EvidenceOutcome.PASS
                G520CapabilityStatus.LIMITED -> EvidenceOutcome.LIMITED
                G520CapabilityStatus.UNKNOWN -> error("handled above")
            }
        require(
            evidence.any { record ->
                record.environment == EvidenceEnvironment.G520_HARDWARE &&
                    record.recordKind == EvidenceRecordKind.EVIDENCE &&
                    record.outcome == requiredOutcome
            },
        ) {
            "Capability ${row.id} cannot be ${row.status} without matching first-hand G520 evidence"
        }
    }
}
