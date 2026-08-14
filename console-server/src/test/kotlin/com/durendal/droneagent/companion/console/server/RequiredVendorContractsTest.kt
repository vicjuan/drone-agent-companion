package com.durendal.droneagent.companion.console.server

import org.junit.Assert.assertEquals
import org.junit.Test

class RequiredVendorContractsTest {
    @Test
    fun `composite build resolves the admission and actuation boundaries`() {
        assertEquals(
            setOf(
                "com.durendal.droneagent.core.DroneAgent",
                "com.durendal.droneagent.actuation.ActuationCommandFrame",
                "com.durendal.droneagent.gateway.admission.CommandAdmissionPolicy",
            ),
            RequiredVendorContracts.typeNames,
        )
    }
}
