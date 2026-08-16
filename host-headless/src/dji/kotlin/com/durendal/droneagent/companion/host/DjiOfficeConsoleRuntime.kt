package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.console.dji.DjiConsoleSnapshotProvider
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.server.ConsoleActuationIntent
import com.durendal.droneagent.companion.console.server.ConsoleActuationObservation
import com.durendal.droneagent.companion.console.server.ConsoleCommandAdmission
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleCommissioningStartDecision
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleServerCore
import com.durendal.droneagent.companion.console.server.ConsoleServerCoreConfig
import com.durendal.droneagent.companion.console.server.FileConsoleAuditSink
import com.durendal.droneagent.companion.console.server.JdkConsoleDeadlineScheduler
import com.durendal.droneagent.companion.console.server.security.ConsoleExposurePolicy
import com.durendal.droneagent.companion.console.server.toActuationReadiness
import com.durendal.droneagent.companion.console.server.transport.ConsoleCoreProtocolAdapter
import com.durendal.droneagent.companion.console.server.transport.ConsoleMediaPlaybackConfig
import com.durendal.droneagent.companion.console.server.transport.ConsoleMediaSourceKind
import com.durendal.droneagent.companion.console.server.transport.ConsoleProtocolEndpoint
import com.durendal.droneagent.companion.console.server.transport.ConsoleServerConfig
import com.durendal.droneagent.companion.console.server.transport.KtorConsoleServer
import com.durendal.droneagent.companion.console.server.transport.ProtocolConsoleSocketController
import com.durendal.droneagent.core.connection.ConnectionListener
import com.durendal.droneagent.core.telemetry.TelemetryListener
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Stable listener identity is required so the runtime can remove it before releasing the agent. */
internal fun interface DjiConsoleActuationReadinessListener {
    fun onActuationReadinessChanged()
}

/**
 * Host-local event seam for the executor's complete actuation preflight.
 *
 * Connection state alone is insufficient: the first complete fresh-neutral sample can arrive
 * after MSDK reports AIRCRAFT_CONNECTED. An executor supplied to [DjiOfficeConsoleRuntime] must
 * also implement this interface or the production Core observation remains fail-closed.
 */
internal interface DjiConsoleActuationReadiness {
    fun isReady(): Boolean

    fun addListener(listener: DjiConsoleActuationReadinessListener)

    fun removeListener(listener: DjiConsoleActuationReadinessListener)
}

/**
 * Production composition factory for the isolated G520 office commissioning network.
 *
 * The browser never receives a commissioning mutation API. After a strict, successful
 * [ClientHelloPayload], this host-owned endpoint makes one bounded attempt to grant that exact
 * transport session all four actuation intents for five minutes. The public runtime projection
 * remains LOCKED throughout; effective authority continues to live exclusively in
 * [ConsoleServerCore].
 */
internal class DjiOfficeConsoleRuntime(
    private val executorFactory:
        (DjiConsoleRuntimeSession, ConsoleMonotonicClock) -> ConsoleCommandExecutor,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val serverStartupTimeoutMillis: Long = DEFAULT_SERVER_STARTUP_TIMEOUT_MILLIS,
) : DjiConsoleRuntimeFactory {
    init {
        require(serverStartupTimeoutMillis in 100L..60_000L) {
            "serverStartupTimeoutMillis must be between 100 and 60000"
        }
    }

    override fun create(session: DjiConsoleRuntimeSession): HeadlessRuntime =
        SessionRuntime(
            session = session,
            executorFactory = executorFactory,
            epochMillis = epochMillis,
            serverStartupTimeoutMillis = serverStartupTimeoutMillis,
        )

    private class SessionRuntime(
        private val session: DjiConsoleRuntimeSession,
        private val executorFactory:
            (DjiConsoleRuntimeSession, ConsoleMonotonicClock) -> ConsoleCommandExecutor,
        private val epochMillis: () -> Long,
        private val serverStartupTimeoutMillis: Long,
    ) : HeadlessRuntime {
        private val lifecycleLock = Any()
        private val stopRequested = AtomicBoolean(false)
        private val startupCommitted = AtomicBoolean(false)
        private var state = State.NEW
        @Volatile private var startingComponents: Components? = null
        @Volatile private var components: Components? = null
        private var terminalCloseResult: RuntimeCloseResult? = null

        override fun requestStop() {
            stopRequested.set(true)
            startupCommitted.set(false)
            startingComponents?.closeActuationAdmission()
            components?.closeActuationAdmission()
        }

        override fun start() {
            synchronized(lifecycleLock) {
                check(state == State.NEW) { "DJI office console runtime may be started only once" }
                check(!stopRequested.get()) { "DJI office console startup was cancelled" }
                check(session.webRoot.isDirectory) {
                    "packaged web console is unavailable at ${session.webRoot.absolutePath}"
                }
                validateOfficeEthernet(session.ethernet)
                state = State.STARTING
            }

            val created =
                try {
                    createComponents()
                } catch (failure: Throwable) {
                    synchronized(lifecycleLock) {
                        state = State.CLOSED
                        terminalCloseResult = RuntimeCloseResult.FAILED
                    }
                    throw failure
                }
            startingComponents = created
            try {
                ensureStartupMayContinue(created)
                created.installListeners()
                ensureStartupMayContinue(created)
                created.publishRuntimeTruth()
                ensureStartupMayContinue(created)
                created.prewarmCapabilitySnapshot()
                ensureStartupMayContinue(created)
                created.startServer()
                ensureStartupMayContinue(created)
                synchronized(lifecycleLock) {
                    check(!stopRequested.get()) { "DJI office console startup was cancelled" }
                    components = created
                    startingComponents = null
                    state = State.STARTUP_PENDING
                }
            } catch (failure: Throwable) {
                requestStop()
                val cleanupResult = created.closeWithin(CLOSE_AFTER_START_FAILURE_MILLIS)
                synchronized(lifecycleLock) {
                    startingComponents = null
                    if (cleanupResult == RuntimeCloseResult.CLOSED) {
                        terminalCloseResult = RuntimeCloseResult.CLOSED
                        state = State.CLOSED
                    } else {
                        components = created
                        state = State.CLOSE_INCOMPLETE
                    }
                }
                throw failure
            }
        }

        override fun completeStartup() {
            val completing =
                synchronized(lifecycleLock) {
                    check(state == State.STARTUP_PENDING) {
                        "DJI office console runtime is not awaiting startup completion"
                    }
                    check(!stopRequested.get()) { "DJI office console startup was cancelled" }
                    state = State.COMPLETING_STARTUP
                    checkNotNull(components) { "DJI office console components are unavailable" }
                }

            try {
                ensureStartupMayContinue(completing)
                completing.startTelemetry()
                ensureStartupMayContinue(completing)
                startupCommitted.set(true)
                completing.publishRuntimeTruth()
                ensureStartupMayContinue(completing)
                synchronized(lifecycleLock) {
                    check(!stopRequested.get()) { "DJI office console startup was cancelled" }
                    state = State.RUNNING
                }
            } catch (failure: Throwable) {
                startupCommitted.set(false)
                runCatching { completing.publishRuntimeTruth() }
                completing.closeActuationAdmission()
                synchronized(lifecycleLock) {
                    if (state == State.COMPLETING_STARTUP) state = State.STARTUP_PENDING
                }
                throw failure
            }
        }

        override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
            require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
            requestStop()
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
                        State.RUNNING,
                        State.CLOSE_INCOMPLETE,
                        -> {
                            state = State.CLOSING
                            components.also { components = null }
                        }
                        State.CLOSING -> return RuntimeCloseResult.FAILED
                    }
                } ?: return RuntimeCloseResult.FAILED

            val result = closing.closeWithin(timeoutMillis)
            synchronized(lifecycleLock) {
                if (result == RuntimeCloseResult.CLOSED) {
                    terminalCloseResult = result
                    state = State.CLOSED
                } else {
                    components = closing
                    state = State.CLOSE_INCOMPLETE
                }
            }
            return result
        }

        private fun createComponents(): Components {
            val monotonicClock = ConsoleMonotonicClock(session.monotonicNanos)
            val epochClock = ConsoleEpochClock(epochMillis)
            val scheduler = JdkConsoleDeadlineScheduler(monotonicClock)
            var audit: FileConsoleAuditSink? = null
            var executor: ConsoleCommandExecutor? = null
            var core: ConsoleServerCore? = null
            var server: KtorConsoleServer? = null
            try {
                val createdAudit = FileConsoleAuditSink(session.auditFile.toPath())
                audit = createdAudit
                val createdExecutor = executorFactory(session, monotonicClock)
                executor = createdExecutor
                val actuationReadiness = createdExecutor as? DjiConsoleActuationReadiness
                val snapshots =
                    DjiConsoleSnapshotProvider(
                        agent = session.agent,
                        monotonicClockNanos = session.monotonicNanos,
                    )
                val coreReference = AtomicReference<ConsoleServerCore>()
                val controllerReference = AtomicReference<ProtocolConsoleSocketController>()
                val requiredProtocol =
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
                val createdCore =
                    ConsoleServerCore(
                        config = ConsoleServerCoreConfig(),
                        admission =
                            ConsoleCommandAdmission(
                                agentId = session.agent.id,
                                streamId = DJI_STREAM_ID,
                                epochClock = epochClock,
                            ),
                        executor = createdExecutor,
                        monotonicClock = monotonicClock,
                        epochClock = epochClock,
                        scheduler = scheduler,
                        auditSink = createdAudit,
                        eventSink = requiredProtocol,
                    )
                core = createdCore
                coreReference.set(createdCore)
                val officeProtocol =
                    OfficeCommissioningProtocolEndpoint(
                        delegate = requiredProtocol,
                        coreProvider = { createdCore },
                    )
                val controller = ProtocolConsoleSocketController(officeProtocol)
                controllerReference.set(controller)
                val createdServer =
                    KtorConsoleServer(
                        ConsoleServerConfig.create(
                            exposure = ConsoleExposurePolicy.officeMvpPointToPoint(),
                            webRoot = session.webRoot.toPath(),
                            mediaPlayback =
                                ConsoleMediaPlaybackConfig(
                                    origin = DJI_MEDIA_ORIGIN,
                                    streamId = DJI_STREAM_ID,
                                    source = ConsoleMediaSourceKind.DJI_MSDK,
                                ),
                            startupTimeoutMillis = serverStartupTimeoutMillis,
                        ),
                        controller,
                    )
                server = createdServer
                lateinit var components: Components
                val publishRuntimeTruth = {
                    runCatching { components.publishRuntimeTruth() }
                        .onFailure { components.closeActuationAdmission() }
                    Unit
                }
                val connectionListener = ConnectionListener { publishRuntimeTruth() }
                val actuationReadinessListener =
                    DjiConsoleActuationReadinessListener { publishRuntimeTruth() }
                val telemetryListener = TelemetryListener {
                    runCatching {
                        snapshots.latestTelemetry()?.let(requiredProtocol::publishTelemetry)
                    }
                }
                components =
                    Components(
                        session = session,
                        server = createdServer,
                        core = createdCore,
                        protocol = requiredProtocol,
                        commissioningEndpoint = officeProtocol,
                        snapshots = snapshots,
                        executor = createdExecutor,
                        scheduler = scheduler,
                        audit = createdAudit,
                        connectionListener = connectionListener,
                        telemetryListener = telemetryListener,
                        actuationReadiness = actuationReadiness,
                        actuationReadinessListener = actuationReadinessListener,
                        stopRequested = stopRequested,
                        startupCommitted = startupCommitted,
                    )
                return components
            } catch (failure: Throwable) {
                runCatching { server?.closeWithin(PARTIAL_CLOSE_MILLIS) }
                val neutralConfirmed =
                    core?.let { createdCore ->
                        runCatching { createdCore.close() }
                        runCatching { createdCore.awaitShutdownNeutral(PARTIAL_CLOSE_MILLIS) }
                            .getOrDefault(false)
                    } ?: true
                if (neutralConfirmed) {
                    closeExecutor(executor)
                    runCatching { scheduler.close() }
                    runCatching { audit?.close() }
                }
                throw failure
            }
        }

        private fun ensureStartupMayContinue(created: Components) {
            if (stopRequested.get()) {
                created.closeActuationAdmission()
                throw IllegalStateException("DJI office console startup was cancelled")
            }
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
    }

    private class Components(
        private val session: DjiConsoleRuntimeSession,
        private val server: KtorConsoleServer,
        private val core: ConsoleServerCore,
        private val protocol: ConsoleProtocolEndpoint,
        private val commissioningEndpoint: OfficeCommissioningProtocolEndpoint,
        private val snapshots: DjiConsoleSnapshotProvider,
        private val executor: ConsoleCommandExecutor,
        private val scheduler: JdkConsoleDeadlineScheduler,
        private val audit: FileConsoleAuditSink,
        private val connectionListener: ConnectionListener,
        private val telemetryListener: TelemetryListener,
        private val actuationReadiness: DjiConsoleActuationReadiness?,
        private val actuationReadinessListener: DjiConsoleActuationReadinessListener,
        private val stopRequested: AtomicBoolean,
        private val startupCommitted: AtomicBoolean,
    ) {
        private val connectionListenerInstalled = AtomicBoolean(false)
        private val telemetryListenerInstalled = AtomicBoolean(false)
        private val actuationReadinessListenerInstalled = AtomicBoolean(false)
        private val telemetryStarted = AtomicBoolean(false)
        private val serverStarted = AtomicBoolean(false)
        private val closer =
            OfficeRuntimeCloser(
                monotonicNanos = session.monotonicNanos,
                stopServerWithin = { timeoutMillis ->
                    if (serverStarted.get()) server.closeWithin(timeoutMillis)
                },
                initiateCoreStop = core::close,
                awaitNeutral = core::awaitShutdownNeutral,
                cleanupAfterNeutral = ::cleanupAfterNeutral,
            )

        fun installListeners() {
            check(connectionListenerInstalled.compareAndSet(false, true)) {
                "DJI connection listener is already installed"
            }
            try {
                session.agent.connection.addListener(connectionListener)
                check(telemetryListenerInstalled.compareAndSet(false, true)) {
                    "DJI telemetry listener is already installed"
                }
                session.agent.telemetry.addListener(telemetryListener)
                actuationReadiness?.let { readiness ->
                    check(actuationReadinessListenerInstalled.compareAndSet(false, true)) {
                        "DJI actuation-readiness listener is already installed"
                    }
                    readiness.addListener(actuationReadinessListener)
                }
            } catch (failure: Throwable) {
                if (actuationReadinessListenerInstalled.get()) {
                    runCatching { actuationReadiness?.removeListener(actuationReadinessListener) }
                }
                if (telemetryListenerInstalled.get()) {
                    runCatching { session.agent.telemetry.removeListener(telemetryListener) }
                }
                if (connectionListenerInstalled.get()) {
                    runCatching { session.agent.connection.removeListener(connectionListener) }
                }
                throw failure
            }
        }

        fun startTelemetry() {
            check(telemetryStarted.compareAndSet(false, true)) {
                "DJI telemetry is already started by the console runtime"
            }
            session.agent.telemetry.start()
        }

        fun publishRuntimeTruth() {
            val runtimeState = snapshots.runtimeState()
            core.updateActuationReadiness(runtimeState.toActuationReadiness())
            val ready =
                !stopRequested.get() &&
                    startupCommitted.get() &&
                    runtimeState.aircraftConnection == AircraftConnectionState.CONNECTED &&
                    runCatching { actuationReadiness?.isReady() == true }.getOrDefault(false)
            val observationAccepted =
                core.updateActuationObservation(
                    ConsoleActuationObservation(
                        adapter = runtimeState.adapter,
                        aircraftConnection = runtimeState.aircraftConnection,
                        adapterActuationReady = ready,
                        operatingProfile = runtimeState.operatingProfile,
                    ),
                )
            protocol.publishRuntimeState(runtimeState)
            commissioningEndpoint.onRuntimeObservationPublished(observationAccepted && ready)
        }

        fun prewarmCapabilitySnapshot() {
            snapshots.capabilitySnapshot()
        }

        fun startServer() {
            server.start(wait = false)
            serverStarted.set(true)
        }

        fun closeActuationAdmission() {
            core.closeActuationAdmission()
        }

        fun closeWithin(timeoutMillis: Long): RuntimeCloseResult = closer.closeWithin(timeoutMillis)

        private fun cleanupAfterNeutral() {
            var firstFailure: Throwable? = null
            fun attempt(block: () -> Unit) {
                runCatching(block).onFailure { failure ->
                    if (firstFailure == null) firstFailure = failure
                }
            }

            closeExecutor(executor) { failure ->
                if (firstFailure == null) firstFailure = failure
            }
            attempt { scheduler.close() }
            if (actuationReadinessListenerInstalled.get()) {
                attempt { actuationReadiness?.removeListener(actuationReadinessListener) }
            }
            if (telemetryListenerInstalled.get()) {
                attempt { session.agent.telemetry.removeListener(telemetryListener) }
            }
            if (connectionListenerInstalled.get()) {
                attempt { session.agent.connection.removeListener(connectionListener) }
            }
            if (telemetryStarted.get()) {
                attempt { session.agent.telemetry.stop() }
            }
            attempt { audit.close() }
            firstFailure?.let { throw it }
        }
    }

    /**
     * Opaque host decorator: no HTTP or console message maps to the commissioning lifecycle.
     * A hello observed before the durable host startup commit remains pending and is retried once
     * when the adapter observation first becomes ready; the grant is never renewed after expiry.
     */
    private class OfficeCommissioningProtocolEndpoint(
        private val delegate: ConsoleProtocolEndpoint,
        private val coreProvider: () -> ConsoleServerCore,
    ) : ConsoleProtocolEndpoint by delegate {
        private val lock = Any()
        private val selectedVersions = mutableMapOf<String, String>()
        private val greetedSessions = linkedSetOf<String>()
        private val attemptedWhileReady = mutableSetOf<String>()

        override fun onProtocolSelected(
            sessionId: String,
            selectedProtocolVersion: String,
        ) {
            delegate.onProtocolSelected(sessionId, selectedProtocolVersion)
            synchronized(lock) {
                check(selectedVersions.putIfAbsent(sessionId, selectedProtocolVersion) == null) {
                    "office protocol version is already selected"
                }
            }
        }

        override fun onClientMessage(
            sessionId: String,
            message: ConsoleClientMessage,
        ) {
            delegate.onClientMessage(sessionId, message)
            if (message.payload is ClientHelloPayload) {
                val commissioningCapable = synchronized(lock) {
                    selectedVersions[sessionId] ==
                        ConsoleProtocolModule.COMMISSIONING_AUTHORITY_PROTOCOL_VERSION
                }
                if (!commissioningCapable) return
                synchronized(lock) { greetedSessions += sessionId }
                attemptCommissioning(sessionId)
            }
        }

        override fun onSessionClosed(
            sessionId: String,
            reason: String,
        ) {
            synchronized(lock) {
                selectedVersions -= sessionId
                greetedSessions -= sessionId
                attemptedWhileReady -= sessionId
            }
            delegate.onSessionClosed(sessionId, reason)
        }

        fun onRuntimeObservationPublished(adapterReady: Boolean) {
            if (!adapterReady) return
            val candidates = synchronized(lock) { greetedSessions.toList() }
            candidates.forEach(::attemptCommissioning)
        }

        private fun attemptCommissioning(sessionId: String) {
            val mayAttempt =
                synchronized(lock) {
                    sessionId in greetedSessions && attemptedWhileReady.add(sessionId)
                }
            if (!mayAttempt) return
            val result =
                coreProvider().startHardwareCommissioning(
                    operatorSessionId = sessionId,
                    allowedIntents = ALL_COMMISSIONING_INTENTS,
                    ttlMillis = OFFICE_COMMISSIONING_TTL_MILLIS,
                )
            if (result.decision == ConsoleCommissioningStartDecision.OBSERVATION_NOT_READY) {
                synchronized(lock) {
                    if (sessionId in greetedSessions) attemptedWhileReady -= sessionId
                }
            }
        }
    }

    /** One caller-owned deadline for connector stop, Core neutral, and resource cleanup. */
    private class OfficeRuntimeCloser(
        private val monotonicNanos: () -> Long,
        private val stopServerWithin: (Long) -> Unit,
        private val initiateCoreStop: () -> Unit,
        private val awaitNeutral: (Long) -> Boolean,
        private val cleanupAfterNeutral: () -> Unit,
        private val cleanupExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "dji-office-console-cleanup").apply { isDaemon = true }
            },
    ) {
        private var stopInitiated = false
        private var closeFailed = false
        private var cleanupFuture: Future<Unit>? = null
        private var terminalResult: RuntimeCloseResult? = null

        @Synchronized
        fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
            require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
            terminalResult?.let { return it }
            val deadlineNanos = deadlineAfter(monotonicNanos(), timeoutMillis)

            if (!stopInitiated) {
                stopInitiated = true
                val serverBudget =
                    (remainingMillis(deadlineNanos) / 4L)
                        .coerceIn(1L, MAX_SERVER_CLOSE_MILLIS)
                var interrupted = false
                try {
                    stopServerWithin(serverBudget)
                } catch (_: InterruptedException) {
                    interrupted = true
                    closeFailed = true
                } catch (_: Throwable) {
                    closeFailed = true
                }
                try {
                    initiateCoreStop()
                } catch (_: InterruptedException) {
                    interrupted = true
                    closeFailed = true
                } catch (_: Throwable) {
                    closeFailed = true
                }
                if (interrupted) {
                    Thread.currentThread().interrupt()
                    return RuntimeCloseResult.TIMED_OUT
                }
            }

            var cleanup = cleanupFuture
            if (cleanup == null) {
                val neutralConfirmed =
                    try {
                        awaitNeutral(remainingMillis(deadlineNanos).coerceAtLeast(1L))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return RuntimeCloseResult.TIMED_OUT
                    } catch (_: Throwable) {
                        false
                    }
                if (!neutralConfirmed) return RuntimeCloseResult.TIMED_OUT
                cleanup = cleanupExecutor.submit(Callable { cleanupAfterNeutral() })
                cleanupFuture = cleanup
            }

            return try {
                checkNotNull(cleanup).get(
                    remainingMillis(deadlineNanos).coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                )
                complete(if (closeFailed) RuntimeCloseResult.FAILED else RuntimeCloseResult.CLOSED)
            } catch (_: TimeoutException) {
                RuntimeCloseResult.TIMED_OUT
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                RuntimeCloseResult.TIMED_OUT
            } catch (_: Throwable) {
                cleanupFuture = null
                RuntimeCloseResult.FAILED
            }
        }

        private fun complete(result: RuntimeCloseResult): RuntimeCloseResult {
            terminalResult = result
            cleanupExecutor.shutdown()
            return result
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = (deadlineNanos - monotonicNanos()).coerceAtLeast(0L)
            val wholeMillis = remainingNanos / NANOS_PER_MILLISECOND
            return wholeMillis + if (remainingNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
        }

        private fun deadlineAfter(
            nowNanos: Long,
            timeoutMillis: Long,
        ): Long {
            val timeoutNanos =
                if (timeoutMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
                    Long.MAX_VALUE
                } else {
                    timeoutMillis * NANOS_PER_MILLISECOND
                }
            return if (nowNanos > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else nowNanos + timeoutNanos
        }
    }

    private companion object {
        const val SERVER_VERSION = "0.3.0-g520-dji-office-mvp"
        const val DJI_STREAM_ID = "dji-main"
        const val DJI_MEDIA_ORIGIN = "http://10.52.0.1:8891"
        const val OFFICE_COMMISSIONING_TTL_MILLIS = 300_000L
        const val DEFAULT_SERVER_STARTUP_TIMEOUT_MILLIS = 10_000L
        const val CLOSE_AFTER_START_FAILURE_MILLIS = 3_000L
        const val PARTIAL_CLOSE_MILLIS = 1_000L
        const val MAX_SERVER_CLOSE_MILLIS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val ALL_COMMISSIONING_INTENTS: Set<ConsoleActuationIntent> =
            setOf(
                ConsoleActuationIntent.TAKEOFF,
                ConsoleActuationIntent.LANDING,
                ConsoleActuationIntent.RETURN_TO_HOME,
                ConsoleActuationIntent.VIRTUAL_STICK,
            )

        fun validateOfficeEthernet(binding: AndroidFixedEthernetBinding) {
            val accepted = binding.policyBinding
            check(binding.inventoryGeneration > 0L) { "Ethernet inventory generation is unavailable" }
            check(accepted.stableInterfaceId == AndroidFixedEthernetConfig.EXPECTED_INTERFACE_NAME)
            check(accepted.bindIpv4 == AndroidFixedEthernetConfig.G520_IPV4)
            check(accepted.operatorPeerIpv4 == AndroidFixedEthernetConfig.WINDOWS_IPV4)
            check(accepted.prefixLength == AndroidFixedEthernetConfig.PREFIX_LENGTH)
            check(accepted.httpOrigin == "http://${AndroidFixedEthernetConfig.G520_IPV4}:8080")
        }

        fun closeExecutor(executor: ConsoleCommandExecutor?) {
            runCatching { (executor as? AutoCloseable)?.close() }
        }

        fun closeExecutor(
            executor: ConsoleCommandExecutor,
            onFailure: (Throwable) -> Unit,
        ) {
            runCatching { (executor as? AutoCloseable)?.close() }.onFailure(onFailure)
        }
    }
}
