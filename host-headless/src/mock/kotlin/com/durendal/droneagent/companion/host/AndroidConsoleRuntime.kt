package com.durendal.droneagent.companion.host

import android.util.Log
import com.durendal.droneagent.actuation.FlightControlPortSnapshotListener
import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.mock.MockConsoleCommandExecutor
import com.durendal.droneagent.companion.console.mock.MockConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.mock.ObservableMockReturnToHomePort
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.FileConsoleAuditSink
import com.durendal.droneagent.companion.console.server.JdkConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.security.ConsoleExposurePolicy
import com.durendal.droneagent.companion.console.server.toActuationReadiness
import com.durendal.droneagent.companion.console.server.transport.ConsoleCoreProtocolAdapter
import com.durendal.droneagent.companion.console.server.transport.ConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.server.transport.ConsoleServerConfig
import com.durendal.droneagent.companion.console.server.transport.KtorConsoleServer
import com.durendal.droneagent.companion.console.server.transport.ProtocolConsoleSocketController
import com.durendal.droneagent.core.connection.ConnectionListener
import com.durendal.droneagent.core.telemetry.TelemetryListener
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class AndroidConsoleRuntimeConfig(
    val webRoot: File,
    val auditFile: File,
    val bindPort: Int = BIND_PORT,
    val serverStartupTimeoutMillis: Long = 10_000L,
) {
    init {
        require(bindPort in 1..65_535) { "bindPort must be between 1 and 65535" }
        require(serverStartupTimeoutMillis in 100L..60_000L) {
            "serverStartupTimeoutMillis must be between 100 and 60000"
        }
    }

    companion object {
        const val BIND_PORT: Int = 8080
        const val FORWARDED_BROWSER_PORT: Int = 18_080
    }
}

/**
 * Android composition of the already-reviewed console core and adapter-mock.
 *
 * This runtime deliberately exposes only the localhost development profile. It is not a DJI or
 * G520 commissioning composition, and none of its successes may promote the hardware capability
 * matrix. Hardware process-loss safety also requires a first-hand aircraft-side failsafe test;
 * an in-process foreground service cannot neutralize after its own process has died.
 * [completeStartup] publishes MOCK_READY only for this explicitly mock-only profile; the generic
 * host startup contract never grants hardware commissioning authority.
 */
class AndroidConsoleRuntime internal constructor(
    private val config: AndroidConsoleRuntimeConfig,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val agentFactory: () -> MockDroneAgent = { MockDroneAgent() },
    private val openCvObservationSessionFactory: (() -> HeadlessOpenCvObservationSession)? = null,
) : HeadlessRuntime {
    private val lifecycleLock = Any()
    private val stopRequested = AtomicBoolean(false)
    private val mockReady = AtomicBoolean(false)
    private var state = State.NEW
    @Volatile private var startingComponents: Components? = null
    @Volatile private var components: Components? = null
    private var terminalCloseResult: RuntimeCloseResult? = null

    override fun requestStop() {
        stopRequested.set(true)
        mockReady.set(false)
        startingComponents?.closeActuationAdmission()
        components?.closeActuationAdmission()
    }

    override fun start() {
        synchronized(lifecycleLock) {
            check(state == State.NEW) { "headless console runtime may be started only once" }
            check(!stopRequested.get()) { "headless runtime startup was cancelled" }
            check(config.webRoot.isDirectory) {
                "packaged web console is unavailable at ${config.webRoot.absolutePath}"
            }
            state = State.STARTING
        }

        val created =
            try {
                runStartupPhase(StartupPhase.COMPONENTS, ::createComponents)
            } catch (failure: Throwable) {
                synchronized(lifecycleLock) {
                    state = State.CLOSED
                    if (terminalCloseResult == null) terminalCloseResult = RuntimeCloseResult.FAILED
                }
                throw failure
            }
        startingComponents = created
        try {
            runStartupPhase(StartupPhase.LISTENERS) {
                ensureStartupMayContinue(created)
                created.installListeners()
            }
            runStartupPhase(StartupPhase.AGENT_CONNECT) {
                ensureStartupMayContinue(created)
                created.agent.connection.connect()
            }
            runStartupPhase(StartupPhase.VISION_START) {
                ensureStartupAdmitted(created)
                created.startVisionObservation()
            }
            runStartupPhase(StartupPhase.LOCKED_RUNTIME_STATE) {
                ensureStartupMayContinue(created)
                created.publishRuntimeState()
            }
            runStartupPhase(StartupPhase.CONNECTOR) {
                ensureStartupMayContinue(created)
                created.server.start(wait = false)
            }
            // Keep periodic telemetry stopped while the one-time canonical matrix parse warms.
            // A browser may already load the SPA/health endpoint, but HANDSHAKING cannot overflow
            // its bounded broadcast queue before this cold path finishes.
            runStartupPhase(StartupPhase.CAPABILITY_PREWARM) {
                ensureStartupMayContinue(created)
                created.prewarmCapabilitySnapshot()
            }
            runStartupPhase(StartupPhase.STARTUP_PENDING) {
                ensureStartupMayContinue(created)
                synchronized(lifecycleLock) {
                    check(!stopRequested.get()) { "headless runtime startup was cancelled" }
                    components = created
                    startingComponents = null
                    state = State.STARTUP_PENDING
                }
            }
        } catch (failure: Throwable) {
            val cleanupResult = created.closeWithin(CLOSE_AFTER_START_FAILURE_MILLIS)
            synchronized(lifecycleLock) {
                startingComponents = null
                terminalCloseResult = cleanupResult.takeIf { it == RuntimeCloseResult.CLOSED }
                components = created.takeUnless { cleanupResult == RuntimeCloseResult.CLOSED }
                state =
                    if (cleanupResult != RuntimeCloseResult.CLOSED) {
                        State.CLOSE_INCOMPLETE
                    } else {
                        State.CLOSED
                    }
            }
            throw failure
        }
    }

    override fun completeStartup() {
        val completing =
            synchronized(lifecycleLock) {
                check(state == State.STARTUP_PENDING) {
                    "headless console runtime is not awaiting startup completion"
                }
                check(!stopRequested.get()) { "headless runtime startup completion was cancelled" }
                state = State.COMPLETING_STARTUP
                checkNotNull(components) { "headless console components are unavailable" }
            }

        try {
            ensureStartupMayContinue(completing)
            // This readiness publication is confined to the localhost adapter-mock composition.
            // It is not a commissioning decision and must never be copied into a DJI runtime.
            mockReady.set(true)
            ensureStartupMayContinue(completing)
            completing.publishRuntimeState()
            ensureStartupMayContinue(completing)
            completing.agent.telemetry.start()
            ensureStartupMayContinue(completing)
            synchronized(lifecycleLock) {
                check(!stopRequested.get()) { "headless runtime startup completion was cancelled" }
                state = State.RUNNING
            }
        } catch (failure: Throwable) {
            reportStartupFailure(StartupPhase.MOCK_READY)
            mockReady.set(false)
            completing.closeActuationAdmission()
            synchronized(lifecycleLock) {
                if (state == State.COMPLETING_STARTUP) state = State.STARTUP_PENDING
            }
            throw failure
        }
    }

    override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val closing =
            synchronized(lifecycleLock) {
                when (state) {
                    State.CLOSED -> return terminalCloseResult ?: RuntimeCloseResult.CLOSED
                    State.NEW -> {
                        state = State.CLOSED
                        terminalCloseResult = RuntimeCloseResult.CLOSED
                        return RuntimeCloseResult.CLOSED
                    }
                    State.STARTING -> return RuntimeCloseResult.FAILED
                    State.STARTUP_PENDING,
                    State.COMPLETING_STARTUP,
                    State.RUNNING -> {
                        state = State.CLOSING
                        components.also { components = null }
                    }
                    State.CLOSE_INCOMPLETE -> {
                        state = State.CLOSING
                        components.also { components = null }
                    }
                    State.CLOSING -> return RuntimeCloseResult.FAILED
                }
            } ?: return RuntimeCloseResult.FAILED

        val result = closing.closeWithin(timeoutMillis)
        synchronized(lifecycleLock) {
            if (result != RuntimeCloseResult.CLOSED) {
                components = closing
                state = State.CLOSE_INCOMPLETE
            } else {
                terminalCloseResult = result
                state = State.CLOSED
            }
        }
        return result
    }

    private fun createComponents(): Components {
        val monotonicClock = ConsoleMonotonicClock(monotonicNanos)
        val epochClock = ConsoleEpochClock(epochMillis)
        val agent = agentFactory()
        val returnToHome = ObservableMockReturnToHomePort()
        val executor = MockConsoleCommandExecutor(agent, monotonicClock, returnToHome)
        val snapshots = MockConsoleSnapshotProvider(agent, returnToHome)
        val mockReadySnapshots =
            MockReadyGatedConsoleSnapshotProvider(
                delegate = snapshots,
                mockReady = mockReady,
                stopRequested = stopRequested,
            )
        val scheduler = JdkConsoleDeadlineScheduler(monotonicClock)
        val audit =
            try {
                FileConsoleAuditSink(config.auditFile.toPath())
            } catch (failure: Throwable) {
                var cleanupFailed = false
                runCatching { executor.close() }.onFailure { cleanupFailed = true }
                runCatching { scheduler.close() }.onFailure { cleanupFailed = true }
                runCatching { agent.shutdown() }.onFailure { cleanupFailed = true }
                synchronized(lifecycleLock) {
                    terminalCloseResult =
                        if (cleanupFailed) RuntimeCloseResult.FAILED else RuntimeCloseResult.CLOSED
                }
                throw failure
            }
        val coreReference = AtomicReference<ConsoleServerCore>()
        val controllerReference = AtomicReference<ProtocolConsoleSocketController>()
        var visionSessionForCleanup: HeadlessOpenCvObservationSession? = null
        var coreForCleanup: ConsoleServerCore? = null
        var serverForCleanup: KtorConsoleServer? = null
        try {
            val visionSession = openCvObservationSessionFactory?.invoke()
            visionSessionForCleanup = visionSession
            val protocol =
                ConsoleCoreProtocolAdapter(
                    coreProvider = {
                        checkNotNull(coreReference.get()) { "console core not attached" }
                    },
                    snapshots = mockReadySnapshots,
                    serverVersion = SERVER_VERSION,
                    emitPayload = { target, payload ->
                        controllerReference.get()?.emit(target, payload) ?: false
                    },
                )
            val core =
                ConsoleServerCore(
                    config = ConsoleServerCoreConfig(),
                    admission =
                        ConsoleCommandAdmission(
                            agentId = AGENT_ID,
                            streamId = STREAM_ID,
                            epochClock = epochClock,
                        ),
                    executor = executor,
                    monotonicClock = monotonicClock,
                    epochClock = epochClock,
                    scheduler = scheduler,
                    auditSink = audit,
                    eventSink = protocol,
                )
            coreForCleanup = core
            coreReference.set(core)
            val controller = ProtocolConsoleSocketController(protocol)
            controllerReference.set(controller)
            val server =
                KtorConsoleServer(
                    ConsoleServerConfig.create(
                        exposure =
                            ConsoleExposurePolicy.localhostDevelopment(
                                bindPort = config.bindPort,
                                browserPort = AndroidConsoleRuntimeConfig.FORWARDED_BROWSER_PORT,
                            ),
                        webRoot = config.webRoot.toPath(),
                        startupTimeoutMillis = config.serverStartupTimeoutMillis,
                    ),
                    controller,
                )
            serverForCleanup = server
            val telemetryListener =
                TelemetryListener { telemetry ->
                    protocol.publishTelemetry(snapshots.mapTelemetry(telemetry))
                }
            lateinit var publishRuntimeState: () -> Unit
            publishRuntimeState = {
                val mockRuntimeState = mockReadySnapshots.runtimeState()
                // Update the server-owned gate before publishing browser-visible state. A raw
                // socket client therefore cannot dispatch through an unlocked UI snapshot alone.
                core.updateActuationReadiness(mockRuntimeState.toActuationReadiness())
                protocol.publishRuntimeState(mockRuntimeState)
            }
            val connectionListener = ConnectionListener { publishRuntimeState() }
            val actuationListener = FlightControlPortSnapshotListener { publishRuntimeState() }

            return Components(
                server = server,
                core = core,
                executor = executor,
                scheduler = scheduler,
                audit = audit,
                agent = agent,
                telemetryListener = telemetryListener,
                connectionListener = connectionListener,
                actuationListener = actuationListener,
                visionSession = visionSession,
                publishRuntimeState = publishRuntimeState,
                prewarmCapabilitySnapshot = { snapshots.capabilitySnapshot() },
                monotonicNanos = monotonicNanos,
            )
        } catch (failure: Throwable) {
            var cleanupFailed = false
            runCatching { serverForCleanup?.closeWithin(500L) }.onFailure { cleanupFailed = true }
            runCatching { coreForCleanup?.close() }.onFailure { cleanupFailed = true }
            if (
                coreForCleanup != null &&
                    !runCatching { coreForCleanup.awaitShutdownNeutral(500L) }
                        .onFailure { cleanupFailed = true }
                        .getOrDefault(false)
            ) {
                cleanupFailed = true
            }
            runCatching { executor.close() }.onFailure { cleanupFailed = true }
            runCatching { scheduler.close() }.onFailure { cleanupFailed = true }
            runCatching { visionSessionForCleanup?.awaitClosed() }.onFailure { cleanupFailed = true }
            runCatching { audit.close() }.onFailure { cleanupFailed = true }
            runCatching { agent.shutdown() }.onFailure { cleanupFailed = true }
            synchronized(lifecycleLock) {
                terminalCloseResult =
                    if (cleanupFailed) RuntimeCloseResult.FAILED else RuntimeCloseResult.CLOSED
            }
            throw failure
        }
    }

    private fun ensureStartupMayContinue(created: Components) {
        if (stopRequested.get()) {
            created.closeActuationAdmission()
            throw IllegalStateException("headless runtime startup was cancelled")
        }
    }

    private fun reportStartupFailure(phase: StartupPhase) {
        // Bounded enum only: never emit exception text, paths, credentials, or identifiers.
        val outcome = if (stopRequested.get()) "runtime_start_cancelled" else "runtime_start_failed"
        runCatching { Log.e(LOG_TAG, "$outcome phase=${phase.wireName}") }
    }

    private inline fun <T> runStartupPhase(
        phase: StartupPhase,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: Throwable) {
            reportStartupFailure(phase)
            throw failure
        }

    private enum class StartupPhase(val wireName: String) {
        COMPONENTS("components"),
        LISTENERS("listeners"),
        AGENT_CONNECT("agent_connect"),
        VISION_START("vision_start"),
        LOCKED_RUNTIME_STATE("locked_runtime_state"),
        CONNECTOR("connector"),
        CAPABILITY_PREWARM("capability_prewarm"),
        STARTUP_PENDING("startup_pending"),
        MOCK_READY("mock_ready"),
    }

    private enum class State {
        NEW,
        STARTING,
        STARTUP_PENDING,
        COMPLETING_STARTUP,
        RUNNING,
        CLOSING,
        CLOSE_INCOMPLETE,
        CLOSED,
    }

    private class Components(
        val server: KtorConsoleServer,
        val core: ConsoleServerCore,
        val executor: MockConsoleCommandExecutor,
        val scheduler: JdkConsoleDeadlineScheduler,
        val audit: FileConsoleAuditSink,
        val agent: MockDroneAgent,
        private val telemetryListener: TelemetryListener,
        private val connectionListener: ConnectionListener,
        private val actuationListener: FlightControlPortSnapshotListener,
        private val visionSession: HeadlessOpenCvObservationSession?,
        val publishRuntimeState: () -> Unit,
        private val prewarmCapabilitySnapshot: () -> Unit,
        monotonicNanos: () -> Long,
    ) {
        private val listenersInstalled = AtomicBoolean(false)
        private val closer =
            AndroidRuntimeCloser(
                monotonicNanos = monotonicNanos,
                stopServerWithin = server::closeWithin,
                initiateCoreStop = core::close,
                awaitNeutral = core::awaitShutdownNeutral,
                cleanupAfterNeutral = ::cleanupAfterNeutral,
            )

        fun installListeners() {
            check(listenersInstalled.compareAndSet(false, true)) { "listeners already installed" }
            agent.telemetry.addListener(telemetryListener)
            agent.connection.addListener(connectionListener)
            agent.actuation.addSnapshotListener(actuationListener)
        }

        fun closeWithin(timeoutMillis: Long): RuntimeCloseResult = closer.closeWithin(timeoutMillis)

        fun prewarmCapabilitySnapshot() {
            prewarmCapabilitySnapshot.invoke()
        }

        fun startVisionObservation() {
            val result = visionSession?.start() ?: return
            if (result is OpenCvObservationStartResult.Failure) {
                // Bounded enum only. Vision remains observation-only and unavailable; console
                // authority is neither granted nor revoked by recognition availability.
                runCatching {
                    Log.w(LOG_TAG, "vision_start_unavailable reason=${result.reason.name.lowercase()}")
                }
            }
        }

        fun closeActuationAdmission() {
            core.closeActuationAdmission()
            visionSession?.requestStop()
        }

        private fun cleanupAfterNeutral() {
            val visionStop =
                visionSession?.stopWithin(VISION_CLOSE_ATTEMPT_MILLIS)
                    ?: OpenCvObservationStopResult.CLOSED
            check(visionStop == OpenCvObservationStopResult.CLOSED) {
                "OpenCV observation cleanup is incomplete: $visionStop"
            }

            var firstFailure: Throwable? = null
            fun attempt(block: () -> Unit) {
                runCatching(block).onFailure { failure ->
                    if (firstFailure == null) firstFailure = failure
                }
            }

            // Preserve the reviewed order: core neutral evidence lands before executor/audit and
            // adapter ownership are released.
            // Vision stop can block on a vendor source or native frame; this method already runs
            // on AndroidRuntimeCloser's bounded cleanup daemon, never the Android main thread.
            attempt { executor.close() }
            attempt { scheduler.close() }
            if (listenersInstalled.get()) {
                attempt { agent.telemetry.removeListener(telemetryListener) }
                attempt { agent.connection.removeListener(connectionListener) }
                attempt { agent.actuation.removeSnapshotListener(actuationListener) }
            }
            attempt { audit.close() }
            attempt { agent.shutdown() }
            firstFailure?.let { throw it }
        }
    }

    private companion object {
        const val AGENT_ID = "mock-android"
        const val STREAM_ID = "mock-android-main"
        const val SERVER_VERSION = "0.2.0-headless-mock"
        const val CLOSE_AFTER_START_FAILURE_MILLIS = 2_000L
        const val VISION_CLOSE_ATTEMPT_MILLIS = 2_500L
        const val LOG_TAG = "DroneCompanionHost"
    }
}

internal class MockReadyGatedConsoleSnapshotProvider(
    private val delegate: ConsoleSnapshotProvider,
    private val mockReady: AtomicBoolean,
    private val stopRequested: AtomicBoolean,
) : ConsoleSnapshotProvider by delegate {
    override fun runtimeState() =
        delegate.runtimeState().let { runtimeState ->
            if (stopRequested.get() || !mockReady.get()) {
                runtimeState.copy(actuationLock = ActuationLockState.LOCKED)
            } else {
                runtimeState
            }
        }
}
