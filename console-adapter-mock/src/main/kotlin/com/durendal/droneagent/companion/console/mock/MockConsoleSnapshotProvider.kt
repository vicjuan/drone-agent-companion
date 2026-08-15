package com.durendal.droneagent.companion.console.mock

import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.protocol.HealthPayload
import com.durendal.droneagent.companion.console.protocol.HealthStatus
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.companion.console.protocol.RuntimeStatePayload
import com.durendal.droneagent.companion.console.protocol.TelemetryPayload
import com.durendal.droneagent.companion.console.server.G520ProtocolCapabilitySource
import com.durendal.droneagent.companion.console.server.transport.ConsoleSnapshotProvider
import com.durendal.droneagent.core.connection.ConnectionState
import com.durendal.droneagent.core.model.FlightMode
import com.durendal.droneagent.core.model.Telemetry
import java.util.concurrent.atomic.AtomicLong

/** Snapshot rail paired with the execution adapter through one shared observable RTH port. */
class MockConsoleSnapshotProvider(
    private val agent: MockDroneAgent,
    private val returnToHome: ObservableMockReturnToHomePort,
    capabilitySnapshotLoader: () -> CapabilitySnapshotPayload = {
        G520ProtocolCapabilitySource.loadBundled().snapshot()
    },
    private val monotonicClockNanos: () -> Long = System::nanoTime,
) : ConsoleSnapshotProvider {
    private val startedAtNanos = monotonicClockNanos()
    private val telemetrySequence = AtomicLong(0L)
    private val loadedCapabilitySnapshot =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED, capabilitySnapshotLoader)

    override fun runtimeState(): RuntimeStatePayload =
        RuntimeStatePayload(
            adapter = AdapterKind.MOCK,
            aircraftConnection = agent.connection.state().toProtocol(),
            actuationLock =
                when (agent.actuation.currentSnapshot().state) {
                    com.durendal.droneagent.actuation.FlightControlPortState.FAULTED,
                    com.durendal.droneagent.actuation.FlightControlPortState.RELEASED,
                    -> ActuationLockState.LOCKED
                    else -> ActuationLockState.UNLOCKED
                },
            operatingProfile = OperatingProfile.LOCALHOST_DEVELOPMENT,
        )

    override fun capabilitySnapshot(): CapabilitySnapshotPayload = loadedCapabilitySnapshot.value

    override fun health(): HealthPayload =
        HealthPayload(
            status = HealthStatus.HEALTHY,
            uptimeMs = ((monotonicClockNanos() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L),
            detail = null,
        )

    override fun latestTelemetry(): TelemetryPayload? = agent.telemetry.latest()?.toProtocol()

    fun mapTelemetry(telemetry: Telemetry): TelemetryPayload = telemetry.toProtocol()

    private fun Telemetry.toProtocol(): TelemetryPayload =
        TelemetryPayload(
            sequence = nextTelemetrySequence(),
            batteryPercent = battery?.chargePercent,
            latitude = position?.latitude,
            longitude = position?.longitude,
            altitudeM = position?.altitudeMeters,
            flightState = returnToHome.flightStateOverride() ?: flightState?.mode.toProtocol(),
            gimbalPitchDeg = gimbal?.pitchDegrees,
            cameraRecording = camera?.isRecording,
        )

    private fun nextTelemetrySequence(): Long {
        val next = telemetrySequence.incrementAndGet()
        check(next in 1L..ConsoleProtocolModule.MAX_SAFE_INTEGER) {
            "console telemetry sequence exhausted"
        }
        return next
    }

    private fun ConnectionState.toProtocol(): AircraftConnectionState =
        when (this) {
            ConnectionState.DISCONNECTED -> AircraftConnectionState.DISCONNECTED
            ConnectionState.CONNECTING,
            ConnectionState.RC_CONNECTED,
            -> AircraftConnectionState.CONNECTING
            ConnectionState.AIRCRAFT_CONNECTED -> AircraftConnectionState.CONNECTED
            ConnectionState.ERROR -> AircraftConnectionState.ERROR
        }

    private fun FlightMode?.toProtocol(): FlightState =
        when (this) {
            FlightMode.ON_GROUND -> FlightState.GROUNDED
            FlightMode.TAKING_OFF -> FlightState.TAKING_OFF
            FlightMode.IN_AIR -> FlightState.FLYING
            FlightMode.LANDING -> FlightState.LANDING
            FlightMode.RETURNING_HOME -> FlightState.RETURNING_HOME
            FlightMode.UNKNOWN,
            null,
            -> FlightState.UNKNOWN
        }
}
