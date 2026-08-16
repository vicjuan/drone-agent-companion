package com.durendal.droneagent.companion.console.server.transport

import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.CapabilityEvidenceStatus
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.CommissioningAuthorityReason
import com.durendal.droneagent.companion.console.protocol.CommissioningAuthorityState
import com.durendal.droneagent.companion.console.protocol.CommissioningAuthorityStatePayload
import com.durendal.droneagent.companion.console.protocol.CommissioningIntent
import com.durendal.droneagent.companion.console.protocol.CommandAckPayload
import com.durendal.droneagent.companion.console.protocol.CommandDecision
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleClientPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleMessageType
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.protocol.ConsoleServerMessage
import com.durendal.droneagent.companion.console.protocol.ControlAckPayload
import com.durendal.droneagent.companion.console.protocol.ControlAckStatus
import com.durendal.droneagent.companion.console.protocol.ControlFramePayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralPayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralReason
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import com.durendal.droneagent.companion.console.protocol.HealthPayload
import com.durendal.droneagent.companion.console.protocol.HealthStatus
import com.durendal.droneagent.companion.console.protocol.LeaseAcquirePayload
import com.durendal.droneagent.companion.console.protocol.LeaseState
import com.durendal.droneagent.companion.console.protocol.LeaseStatePayload
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorPayload
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload
import com.durendal.droneagent.companion.console.protocol.SafetyEventPayload
import com.durendal.droneagent.companion.console.protocol.SafetyOutcome
import com.durendal.droneagent.companion.console.protocol.SafetyTrigger
import com.durendal.droneagent.companion.console.protocol.ServerHelloPayload
import com.durendal.droneagent.companion.console.protocol.TelemetryPayload
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleActuationIntent
import com.durendal.droneagent.companion.console.server.ConsoleActuationObservation
import com.durendal.droneagent.companion.console.server.ConsoleAuditEvent
import com.durendal.droneagent.companion.console.server.ConsoleAuditKind
import com.durendal.droneagent.companion.console.server.ConsoleAuditSink
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningLifecycle
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningRevokeDecision
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningSessionView
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningAuthorityReason
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningAuthorityState
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningAuthorityStatus
import com.durendal.droneagent.companion.console.server.ConsoleCoreEvent
import com.durendal.droneagent.companion.console.server.ConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleScheduledTask
import com.durendal.droneagent.companion.console.server.ConsoleSession
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.G520ProtocolCapabilitySource
import com.durendal.droneagent.companion.console.server.security.ConsoleExposurePolicy
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory Ktor WebSocket -> strict protocol -> real Core with synthetic DJI observations. */
class HardwareCommissioningWebSocketIntegrationTest {
    private val codec = ConsoleProtocolCodec()

    @Test
    fun `locked DJI websocket needs an out-of-band exact-intent grant and disconnect revokes it`() {
        val webRoot = Files.createTempDirectory("commissioning-ws-e2e")
        Files.writeString(webRoot.resolve("index.html"), "<html>commissioning fixture</html>")
        val fixture = RuntimeFixture()
        try {
            testApplication {
                application {
                    installConsoleApplication(
                        ConsoleServerConfig.create(
                            exposure = ConsoleExposurePolicy.localhostDevelopment(bindPort = 0),
                            webRoot = webRoot,
                        ),
                        fixture.controller,
                        sessionIdFactory = fixture::nextSessionId,
                    )
                }
                val socketClient = createClient { install(WebSockets) }
                var firstSessionId: String? = null
                var firstLeaseId: String? = null

                assertEquals(
                    HttpStatusCode.NotFound,
                    client.post("/api/console/v1/commissioning").status,
                )

                socketClient.webSocket(
                    ConsoleRoutes.WEBSOCKET,
                    request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                ) {
                    sendClient("hello-first", hello())
                    val initial = receiveUntil { it.type == ConsoleMessageType.LEASE_STATE }
                    val serverHello = initial.payload<ServerHelloPayload>()
                    val runtime = initial.payload<RuntimeStatePayload>()
                    val capability = initial.payload<CapabilitySnapshotPayload>()
                    firstSessionId = serverHello.sessionId

                    assertEquals(AdapterKind.DJI, runtime.adapter)
                    assertEquals(AircraftConnectionState.CONNECTED, runtime.aircraftConnection)
                    assertEquals(ActuationLockState.LOCKED, runtime.actuationLock)
                    assertEquals(OperatingProfile.HARDWARE_COMMISSIONING, runtime.operatingProfile)
                    assertEquals(17, capability.rows.size)
                    assertTrue(capability.rows.all { it.status == CapabilityEvidenceStatus.UNKNOWN })

                    send(
                        Frame.Text(
                            """{
                              "protocolVersion":"1.0",
                              "messageId":"forged-commissioning-start",
                              "type":"commissioning_start",
                              "payload":{"allowedIntents":["virtual_stick"],"ttlMs":5000}
                            }""".trimIndent(),
                        ),
                    )
                    val forgedStart =
                        receiveUntil { it.payload is ProtocolErrorPayload }
                            .last().payload as ProtocolErrorPayload
                    assertEquals(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, forgedStart.code)
                    assertTrue(fixture.audit.none { it.kind == ConsoleAuditKind.COMMISSIONING_STARTED })
                    assertEquals(null, fixture.currentCommissioningSession())

                    sendClient("lease-before-grant", LeaseAcquirePayload(requestedTtlMs = 5_000))
                    val deniedBeforeGrant =
                        receiveUntil {
                            (it.payload as? LeaseStatePayload)?.requestMessageId == "lease-before-grant"
                        }.last().payload as LeaseStatePayload
                    assertEquals(LeaseState.DENIED, deniedBeforeGrant.state)
                    assertEquals("actuation_not_ready", deniedBeforeGrant.reason)
                    assertTrue(fixture.executor.controls.isEmpty())
                    assertTrue(fixture.audit.none { it.kind == ConsoleAuditKind.COMMISSIONING_STARTED })

                    val started = fixture.startCommissioning(serverHello.sessionId)
                    assertEquals(setOf(ConsoleActuationIntent.VIRTUAL_STICK), started.allowedIntents)
                    assertEquals(serverHello.sessionId, started.operatorSessionId)
                    assertEquals(ActuationLockState.LOCKED, fixture.snapshots.runtimeState().actuationLock)

                    sendClient("lease-after-grant", LeaseAcquirePayload(requestedTtlMs = 5_000))
                    val held =
                        receiveUntil {
                            val lease = it.payload as? LeaseStatePayload
                            lease?.requestMessageId == "lease-after-grant" && lease.state == LeaseState.HELD
                        }.last().payload as LeaseStatePayload
                    val leaseId = checkNotNull(held.leaseId)
                    firstLeaseId = leaseId
                    assertEquals(serverHello.sessionId, held.holderSessionId)

                    sendClient(
                        "takeoff-not-allowed",
                        DiscreteCommandRequestPayload(
                            commandId = "takeoff-not-allowed",
                            leaseId = leaseId,
                            action = DiscreteCommandAction.TAKEOFF,
                            ttlMs = 2_000,
                        ),
                    )
                    val takeoffAck =
                        receiveUntil {
                            (it.payload as? CommandAckPayload)?.commandId == "takeoff-not-allowed"
                        }.last().payload as CommandAckPayload
                    assertEquals(CommandDecision.REJECTED, takeoffAck.decision)
                    assertEquals("actuation_not_ready", takeoffAck.reason)
                    assertTrue(fixture.executor.discrete.isEmpty())

                    sendClient(
                        "control-allowed",
                        ControlFramePayload(
                            leaseId = leaseId,
                            inputSequence = 1L,
                            ttlMs = 250,
                            forward = 0.5,
                            right = 0.0,
                            up = 0.0,
                            yaw = 0.0,
                        ),
                    )
                    val controlAck =
                        receiveUntil {
                            (it.payload as? ControlAckPayload)?.inputSequence == 1L
                        }.last().payload as ControlAckPayload
                    assertEquals(ControlAckStatus.APPLIED, controlAck.status)
                    val applied = fixture.executor.controls.single()
                    assertEquals(serverHello.sessionId, applied.sessionId)
                    assertEquals(1L, applied.frame.inputSequence)
                    assertEquals(0.25, applied.command.command.forwardMps, 1e-12)
                    assertTrue(applied.command.clippedAxes.isEmpty())

                    sendClient(
                        "operator-release",
                        ControlNeutralPayload(
                            leaseId = leaseId,
                            inputSequence = 2L,
                            reason = ControlNeutralReason.OPERATOR_RELEASE,
                        ),
                    )
                    val released =
                        receiveUntil {
                            val safety = it.payload as? SafetyEventPayload
                            safety?.trigger == SafetyTrigger.CLIENT_REQUEST &&
                                safety.lastInputSequence == 2L
                        }
                    val releaseAck =
                        released.mapNotNull { it.payload as? ControlAckPayload }
                            .single { it.inputSequence == 2L }
                    val releaseSafety =
                        released.mapNotNull { it.payload as? SafetyEventPayload }
                            .single {
                                it.trigger == SafetyTrigger.CLIENT_REQUEST &&
                                    it.lastInputSequence == 2L
                            }
                    assertEquals(ControlAckStatus.APPLIED, releaseAck.status)
                    assertEquals(SafetyOutcome.SUCCEEDED, releaseSafety.outcome)
                    assertEquals(leaseId, releaseSafety.leaseId)
                    assertEquals(
                        ConsoleSafetyTrigger.CLIENT_REQUEST,
                        fixture.executor.neutrals.single().trigger,
                    )
                }

                fixture.awaitCommissioningTermination(
                    reason = "operator_disconnected",
                    leaseId = checkNotNull(firstLeaseId),
                )
                assertEquals(null, fixture.currentCommissioningSession())
                assertEquals(2, fixture.executor.neutrals.size)
                val disconnectedNeutral = fixture.executor.neutrals.last()
                // A commissioning generation always terminates through the fresh readiness-loss
                // barrier; the exact external cause remains in required terminal audit evidence.
                assertEquals(ConsoleSafetyTrigger.ACTUATION_READINESS_LOST, disconnectedNeutral.trigger)
                assertEquals(firstLeaseId, disconnectedNeutral.leaseId)
                assertEquals(
                    fixture.executor.neutrals.first().controlEpoch,
                    disconnectedNeutral.controlEpoch,
                )
                fixture.assertTerminalOrdering(checkNotNull(firstLeaseId))
                assertTrue(
                    fixture.audit.any {
                        it.kind == ConsoleAuditKind.COMMISSIONING_TERMINATED &&
                            it.sessionId == firstSessionId &&
                            it.reason == "operator_disconnected"
                    },
                )
                assertTrue(
                    fixture.audit.any {
                        it.kind == ConsoleAuditKind.CONTROL_COMPLETED &&
                            it.outcome == "applied"
                    },
                )

                socketClient.webSocket(
                    ConsoleRoutes.WEBSOCKET,
                    request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                ) {
                    sendClient("hello-replacement", hello())
                    val initial = receiveUntil { it.type == ConsoleMessageType.LEASE_STATE }
                    val replacementHello = initial.payload<ServerHelloPayload>()
                    val replacementRuntime = initial.payload<RuntimeStatePayload>()
                    assertTrue(replacementHello.sessionId != firstSessionId)
                    assertEquals(ActuationLockState.LOCKED, replacementRuntime.actuationLock)

                    sendClient("lease-without-regrant", LeaseAcquirePayload(requestedTtlMs = 5_000))
                    val denied =
                        receiveUntil {
                            (it.payload as? LeaseStatePayload)?.requestMessageId == "lease-without-regrant"
                        }.last().payload as LeaseStatePayload
                    assertEquals(LeaseState.DENIED, denied.state)
                    assertEquals("actuation_not_ready", denied.reason)
                }
            }

            assertEquals(1, fixture.audit.count { it.kind == ConsoleAuditKind.COMMISSIONING_STARTED })
            assertEquals(
                1,
                fixture.audit.count {
                    it.kind == ConsoleAuditKind.COMMISSIONING_TERMINATED &&
                        it.reason == "operator_disconnected"
                },
            )
            assertTrue(fixture.executor.discrete.isEmpty())
            assertEquals(1, fixture.executor.controls.size)
            assertEquals(2, fixture.executor.neutrals.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `v11 authority view is exact-recipient only while v10 remains unchanged`() {
        val webRoot = Files.createTempDirectory("commissioning-authority-ws-e2e")
        Files.writeString(webRoot.resolve("index.html"), "<html>authority fixture</html>")
        val fixture = RuntimeFixture()
        try {
            testApplication {
                application {
                    installConsoleApplication(
                        ConsoleServerConfig.create(
                            exposure = ConsoleExposurePolicy.localhostDevelopment(bindPort = 0),
                            webRoot = webRoot,
                        ),
                        fixture.controller,
                        sessionIdFactory = fixture::nextSessionId,
                    )
                }
                val socketClient = createClient { install(WebSockets) }
                socketClient.webSocket(
                    ConsoleRoutes.WEBSOCKET,
                    request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                ) {
                    val legacySocket = this
                    legacySocket.sendClient("hello-legacy", hello(listOf("1.0")))
                    val legacyInitial =
                        legacySocket.receiveUntil("1.0") {
                            it.type == ConsoleMessageType.LEASE_STATE
                        }
                    assertEquals(
                        "1.0",
                        legacyInitial.payload<ServerHelloPayload>().selectedProtocolVersion,
                    )
                    assertTrue(
                        legacyInitial.none {
                            it.type == ConsoleMessageType.COMMISSIONING_AUTHORITY_STATE
                        },
                    )

                    socketClient.webSocket(
                        ConsoleRoutes.WEBSOCKET,
                        request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                    ) {
                        val observerSocket = this
                        observerSocket.sendClient(
                            "hello-observer",
                            hello(listOf("1.1", "1.0")),
                        )
                        val observerInitial =
                            observerSocket.receiveUntil("1.1") {
                                it.type == ConsoleMessageType.LEASE_STATE
                            }
                        assertEquals(
                            "1.1",
                            observerInitial.payload<ServerHelloPayload>()
                                .selectedProtocolVersion,
                        )
                        val observerAuthority =
                            observerInitial.payload<CommissioningAuthorityStatePayload>()
                        assertEquals(CommissioningAuthorityState.INACTIVE, observerAuthority.state)
                        assertEquals(
                            CommissioningAuthorityReason.NO_ACTIVE_SESSION,
                            observerAuthority.reason,
                        )
                        assertNull(observerAuthority.commissioningId)

                        socketClient.webSocket(
                            ConsoleRoutes.WEBSOCKET,
                            request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                        ) {
                            val ownerSocket = this
                            ownerSocket.sendClient(
                                "hello-owner",
                                hello(listOf("1.1", "1.0")),
                            )
                            val ownerInitial =
                                ownerSocket.receiveUntil("1.1") {
                                    it.type == ConsoleMessageType.LEASE_STATE
                                }
                            val ownerHello = ownerInitial.payload<ServerHelloPayload>()
                            val ownerRuntime = ownerInitial.payload<RuntimeStatePayload>()
                            val initialAuthority =
                                ownerInitial.payload<CommissioningAuthorityStatePayload>()
                            assertEquals("1.1", ownerHello.selectedProtocolVersion)
                            assertEquals(ActuationLockState.LOCKED, ownerRuntime.actuationLock)
                            assertEquals(CommissioningAuthorityState.INACTIVE, initialAuthority.state)
                            assertEquals(
                                CommissioningAuthorityReason.NO_ACTIVE_SESSION,
                                initialAuthority.reason,
                            )
                            assertEquals("0", initialAuthority.generation)

                            fixture.emitStaleRecipientAuthority(ownerHello.sessionId)
                            fixture.publishHealth(10L)
                            val staleRecipientBarrier =
                                ownerSocket.receiveUntil("1.1") {
                                    (it.payload as? HealthPayload)?.uptimeMs == 10L
                                }
                            assertTrue(
                                staleRecipientBarrier.none {
                                    it.payload is CommissioningAuthorityStatePayload
                                },
                            )

                            val grant = fixture.startCommissioning(ownerHello.sessionId)
                            val activeMessages =
                                ownerSocket.receiveUntil("1.1") {
                                    (it.payload as? CommissioningAuthorityStatePayload)?.state ==
                                        CommissioningAuthorityState.ACTIVE
                                }
                            val active =
                                activeMessages.mapNotNull {
                                    it.payload as? CommissioningAuthorityStatePayload
                                }.single { it.state == CommissioningAuthorityState.ACTIVE }
                            assertEquals(grant.commissioningId, active.commissioningId)
                            assertEquals(grant.generation.toString(), active.generation)
                            assertEquals(listOf(CommissioningIntent.VIRTUAL_STICK), active.allowedIntents)
                            assertTrue(checkNotNull(active.expiresInMs) in 1L..5_000L)
                            assertTrue(
                                active.stateRevision.toLong() >
                                    initialAuthority.stateRevision.toLong(),
                            )

                            fixture.publishHealth(20L)
                            val observerAfterActive =
                                observerSocket.receiveUntil("1.1") {
                                    (it.payload as? HealthPayload)?.uptimeMs == 20L
                                }
                            assertTrue(
                                observerAfterActive.none {
                                    it.payload is CommissioningAuthorityStatePayload
                                },
                            )
                            val legacyAfterActive =
                                legacySocket.receiveUntil("1.0") {
                                    (it.payload as? HealthPayload)?.uptimeMs == 20L
                                }
                            assertTrue(
                                legacyAfterActive.none {
                                    it.type == ConsoleMessageType.COMMISSIONING_AUTHORITY_STATE
                                },
                            )

                            fixture.revokeCommissioning(grant)
                            val terminalMessages =
                                ownerSocket.receiveUntil("1.1") {
                                    (it.payload as? CommissioningAuthorityStatePayload)?.reason ==
                                        CommissioningAuthorityReason.HOST_REVOKED
                                }
                            val terminal =
                                terminalMessages.mapNotNull {
                                    it.payload as? CommissioningAuthorityStatePayload
                                }.single { it.reason == CommissioningAuthorityReason.HOST_REVOKED }
                            assertEquals(CommissioningAuthorityState.INACTIVE, terminal.state)
                            assertEquals(active.commissioningId, terminal.commissioningId)
                            assertEquals(active.generation, terminal.generation)
                            assertTrue(
                                terminal.stateRevision.toLong() > active.stateRevision.toLong(),
                            )

                            fixture.publishHealth(30L)
                            val observerAfterTerminal =
                                observerSocket.receiveUntil("1.1") {
                                    (it.payload as? HealthPayload)?.uptimeMs == 30L
                                }
                            assertTrue(
                                observerAfterTerminal.none {
                                    it.payload is CommissioningAuthorityStatePayload
                                },
                            )
                            legacySocket.receiveUntil("1.0") {
                                (it.payload as? HealthPayload)?.uptimeMs == 30L
                            }
                        }
                    }
                }
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendClient(
        messageId: String,
        payload: ConsoleClientPayload,
        protocolVersion: String = ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION,
    ) {
        send(
            Frame.Text(
                codec.encodeClient(
                    ConsoleClientMessage(messageId, payload),
                    protocolVersion,
                ),
            ),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveUntil(
        protocolVersion: String = ConsoleProtocolModule.PROTOCOL_VERSION,
        complete: (ConsoleServerMessage) -> Boolean,
    ): List<ConsoleServerMessage> =
        withTimeout(3_000L) {
            val messages = mutableListOf<ConsoleServerMessage>()
            while (true) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                val message = codec.decodeServer(frame.readText(), protocolVersion)
                messages += message
                if (complete(message)) return@withTimeout messages
            }
            @Suppress("UNREACHABLE_CODE")
            messages
        }

    private inline fun <reified T : Any> List<ConsoleServerMessage>.payload(): T =
        mapNotNull { it.payload as? T }.single()

    private fun hello(
        supportedProtocolVersions: List<String> = listOf("1.0"),
    ): ClientHelloPayload =
        ClientHelloPayload(
            clientName = "commissioning-e2e",
            clientVersion = "0.1.0-test",
            supportedProtocolVersions = supportedProtocolVersions,
            authentication = null,
        )

    private class RuntimeFixture : AutoCloseable {
        val audit = CopyOnWriteArrayList<ConsoleAuditEvent>()
        val timeline = CopyOnWriteArrayList<String>()
        val executor = RecordingExecutor(timeline)
        val snapshots = LockedDjiSnapshots()
        private val clock = FixedClock()
        private val scheduler = RecordingScheduler()
        private val commissioningLifecycle = ConsoleCommissioningLifecycle()
        private val coreReference = AtomicReference<ConsoleServerCore>()
        private val controllerReference = AtomicReference<ProtocolConsoleSocketController>()
        private val sessionSequence = AtomicInteger()
        private val protocol =
            ConsoleCoreProtocolAdapter(
                coreProvider = { checkNotNull(coreReference.get()) },
                snapshots = snapshots,
                serverVersion = "0.1.0-commissioning-test",
                emitPayload = { target, payload ->
                    controllerReference.get()?.emit(target, payload) ?: false
                },
            )
        private val core =
            ConsoleServerCore(
                config = ConsoleServerCoreConfig(),
                admission =
                    ConsoleCommandAdmission(
                        agentId = "fake-dji-agent",
                        streamId = "fake-dji-stream",
                        epochClock = ConsoleEpochClock { TEST_EPOCH_MILLIS },
                    ),
                executor = executor,
                monotonicClock = clock,
                epochClock = ConsoleEpochClock { TEST_EPOCH_MILLIS },
                scheduler = scheduler,
                auditSink =
                    ConsoleAuditSink { event ->
                        timeline += "audit:${event.kind}:${event.leaseId}:${event.reason}"
                        audit += event
                    },
                eventSink = protocol,
                commissioningLifecycle = commissioningLifecycle,
                initialActuationReadiness = snapshots.runtimeState().let {
                    com.durendal.droneagent.companion.console.server.ConsoleActuationReadinessSnapshot(
                        adapter = it.adapter,
                        aircraftConnection = it.aircraftConnection,
                        actuationLock = it.actuationLock,
                        operatingProfile = it.operatingProfile,
                    )
                },
                initialActuationObservation =
                    ConsoleActuationObservation(
                        adapter = AdapterKind.DJI,
                        aircraftConnection = AircraftConnectionState.CONNECTED,
                        adapterActuationReady = true,
                        operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
                    ),
            )
        val controller = ProtocolConsoleSocketController(protocol)

        init {
            coreReference.set(core)
            controllerReference.set(controller)
        }

        fun nextSessionId(): String = "session-commissioning-${sessionSequence.incrementAndGet()}"

        fun currentCommissioningSession() = commissioningLifecycle.currentSession()

        fun assertTerminalOrdering(leaseId: String) {
            val neutral = timeline.indexOf("neutral:$leaseId:ACTUATION_READINESS_LOST")
            val requested =
                timeline.indexOf(
                    "audit:SAFETY_NEUTRAL_REQUESTED:$leaseId:actuation_readiness_lost",
                )
            val completed =
                timeline.indexOf(
                    "audit:SAFETY_NEUTRAL_COMPLETED:$leaseId:actuation_readiness_lost",
                )
            val terminated =
                timeline.indexOf(
                    "audit:COMMISSIONING_TERMINATED:$leaseId:operator_disconnected",
                )
            assertTrue(neutral >= 0)
            assertTrue(requested > neutral)
            assertTrue(completed > requested)
            assertTrue(terminated > requested)
            val requestedAudit =
                audit.single {
                    it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_REQUESTED &&
                        it.reason == "actuation_readiness_lost"
                }
            val completedAudit =
                audit.single {
                    it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED &&
                        it.reason == "actuation_readiness_lost"
                }
            assertEquals(leaseId, requestedAudit.leaseId)
            assertEquals("requested", requestedAudit.outcome)
            assertEquals("actuation_readiness_lost", requestedAudit.reason)
            assertEquals(leaseId, completedAudit.leaseId)
            assertEquals("succeeded", completedAudit.outcome)
            assertEquals("actuation_readiness_lost", completedAudit.reason)
        }

        fun startCommissioning(sessionId: String) =
            checkNotNull(
                core.startHardwareCommissioning(
                    operatorSessionId = sessionId,
                    allowedIntents = setOf(ConsoleActuationIntent.VIRTUAL_STICK),
                    ttlMillis = 5_000L,
                ).session,
            )

        fun revokeCommissioning(session: ConsoleCommissioningSessionView) {
            check(
                core.revokeHardwareCommissioning(session) ==
                    ConsoleCommissioningRevokeDecision.REVOKED,
            )
        }

        fun emitStaleRecipientAuthority(sessionId: String) {
            protocol.emit(
                sessionId,
                ConsoleCoreEvent.CommissioningAuthorityChanged(
                    recipientSession = ConsoleSession(sessionId, "stale-core-session"),
                    state =
                        ConsoleCommissioningAuthorityState(
                            stateRevision = 999L,
                            state = ConsoleCommissioningAuthorityStatus.ACTIVE,
                            commissioningId = "00000000-0000-4000-8000-000000000999",
                            generation = 999L,
                            allowedIntents = setOf(ConsoleActuationIntent.VIRTUAL_STICK),
                            expiresInMillis = 1_000L,
                            reason = null,
                        ),
                ),
            )
        }

        fun publishHealth(uptimeMillis: Long) {
            check(
                protocol.publishHealth(
                    HealthPayload(HealthStatus.HEALTHY, uptimeMillis, null),
                ),
            )
        }

        suspend fun awaitCommissioningTermination(
            reason: String,
            leaseId: String,
        ) {
            withTimeout(3_000L) {
                while (
                    audit.none {
                        it.kind == ConsoleAuditKind.COMMISSIONING_TERMINATED &&
                            it.reason == reason &&
                            it.leaseId == leaseId
                    } ||
                        audit.none {
                            it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED &&
                                it.reason == "actuation_readiness_lost" &&
                                it.leaseId == leaseId
                        }
                ) {
                    delay(5L)
                }
            }
        }

        override fun close() {
            core.close()
        }
    }

    private class LockedDjiSnapshots : ConsoleSnapshotProvider {
        private val capabilities = G520ProtocolCapabilitySource.loadBundled().snapshot()

        override fun runtimeState(): RuntimeStatePayload =
            RuntimeStatePayload(
                adapter = AdapterKind.DJI,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                actuationLock = ActuationLockState.LOCKED,
                operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
            )

        override fun capabilitySnapshot(): CapabilitySnapshotPayload = capabilities

        override fun health(): HealthPayload = HealthPayload(HealthStatus.HEALTHY, 0L, null)

        override fun latestTelemetry(): TelemetryPayload? = null
    }

    private class RecordingExecutor(
        private val timeline: MutableList<String>,
    ) : ConsoleCommandExecutor {
        val discrete = CopyOnWriteArrayList<AdmittedDiscreteCommand>()
        val controls = CopyOnWriteArrayList<AdmittedControlFrame>()
        val neutrals = CopyOnWriteArrayList<NeutralCall>()

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
            neutrals += NeutralCall(leaseId, controlEpoch, trigger)
            timeline += "neutral:$leaseId:${trigger.name}"
            callback(ConsoleExecutionResult(succeeded = true))
        }
    }

    private data class NeutralCall(
        val leaseId: String,
        val controlEpoch: Long,
        val trigger: ConsoleSafetyTrigger,
    )

    private class FixedClock : ConsoleMonotonicClock {
        override fun nowNanos(): Long = TimeUnit.SECONDS.toNanos(1L)
    }

    private class RecordingScheduler : ConsoleDeadlineScheduler {
        override fun scheduleAt(
            deadlineNanos: Long,
            task: () -> Unit,
        ): ConsoleScheduledTask {
            assertTrue(deadlineNanos > TimeUnit.SECONDS.toNanos(1L))
            assertNotNull(task)
            return ConsoleScheduledTask { }
        }
    }

    private companion object {
        const val TEST_BROWSER_ORIGIN = "http://127.0.0.1:0"
        const val TEST_EPOCH_MILLIS = 1_800_000_000_000L
    }
}
