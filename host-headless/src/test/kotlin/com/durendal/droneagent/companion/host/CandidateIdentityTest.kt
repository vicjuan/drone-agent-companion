package com.durendal.droneagent.companion.host

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateIdentityTest {
    @Test
    fun `generated APK candidate identity matches repository HEAD and worktree`() {
        val repoRoot = File(checkNotNull(System.getProperty("companion.repoRoot"))).canonicalFile
        val expectedCommit = git(repoRoot, "rev-parse", "--verify", "HEAD^{commit}").trim()
        assertTrue(expectedCommit.matches(Regex("[0-9a-fA-F]{40,64}")))
        val expectedWorktreeState =
            if (
                git(
                    repoRoot,
                    "status",
                    "--porcelain=v1",
                    "--untracked-files=all",
                    "--ignore-submodules=none",
                ).isBlank()
            ) {
                "clean"
            } else {
                "dirty"
            }

        val generatedRoot =
            repoRoot.resolve(
                "host-headless/build/generated/candidateAssets",
            )
        val generatedDirectory = generatedRoot.resolve("companion-candidate")
        assertEquals(
            setOf("companion-candidate"),
            generatedRoot.listFiles().orEmpty().map { it.name }.toSet(),
        )
        val generatedCommit = generatedDirectory.resolve("commit.txt")
        val generatedWorktreeState = generatedDirectory.resolve("worktree-state.txt")
        assertEquals(
            setOf("commit.txt", "worktree-state.txt"),
            generatedDirectory.listFiles().orEmpty().map { it.name }.toSet(),
        )
        assertTrue("generated candidate commit is missing", generatedCommit.isFile)
        assertTrue("generated candidate worktree state is missing", generatedWorktreeState.isFile)
        assertEquals(expectedCommit, generatedCommit.readText(Charsets.US_ASCII).trim())
        assertEquals(
            expectedWorktreeState,
            generatedWorktreeState.readText(Charsets.US_ASCII).trim(),
        )
    }

    private fun git(repoRoot: File, vararg arguments: String): String {
        val process =
            ProcessBuilder(listOf("git", "-C", repoRoot.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("git ${arguments.joinToString(" ")} failed: $output", 0, process.waitFor())
        return output
    }
}
