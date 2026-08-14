package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.capability.CapabilityMatrixLoader
import com.durendal.droneagent.companion.capability.CapabilityMatrixValidator
import com.durendal.droneagent.companion.capability.G520CapabilityMatrixDocument
import com.durendal.droneagent.companion.capability.G520CapabilityRow
import com.durendal.droneagent.core.capability.CapabilityMatrix
import com.durendal.droneagent.core.capability.CapabilityStatus
import java.util.Collections

/**
 * Immutable evidence snapshot for console clients.
 *
 * Adapter, connection, actuation-lock and lease state are live runtime facts
 * and must be published separately; they never overwrite this evidence model.
 */
class G520CapabilityMatrixProvider(
    evidenceMatrix: G520CapabilityMatrixDocument = CapabilityMatrixLoader.loadBundled(),
) {
    val evidenceMatrix: G520CapabilityMatrixDocument = evidenceMatrix.detachedCopy()
    val coreEvidenceMatrix: CapabilityMatrix = this.evidenceMatrix.toCoreMatrix()

    init {
        CapabilityMatrixValidator.validate(this.evidenceMatrix)
    }

    fun snapshot(
        adapterId: String,
        reportedRuntimeCapabilities: CapabilityMatrix,
    ): ConsoleCapabilitySnapshot {
        require(adapterId.isNotBlank()) { "adapterId must not be blank" }
        return ConsoleCapabilitySnapshot(
            evidenceMatrix = evidenceMatrix,
            runtimeStatus =
                RuntimeCapabilitySnapshot(
                    adapterId = adapterId,
                    entries =
                        immutableList(reportedRuntimeCapabilities.entries().map { entry ->
                            RuntimeCapabilityEntry(
                                id = entry.capability.key,
                                status = entry.status,
                                detail = entry.evidence,
                            )
                        }),
                ),
        )
    }
}

data class ConsoleCapabilitySnapshot(
    val evidenceMatrix: G520CapabilityMatrixDocument,
    val runtimeStatus: RuntimeCapabilitySnapshot,
)

data class RuntimeCapabilitySnapshot(
    val adapterId: String,
    val entries: List<RuntimeCapabilityEntry>,
)

data class RuntimeCapabilityEntry(
    val id: String,
    val status: CapabilityStatus,
    val detail: String,
)

private fun G520CapabilityMatrixDocument.detachedCopy(): G520CapabilityMatrixDocument =
    copy(
        targetStack = targetStack.copy(),
        rows =
            immutableList(rows.map { row: G520CapabilityRow ->
                row.copy(
                    trackingIssues = immutableList(row.trackingIssues),
                    evidenceRefs = immutableList(row.evidenceRefs),
                )
            }),
        evidenceRecords = immutableList(evidenceRecords.map { it.copy() }),
    )

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
