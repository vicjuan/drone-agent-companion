package com.durendal.droneagent.companion.host

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LifecycleEvidenceJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `records fixed-schema evidence and keeps only current plus previous`() {
        val directory = temporaryFolder.newFolder("journal")
        var time = 1_000L
        val journal =
            LifecycleEvidenceJournal(
                directory = directory,
                maxFileBytes = 256L,
                clock = { time++ },
                pid = { 42 },
            )

        repeat(12) {
            journal.record(LifecycleEvent.SERVICE_START_REQUESTED, LifecycleTrigger.EXPLICIT_START)
        }

        val current = File(directory, LifecycleEvidenceJournal.CURRENT_FILE_NAME)
        val previous = File(directory, LifecycleEvidenceJournal.PREVIOUS_FILE_NAME)
        val lock = File(directory, LifecycleEvidenceJournal.LOCK_FILE_NAME)
        assertTrue(current.isFile)
        assertTrue(previous.isFile)
        assertTrue(lock.isFile)
        assertEquals(0L, lock.length())
        assertTrue(current.length() <= 256L)
        assertTrue(previous.length() <= 256L)
        assertTrue(directory.listFiles().orEmpty().toSet() == setOf(current, previous, lock))

        (current.readLines() + previous.readLines()).forEach { line ->
            assertTrue(line.startsWith("{\"schema\":1,"))
            assertTrue(line.contains("\"event\":\"service_start_requested\""))
            assertTrue(line.contains("\"trigger\":\"explicit_start\""))
            assertTrue(line.contains("\"pid\":42"))
            assertFalse(line.contains("serial", ignoreCase = true))
            assertFalse(line.contains("secret", ignoreCase = true))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a journal bound too small for deterministic rotation`() {
        LifecycleEvidenceJournal(
            directory = temporaryFolder.newFolder("too-small"),
            maxFileBytes = LifecycleEvidenceJournal.MIN_MAX_FILE_BYTES - 1L,
            pid = { 1 },
        )
    }

    @Test(expected = IOException::class)
    fun `fails closed instead of rotating a preexisting oversized journal`() {
        val directory = temporaryFolder.newFolder("oversized")
        File(directory, LifecycleEvidenceJournal.CURRENT_FILE_NAME)
            .writeBytes(ByteArray(257) { 1 })

        LifecycleEvidenceJournal(
            directory = directory,
            maxFileBytes = 256L,
            pid = { 1 },
        ).record(LifecycleEvent.BOOT_RECEIVED, LifecycleTrigger.BOOT_COMPLETED)
    }
}
