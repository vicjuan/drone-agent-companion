package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.actuation.ControlAuthorityOwner
import com.durendal.droneagent.actuation.ControlProducer
import com.durendal.droneagent.actuation.FlightControlCommandEvidence
import com.durendal.droneagent.actuation.FlightControlCommandEvidenceListener
import com.durendal.droneagent.actuation.FlightControlExecutionTarget
import com.durendal.droneagent.actuation.NeutralizationReason
import com.durendal.droneagent.companion.capability.CapabilityMatrixLoader
import com.durendal.droneagent.companion.capability.G520CapabilityStatus
import com.durendal.droneagent.companion.console.mock.MockConsoleCommandExecutor
import com.durendal.droneagent.companion.console.mock.MockConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.mock.ObservableMockReturnToHomePort
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.CapabilityEvidenceStatus
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.CommandAckPayload
import com.durendal.droneagent.companion.console.protocol.CommandDecision
import com.durendal.droneagent.companion.console.protocol.CommandResultPayload
import com.durendal.droneagent.companion.console.protocol.CommandResultStatus
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleClientPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleIntentDigest
import com.durendal.droneagent.companion.console.protocol.ConsoleMessageType
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleServerMessage
import com.durendal.droneagent.companion.console.protocol.ControlAckPayload
import com.durendal.droneagent.companion.console.protocol.ControlAckStatus
import com.durendal.droneagent.companion.console.protocol.ControlFramePayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralPayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralReason
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.protocol.LeaseAcquirePayload
import com.durendal.droneagent.companion.console.protocol.LeaseState
import com.durendal.droneagent.companion.console.protocol.LeaseStatePayload
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload
import com.durendal.droneagent.companion.console.protocol.SafetyEventPayload
import com.durendal.droneagent.companion.console.protocol.SafetyOutcome
import com.durendal.droneagent.companion.console.protocol.SafetyTrigger
import com.durendal.droneagent.companion.console.protocol.TelemetryPayload
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleScheduledTask
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.FileConsoleAuditSink
import com.durendal.droneagent.companion.console.server.transport.ConsoleCoreProtocolAdapter
import com.durendal.droneagent.companion.console.server.transport.ConsoleRoutes
import com.durendal.droneagent.companion.console.server.transport.ConsoleServerConfig
import com.durendal.droneagent.companion.console.server.transport.ProtocolConsoleSocketController
import com.durendal.droneagent.companion.console.server.toActuationReadiness
import com.durendal.droneagent.companion.console.server.transport.installConsoleApplication
import com.durendal.droneagent.core.control.BodyFrameVelocityCommand
import com.durendal.droneagent.core.telemetry.TelemetryListener
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Ktor WebSocket -> strict protocol -> safety core -> adapter-mock vertical slice. */
class ConsoleRunnerEndToEndTest {
    private val codec = ConsoleProtocolCodec()

    private companion object {
        const val TEST_BROWSER_ORIGIN = "http://127.0.0.1:0"
        const val AXIS_DELTA = 1e-12
        val SHA256 = Regex("^[0-9a-f]{64}$")

        val CONTROL_CASES =
            listOf(
                ControlCase(
                    "forward",
                    forward = 0.75,
                    expected = BodyFrameVelocityCommand(0.375, 0.0, 0.0, 0.0),
                ),
                ControlCase(
                    "backward",
                    forward = -0.75,
                    expected = BodyFrameVelocityCommand(-0.375, 0.0, 0.0, 0.0),
                ),
                ControlCase(
                    "ascend",
                    up = 0.75,
                    expected = BodyFrameVelocityCommand(0.0, 0.0, 0.225, 0.0),
                ),
                ControlCase(
                    "descend",
                    up = -0.75,
                    expected = BodyFrameVelocityCommand(0.0, 0.0, -0.225, 0.0),
                ),
                ControlCase(
                    "yaw-left",
                    yaw = -0.75,
                    expected = BodyFrameVelocityCommand(0.0, 0.0, 0.0, -11.25),
                ),
                ControlCase(
                    "yaw-right",
                    yaw = 0.75,
                    expected = BodyFrameVelocityCommand(0.0, 0.0, 0.0, 11.25),
                ),
            )
    }

    @Test
    fun `websocket executes mock actions six-axis control neutral deadman and rejects a second lease holder`() {
        val webRoot = Files.createTempDirectory("console-e2e-web")
        Files.writeString(webRoot.resolve("index.html"), "<html>console e2e</html>")
        val fixture = RuntimeFixture()
        val sixDirectionFrames = mutableListOf<ControlFramePayload>()
        val discreteRequests = mutableListOf<DiscreteCommandRequestPayload>()
        var primarySessionId: String? = null
        var primaryControlLeaseId: String? = null
        try {
            testApplication {
                application {
                    installConsoleApplication(
                        ConsoleServerConfig("127.0.0.1", 0, webRoot),
                        fixture.controller,
                        sessionIdFactory = fixture::nextSessionId,
                    )
                }
                val socketClient = createClient { install(WebSockets) }

                socketClient.webSocket(
                    ConsoleRoutes.WEBSOCKET,
                    request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                ) primary@{
                    sendClient("hello-primary", hello())
                    val initial = receiveUntil { it.type == ConsoleMessageType.LEASE_STATE }
                    val runtime = initial.payload<RuntimeStatePayload>()
                    assertEquals(AdapterKind.MOCK, runtime.adapter)
                    assertTrue(initial.any { it.type == ConsoleMessageType.TELEMETRY })
                    val capability = initial.payload<CapabilitySnapshotPayload>()
                    assertEquals(17, capability.rows.size)
                    assertTrue(capability.rows.all { it.status == CapabilityEvidenceStatus.UNKNOWN })
                    assertTrue(initial.any { it.type == ConsoleMessageType.HEALTH })

                    socketClient.webSocket(
                        ConsoleRoutes.WEBSOCKET,
                        request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
                    ) observer@{
                        sendClient("hello-observer", hello())
                        val observerInitial =
                            receiveUntil { it.type == ConsoleMessageType.LEASE_STATE }
                                .last()
                                .payload as LeaseStatePayload
                        assertEquals(LeaseState.AVAILABLE, observerInitial.state)

                        this@primary.sendClient("lease-primary", LeaseAcquirePayload(5_000))
                        val held =
                            this@primary.receiveUntil {
                                val payload = it.payload as? LeaseStatePayload
                                payload?.requestMessageId == "lease-primary" &&
                                    payload.state == LeaseState.HELD
                            }.last().payload as LeaseStatePayload
                        val leaseId = checkNotNull(held.leaseId)
                        primarySessionId = checkNotNull(held.holderSessionId)
                        primaryControlLeaseId = leaseId
                        val observerHeld =
                            this@observer.receiveUntil {
                                val payload = it.payload as? LeaseStatePayload
                                payload != null &&
                                    payload.requestMessageId == null &&
                                    payload.state == LeaseState.HELD &&
                                    payload.leaseId == leaseId
                            }.last().payload as LeaseStatePayload
                        assertEquals(held.holderSessionId, observerHeld.holderSessionId)

                        sendClient("lease-observer", LeaseAcquirePayload(5_000))
                        val denied =
                            receiveUntil {
                                val payload = it.payload as? LeaseStatePayload
                                payload?.requestMessageId == "lease-observer"
                            }.last().payload as LeaseStatePayload
                        assertEquals(LeaseState.DENIED, denied.state)
                        assertEquals("lease_held", denied.reason)

                        val takeoffRequest =
                            DiscreteCommandRequestPayload(
                                commandId = "takeoff-1",
                                leaseId = leaseId,
                                action = DiscreteCommandAction.TAKEOFF,
                                ttlMs = 2_000,
                            )
                        discreteRequests += takeoffRequest
                        assertActionSucceeds(
                            this@primary,
                            takeoffRequest,
                        )

                        CONTROL_CASES.forEachIndexed { index, controlCase ->
                            val controlSequence = index.toLong() * 2L + 1L
                            val neutralSequence = controlSequence + 1L
                            val frame = controlCase.toPayload(leaseId, controlSequence)
                            sixDirectionFrames += frame
                            assertDirectionAndOperatorRelease(
                                this@primary,
                                fixture,
                                controlCase,
                                frame,
                                neutralSequence,
                            )
                        }

                        val deadManSequence = CONTROL_CASES.size.toLong() * 2L + 1L
                        this@primary.sendClient(
                            "control-deadman-$deadManSequence",
                            ControlFramePayload(
                                leaseId = leaseId,
                                inputSequence = deadManSequence,
                                ttlMs = 250,
                                forward = 0.75,
                                right = 0.0,
                                up = 0.0,
                                yaw = 0.0,
                            ),
                        )
                        val control =
                            this@primary.receiveUntil {
                                (it.payload as? ControlAckPayload)?.inputSequence == deadManSequence
                            }.last().payload as ControlAckPayload
                        assertEquals(ControlAckStatus.APPLIED, control.status)

                        fixture.clock.advanceMillis(101L)
                        fixture.scheduler.runDue()
                        val deadMan =
                            this@primary.receiveUntil {
                                (it.payload as? SafetyEventPayload)?.trigger ==
                                    SafetyTrigger.CONTROL_TTL_EXPIRED
                            }.last().payload as SafetyEventPayload
                        assertEquals(SafetyOutcome.SUCCEEDED, deadMan.outcome)

                        val rthRequest =
                            DiscreteCommandRequestPayload(
                                commandId = "rth-1",
                                leaseId = leaseId,
                                action = DiscreteCommandAction.RETURN_TO_HOME,
                                ttlMs = 2_000,
                            )
                        discreteRequests += rthRequest
                        val rthMessages = assertActionSucceeds(this@primary, rthRequest)
                        val rthResult =
                            rthMessages.mapNotNull { it.payload as? CommandResultPayload }
                                .single { it.commandId == "rth-1" }
                        assertEquals(
                            "Companion-owned RTH simulation entered RETURNING_HOME; " +
                                "vendor adapter-mock has no RTH action port.",
                            rthResult.detail,
                        )
                        val rthTelemetry =
                            rthMessages.mapNotNull { it.payload as? TelemetryPayload }
                                .firstOrNull { it.flightState == FlightState.RETURNING_HOME }
                                ?: this@primary.receiveUntil {
                                    (it.payload as? TelemetryPayload)?.flightState ==
                                        FlightState.RETURNING_HOME
                                }.last().payload as TelemetryPayload
                        assertEquals(FlightState.RETURNING_HOME, rthTelemetry.flightState)
                        assertTrue(
                            rthMessages.any {
                                (it.payload as? LeaseStatePayload)?.state == LeaseState.RELEASED
                            },
                        )
                        val observerReleased =
                            this@observer.receiveUntil {
                                val payload = it.payload as? LeaseStatePayload
                                payload != null &&
                                    payload.requestMessageId == null &&
                                    payload.state == LeaseState.RELEASED &&
                                    payload.leaseId == leaseId
                            }.last().payload as LeaseStatePayload
                        assertEquals(null, observerReleased.holderSessionId)
                    }

                    sendClient("lease-after-rth", LeaseAcquirePayload(5_000))
                    val landingLease =
                        receiveUntil {
                            val payload = it.payload as? LeaseStatePayload
                            payload?.requestMessageId == "lease-after-rth" &&
                                payload.state == LeaseState.HELD
                        }.last().payload as LeaseStatePayload
                    val landingRequest =
                        DiscreteCommandRequestPayload(
                            commandId = "landing-1",
                            leaseId = checkNotNull(landingLease.leaseId),
                            action = DiscreteCommandAction.LANDING,
                            ttlMs = 2_000,
                        )
                    discreteRequests += landingRequest
                    assertActionSucceeds(
                        this,
                        landingRequest,
                    )
                }
            }

            assertStructuredAudit(
                fixture.auditPath,
                checkNotNull(primarySessionId),
                checkNotNull(primaryControlLeaseId),
                sixDirectionFrames,
                discreteRequests,
            )

            val canonical = CapabilityMatrixLoader.loadBundled()
            assertEquals(17, canonical.rows.size)
            assertTrue(canonical.rows.all { it.status == G520CapabilityStatus.UNKNOWN })
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertDirectionAndOperatorRelease(
        session: DefaultClientWebSocketSession,
        fixture: RuntimeFixture,
        controlCase: ControlCase,
        frame: ControlFramePayload,
        neutralSequence: Long,
    ) {
        session.sendClient(
            "control-${controlCase.name}-${frame.inputSequence}",
            frame,
        )
        val controlMessages =
            session.receiveUntil {
                (it.payload as? ControlAckPayload)?.inputSequence == frame.inputSequence
            }
        val controlAck =
            controlMessages.mapNotNull { it.payload as? ControlAckPayload }
                .single { it.inputSequence == frame.inputSequence }
        val evidenceBeforeNeutral = fixture.commandEvidence().size
        session.sendClient(
            "neutral-${controlCase.name}-$neutralSequence",
            ControlNeutralPayload(
                leaseId = frame.leaseId,
                inputSequence = neutralSequence,
                reason = ControlNeutralReason.OPERATOR_RELEASE,
            ),
        )
        val neutralMessages =
            session.receiveUntil {
                val safety = it.payload as? SafetyEventPayload
                safety?.trigger == SafetyTrigger.CLIENT_REQUEST &&
                    safety.lastInputSequence == neutralSequence
            }
        val neutralAck =
            neutralMessages.mapNotNull { it.payload as? ControlAckPayload }
                .single { it.inputSequence == neutralSequence }
        val safety =
            neutralMessages.mapNotNull { it.payload as? SafetyEventPayload }
                .single {
                    it.trigger == SafetyTrigger.CLIENT_REQUEST &&
                    it.lastInputSequence == neutralSequence
                }

        assertEquals("${controlCase.name} control ack", ControlAckStatus.APPLIED, controlAck.status)
        assertEquals("${controlCase.name} neutral ack", ControlAckStatus.APPLIED, neutralAck.status)
        assertEquals(SafetyOutcome.SUCCEEDED, safety.outcome)

        val commandEvidence =
            fixture.commandEvidence().single { it.inputSequence == frame.inputSequence }
        assertEquals(FlightControlExecutionTarget.SIMULATION, commandEvidence.executionTarget)
        assertEquals(ControlProducer.MANUAL, commandEvidence.producer)
        assertEquals(ControlAuthorityOwner.LOCAL_APP, commandEvidence.authorityOwner)
        assertEquals(null, commandEvidence.neutralizationReason)
        assertTrue("${controlCase.name} must not clip the provisional mapping", commandEvidence.clippedAxes.isEmpty())
        assertCommandMapping(controlCase.name, controlCase.expected, commandEvidence.command)

        val neutralEvidence = fixture.commandEvidence().drop(evidenceBeforeNeutral)
        assertTrue("${controlCase.name} operator release must reach adapter neutral", neutralEvidence.isNotEmpty())
        assertTrue(
            "${controlCase.name} operator release emitted non-neutral evidence",
            neutralEvidence.all {
                it.executionTarget == FlightControlExecutionTarget.SIMULATION &&
                    it.producer == ControlProducer.NONE &&
                    it.authorityOwner == ControlAuthorityOwner.LOCAL_APP &&
                    it.command.forwardMps == 0.0 &&
                    it.command.rightMps == 0.0 &&
                    it.command.upMps == 0.0 &&
                    it.command.yawRateDegreesPerSecond == 0.0 &&
                    it.neutralizationReason == NeutralizationReason.PRODUCER_RELEASED
            },
        )
    }

    private fun assertCommandMapping(
        name: String,
        expected: BodyFrameVelocityCommand,
        actual: BodyFrameVelocityCommand,
    ) {
        assertEquals("$name forward", expected.forwardMps, actual.forwardMps, AXIS_DELTA)
        assertEquals("$name right", expected.rightMps, actual.rightMps, AXIS_DELTA)
        assertEquals("$name up", expected.upMps, actual.upMps, AXIS_DELTA)
        assertEquals(
            "$name yaw",
            expected.yawRateDegreesPerSecond,
            actual.yawRateDegreesPerSecond,
            AXIS_DELTA,
        )
    }

    private fun assertStructuredAudit(
        auditPath: Path,
        primarySessionId: String,
        primaryControlLeaseId: String,
        sixDirectionFrames: List<ControlFramePayload>,
        discreteRequests: List<DiscreteCommandRequestPayload>,
    ) {
        val audit =
            Files.readAllLines(auditPath).filter(String::isNotBlank).map { line ->
                val objectValue = Json.parseToJsonElement(line).jsonObject
                assertEquals(1, objectValue.getValue("schemaVersion").jsonPrimitive.int)
                AuditRecord(
                    kind = objectValue.requiredString("kind"),
                    sessionId = objectValue.optionalString("sessionId"),
                    leaseId = objectValue.optionalString("leaseId"),
                    subjectId = objectValue.optionalString("subjectId"),
                    intentDigestSha256 = objectValue.optionalString("intentDigestSha256"),
                    authorityDecisionId = objectValue.optionalString("authorityDecisionId"),
                    outcome = objectValue.requiredString("outcome"),
                    reason = objectValue.optionalString("reason"),
                    clientRequestReason = objectValue.optionalString("clientRequestReason"),
                )
            }

        assertEquals(CONTROL_CASES.size, sixDirectionFrames.size)
        val authorityDecisionIds = mutableSetOf<String>()
        assertTrue(sixDirectionFrames.all { it.leaseId == primaryControlLeaseId })
        sixDirectionFrames.forEachIndexed { index, frame ->
            val controlSubject = frame.inputSequence.toString()
            val admitted =
                audit.single {
                    it.kind == "control_admitted" &&
                        it.subjectId == controlSubject &&
                        it.sessionId == primarySessionId &&
                        it.leaseId == primaryControlLeaseId
                }
            val completed =
                audit.single {
                    it.kind == "control_completed" &&
                        it.subjectId == controlSubject &&
                        it.sessionId == primarySessionId &&
                        it.leaseId == primaryControlLeaseId
                }
            val authorityDecisionId = requireNotNull(admitted.authorityDecisionId)
            assertTrue(authorityDecisionId.isNotBlank())
            assertTrue("authority decision must be unique per control intent", authorityDecisionIds.add(authorityDecisionId))
            assertEquals(authorityDecisionId, completed.authorityDecisionId)
            val expectedDigest = ConsoleIntentDigest.sha256(frame)
            assertTrue(expectedDigest.matches(SHA256))
            assertEquals(expectedDigest, admitted.intentDigestSha256)
            assertEquals(expectedDigest, completed.intentDigestSha256)
            assertEquals(admitted.sessionId, completed.sessionId)
            assertEquals(admitted.leaseId, completed.leaseId)
            assertEquals("admission_passed", admitted.outcome)
            assertEquals("applied", completed.outcome)

            val neutralSubject = (index.toLong() * 2L + 2L).toString()
            val requested =
                audit.single {
                    it.kind == "safety_neutral_requested" &&
                        it.subjectId == neutralSubject &&
                        it.sessionId == primarySessionId &&
                        it.leaseId == primaryControlLeaseId &&
                        it.clientRequestReason == "operator_release"
                }
            val neutralCompleted =
                audit.single {
                    it.kind == "safety_neutral_completed" &&
                        it.subjectId == neutralSubject &&
                        it.sessionId == primarySessionId &&
                        it.leaseId == primaryControlLeaseId &&
                        it.clientRequestReason == "operator_release"
                }
            assertEquals("requested", requested.outcome)
            assertEquals("succeeded", neutralCompleted.outcome)
            assertEquals("client_request", requested.reason)
            assertEquals("client_request", neutralCompleted.reason)
            assertEquals(requested.sessionId, neutralCompleted.sessionId)
            assertEquals(requested.leaseId, neutralCompleted.leaseId)
        }

        assertEquals(
            setOf("takeoff-1", "rth-1", "landing-1"),
            discreteRequests.map { it.commandId }.toSet(),
        )
        assertEquals(3, discreteRequests.size)
        discreteRequests.forEach { request ->
            val admitted =
                audit.single {
                    it.kind == "command_admitted" &&
                        it.subjectId == request.commandId &&
                        it.sessionId == primarySessionId &&
                        it.leaseId == request.leaseId
                }
            val completed =
                audit.single {
                    it.kind == "command_completed" &&
                        it.subjectId == request.commandId &&
                        it.sessionId == primarySessionId &&
                        it.leaseId == request.leaseId
                }
            val authorityDecisionId = requireNotNull(admitted.authorityDecisionId)
            assertTrue(authorityDecisionId.isNotBlank())
            assertTrue(
                "authority decision must be unique per discrete intent",
                authorityDecisionIds.add(authorityDecisionId),
            )
            assertEquals(authorityDecisionId, completed.authorityDecisionId)
            val expectedDigest = ConsoleIntentDigest.sha256(request)
            assertTrue(expectedDigest.matches(SHA256))
            assertEquals(expectedDigest, admitted.intentDigestSha256)
            assertEquals(expectedDigest, completed.intentDigestSha256)
            assertEquals("admission_passed", admitted.outcome)
            assertEquals("succeeded", completed.outcome)
        }
        assertEquals(sixDirectionFrames.size + discreteRequests.size, authorityDecisionIds.size)

        assertTrue(audit.any { it.kind == "lease_refused" })
        assertTrue(
            audit.any {
                it.kind == "safety_neutral_completed" &&
                    it.reason == "control_ttl_expired"
            },
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        requireNotNull(optionalString(name)) { "Missing required audit field: $name" }

    private fun JsonObject.optionalString(name: String): String? =
        getValue(name).jsonPrimitive.contentOrNull

    private data class ControlCase(
        val name: String,
        val forward: Double = 0.0,
        val right: Double = 0.0,
        val up: Double = 0.0,
        val yaw: Double = 0.0,
        val expected: BodyFrameVelocityCommand,
    ) {
        fun toPayload(
            leaseId: String,
            inputSequence: Long,
        ): ControlFramePayload =
            ControlFramePayload(
                leaseId = leaseId,
                inputSequence = inputSequence,
                ttlMs = 250,
                forward = forward,
                right = right,
                up = up,
                yaw = yaw,
            )
    }

    private data class AuditRecord(
        val kind: String,
        val sessionId: String?,
        val leaseId: String?,
        val subjectId: String?,
        val intentDigestSha256: String?,
        val authorityDecisionId: String?,
        val outcome: String,
        val reason: String?,
        val clientRequestReason: String?,
    )

    private suspend fun assertActionSucceeds(
        session: DefaultClientWebSocketSession,
        request: DiscreteCommandRequestPayload,
    ): List<ConsoleServerMessage> {
        session.sendClient(
            "request-${request.commandId}",
            request,
        )
        val messages =
            session.receiveUntil {
                (it.payload as? CommandResultPayload)?.commandId == request.commandId
            }
        val ack =
            messages.mapNotNull { it.payload as? CommandAckPayload }
                .single { it.commandId == request.commandId }
        val result =
            messages.mapNotNull { it.payload as? CommandResultPayload }
                .single { it.commandId == request.commandId }
        assertEquals(CommandDecision.ACCEPTED, ack.decision)
        assertEquals(CommandResultStatus.SUCCEEDED, result.status)
        assertTrue(
            messages.any {
                val safety = it.payload as? SafetyEventPayload
                safety?.outcome == SafetyOutcome.SUCCEEDED
            },
        )
        return messages
    }

    private suspend fun DefaultClientWebSocketSession.sendClient(
        messageId: String,
        payload: ConsoleClientPayload,
    ) {
        send(Frame.Text(codec.encodeClient(ConsoleClientMessage(messageId, payload))))
    }

    private suspend fun DefaultClientWebSocketSession.receiveUntil(
        complete: (ConsoleServerMessage) -> Boolean,
    ): List<ConsoleServerMessage> =
        withTimeout(3_000L) {
            val messages = mutableListOf<ConsoleServerMessage>()
            while (true) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                val message = codec.decodeServer(frame.readText())
                messages += message
                if (complete(message)) return@withTimeout messages
            }
            @Suppress("UNREACHABLE_CODE")
            messages
        }

    private inline fun <reified T : Any> List<ConsoleServerMessage>.payload(): T =
        mapNotNull { it.payload as? T }.single()

    private fun hello(): ClientHelloPayload =
        ClientHelloPayload(
            clientName = "console-e2e",
            clientVersion = "0.1.0",
            supportedProtocolVersions = listOf("1.0"),
            authentication = null,
        )

    private class RuntimeFixture : AutoCloseable {
        val clock = TestClock()
        val scheduler = ManualScheduler(clock)
        val auditPath = Files.createTempDirectory("console-e2e-audit").resolve("events.jsonl")
        private val audit = FileConsoleAuditSink(auditPath)
        private val agent = MockDroneAgent(telemetryPeriodMs = 20L)
        private val commandEvidence = CopyOnWriteArrayList<FlightControlCommandEvidence>()
        private val commandEvidenceListener =
            FlightControlCommandEvidenceListener { evidence -> commandEvidence += evidence }
        private val returnToHome = ObservableMockReturnToHomePort()
        private val executor = MockConsoleCommandExecutor(agent, clock, returnToHome)
        private val coreReference = AtomicReference<ConsoleServerCore>()
        private val controllerReference = AtomicReference<ProtocolConsoleSocketController>()
        private val sessionSequence = AtomicInteger()
        private val firstTelemetry = CountDownLatch(1)
        private val snapshots =
            MockConsoleSnapshotProvider(
                agent,
                returnToHome,
                monotonicClockNanos = clock::nowNanos,
            )
        private val protocol =
            ConsoleCoreProtocolAdapter(
                coreProvider = { checkNotNull(coreReference.get()) },
                snapshots = snapshots,
                serverVersion = "0.1.0-test",
                emitPayload = { target, payload ->
                    controllerReference.get()?.emit(target, payload) ?: false
                },
            )
        private val core =
            ConsoleServerCore(
                config = ConsoleServerCoreConfig(deadManTimeoutMillis = 100L),
                admission =
                    ConsoleCommandAdmission(
                        agentId = "mock",
                        streamId = "mock-main",
                        epochClock = ConsoleEpochClock { 1_800_000_000_000L },
                    ),
                executor = executor,
                monotonicClock = clock,
                epochClock = ConsoleEpochClock { 1_800_000_000_000L },
                scheduler = scheduler,
                auditSink = audit,
                eventSink = protocol,
            )
        val controller = ProtocolConsoleSocketController(protocol)
        private val telemetryListener =
            TelemetryListener { telemetry ->
                protocol.publishTelemetry(snapshots.mapTelemetry(telemetry))
                firstTelemetry.countDown()
            }

        init {
            coreReference.set(core)
            controllerReference.set(controller)
            agent.actuation.addCommandEvidenceListener(commandEvidenceListener)
            agent.telemetry.addListener(telemetryListener)
            agent.connection.connect()
            core.updateActuationReadiness(snapshots.runtimeState().toActuationReadiness())
            agent.telemetry.start()
            check(firstTelemetry.await(1L, TimeUnit.SECONDS)) { "mock telemetry did not start" }
        }

        fun nextSessionId(): String = "session-e2e-${sessionSequence.incrementAndGet()}"

        fun commandEvidence(): List<FlightControlCommandEvidence> = commandEvidence.toList()

        override fun close() {
            core.close()
            executor.close()
            agent.actuation.removeCommandEvidenceListener(commandEvidenceListener)
            agent.telemetry.removeListener(telemetryListener)
            audit.close()
            agent.shutdown()
        }
    }

    private class TestClock : ConsoleMonotonicClock {
        @Volatile private var now = 0L

        override fun nowNanos(): Long = now

        fun advanceMillis(millis: Long) {
            now += TimeUnit.MILLISECONDS.toNanos(millis)
        }
    }

    private class ManualScheduler(
        private val clock: ConsoleMonotonicClock,
    ) : ConsoleDeadlineScheduler {
        private val lock = Any()
        private val entries = mutableListOf<Entry>()

        override fun scheduleAt(deadlineNanos: Long, task: () -> Unit): ConsoleScheduledTask {
            val entry = Entry(deadlineNanos, task)
            synchronized(lock) { entries += entry }
            return ConsoleScheduledTask { synchronized(lock) { entry.cancelled = true } }
        }

        fun runDue() {
            while (true) {
                val entry =
                    synchronized(lock) {
                        entries.firstOrNull {
                            !it.cancelled && !it.executed && it.deadlineNanos <= clock.nowNanos()
                        }?.also { it.executed = true }
                    } ?: return
                entry.task()
            }
        }

        private class Entry(
            val deadlineNanos: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
            var executed: Boolean = false,
        )
    }
}
