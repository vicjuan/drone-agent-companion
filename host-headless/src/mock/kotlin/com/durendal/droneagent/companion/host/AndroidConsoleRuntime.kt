package com.durendal.droneagent.companion.host

import com.durendal.droneagent.actuation.FlightControlPortSnapshotListener
import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.mock.MockConsoleCommandExecutor
import com.durendal.droneagent.companion.console.mock.MockConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.mock.ObservableMockReturnToHomePort
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.FileConsoleAuditSink
import com.durendal.droneagent.companion.console.server.JdkConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.toActuationReadiness
import com.durendal.droneagent.companion.console.server.transport.ConsoleCoreProtocolAdapter
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
    val bindHost: String = BIND_HOST,
    val bindPort: Int = BIND_PORT,
    val allowedBrowserOrigin: String = FORWARDED_BROWSER_ORIGIN,
    val serverStartupTimeoutMillis: Long = 10_000L,
) {
    init {
        require(bindHost == BIND_HOST) {
            "emulator mock host must remain bound to the IPv4 loopback interface"
        }
        require(bindPort in 1..65_535) { "bindPort must be between 1 and 65535" }
        require(allowedBrowserOrigin == FORWARDED_BROWSER_ORIGIN) {
            "emulator mock host must admit only the fixed adb-forward browser origin"
        }
        require(serverStartupTimeoutMillis in 100L..60_000L) {
            "serverStartupTimeoutMillis must be between 100 and 60000"
        }
    }

    companion object {
        const val BIND_HOST: String = "127.0.0.1"
        const val BIND_PORT: Int = 8080
        const val FORWARDED_BROWSER_ORIGIN: String = "http://127.0.0.1:18080"
    }
}

/**
 * Android composition of the already-reviewed console core and adapter-mock.
 *
 * This runtime deliberately exposes only the localhost development profile. It is not a DJI or
 * G520 commissioning composition, and none of its successes may promote the hardware capability
 * matrix. Hardware process-loss safety also requires a first-hand aircraft-side failsafe test;
 * an in-process foreground service cannot neutralize after its own process has died.
 */
class AndroidConsoleRuntime(
    private val config: AndroidConsoleRuntimeConfig,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) : HeadlessRuntime {
    private val lifecycleLock = Any()
    private var state = State.NEW
    private var components: Components? = null
    private var terminalCloseResult: RuntimeCloseResult? = null

    override fun start() {
        synchronized(lifecycleLock) {
            check(state == State.NEW) { "headless console runtime may be started only once" }
            check(config.webRoot.isDirectory) {
                "packaged web console is unavailable at ${config.webRoot.absolutePath}"
            }
            state = State.STARTING
        }

        val created =
            try {
                createComponents()
            } catch (failure: Throwable) {
                synchronized(lifecycleLock) {
                    state = State.CLOSED
                    if (terminalCloseResult == null) terminalCloseResult = RuntimeCloseResult.FAILED
                }
                throw failure
            }
        try {
            created.installListeners()
            created.agent.connection.connect()
            created.publishRuntimeState()
            created.agent.telemetry.start()
            created.server.start(wait = false)
        } catch (failure: Throwable) {
            val cleanupResult = created.closeWithin(CLOSE_AFTER_START_FAILURE_MILLIS)
            synchronized(lifecycleLock) {
                terminalCloseResult =
                    cleanupResult.takeUnless { it == RuntimeCloseResult.TIMED_OUT }
                components =
                    created.takeIf { cleanupResult == RuntimeCloseResult.TIMED_OUT }
                state =
                    if (cleanupResult == RuntimeCloseResult.TIMED_OUT) {
                        State.CLOSE_INCOMPLETE
                    } else {
                        State.CLOSED
                    }
            }
            throw failure
        }

        synchronized(lifecycleLock) {
            components = created
            state = State.RUNNING
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
            if (result == RuntimeCloseResult.TIMED_OUT) {
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
        val agent = MockDroneAgent()
        val returnToHome = ObservableMockReturnToHomePort()
        val executor = MockConsoleCommandExecutor(agent, monotonicClock, returnToHome)
        val snapshots = MockConsoleSnapshotProvider(agent, returnToHome)
        val scheduler = JdkConsoleDeadlineScheduler(monotonicClock)
        val audit =
            try {
                FileConsoleAuditSink(config.auditFile.toPath())
            } catch (failure: Throwable) {
                runCatching { executor.close() }
                runCatching { scheduler.close() }
                runCatching { agent.shutdown() }
                throw failure
            }
        val coreReference = AtomicReference<ConsoleServerCore>()
        val controllerReference = AtomicReference<ProtocolConsoleSocketController>()
        var coreForCleanup: ConsoleServerCore? = null
        var serverForCleanup: KtorConsoleServer? = null
        try {
            val protocol =
                ConsoleCoreProtocolAdapter(
                    coreProvider = {
                        checkNotNull(coreReference.get()) { "console core not attached" }
                    },
                    snapshots = snapshots,
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
                    ConsoleServerConfig(
                        bindHost = config.bindHost,
                        bindPort = config.bindPort,
                        webRoot = config.webRoot.toPath(),
                        allowedBrowserOrigin = config.allowedBrowserOrigin,
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
                val runtimeState = snapshots.runtimeState()
                // Update the server-owned gate before publishing browser-visible state. A raw
                // socket client therefore cannot dispatch through an unlocked UI snapshot alone.
                core.updateActuationReadiness(runtimeState.toActuationReadiness())
                protocol.publishRuntimeState(runtimeState)
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
                publishRuntimeState = publishRuntimeState,
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
            runCatching { audit.close() }.onFailure { cleanupFailed = true }
            runCatching { agent.shutdown() }.onFailure { cleanupFailed = true }
            synchronized(lifecycleLock) {
                terminalCloseResult =
                    if (cleanupFailed) RuntimeCloseResult.FAILED else RuntimeCloseResult.CLOSED
            }
            throw failure
        }
    }

    private enum class State {
        NEW,
        STARTING,
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
        val publishRuntimeState: () -> Unit,
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

        private fun cleanupAfterNeutral() {
            var firstFailure: Throwable? = null
            fun attempt(block: () -> Unit) {
                runCatching(block).onFailure { failure ->
                    if (firstFailure == null) firstFailure = failure
                }
            }

            // Preserve the reviewed order: core neutral evidence lands before executor/audit and
            // adapter ownership are released.
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
    }
}
