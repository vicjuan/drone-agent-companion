package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.AuthenticationPresentation
import com.durendal.droneagent.companion.console.protocol.CapabilityEvidenceStatus
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotRow
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.HealthPayload
import com.durendal.droneagent.companion.console.protocol.HealthStatus
import com.durendal.droneagent.companion.console.protocol.LeaseAcquirePayload
import com.durendal.droneagent.companion.console.protocol.LeaseState
import com.durendal.droneagent.companion.console.protocol.LeaseStatePayload
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorPayload
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload
import com.durendal.droneagent.companion.console.protocol.ServerHelloPayload
import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.protocol.TelemetryPayload
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleActuationReadinessSnapshot
import com.durendal.droneagent.companion.console.server.ConsoleAuditEvent
import com.durendal.droneagent.companion.console.server.ConsoleAuditKind
import com.durendal.droneagent.companion.console.server.ConsoleAuditSink
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleEventSink
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import com.durendal.droneagent.companion.console.server.ConsoleScheduledTask
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.transport.ConsoleClientSessionHandler
import com.durendal.droneagent.companion.console.server.transport.ConsoleCoreProtocolAdapter
import com.durendal.droneagent.companion.console.server.transport.ConsoleFrameSink
import com.durendal.droneagent.companion.console.server.transport.ConsoleProtocolEndpoint
import com.durendal.droneagent.companion.console.server.transport.ConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.server.transport.ProtocolConsoleSocketController
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedConsoleAdapterFactoryTest {
    @Test
    fun `missing or failed authentication never opens core or drains pre-auth server output`() {
        listOf(
            null to ProtocolErrorCode.AUTHENTICATION_REQUIRED,
            wrongToken() to ProtocolErrorCode.AUTHENTICATION_FAILED,
        ).forEachIndexed { index, (presentedToken, expectedCode) ->
            val fixture = FactoryFixture()
            val sessionId = "session-failed-$index"
            fixture.controller.onOpen(sessionId, fixture.frames)
            assertTrue(fixture.controller.emit(null, healthyPayload()))
            assertFalse(fixture.controller.emit(sessionId, healthyPayload()))
            assertTrue(fixture.frames.frames.isEmpty())

            fixture.controller.onText(
                sessionId,
                fixture.encodeHello("hello-failed-$index", presentedToken),
            )

            val error = fixture.frames.decoded().single().payload as ProtocolErrorPayload
            assertEquals(expectedCode, error.code)
            assertEquals(0, fixture.coreProviderCalls.get())
            assertEquals(0, fixture.snapshots.totalReads())
            assertTrue(fixture.coreAudit.events.isEmpty())
            val frameCount = fixture.frames.frames.size
            assertTrue(fixture.controller.emit(null, healthyPayload()))
            assertEquals(frameCount, fixture.frames.frames.size)
        }
    }

    @Test
    fun `valid factory token emits required server hello first then admits operator lease`() {
        val fixture = FactoryFixture()
        assertFalse(fixture.endpoint is ConsoleCoreProtocolAdapter)
        fixture.controller.onOpen("session-1", fixture.frames)
        assertTrue(fixture.controller.emit(null, healthyPayload()))
        assertTrue(fixture.frames.frames.isEmpty())

        fixture.controller.onText(
            "session-1",
            fixture.encodeHello("hello-1", fixture.token),
        )

        val decoded = fixture.frames.decoded()
        val serverHello = decoded.first().payload as ServerHelloPayload
        assertTrue(serverHello.authenticationRequired)
        assertEquals(listOf("bearer"), serverHello.acceptedAuthenticationSchemes)
        assertEquals(1, fixture.snapshots.runtimeReads)
        assertEquals(1, fixture.snapshots.capabilityReads)
        assertEquals(1, fixture.snapshots.healthReads)
        assertEquals(1, fixture.snapshots.telemetryReads)
        assertEquals(ConsoleAuditKind.AUTHENTICATION_SUCCEEDED, fixture.authAudit.events.single().kind)
        assertFalse(fixture.authAudit.events.single().toString().contains(fixture.token))

        fixture.controller.onText(
            "session-1",
            fixture.codec.encodeClient(
                ConsoleClientMessage("lease-1", LeaseAcquirePayload(5_000)),
            ),
        )

        assertTrue(fixture.coreAudit.events.any { it.kind == ConsoleAuditKind.LEASE_ACQUIRED })
        assertTrue(
            fixture.frames.decoded().any { message ->
                (message.payload as? LeaseStatePayload)?.state == LeaseState.HELD
            },
        )

        assertTrue(
            fixture.endpoint.publishTelemetry(
                TelemetryPayload(
                    sequence = 1L,
                    batteryPercent = 80,
                    latitude = null,
                    longitude = null,
                    altitudeM = null,
                    flightState = FlightState.GROUNDED,
                    gimbalPitchDeg = null,
                    cameraRecording = null,
                ),
            ),
        )
        assertTrue(
            fixture.frames.decoded().any { message ->
                (message.payload as? TelemetryPayload)?.sequence == 1L
            },
        )
    }

    @Test
    fun `broadcast queued while failed authentication audit is handshaking never reaches sink`() {
        val auditEntered = CountDownLatch(1)
        val releaseAudit = CountDownLatch(1)
        val blockingAudit =
            ConsoleAuditSink {
                auditEntered.countDown()
                check(releaseAudit.await(2, TimeUnit.SECONDS))
            }
        val fixture = FactoryFixture(authAuditOverride = blockingAudit)
        fixture.controller.onOpen("session-handshaking", fixture.frames)
        val failure = AtomicReference<Throwable?>()
        val authThread =
            Thread {
                try {
                    fixture.controller.onText(
                        "session-handshaking",
                        fixture.encodeHello("hello-failed", wrongToken()),
                    )
                } catch (caught: Throwable) {
                    failure.set(caught)
                }
            }
        authThread.start()
        assertTrue(auditEntered.await(1, TimeUnit.SECONDS))

        assertTrue(fixture.controller.emit(null, healthyPayload()))
        assertTrue(fixture.frames.frames.isEmpty())
        releaseAudit.countDown()
        authThread.join(2_000L)

        assertFalse(authThread.isAlive)
        assertEquals(null, failure.get())
        val decoded = fixture.frames.decoded()
        assertEquals(1, decoded.size)
        assertEquals(
            ProtocolErrorCode.AUTHENTICATION_FAILED,
            (decoded.single().payload as ProtocolErrorPayload).code,
        )
        assertEquals(0, fixture.coreProviderCalls.get())
        assertEquals(0, fixture.snapshots.totalReads())
    }

    @Test
    fun `broadcast racing successful authentication follows required server hello`() {
        val auditEntered = CountDownLatch(1)
        val releaseAudit = CountDownLatch(1)
        val blockingAudit =
            ConsoleAuditSink {
                auditEntered.countDown()
                check(releaseAudit.await(2, TimeUnit.SECONDS))
            }
        val fixture = FactoryFixture(authAuditOverride = blockingAudit)
        fixture.controller.onOpen("session-handshaking", fixture.frames)
        val failure = AtomicReference<Throwable?>()
        val authThread =
            Thread {
                try {
                    fixture.controller.onText(
                        "session-handshaking",
                        fixture.encodeHello("hello-success", fixture.token),
                    )
                } catch (caught: Throwable) {
                    failure.set(caught)
                }
            }
        authThread.start()
        assertTrue(auditEntered.await(1, TimeUnit.SECONDS))

        assertTrue(fixture.controller.emit(null, healthyPayload()))
        assertTrue(fixture.frames.frames.isEmpty())
        releaseAudit.countDown()
        authThread.join(2_000L)

        assertFalse(authThread.isAlive)
        assertEquals(null, failure.get())
        val first = fixture.frames.decoded().first().payload as ServerHelloPayload
        assertTrue(first.authenticationRequired)
        assertEquals(listOf("bearer"), first.acceptedAuthenticationSchemes)
    }

    @Test
    fun `public raw adapter constructor has no security metadata knob`() {
        val constructors = ConsoleCoreProtocolAdapter::class.java.constructors.toList()
        assertEquals(1, constructors.size)
        val constructor = constructors.single()
        assertEquals(4, constructor.parameterCount)
        assertFalse(
            constructor.parameterTypes.any { type ->
                type.name.contains("ConsoleHandshakeSecurity") ||
                    type.name.contains("AuthenticatingConsoleClientSessionHandler") ||
                    type.name.contains("DefaultConstructorMarker")
            },
        )
        val factory =
            ConsoleCoreProtocolAdapter.Companion::class.java.methods.single {
                it.name == "authenticatedBearer"
            }
        assertEquals(ConsoleProtocolEndpoint::class.java, factory.returnType)
        assertTrue(ConsoleClientSessionHandler::class.java.isAssignableFrom(factory.returnType))
        assertTrue(ConsoleEventSink::class.java.isAssignableFrom(factory.returnType))
    }

    private class FactoryFixture(
        authAuditOverride: ConsoleAuditSink? = null,
    ) {
        val codec = ConsoleProtocolCodec()
        val token = canonicalToken(0)
        val snapshots = CountingSnapshots()
        val frames = RecordingFrameSink(codec)
        val authAudit = RecordingAuditSink()
        val coreAudit = RecordingAuditSink()
        val coreProviderCalls = AtomicInteger()
        private val endpointReference = AtomicReference<ConsoleProtocolEndpoint>()
        private val clock = FixedClock()
        private val core =
            ConsoleServerCore(
                config = ConsoleServerCoreConfig(),
                admission =
                    ConsoleCommandAdmission(
                        agentId = "agent-1",
                        streamId = "stream-1",
                        epochClock = clock,
                        authorityDecisionId = { "authority-1" },
                    ),
                executor = ImmediateExecutor,
                monotonicClock = clock,
                epochClock = clock,
                scheduler =
                    ConsoleDeadlineScheduler { _, _ ->
                        ConsoleScheduledTask {}
                    },
                auditSink = coreAudit,
                eventSink =
                    ConsoleEventSink { targetSessionId, event ->
                        checkNotNull(endpointReference.get()) {
                            "authenticated endpoint not attached"
                        }.emit(targetSessionId, event)
                    },
                initialActuationReadiness = ConsoleActuationReadinessSnapshot.MOCK_READY,
                leaseIdFactory = { "lease-server-1" },
            )
        lateinit var endpoint: ConsoleProtocolEndpoint
        lateinit var controller: ProtocolConsoleSocketController

        init {
            endpoint =
                ConsoleCoreProtocolAdapter.authenticatedBearer(
                    coreProvider = {
                        coreProviderCalls.incrementAndGet()
                        core
                    },
                    snapshots = snapshots,
                    serverVersion = "server-test",
                    emitPayload = { target, payload -> controller.emit(target, payload) },
                    verifier =
                        ConsoleBearerTokenVerifier(
                            listOf(
                                ConsoleBearerCredentialDigest(
                                    subjectId = "operator-1",
                                    role = ConsoleSessionRole.OPERATOR,
                                    tokenDigestSha256 =
                                        ConsoleBearerTokenVerifier.digestTokenForProvisioning(token),
                                ),
                            ),
                        ),
                    auditSink = authAuditOverride ?: authAudit,
                    epochClock = clock,
                    monotonicClock = clock,
                )
            endpointReference.set(endpoint)
            val serverSequence = AtomicInteger()
            controller =
                ProtocolConsoleSocketController(
                    handler = endpoint,
                    codec = codec,
                    messageIdFactory = { "server-${serverSequence.incrementAndGet()}" },
                )
        }

        fun encodeHello(
            messageId: String,
            presentedToken: String?,
        ): String =
            codec.encodeClient(
                ConsoleClientMessage(
                    messageId = messageId,
                    payload =
                        ClientHelloPayload(
                            clientName = "web-console",
                            clientVersion = "test",
                            supportedProtocolVersions = listOf("1.0"),
                            authentication =
                                presentedToken?.let { AuthenticationPresentation("bearer", it) },
                        ),
                ),
            )
    }

    private class CountingSnapshots : ConsoleSnapshotProvider {
        var runtimeReads = 0
        var capabilityReads = 0
        var healthReads = 0
        var telemetryReads = 0

        override fun runtimeState(): RuntimeStatePayload {
            runtimeReads += 1
            return RuntimeStatePayload(
                adapter = AdapterKind.MOCK,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                actuationLock = ActuationLockState.UNLOCKED,
                operatingProfile = OperatingProfile.LOCALHOST_DEVELOPMENT,
            )
        }

        override fun capabilitySnapshot(): CapabilitySnapshotPayload {
            capabilityReads += 1
            return CapabilitySnapshotPayload(
                matrixId = "mini4pro-rcn3-g520-android",
                schemaVersion = 1,
                lastUpdated = "2026-08-16",
                sourceDigestSha256 = "a".repeat(64),
                rows =
                    listOf(
                        CapabilitySnapshotRow(
                            id = "battery",
                            status = CapabilityEvidenceStatus.UNKNOWN,
                            assessment = "No target-stack evidence.",
                        ),
                    ),
            )
        }

        override fun health(): HealthPayload {
            healthReads += 1
            return healthyPayload()
        }

        override fun latestTelemetry(): TelemetryPayload? {
            telemetryReads += 1
            return null
        }

        fun totalReads(): Int = runtimeReads + capabilityReads + healthReads + telemetryReads
    }

    private class RecordingFrameSink(
        private val codec: ConsoleProtocolCodec,
    ) : ConsoleFrameSink {
        val frames = CopyOnWriteArrayList<String>()
        val closeReasons = CopyOnWriteArrayList<String>()

        override fun offer(text: String): Boolean {
            frames += text
            return true
        }

        override fun close(reason: String) {
            closeReasons += reason
        }

        fun decoded() = frames.map(codec::decodeServer)
    }

    private class RecordingAuditSink : ConsoleAuditSink {
        val events = CopyOnWriteArrayList<ConsoleAuditEvent>()

        override fun record(event: ConsoleAuditEvent) {
            events += event
        }
    }

    private class FixedClock : ConsoleEpochClock, ConsoleMonotonicClock {
        override fun nowMillis(): Long = 1_800_000_000_000L
        override fun nowNanos(): Long = 1_000_000_000L
    }

    private object ImmediateExecutor : ConsoleCommandExecutor {
        override fun executeDiscrete(
            command: AdmittedDiscreteCommand,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            callback(ConsoleExecutionResult(true))
        }

        override fun submitControl(
            frame: AdmittedControlFrame,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            callback(ConsoleExecutionResult(true))
        }

        override fun neutralize(
            leaseId: String,
            controlEpoch: Long,
            trigger: ConsoleSafetyTrigger,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            callback(ConsoleExecutionResult(true))
        }
    }

    private companion object {
        fun canonicalToken(offset: Int): String =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32) { index -> (index + offset).toByte() })

        fun wrongToken(): String = canonicalToken(1)

        fun healthyPayload(): HealthPayload =
            HealthPayload(HealthStatus.HEALTHY, uptimeMs = 0L, detail = null)
    }
}
