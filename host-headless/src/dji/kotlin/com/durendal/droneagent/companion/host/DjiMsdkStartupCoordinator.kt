package com.durendal.droneagent.companion.host

import com.durendal.droneagent.adapter.dji.DjiDroneAgent
import com.durendal.droneagent.core.connection.ConnectionEvent
import com.durendal.droneagent.core.connection.ConnectionListener
import com.durendal.droneagent.core.connection.ConnectionState
import com.durendal.droneagent.core.registration.RegistrationResult
import com.durendal.droneagent.core.registration.RegistrationState

internal enum class DjiMsdkStartupPhase {
    IDLE,
    WAITING_FOR_USB_PERMISSION,
    REGISTERING,
    CONNECTING,
    ACTIVE,
    BLOCKED,
    CLOSED,
}

internal data class DjiMsdkStartupSnapshot(
    val phase: DjiMsdkStartupPhase,
    val registrationState: RegistrationState,
    val connectionState: ConnectionState,
)

/**
 * Sequences manifest-key MSDK registration and connection observation behind USB permission.
 *
 * A blank key is intentional: [com.durendal.droneagent.adapter.dji.DjiRegistrar] reads the same
 * `com.dji.sdk.API_KEY` manifest metadata consumed by MSDK. Failure or a late callback after USB
 * loss never opens the connection rail.
 */
internal class DjiMsdkStartupCoordinator(
    private val agent: DjiDroneAgent,
    private val onStateChanged: (DjiMsdkStartupSnapshot) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var started = false
    private var closed = false
    private var usbPermissionGranted = false
    private var registrationState = RegistrationState.NOT_REGISTERED
    private var connectionState = ConnectionState.DISCONNECTED
    private var phase = DjiMsdkStartupPhase.IDLE

    private val connectionListener =
        ConnectionListener { event: ConnectionEvent ->
            val next =
                synchronized(lock) {
                    if (closed) return@ConnectionListener
                    connectionState = event.state
                    phase = resolvePhaseLocked()
                    snapshotLocked()
                }
            onStateChanged(next)
        }

    fun start(): DjiMsdkStartupSnapshot {
        val next =
            synchronized(lock) {
                check(!started) { "DJI MSDK startup coordinator may be started only once" }
                check(!closed) { "DJI MSDK startup coordinator is closed" }
                started = true
                registrationState = agent.registrar.state()
                connectionState = agent.connection.state()
                phase = resolvePhaseLocked()
                snapshotLocked()
            }
        agent.connection.addListener(connectionListener)
        onStateChanged(next)
        return next
    }

    fun onUsbPermission(snapshot: UsbAccessoryPermissionSnapshot) {
        val permissionGranted = snapshot.state == UsbAccessoryPermissionState.GRANTED
        val shouldDisconnect =
            synchronized(lock) {
                if (!started || closed) return
                val lostPermission = usbPermissionGranted && !permissionGranted
                usbPermissionGranted = permissionGranted
                phase = resolvePhaseLocked()
                lostPermission
            }
        if (shouldDisconnect) runCatching { agent.connection.disconnect() }
        if (permissionGranted) {
            ensureRegisteredAndConnected()
        } else {
            publishCurrent()
        }
    }

    fun currentSnapshot(): DjiMsdkStartupSnapshot = synchronized(lock) { snapshotLocked() }

    override fun close() {
        val shouldClose =
            synchronized(lock) {
                if (closed) return
                closed = true
                phase = DjiMsdkStartupPhase.CLOSED
                started
            }
        if (shouldClose) runCatching { agent.connection.removeListener(connectionListener) }
        runCatching { agent.connection.disconnect() }
        publishCurrent()
    }

    private fun ensureRegisteredAndConnected() {
        val currentRegistration = agent.registrar.state()
        val connectNow =
            synchronized(lock) {
                if (closed || !usbPermissionGranted) return
                registrationState = currentRegistration
                phase = resolvePhaseLocked()
                currentRegistration == RegistrationState.REGISTERED
            }
        if (connectNow) {
            runCatching { agent.connection.connect() }
            publishCurrent()
            return
        }
        if (currentRegistration == RegistrationState.REGISTERING) {
            publishCurrent()
            return
        }

        synchronized(lock) {
            if (closed || !usbPermissionGranted) return
            registrationState = RegistrationState.REGISTERING
            phase = DjiMsdkStartupPhase.REGISTERING
        }
        publishCurrent()
        agent.registrar.register("") { result -> onRegistrationChanged(result) }
    }

    private fun onRegistrationChanged(result: RegistrationResult) {
        val shouldConnect =
            synchronized(lock) {
                if (closed) return
                registrationState = result.state
                phase = resolvePhaseLocked()
                usbPermissionGranted && result.state == RegistrationState.REGISTERED
            }
        publishCurrent()
        if (shouldConnect) {
            runCatching { agent.connection.connect() }
            publishCurrent()
        }
    }

    private fun resolvePhaseLocked(): DjiMsdkStartupPhase =
        when {
            closed -> DjiMsdkStartupPhase.CLOSED
            !usbPermissionGranted -> DjiMsdkStartupPhase.WAITING_FOR_USB_PERMISSION
            registrationState == RegistrationState.BLOCKED ||
                registrationState == RegistrationState.FAILED -> DjiMsdkStartupPhase.BLOCKED
            registrationState != RegistrationState.REGISTERED -> DjiMsdkStartupPhase.REGISTERING
            connectionState == ConnectionState.AIRCRAFT_CONNECTED ||
                connectionState == ConnectionState.RC_CONNECTED -> DjiMsdkStartupPhase.ACTIVE
            connectionState == ConnectionState.ERROR -> DjiMsdkStartupPhase.BLOCKED
            else -> DjiMsdkStartupPhase.CONNECTING
        }

    private fun publishCurrent() {
        val next = synchronized(lock) { snapshotLocked() }
        onStateChanged(next)
    }

    private fun snapshotLocked(): DjiMsdkStartupSnapshot =
        DjiMsdkStartupSnapshot(phase, registrationState, connectionState)
}
