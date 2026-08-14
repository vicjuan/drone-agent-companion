package com.durendal.droneagent.companion.capability

import com.durendal.droneagent.core.capability.Capability
import com.durendal.droneagent.core.capability.CapabilityEntry
import com.durendal.droneagent.core.capability.CapabilityMatrix
import com.durendal.droneagent.core.capability.CapabilityStatus
import kotlinx.serialization.Serializable

/**
 * The companion-owned evidence model for the Mini 4 Pro + RC-N3 + G520 stack.
 *
 * It deliberately has open string row IDs because the upstream [Capability]
 * enum covers only five telemetry/stream rows. Commissioning also needs to
 * record host, USB, MSDK, actuation, neutralization and OpenCV capabilities.
 */
@Serializable
data class G520CapabilityMatrixDocument(
    val schemaVersion: Int,
    val matrixId: String,
    val targetStack: TargetStack,
    val lastUpdated: String,
    val rows: List<G520CapabilityRow>,
    val evidenceRecords: List<CapabilityEvidenceRecord>,
) {
    /** Projects the five upstream rows without transferring any other stack's evidence. */
    fun toCoreMatrix(): CapabilityMatrix {
        CapabilityMatrixValidator.validate(this)
        val rowsById = rows.associateBy(G520CapabilityRow::id)
        return CapabilityMatrix(
            Capability.entries.map { capability ->
                val row = checkNotNull(rowsById[capability.key]) {
                    "Missing required vendor capability row: ${capability.key}"
                }
                CapabilityEntry(
                    capability = capability,
                    status = CapabilityStatus.valueOf(row.status.name),
                    evidence = row.assessment,
                    method = row.verificationMethod,
                )
            },
        )
    }
}

@Serializable
data class TargetStack(
    val aircraft: String,
    val remoteController: String,
    val host: String,
)

/** Issue #12 keeps failure evidence UNKNOWN unless support is proven or limited. */
@Serializable
enum class G520CapabilityStatus {
    CONFIRMED,
    LIMITED,
    UNKNOWN,
}

@Serializable
data class G520CapabilityRow(
    val id: String,
    val title: String,
    val status: G520CapabilityStatus,
    val assessment: String,
    val verificationMethod: String,
    val trackingIssues: List<Int>,
    val evidenceRefs: List<String>,
)

@Serializable
enum class EvidenceRecordKind {
    EVIDENCE,
    TEMPLATE,
}

@Serializable
enum class EvidenceEnvironment {
    MOCK,
    ANDROID_EMULATOR,
    G520_HARDWARE,
}

@Serializable
enum class EvidenceOutcome {
    PASS,
    LIMITED,
    FAIL,
}

@Serializable
data class CapabilityEvidenceRecord(
    val id: String,
    val recordKind: EvidenceRecordKind,
    val environment: EvidenceEnvironment,
    val capturedAt: String,
    val commit: String,
    val operator: String,
    val runtimeProfile: String,
    /** Opaque public reference; the serial tuple stays in the protected raw evidence bundle. */
    val deviceSetId: String?,
    val rawLogLocation: String,
    val trackingIssue: Int,
    val outcome: EvidenceOutcome,
    val summary: String,
)

@Serializable
data class CapabilityEvidenceTemplateSet(
    val schemaVersion: Int,
    val templates: List<CapabilityEvidenceRecord>,
)

/**
 * A reviewed inventory prevents a missing commissioning row from being hidden
 * by the upstream CapabilityMatrix behavior that auto-fills absent rows.
 */
object RequiredG520Capabilities {
    val rowIds: Set<String> =
        linkedSetOf(
            "battery",
            "gps_position",
            "flight_state",
            "gimbal_camera_state",
            "stream_capability",
            "headless_boot_service",
            "point_to_point_ethernet",
            "rcn3_usb_attach",
            "msdk_registration_activation",
            "aircraft_connection",
            "rcn3_four_axis_input",
            "takeoff_actuation",
            "landing_actuation",
            "rth_actuation",
            "virtual_stick_actuation",
            "continuous_control_neutralization",
            "opencv_on_device_recognition",
        )
}

object G520TargetIdentity {
    const val MATRIX_ID = "mini4pro-rcn3-g520-android"
    const val AIRCRAFT = "DJI Mini 4 Pro"
    const val REMOTE_CONTROLLER = "DJI RC-N3"
    const val HOST = "MediaTek Genio 520 (Android)"
}
