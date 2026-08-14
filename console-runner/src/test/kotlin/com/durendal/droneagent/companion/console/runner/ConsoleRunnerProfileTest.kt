package com.durendal.droneagent.companion.console.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleRunnerProfileTest {
    @Test
    fun `development runner remains loopback only`() {
        assertEquals("127.0.0.1", ConsoleRunnerProfile.BIND_HOST)
        assertEquals(8080, ConsoleRunnerProfile.BIND_PORT)
    }
}
