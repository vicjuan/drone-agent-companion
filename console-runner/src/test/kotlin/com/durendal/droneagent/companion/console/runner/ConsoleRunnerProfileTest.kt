package com.durendal.droneagent.companion.console.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleRunnerProfileTest {
    @Test
    fun `development runner remains loopback only`() {
        assertEquals("127.0.0.1", ConsoleRunnerProfile.BIND_HOST)
        assertEquals(8080, ConsoleRunnerProfile.BIND_PORT)
    }

    @Test
    fun `runner arguments can select artifacts but cannot widen the bind surface`() {
        val config = ConsoleRunnerConfig.from(arrayOf("web-output", "state/audit.jsonl"))

        assertTrue(config.webRoot.isAbsolute)
        assertTrue(config.webRoot.endsWith("web-output"))
        assertTrue(config.auditPath.endsWith("state/audit.jsonl"))
        assertEquals("127.0.0.1", ConsoleRunnerProfile.BIND_HOST)
    }

    @Test
    fun `runner rejects unknown positional arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleRunnerConfig.from(arrayOf("web", "audit", "0.0.0.0"))
        }
    }
}
