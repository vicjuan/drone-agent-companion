package com.durendal.droneagent.companion.host

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidConsoleRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `real mock composition serves health and closes with durable server-stop audit`() {
        val webRoot = temporaryFolder.newFolder("web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val auditFile = temporaryFolder.root.resolve("audit/console-events.jsonl")
        val port = availablePort()
        val runtime =
            AndroidConsoleRuntime(
                AndroidConsoleRuntimeConfig(
                    webRoot = webRoot,
                    auditFile = auditFile,
                    bindPort = port,
                ),
            )

        runtime.start()
        try {
            assertEquals("{\"status\":\"ok\"}", awaitHealth(port))
        } finally {
            assertEquals(RuntimeCloseResult.CLOSED, runtime.closeWithin(2_000L))
        }

        val audit = auditFile.readText()
        assertTrue(audit.contains("\"kind\":\"actuation_readiness_changed\""))
        assertTrue(audit.contains("\"kind\":\"server_stopped\""))
    }

    @Test
    fun `port collision fails startup and cleanup remains bounded`() {
        val webRoot = temporaryFolder.newFolder("collision-web")
        File(webRoot, "index.html").writeText("<html>headless</html>")
        val occupied = ServerSocket()
        occupied.reuseAddress = false
        occupied.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        occupied.use {
            val runtime =
                AndroidConsoleRuntime(
                    AndroidConsoleRuntimeConfig(
                        webRoot = webRoot,
                        auditFile = temporaryFolder.root.resolve("collision/audit.jsonl"),
                        bindPort = it.localPort,
                        serverStartupTimeoutMillis = 500L,
                    ),
                )

            val failed = runCatching(runtime::start).isFailure
            assertTrue("Ktor must fail when its exact bind port is occupied", failed)
            assertEquals(RuntimeCloseResult.CLOSED, runtime.closeWithin(2_000L))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mock composition rejects an origin other than the fixed adb-forward endpoint`() {
        AndroidConsoleRuntimeConfig(
            webRoot = temporaryFolder.newFolder("origin-web"),
            auditFile = temporaryFolder.root.resolve("origin/audit.jsonl"),
            allowedBrowserOrigin = "http://127.0.0.1:8080",
        )
    }

    private fun awaitHealth(port: Int): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val connection =
                    (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection)
                        .apply {
                            connectTimeout = 250
                            readTimeout = 500
                        }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(25L)
            }
        }
        throw AssertionError("headless health endpoint did not become ready", lastFailure)
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
}
