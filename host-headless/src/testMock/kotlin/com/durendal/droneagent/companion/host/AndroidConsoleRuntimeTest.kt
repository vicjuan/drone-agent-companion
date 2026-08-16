package com.durendal.droneagent.companion.host

import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.mock.MockConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.mock.ObservableMockReturnToHomePort
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.vision.opencv.OpenCvNativeInitializer
import com.durendal.droneagent.companion.vision.opencv.OpenCvRuntimeIdentity
import com.durendal.droneagent.core.connection.ConnectionState
import com.durendal.droneagent.observation.DecodedFrameFormat
import com.durendal.droneagent.observation.DecodedFrameListener
import com.durendal.droneagent.observation.DecodedFrameStartResult
import com.durendal.droneagent.observation.DecodedFrameStream
import com.durendal.droneagent.observation.DecodedFrameStreamFactory
import com.durendal.droneagent.vision.segment.Segmenter
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidConsoleRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `real mock composition serves health and closes with durable server-stop audit`() {
        val webRoot = temporaryFolder.newFolder("web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val auditFile = temporaryFolder.root.resolve("audit/console-events.jsonl")
        val port = availablePort()
        val agent = MockDroneAgent()
        val runtime =
            AndroidConsoleRuntime(
                AndroidConsoleRuntimeConfig(
                    webRoot = webRoot,
                    auditFile = auditFile,
                    bindPort = port,
                ),
                agentFactory = { agent },
            )

        runtime.start()
        try {
            assertEquals("{\"status\":\"ok\"}", awaitHealth(port))
            assertNull("start must leave periodic telemetry stopped", agent.telemetry.latest())
            val lockedAudit = auditFile.readText()
            assertTrue(lockedAudit.contains("mock:connected:locked:localhost_development"))
            assertFalse(lockedAudit.contains("mock:connected:unlocked:localhost_development"))

            runtime.completeStartup()
            assertTrue(
                "mock startup completion must start periodic telemetry",
                awaitTelemetry(agent),
            )
            assertTrue(
                auditFile.readText().contains("mock:connected:unlocked:localhost_development"),
            )
            assertTrue(
                "startup completion must be single use",
                runCatching(runtime::completeStartup).isFailure,
            )
        } finally {
            runtime.requestStop()
            assertEquals(RuntimeCloseResult.CLOSED, runtime.closeWithin(2_000L))
        }

        val audit = auditFile.readText()
        assertTrue(audit.contains("\"kind\":\"actuation_readiness_changed\""))
        assertTrue(audit.contains("\"kind\":\"server_stopped\""))
    }

    @Test
    fun `optional observation source starts after agent connect and closes before agent shutdown`() {
        val webRoot = temporaryFolder.newFolder("vision-web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val agent = MockDroneAgent()
        val sourceStartConnection = AtomicReference<ConnectionState>()
        val sourceCloseConnection = AtomicReference<ConnectionState>()
        val source = OrderingDecodedFrameStream(
            onStart = { sourceStartConnection.set(agent.connection.state()) },
            onClose = { sourceCloseConnection.set(agent.connection.state()) },
        )
        val session =
            HeadlessOpenCvObservationSession(
                streamFactory = DecodedFrameStreamFactory { source },
                nativeInitializer = OpenCvNativeInitializer { TEST_OPENCV_IDENTITY },
                segmenterFactory = { Segmenter { error("no synthetic frame is expected") } },
                bridgeStopAwaitMillis = 50L,
            )
        val runtime =
            AndroidConsoleRuntime(
                config =
                    AndroidConsoleRuntimeConfig(
                        webRoot = webRoot,
                        auditFile = temporaryFolder.root.resolve("vision/audit.jsonl"),
                        bindPort = availablePort(),
                    ),
                agentFactory = { agent },
                openCvObservationSessionFactory = { session },
            )

        runtime.start()
        assertEquals(ConnectionState.AIRCRAFT_CONNECTED, sourceStartConnection.get())
        assertEquals(listOf(DecodedFrameFormat.NV21), source.startedFormats.toList())
        assertEquals(1, source.startCount.get())

        source.failStop = true
        runtime.requestStop()
        assertEquals(RuntimeCloseResult.FAILED, runtime.closeWithin(2_000L))
        assertEquals(ConnectionState.AIRCRAFT_CONNECTED, agent.connection.state())
        assertNull(sourceCloseConnection.get())
        assertEquals(0, source.closeCount.get())

        source.failStop = false
        assertEquals(RuntimeCloseResult.CLOSED, runtime.closeWithin(2_000L))
        assertEquals(
            "the observation source must close before MockDroneAgent.shutdown disconnects",
            ConnectionState.AIRCRAFT_CONNECTED,
            sourceCloseConnection.get(),
        )
        assertEquals(ConnectionState.DISCONNECTED, agent.connection.state())
        assertEquals(3, source.stopCount.get())
        assertEquals(1, source.closeCount.get())
        assertTrue(source.listeners.isEmpty())
        assertEquals(OpenCvObservationLifecycleState.CLOSED, session.snapshot().lifecycleState)
        assertTrue(session.snapshot().finalMeasurement)
    }

    @Test
    fun `stop before startup completion rejects late completion`() {
        val webRoot = temporaryFolder.newFolder("startup-web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val auditFile = temporaryFolder.root.resolve("startup/audit.jsonl")
        val runtime =
            AndroidConsoleRuntime(
                AndroidConsoleRuntimeConfig(
                    webRoot = webRoot,
                    auditFile = auditFile,
                    bindPort = availablePort(),
                ),
            )

        runtime.start()
        runtime.requestStop()
        assertTrue(runCatching(runtime::completeStartup).isFailure)
        assertFalse(
            auditFile.readText().contains("mock:connected:unlocked:localhost_development"),
        )
        assertEquals(RuntimeCloseResult.CLOSED, runtime.closeWithin(2_000L))
    }

    @Test
    fun `port collision fails startup and cleanup remains bounded`() {
        val webRoot = temporaryFolder.newFolder("collision-web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val occupied = ServerSocket()
        occupied.reuseAddress = false
        occupied.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        occupied.use {
            val runtime =
                AndroidConsoleRuntime(
                    AndroidConsoleRuntimeConfig(
                        webRoot = webRoot,
                        auditFile = temporaryFolder.root.resolve("collision/audit.jsonl"),
                        bindPort = it.localPort,
                        serverStartupTimeoutMillis = 500L,
                    ),
                )

            val failed = runCatching(runtime::start).isFailure
            assertTrue("Ktor must fail when its exact bind port is occupied", failed)
            assertEquals(RuntimeCloseResult.CLOSED, runtime.closeWithin(2_000L))
        }
    }

    @Test
    fun `audit construction failure reports closed after partial resources are released`() {
        val webRoot = temporaryFolder.newFolder("audit-failure-web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val invalidParent = temporaryFolder.newFile("audit-parent-is-a-file")
        val runtime =
            AndroidConsoleRuntime(
                AndroidConsoleRuntimeConfig(
                    webRoot = webRoot,
                    auditFile = File(invalidParent, "console-events.jsonl"),
                    bindPort = availablePort(),
                ),
            )

        assertTrue(runCatching(runtime::start).isFailure)
        assertEquals(
            "pre-server resources were already released by the construction failure path",
            RuntimeCloseResult.CLOSED,
            runtime.closeWithin(2_000L),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mock composition rejects an origin other than the fixed adb-forward endpoint`() {
        AndroidConsoleRuntimeConfig(
            webRoot = temporaryFolder.newFolder("origin-web"),
            auditFile = temporaryFolder.root.resolve("origin/audit.jsonl"),
            allowedBrowserOrigin = "http://127.0.0.1:8080",
        )
    }

    @Test
    fun `browser runtime snapshot remains locked until mock ready and after terminal stop`() {
        val agent = MockDroneAgent()
        val mockReady = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val snapshots =
            MockReadyGatedConsoleSnapshotProvider(
                delegate = MockConsoleSnapshotProvider(agent, ObservableMockReturnToHomePort()),
                mockReady = mockReady,
                stopRequested = stopRequested,
            )
        try {
            agent.connection.connect()

            assertEquals(ActuationLockState.LOCKED, snapshots.runtimeState().actuationLock)

            mockReady.set(true)
            assertEquals(ActuationLockState.UNLOCKED, snapshots.runtimeState().actuationLock)

            stopRequested.set(true)
            assertEquals(ActuationLockState.LOCKED, snapshots.runtimeState().actuationLock)
        } finally {
            agent.shutdown()
        }
    }

    private class OrderingDecodedFrameStream(
        private val onStart: () -> Unit,
        private val onClose: () -> Unit,
    ) : DecodedFrameStream {
        val listeners = CopyOnWriteArrayList<DecodedFrameListener>()
        val startedFormats = CopyOnWriteArrayList<DecodedFrameFormat>()
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        @Volatile var failStop = false

        override fun start(format: DecodedFrameFormat): DecodedFrameStartResult {
            onStart()
            startCount.incrementAndGet()
            startedFormats += format
            return DecodedFrameStartResult.Started
        }

        override fun stop() {
            stopCount.incrementAndGet()
            if (failStop) error("synthetic transient observation stop failure")
        }

        override fun addListener(listener: DecodedFrameListener) {
            listeners += listener
        }

        override fun removeListener(listener: DecodedFrameListener) {
            listeners -= listener
        }

        override fun close() {
            onClose()
            closeCount.incrementAndGet()
        }
    }

    private companion object {
        val TEST_OPENCV_IDENTITY =
            OpenCvRuntimeIdentity(
                version = "test-4.9.0",
                platform = "jvm-test",
                buildInformationSha256 = "b".repeat(64),
            )
    }

    private fun awaitHealth(port: Int): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val connection =
                    (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection)
                        .apply {
                            connectTimeout = 250
                            readTimeout = 500
                        }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(25L)
            }
        }
        throw AssertionError("headless health endpoint did not become ready", lastFailure)
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitTelemetry(agent: MockDroneAgent): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (agent.telemetry.latest() != null) return true
            Thread.sleep(10L)
        }
        return false
    }
}
