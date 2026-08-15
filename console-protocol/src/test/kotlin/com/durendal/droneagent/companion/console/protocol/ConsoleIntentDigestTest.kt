package com.durendal.droneagent.companion.console.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConsoleIntentDigestTest {
    private val takeoff =
        DiscreteCommandRequestPayload(
            commandId = "command-takeoff-001",
            leaseId = "lease-primary-001",
            action = DiscreteCommandAction.TAKEOFF,
            ttlMs = 5000,
        )

    @Test
    fun `discrete digest is deterministic and matches the canonical accepted fixture`() {
        assertEquals(
            "707a5ad01589dda74b7cf8240c10aec56ad1fffaf440ed02204276e2f4158487",
            ConsoleIntentDigest.sha256(takeoff),
        )
        assertEquals(ConsoleIntentDigest.sha256(takeoff), ConsoleIntentDigest.sha256(takeoff.copy()))
    }

    @Test
    fun `discrete digest covers command lease action and TTL`() {
        val original = ConsoleIntentDigest.sha256(takeoff)
        listOf(
            takeoff.copy(commandId = "command-takeoff-002"),
            takeoff.copy(leaseId = "lease-secondary-001"),
            takeoff.copy(action = DiscreteCommandAction.LANDING),
            takeoff.copy(ttlMs = 5001),
        ).forEach { changed -> assertNotEquals(original, ConsoleIntentDigest.sha256(changed)) }
    }

    @Test
    fun `continuous digest covers lease sequence TTL and every axis`() {
        val frame =
            ControlFramePayload(
                leaseId = "lease-primary-001",
                inputSequence = 7,
                ttlMs = 250,
                forward = 0.75,
                right = -0.25,
                up = 0.5,
                yaw = -0.5,
            )
        val original = ConsoleIntentDigest.sha256(frame)
        listOf(
            frame.copy(leaseId = "lease-secondary-001"),
            frame.copy(inputSequence = 8),
            frame.copy(ttlMs = 251),
            frame.copy(forward = 0.5),
            frame.copy(right = 0.0),
            frame.copy(up = 0.0),
            frame.copy(yaw = 0.0),
        ).forEach { changed -> assertNotEquals(original, ConsoleIntentDigest.sha256(changed)) }
    }
}
