package com.durendal.droneagent.companion.console.protocol

import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Digests the complete companion intent, rather than the vendor inbox's legacy
 * name/stream tuple. The client never supplies this digest as trusted input.
 */
object ConsoleIntentDigest {
    @OptIn(ExperimentalSerializationApi::class)
    private val canonicalJson =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }

    fun sha256(payload: DiscreteCommandRequestPayload): String {
        ConsoleMessageValidator.validate(payload)
        return digest(
            canonicalJson.encodeToString(
                CanonicalDiscreteIntent(
                    commandId = payload.commandId,
                    leaseId = payload.leaseId,
                    action = payload.action,
                    ttlMs = payload.ttlMs,
                ),
            ),
        )
    }

    fun sha256(payload: ControlFramePayload): String {
        ConsoleMessageValidator.validate(payload)
        return digest(
            canonicalJson.encodeToString(
                CanonicalControlIntent(
                    leaseId = payload.leaseId,
                    inputSequence = payload.inputSequence,
                    ttlMs = payload.ttlMs,
                    forward = payload.forward,
                    right = payload.right,
                    up = payload.up,
                    yaw = payload.yaw,
                ),
            ),
        )
    }

    private fun digest(canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class CanonicalDiscreteIntent(
        val kind: String = "discrete",
        val commandId: String,
        val leaseId: String,
        val action: DiscreteCommandAction,
        val ttlMs: Int,
    )

    @Serializable
    private data class CanonicalControlIntent(
        val kind: String = "control_frame",
        val leaseId: String,
        val inputSequence: Long,
        val ttlMs: Int,
        val forward: Double,
        val right: Double,
        val up: Double,
        val yaw: Double,
    )
}
