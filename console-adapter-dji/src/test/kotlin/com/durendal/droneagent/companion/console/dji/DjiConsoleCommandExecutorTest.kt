package com.durendal.droneagent.companion.console.dji

import com.durendal.droneagent.actuation.ActuationCommandFrame
import com.durendal.droneagent.actuation.ActuationOperation
import com.durendal.droneagent.actuation.ActuationOperationCallback
import com.durendal.droneagent.actuation.ActuationOperationResult
import com.durendal.droneagent.actuation.AircraftActionFailureKind
import com.durendal.droneagent.actuation.CommandSubmissionStatus
import com.durendal.droneagent.actuation.ControlGeneration
import com.durendal.droneagent.actuation.ControlProducer
import com.durendal.droneagent.actuation.FlightControlCommandEvidenceListener
import com.durendal.droneagent.actuation.FlightControlCommandPort
import com.durendal.droneagent.actuation.FlightControlPortSnapshot
import com.durendal.droneagent.actuation.FlightControlPortSnapshotListener
import com.durendal.droneagent.actuation.FlightControlPortState
import com.durendal.droneagent.actuation.LandingAction
import com.durendal.droneagent.actuation.LandingActionFailure
import com.durendal.droneagent.actuation.LandingActionPort
import com.durendal.droneagent.actuation.LandingActionResult
import com.durendal.droneagent.actuation.LandingActionSnapshot
import com.durendal.droneagent.actuation.LandingActionSnapshotListener
import com.durendal.droneagent.actuation.NeutralizationReason
import com.durendal.droneagent.actuation.PhysicalRcTakeoverListener
import com.durendal.droneagent.actuation.PhysicalRcTakeoverPort
import com.durendal.droneagent.actuation.PhysicalRcTakeoverPortState
import com.durendal.droneagent.actuation.PhysicalRcTakeoverSnapshot
import com.durendal.droneagent.actuation.PhysicalRcTakeoverSnapshotListener
import com.durendal.droneagent.actuation.PhysicalRcTakeoverStartResult
import com.durendal.droneagent.actuation.ReturnToHomeAction
import com.durendal.droneagent.actuation.ReturnToHomeActionFailure
import com.durendal.droneagent.actuation.ReturnToHomeActionPort
import com.durendal.droneagent.actuation.ReturnToHomeActionResult
import com.durendal.droneagent.actuation.ReturnToHomeActionSnapshot
import com.durendal.droneagent.actuation.ReturnToHomeActionSnapshotListener
import com.durendal.droneagent.actuation.ReturnToHomeState
import com.durendal.droneagent.actuation.TakeoffAction
import com.durendal.droneagent.actuation.TakeoffActionFailure
import com.durendal.droneagent.actuation.TakeoffActionFailureKind
import com.durendal.droneagent.actuation.TakeoffActionPort
import com.durendal.droneagent.actuation.TakeoffActionResult
import com.durendal.droneagent.actuation.TakeoffActionSnapshot
import com.durendal.droneagent.actuation.TakeoffActionSnapshotListener
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
import com.durendal.droneagent.core.model.FlightMode
import com.durendal.droneagent.core.model.FlightStateTelemetry
import com.durendal.droneagent.core.model.Telemetry
import com.durendal.droneagent.core.telemetry.TelemetryListener
import com.durendal.droneagent.core.telemetry.TelemetrySource
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiConsoleCommandExecutorTest {
    @Test
    fun `activation is asynchronous and only the latest frame reaches the vendor clock domain`() {
        val harness = Harness(generationWatermark = 41L)
        try {
            val firstResults = mutableListOf<ConsoleExecutionResult>()
            val latestResults = mutableListOf<ConsoleExecutionResult>()
            val first = control(inputSequence = 1L, controlEpoch = 1L)
            val latest = control(inputSequence = 2L, controlEpoch = 1L)

            harness.executor.submitControl(first) { firstResults += it }

            assertEquals(listOf(ControlGeneration(42L)), harness.flight.activations)
            assertTrue(firstResults.isEmpty())
            assertTrue(harness.flight.submissions.isEmpty())

            harness.executor.submitControl(latest) { latestResults += it }

            assertEquals(1, firstResults.size)
            assertEquals("dji_control_superseded", firstResults.single().reason)
            assertTrue(latestResults.isEmpty())
            assertTrue(harness.flight.submissions.isEmpty())
            assertFalse(firstResults.single().succeeded)

            harness.flight.completeNextActivation(callbackCount = 2)

            assertEquals(1, latestResults.size)
            assertTrue(latestResults.single().succeeded)
            assertEquals(1, harness.flight.submissions.size)
            val submitted = harness.flight.submissions.single()
            assertEquals(latest.command, submitted.command)
            assertEquals(ControlProducer.MANUAL, submitted.producer)
            assertEquals(ControlGeneration(42L), submitted.generation)
            assertEquals(latest.frame.inputSequence, submitted.commandSequence)
            assertEquals(latest.frame.inputSequence, submitted.inputSequence)
            assertEquals(harness.actuationNowNanos, submitted.createdAtNanos)
            assertFalse(submitted.createdAtNanos == latest.admittedAtNanos)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `control trusts the exact Core deadline instead of reconstructing raw TTL`() {
        val harness = Harness(consoleNowNanos = 10_000_000L)
        try {
            val results = mutableListOf<ConsoleExecutionResult>()

            harness.executor.submitControl(
                control(controlEpoch = 1L, expiresAtNanos = 10_000_000L),
            ) { results += it }

            assertEquals("dji_control_expired", results.single().reason)
            assertTrue(harness.flight.activations.isEmpty())
            assertTrue(harness.flight.submissions.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `executor entrypoints never refresh telemetry and stale cache fails closed`() {
        val harness = Harness()
        try {
            assertEquals(0, harness.telemetry.refreshCount)
            assertTrue(harness.executor.isReady())
            assertEquals(0, harness.telemetry.refreshCount)

            val discreteResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(
                discrete(
                    action = ConsoleDiscreteAction.TAKEOFF,
                    expiresAtNanos = harness.consoleClock.nowNanos,
                ),
            ) { discreteResults += it }
            assertEquals("dji_command_expired", discreteResults.single().reason)
            assertEquals(0, harness.telemetry.refreshCount)

            val controlResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(
                control(
                    controlEpoch = 1L,
                    expiresAtNanos = harness.consoleClock.nowNanos,
                ),
            ) { controlResults += it }
            assertEquals("dji_control_expired", controlResults.single().reason)
            assertEquals(0, harness.telemetry.refreshCount)

            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 0L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }
            assertTrue(neutralResults.single().succeeded)
            assertEquals(0, harness.telemetry.refreshCount)

            val cached = checkNotNull(harness.telemetry.latest().flightState)
            harness.actuationNowNanos = cached.modeObservedAtNanos + 2_000_000_001L
            assertFalse(harness.executor.isReady())
            assertEquals(0, harness.telemetry.refreshCount)

            val staleResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.TAKEOFF)) {
                staleResults += it
            }
            assertEquals("dji_actuation_not_ready", staleResults.single().reason)
            assertEquals(0, harness.takeoff.startCount)
            assertEquals(0, harness.telemetry.refreshCount)

            harness.telemetry.refreshCachedFlight(harness.actuationNowNanos)
            assertTrue(harness.executor.isReady())
            assertEquals(0, harness.telemetry.refreshCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dedicated readiness poll synchronously fans out refreshed telemetry without deadlock`() {
        val refreshReturned = CountDownLatch(1)
        val harness = Harness(readinessPollingEnabled = true)
        try {
            val cached = checkNotNull(harness.telemetry.latest().flightState)
            harness.actuationNowNanos = cached.modeObservedAtNanos + 2_000_000_001L
            var synchronousFanoutReturned = false
            harness.telemetry.refreshHook = {
                harness.telemetry.refreshCachedFlight(harness.actuationNowNanos)
                synchronousFanoutReturned = true
                refreshReturned.countDown()
            }

            assertTrue(refreshReturned.await(2L, TimeUnit.SECONDS))
            assertTrue(synchronousFanoutReturned)
            assertTrue(harness.telemetry.refreshCount > 0)
            assertTrue(harness.executor.isReady())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `synchronous activation success followed by throw is neutralized and deactivated`() {
        val harness = Harness()
        try {
            harness.flight.activateSynchronouslyThenThrow = true
            val results = mutableListOf<ConsoleExecutionResult>()

            harness.executor.submitControl(control(controlEpoch = 1L)) { results += it }

            assertEquals(1, results.size)
            assertTrue(results.single().succeeded)
            assertEquals(1, harness.flight.submissions.size)
            assertEquals(
                listOf(ControlGeneration(1L) to NeutralizationReason.FAULT),
                harness.flight.neutralizations,
            )
            assertEquals(listOf(ControlGeneration(1L)), harness.flight.deactivations)

            harness.flight.completeNextDeactivation(callbackCount = 2)
            assertEquals(1, results.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `neutral during activation fences the epoch and late activation cannot apply`() {
        val harness = Harness()
        try {
            val controlResults = mutableListOf<ConsoleExecutionResult>()
            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(controlEpoch = 7L)) { controlResults += it }

            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 7L,
                trigger = ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
            ) { neutralResults += it }

            assertEquals("dji_control_neutralized", controlResults.single().reason)
            assertTrue(neutralResults.isEmpty())
            assertTrue(harness.flight.neutralizations.isEmpty())
            assertEquals(listOf(ControlGeneration(1L)), harness.flight.deactivations)

            val fenced = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(inputSequence = 2L, controlEpoch = 7L)) {
                fenced += it
            }
            assertEquals("dji_control_epoch_fenced", fenced.single().reason)

            harness.flight.completeNextActivation(callbackCount = 2)
            assertTrue(harness.flight.submissions.isEmpty())
            assertTrue(neutralResults.isEmpty())

            harness.flight.completeNextDeactivation(callbackCount = 2)
            assertEquals(1, neutralResults.size)
            assertTrue(neutralResults.single().succeeded)
            assertTrue(harness.flight.submissions.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `same lease neutral from an old epoch cannot stop the newer generation`() {
        val harness = Harness()
        try {
            val first = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(inputSequence = 1L, controlEpoch = 1L)) {
                first += it
            }
            harness.flight.completeNextActivation()
            assertTrue(first.single().succeeded)

            val firstNeutral = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                LEASE_ID,
                1L,
                ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { firstNeutral += it }
            harness.flight.completeNextDeactivation()
            assertTrue(firstNeutral.single().succeeded)

            val second = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(inputSequence = 2L, controlEpoch = 2L)) {
                second += it
            }
            harness.flight.completeNextActivation()
            assertTrue(second.single().succeeded)
            val neutralCount = harness.flight.neutralizations.size
            val deactivationCount = harness.flight.deactivations.size

            val staleNeutral = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                LEASE_ID,
                1L,
                ConsoleSafetyTrigger.CLIENT_DISCONNECT,
            ) { staleNeutral += it }

            assertTrue(staleNeutral.single().succeeded)
            assertEquals(neutralCount, harness.flight.neutralizations.size)
            assertEquals(deactivationCount, harness.flight.deactivations.size)

            val followUp = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(inputSequence = 3L, controlEpoch = 2L)) {
                followUp += it
            }
            assertTrue(followUp.single().succeeded)
            assertEquals(3, harness.flight.submissions.size)
            assertEquals(
                harness.flight.submissions[1].generation,
                harness.flight.submissions[2].generation,
            )
            assertFalse(
                harness.flight.submissions[0].generation ==
                    harness.flight.submissions[1].generation,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `physical RC takeover latches cancels once and never auto rearms`() {
        val harness = Harness()
        try {
            val controlResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(controlEpoch = 3L)) { controlResults += it }
            harness.flight.completeNextActivation()
            assertTrue(controlResults.single().succeeded)

            harness.rc.emitTakeover("RC_STICK_INPUT")
            harness.rc.emitTakeover("RC_STICK_INPUT")

            assertEquals(listOf("RC_STICK_INPUT"), harness.takeovers)
            assertEquals(
                listOf(ControlGeneration(1L) to NeutralizationReason.AUTHORITY_LOSS),
                harness.flight.neutralizations,
            )
            assertEquals(listOf(ControlGeneration(1L)), harness.flight.deactivations)

            harness.rc.emitReadyNeutral()
            assertFalse(harness.executor.isReady())

            val refused = mutableListOf<ConsoleExecutionResult>()
            harness.executor.submitControl(control(inputSequence = 2L, controlEpoch = 4L)) {
                refused += it
            }
            assertEquals("dji_physical_rc_takeover", refused.single().reason)
            assertEquals(1, harness.flight.neutralizations.size)
            assertEquals(1, harness.flight.deactivations.size)

            harness.flight.completeNextDeactivation()
        } finally {
            harness.close()
        }

        val discreteHarness = Harness()
        try {
            discreteHarness.takeoff.reenterStartCallbackOnStop = true
            val takeoffResults = mutableListOf<ConsoleExecutionResult>()
            discreteHarness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.TAKEOFF)) {
                takeoffResults += it
            }
            assertTrue(takeoffResults.isEmpty())

            discreteHarness.rc.emitTakeover("RC_STICK_INPUT")
            discreteHarness.rc.emitTakeover("RC_STICK_INPUT")

            assertEquals(listOf("RC_STICK_INPUT"), discreteHarness.takeovers)
            assertEquals(1, takeoffResults.size)
            assertEquals("dji_physical_rc_takeover", takeoffResults.single().reason)
            assertEquals(1, discreteHarness.takeoff.stopCount)
        } finally {
            discreteHarness.close()
        }
    }

    @Test
    fun `discrete actions initialize and start only their matching port and complete once`() {
        ConsoleDiscreteAction.entries.forEach { action ->
            val harness = Harness()
            try {
                val results = mutableListOf<ConsoleExecutionResult>()
                harness.executor.executeDiscrete(discrete(action)) { results += it }

                when (action) {
                    ConsoleDiscreteAction.TAKEOFF -> {
                        assertEquals(2, harness.takeoff.initializeCount)
                        assertEquals(1, harness.takeoff.startCount)
                        harness.takeoff.completeStart(callbackCount = 2)
                    }
                    ConsoleDiscreteAction.LANDING -> {
                        assertEquals(2, harness.landing.initializeCount)
                        assertEquals(1, harness.landing.startCount)
                        harness.landing.completeStart(callbackCount = 2)
                    }
                    ConsoleDiscreteAction.RETURN_TO_HOME -> {
                        assertEquals(2, harness.rth.initializeCount)
                        assertEquals(1, harness.rth.startCount)
                        harness.rth.completeStart(callbackCount = 2)
                    }
                }

                assertEquals(4, totalInitializations(harness))
                assertEquals(1, totalStarts(harness))
                assertEquals(1, results.size)
                assertTrue(results.single().succeeded)
                assertTrue(results.single().detail.orEmpty().contains("accepted"))
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `RTH cancel busy before START callback retries after acceptance and holds neutral barrier`() {
        val harness = Harness()
        try {
            harness.rth.firstCancelReturnsBusy = true
            val commandResults = mutableListOf<ConsoleExecutionResult>()
            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
                commandResults += it
            }

            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }

            assertEquals(1, harness.rth.cancelCount)
            assertEquals(1, commandResults.size)
            assertEquals("dji_neutralized", commandResults.single().reason)
            assertTrue(neutralResults.isEmpty())

            harness.rth.completeStart(callbackCount = 2)

            assertEquals(2, harness.rth.cancelCount)
            assertEquals(1, neutralResults.size)
            assertTrue(neutralResults.single().succeeded)
            assertEquals(1, commandResults.size)
            assertEquals("dji_neutralized", commandResults.single().reason)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `takeoff neutral waits for STOP completion after START cancelled callback`() {
        val harness = Harness()
        try {
            harness.takeoff.reenterStartCallbackOnStop = true
            harness.takeoff.deferStopCompletion = true
            val commandResults = mutableListOf<ConsoleExecutionResult>()
            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.TAKEOFF)) {
                commandResults += it
            }

            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }

            assertEquals(1, harness.takeoff.stopCount)
            assertEquals("dji_neutralized", commandResults.single().reason)
            assertTrue(neutralResults.isEmpty())

            harness.takeoff.completeStop()

            assertEquals(1, neutralResults.size)
            assertTrue(neutralResults.single().succeeded)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `landing START disconnected remains cancelable and failed cancel fails neutral`() {
        val harness = Harness()
        try {
            val commandResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                commandResults += it
            }
            harness.landing.cancelFailureKind = AircraftActionFailureKind.DISCONNECTED
            harness.landing.completeStartFailure(AircraftActionFailureKind.DISCONNECTED)

            assertEquals(1, commandResults.size)
            assertFalse(commandResults.single().succeeded)
            assertEquals("dji_landing_disconnected", commandResults.single().reason)

            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }

            assertEquals(2, harness.landing.cancelCount)
            assertEquals(1, neutralResults.size)
            assertFalse(neutralResults.single().succeeded)
            assertEquals("dji_discrete_cancel_failed", neutralResults.single().reason)
            assertEquals(1, commandResults.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `RTH START timeout remains cancelable and failed cancel fails neutral`() {
        val harness = Harness()
        try {
            val commandResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
                commandResults += it
            }
            harness.rth.cancelFailureKind = AircraftActionFailureKind.TIMEOUT
            harness.rth.completeStartFailure(AircraftActionFailureKind.TIMEOUT)

            assertEquals(1, commandResults.size)
            assertFalse(commandResults.single().succeeded)
            assertEquals("dji_rth_timeout", commandResults.single().reason)

            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }

            assertEquals(2, harness.rth.cancelCount)
            assertEquals(1, neutralResults.size)
            assertFalse(neutralResults.single().succeeded)
            assertEquals("dji_discrete_cancel_failed", neutralResults.single().reason)
            assertEquals(1, commandResults.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `landing true then false snapshots retire action before neutral and next action`() {
        val harness = Harness()
        try {
            val landingResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                landingResults += it
            }
            harness.landing.completeStart(landingActiveAtCallback = null)
            assertTrue(landingResults.single().succeeded)

            harness.landing.emitLandingActive(true)
            harness.landing.emitLandingActive(false)

            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }
            assertTrue(neutralResults.single().succeeded)
            assertEquals(0, harness.landing.cancelCount)

            val nextResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
                nextResults += it
            }
            assertEquals(1, harness.rth.startCount)
            assertTrue(nextResults.isEmpty())
            harness.rth.completeStart()
            assertTrue(nextResults.single().succeeded)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `transient RTH active then completed snapshots retire action before next entrypoint`() {
        val harness = Harness()
        try {
            val rthResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
                rthResults += it
            }
            harness.rth.completeStart(stateAtCallback = ReturnToHomeState.UNKNOWN)
            assertTrue(rthResults.single().succeeded)

            harness.rth.emitState(ReturnToHomeState.RETURNING_TO_HOME)
            harness.rth.emitState(ReturnToHomeState.COMPLETED)
            harness.rth.emitState(ReturnToHomeState.IDLE)

            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }
            assertTrue(neutralResults.single().succeeded)
            assertEquals(0, harness.rth.cancelCount)

            val nextResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                nextResults += it
            }
            assertEquals(1, harness.landing.startCount)
            assertTrue(nextResults.isEmpty())
            harness.landing.completeStart()
            assertTrue(nextResults.single().succeeded)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `old RTH completed is ignored until the current action becomes active then terminal`() {
        val oldTerminalOnly = Harness()
        try {
            acceptRthFromOldCompleted(oldTerminalOnly)
            val neutralResults = mutableListOf<ConsoleExecutionResult>()
            oldTerminalOnly.executor.neutralize(
                leaseId = LEASE_ID,
                controlEpoch = 1L,
                trigger = ConsoleSafetyTrigger.CLIENT_REQUEST,
            ) { neutralResults += it }

            assertEquals(1, oldTerminalOnly.rth.cancelCount)
            assertEquals(1, neutralResults.size)
            assertTrue(neutralResults.single().succeeded)
        } finally {
            oldTerminalOnly.close()
        }

        val currentLifecycle = Harness()
        try {
            acceptRthFromOldCompleted(currentLifecycle)
            currentLifecycle.rth.emitState(ReturnToHomeState.RETURNING_TO_HOME)
            currentLifecycle.rth.emitState(ReturnToHomeState.COMPLETED)

            val nextResults = mutableListOf<ConsoleExecutionResult>()
            currentLifecycle.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                nextResults += it
            }

            assertEquals(0, currentLifecycle.rth.cancelCount)
            assertEquals(1, currentLifecycle.landing.startCount)
            assertTrue(nextResults.isEmpty())
        } finally {
            currentLifecycle.close()
        }
    }

    @Test
    fun `discrete and Virtual Stick lifecycles reject overlap in both directions`() {
        val discreteActive = Harness()
        try {
            val landingResults = mutableListOf<ConsoleExecutionResult>()
            discreteActive.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                landingResults += it
            }
            discreteActive.landing.completeStart()
            assertTrue(landingResults.single().succeeded)

            val controlResults = mutableListOf<ConsoleExecutionResult>()
            discreteActive.executor.submitControl(control(controlEpoch = 1L)) {
                controlResults += it
            }

            assertFalse(controlResults.single().succeeded)
            assertEquals("dji_discrete_busy", controlResults.single().reason)
            assertTrue(discreteActive.flight.activations.isEmpty())
            assertTrue(discreteActive.flight.submissions.isEmpty())
        } finally {
            discreteActive.close()
        }

        val controlActive = Harness()
        try {
            val controlResults = mutableListOf<ConsoleExecutionResult>()
            controlActive.executor.submitControl(control(controlEpoch = 1L)) {
                controlResults += it
            }
            assertTrue(controlResults.isEmpty())

            val whileActivating = mutableListOf<ConsoleExecutionResult>()
            controlActive.executor.executeDiscrete(discrete(ConsoleDiscreteAction.TAKEOFF)) {
                whileActivating += it
            }
            assertFalse(whileActivating.single().succeeded)
            assertEquals("dji_control_busy", whileActivating.single().reason)
            assertEquals(0, controlActive.takeoff.startCount)

            controlActive.flight.completeNextActivation()
            assertTrue(controlResults.single().succeeded)
            val whileActive = mutableListOf<ConsoleExecutionResult>()
            controlActive.executor.executeDiscrete(
                discrete(ConsoleDiscreteAction.RETURN_TO_HOME),
            ) { whileActive += it }

            assertFalse(whileActive.single().succeeded)
            assertEquals("dji_control_busy", whileActive.single().reason)
            assertEquals(0, controlActive.rth.startCount)
        } finally {
            controlActive.close()
        }
    }

    @Test
    fun `landing confirmation is edge triggered once and success suppresses duplicate true snapshots`() {
        val harness = Harness()
        try {
            harness.landing.deferConfirmCompletion = true
            val commandResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                commandResults += it
            }

            harness.landing.emitConfirmationNeeded(true)
            assertEquals(0, harness.landing.confirmCount)
            harness.landing.emitConfirmationNeeded(false)
            harness.landing.completeStart(landingActiveAtCallback = null)
            assertEquals(1, commandResults.size)
            assertTrue(commandResults.single().succeeded)

            harness.landing.emitConfirmationNeeded(false)
            assertEquals(0, harness.landing.confirmCount)
            harness.landing.emitConfirmationNeeded(true)
            assertEquals(1, harness.landing.confirmCount)
            harness.landing.emitConfirmationNeeded(true)
            assertEquals(1, harness.landing.confirmCount)

            harness.landing.completeConfirm()
            harness.landing.emitConfirmationNeeded(true)

            assertEquals(1, harness.landing.confirmCount)
            assertEquals(0, harness.landing.cancelCount)
            assertEquals(null, harness.landing.landingActive)
            assertEquals(1, commandResults.size)
            assertTrue(commandResults.single().succeeded)
            assertTrue(harness.executor.isReady())

            harness.landing.emitConfirmationNeeded(false)
            val beforeGroundResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
                beforeGroundResults += it
            }
            assertFalse(beforeGroundResults.single().succeeded)
            assertEquals("dji_discrete_busy", beforeGroundResults.single().reason)
            assertEquals(0, harness.rth.startCount)

            harness.telemetry.emitFlight(FlightMode.ON_GROUND, isFlying = false)
            val afterGroundResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
                afterGroundResults += it
            }
            assertEquals(1, harness.rth.startCount)
            assertTrue(afterGroundResults.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `landing confirm failure permanently disables readiness and preserves START callback`() {
        assertLandingConfirmationFault {
            confirmResult =
                LandingActionResult.Failure(
                    LandingActionFailure(
                        action = LandingAction.CONFIRM,
                        kind = AircraftActionFailureKind.SDK_REFUSED,
                        detail = "fake landing CONFIRM failure",
                    ),
                )
        }
    }

    @Test
    fun `landing confirm throw permanently disables readiness and preserves START callback`() {
        assertLandingConfirmationFault { throwOnConfirm = true }
    }

    @Test
    fun `discrete TTL is checked before initialize and again before start`() {
        val alreadyExpired = Harness(consoleNowNanos = 100L)
        try {
            val results = mutableListOf<ConsoleExecutionResult>()
            alreadyExpired.executor.executeDiscrete(
                discrete(ConsoleDiscreteAction.TAKEOFF, expiresAtNanos = 100L),
            ) { results += it }

            assertEquals("dji_command_expired", results.single().reason)
            assertEquals(3, totalInitializations(alreadyExpired))
            assertEquals(0, totalStarts(alreadyExpired))
        } finally {
            alreadyExpired.close()
        }

        val expiresAfterInitialize = Harness(consoleNowNanos = 99L)
        try {
            expiresAfterInitialize.takeoff.onInitialize = {
                expiresAfterInitialize.consoleClock.nowNanos = 100L
            }
            val results = mutableListOf<ConsoleExecutionResult>()
            expiresAfterInitialize.executor.executeDiscrete(
                discrete(ConsoleDiscreteAction.TAKEOFF, expiresAtNanos = 100L),
            ) { results += it }

            assertEquals("dji_command_expired", results.single().reason)
            assertEquals(2, expiresAfterInitialize.takeoff.initializeCount)
            assertEquals(0, expiresAfterInitialize.takeoff.startCount)
        } finally {
            expiresAfterInitialize.close()
        }
    }

    @Test
    fun `close is idempotent cancels pending work and releases listeners without owning ports`() {
        val harness = Harness()
        val controlResults = mutableListOf<ConsoleExecutionResult>()
        harness.executor.submitControl(control(controlEpoch = 9L)) { controlResults += it }
        harness.flight.completeNextActivation()
        assertTrue(controlResults.single().succeeded)

        harness.executor.close()
        harness.executor.close()

        assertEquals(
            listOf(ControlGeneration(1L) to NeutralizationReason.LIFECYCLE_LOSS),
            harness.flight.neutralizations,
        )
        assertEquals(listOf(ControlGeneration(1L)), harness.flight.deactivations)
        assertEquals(1, harness.rc.stopCount)
        assertEquals(0, harness.rc.snapshotListenerCount)
        assertEquals(0, harness.rc.takeoverListenerCount)
        assertEquals(0, harness.flight.snapshotListenerCount)
        assertEquals(0, harness.telemetry.listenerCount)
        assertEquals(0, harness.flight.closeCount)
        assertEquals(0, harness.takeoff.closeCount)
        assertFalse(harness.executor.isReady())

        val discreteHarness = Harness()
        val rthResults = mutableListOf<ConsoleExecutionResult>()
        discreteHarness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
            rthResults += it
        }
        assertTrue(rthResults.isEmpty())

        discreteHarness.executor.close()
        discreteHarness.executor.close()

        assertEquals(1, rthResults.size)
        assertEquals("dji_executor_closed", rthResults.single().reason)
        assertEquals(1, discreteHarness.rth.cancelCount)
    }

    private fun assertLandingConfirmationFault(configure: FakeLandingPort.() -> Unit) {
        val harness = Harness()
        try {
            assertTrue(harness.executor.isReady())
            harness.landing.configure()
            val commandResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.LANDING)) {
                commandResults += it
            }
            harness.landing.completeStart()

            assertEquals(1, commandResults.size)
            assertTrue(commandResults.single().succeeded)
            harness.landing.emitConfirmationNeeded(false)
            harness.landing.emitConfirmationNeeded(true)

            assertEquals(1, harness.landing.confirmCount)
            assertEquals(1, harness.landing.cancelCount)
            assertEquals(1, commandResults.size)
            assertTrue(commandResults.single().succeeded)
            assertFalse(harness.executor.isReady())

            harness.landing.emitConfirmationNeeded(false)
            assertFalse(harness.executor.isReady())
            val rejectedResults = mutableListOf<ConsoleExecutionResult>()
            harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.TAKEOFF)) {
                rejectedResults += it
            }

            assertFalse(rejectedResults.single().succeeded)
            assertEquals("dji_actuation_not_ready", rejectedResults.single().reason)
            assertEquals(0, harness.takeoff.startCount)
            assertEquals(1, harness.landing.confirmCount)
            assertEquals(1, harness.landing.cancelCount)
            assertEquals(1, commandResults.size)
        } finally {
            harness.close()
        }
    }

    private fun acceptRthFromOldCompleted(harness: Harness) {
        harness.rth.preserveStateOnInitialize = true
        harness.rth.emitState(ReturnToHomeState.COMPLETED)
        val commandResults = mutableListOf<ConsoleExecutionResult>()
        harness.executor.executeDiscrete(discrete(ConsoleDiscreteAction.RETURN_TO_HOME)) {
            commandResults += it
        }
        assertTrue(commandResults.isEmpty())

        harness.rth.completeStart(stateAtCallback = ReturnToHomeState.COMPLETED)

        assertEquals(1, commandResults.size)
        assertTrue(commandResults.single().succeeded)
        assertEquals(1, harness.rth.startCount)
    }

    private fun totalInitializations(harness: Harness): Int =
        harness.takeoff.initializeCount + harness.landing.initializeCount + harness.rth.initializeCount

    private fun totalStarts(harness: Harness): Int =
        harness.takeoff.startCount + harness.landing.startCount + harness.rth.startCount

    private fun discrete(
        action: ConsoleDiscreteAction,
        expiresAtNanos: Long = 1_000_000_000L,
    ): AdmittedDiscreteCommand =
        AdmittedDiscreteCommand(
            sessionId = "session-1",
            command =
                ConsoleDiscreteCommand(
                    commandId = "command-${action.name.lowercase()}",
                    leaseId = LEASE_ID,
                    action = action,
                    ttlMillis = 5_000L,
                ),
            authorityDecisionId = "authority-discrete",
            intentDigestSha256 = "a".repeat(64),
            admittedAtNanos = 1L,
            expiresAtNanos = expiresAtNanos,
        )

    private fun control(
        leaseId: String = LEASE_ID,
        inputSequence: Long = 1L,
        controlEpoch: Long,
        expiresAtNanos: Long = 251_000_000L,
    ): AdmittedControlFrame =
        AdmittedControlFrame(
            sessionId = "session-1",
            frame =
                ConsoleControlFrame(
                    leaseId = leaseId,
                    inputSequence = inputSequence,
                    ttlMillis = 250L,
                    forward = 0.4,
                    right = -0.2,
                    up = 0.1,
                    yaw = 0.3,
                ),
            command =
                SaturatedCommand(
                    command =
                        BodyFrameVelocityCommand(
                            forwardMps = 0.8,
                            rightMps = -0.4,
                            upMps = 0.2,
                            yawRateDegreesPerSecond = 9.0,
                        ),
                    clippedAxes = emptySet(),
                ),
            authorityDecisionId = "authority-$controlEpoch",
            intentDigestSha256 = "b".repeat(64),
            admittedAtNanos = 1_000_000L,
            expiresAtNanos = expiresAtNanos,
            controlEpoch = controlEpoch,
        )

    private class Harness(
        consoleNowNanos: Long = 2_000_000L,
        generationWatermark: Long = 0L,
        readinessPollingEnabled: Boolean = false,
    ) : AutoCloseable {
        val consoleClock = MutableClock(consoleNowNanos)
        var actuationNowNanos = 9_876_543_210L
        val takeoff = FakeTakeoffPort()
        val landing = FakeLandingPort()
        val rth = FakeReturnToHomePort()
        val flight = FakeFlightControlPort(generationWatermark)
        val rc = FakePhysicalRcTakeoverPort()
        val telemetry = FakeTelemetrySource()
        val takeovers = mutableListOf<String>()
        val executor =
            DjiConsoleCommandExecutor(
                ports =
                    DjiConsoleActuationPorts(
                        takeoff = takeoff,
                        landing = landing,
                        rth = rth,
                        flightControl = flight,
                        physicalRcTakeover = rc,
                    ),
                telemetrySource = telemetry,
                telemetryRefresh = telemetry::refresh,
                monotonicClock = consoleClock,
                actuationClockNanos = { actuationNowNanos },
                readinessPollingEnabled = readinessPollingEnabled,
                onPhysicalRcTakeover = { takeovers += it },
            )

        override fun close() {
            executor.close()
        }
    }

    private class MutableClock(var nowNanos: Long) : ConsoleMonotonicClock {
        override fun nowNanos(): Long = nowNanos
    }

    private class FakeTelemetrySource : TelemetrySource {
        private val listeners = mutableListOf<TelemetryListener>()
        private var observedAtNanos = 9_800_000_000L
        private var current =
            telemetry(
                mode = FlightMode.IN_AIR,
                isFlying = true,
                observedAtNanos = observedAtNanos,
            )
        val listenerCount: Int
            get() = listeners.size
        var refreshCount = 0
            private set
        var refreshHook: () -> Unit = {}

        override fun latest(): Telemetry = current

        override fun start() = Unit

        override fun stop() = Unit

        override fun addListener(listener: TelemetryListener) {
            listeners += listener
            listener.onTelemetry(current)
        }

        override fun removeListener(listener: TelemetryListener) {
            listeners -= listener
        }

        fun refresh() {
            refreshCount += 1
            refreshHook()
        }

        fun refreshCachedFlight(observedAtNanos: Long) {
            val flightState = checkNotNull(current.flightState)
            this.observedAtNanos = observedAtNanos
            current =
                telemetry(
                    mode = flightState.mode,
                    isFlying = checkNotNull(flightState.isFlying),
                    observedAtNanos = observedAtNanos,
                )
            listeners.toList().forEach { it.onTelemetry(current) }
        }

        fun emitFlight(
            mode: FlightMode,
            isFlying: Boolean,
        ) {
            observedAtNanos += 1L
            current = telemetry(mode, isFlying, observedAtNanos)
            listeners.toList().forEach { it.onTelemetry(current) }
        }

        private fun telemetry(
            mode: FlightMode,
            isFlying: Boolean,
            observedAtNanos: Long,
        ): Telemetry =
            Telemetry(
                timestampEpochMs = 1L,
                monotonicNanos = observedAtNanos,
                flightState =
                    FlightStateTelemetry(
                        mode = mode,
                        isFlying = isFlying,
                        modeObservedAtNanos = observedAtNanos,
                        isFlyingObservedAtNanos = observedAtNanos,
                    ),
            )
    }

    private class FakeFlightControlPort(
        generationWatermark: Long,
    ) : FlightControlCommandPort {
        private data class PendingOperation(
            val generation: ControlGeneration,
            val callback: ActuationOperationCallback,
        )

        private val snapshotListeners = mutableListOf<FlightControlPortSnapshotListener>()
        private val pendingActivations = ArrayDeque<PendingOperation>()
        private val pendingDeactivations = ArrayDeque<PendingOperation>()
        private var snapshot =
            FlightControlPortSnapshot(
                state = FlightControlPortState.IDLE,
                generationWatermark = ControlGeneration(generationWatermark),
            )
        val activations = mutableListOf<ControlGeneration>()
        val deactivations = mutableListOf<ControlGeneration>()
        val neutralizations = mutableListOf<Pair<ControlGeneration, NeutralizationReason>>()
        val submissions = mutableListOf<ActuationCommandFrame>()
        var activateSynchronouslyThenThrow = false
        var closeCount = 0
            private set
        val snapshotListenerCount: Int
            get() = snapshotListeners.size

        override fun currentSnapshot(): FlightControlPortSnapshot = snapshot

        override fun addSnapshotListener(listener: FlightControlPortSnapshotListener) {
            snapshotListeners += listener
            listener.onSnapshot(snapshot)
        }

        override fun removeSnapshotListener(listener: FlightControlPortSnapshotListener) {
            snapshotListeners -= listener
        }

        override fun addCommandEvidenceListener(listener: FlightControlCommandEvidenceListener) = Unit

        override fun removeCommandEvidenceListener(listener: FlightControlCommandEvidenceListener) = Unit

        override fun activate(
            generation: ControlGeneration,
            callback: ActuationOperationCallback,
        ) {
            activations += generation
            snapshot =
                snapshot.copy(
                    state = FlightControlPortState.ACTIVATING,
                    generation = generation,
                    generationWatermark = generation,
                )
            publish()
            if (activateSynchronouslyThenThrow) {
                snapshot = snapshot.copy(state = FlightControlPortState.ACTIVE)
                publish()
                callback.onComplete(
                    ActuationOperationResult(ActuationOperation.ACTIVATE, generation),
                )
                throw IllegalStateException("fake activate threw after synchronous success")
            }
            pendingActivations += PendingOperation(generation, callback)
        }

        fun completeNextActivation(callbackCount: Int = 1) {
            val pending = pendingActivations.removeFirst()
            snapshot =
                snapshot.copy(
                    state = FlightControlPortState.ACTIVE,
                    generation = pending.generation,
                    generationWatermark = pending.generation,
                )
            publish()
            val result = ActuationOperationResult(ActuationOperation.ACTIVATE, pending.generation)
            repeat(callbackCount) { pending.callback.onComplete(result) }
        }

        override fun submit(frame: ActuationCommandFrame): CommandSubmissionStatus {
            submissions += frame
            return CommandSubmissionStatus.SUBMITTED
        }

        override fun neutralize(
            generation: ControlGeneration,
            reason: NeutralizationReason,
        ): CommandSubmissionStatus {
            neutralizations += generation to reason
            return CommandSubmissionStatus.SUBMITTED
        }

        override fun deactivate(
            generation: ControlGeneration,
            callback: ActuationOperationCallback,
        ) {
            deactivations += generation
            snapshot =
                snapshot.copy(
                    state = FlightControlPortState.DEACTIVATING,
                    generation = generation,
                    generationWatermark = generation,
                )
            publish()
            pendingDeactivations += PendingOperation(generation, callback)
        }

        fun completeNextDeactivation(callbackCount: Int = 1) {
            val pending = pendingDeactivations.removeFirst()
            snapshot =
                snapshot.copy(
                    state = FlightControlPortState.IDLE,
                    generation = null,
                    generationWatermark = pending.generation,
                )
            publish()
            val result = ActuationOperationResult(ActuationOperation.DEACTIVATE, pending.generation)
            repeat(callbackCount) { pending.callback.onComplete(result) }
        }

        override fun close() {
            closeCount += 1
        }

        private fun publish() {
            snapshotListeners.toList().forEach { it.onSnapshot(snapshot) }
        }
    }

    private class FakePhysicalRcTakeoverPort : PhysicalRcTakeoverPort {
        private val snapshotListeners = mutableListOf<PhysicalRcTakeoverSnapshotListener>()
        private val takeoverListeners = mutableListOf<PhysicalRcTakeoverListener>()
        private var snapshot =
            PhysicalRcTakeoverSnapshot(
                state = PhysicalRcTakeoverPortState.RUNNING,
                observationReady = true,
                neutral = true,
                lastSampleObservedAtNanos = 1L,
            )
        var startCount = 0
            private set
        var stopCount = 0
            private set
        val snapshotListenerCount: Int
            get() = snapshotListeners.size
        val takeoverListenerCount: Int
            get() = takeoverListeners.size

        override fun start(): PhysicalRcTakeoverStartResult {
            startCount += 1
            return PhysicalRcTakeoverStartResult.Started
        }

        override fun stop() {
            stopCount += 1
            snapshot = PhysicalRcTakeoverSnapshot(state = PhysicalRcTakeoverPortState.IDLE)
            publish()
        }

        override fun currentSnapshot(): PhysicalRcTakeoverSnapshot = snapshot

        override fun addSnapshotListener(listener: PhysicalRcTakeoverSnapshotListener) {
            snapshotListeners += listener
            listener.onSnapshot(snapshot)
        }

        override fun removeSnapshotListener(listener: PhysicalRcTakeoverSnapshotListener) {
            snapshotListeners -= listener
        }

        override fun addPhysicalRcTakeoverListener(listener: PhysicalRcTakeoverListener) {
            takeoverListeners += listener
        }

        override fun removePhysicalRcTakeoverListener(listener: PhysicalRcTakeoverListener) {
            takeoverListeners -= listener
        }

        fun emitTakeover(reason: String) {
            snapshot = snapshot.copy(neutral = false, lastTakeoverReason = reason)
            publish()
            takeoverListeners.toList().forEach { it.onTakeover(reason) }
        }

        fun emitReadyNeutral() {
            snapshot =
                snapshot.copy(
                    state = PhysicalRcTakeoverPortState.RUNNING,
                    observationReady = true,
                    neutral = true,
                )
            publish()
        }

        private fun publish() {
            snapshotListeners.toList().forEach { it.onSnapshot(snapshot) }
        }
    }

    private class FakeTakeoffPort : TakeoffActionPort {
        private val listeners = mutableListOf<TakeoffActionSnapshotListener>()
        private var snapshot = TakeoffActionSnapshot()
        private var startCallback: ((TakeoffActionResult) -> Unit)? = null
        var initializeCount = 0
            private set
        var startCount = 0
            private set
        var stopCount = 0
            private set
        var closeCount = 0
            private set
        var onInitialize: () -> Unit = {}
        var reenterStartCallbackOnStop = false
        var deferStopCompletion = false
        private var stopCallback: ((TakeoffActionResult) -> Unit)? = null

        override fun initialize(): TakeoffActionResult {
            initializeCount += 1
            onInitialize()
            snapshot =
                snapshot.copy(
                    observing = true,
                    aircraftConnected = true,
                    startSupported = true,
                    stopSupported = true,
                )
            publish()
            return TakeoffActionResult.Success(TakeoffAction.INITIALIZE)
        }

        override fun startTakeoff(callback: (TakeoffActionResult) -> Unit) {
            startCount += 1
            startCallback = callback
            snapshot =
                snapshot.copy(
                    pendingAction = TakeoffAction.START,
                    operationId = startCount.toLong(),
                )
            publish()
        }

        fun completeStart(callbackCount: Int = 1) {
            val callback = checkNotNull(startCallback)
            startCallback = null
            snapshot =
                snapshot.copy(
                    pendingAction = null,
                    operationId = null,
                    takeoffMayBeActive = true,
                )
            publish()
            val result = TakeoffActionResult.Success(TakeoffAction.START)
            repeat(callbackCount) { callback(result) }
        }

        override fun stopTakeoff(callback: (TakeoffActionResult) -> Unit) {
            stopCount += 1
            val originalStart = startCallback
            startCallback = null
            snapshot =
                snapshot.copy(
                    pendingAction = null,
                    operationId = null,
                    takeoffMayBeActive = false,
                )
            publish()
            if (reenterStartCallbackOnStop) {
                originalStart?.invoke(
                    TakeoffActionResult.Failure(
                        TakeoffActionFailure(
                            action = TakeoffAction.START,
                            kind = TakeoffActionFailureKind.CANCELLED,
                            detail = "cancelled by fake physical RC takeover",
                        ),
                    ),
                )
            }
            if (deferStopCompletion) {
                stopCallback = callback
            } else {
                callback(TakeoffActionResult.Success(TakeoffAction.STOP))
            }
        }

        fun completeStop() {
            checkNotNull(stopCallback).invoke(TakeoffActionResult.Success(TakeoffAction.STOP))
            stopCallback = null
        }

        override fun currentSnapshot(): TakeoffActionSnapshot = snapshot

        override fun addSnapshotListener(listener: TakeoffActionSnapshotListener) {
            listeners += listener
        }

        override fun removeSnapshotListener(listener: TakeoffActionSnapshotListener) {
            listeners -= listener
        }

        override fun close() {
            closeCount += 1
        }

        private fun publish() {
            listeners.toList().forEach { it.onSnapshot(snapshot) }
        }
    }

    private class FakeLandingPort : LandingActionPort {
        private val listeners = mutableListOf<LandingActionSnapshotListener>()
        private var snapshot = LandingActionSnapshot()
        private var startCallback: ((LandingActionResult) -> Unit)? = null
        var initializeCount = 0
            private set
        var startCount = 0
            private set
        var cancelCount = 0
            private set
        var confirmCount = 0
            private set
        var cancelFailureKind: AircraftActionFailureKind? = null
        var confirmResult: LandingActionResult = LandingActionResult.Success(LandingAction.CONFIRM)
        var deferConfirmCompletion = false
        var throwOnConfirm = false
        private var confirmCallback: ((LandingActionResult) -> Unit)? = null
        val landingActive: Boolean?
            get() = snapshot.landingActive

        override fun initialize(): LandingActionResult {
            initializeCount += 1
            snapshot =
                snapshot.copy(
                    observing = true,
                    aircraftConnected = true,
                    startSupported = true,
                    cancelSupported = true,
                    confirmSupported = true,
                    confirmationNeeded = false,
                )
            publish()
            return LandingActionResult.Success(LandingAction.INITIALIZE)
        }

        override fun start(callback: (LandingActionResult) -> Unit) {
            startCount += 1
            startCallback = callback
            snapshot = snapshot.copy(pendingAction = LandingAction.START)
            publish()
        }

        fun completeStart(
            callbackCount: Int = 1,
            landingActiveAtCallback: Boolean? = true,
        ) {
            val callback = checkNotNull(startCallback)
            startCallback = null
            snapshot =
                snapshot.copy(
                    aircraftConnected = true,
                    pendingAction = null,
                    landingActive = landingActiveAtCallback,
                    lastFailure = null,
                )
            publish()
            val result = LandingActionResult.Success(LandingAction.START)
            repeat(callbackCount) { callback(result) }
        }

        fun completeStartFailure(kind: AircraftActionFailureKind) {
            val callback = checkNotNull(startCallback)
            startCallback = null
            val failure =
                LandingActionFailure(
                    action = LandingAction.START,
                    kind = kind,
                    detail = "fake landing START failure",
                )
            snapshot =
                snapshot.copy(
                    aircraftConnected = false,
                    pendingAction = null,
                    lastFailure = failure,
                )
            publish()
            callback(LandingActionResult.Failure(failure))
        }

        fun emitLandingActive(active: Boolean) {
            snapshot =
                snapshot.copy(
                    aircraftConnected = true,
                    pendingAction = null,
                    landingActive = active,
                    lastFailure = null,
                )
            publish()
        }

        fun emitConfirmationNeeded(needed: Boolean) {
            snapshot =
                snapshot.copy(
                    observing = true,
                    aircraftConnected = true,
                    startSupported = true,
                    cancelSupported = true,
                    confirmSupported = true,
                    confirmationNeeded = needed,
                    lastFailure = null,
                )
            publish()
        }

        override fun cancel(callback: (LandingActionResult) -> Unit) {
            cancelCount += 1
            cancelFailureKind?.let { kind ->
                callback(
                    LandingActionResult.Failure(
                        LandingActionFailure(
                            action = LandingAction.CANCEL,
                            kind = kind,
                            detail = "fake landing CANCEL failure",
                        ),
                    ),
                )
                return
            }
            startCallback = null
            snapshot =
                snapshot.copy(
                    aircraftConnected = true,
                    pendingAction = null,
                    landingActive = false,
                    lastFailure = null,
                )
            publish()
            callback(LandingActionResult.Success(LandingAction.CANCEL))
        }

        override fun confirm(callback: (LandingActionResult) -> Unit) {
            confirmCount += 1
            if (throwOnConfirm) {
                throw IllegalStateException("fake landing CONFIRM throw")
            }
            if (deferConfirmCompletion) {
                check(confirmCallback == null) { "fake landing CONFIRM is already pending" }
                confirmCallback = callback
            } else {
                callback(confirmResult)
            }
        }

        fun completeConfirm() {
            val callback = checkNotNull(confirmCallback)
            confirmCallback = null
            callback(confirmResult)
        }

        override fun currentSnapshot(): LandingActionSnapshot = snapshot

        override fun addSnapshotListener(listener: LandingActionSnapshotListener) {
            listeners += listener
        }

        override fun removeSnapshotListener(listener: LandingActionSnapshotListener) {
            listeners -= listener
        }

        private fun publish() {
            listeners.toList().forEach { it.onSnapshot(snapshot) }
        }
    }

    private class FakeReturnToHomePort : ReturnToHomeActionPort {
        private val listeners = mutableListOf<ReturnToHomeActionSnapshotListener>()
        private var snapshot = ReturnToHomeActionSnapshot()
        private var startCallback: ((ReturnToHomeActionResult) -> Unit)? = null
        var initializeCount = 0
            private set
        var startCount = 0
            private set
        var cancelCount = 0
            private set
        var firstCancelReturnsBusy = false
        var cancelFailureKind: AircraftActionFailureKind? = null
        var preserveStateOnInitialize = false

        override fun initialize(): ReturnToHomeActionResult {
            initializeCount += 1
            snapshot =
                snapshot.copy(
                    observing = true,
                    aircraftConnected = true,
                    startSupported = true,
                    cancelSupported = true,
                    state =
                        if (preserveStateOnInitialize) snapshot.state
                        else ReturnToHomeState.IDLE,
                )
            publish()
            return ReturnToHomeActionResult.Success(ReturnToHomeAction.INITIALIZE)
        }

        override fun start(callback: (ReturnToHomeActionResult) -> Unit) {
            startCount += 1
            startCallback = callback
            snapshot = snapshot.copy(pendingAction = ReturnToHomeAction.START)
            publish()
        }

        fun completeStart(
            callbackCount: Int = 1,
            stateAtCallback: ReturnToHomeState = ReturnToHomeState.RETURNING_TO_HOME,
        ) {
            val callback = checkNotNull(startCallback)
            startCallback = null
            snapshot =
                snapshot.copy(
                    aircraftConnected = true,
                    pendingAction = null,
                    state = stateAtCallback,
                    lastFailure = null,
                )
            publish()
            val result = ReturnToHomeActionResult.Success(ReturnToHomeAction.START)
            repeat(callbackCount) { callback(result) }
        }

        fun completeStartFailure(kind: AircraftActionFailureKind) {
            val callback = checkNotNull(startCallback)
            startCallback = null
            val failure =
                ReturnToHomeActionFailure(
                    action = ReturnToHomeAction.START,
                    kind = kind,
                    detail = "fake RTH START failure",
                )
            snapshot =
                snapshot.copy(
                    aircraftConnected = false,
                    pendingAction = null,
                    state = ReturnToHomeState.UNKNOWN,
                    lastFailure = failure,
                )
            publish()
            callback(ReturnToHomeActionResult.Failure(failure))
        }

        fun emitState(state: ReturnToHomeState) {
            snapshot =
                snapshot.copy(
                    aircraftConnected = true,
                    pendingAction = null,
                    state = state,
                    lastFailure = null,
                )
            publish()
        }

        override fun cancel(callback: (ReturnToHomeActionResult) -> Unit) {
            cancelCount += 1
            if (firstCancelReturnsBusy && cancelCount == 1) {
                callback(
                    ReturnToHomeActionResult.Failure(
                        ReturnToHomeActionFailure(
                            action = ReturnToHomeAction.CANCEL,
                            kind = AircraftActionFailureKind.BUSY,
                            detail = "START callback is still pending",
                        ),
                    ),
                )
                return
            }
            cancelFailureKind?.let { kind ->
                callback(
                    ReturnToHomeActionResult.Failure(
                        ReturnToHomeActionFailure(
                            action = ReturnToHomeAction.CANCEL,
                            kind = kind,
                            detail = "fake RTH CANCEL failure",
                        ),
                    ),
                )
                return
            }
            startCallback = null
            snapshot =
                snapshot.copy(
                    aircraftConnected = true,
                    pendingAction = null,
                    state = ReturnToHomeState.IDLE,
                    lastFailure = null,
                )
            publish()
            callback(ReturnToHomeActionResult.Success(ReturnToHomeAction.CANCEL))
        }

        override fun currentSnapshot(): ReturnToHomeActionSnapshot = snapshot

        override fun addSnapshotListener(listener: ReturnToHomeActionSnapshotListener) {
            listeners += listener
        }

        override fun removeSnapshotListener(listener: ReturnToHomeActionSnapshotListener) {
            listeners -= listener
        }

        private fun publish() {
            listeners.toList().forEach { it.onSnapshot(snapshot) }
        }
    }

    private companion object {
        const val LEASE_ID = "lease-1"
    }
}
