package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.companion.console.server.security.ConsoleExposureProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleRunnerProfileTest {
    @Test
    fun `development runner remains loopback only`() {
        with(ConsoleRunnerProfile.exposure()) {
            assertEquals(ConsoleExposureProfile.LOCALHOST_DEVELOPMENT, profile)
            assertEquals("127.0.0.1", bindHost)
            assertEquals(8080, bindPort)
            assertEquals("http://127.0.0.1:8080", allowedBrowserOrigin)
        }
    }

    @Test
    fun `runner arguments can select artifacts but cannot widen the bind surface`() {
        val config = ConsoleRunnerConfig.from(arrayOf("web-output", "state/audit.jsonl"))

        assertTrue(config.webRoot.isAbsolute)
        assertTrue(config.webRoot.endsWith("web-output"))
        assertTrue(config.auditPath.endsWith("state/audit.jsonl"))
        assertEquals("127.0.0.1", ConsoleRunnerProfile.exposure().bindHost)
    }

    @Test
    fun `runner rejects unknown positional arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleRunnerConfig.from(arrayOf("web", "audit", "0.0.0.0"))
        }
    }
}
