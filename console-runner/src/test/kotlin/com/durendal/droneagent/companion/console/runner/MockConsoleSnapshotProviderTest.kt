package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.capability.RequiredG520Capabilities
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
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

class MockConsoleSnapshotProviderTest {
    @Test
    fun `runtime snapshot names the mock adapter and current connection honestly`() {
        val agent = MockDroneAgent()
        val snapshots =
            MockConsoleSnapshotProvider(
                agent,
                ObservableMockReturnToHomePort(),
                monotonicClockNanos = { 1_000_000L },
            )
        try {
            val disconnected = snapshots.runtimeState()
            agent.connection.connect()
            val connected = snapshots.runtimeState()

            assertEquals(AdapterKind.MOCK, disconnected.adapter)
            assertEquals(AircraftConnectionState.DISCONNECTED, disconnected.aircraftConnection)
            assertEquals(AircraftConnectionState.CONNECTED, connected.aircraftConnection)
            assertEquals(ActuationLockState.UNLOCKED, connected.actuationLock)
            assertEquals(OperatingProfile.LOCALHOST_DEVELOPMENT, connected.operatingProfile)
        } finally {
            agent.shutdown()
        }
    }

    @Test
    fun `telemetry mapping is monotonic and preserves nullable vendor-neutral fields`() {
        val agent = MockDroneAgent()
        var now = 1_000_000L
        val snapshots =
            MockConsoleSnapshotProvider(
                agent,
                ObservableMockReturnToHomePort(),
                monotonicClockNanos = { now },
            )
        try {
            val telemetry =
                Telemetry(
                    timestampEpochMs = 1_700_000_000_000L,
                    battery = BatteryTelemetry(chargePercent = 82),
                    position = PositionTelemetry(latitude = 25.0, longitude = 121.5, altitudeMeters = 3.2),
                    flightState = FlightStateTelemetry(mode = FlightMode.IN_AIR),
                    gimbal = GimbalState(pitchDegrees = -45.0),
                    camera = CameraState(isRecording = null),
                )

            val first = snapshots.mapTelemetry(telemetry)
            val second = snapshots.mapTelemetry(telemetry)

            assertEquals(1L, first.sequence)
            assertEquals(2L, second.sequence)
            assertEquals(82, first.batteryPercent)
            assertEquals(25.0, first.latitude)
            assertEquals(121.5, first.longitude)
            assertEquals(3.2, first.altitudeM)
            assertEquals(FlightState.FLYING, first.flightState)
            assertEquals(-45.0, first.gimbalPitchDeg)
            assertEquals(null, first.cameraRecording)

            now += 5_000_000L
            assertTrue(snapshots.health().uptimeMs >= 5L)
        } finally {
            agent.shutdown()
        }
    }

    @Test
    fun `companion-owned mock RTH is exposed as an observable simulation state`() {
        val agent = MockDroneAgent()
        val rthPort = ObservableMockReturnToHomePort()
        val snapshots = MockConsoleSnapshotProvider(agent, rthPort)
        val command =
            com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand(
                sessionId = "session-1",
                command =
                    com.durendal.droneagent.companion.console.server.ConsoleDiscreteCommand(
                        "rth-observable",
                        "lease-1",
                        com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction.RETURN_TO_HOME,
                        500L,
                    ),
                authorityDecisionId = "authority-1",
                intentDigestSha256 = "a".repeat(64),
                admittedAtNanos = 1L,
                expiresAtNanos = 2L,
            )
        try {
            rthPort.execute(command) { assertTrue(it.succeeded) }
            val telemetry =
                snapshots.mapTelemetry(
                    Telemetry(
                        timestampEpochMs = 1_700_000_000_000L,
                        flightState = FlightStateTelemetry(mode = FlightMode.IN_AIR),
                    ),
                )

            assertEquals(FlightState.RETURNING_HOME, telemetry.flightState)
            assertEquals("rth-observable", rthPort.activeCommandId())
        } finally {
            agent.shutdown()
        }
    }

    @Test
    fun `capability snapshot remains the canonical G520 evidence matrix`() {
        val agent = MockDroneAgent()
        val snapshots = MockConsoleSnapshotProvider(agent, ObservableMockReturnToHomePort())
        try {
            val capability = snapshots.capabilitySnapshot()

            assertEquals("mini4pro-rcn3-g520-android", capability.matrixId)
            assertEquals(RequiredG520Capabilities.rowIds, capability.rows.map { it.id }.toSet())
            assertTrue(capability.sourceDigestSha256.matches(Regex("^[0-9a-f]{64}$")))
        } finally {
            agent.shutdown()
        }
    }
}
