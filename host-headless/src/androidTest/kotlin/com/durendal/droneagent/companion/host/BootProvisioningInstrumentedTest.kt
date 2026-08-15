package com.durendal.droneagent.companion.host

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit emulator commissioning step for an Activity-free package.
 *
 * Starting instrumentation is the external interaction that clears Android's stopped state.
 * This test deliberately does not start [HeadlessAgentService]; the following reboot must be the
 * only cause of the first lifecycle journal and `:agent` process.
 */
@RunWith(AndroidJUnit4::class)
class BootProvisioningInstrumentedTest {
    @Test
    fun clearsStoppedStateWithoutStartingTheAgent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val expectedCommit =
            requireNotNull(
                InstrumentationRegistry.getArguments().getString(EXPECTED_COMMIT_ARGUMENT),
            ) { "missing expected frozen candidate commit" }
        require(COMMIT_PATTERN.matches(expectedCommit)) {
            "expected frozen candidate commit is malformed"
        }

        assertEquals(TARGET_PACKAGE, targetContext.packageName)
        assertCandidateIdentity(targetContext, expectedCommit)
        assertCandidateIdentity(instrumentation.context, expectedCommit)

        val applicationInfo =
            targetContext.packageManager.getApplicationInfo(targetContext.packageName, 0)
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_STOPPED)

        val lifecycleDirectory = HeadlessHostStorage.lifecycleDirectory(targetContext)
        assertFalse(
            "fresh provisioning must not inherit lifecycle evidence",
            lifecycleDirectory.exists(),
        )
        assertFalse(
            "provisioning must not start the headless service",
            isProcessRunning(targetContext, AGENT_PROCESS),
        )
    }

    private fun assertCandidateIdentity(context: Context, expectedCommit: String) {
        val commit =
            context.assets.open(CANDIDATE_COMMIT_ASSET).bufferedReader(Charsets.US_ASCII).use {
                it.readText().trim()
            }
        val worktreeState =
            context.assets.open(CANDIDATE_WORKTREE_ASSET).bufferedReader(Charsets.US_ASCII).use {
                it.readText().trim()
            }
        assertEquals(expectedCommit, commit)
        assertEquals("clean", worktreeState)
    }

    private fun isProcessRunning(context: Context, processName: String): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return activityManager.runningAppProcesses.orEmpty().any { it.processName == processName }
    }

    companion object {
        private const val EXPECTED_COMMIT_ARGUMENT = "candidateCommit"
        private const val TARGET_PACKAGE = "com.durendal.droneagent.companion.host.mock"
        private const val AGENT_PROCESS = "$TARGET_PACKAGE:agent"
        private const val CANDIDATE_COMMIT_ASSET = "companion-candidate/commit.txt"
        private const val CANDIDATE_WORKTREE_ASSET = "companion-candidate/worktree-state.txt"
        private val COMMIT_PATTERN = Regex("[0-9a-fA-F]{40,64}")
    }
}
