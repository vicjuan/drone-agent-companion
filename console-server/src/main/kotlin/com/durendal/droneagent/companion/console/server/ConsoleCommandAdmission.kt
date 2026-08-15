package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.ConsoleIntentDigest
import com.durendal.droneagent.companion.console.protocol.ControlFramePayload
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import com.durendal.droneagent.gateway.admission.AdmissionDecision
import com.durendal.droneagent.gateway.admission.CommandAdmissionPolicy
import com.durendal.droneagent.gateway.protocol.AuthorityAssertion
import com.durendal.droneagent.gateway.protocol.CommandMessage
import com.durendal.droneagent.gateway.protocol.CommandParams
import com.durendal.droneagent.gateway.protocol.CommandPayload
import java.time.Instant
import java.util.UUID

data class CompanionAdmissionRule(
    val accepted: Boolean,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        val ACCEPTED = CompanionAdmissionRule(true)
        fun reject(reason: String, detail: String? = null) = CompanionAdmissionRule(false, reason, detail)
    }
}

sealed interface ConsoleAdmissionDecision {
    val intentDigestSha256: String

    data class Admitted(
        val authorityDecisionId: String,
        override val intentDigestSha256: String,
    ) : ConsoleAdmissionDecision

    data class Rejected(
        val reason: String,
        val detail: String?,
        override val intentDigestSha256: String,
        val authorityDecisionId: String? = null,
    ) : ConsoleAdmissionDecision
}

/**
 * Console boundary around the frozen vendor policy. The browser never supplies a trusted
 * AuthorityAssertion; this facade creates the short-lived localhost operator assertion and still
 * applies strict vendor target/name/time-window policy before companion lease/TTL rules.
 */
class ConsoleCommandAdmission(
    private val agentId: String,
    private val streamId: String,
    private val epochClock: ConsoleEpochClock,
    private val authorityDecisionId: () -> String = { UUID.randomUUID().toString() },
    private val authorityValidityMillis: Long = 5_000L,
) {
    init {
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        require(streamId.isNotBlank()) { "streamId must not be blank" }
        require(authorityValidityMillis in 1L..30_000L) {
            "authorityValidityMillis must be within the vendor 30s ceiling"
        }
    }

    private val policy =
        CommandAdmissionPolicy(
            agentId = agentId,
            streamId = streamId,
            supportedCommands = POLICY_NAMES,
            strictAuthority = true,
            clock = epochClock::nowMillis,
        )

    fun admit(
        command: ConsoleDiscreteCommand,
        companionRule: CompanionAdmissionRule,
    ): ConsoleAdmissionDecision {
        val digestAttempt =
            runCatching {
                ConsoleIntentDigest.sha256(
                    DiscreteCommandRequestPayload(
                        commandId = command.commandId,
                        leaseId = command.leaseId,
                        action = command.action.toProtocol(),
                        ttlMs = command.ttlMillis.toIntExact(),
                    ),
                )
            }
        val rule =
            if (digestAttempt.isFailure && companionRule.accepted) {
                CompanionAdmissionRule.reject("invalid_command")
            } else {
                companionRule
            }
        return admit(
            command.commandId,
            command.action.policyName,
            digestAttempt.getOrDefault(INVALID_DIGEST),
            rule,
            sequence = 0L,
        )
    }

    fun admit(
        frame: ConsoleControlFrame,
        companionRule: CompanionAdmissionRule,
    ): ConsoleAdmissionDecision {
        val digestAttempt =
            runCatching {
                ConsoleIntentDigest.sha256(
                    ControlFramePayload(
                        leaseId = frame.leaseId,
                        inputSequence = frame.inputSequence,
                        ttlMs = frame.ttlMillis.toIntExact(),
                        forward = frame.forward,
                        right = frame.right,
                        up = frame.up,
                        yaw = frame.yaw,
                    ),
                )
            }
        val rule =
            if (digestAttempt.isFailure && companionRule.accepted) {
                CompanionAdmissionRule.reject("invalid_control_frame")
            } else {
                companionRule
            }
        return admit(
            "control-${frame.inputSequence}",
            CONTROL_POLICY_NAME,
            digestAttempt.getOrDefault(INVALID_DIGEST),
            rule,
            frame.inputSequence,
        )
    }

    private fun admit(
        commandId: String,
        policyName: String,
        digest: String,
        companionRule: CompanionAdmissionRule,
        sequence: Long,
    ): ConsoleAdmissionDecision {
        val authorityMaterial =
            try {
                val nowMillis = epochClock.nowMillis()
                val decisionId = authorityDecisionId()
                require(AUTHORITY_DECISION_ID.matches(decisionId)) {
                    "authorityDecisionId must match the bounded protocol identifier grammar"
                }
                AuthorityMaterial(
                    nowMillis,
                    Math.addExact(nowMillis, authorityValidityMillis),
                    decisionId,
                )
            } catch (_: Exception) {
                return ConsoleAdmissionDecision.Rejected("authority_unavailable", null, digest)
            }
        val nowMillis = authorityMaterial.nowMillis
        val decisionId = authorityMaterial.decisionId
        val message =
            CommandMessage(
                agentId = agentId,
                sequence = sequence.coerceAtLeast(0L),
                eventId = "console-$decisionId",
                timestamp = Instant.ofEpochMilli(nowMillis).toString(),
                command =
                    CommandPayload(
                        commandId = commandId,
                        name = policyName,
                        params = CommandParams(streamId),
                        authority =
                            AuthorityAssertion(
                                authorityDecisionId = decisionId,
                                originType = "operator",
                                entrypoint = "companion_console",
                                actorId = null,
                                issuedAt = Instant.ofEpochMilli(nowMillis).toString(),
                                expiresAt = Instant.ofEpochMilli(authorityMaterial.expiresAtMillis).toString(),
                                policyVersion = "companion-console-v1",
                            ),
                    ),
            )
        return when (val vendor = policy.admit(message)) {
            is AdmissionDecision.Rejected ->
                ConsoleAdmissionDecision.Rejected(
                    vendor.reason.wireName,
                    vendor.detail,
                    digest,
                    decisionId,
                )
            is AdmissionDecision.Admitted ->
                if (!companionRule.accepted) {
                    ConsoleAdmissionDecision.Rejected(
                        checkNotNull(companionRule.reason),
                        companionRule.detail,
                        digest,
                        decisionId,
                    )
                } else {
                    ConsoleAdmissionDecision.Admitted(
                        authorityDecisionId = checkNotNull(vendor.authority).authorityDecisionId,
                        intentDigestSha256 = digest,
                    )
                }
        }
    }

    private fun Long.toIntExact(): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "ttlMillis is outside Int range" }
        return toInt()
    }

    private data class AuthorityMaterial(
        val nowMillis: Long,
        val expiresAtMillis: Long,
        val decisionId: String,
    )

    private fun ConsoleDiscreteAction.toProtocol(): DiscreteCommandAction =
        when (this) {
            ConsoleDiscreteAction.TAKEOFF -> DiscreteCommandAction.TAKEOFF
            ConsoleDiscreteAction.LANDING -> DiscreteCommandAction.LANDING
            ConsoleDiscreteAction.RETURN_TO_HOME -> DiscreteCommandAction.RETURN_TO_HOME
        }

    private val ConsoleDiscreteAction.policyName: String
        get() =
            when (this) {
                ConsoleDiscreteAction.TAKEOFF -> "takeoff"
                ConsoleDiscreteAction.LANDING -> "landing"
                ConsoleDiscreteAction.RETURN_TO_HOME -> "return_to_home"
            }

    companion object {
        private const val CONTROL_POLICY_NAME = "virtual_stick"
        private val AUTHORITY_DECISION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
        private val INVALID_DIGEST = "0".repeat(64)
        private val POLICY_NAMES =
            setOf("takeoff", "landing", "return_to_home", CONTROL_POLICY_NAME)
    }
}
