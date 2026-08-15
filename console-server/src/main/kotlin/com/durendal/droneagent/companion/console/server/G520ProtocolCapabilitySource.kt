package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.capability.CapabilityMatrixLoader
import com.durendal.droneagent.companion.capability.G520CapabilityStatus
import com.durendal.droneagent.companion.console.protocol.CapabilityEvidenceStatus
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotRow
import java.security.MessageDigest
import java.util.Collections

/** Loads the canonical evidence bytes once and exposes their exact digest to console clients. */
class G520ProtocolCapabilitySource private constructor(
    private val payload: CapabilitySnapshotPayload,
) {
    fun snapshot(): CapabilitySnapshotPayload = payload

    companion object {
        fun loadBundled(
            classLoader: ClassLoader = G520ProtocolCapabilitySource::class.java.classLoader,
        ): G520ProtocolCapabilitySource {
            val bytes =
                requireNotNull(classLoader.getResourceAsStream(CapabilityMatrixLoader.BUNDLED_RESOURCE)) {
                    "Missing bundled capability matrix: ${CapabilityMatrixLoader.BUNDLED_RESOURCE}"
                }.use { it.readBytes() }
            val document = CapabilityMatrixLoader().load(bytes.toString(Charsets.UTF_8))
            val detached = G520CapabilityMatrixProvider(document).evidenceMatrix
            return G520ProtocolCapabilitySource(
                CapabilitySnapshotPayload(
                    matrixId = detached.matrixId,
                    schemaVersion = detached.schemaVersion,
                    lastUpdated = detached.lastUpdated,
                    sourceDigestSha256 = bytes.sha256(),
                    rows =
                        Collections.unmodifiableList(
                            ArrayList(
                                detached.rows.map { row ->
                                    CapabilitySnapshotRow(
                                        id = row.id,
                                        status = row.status.toProtocol(),
                                        assessment = row.assessment,
                                    )
                                },
                            ),
                        ),
                ),
            )
        }

        private fun ByteArray.sha256(): String =
            MessageDigest.getInstance("SHA-256")
                .digest(this)
                .joinToString("") { byte -> "%02x".format(byte) }

        private fun G520CapabilityStatus.toProtocol(): CapabilityEvidenceStatus =
            when (this) {
                G520CapabilityStatus.CONFIRMED -> CapabilityEvidenceStatus.CONFIRMED
                G520CapabilityStatus.LIMITED -> CapabilityEvidenceStatus.LIMITED
                G520CapabilityStatus.UNKNOWN -> CapabilityEvidenceStatus.UNKNOWN
            }
    }
}
