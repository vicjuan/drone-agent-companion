package com.durendal.droneagent.companion.console.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleProtocolModuleTest {
    @Test
    fun `browser protocol has its own companion identity`() {
        assertEquals("companion-console", ConsoleProtocolModule.NAME)
    }
}
