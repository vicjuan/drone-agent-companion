package com.durendal.droneagent.companion.console.server.transport

import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.AuthenticationPresentation
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.protocol.ConsoleServerPayload
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolConsoleSocketControllerTest {
    private val codec = ConsoleProtocolCodec()

    @Test
    fun `only a valid hello crosses the first-message gate`() {
        val handler = RecordingSessionHandler()
        val controller = controller(handler)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)

        controller.onText(
            "session-1",
            client("lease-before-hello", LeaseAcquirePayload(requestedTtlMs = 5_000)),
            sink,
        )

        assertTrue(handler.messages.isEmpty())
        assertEquals(ProtocolErrorCode.HANDSHAKE_REQUIRED, sink.lastProtocolError().code)

        controller.onText("session-1", client("hello-1", hello()), sink)
        assertEquals(listOf("hello-1"), handler.messages.map { it.second.messageId })

        controller.onText("session-1", client("hello-duplicate", hello()), sink)
        assertEquals(ProtocolErrorCode.UNEXPECTED_MESSAGE, sink.lastProtocolError().code)
        assertEquals(listOf("hello-1"), handler.messages.map { it.second.messageId })
    }

    @Test
    fun `strict payload decoding rejects a client authority assertion before dispatch`() {
        val handler = RecordingSessionHandler()
        val controller = controller(handler)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)
        controller.onText("session-1", client("hello-1", hello()), sink)

        controller.onText(
            "session-1",
            """{
              "protocolVersion":"1.0",
              "messageId":"command-with-authority",
              "type":"command_request",
              "payload":{
                "commandId":"takeoff-1",
                "leaseId":"lease-1",
                "action":"takeoff",
                "ttlMs":5000,
                "authority":{"originType":"operator"}
              }
            }""".trimIndent(),
            sink,
        )

        assertEquals(listOf("hello-1"), handler.messages.map { it.second.messageId })
        val error = sink.lastProtocolError()
        assertEquals(ProtocolErrorCode.INVALID_PAYLOAD, error.code)
        assertEquals("command-with-authority", error.relatedMessageId)
    }

    @Test
    fun `handler failure is sanitized and closes the authority session exactly once`() {
        val handler = RecordingSessionHandler(failOnMessage = true)
        val controller = controller(handler)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)

        controller.onText("session-1", client("hello-1", hello()), sink)
        controller.onClose("session-1", "peer_closed", sink)

        val error = sink.lastProtocolError()
        assertEquals(ProtocolErrorCode.SERVER_UNAVAILABLE, error.code)
        assertFalse(checkNotNull(error.detail).contains("secret-adapter-failure"))
        assertEquals(listOf("session-1" to "server_unavailable"), handler.closed)
        assertEquals(listOf("server_unavailable"), sink.closeReasons)
    }

    @Test
    fun `events reach only hello-complete sessions and can be emitted reentrantly`() {
        lateinit var controller: ProtocolConsoleSocketController
        val handler =
            RecordingSessionHandler(
                onMessage = { sessionId, message ->
                    if (message.payload is ClientHelloPayload) {
                        assertTrue(
                            controller.emit(
                                sessionId,
                                HealthPayload(
                                    HealthStatus.HEALTHY,
                                    uptimeMs = 5L,
                                    detail = null,
                                ),
                            ),
                        )
                        assertTrue(
                            controller.emit(
                                sessionId,
                                ServerHelloPayload(
                                    sessionId = sessionId,
                                    serverVersion = "0.1.0",
                                    selectedProtocolVersion = "1.0",
                                    authenticationRequired = false,
                                    acceptedAuthenticationSchemes = emptyList(),
                                ),
                            ),
                        )
                    }
                },
            )
        controller = controller(handler, autoServerHello = false)
        val readySink = RecordingFrameSink()
        val waitingSink = RecordingFrameSink()
        controller.onOpen("session-ready", readySink)
        controller.onOpen("session-waiting", waitingSink)

        controller.onText("session-ready", client("hello-ready", hello()), readySink)
        assertEquals(
            listOf("server_hello", "health"),
            readySink.decoded.map { it.type.wireName },
        )

        assertTrue(
            controller.emit(
                targetSessionId = null,
                payload = HealthPayload(HealthStatus.HEALTHY, uptimeMs = 10L, detail = null),
            ),
        )

        assertEquals(
            listOf("server_hello", "health", "health"),
            readySink.decoded.map { it.type.wireName },
        )
        assertTrue(waitingSink.frames.isEmpty())
    }

    @Test
    fun `negotiated sessions use exact versions for hello ready input broadcast and errors`() {
        val handler = RecordingSessionHandler()
        val controller = controller(handler)
        val legacySink = RecordingFrameSink()
        val currentSink = RecordingFrameSink()
        controller.onOpen("session-v10", legacySink)
        controller.onOpen("session-v11", currentSink)

        controller.onText(
            "session-v10",
            client("hello-v10", hello(listOf("1.0"))),
            legacySink,
        )
        controller.onText(
            "session-v11",
            client("hello-v11", hello(listOf("1.1", "1.0"))),
            currentSink,
        )

        assertEquals(
            listOf("session-v10" to "1.0", "session-v11" to "1.1"),
            handler.selected,
        )
        assertEquals(
            "1.0",
            (legacySink.decoded("1.0").first().payload as ServerHelloPayload)
                .selectedProtocolVersion,
        )
        assertEquals(
            "1.1",
            (currentSink.decoded("1.1").first().payload as ServerHelloPayload)
                .selectedProtocolVersion,
        )

        assertTrue(
            controller.emit(
                null,
                HealthPayload(HealthStatus.HEALTHY, uptimeMs = 10L, detail = null),
            ),
        )
        assertEquals("1.0", codec.inspectEnvelope(legacySink.frames.last(), "1.0").protocolVersion)
        assertEquals("1.1", codec.inspectEnvelope(currentSink.frames.last(), "1.1").protocolVersion)

        controller.onText(
            "session-v11",
            client(
                "lease-v11",
                LeaseAcquirePayload(requestedTtlMs = 5_000),
                protocolVersion = "1.1",
            ),
            currentSink,
        )
        assertEquals("lease-v11", handler.messages.last().second.messageId)

        controller.onText(
            "session-v11",
            """{"protocolVersion":"1.1","messageId":"bad-v11","type":"lease_acquire","payload":{"requestedTtlMs":0}}""",
            currentSink,
        )
        val error = currentSink.decoded("1.1").last().payload as ProtocolErrorPayload
        assertEquals(ProtocolErrorCode.INVALID_PAYLOAD, error.code)
        assertEquals("bad-v11", error.relatedMessageId)
    }

    @Test
    fun `rejected hello sends only a bootstrap error and closes`() {
        val handler =
            RecordingSessionHandler(
                onMessage = { _, message ->
                    if ((message.payload as? ClientHelloPayload)?.authentication != null) {
                        throw ConsoleSessionProtocolException(ProtocolErrorCode.UNEXPECTED_MESSAGE)
                    }
                },
            )
        val controller = controller(handler)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)

        controller.onText(
            "session-1",
            client(
                "hello-auth",
                hello(
                    supportedProtocolVersions = listOf("1.1", "1.0"),
                    authentication = AuthenticationPresentation("token", "not-logged"),
                ),
            ),
            sink,
        )

        val frames = sink.decoded("1.0")
        assertEquals(listOf("protocol_error"), frames.map { it.type.wireName })
        assertEquals(
            ProtocolErrorCode.UNEXPECTED_MESSAGE,
            (frames.single().payload as ProtocolErrorPayload).code,
        )
        assertEquals(
            listOf("session-1" to "session_rejected:unexpected_message"),
            handler.closed,
        )
    }

    @Test
    fun `closing connection tombstone and sink identity reject same-id ABA`() {
        lateinit var controller: ProtocolConsoleSocketController
        val firstHello = AtomicBoolean(true)
        val helloEntered = CountDownLatch(1)
        val releaseHello = CountDownLatch(1)
        val handler =
            RecordingSessionHandler(
                onMessage = { sessionId, message ->
                    if (message.payload is ClientHelloPayload) {
                        if (firstHello.compareAndSet(true, false)) {
                            helloEntered.countDown()
                            check(releaseHello.await(2L, TimeUnit.SECONDS))
                        }
                        check(controller.emit(sessionId, serverHello(sessionId)))
                    }
                },
            )
        controller = controller(handler, autoServerHello = false)
        val oldSink = RecordingFrameSink()
        controller.onOpen("session-reused", oldSink)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val oldHello =
                pool.submit {
                    controller.onText(
                        "session-reused",
                        client("hello-old", hello()),
                        oldSink,
                    )
                }
            assertTrue(helloEntered.await(1L, TimeUnit.SECONDS))

            val terminatorThread = AtomicReference<Thread>()
            val terminatorStarted = CountDownLatch(1)
            val terminating =
                pool.submit {
                    terminatorThread.set(Thread.currentThread())
                    terminatorStarted.countDown()
                    controller.onProtocolViolation(
                        "session-reused",
                        "old_transport_failure",
                        oldSink,
                    )
                }
            assertTrue(terminatorStarted.await(1L, TimeUnit.SECONDS))
            val blockDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
            while (terminatorThread.get()?.state != Thread.State.BLOCKED &&
                System.nanoTime() < blockDeadline
            ) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, terminatorThread.get()?.state)

            val rejectedReplacement = RecordingFrameSink()
            controller.onOpen("session-reused", rejectedReplacement)
            assertEquals(
                listOf("duplicate_server_session_id"),
                rejectedReplacement.closeReasons,
            )

            releaseHello.countDown()
            oldHello.get(1L, TimeUnit.SECONDS)
            terminating.get(1L, TimeUnit.SECONDS)

            val replacementSink = RecordingFrameSink()
            controller.onOpen("session-reused", replacementSink)
            controller.onText(
                "session-reused",
                client("hello-new", hello()),
                replacementSink,
            )
            controller.onClose("session-reused", "late_old_close", oldSink)

            assertEquals(
                listOf("server_hello"),
                replacementSink.decoded.map { it.type.wireName },
            )
            assertTrue(replacementSink.closeReasons.isEmpty())
            assertEquals(
                listOf("session-reused" to "protocol_violation:old_transport_failure"),
                handler.closed,
            )
        } finally {
            releaseHello.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `broadcasts wait behind an in-progress hello and a concurrent client frame cannot overtake`() {
        lateinit var controller: ProtocolConsoleSocketController
        val helloEntered = CountDownLatch(1)
        val releaseHello = CountDownLatch(1)
        val secondFrameStarted = CountDownLatch(1)
        val handler =
            RecordingSessionHandler(
                onMessage = { sessionId, message ->
                    if (message.payload is ClientHelloPayload) {
                        helloEntered.countDown()
                        check(releaseHello.await(2L, TimeUnit.SECONDS))
                        check(
                            controller.emit(
                                sessionId,
                                ServerHelloPayload(
                                    sessionId = sessionId,
                                    serverVersion = "0.1.0",
                                    selectedProtocolVersion = "1.0",
                                    authenticationRequired = false,
                                    acceptedAuthenticationSchemes = emptyList(),
                                ),
                            ),
                        )
                    }
                },
            )
        controller = controller(handler, autoServerHello = false)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val helloFuture =
                executor.submit {
                    controller.onText("session-1", client("hello-1", hello()), sink)
                }
            assertTrue(helloEntered.await(1L, TimeUnit.SECONDS))

            assertTrue(
                controller.emit(
                    null,
                    HealthPayload(HealthStatus.HEALTHY, uptimeMs = 10L, detail = null),
                ),
            )
            assertTrue("handshake output must still be buffered", sink.frames.isEmpty())

            val secondFrame =
                executor.submit {
                    secondFrameStarted.countDown()
                    controller.onText(
                        "session-1",
                        client("lease-after-hello", LeaseAcquirePayload(requestedTtlMs = 5_000)),
                        sink,
                    )
                }
            assertTrue(secondFrameStarted.await(1L, TimeUnit.SECONDS))
            assertFalse("second frame crossed the hello handler", secondFrame.isDone)
            assertEquals(listOf("hello-1"), handler.messages.map { it.second.messageId })

            releaseHello.countDown()
            helloFuture.get(1L, TimeUnit.SECONDS)
            secondFrame.get(1L, TimeUnit.SECONDS)

            assertEquals(
                listOf("server_hello", "health"),
                sink.decoded.map { it.type.wireName },
            )
            assertEquals(
                listOf("hello-1", "lease-after-hello"),
                handler.messages.map { it.second.messageId },
            )
        } finally {
            releaseHello.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `handshake broadcast buffer is bounded and overflow revokes the session`() {
        lateinit var controller: ProtocolConsoleSocketController
        val emitResults = CopyOnWriteArrayList<Boolean>()
        val handler =
            RecordingSessionHandler(
                onMessage = { _, message ->
                    if (message.payload is ClientHelloPayload) {
                        repeat(3) { index ->
                            emitResults +=
                                controller.emit(
                                    null,
                                    HealthPayload(
                                        HealthStatus.HEALTHY,
                                        uptimeMs = index.toLong(),
                                        detail = null,
                                    ),
                                )
                        }
                    }
                },
            )
        controller = controller(handler, maxBufferedServerFramesPerSession = 2)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)

        controller.onText("session-1", client("hello-1", hello()), sink)

        assertEquals(listOf(true, true, false), emitResults)
        assertTrue(sink.frames.isEmpty())
        assertEquals(listOf("session-1" to "outbound_backpressure"), handler.closed)
        assertEquals(listOf("outbound_backpressure"), sink.closeReasons)
    }

    @Test
    fun `lease release and runtime lock during handshake follow initial snapshots`() {
        lateinit var controller: ProtocolConsoleSocketController
        val initialSnapshotsQueued = CountDownLatch(1)
        val releaseHello = CountDownLatch(1)
        val handler =
            RecordingSessionHandler(
                onMessage = { sessionId, message ->
                    if (message.payload is ClientHelloPayload) {
                        check(controller.emit(sessionId, serverHello(sessionId)))
                        check(controller.emit(sessionId, heldLease(sessionId)))
                        check(controller.emit(sessionId, runtime(ActuationLockState.UNLOCKED)))
                        initialSnapshotsQueued.countDown()
                        check(releaseHello.await(2L, TimeUnit.SECONDS))
                    }
                },
            )
        controller = controller(handler, autoServerHello = false)
        val sink = RecordingFrameSink()
        controller.onOpen("session-b", sink)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val helloFuture =
                executor.submit {
                    controller.onText("session-b", client("hello-b", hello()), sink)
                }
            assertTrue(initialSnapshotsQueued.await(1L, TimeUnit.SECONDS))

            assertTrue(controller.emit(null, releasedLease()))
            assertTrue(controller.emit(null, runtime(ActuationLockState.LOCKED)))
            assertTrue("no handshake frame may escape before READY", sink.frames.isEmpty())

            releaseHello.countDown()
            helloFuture.get(1L, TimeUnit.SECONDS)

            assertEquals(
                listOf(
                    "server_hello",
                    "lease_state",
                    "runtime_state",
                    "lease_state",
                    "runtime_state",
                ),
                sink.decoded.map { it.type.wireName },
            )
            assertEquals(
                listOf(LeaseState.HELD, LeaseState.RELEASED),
                sink.decoded.mapNotNull { it.payload as? LeaseStatePayload }.map { it.state },
            )
            assertEquals(
                listOf(ActuationLockState.UNLOCKED, ActuationLockState.LOCKED),
                sink.decoded.mapNotNull { it.payload as? RuntimeStatePayload }.map { it.actuationLock },
            )
        } finally {
            releaseHello.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `outbound backpressure revokes the session instead of leaving an input-only peer`() {
        val handler = RecordingSessionHandler()
        val controller = controller(handler)
        val sink = RecordingFrameSink(acceptOffers = false)
        controller.onOpen("session-1", sink)
        controller.onText("session-1", client("hello-1", hello()), sink)

        assertFalse(
            controller.emit(
                "session-1",
                HealthPayload(HealthStatus.HEALTHY, uptimeMs = 10L, detail = null),
            ),
        )

        assertEquals(listOf("session-1" to "outbound_backpressure"), handler.closed)
        assertEquals(listOf("outbound_backpressure"), sink.closeReasons)
    }

    @Test
    fun `an invalid server event closes every affected session fail closed`() {
        val handler = RecordingSessionHandler()
        val controller = controller(handler)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)
        controller.onText("session-1", client("hello-1", hello()), sink)

        assertFalse(
            controller.emit(
                "session-1",
                HealthPayload(HealthStatus.HEALTHY, uptimeMs = -1L, detail = null),
            ),
        )

        assertEquals(listOf("session-1" to "invalid_server_event"), handler.closed)
        assertEquals(listOf("invalid_server_event"), sink.closeReasons)
    }

    @Test
    fun `transport protocol violation invokes the disconnect path once`() {
        val handler = RecordingSessionHandler()
        val controller = controller(handler)
        val sink = RecordingFrameSink()
        controller.onOpen("session-1", sink)

        controller.onProtocolViolation("session-1", "non_text_frame", sink)
        controller.onClose("session-1", "peer_closed", sink)

        assertEquals(
            listOf("session-1" to "protocol_violation:non_text_frame"),
            handler.closed,
        )
    }

    private fun controller(
        handler: ConsoleClientSessionHandler,
        maxBufferedServerFramesPerSession: Int = 128,
        autoServerHello: Boolean = true,
    ): ProtocolConsoleSocketController {
        val sequence = AtomicLongForTest()
        lateinit var controller: ProtocolConsoleSocketController
        val selectedVersions = ConcurrentHashMap<String, String>()
        val effectiveHandler =
            if (!autoServerHello) {
                handler
            } else {
                object : ConsoleClientSessionHandler {
                    override fun onSessionOpened(sessionId: String) {
                        handler.onSessionOpened(sessionId)
                    }

                    override fun onProtocolSelected(
                        sessionId: String,
                        selectedProtocolVersion: String,
                    ) {
                        selectedVersions[sessionId] = selectedProtocolVersion
                        handler.onProtocolSelected(sessionId, selectedProtocolVersion)
                    }

                    override fun onClientMessage(
                        sessionId: String,
                        message: ConsoleClientMessage,
                    ) {
                        handler.onClientMessage(sessionId, message)
                        if (message.payload is ClientHelloPayload) {
                            controller.emit(
                                sessionId,
                                serverHello(
                                    sessionId,
                                    checkNotNull(selectedVersions[sessionId]),
                                ),
                            )
                        }
                    }

                    override fun onSessionClosed(
                        sessionId: String,
                        reason: String,
                    ) {
                        selectedVersions.remove(sessionId)
                        handler.onSessionClosed(sessionId, reason)
                    }
                }
            }
        controller = ProtocolConsoleSocketController(
            handler = effectiveHandler,
            codec = codec,
            messageIdFactory = { "server-test-${sequence.next()}" },
            maxBufferedServerFramesPerSession = maxBufferedServerFramesPerSession,
        )
        return controller
    }

    private fun hello(
        supportedProtocolVersions: List<String> = listOf("1.0"),
        authentication: AuthenticationPresentation? = null,
    ): ClientHelloPayload =
        ClientHelloPayload(
            clientName = "web-console",
            clientVersion = "0.1.0",
            supportedProtocolVersions = supportedProtocolVersions,
            authentication = authentication,
        )

    private fun serverHello(
        sessionId: String,
        selectedProtocolVersion: String = "1.0",
    ): ServerHelloPayload =
        ServerHelloPayload(
            sessionId = sessionId,
            serverVersion = "0.1.0",
            selectedProtocolVersion = selectedProtocolVersion,
            authenticationRequired = false,
            acceptedAuthenticationSchemes = emptyList(),
        )

    private fun heldLease(holderSessionId: String): LeaseStatePayload =
        LeaseStatePayload(
            requestMessageId = null,
            state = LeaseState.HELD,
            leaseId = "lease-a",
            holderSessionId = holderSessionId,
            expiresInMs = 5_000L,
            reason = null,
        )

    private fun releasedLease(): LeaseStatePayload =
        LeaseStatePayload(
            requestMessageId = null,
            state = LeaseState.RELEASED,
            leaseId = "lease-a",
            holderSessionId = null,
            expiresInMs = null,
            reason = null,
        )

    private fun runtime(lock: ActuationLockState): RuntimeStatePayload =
        RuntimeStatePayload(
            adapter = AdapterKind.MOCK,
            aircraftConnection = AircraftConnectionState.CONNECTED,
            actuationLock = lock,
            operatingProfile = OperatingProfile.LOCALHOST_DEVELOPMENT,
        )

    private fun client(
        messageId: String,
        payload: com.durendal.droneagent.companion.console.protocol.ConsoleClientPayload,
        protocolVersion: String = ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION,
    ): String = codec.encodeClient(ConsoleClientMessage(messageId, payload), protocolVersion)

    private inner class RecordingFrameSink(
        private val acceptOffers: Boolean = true,
    ) : ConsoleFrameSink {
        val frames = CopyOnWriteArrayList<String>()
        val closeReasons = CopyOnWriteArrayList<String>()
        val decoded get() = decoded(ConsoleProtocolModule.PROTOCOL_VERSION)

        fun decoded(protocolVersion: String) =
            frames.map { codec.decodeServer(it, protocolVersion) }

        override fun offer(text: String): Boolean {
            if (acceptOffers) frames += text
            return acceptOffers
        }

        override fun close(reason: String) {
            closeReasons += reason
        }

        fun lastProtocolError(): ProtocolErrorPayload =
            decoded.last().payload as ProtocolErrorPayload
    }

    private class RecordingSessionHandler(
        private val failOnMessage: Boolean = false,
        private val onMessage: (String, ConsoleClientMessage) -> Unit = { _, _ -> },
    ) : ConsoleClientSessionHandler {
        val opened = CopyOnWriteArrayList<String>()
        val selected = CopyOnWriteArrayList<Pair<String, String>>()
        val messages = CopyOnWriteArrayList<Pair<String, ConsoleClientMessage>>()
        val closed = CopyOnWriteArrayList<Pair<String, String>>()

        override fun onSessionOpened(sessionId: String) {
            opened += sessionId
        }

        override fun onProtocolSelected(
            sessionId: String,
            selectedProtocolVersion: String,
        ) {
            selected += sessionId to selectedProtocolVersion
        }

        override fun onClientMessage(
            sessionId: String,
            message: ConsoleClientMessage,
        ) {
            if (failOnMessage) error("secret-adapter-failure")
            messages += sessionId to message
            onMessage(sessionId, message)
        }

        override fun onSessionClosed(
            sessionId: String,
            reason: String,
        ) {
            closed += sessionId to reason
        }
    }

    private class AtomicLongForTest {
        private var value = 0L

        @Synchronized
        fun next(): Long {
            value += 1L
            return value
        }
    }
}
