package com.durendal.droneagent.companion.console.dji

import com.durendal.droneagent.companion.capability.RequiredG520Capabilities
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.CapabilityEvidenceStatus
import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.companion.console.server.G520ProtocolCapabilitySource
import com.durendal.droneagent.core.connection.ConnectionState
import com.durendal.droneagent.core.model.BatteryTelemetry
import com.durendal.droneagent.core.model.CameraState
import com.durendal.droneagent.core.model.FlightMode
import com.durendal.droneagent.core.model.FlightStateTelemetry
import com.durendal.droneagent.core.model.GimbalState
import com.durendal.droneagent.core.model.PositionTelemetry
import com.durendal.droneagent.core.model.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiConsoleSnapshotProviderTest {
    @Test
    fun `DJI runtime truth remains hardware commissioning locked for every connection state`() {
        var connection = ConnectionState.DISCONNECTED
        val snapshots = provider(connectionState = { connection })

        val disconnected = snapshots.runtimeState()
        connection = ConnectionState.AIRCRAFT_CONNECTED
        val connected = snapshots.runtimeState()

        assertEquals(AdapterKind.DJI, disconnected.adapter)
        assertEquals(AircraftConnectionState.DISCONNECTED, disconnected.aircraftConnection)
        assertEquals(AircraftConnectionState.CONNECTED, connected.aircraftConnection)
        assertEquals(ActuationLockState.LOCKED, disconnected.actuationLock)
        assertEquals(ActuationLockState.LOCKED, connected.actuationLock)
        assertEquals(OperatingProfile.HARDWARE_COMMISSIONING, connected.operatingProfile)
    }

    @Test
    fun `telemetry is projected from the live agent seam without capability substitution`() {
        var telemetry: Telemetry? =
            Telemetry(
                timestampEpochMs = 1_700_000_000_000L,
                battery = BatteryTelemetry(chargePercent = 81),
                position = PositionTelemetry(latitude = 25.0, longitude = 121.5, altitudeMeters = 4.2),
                flightState = FlightStateTelemetry(mode = FlightMode.RETURNING_HOME),
                gimbal = GimbalState(pitchDegrees = -42.0),
                camera = CameraState(isRecording = true),
            )
        val snapshots = provider(latestTelemetry = { telemetry })

        val first = checkNotNull(snapshots.latestTelemetry())
        telemetry = telemetry?.copy(flightState = FlightStateTelemetry(mode = FlightMode.LANDING))
        val second = checkNotNull(snapshots.latestTelemetry())

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(81, first.batteryPercent)
        assertEquals(25.0, first.latitude)
        assertEquals(121.5, first.longitude)
        assertEquals(4.2, first.altitudeM)
        assertEquals(FlightState.RETURNING_HOME, first.flightState)
        assertEquals(FlightState.LANDING, second.flightState)
        assertEquals(-42.0, first.gimbalPitchDeg)
        assertEquals(true, first.cameraRecording)
    }

    @Test
    fun `bundled capability snapshot keeps all seventeen G520 rows unknown`() {
        val snapshots = provider()

        val capability = snapshots.capabilitySnapshot()

        assertEquals("mini4pro-rcn3-g520-android", capability.matrixId)
        assertEquals(RequiredG520Capabilities.rowIds, capability.rows.map { it.id }.toSet())
        assertEquals(17, capability.rows.size)
        assertTrue(capability.rows.all { it.status == CapabilityEvidenceStatus.UNKNOWN })
    }

    private fun provider(
        connectionState: () -> ConnectionState = { ConnectionState.DISCONNECTED },
        latestTelemetry: () -> Telemetry? = { null },
    ): DjiConsoleSnapshotProvider =
        DjiConsoleSnapshotProvider(
            connectionState = connectionState,
            latestAgentTelemetry = latestTelemetry,
            capabilitySnapshotLoader = { G520ProtocolCapabilitySource.loadBundled().snapshot() },
            monotonicClockNanos = { 1_000_000L },
        )
}
