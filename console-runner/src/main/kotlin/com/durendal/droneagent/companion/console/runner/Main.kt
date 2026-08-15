package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.actuation.FlightControlPortSnapshotListener
import com.durendal.droneagent.adapter.mock.MockDroneAgent
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ConsoleRunnerProfile {
    const val BIND_HOST: String = "127.0.0.1"
    const val BIND_PORT: Int = 8080
    const val AGENT_ID: String = "mock"
    const val STREAM_ID: String = "mock-main"
    const val SERVER_VERSION: String = "0.1.0"
}

data class ConsoleRunnerConfig(
    val webRoot: Path,
    val auditPath: Path,
) {
    companion object {
        fun from(args: Array<String>): ConsoleRunnerConfig {
            require(args.size <= 2) { "usage: [web-console-dist] [audit-jsonl]" }
            val workingDirectory = Path.of("").toAbsolutePath().normalize()
            return ConsoleRunnerConfig(
                webRoot =
                    workingDirectory.resolve(args.getOrNull(0) ?: "web-console/dist").normalize(),
                auditPath =
                    workingDirectory.resolve(
                        args.getOrNull(1) ?: ".drone-agent-companion/audit/console-events.jsonl",
                    ).normalize(),
            )
        }
    }
}

/** Mac/JDK composition root for the visible Weekend MVP. All aircraft behavior is simulation. */
fun main(args: Array<String>) {
    val config = ConsoleRunnerConfig.from(args)
    require(Files.isDirectory(config.webRoot)) {
        "Web console build is missing at ${config.webRoot}; run the Gradle run task or npm build"
    }

    val monotonicClock = ConsoleMonotonicClock(System::nanoTime)
    val epochClock = ConsoleEpochClock(System::currentTimeMillis)
    val agent = MockDroneAgent()
    val returnToHome = ObservableMockReturnToHomePort()
    val executor = MockConsoleCommandExecutor(agent, monotonicClock, returnToHome)
    val scheduler = JdkConsoleDeadlineScheduler(monotonicClock)
    val audit = FileConsoleAuditSink(config.auditPath)
    val snapshots = MockConsoleSnapshotProvider(agent, returnToHome)
    val coreReference = AtomicReference<ConsoleServerCore>()
    val controllerReference = AtomicReference<ProtocolConsoleSocketController>()
    val protocol =
        ConsoleCoreProtocolAdapter(
            coreProvider = { checkNotNull(coreReference.get()) { "console core not attached" } },
            snapshots = snapshots,
            serverVersion = ConsoleRunnerProfile.SERVER_VERSION,
            emitPayload = { target, payload ->
                controllerReference.get()?.emit(target, payload) ?: false
            },
        )
    val core =
        ConsoleServerCore(
            config = ConsoleServerCoreConfig(),
            admission =
                ConsoleCommandAdmission(
                    agentId = ConsoleRunnerProfile.AGENT_ID,
                    streamId = ConsoleRunnerProfile.STREAM_ID,
                    epochClock = epochClock,
                ),
            executor = executor,
            monotonicClock = monotonicClock,
            epochClock = epochClock,
            scheduler = scheduler,
            auditSink = audit,
            eventSink = protocol,
        )
    coreReference.set(core)
    val controller = ProtocolConsoleSocketController(protocol)
    controllerReference.set(controller)
    val server =
        KtorConsoleServer(
            ConsoleServerConfig(
                bindHost = ConsoleRunnerProfile.BIND_HOST,
                bindPort = ConsoleRunnerProfile.BIND_PORT,
                webRoot = config.webRoot,
            ),
            controller,
        )

    val telemetryListener =
        TelemetryListener { telemetry -> protocol.publishTelemetry(snapshots.mapTelemetry(telemetry)) }
    val publishRuntimeState = {
        val runtimeState = snapshots.runtimeState()
        // Safety gate first, browser observation second. A raw WebSocket client cannot race the
        // UI snapshot and dispatch through a stale unlocked state.
        core.updateActuationReadiness(runtimeState.toActuationReadiness())
        protocol.publishRuntimeState(runtimeState)
    }
    val connectionListener = ConnectionListener { publishRuntimeState() }
    val actuationListener =
        FlightControlPortSnapshotListener { publishRuntimeState() }
    agent.telemetry.addListener(telemetryListener)
    agent.connection.addListener(connectionListener)
    agent.actuation.addSnapshotListener(actuationListener)

    val runtime =
        ConsoleRunnerRuntime(
            server = server,
            core = core,
            executor = executor,
            scheduler = scheduler,
            audit = audit,
            agent = agent,
            telemetryListener = telemetryListener,
            connectionListener = connectionListener,
            actuationListener = actuationListener,
        )
    val shutdownHook = Thread(runtime::close, "console-runner-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    try {
        agent.connection.connect()
        publishRuntimeState()
        agent.telemetry.start()
        println(
            "[console-runner] adapter=mock profile=localhost_development " +
                "http://${ConsoleRunnerProfile.BIND_HOST}:${ConsoleRunnerProfile.BIND_PORT}",
        )
        println("[console-runner] audit=${config.auditPath}")
        server.start(wait = true)
    } finally {
        runtime.close()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}

private class ConsoleRunnerRuntime(
    private val server: KtorConsoleServer,
    private val core: ConsoleServerCore,
    private val executor: MockConsoleCommandExecutor,
    private val scheduler: JdkConsoleDeadlineScheduler,
    private val audit: FileConsoleAuditSink,
    private val agent: MockDroneAgent,
    private val telemetryListener: TelemetryListener,
    private val connectionListener: ConnectionListener,
    private val actuationListener: FlightControlPortSnapshotListener,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { core.close() }
        val neutralConfirmed = runCatching { core.awaitShutdownNeutral(2_000L) }.getOrDefault(false)
        if (!neutralConfirmed) {
            System.err.println(
                "[console-runner] server-stop neutral was not confirmed before timeout; " +
                    "executor close will issue its final adapter neutral",
            )
        }
        runCatching { executor.close() }
        runCatching { scheduler.close() }
        agent.telemetry.removeListener(telemetryListener)
        agent.connection.removeListener(connectionListener)
        agent.actuation.removeSnapshotListener(actuationListener)
        runCatching { audit.close() }
        runCatching { agent.shutdown() }
    }
}
