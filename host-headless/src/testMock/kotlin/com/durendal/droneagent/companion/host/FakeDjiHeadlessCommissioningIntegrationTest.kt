package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleActuationIntent
import com.durendal.droneagent.companion.console.server.ConsoleActuationObservation
import com.durendal.droneagent.companion.console.server.ConsoleActuationReadinessSnapshot
import com.durendal.droneagent.companion.console.server.ConsoleAuditEvent
import com.durendal.droneagent.companion.console.server.ConsoleAuditKind
import com.durendal.droneagent.companion.console.server.ConsoleAuditSink
import com.durendal.droneagent.companion.console.server.ConsoleCommandAck
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleCommandDecision
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningRevokeDecision
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningSessionView
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningStartDecision
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningStartResult
import com.durendal.droneagent.companion.console.server.ConsoleControlFrame
import com.durendal.droneagent.companion.console.server.ConsoleControlNeutral
import com.durendal.droneagent.companion.console.server.ConsoleControlStatus
import com.durendal.droneagent.companion.console.server.ConsoleCoreEvent
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleEventSink
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleLeaseStatus
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleNeutralRequestReason
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.JdkConsoleDeadlineScheduler
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test-only composition proof for the remaining #10 hardware-commissioning boundary.
 *
 * No fake in this file is packaged into the APK. In particular, [HeadlessRuntimeController] sees
 * only [HeadlessRuntime]; the separate trusted handle is deliberately retained by the test and is
 * never reachable through an Android intent, HTTP route, WebSocket message, or startup callback.
 */
class FakeDjiHeadlessCommissioningIntegrationTest {
    @Test
    fun `headless startup reaches connected telemetry while real core stays locked`() {
        val runtime = FakeDjiHeadlessRuntime()
        val lifecycleEvidence = CopyOnWriteArrayList<LifecycleEvent>()
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                DurableLifecycleEvidence { event, _ -> lifecycleEvidence += event },
            )
        runtime.controller = controller

        assertEquals(
            RuntimeStartResult.STARTED,
            controller.start(LifecycleTrigger.BOOT_COMPLETED),
        )
        assertEquals(
            listOf("listeners", "aircraft_connected", "network_ready", "telemetry_started"),
            runtime.startupTrace,
        )
        assertTrue(runtime.visibleReadinessHistory.isNotEmpty())
        assertTrue(
            runtime.visibleReadinessHistory.all {
                it.adapter == AdapterKind.DJI &&
                    it.aircraftConnection == AircraftConnectionState.CONNECTED &&
                    it.actuationLock == ActuationLockState.LOCKED &&
                    it.operatingProfile == OperatingProfile.HARDWARE_COMMISSIONING
            },
        )
        assertEquals(0, runtime.trustedCommissioning.startCalls)
        assertTrue(LifecycleEvent.RUNTIME_STARTED in lifecycleEvidence)

        runtime.openOperatorSession()
        val denied = runtime.core.acquireLease(OPERATOR_SESSION_ID, LEASE_TTL_MILLIS)
        assertEquals(ConsoleLeaseStatus.DENIED, denied.status)
        assertEquals("actuation_not_ready", denied.reason)
        assertTrue(runtime.executor.discrete.isEmpty())
        assertTrue(runtime.executor.controls.isEmpty())

        // A late connected/ready callback after the lifecycle stop signal may update observer
        // truth, but the Core's one-way actuation-admission fence must remain closed.
        controller.requestStop()
        runtime.publishReadyObservation()
        val lateDenied = runtime.core.acquireLease(OPERATOR_SESSION_ID, LEASE_TTL_MILLIS)
        assertEquals(ConsoleLeaseStatus.DENIED, lateDenied.status)
        assertEquals("actuation_admission_closed", lateDenied.reason)
        assertEquals(0, runtime.trustedCommissioning.startCalls)
        assertEquals(RuntimeCloseResult.CLOSED, controller.closeWithin(CLOSE_TIMEOUT_MILLIS))
        assertTrue(runtime.cleanedUp)
    }

    @Test
    fun `only the test-owned trusted handle opens one immutable intent at a time`() {
        ConsoleActuationIntent.entries.forEach { allowedIntent ->
            val runtime = startedRuntime()
            try {
                val requestedAllowList = EnumSet.of(allowedIntent)
                val start =
                    runtime.trustedCommissioning.start(
                        operatorSessionId = OPERATOR_SESSION_ID,
                        allowedIntents = requestedAllowList,
                        ttlMillis = COMMISSIONING_TTL_MILLIS,
                )
                assertEquals(ConsoleCommissioningStartDecision.STARTED, start.decision)
                assertNotNull(start.session)
                val grant = checkNotNull(start.session)

                // A caller cannot mutate the committed authority after the required start audit.
                requestedAllowList.addAll(ConsoleActuationIntent.entries)
                assertEquals(setOf(allowedIntent), grant.allowedIntents)

                val lease = runtime.acquireHeldLease()
                ConsoleActuationIntent.entries
                    .filterNot { it == allowedIntent }
                    .forEach { deniedIntent ->
                        assertRejected(runtime, lease, deniedIntent, allowedIntent)
                    }

                when (allowedIntent) {
                    ConsoleActuationIntent.VIRTUAL_STICK -> {
                        val ack =
                            runtime.core.handleControlFrame(
                                OPERATOR_SESSION_ID,
                                controlFrame(lease, inputSequence = 100L),
                            )
                        assertEquals(ConsoleControlStatus.APPLIED, ack.status)
                        assertEquals(1, runtime.executor.controls.size)
                        assertTrue(runtime.executor.discrete.isEmpty())
                    }
                    else -> {
                        val action = allowedIntent.toDiscreteAction()
                        val ack =
                            runtime.core.handleDiscreteCommand(
                                OPERATOR_SESSION_ID,
                                ConsoleDiscreteCommand(
                                    commandId = "allowed-${allowedIntent.name.lowercase()}",
                                    leaseId = lease,
                                    action = action,
                                    ttlMillis = COMMAND_TTL_MILLIS,
                                ),
                            )
                        assertEquals(ConsoleCommandDecision.ACCEPTED, ack.decision)
                        assertEquals(listOf(action), runtime.executor.discrete.map { it.command.action })
                        assertTrue(runtime.executor.controls.isEmpty())
                    }
                }
                assertEquals(1, runtime.trustedCommissioning.startCalls)
                assertTrue(
                    runtime.audit.events.any {
                        it.kind == ConsoleAuditKind.COMMISSIONING_STARTED &&
                            it.subjectId == grant.commissioningId
                    },
                )
            } finally {
                assertEquals(RuntimeCloseResult.CLOSED, runtime.controller.closeWithin(CLOSE_TIMEOUT_MILLIS))
            }
        }
    }

    @Test
    fun `trusted revoke and runtime close each terminalize with a fresh neutral`() {
        val runtime = startedRuntime()
        val firstGrant = runtime.startVirtualStickCommissioning()
        val firstLease = runtime.acquireHeldLease()
        assertEquals(
            ConsoleControlStatus.APPLIED,
            runtime.core.handleControlFrame(
                OPERATOR_SESSION_ID,
                controlFrame(firstLease, inputSequence = 1L),
            ).status,
        )
        assertNull(
            runtime.core.handleControlNeutral(
                OPERATOR_SESSION_ID,
                ConsoleControlNeutral(
                    firstLease,
                    inputSequence = 2L,
                    reason = ConsoleNeutralRequestReason.WINDOW_BLUR,
                ),
            ),
        )
        assertEquals(1, runtime.executor.neutrals.size)

        assertEquals(
            ConsoleCommissioningRevokeDecision.REVOKED,
            runtime.trustedCommissioning.revoke(firstGrant),
        )
        assertNull(runtime.core.currentLease())
        assertEquals(2, runtime.executor.neutrals.size)
        assertEquals(
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
            runtime.executor.neutrals.last().trigger,
        )

        val replacementGrant = runtime.startVirtualStickCommissioning()
        val replacementLease = runtime.acquireHeldLease()
        assertEquals(
            ConsoleControlStatus.APPLIED,
            runtime.core.handleControlFrame(
                OPERATOR_SESSION_ID,
                controlFrame(replacementLease, inputSequence = 1L),
            ).status,
        )

        assertEquals(RuntimeCloseResult.CLOSED, runtime.controller.closeWithin(CLOSE_TIMEOUT_MILLIS))
        assertTrue(runtime.cleanedUp)
        assertNull(runtime.core.currentLease())
        assertEquals(3, runtime.executor.neutrals.size)
        assertEquals(
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
            runtime.executor.neutrals.last().trigger,
        )
        assertEquals(
            setOf("host_revoked", "server_closed"),
            runtime.audit.events
                .filter { it.kind == ConsoleAuditKind.COMMISSIONING_TERMINATED }
                .filter { it.subjectId == firstGrant.commissioningId || it.subjectId == replacementGrant.commissioningId }
                .mapNotNull(ConsoleAuditEvent::reason)
                .toSet(),
        )
        val finalNeutralIndex =
            runtime.audit.events.indexOfLast { it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED }
        val serverStoppedIndex =
            runtime.audit.events.indexOfLast { it.kind == ConsoleAuditKind.SERVER_STOPPED }
        assertTrue("final neutral evidence is missing", finalNeutralIndex >= 0)
        assertTrue("server-stopped evidence is missing", serverStoppedIndex >= 0)
        assertTrue(finalNeutralIndex < serverStoppedIndex)
    }

    private fun startedRuntime(): FakeDjiHeadlessRuntime {
        val runtime = FakeDjiHeadlessRuntime()
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                DurableLifecycleEvidence { _, _ -> },
            )
        runtime.controller = controller
        assertEquals(RuntimeStartResult.STARTED, controller.start(LifecycleTrigger.EXPLICIT_START))
        runtime.openOperatorSession()
        return runtime
    }

    private fun assertRejected(
        runtime: FakeDjiHeadlessRuntime,
        leaseId: String,
        deniedIntent: ConsoleActuationIntent,
        allowedIntent: ConsoleActuationIntent,
    ) {
        when (deniedIntent) {
            ConsoleActuationIntent.VIRTUAL_STICK -> {
                val ack =
                    runtime.core.handleControlFrame(
                        OPERATOR_SESSION_ID,
                        controlFrame(leaseId, inputSequence = 10L),
                    )
                assertEquals(ConsoleControlStatus.REJECTED, ack.status)
            }
            else -> {
                val ack: ConsoleCommandAck =
                    runtime.core.handleDiscreteCommand(
                        OPERATOR_SESSION_ID,
                        ConsoleDiscreteCommand(
                            commandId =
                                "denied-${allowedIntent.name.lowercase()}-${deniedIntent.name.lowercase()}",
                            leaseId = leaseId,
                            action = deniedIntent.toDiscreteAction(),
                            ttlMillis = COMMAND_TTL_MILLIS,
                        ),
                    )
                assertEquals(ConsoleCommandDecision.REJECTED, ack.decision)
            }
        }
    }

    private fun controlFrame(leaseId: String, inputSequence: Long) =
        ConsoleControlFrame(
            leaseId = leaseId,
            inputSequence = inputSequence,
            ttlMillis = CONTROL_TTL_MILLIS,
            forward = 0.25,
            right = 0.0,
            up = 0.0,
            yaw = 0.0,
        )

    private fun ConsoleActuationIntent.toDiscreteAction(): ConsoleDiscreteAction =
        when (this) {
            ConsoleActuationIntent.TAKEOFF -> ConsoleDiscreteAction.TAKEOFF
            ConsoleActuationIntent.LANDING -> ConsoleDiscreteAction.LANDING
            ConsoleActuationIntent.RETURN_TO_HOME -> ConsoleDiscreteAction.RETURN_TO_HOME
            ConsoleActuationIntent.VIRTUAL_STICK -> error("virtual stick is not a discrete action")
        }

    private class FakeDjiHeadlessRuntime : HeadlessRuntime {
        val executor = RecordingExecutor()
        val audit = RecordingAuditSink()
        val startupTrace = mutableListOf<String>()
        val visibleReadinessHistory = mutableListOf<ConsoleActuationReadinessSnapshot>()
        private val monotonicClock = ConsoleMonotonicClock(System::nanoTime)
        private val epochClock = ConsoleEpochClock { 1_700_000_000_000L }
        private val scheduler = JdkConsoleDeadlineScheduler(monotonicClock)
        private var started = false
        private var startupCompleted = false
        @Volatile var cleanedUp = false
            private set

        val core =
            ConsoleServerCore(
                config = ConsoleServerCoreConfig(),
                admission =
                    ConsoleCommandAdmission(
                        agentId = "fake-dji-agent",
                        streamId = "fake-dji-main",
                        epochClock = epochClock,
                    ),
                executor = executor,
                monotonicClock = monotonicClock,
                epochClock = epochClock,
                scheduler = scheduler,
                auditSink = audit,
                eventSink = ConsoleEventSink { _, event -> events += event },
            )
        val trustedCommissioning = TestOnlyTrustedCommissioningPort(core)
        val events = CopyOnWriteArrayList<ConsoleCoreEvent>()
        lateinit var controller: HeadlessRuntimeController
        private val closer =
            AndroidRuntimeCloser(
                monotonicNanos = System::nanoTime,
                stopServerWithin = { _ -> },
                initiateCoreStop = core::close,
                awaitNeutral = core::awaitShutdownNeutral,
                cleanupAfterNeutral = {
                    scheduler.close()
                    cleanedUp = true
                },
            )

        override fun requestStop() {
            core.closeActuationAdmission()
        }

        override fun start() {
            check(!started) { "fake DJI runtime may start only once" }
            started = true
            startupTrace += "listeners"
            startupTrace += "aircraft_connected"
            publishReadyObservation()
            startupTrace += "network_ready"
        }

        override fun completeStartup() {
            check(started && !startupCompleted) { "fake DJI startup is not completable" }
            startupCompleted = true
            startupTrace += "telemetry_started"
            publishReadyObservation()
        }

        override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult =
            closer.closeWithin(timeoutMillis)

        fun publishReadyObservation() {
            visibleReadinessHistory += DJI_LOCKED_READINESS
            core.updateActuationReadiness(DJI_LOCKED_READINESS)
            core.updateActuationObservation(DJI_READY_OBSERVATION)
        }

        fun openOperatorSession() {
            core.openSession(OPERATOR_SESSION_ID, clientInstanceId = "test-only-client")
        }

        fun acquireHeldLease(): String {
            val state = core.acquireLease(OPERATOR_SESSION_ID, LEASE_TTL_MILLIS)
            assertEquals(ConsoleLeaseStatus.HELD, state.status)
            return checkNotNull(state.leaseId)
        }

        fun startVirtualStickCommissioning(): ConsoleCommissioningSessionView =
            checkNotNull(
                trustedCommissioning.start(
                    operatorSessionId = OPERATOR_SESSION_ID,
                    allowedIntents = setOf(ConsoleActuationIntent.VIRTUAL_STICK),
                    ttlMillis = COMMISSIONING_TTL_MILLIS,
                ).session,
            )
    }

    /** This handle is intentionally not implemented by or passed to [HeadlessRuntimeController]. */
    private class TestOnlyTrustedCommissioningPort(
        private val core: ConsoleServerCore,
    ) {
        var startCalls = 0
            private set

        fun start(
            operatorSessionId: String,
            allowedIntents: Set<ConsoleActuationIntent>,
            ttlMillis: Long,
        ): ConsoleCommissioningStartResult {
            startCalls++
            return core.startHardwareCommissioning(operatorSessionId, allowedIntents, ttlMillis)
        }

        fun revoke(session: ConsoleCommissioningSessionView): ConsoleCommissioningRevokeDecision =
            core.revokeHardwareCommissioning(session)
    }

    private class RecordingExecutor : ConsoleCommandExecutor {
        val discrete = CopyOnWriteArrayList<AdmittedDiscreteCommand>()
        val controls = CopyOnWriteArrayList<AdmittedControlFrame>()
        val neutrals = CopyOnWriteArrayList<NeutralInvocation>()

        override fun executeDiscrete(
            command: AdmittedDiscreteCommand,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            discrete += command
            callback(ConsoleExecutionResult(succeeded = true))
        }

        override fun submitControl(
            frame: AdmittedControlFrame,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            controls += frame
            callback(ConsoleExecutionResult(succeeded = true))
        }

        override fun neutralize(
            leaseId: String,
            controlEpoch: Long,
            trigger: ConsoleSafetyTrigger,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            neutrals += NeutralInvocation(leaseId, controlEpoch, trigger)
            callback(ConsoleExecutionResult(succeeded = true))
        }
    }

    private class RecordingAuditSink : ConsoleAuditSink {
        val events = CopyOnWriteArrayList<ConsoleAuditEvent>()

        override fun record(event: ConsoleAuditEvent) {
            events += event
        }
    }

    private data class NeutralInvocation(
        val leaseId: String,
        val controlEpoch: Long,
        val trigger: ConsoleSafetyTrigger,
    )

    private companion object {
        const val OPERATOR_SESSION_ID = "fake-dji-operator"
        const val LEASE_TTL_MILLIS = 5_000L
        const val COMMISSIONING_TTL_MILLIS = 5_000L
        const val COMMAND_TTL_MILLIS = 1_000L
        const val CONTROL_TTL_MILLIS = 200L
        const val CLOSE_TIMEOUT_MILLIS = 2_000L

        val DJI_LOCKED_READINESS =
            ConsoleActuationReadinessSnapshot(
                adapter = AdapterKind.DJI,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                actuationLock = ActuationLockState.LOCKED,
                operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
            )
        val DJI_READY_OBSERVATION =
            ConsoleActuationObservation(
                adapter = AdapterKind.DJI,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                adapterActuationReady = true,
                operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
            )
    }
}
