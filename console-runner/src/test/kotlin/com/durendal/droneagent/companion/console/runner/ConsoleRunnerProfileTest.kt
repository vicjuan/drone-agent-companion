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
        assertEquals("http://127.0.0.1:8891", ConsoleRunnerProfile.MEDIA_PLAYBACK_ORIGIN)

        val media = ConsoleRunnerProfile.mediaPlaybackConfig()
        assertEquals("synthetic_mac", media.sourceKind)
        assertEquals(ConsoleRunnerProfile.STREAM_ID, media.streamId)
        assertEquals(
            "http://127.0.0.1:8891/${ConsoleRunnerProfile.STREAM_ID}",
            media.pageUrl,
        )
    }

    @Test
    fun `runner arguments can select artifacts but cannot widen the bind surface`() {
        val config = ConsoleRunnerConfig.from(arrayOf("web-output", "state/audit.jsonl", "18081"))

        assertTrue(config.webRoot.isAbsolute)
        assertTrue(config.webRoot.endsWith("web-output"))
        assertTrue(config.auditPath.endsWith("state/audit.jsonl"))
        assertEquals(18081, config.bindPort)
        assertEquals("127.0.0.1", ConsoleRunnerProfile.BIND_HOST)
    }

    @Test
    fun `runner keeps the default loopback port when no override is supplied`() {
        val config = ConsoleRunnerConfig.from(emptyArray())

        assertEquals(ConsoleRunnerProfile.BIND_PORT, config.bindPort)
    }

    @Test
    fun `runner rejects a non-numeric bind surface or an invalid port`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleRunnerConfig.from(arrayOf("web", "audit", "0.0.0.0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleRunnerConfig.from(arrayOf("web", "audit", "0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleRunnerConfig.from(arrayOf("web", "audit", "65536"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleRunnerConfig.from(arrayOf("web", "audit", "18081", "unexpected"))
        }
    }
}
