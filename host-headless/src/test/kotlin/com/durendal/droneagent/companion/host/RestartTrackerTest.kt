package com.durendal.droneagent.companion.host

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestartTrackerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `tracks sticky restart and process recreation without identity data`() {
        val file = temporaryFolder.root.resolve("lifecycle/${RestartTracker.FILE_NAME}")
        val tracker = RestartTracker(file)

        val first = tracker.observe(currentPid = 100, stickyRestart = false)
        assertEquals(1L, first.generation)
        assertNull(first.previousPid)
        assertFalse(first.processChanged)
        assertFalse(first.stickyRestart)

        val sameProcess = tracker.observe(currentPid = 100, stickyRestart = true)
        assertEquals(2L, sameProcess.generation)
        assertEquals(100, sameProcess.previousPid)
        assertFalse(sameProcess.processChanged)
        assertTrue(sameProcess.stickyRestart)

        val recreated = tracker.observe(currentPid = 101, stickyRestart = true)
        assertEquals(3L, recreated.generation)
        assertEquals(100, recreated.previousPid)
        assertTrue(recreated.processChanged)

        val contents = file.readText()
        assertEquals("version=1\ngeneration=3\npid=101\n", contents)
        assertFalse(contents.contains("serial", ignoreCase = true))
        assertFalse(checkNotNull(file.parentFile).resolve("${file.name}.staging").exists())
    }

    @Test(expected = IOException::class)
    fun `fails closed on malformed persistent state`() {
        val file = temporaryFolder.newFile(RestartTracker.FILE_NAME)
        file.writeText("serial=should-never-be-accepted\n")

        RestartTracker(file).observe(currentPid = 101, stickyRestart = false)
    }
}
