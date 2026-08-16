package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload

/** The concrete actuator family evaluated by the server-owned runtime gate. */
enum class ConsoleActuationIntent {
    TAKEOFF,
    LANDING,
    RETURN_TO_HOME,
    VIRTUAL_STICK,
}

/**
 * Immutable, server-owned operational truth used by admission and final executor dispatch.
 * The default snapshot is deliberately unavailable. Pixel/mock evidence and browser assertions
 * cannot promote a G520 runtime into this state.
 */
data class ConsoleActuationReadinessSnapshot(
    val adapter: AdapterKind?,
    val aircraftConnection: AircraftConnectionState,
    val actuationLock: ActuationLockState,
    val operatingProfile: OperatingProfile?,
) {
    @Suppress("UNUSED_PARAMETER")
    fun allowsLease(sessionId: String): Boolean =
        when {
            !baseReady() -> false
            adapter == AdapterKind.MOCK -> operatingProfile == OperatingProfile.LOCALHOST_DEVELOPMENT
            else -> false
        }

    @Suppress("UNUSED_PARAMETER")
    fun allows(
        sessionId: String,
        intent: ConsoleActuationIntent,
    ): Boolean =
        when {
            !baseReady() -> false
            adapter == AdapterKind.MOCK -> operatingProfile == OperatingProfile.LOCALHOST_DEVELOPMENT
            else -> false
        }

    private fun baseReady(): Boolean =
        aircraftConnection == AircraftConnectionState.CONNECTED &&
            actuationLock == ActuationLockState.UNLOCKED

    companion object {
        val UNAVAILABLE =
            ConsoleActuationReadinessSnapshot(
                adapter = null,
                aircraftConnection = AircraftConnectionState.DISCONNECTED,
                actuationLock = ActuationLockState.LOCKED,
                operatingProfile = null,
            )

        val MOCK_READY =
            ConsoleActuationReadinessSnapshot(
                adapter = AdapterKind.MOCK,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                actuationLock = ActuationLockState.UNLOCKED,
                operatingProfile = OperatingProfile.LOCALHOST_DEVELOPMENT,
            )
    }
}

/** Convert browser-visible runtime truth into the mock gate snapshot. DJI authority is separate. */
fun RuntimeStatePayload.toActuationReadiness(): ConsoleActuationReadinessSnapshot =
    ConsoleActuationReadinessSnapshot(
        adapter = adapter,
        aircraftConnection = aircraftConnection,
        actuationLock = actuationLock,
        operatingProfile = operatingProfile,
    )

internal val ConsoleDiscreteAction.actuationIntent: ConsoleActuationIntent
    get() =
        when (this) {
            ConsoleDiscreteAction.TAKEOFF -> ConsoleActuationIntent.TAKEOFF
            ConsoleDiscreteAction.LANDING -> ConsoleActuationIntent.LANDING
            ConsoleDiscreteAction.RETURN_TO_HOME -> ConsoleActuationIntent.RETURN_TO_HOME
        }
