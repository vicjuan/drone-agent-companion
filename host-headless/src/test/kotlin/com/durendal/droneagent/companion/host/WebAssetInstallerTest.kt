package com.durendal.droneagent.companion.host

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebAssetInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `publishes only verified allowlisted files from staging`() {
        val index = "<html>ok</html>".toByteArray()
        val script = "console.log('ok')".toByteArray()
        val entries =
            listOf(
                entry("bundle/index.html", "index.html", index),
                entry("bundle/assets/main.js", "assets/main.js", script),
            )
        val opened = mutableListOf<String>()
        val source = mapSource(mapOf(entries[0].assetPath to index, entries[1].assetPath to script), opened)
        val installRoot = temporaryFolder.newFolder("web-releases")

        val release = WebAssetInstaller.forTesting(source, installRoot, entries).install()

        assertTrue(release.name.startsWith("release-"))
        assertEquals("<html>ok</html>", release.resolve("index.html").readText())
        assertEquals("console.log('ok')", release.resolve("assets/main.js").readText())
        assertEquals(entries.map { it.assetPath }, opened)
        assertFalse(installRoot.listFiles().orEmpty().any { it.name.startsWith(".stage-") })
    }

    @Test
    fun `hash mismatch leaves prior release intact and unpublished`() {
        val original = "known-good".toByteArray()
        val originalEntry = entry("bundle/index.html", "index.html", original)
        val installRoot = temporaryFolder.newFolder("preserve")
        val goodRelease =
            WebAssetInstaller.forTesting(
                mapSource(mapOf(originalEntry.assetPath to original)),
                installRoot,
                listOf(originalEntry),
            ).install()

        val falseHashEntry =
            WebAssetEntry(
                assetPath = "bundle/index.html",
                relativeOutputPath = "index.html",
                sha256 = sha256("different".toByteArray()),
            )
        try {
            WebAssetInstaller.forTesting(
                mapSource(mapOf(falseHashEntry.assetPath to "tampered".toByteArray())),
                installRoot,
                listOf(falseHashEntry),
            ).install()
            fail("hash mismatch must fail")
        } catch (_: IOException) {
            // expected
        }

        assertEquals("known-good", goodRelease.resolve("index.html").readText())
        assertEquals(1, installRoot.listFiles().orEmpty().count { it.name.startsWith("release-") })
        assertFalse(installRoot.listFiles().orEmpty().any { it.name.startsWith(".stage-") })
    }

    @Test
    fun `existing immutable release with an extra file is rejected`() {
        val content = "fixed".toByteArray()
        val item = entry("bundle/index.html", "index.html", content)
        val installer =
            WebAssetInstaller.forTesting(
                mapSource(mapOf(item.assetPath to content)),
                temporaryFolder.newFolder("exact"),
                listOf(item),
            )
        val release = installer.install()
        release.resolve("unexpected.txt").writeText("not allowlisted")

        try {
            installer.install()
            fail("extra release file must fail")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `production hashes match the generated web console`() {
        val dist = findWebConsoleDist()
        WebAssetInstaller.PACKAGED_WEB_CONSOLE.entries.forEach { item ->
            val file = dist.resolve(item.relativeOutputPath)
            assertTrue("missing generated asset ${item.relativeOutputPath}", file.isFile)
            assertEquals(item.sha256, sha256(file.readBytes()))
            assertEquals("companion-web/${item.relativeOutputPath}", item.assetPath)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects traversal in output allowlist`() {
        WebAssetEntry(
            assetPath = "bundle/index.html",
            relativeOutputPath = "../index.html",
            sha256 = sha256("x".toByteArray()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects traversal in packaged asset path`() {
        WebAssetEntry(
            assetPath = "bundle/../../secret",
            relativeOutputPath = "index.html",
            sha256 = sha256("x".toByteArray()),
        )
    }

    private fun entry(source: String, output: String, bytes: ByteArray) =
        WebAssetEntry(source, output, sha256(bytes))

    private fun mapSource(
        contents: Map<String, ByteArray>,
        opened: MutableList<String> = mutableListOf(),
    ) = WebAssetSource { path ->
        opened += path
        ByteArrayInputStream(contents[path] ?: throw IOException("missing test asset"))
    }

    private fun findWebConsoleDist(): File {
        var cursor = File(".").canonicalFile
        while (true) {
            val candidate = cursor.resolve("web-console/dist")
            if (candidate.isDirectory) return candidate
            cursor = cursor.parentFile ?: throw AssertionError("web-console/dist not found")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
