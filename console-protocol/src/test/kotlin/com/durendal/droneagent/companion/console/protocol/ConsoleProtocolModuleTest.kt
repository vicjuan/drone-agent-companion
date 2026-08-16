package com.durendal.droneagent.companion.console.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleProtocolModuleTest {
    @Test
    fun `browser protocol has its own companion identity`() {
        assertEquals("companion-console", ConsoleProtocolModule.NAME)
    }

    @Test
    fun `bootstrap remains v1 while negotiation prefers the v1_1 authority view`() {
        assertEquals("1.0", ConsoleProtocolModule.PROTOCOL_VERSION)
        assertEquals("1.0", ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION)
        assertEquals(listOf("1.1", "1.0"), ConsoleProtocolModule.SUPPORTED_PROTOCOL_VERSIONS)
        assertEquals(
            "1.1",
            ConsoleProtocolModule.selectProtocolVersion(listOf("1.0", "1.1")),
        )
        assertEquals("1.0", ConsoleProtocolModule.selectProtocolVersion(listOf("2.0", "1.0")))
        assertEquals(null, ConsoleProtocolModule.selectProtocolVersion(listOf("2.0")))
    }
}
