package com.durendal.droneagent.companion.console.server

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileConsoleAuditSinkTest {
    @Test
    fun `audit records are durable append-only JSON lines with escaped operator text`() {
        val path = Files.createTempDirectory("console-audit").resolve("events.jsonl")
        FileConsoleAuditSink(path).use { sink ->
            sink.record(event(detail = "line\n\"quoted\"\\slash"))
        }
        FileConsoleAuditSink(path).use { sink ->
            sink.record(event(kind = ConsoleAuditKind.COMMAND_COMPLETED, outcome = "succeeded"))
        }

        val lines = Files.readAllLines(path)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("{\"schemaVersion\":1,\"kind\":\"command_admitted\""))
        assertTrue(lines[0].contains("\"detail\":\"line\\n\\\"quoted\\\"\\\\slash\""))
        assertTrue(lines[1].contains("\"kind\":\"command_completed\""))
        assertTrue(lines[1].contains("\"outcome\":\"succeeded\""))
    }

    @Test
    fun `invalid audit data fails before any partial record is appended`() {
        val path = Files.createTempDirectory("console-audit").resolve("events.jsonl")
        FileConsoleAuditSink(path).use { sink ->
            assertThrows(IllegalArgumentException::class.java) {
                sink.record(event(detail = "x".repeat(1_025)))
            }
        }

        assertEquals(0L, Files.size(path))
    }

    @Test
    fun `closed audit sink refuses later writes`() {
        val path = Files.createTempDirectory("console-audit").resolve("events.jsonl")
        val sink = FileConsoleAuditSink(path)
        sink.close()

        assertThrows(IllegalStateException::class.java) {
            sink.record(event())
        }
        assertEquals(0L, Files.size(path))
    }

    @Test
    fun `audit destination symlink is rejected`() {
        val directory = Files.createTempDirectory("console-audit")
        val target = directory.resolve("target.jsonl")
        Files.writeString(target, "")
        val link = directory.resolve("events.jsonl")
        Files.createSymbolicLink(link, target.fileName)

        assertThrows(IllegalArgumentException::class.java) {
            FileConsoleAuditSink(link)
        }
    }

    private fun event(
        kind: ConsoleAuditKind = ConsoleAuditKind.COMMAND_ADMITTED,
        outcome: String = "admission_passed",
        detail: String? = null,
    ): ConsoleAuditEvent =
        ConsoleAuditEvent(
            kind = kind,
            timestampEpochMillis = 1_700_000_000_000L,
            monotonicNanos = 123_000_000L,
            sessionId = "session-1",
            leaseId = "lease-1",
            subjectId = "command-1",
            intentDigestSha256 = "a".repeat(64),
            outcome = outcome,
            reason = null,
            detail = detail,
        )
}
