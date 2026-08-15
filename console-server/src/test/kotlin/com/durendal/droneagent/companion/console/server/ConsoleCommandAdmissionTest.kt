package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.ConsoleIntentDigest
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCommandAdmissionTest {
    @Test
    fun `facade mints vendor authority and uses the protocol full-intent digest`() {
        val admission =
            ConsoleCommandAdmission(
                agentId = "agent-1",
                streamId = "stream-1",
                epochClock = ConsoleEpochClock { 1_800_000_000_000L },
                authorityDecisionId = { "authority-7" },
            )
        val command =
            ConsoleDiscreteCommand(
                commandId = "command-1",
                leaseId = "lease-1",
                action = ConsoleDiscreteAction.RETURN_TO_HOME,
                ttlMillis = 500L,
            )

        val decision = admission.admit(command, CompanionAdmissionRule.ACCEPTED)

        assertTrue(decision is ConsoleAdmissionDecision.Admitted)
        decision as ConsoleAdmissionDecision.Admitted
        assertEquals("authority-7", decision.authorityDecisionId)
        assertEquals(
            ConsoleIntentDigest.sha256(
                DiscreteCommandRequestPayload(
                    commandId = "command-1",
                    leaseId = "lease-1",
                    action = DiscreteCommandAction.RETURN_TO_HOME,
                    ttlMs = 500,
                ),
            ),
            decision.intentDigestSha256,
        )
    }

    @Test
    fun `vendor policy runs before a companion lease rejection`() {
        val admission =
            ConsoleCommandAdmission(
                agentId = "agent-1",
                streamId = "stream-1",
                epochClock = ConsoleEpochClock { 1_800_000_000_000L },
                authorityDecisionId = { "authority-8" },
            )
        val command =
            ConsoleDiscreteCommand(
                "command-2",
                "lease-other",
                ConsoleDiscreteAction.TAKEOFF,
                500L,
            )

        val decision =
            admission.admit(
                command,
                CompanionAdmissionRule.reject("not_lease_holder"),
            )

        assertTrue(decision is ConsoleAdmissionDecision.Rejected)
        decision as ConsoleAdmissionDecision.Rejected
        assertEquals("not_lease_holder", decision.reason)
        assertEquals(64, decision.intentDigestSha256.length)
    }

    @Test
    fun `authority minting failure is a stable fail-closed rejection`() {
        val admission =
            ConsoleCommandAdmission(
                agentId = "agent-1",
                streamId = "stream-1",
                epochClock = ConsoleEpochClock { 1_800_000_000_000L },
                authorityDecisionId = { error("secret provider detail") },
            )

        val decision =
            admission.admit(
                ConsoleDiscreteCommand(
                    "command-3",
                    "lease-1",
                    ConsoleDiscreteAction.TAKEOFF,
                    500L,
                ),
                CompanionAdmissionRule.ACCEPTED,
            ) as ConsoleAdmissionDecision.Rejected

        assertEquals("authority_unavailable", decision.reason)
        assertEquals(null, decision.detail)
    }
}
