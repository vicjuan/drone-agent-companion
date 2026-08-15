package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.actuation.FlightControlCommandEvidence
import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleControlFrame
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import com.durendal.droneagent.core.control.BodyFrameVelocityCommand
import com.durendal.droneagent.core.control.SaturatedCommand
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockConsoleCommandExecutorTest {
    @Test
    fun `takeoff and landing use adapter mock while companion RTH is observable`() {
        val agent = MockDroneAgent()
        val rthPort = ObservableMockReturnToHomePort()
        val executor = MockConsoleCommandExecutor(agent, ConsoleMonotonicClock { 1_000L }, rthPort)
        try {
            val takeoff = executor.execute(discrete(ConsoleDiscreteAction.TAKEOFF, "takeoff-1"))
            val landing = executor.execute(discrete(ConsoleDiscreteAction.LANDING, "landing-1"))
            val rth = executor.execute(discrete(ConsoleDiscreteAction.RETURN_TO_HOME, "rth-1"))

            assertTrue(takeoff.succeeded)
            assertTrue(landing.succeeded)
            assertTrue(rth.succeeded)
            assertEquals("rth-1", rthPort.activeCommandId())
            assertTrue(rth.detail.orEmpty().contains("vendor adapter-mock has no RTH action port"))
        } finally {
            executor.close()
            agent.shutdown()
        }
    }

    @Test
    fun `expired discrete command never reaches its mock action`() {
        val agent = MockDroneAgent()
        val rthPort = ObservableMockReturnToHomePort()
        val executor = MockConsoleCommandExecutor(agent, ConsoleMonotonicClock { 10_000L }, rthPort)
        try {
            val result =
                executor.execute(
                    discrete(
                        action = ConsoleDiscreteAction.RETURN_TO_HOME,
                        commandId = "rth-expired",
                        expiresAtNanos = 10_000L,
                    ),
                )

            assertFalse(result.succeeded)
            assertEquals("command_ttl_expired", result.reason)
            assertEquals(null, rthPort.activeCommandId())
        } finally {
            executor.close()
            agent.shutdown()
        }
    }

    @Test
    fun `neutral is a linearization barrier and a greater epoch starts a fresh generation`() {
        val agent = MockDroneAgent()
        val evidence = CopyOnWriteArrayList<FlightControlCommandEvidence>()
        agent.actuation.addCommandEvidenceListener { item -> evidence += item }
        val executor =
            MockConsoleCommandExecutor(
                agent,
                ConsoleMonotonicClock { 1_000L },
                ObservableMockReturnToHomePort(),
            )
        try {
            val first = executor.submit(control("lease-a", inputSequence = 1L, controlEpoch = 1L))
            val neutral = executor.neutralize("lease-a", 1L, ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED)
            val late = executor.submit(control("lease-a", inputSequence = 2L, controlEpoch = 1L))
            val next = executor.submit(control("lease-b", inputSequence = 1L, controlEpoch = 2L))

            assertTrue(first.succeeded)
            assertTrue(neutral.succeeded)
            assertFalse(late.succeeded)
            assertEquals("control_epoch_fenced", late.reason)
            assertTrue(next.succeeded)

            val nonNeutral = evidence.filter { it.command != BodyFrameVelocityCommand.NEUTRAL }
            assertEquals(2, nonNeutral.size)
            assertEquals(listOf(1L, 2L), nonNeutral.map { it.generation.value })
            assertTrue(evidence.any { it.command == BodyFrameVelocityCommand.NEUTRAL })
        } finally {
            executor.close()
            agent.shutdown()
        }
    }

    private fun MockConsoleCommandExecutor.execute(command: AdmittedDiscreteCommand): ConsoleExecutionResult {
        var result: ConsoleExecutionResult? = null
        executeDiscrete(command) { completed -> result = completed }
        return checkNotNull(result)
    }

    private fun MockConsoleCommandExecutor.submit(frame: AdmittedControlFrame): ConsoleExecutionResult {
        var result: ConsoleExecutionResult? = null
        submitControl(frame) { completed -> result = completed }
        return checkNotNull(result)
    }

    private fun MockConsoleCommandExecutor.neutralize(
        leaseId: String,
        controlEpoch: Long,
        trigger: ConsoleSafetyTrigger,
    ): ConsoleExecutionResult {
        var result: ConsoleExecutionResult? = null
        neutralize(leaseId, controlEpoch, trigger) { completed -> result = completed }
        return checkNotNull(result)
    }

    private fun discrete(
        action: ConsoleDiscreteAction,
        commandId: String,
        expiresAtNanos: Long = 20_000L,
    ): AdmittedDiscreteCommand =
        AdmittedDiscreteCommand(
            sessionId = "session-1",
            command =
                ConsoleDiscreteCommand(
                    commandId = commandId,
                    leaseId = "lease-1",
                    action = action,
                    ttlMillis = 5_000L,
                ),
            authorityDecisionId = "authority-1",
            intentDigestSha256 = "a".repeat(64),
            admittedAtNanos = 1_000L,
            expiresAtNanos = expiresAtNanos,
        )

    private fun control(
        leaseId: String,
        inputSequence: Long,
        controlEpoch: Long,
    ): AdmittedControlFrame =
        AdmittedControlFrame(
            sessionId = "session-1",
            frame =
                ConsoleControlFrame(
                    leaseId = leaseId,
                    inputSequence = inputSequence,
                    ttlMillis = 250L,
                    forward = 1.0,
                    right = 0.0,
                    up = 0.0,
                    yaw = 0.0,
                ),
            command =
                SaturatedCommand(
                    command =
                        BodyFrameVelocityCommand(
                            forwardMps = 0.5,
                            rightMps = 0.0,
                            upMps = 0.0,
                            yawRateDegreesPerSecond = 0.0,
                        ),
                    clippedAxes = emptySet(),
                ),
            authorityDecisionId = "authority-$controlEpoch",
            intentDigestSha256 = "b".repeat(64),
            admittedAtNanos = 1_000L,
            controlEpoch = controlEpoch,
        )
}
