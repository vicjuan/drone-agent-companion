package com.durendal.droneagent.companion.console.mock

import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.capability.RequiredG520Capabilities
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.CapabilitySnapshotPayload
import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import com.durendal.droneagent.core.model.BatteryTelemetry
import com.durendal.droneagent.core.model.CameraState
import com.durendal.droneagent.core.model.FlightMode
import com.durendal.droneagent.core.model.FlightStateTelemetry
import com.durendal.droneagent.core.model.GimbalState
import com.durendal.droneagent.core.model.PositionTelemetry
import com.durendal.droneagent.core.model.Telemetry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `runtime health and telemetry paths do not load the capability snapshot`() {
        val agent = MockDroneAgent()
        val loadCalls = AtomicInteger()
        val snapshots =
            MockConsoleSnapshotProvider(
                agent,
                ObservableMockReturnToHomePort(),
                capabilitySnapshotLoader = {
                    loadCalls.incrementAndGet()
                    error("capability snapshot must remain lazy")
                },
                monotonicClockNanos = { 1_000_000L },
            )
        try {
            snapshots.runtimeState()
            snapshots.health()
            snapshots.mapTelemetry(Telemetry(timestampEpochMs = 1_700_000_000_000L))

            assertEquals(0, loadCalls.get())
        } finally {
            agent.shutdown()
        }
    }

    @Test
    fun `capability snapshot loads once across concurrent first readers`() {
        val agent = MockDroneAgent()
        val loadCalls = AtomicInteger()
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val expected =
            CapabilitySnapshotPayload(
                matrixId = "lazy-test",
                schemaVersion = 1,
                lastUpdated = "2026-08-15",
                sourceDigestSha256 = "a".repeat(64),
                rows = emptyList(),
            )
        val snapshots =
            MockConsoleSnapshotProvider(
                agent,
                ObservableMockReturnToHomePort(),
                capabilitySnapshotLoader = {
                    loadCalls.incrementAndGet()
                    loaderEntered.countDown()
                    check(releaseLoader.await(5L, TimeUnit.SECONDS)) {
                        "timed out waiting to release capability loader"
                    }
                    expected
                },
            )
        val callers = Executors.newFixedThreadPool(8)
        val callersReady = CountDownLatch(8)
        val startCallers = CountDownLatch(1)
        try {
            val results =
                List(8) {
                    callers.submit<CapabilitySnapshotPayload> {
                        callersReady.countDown()
                        check(startCallers.await(5L, TimeUnit.SECONDS)) {
                            "timed out waiting to start capability readers"
                        }
                        snapshots.capabilitySnapshot()
                    }
                }

            assertTrue(callersReady.await(5L, TimeUnit.SECONDS))
            assertEquals(0, loadCalls.get())
            startCallers.countDown()
            assertTrue(loaderEntered.await(5L, TimeUnit.SECONDS))
            releaseLoader.countDown()

            assertTrue(results.all { it.get(5L, TimeUnit.SECONDS) === expected })
            assertTrue(snapshots.capabilitySnapshot() === expected)
            assertEquals(1, loadCalls.get())
        } finally {
            startCallers.countDown()
            releaseLoader.countDown()
            callers.shutdownNow()
            agent.shutdown()
        }
    }

    @Test
    fun `companion-owned mock RTH is exposed as an observable simulation state`() {
        val agent = MockDroneAgent()
        val rthPort = ObservableMockReturnToHomePort()
        val snapshots = MockConsoleSnapshotProvider(agent, rthPort)
        val executor = MockConsoleCommandExecutor(agent, { 1L }, rthPort)
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
            executor.executeDiscrete(command) { assertTrue(it.succeeded) }
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
            executor.close()
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
