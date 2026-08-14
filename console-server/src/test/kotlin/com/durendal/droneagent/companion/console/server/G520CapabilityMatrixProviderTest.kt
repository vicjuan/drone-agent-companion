package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.capability.G520CapabilityStatus
import com.durendal.droneagent.core.capability.Capability
import com.durendal.droneagent.core.capability.CapabilityEntry
import com.durendal.droneagent.core.capability.CapabilityMatrix
import com.durendal.droneagent.core.capability.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class G520CapabilityMatrixProviderTest {
    @Test
    fun `optimistic runtime report remains separate from hardware evidence`() {
        val initial =
            com.durendal.droneagent.companion.capability.CapabilityMatrixLoader.loadBundled().let { document ->
                document.copy(
                    rows =
                        document.rows.map { row ->
                            row.copy(status = G520CapabilityStatus.UNKNOWN, evidenceRefs = emptyList())
                        },
                    evidenceRecords = emptyList(),
                )
            }
        val provider = G520CapabilityMatrixProvider(initial)
        val optimisticRuntime =
            CapabilityMatrix(
                Capability.entries.map { capability ->
                    CapabilityEntry(
                        capability = capability,
                        status = CapabilityStatus.CONFIRMED,
                        evidence = "SIMULATED runtime report",
                    )
                },
            )
        val snapshot = provider.snapshot("adapter-mock", optimisticRuntime)

        assertTrue(snapshot.evidenceMatrix.rows.all { it.status == G520CapabilityStatus.UNKNOWN })
        assertTrue(provider.coreEvidenceMatrix.entries().all { it.status == CapabilityStatus.UNKNOWN })
        assertEquals("adapter-mock", snapshot.runtimeStatus.adapterId)
        assertTrue(snapshot.runtimeStatus.entries.all { it.status == CapabilityStatus.CONFIRMED })
    }

    @Test
    fun `validated evidence snapshot rejects collection mutation attempts`() {
        val snapshot = G520CapabilityMatrixProvider().evidenceMatrix

        @Suppress("UNCHECKED_CAST")
        val rows = snapshot.rows as MutableList<Any?>
        assertThrows(UnsupportedOperationException::class.java) { rows.clear() }

        @Suppress("UNCHECKED_CAST")
        val trackingIssues = snapshot.rows.first().trackingIssues as MutableList<Int>
        assertThrows(UnsupportedOperationException::class.java) { trackingIssues.add(999) }

        assertEquals(snapshot, G520CapabilityMatrixProvider().evidenceMatrix)
    }
}
