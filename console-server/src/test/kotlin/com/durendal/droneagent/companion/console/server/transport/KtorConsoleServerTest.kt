package com.durendal.droneagent.companion.console.server.transport

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorConsoleServerTest {
    @Test
    fun `real engine reclaims its fixed loopback port after bounded close`() {
        val port = ServerSocket(0).use { it.localPort }
        val webRoot = Files.createTempDirectory("console-port-restart")
        Files.writeString(webRoot.resolve("index.html"), "<html>restart</html>")

        repeat(2) {
            val server =
                KtorConsoleServer(
                    ConsoleServerConfig(
                        bindHost = "127.0.0.1",
                        bindPort = port,
                        webRoot = webRoot,
                        startupTimeoutMillis = 2_000L,
                    ),
                    RecordingController(),
                )
            server.start(wait = false)
            val connection =
                URL("http://127.0.0.1:$port${ConsoleRoutes.HEALTH}")
                    .openConnection() as HttpURLConnection
            try {
                assertEquals(HttpStatusCode.OK.value, connection.responseCode)
                assertEquals("{\"status\":\"ok\"}", connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                server.closeWithin(1_000L)
                connection.disconnect()
            }
        }
    }

    @Test
    fun `real engine reports a loopback port collision before start returns`() {
        val occupied = ServerSocket()
        occupied.reuseAddress = false
        occupied.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        occupied.use { socket ->
            val server =
                KtorConsoleServer(
                    ConsoleServerConfig(
                        bindHost = "127.0.0.1",
                        bindPort = socket.localPort,
                        webRoot = Files.createTempDirectory("console-port-collision"),
                        startupTimeoutMillis = 2_000L,
                    ),
                    RecordingController(),
                )

            assertThrows(IllegalStateException::class.java) { server.start(wait = false) }
            runCatching { server.close() }
        }
    }

    @Test
    fun `wildcard bind is rejected before an engine can expose the console`() {
        for (
            host in
                listOf(
                    "0.0.0.0",
                    "0",
                    "::",
                    "::0",
                    "[::]",
                    "0:0:0:0:0:0:0:0",
                    "[0:0:0:0:0:0:0:0]",
                    "*",
                )
        ) {
            assertThrows(IllegalArgumentException::class.java) {
                ConsoleServerConfig(
                    bindHost = host,
                    bindPort = 8080,
                    webRoot = Path.of("web-console/dist"),
                )
            }
        }
    }

    @Test
    fun `missing SPA artifact fails visibly instead of serving a placeholder success`() = testApplication {
        val missingRoot = Files.createTempDirectory("missing-console-root").resolve("dist")
        application {
            installConsoleApplication(config(missingRoot), RecordingController())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/").status)
    }

    @Test
    fun `health and static SPA are available without exposing HTTP command routes`() = testApplication {
        val webRoot = Files.createTempDirectory("console-web-root")
        Files.writeString(webRoot.resolve("index.html"), "<html>console fixture</html>")
        application {
            installConsoleApplication(config(webRoot), RecordingController())
        }

        val health = client.get(ConsoleRoutes.HEALTH)
        val spa = client.get("/")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("{\"status\":\"ok\"}", health.bodyAsText())
        assertTrue(spa.bodyAsText().contains("console fixture"))
        for (response in listOf(health, spa)) {
            val csp = checkNotNull(response.headers["Content-Security-Policy"])
            assertTrue(csp.contains("frame-ancestors 'none'"))
            assertTrue(csp.contains("default-src 'self'"))
            assertTrue(csp.contains("connect-src 'self' ws://127.0.0.1:0"))
            assertEquals("DENY", response.headers["X-Frame-Options"])
        }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/command").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/takeoff").status)
    }

    @Test
    fun `static SPA refuses a symlink root and an escaping symlink ancestor`() = testApplication {
        val fixtureRoot = Files.createTempDirectory("console-static-symlink")
        val actualRoot = Files.createDirectory(fixtureRoot.resolve("actual-root"))
        Files.writeString(actualRoot.resolve("index.html"), "<html>must not escape</html>")
        val linkedRoot = Files.createSymbolicLink(fixtureRoot.resolve("linked-root"), actualRoot)
        application {
            installConsoleApplication(config(linkedRoot), RecordingController())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/").status)
    }

    @Test
    fun `static SPA refuses files reached through a symlink ancestor`() = testApplication {
        val webRoot = Files.createTempDirectory("console-safe-static-root")
        val outsideRoot = Files.createTempDirectory("console-static-outside")
        Files.writeString(webRoot.resolve("index.html"), "<html>safe</html>")
        Files.writeString(outsideRoot.resolve("secret.txt"), "must-not-be-served")
        Files.createSymbolicLink(webRoot.resolve("escape"), outsideRoot)
        application {
            installConsoleApplication(config(webRoot), RecordingController())
        }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        val escaped = client.get("/escape/secret.txt")
        assertEquals(HttpStatusCode.Forbidden, escaped.status)
        assertFalse(escaped.bodyAsText().contains("must-not-be-served"))
    }

    @Test
    fun `text frames reach exactly one controller session and disconnect is observable`() = testApplication {
        val controller = RecordingController(echo = true)
        application {
            installConsoleApplication(
                config(Files.createTempDirectory("console-web-root")),
                controller,
                sessionIdFactory = { "session-1" },
            )
        }
        val socketClient = createClient { install(WebSockets) }

        socketClient.webSocket(
            ConsoleRoutes.WEBSOCKET,
            request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
        ) {
            assertEquals("opened:session-1", (incoming.receive() as Frame.Text).readText())
            send(Frame.Text("hello"))
            assertEquals("echo:hello", (incoming.receive() as Frame.Text).readText())
        }

        assertEquals(listOf("session-1" to "hello"), controller.textFrames)
        assertTrue(controller.closed.any { it.first == "session-1" })
    }

    @Test
    fun `binary input is rejected before it reaches protocol dispatch`() = testApplication {
        val controller = RecordingController()
        application {
            installConsoleApplication(
                config(Files.createTempDirectory("console-web-root")),
                controller,
                sessionIdFactory = { "session-binary" },
            )
        }
        val socketClient = createClient { install(WebSockets) }

        socketClient.webSocket(
            ConsoleRoutes.WEBSOCKET,
            request = { header(HttpHeaders.Origin, TEST_BROWSER_ORIGIN) },
        ) {
            incoming.receive()
            send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))
            while (incoming.receiveCatching().isSuccess) {
                // Drain until the server closes the rejected session.
            }
        }

        assertTrue(controller.textFrames.isEmpty())
        assertTrue(controller.violations.contains("session-binary" to "non_text_frame"))
    }

    @Test
    fun `cross-site missing null wrong-port and duplicate origins never create a controller session`() =
        testApplication {
            val controller = RecordingController()
            application {
                installConsoleApplication(
                    config(Files.createTempDirectory("console-web-root")),
                    controller,
                )
            }
            val socketClient = createClient { install(WebSockets) }
            val rejectedOrigins =
                listOf(
                    emptyList(),
                    listOf("null"),
                    listOf("https://attacker.example"),
                    listOf("http://127.0.0.1:8080"),
                    listOf(TEST_BROWSER_ORIGIN, TEST_BROWSER_ORIGIN),
                    listOf(TEST_BROWSER_ORIGIN, "https://attacker.example"),
                )

            rejectedOrigins.forEach { origins ->
                val enteredWebSocketBlock = AtomicBoolean(false)
                val failure =
                    runCatching {
                        socketClient.webSocket(
                            ConsoleRoutes.WEBSOCKET,
                            request = {
                                origins.forEach { headers.append(HttpHeaders.Origin, it) }
                            },
                        ) {
                            enteredWebSocketBlock.set(true)
                            while (incoming.receiveCatching().isSuccess) {
                                // A rejected request must never upgrade into this block.
                            }
                        }
                    }.exceptionOrNull()
                assertNotNull("origin $origins must fail the upgrade", failure)
                assertFalse("origin $origins entered WebSocket session", enteredWebSocketBlock.get())

                val preflight =
                    client.get(ConsoleRoutes.WEBSOCKET) {
                        origins.forEach { headers.append(HttpHeaders.Origin, it) }
                    }
                assertEquals(HttpStatusCode.Forbidden, preflight.status)
            }

            assertTrue(controller.opened.isEmpty())
            assertTrue(controller.textFrames.isEmpty())
            assertTrue(controller.closed.isEmpty())
        }

    private fun config(webRoot: Path): ConsoleServerConfig =
        ConsoleServerConfig(
            bindHost = "127.0.0.1",
            bindPort = 0,
            webRoot = webRoot,
            maxTextFrameBytes = 1024,
            allowedBrowserOrigin = TEST_BROWSER_ORIGIN,
        )

    private companion object {
        const val TEST_BROWSER_ORIGIN = "http://127.0.0.1:0"
    }

    private class RecordingController(
        private val echo: Boolean = false,
    ) : ConsoleSocketController {
        private val sinks = mutableMapOf<String, ConsoleFrameSink>()
        val opened = CopyOnWriteArrayList<String>()
        val textFrames = CopyOnWriteArrayList<Pair<String, String>>()
        val violations = CopyOnWriteArrayList<Pair<String, String>>()
        val closed = CopyOnWriteArrayList<Pair<String, String>>()

        override fun onOpen(
            sessionId: String,
            sink: ConsoleFrameSink,
        ) {
            sinks[sessionId] = sink
            opened += sessionId
            check(sink.offer("opened:$sessionId"))
        }

        override fun onText(
            sessionId: String,
            text: String,
            expectedSink: ConsoleFrameSink,
        ) {
            check(sinks[sessionId] === expectedSink)
            textFrames += sessionId to text
            if (echo) checkNotNull(sinks[sessionId]).offer("echo:$text")
        }

        override fun onProtocolViolation(
            sessionId: String,
            reason: String,
            expectedSink: ConsoleFrameSink,
        ) {
            check(sinks[sessionId] === expectedSink)
            violations += sessionId to reason
        }

        override fun onClose(
            sessionId: String,
            reason: String,
            expectedSink: ConsoleFrameSink,
        ) {
            check(sinks[sessionId] === expectedSink)
            sinks -= sessionId
            closed += sessionId to reason
        }
    }
}
