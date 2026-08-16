package com.durendal.droneagent.companion.host

import android.content.Context
import android.util.Log
import com.durendal.droneagent.adapter.dji.DjiDroneAgent
import com.durendal.droneagent.core.connection.ConnectionState
import com.durendal.droneagent.core.registration.RegistrationState
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Inputs handed to the future DJI console executor/snapshot composition. */
internal data class DjiConsoleRuntimeSession(
    val appContext: Context,
    val agent: DjiDroneAgent,
    val ethernet: AndroidFixedEthernetBinding,
    val webRoot: File,
    val auditFile: File,
    val monotonicNanos: () -> Long,
)

/**
 * Deliberate seam for the separately reviewed DJI command executor and snapshot mapper.
 * Returning null keeps the platform owner alive but opens no Ktor listener or command surface.
 */
internal fun interface DjiConsoleRuntimeFactory {
    fun create(session: DjiConsoleRuntimeSession): HeadlessRuntime?

    companion object {
        val UNAVAILABLE = DjiConsoleRuntimeFactory { null }
    }
}

/**
 * Production DJI platform lifecycle around the future console runtime.
 *
 * The MSDK agent, USB permission and live Android Network binding share one owner. USB or network
 * identity loss terminalizes the console generation; reattachment cannot ABA-reopen the old
 * command graph. Hardware commissioning authority is intentionally absent from this class.
 */
internal class DjiPlatformRuntime(
    context: Context,
    private val webRoot: File,
    private val auditFile: File,
    private val consoleRuntimeFactory: DjiConsoleRuntimeFactory,
    private val monotonicNanos: () -> Long,
) : HeadlessRuntime {
    private val appContext = context.applicationContext
    private val platformExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dji-platform-lifecycle").apply { isDaemon = true }
        }
    private val started = AtomicBoolean(false)
    private val startupCommitted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val consoleRuntime = AtomicReference<HeadlessRuntime?>(null)
    private val closeFuture = AtomicReference<CompletableFuture<RuntimeCloseResult>?>(null)

    private val agent = DjiDroneAgent(appContext)
    private val observationMedia =
        DjiObservationMediaLifecycle(agent) {
            enqueuePlatformAfter(MEDIA_RETRY_DELAY_MILLIS) { reconcilePlatformReadiness() }
        }
    private val msdkStartup =
        DjiMsdkStartupCoordinator(agent) { snapshot ->
            enqueuePlatform { onMsdkStartupChanged(snapshot) }
        }
    private val usbPermission =
        UsbAccessoryPermissionCoordinator(appContext) { snapshot ->
            enqueuePlatform { onUsbPermissionChanged(snapshot) }
        }
    private val ethernet =
        AndroidFixedEthernetCoordinator(appContext) { state ->
            enqueuePlatform { onEthernetChanged(state) }
        }

    // Accessed only on platformExecutor.
    private var usbPermissionGranted = false
    private var ethernetStarted = false
    private var ethernetWasBound = false
    private var ethernetBinding: AndroidFixedEthernetBinding? = null
    private var msdkSnapshot =
        DjiMsdkStartupSnapshot(
            phase = DjiMsdkStartupPhase.IDLE,
            registrationState = RegistrationState.NOT_REGISTERED,
            connectionState = ConnectionState.DISCONNECTED,
        )
    private var consoleFactoryAttempted = false
    private var consoleGenerationTerminal = false
    private var consoleNetworkHandle: Long? = null

    override fun start() {
        check(started.compareAndSet(false, true)) { "DJI platform runtime may be started only once" }
        check(webRoot.isDirectory) { "packaged web console is unavailable at ${webRoot.absolutePath}" }
        check(!stopRequested.get()) { "DJI platform runtime startup was cancelled" }
        try {
            Log.i(TAG, "Starting locked DJI platform owner")
            val initialMsdk = msdkStartup.start()
            val usbSnapshot = usbPermission.start()
            enqueuePlatform {
                onMsdkStartupChanged(initialMsdk)
                onUsbPermissionChanged(usbSnapshot)
            }
        } catch (failure: Throwable) {
            requestStop()
            runCatching { usbPermission.close() }
            runCatching { ethernet.close() }
            runCatching { observationMedia.closeWithin(OBSERVATION_CLOSE_MILLIS) }
            runCatching { msdkStartup.close() }
            throw failure
        }
    }

    override fun completeStartup() {
        check(started.get()) { "DJI platform runtime has not started" }
        check(!stopRequested.get()) { "DJI platform runtime startup was cancelled" }
        startupCommitted.set(true)
        Log.i(TAG, "Android host startup committed; hardware authority remains gated")
        enqueuePlatform {
            if (!stopRequested.get()) consoleRuntime.get()?.completeStartup()
        }
    }

    override fun requestStop() {
        stopRequested.set(true)
        observationMedia.requestStop()
        consoleRuntime.get()?.requestStop()
    }

    override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        requestStop()
        val future =
            closeFuture.get() ?: CompletableFuture<RuntimeCloseResult>().also { candidate ->
                if (closeFuture.compareAndSet(null, candidate)) {
                    enqueuePlatform {
                        val result = closePlatformResources()
                        candidate.complete(result)
                        platformExecutor.shutdown()
                    }
                }
            }
        val activeFuture = checkNotNull(closeFuture.get() ?: future)
        return try {
            activeFuture.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            RuntimeCloseResult.TIMED_OUT
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            RuntimeCloseResult.TIMED_OUT
        } catch (_: Throwable) {
            RuntimeCloseResult.FAILED
        }
    }

    private fun onUsbPermissionChanged(snapshot: UsbAccessoryPermissionSnapshot) {
        if (stopRequested.get()) return
        Log.i(TAG, "USB state=${snapshot.state} accessoryCount=${snapshot.connectedAccessoryCount}")
        val granted = snapshot.state == UsbAccessoryPermissionState.GRANTED
        val lostPermission = usbPermissionGranted && !granted
        if (lostPermission && consoleFactoryAttempted) terminalizeConsoleGeneration()
        usbPermissionGranted = granted
        reconcilePlatformReadiness()
        msdkStartup.onUsbPermission(snapshot)
        onMsdkStartupChanged(msdkStartup.currentSnapshot())
    }

    private fun onMsdkStartupChanged(snapshot: DjiMsdkStartupSnapshot) {
        if (stopRequested.get()) return
        Log.i(
            TAG,
            "MSDK phase=${snapshot.phase} registration=${snapshot.registrationState} " +
                "connection=${snapshot.connectionState}",
        )
        val wasReady = msdkSnapshot.isAircraftReady()
        val registrationWasEstablished =
            msdkSnapshot.registrationState == RegistrationState.REGISTERED
        msdkSnapshot = snapshot
        if (snapshot.registrationState == RegistrationState.REGISTERED) {
            startEthernetAfterRegistration()
        } else if (registrationWasEstablished && ethernetStarted) {
            Log.e(TAG, "DJI registration was lost; permanently closing the fixed Ethernet generation")
            runCatching { ethernet.close() }
        }
        if (wasReady && !snapshot.isAircraftReady() && consoleFactoryAttempted) {
            terminalizeConsoleGeneration()
        }
        reconcilePlatformReadiness()
    }

    /**
     * Process-wide Android Network pinning must never race MSDK's Internet-backed registration.
     * The Ethernet coordinator both observes and binds, so its lifecycle begins only after DJI has
     * reported REGISTERED. Until then the isolated /30 cannot consume a console generation or
     * redirect registration traffic away from the Internet-capable network.
     */
    private fun startEthernetAfterRegistration() {
        if (ethernetStarted || stopRequested.get()) return
        check(msdkSnapshot.registrationState == RegistrationState.REGISTERED) {
            "fixed Ethernet may start only after DJI registration"
        }
        ethernetStarted = true
        try {
            val initialState = ethernet.start()
            // A changed state is normally delivered by the coordinator callback. Waiting is also
            // consumed here because it may equal the coordinator's initial fail-closed state and
            // therefore produce no callback.
            if (initialState is AndroidFixedEthernetState.Waiting) {
                onEthernetChanged(initialState)
            }
        } catch (failure: Throwable) {
            ethernetStarted = false
            Log.e(TAG, "Fixed Ethernet observation failed after DJI registration", failure)
        }
    }

    private fun onEthernetChanged(state: AndroidFixedEthernetState) {
        if (stopRequested.get()) return
        when (state) {
            is AndroidFixedEthernetState.Bound ->
                Log.i(
                    TAG,
                    "Ethernet accepted generation=${state.binding.inventoryGeneration} " +
                        "networkHandle=${state.binding.androidNetwork.networkHandle}",
                )
            is AndroidFixedEthernetState.Waiting ->
                Log.i(TAG, "Ethernet waiting failure=${state.failure} policy=${state.policyRejection}")
            AndroidFixedEthernetState.Closed -> Log.i(TAG, "Ethernet coordinator closed")
        }
        when (state) {
            is AndroidFixedEthernetState.Bound -> {
                if (ethernetWasBound &&
                    consoleRuntime.get() != null &&
                    consoleNetworkHandle != state.binding.androidNetwork.networkHandle
                ) {
                    terminalizeConsoleGeneration()
                    return
                }
                ethernetWasBound = true
                ethernetBinding = state.binding
                reconcilePlatformReadiness()
            }
            is AndroidFixedEthernetState.Waiting,
            AndroidFixedEthernetState.Closed,
            -> {
                val lostBinding = ethernetWasBound
                ethernetWasBound = false
                ethernetBinding = null
                if (lostBinding && consoleFactoryAttempted) terminalizeConsoleGeneration()
                reconcilePlatformReadiness()
            }
        }
    }

    private fun reconcilePlatformReadiness() {
        observationMedia.reconcile(
            usbPermissionGranted = usbPermissionGranted,
            ethernetBinding = ethernetBinding,
            msdk = msdkSnapshot,
        )
        val binding = ethernetBinding ?: return
        if (!usbPermissionGranted || !msdkSnapshot.isAircraftReady()) return
        runCatching { startConsoleRuntimeIfAvailable(binding) }
            .onFailure { terminalizeConsoleGeneration() }
    }

    private fun startConsoleRuntimeIfAvailable(binding: AndroidFixedEthernetBinding) {
        if (consoleGenerationTerminal || consoleFactoryAttempted || stopRequested.get()) return
        consoleFactoryAttempted = true
        val runtime =
            consoleRuntimeFactory.create(
                DjiConsoleRuntimeSession(
                    appContext = appContext,
                    agent = agent,
                    ethernet = binding,
                    webRoot = webRoot,
                    auditFile = auditFile,
                    monotonicNanos = monotonicNanos,
                ),
            ) ?: return
        consoleRuntime.set(runtime)
        consoleNetworkHandle = binding.androidNetwork.networkHandle
        try {
            runtime.start()
            if (startupCommitted.get()) runtime.completeStartup()
            Log.i(TAG, "DJI office Console started on the accepted Ethernet generation")
        } catch (failure: Throwable) {
            Log.e(TAG, "DJI office Console startup failed; generation is terminal", failure)
            runtime.requestStop()
            runCatching { runtime.closeWithin(CONSOLE_TERMINAL_CLOSE_MILLIS) }
            consoleRuntime.compareAndSet(runtime, null)
            consoleNetworkHandle = null
            consoleGenerationTerminal = true
        }
    }

    private fun terminalizeConsoleGeneration() {
        Log.w(TAG, "Terminalizing DJI office Console generation")
        consoleGenerationTerminal = true
        val runtime = consoleRuntime.get() ?: return
        runtime.requestStop()
        if (runtime.closeWithin(CONSOLE_TERMINAL_CLOSE_MILLIS) == RuntimeCloseResult.CLOSED) {
            consoleRuntime.compareAndSet(runtime, null)
            consoleNetworkHandle = null
        }
    }

    private fun closePlatformResources(): RuntimeCloseResult {
        var failed = false
        consoleRuntime.get()?.let { runtime ->
            runtime.requestStop()
            if (runtime.closeWithin(CONSOLE_CLOSE_MILLIS) != RuntimeCloseResult.CLOSED) failed = true
        }
        if (!observationMedia.closeWithin(OBSERVATION_CLOSE_MILLIS)) failed = true
        runCatching { ethernet.close() }.onFailure { failed = true }
        runCatching { usbPermission.close() }.onFailure { failed = true }
        runCatching { msdkStartup.close() }.onFailure { failed = true }

        val shutdownComplete = CompletableFuture<Unit>()
        runCatching { agent.shutdown { shutdownComplete.complete(Unit) } }
            .onFailure {
                failed = true
                shutdownComplete.complete(Unit)
            }
        val agentClosed =
            try {
                shutdownComplete.get(AGENT_CLOSE_MILLIS, TimeUnit.MILLISECONDS)
                true
            } catch (_: Throwable) {
                false
            }
        return when {
            !agentClosed -> RuntimeCloseResult.TIMED_OUT
            failed -> RuntimeCloseResult.FAILED
            else -> RuntimeCloseResult.CLOSED
        }
    }

    private fun enqueuePlatform(action: () -> Unit) {
        runCatching { platformExecutor.execute(action) }
    }

    private fun enqueuePlatformAfter(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        runCatching { platformExecutor.schedule(action, delayMillis, TimeUnit.MILLISECONDS) }
    }

    private companion object {
        const val TAG = "DjiPlatformRuntime"
        const val CONSOLE_TERMINAL_CLOSE_MILLIS = 2_000L
        const val CONSOLE_CLOSE_MILLIS = 3_000L
        const val OBSERVATION_CLOSE_MILLIS = 2_000L
        const val AGENT_CLOSE_MILLIS = 7_000L
        const val MEDIA_RETRY_DELAY_MILLIS = 750L
    }
}

private fun DjiMsdkStartupSnapshot.isAircraftReady(): Boolean =
    registrationState == RegistrationState.REGISTERED &&
        connectionState == ConnectionState.AIRCRAFT_CONNECTED
