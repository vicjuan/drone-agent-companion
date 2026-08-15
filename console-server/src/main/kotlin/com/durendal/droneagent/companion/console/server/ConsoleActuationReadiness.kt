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
 * Hardware commissioning authority is created by the host, never decoded from a browser frame.
 * Keeping both the session and intent allow-list here prevents a generic "unlocked" snapshot from
 * silently becoming production authority.
 */
data class ConsoleCommissioningAuthority(
    val sessionId: String,
    val allowedIntents: Set<ConsoleActuationIntent>,
) {
    init {
        require(sessionId.isNotBlank()) { "commissioning sessionId must not be blank" }
        require(allowedIntents.isNotEmpty()) { "commissioning intent allow-list must not be empty" }
    }
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
    val commissioningAuthority: ConsoleCommissioningAuthority? = null,
) {
    init {
        require(adapter == AdapterKind.DJI || commissioningAuthority == null) {
            "commissioning authority is valid only for the DJI adapter"
        }
    }

    fun allowsLease(sessionId: String): Boolean =
        when {
            !baseReady() -> false
            adapter == AdapterKind.MOCK -> operatingProfile == OperatingProfile.LOCALHOST_DEVELOPMENT
            adapter == AdapterKind.DJI ->
                operatingProfile == OperatingProfile.HARDWARE_COMMISSIONING &&
                    commissioningAuthority?.sessionId == sessionId &&
                    commissioningAuthority.allowedIntents.isNotEmpty()
            else -> false
        }

    fun allows(
        sessionId: String,
        intent: ConsoleActuationIntent,
    ): Boolean =
        when {
            !baseReady() -> false
            adapter == AdapterKind.MOCK -> operatingProfile == OperatingProfile.LOCALHOST_DEVELOPMENT
            adapter == AdapterKind.DJI ->
                operatingProfile == OperatingProfile.HARDWARE_COMMISSIONING &&
                    commissioningAuthority?.sessionId == sessionId &&
                    intent in commissioningAuthority.allowedIntents
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

/** Convert host-owned runtime observation into the exact gate snapshot. */
fun RuntimeStatePayload.toActuationReadiness(
    commissioningAuthority: ConsoleCommissioningAuthority? = null,
): ConsoleActuationReadinessSnapshot =
    ConsoleActuationReadinessSnapshot(
        adapter = adapter,
        aircraftConnection = aircraftConnection,
        actuationLock = actuationLock,
        operatingProfile = operatingProfile,
        commissioningAuthority = commissioningAuthority,
    )

internal val ConsoleDiscreteAction.actuationIntent: ConsoleActuationIntent
    get() =
        when (this) {
            ConsoleDiscreteAction.TAKEOFF -> ConsoleActuationIntent.TAKEOFF
            ConsoleDiscreteAction.LANDING -> ConsoleActuationIntent.LANDING
            ConsoleDiscreteAction.RETURN_TO_HOME -> ConsoleActuationIntent.RETURN_TO_HOME
        }
