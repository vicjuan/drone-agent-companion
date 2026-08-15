package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.mock.MockConsoleCommandExecutor
import com.durendal.droneagent.companion.console.mock.MockConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.mock.ObservableMockReturnToHomePort
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.CommandAckPayload
import com.durendal.droneagent.companion.console.protocol.CommandDecision
import com.durendal.droneagent.companion.console.protocol.CommandResultPayload
import com.durendal.droneagent.companion.console.protocol.CommandResultStatus
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleClientPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleMessageType
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleServerMessage
import com.durendal.droneagent.companion.console.protocol.ControlAckPayload
import com.durendal.droneagent.companion.console.protocol.ControlAckStatus
import com.durendal.droneagent.companion.console.protocol.ControlFramePayload
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import com.durendal.droneagent.companion.console.protocol.LeaseAcquirePayload
import com.durendal.droneagent.companion.console.protocol.LeaseState
import com.durendal.droneagent.companion.console.protocol.LeaseStatePayload
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload
import com.durendal.droneagent.companion.console.protocol.SafetyEventPayload
import com.durendal.droneagent.companion.console.protocol.SafetyOutcome
import com.durendal.droneagent.companion.console.protocol.SafetyTrigger
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleScheduledTask
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.FileConsoleAuditSink
import com.durendal.droneagent.companion.console.server.security.ConsoleExposurePolicy
import com.durendal.droneagent.companion.console.server.transport.ConsoleCoreProtocolAdapter
import com.durendal.droneagent.companion.console.server.transport.ConsoleRoutes
import com.durendal.droneagent.companion.console.server.transport.ConsoleServerConfig
import com.durendal.droneagent.companion.console.server.transport.ProtocolConsoleSocketController
import com.durendal.droneagent.companion.console.server.toActuationReadiness
import com.durendal.droneagent.companion.console.server.transport.installConsoleApplication
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Ktor WebSocket -> strict protocol -> safety core -> adapter-mock vertical slice. */
class ConsoleRunnerEndToEndTest {
    private val codec = ConsoleProtocolCodec()

    private companion object {
        const val TEST_BROWSER_ORIGIN = "http://127.0.0.1:0"
    }

    @Test
    fun `websocket executes mock actions control deadman and rejects a second lease holder`() {
        val webRoot = Files.createTempDirectory("console-e2e-web")
        Files.writeString(webRoot.resolve("index.html"), "<html>console e2e</html>")
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
                ) primary@{
                    sendClient("hello-primary", hello())
                    val initial = receiveUntil { it.type == ConsoleMessageType.LEASE_STATE }
                    val runtime = initial.payload<RuntimeStatePayload>()
                    assertEquals(AdapterKind.MOCK, runtime.adapter)
                    assertTrue(initial.any { it.type == ConsoleMessageType.TELEMETRY })
                    assertTrue(initial.any { it.type == ConsoleMessageType.CAPABILITY_SNAPSHOT })
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

                        assertActionSucceeds(
                            this@primary,
                            "takeoff-1",
                            leaseId,
                            DiscreteCommandAction.TAKEOFF,
                        )

                        this@primary.sendClient(
                            "control-forward-1",
                            ControlFramePayload(
                                leaseId = leaseId,
                                inputSequence = 1L,
                                ttlMs = 250,
                                forward = 0.75,
                                right = 0.0,
                                up = 0.0,
                                yaw = 0.0,
                            ),
                        )
                        val control =
                            this@primary.receiveUntil {
                                (it.payload as? ControlAckPayload)?.inputSequence == 1L
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

                        val rthMessages =
                            assertActionSucceeds(
                                this@primary,
                                "rth-1",
                                leaseId,
                                DiscreteCommandAction.RETURN_TO_HOME,
                            )
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
                    assertActionSucceeds(
                        this,
                        "landing-1",
                        checkNotNull(landingLease.leaseId),
                        DiscreteCommandAction.LANDING,
                    )
                }
            }

            val audit = Files.readString(fixture.auditPath)
            assertTrue(audit.contains("\"kind\":\"command_admitted\""))
            assertTrue(audit.contains("\"kind\":\"command_completed\""))
            assertTrue(audit.contains("\"kind\":\"lease_refused\""))
            assertTrue(audit.contains("\"reason\":\"control_ttl_expired\""))
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertActionSucceeds(
        session: DefaultClientWebSocketSession,
        commandId: String,
        leaseId: String,
        action: DiscreteCommandAction,
    ): List<ConsoleServerMessage> {
        session.sendClient(
            "request-$commandId",
            DiscreteCommandRequestPayload(commandId, leaseId, action, ttlMs = 2_000),
        )
        val messages =
            session.receiveUntil {
                (it.payload as? CommandResultPayload)?.commandId == commandId
            }
        val ack = messages.mapNotNull { it.payload as? CommandAckPayload }.single { it.commandId == commandId }
        val result = messages.mapNotNull { it.payload as? CommandResultPayload }.single { it.commandId == commandId }
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
            agent.telemetry.addListener(telemetryListener)
            agent.connection.connect()
            core.updateActuationReadiness(snapshots.runtimeState().toActuationReadiness())
            agent.telemetry.start()
            check(firstTelemetry.await(1L, TimeUnit.SECONDS)) { "mock telemetry did not start" }
        }

        fun nextSessionId(): String = "session-e2e-${sessionSequence.incrementAndGet()}"

        override fun close() {
            core.close()
            executor.close()
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
