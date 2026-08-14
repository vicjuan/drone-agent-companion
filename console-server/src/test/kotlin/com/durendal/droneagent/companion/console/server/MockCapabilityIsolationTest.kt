package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.actuation.ActuationCommandFrame
import com.durendal.droneagent.actuation.CommandSubmissionStatus
import com.durendal.droneagent.actuation.ControlGeneration
import com.durendal.droneagent.actuation.ControlProducer
import com.durendal.droneagent.adapter.mock.MockDroneAgent
import com.durendal.droneagent.companion.capability.CapabilityMatrixLoader
import com.durendal.droneagent.core.control.BodyFrameVelocityCommand
import com.durendal.droneagent.core.control.SaturatedCommand
import com.durendal.droneagent.core.stream.IngestCredential
import com.durendal.droneagent.core.stream.IngestEndpoint
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockCapabilityIsolationTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("companion.repoRoot")))

    @Test
    fun `mock stream takeoff and actuation stay on the runtime rail`() {
        val canonicalPath = repoRoot.resolve("config/capability-matrix/g520-stack.json")
        val loader = CapabilityMatrixLoader()
        val beforeBytes = Files.readAllBytes(canonicalPath)
        val before = loader.load(String(beforeBytes, StandardCharsets.UTF_8))
        val agent = MockDroneAgent()

        val consoleSnapshot =
            try {
                agent.connection.connect()
                IngestCredential("test-user", "test-password").use { credential ->
                    agent.liveStream.configure(
                        IngestEndpoint.fromPublisherUrl("rtmp://127.0.0.1:1935/test"),
                        credential,
                    )
                }
                agent.liveStream.start()
                agent.takeoff.initialize()
                agent.takeoff.startTakeoff {}

                val generation = ControlGeneration(1L)
                agent.actuation.activate(generation) {}
                val submission =
                    agent.actuation.submit(
                        ActuationCommandFrame(
                            command =
                                SaturatedCommand(
                                    BodyFrameVelocityCommand(
                                        forwardMps = 0.2,
                                        rightMps = 0.0,
                                        upMps = 0.0,
                                        yawRateDegreesPerSecond = 0.0,
                                    ),
                                    emptySet(),
                                ),
                            producer = ControlProducer.MANUAL,
                            generation = generation,
                            commandSequence = 1L,
                            createdAtNanos = System.nanoTime(),
                        ),
                    )
                assertEquals(CommandSubmissionStatus.SUBMITTED, submission)
                G520CapabilityMatrixProvider(before).snapshot("adapter-mock", agent.capabilityMatrix())
            } finally {
                agent.shutdown()
            }

        val afterBytes = Files.readAllBytes(canonicalPath)
        val after = loader.load(String(afterBytes, StandardCharsets.UTF_8))
        assertEquals(before, consoleSnapshot.evidenceMatrix)
        assertEquals("adapter-mock", consoleSnapshot.runtimeStatus.adapterId)
        assertEquals(before, after)
        assertTrue(beforeBytes.contentEquals(afterBytes))
    }
}
