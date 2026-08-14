package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.actuation.ActuationCommandFrame
import com.durendal.droneagent.core.DroneAgent
import com.durendal.droneagent.gateway.admission.CommandAdmissionPolicy

/**
 * Compile-time proof of the three vendor contracts the future console server
 * must compose. It does not expose a command endpoint or implement issue #3.
 */
object RequiredVendorContracts {
    val typeNames: Set<String> = setOf(
        requireNotNull(DroneAgent::class.qualifiedName),
        requireNotNull(ActuationCommandFrame::class.qualifiedName),
        requireNotNull(CommandAdmissionPolicy::class.qualifiedName),
    )
}
