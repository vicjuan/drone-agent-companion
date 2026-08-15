package com.durendal.droneagent.companion.console.server.transport

import com.durendal.droneagent.companion.console.server.security.ConsoleExposure
import com.durendal.droneagent.companion.console.server.security.ConsoleExposurePolicy
import com.durendal.droneagent.companion.console.server.security.ConsoleExposureProfile
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

sealed interface ConsoleServerConfig {
    val exposureProfile: ConsoleExposureProfile
    val bindHost: String
    val bindPort: Int
    val webRoot: Path
    val maxTextFrameBytes: Int
    val allowedBrowserOrigin: String
    val startupTimeoutMillis: Long
    val startupProbeToken: String
    val expectedRemotePeerIpv4: String?
    val runtimeNetworkId: String?

    fun acceptsRemoteAddress(
        remoteAddress: String,
        requestPath: String,
    ): Boolean

    companion object {
        fun create(
            exposure: ConsoleExposure,
            webRoot: Path,
            maxTextFrameBytes: Int = 64 * 1024,
            startupTimeoutMillis: Long = 10_000L,
            startupProbeToken: String = UUID.randomUUID().toString(),
        ): ConsoleServerConfig =
            PolicyBoundConsoleServerConfig(
                exposure = exposure,
                webRoot = webRoot,
                maxTextFrameBytes = maxTextFrameBytes,
                startupTimeoutMillis = startupTimeoutMillis,
                startupProbeToken = startupProbeToken,
            )
    }
}

private class PolicyBoundConsoleServerConfig(
    private val exposure: ConsoleExposure,
    override val webRoot: Path,
    override val maxTextFrameBytes: Int,
    override val startupTimeoutMillis: Long,
    override val startupProbeToken: String,
) : ConsoleServerConfig {
    override val exposureProfile: ConsoleExposureProfile = exposure.profile
    override val bindHost: String = exposure.bindHost
    override val bindPort: Int = exposure.bindPort
    override val allowedBrowserOrigin: String = exposure.allowedBrowserOrigin
    override val expectedRemotePeerIpv4: String? = exposure.expectedRemotePeerIpv4
    override val runtimeNetworkId: String? = exposure.runtimeNetworkId

    init {
        require(maxTextFrameBytes in 1..MAX_CONSOLE_TEXT_FRAME_BYTES) {
            "maxTextFrameBytes must be between 1 and $MAX_CONSOLE_TEXT_FRAME_BYTES"
        }
        require(startupTimeoutMillis in 100L..60_000L) {
            "startupTimeoutMillis must be between 100 and 60000"
        }
        require(CONSOLE_STARTUP_PROBE_TOKEN.matches(startupProbeToken)) {
            "startupProbeToken must be a bounded opaque identifier"
        }
        requireCanonicalBrowserOrigin(allowedBrowserOrigin)
    }

    override fun acceptsRemoteAddress(
        remoteAddress: String,
        requestPath: String,
    ): Boolean =
        ConsoleExposurePolicy.acceptsRemoteAddress(exposure, remoteAddress) ||
            (requestPath == ConsoleRoutes.READINESS && remoteAddress == bindHost)

}

private const val MAX_CONSOLE_TEXT_FRAME_BYTES = 1024 * 1024
private val CONSOLE_STARTUP_PROBE_TOKEN = Regex("^[A-Za-z0-9_-]{16,64}$")

interface ConsoleFrameSink {
    /** Returns false when transport backpressure has made the session unusable. */
    fun offer(text: String): Boolean

    /** Closes the peer without exposing [reason] on the WebSocket close frame. */
    fun close(reason: String)
}

/**
 * Transport boundary owned by the browser-facing server.
 *
 * Implementations decode protocol messages and invoke the admission/lease core.
 * Ktor never dispatches a command itself, so no HTTP or WebSocket route can
 * bypass that core.
 */
interface ConsoleSocketController {
    fun onOpen(
        sessionId: String,
        sink: ConsoleFrameSink,
    )

    fun onText(
        sessionId: String,
        text: String,
    )

    fun onProtocolViolation(
        sessionId: String,
        reason: String,
    )

    fun onClose(
        sessionId: String,
        reason: String,
    )
}

object ConsoleRoutes {
    const val HEALTH = "/healthz"
    const val READINESS = "/readyz"
    const val WEBSOCKET = "/api/console/v1"
}

/** Ktor CIO host shared by the Mac runner and the Android emulator candidate. */
class KtorConsoleServer(
    private val config: ConsoleServerConfig,
    controller: ConsoleSocketController,
) : AutoCloseable {
    private val engine: ApplicationEngine =
        embeddedServer(
            factory = CIO,
            host = config.bindHost,
            port = config.bindPort,
            configure = {
                // A command server must have exclusive ownership of its bind tuple. CIO defaults
                // to address reuse, which can permit two listeners on the same port on some hosts
                // and make an HTTP readiness probe observe the wrong process.
                reuseAddress = false
            },
        ) {
            installConsoleApplication(config, controller)
        }

    fun start(wait: Boolean = false) {
        if (wait) {
            engine.start(wait = true)
            return
        }

        engine.start(wait = false)
        try {
            // CIO may launch connector binding asynchronously. Do not let a foreground service
            // publish RUNTIME_STARTED until the requested socket has actually resolved; otherwise
            // a port collision can leave a healthy-looking notification with no console server.
            val connectors = runBlocking {
                withTimeout(config.startupTimeoutMillis) {
                    engine.resolvedConnectors()
                }
            }
            val connector = connectors.singleOrNull()
                ?: throw IllegalStateException("console server requires exactly one connector")
            awaitReadiness(connector.host, connector.port)
        } catch (failure: Throwable) {
            runCatching { engine.stop(gracePeriodMillis = 0L, timeoutMillis = 500L) }
            throw IllegalStateException("console connector failed to start", failure)
        }
    }

    override fun close() {
        closeWithin(DEFAULT_CLOSE_TIMEOUT_MILLIS)
    }

    /** Stops connector acceptance within the caller-owned shutdown budget. */
    fun closeWithin(timeoutMillis: Long) {
        require(timeoutMillis > 0L) { "console close timeout must be positive" }
        engine.stop(gracePeriodMillis = 0L, timeoutMillis = timeoutMillis)
    }

    private fun awaitReadiness(host: String, port: Int) {
        val deadlineNanos = System.nanoTime() + config.startupTimeoutMillis * 1_000_000L
        val urlHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
        val readinessUrl = URL("http://$urlHost:$port${ConsoleRoutes.READINESS}")
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadlineNanos) {
            val remainingMillis =
                ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L)
            val attemptTimeout = remainingMillis.coerceIn(1L, STARTUP_PROBE_ATTEMPT_MILLIS).toInt()
            try {
                val connection =
                    (readinessUrl.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                        connectTimeout = attemptTimeout
                        readTimeout = attemptTimeout
                        useCaches = false
                    }
                try {
                    if (
                        connection.responseCode == HttpStatusCode.OK.value &&
                        connection.inputStream.bufferedReader().use { it.readText() } ==
                            config.startupProbeToken
                    ) {
                        return
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            Thread.sleep(STARTUP_PROBE_RETRY_MILLIS)
        }
        throw IllegalStateException("console readiness probe timed out", lastFailure)
    }

    private companion object {
        const val STARTUP_PROBE_ATTEMPT_MILLIS = 250L
        const val STARTUP_PROBE_RETRY_MILLIS = 25L
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 2_000L
    }
}

fun Application.installConsoleApplication(
    config: ConsoleServerConfig,
    controller: ConsoleSocketController,
    sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    install(WebSockets) {
        pingPeriodMillis = 15_000L
        timeoutMillis = 20_000L
        maxFrameSize = config.maxTextFrameBytes.toLong()
        masking = false
    }

    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(CONTENT_SECURITY_POLICY_HEADER, contentSecurityPolicy(config))
        call.response.headers.append(X_FRAME_OPTIONS_HEADER, "DENY")
        call.response.headers.append(X_CONTENT_TYPE_OPTIONS_HEADER, "nosniff")
        call.response.headers.append(REFERRER_POLICY_HEADER, "no-referrer")

        // Defence in depth for the runnable localhost listener. Ktor connection metadata is not
        // authentication; commissioning cannot create a ConsoleServerConfig until a future live
        // Android Network pinner and inventory-freshness gate exist. This application installs no
        // forwarded-header plugin, so headers cannot widen the current loopback filter.
        val requestPath = call.request.path()
        if (!config.acceptsRemoteAddress(call.request.local.remoteAddress, requestPath)) {
            call.respondText(
                text = "Console peer rejected",
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.Forbidden,
            )
            finish()
            return@intercept
        }

        // Reject cross-site WebSocket attempts before Ktor performs the protocol upgrade. Browser
        // clients always send Origin; accepting a missing or ambiguous value would turn the
        // unauthenticated commissioning endpoint into a cross-site command surface.
        if (requestPath == ConsoleRoutes.WEBSOCKET && !call.hasExactBrowserOrigin(config)) {
            call.respondText(
                text = "WebSocket origin rejected",
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.Forbidden,
            )
            finish()
        }
    }

    routing {
        get(ConsoleRoutes.HEALTH) {
            call.respondText(
                text = "{\"status\":\"ok\"}",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }

        get(ConsoleRoutes.READINESS) {
            call.respondText(
                text = config.startupProbeToken,
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.OK,
            )
        }

        webSocket(ConsoleRoutes.WEBSOCKET) {
            // Defence in depth if application pipeline composition changes in a future Ktor
            // upgrade. This must remain identical to the pre-upgrade check above.
            if (!call.hasExactBrowserOrigin(config)) {
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "browser origin rejected",
                    ),
                )
                return@webSocket
            }
            val sessionId = sessionIdFactory()
            val outbound = Channel<String>(capacity = OUTBOUND_FRAME_CAPACITY)
            val closeDetail = AtomicReference("peer_closed")
            fun requestClose(reason: String) {
                if (closeDetail.compareAndSet("peer_closed", reason)) {
                    launch {
                        close(
                            CloseReason(
                                CloseReason.Codes.TRY_AGAIN_LATER,
                                "console session closed",
                            ),
                        )
                    }
                }
            }
            val sink =
                object : ConsoleFrameSink {
                    override fun offer(text: String): Boolean {
                        val accepted = outbound.trySend(text).isSuccess
                        if (!accepted) {
                            requestClose("outbound_backpressure")
                        }
                        return accepted
                    }

                    override fun close(reason: String) {
                        requestClose("controller_close:$reason")
                    }
                }
            val writer = launch {
                for (text in outbound) {
                    send(Frame.Text(text))
                }
            }
            var primaryFailure: Throwable? = null
            try {
                controller.onOpen(sessionId, sink)
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            if (text.toByteArray(StandardCharsets.UTF_8).size > config.maxTextFrameBytes) {
                                closeDetail.set("text_frame_too_large")
                                controller.onProtocolViolation(sessionId, closeDetail.get())
                                close(
                                    CloseReason(
                                        CloseReason.Codes.TOO_BIG,
                                        "text frame exceeds server limit",
                                    ),
                                )
                                break
                            }
                            controller.onText(sessionId, text)
                        }

                        is Frame.Close -> {
                            closeDetail.set("peer_close_frame")
                            break
                        }

                        else -> {
                            closeDetail.set("non_text_frame")
                            controller.onProtocolViolation(sessionId, closeDetail.get())
                            close(
                                CloseReason(
                                    CloseReason.Codes.CANNOT_ACCEPT,
                                    "only text frames are accepted",
                                ),
                            )
                            break
                        }
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                closeDetail.compareAndSet(
                    "peer_closed",
                    "transport_failure:${failure::class.simpleName ?: "unknown"}",
                )
                throw failure
            } finally {
                val closeFailure =
                    runCatching { controller.onClose(sessionId, closeDetail.get()) }.exceptionOrNull()
                outbound.close()
                writer.cancelAndJoin()
                closeFailure?.let { failure ->
                    primaryFailure?.addSuppressed(failure) ?: throw failure
                }
            }
        }

        val staticRoot = safeStaticRoot(config.webRoot)
        if (staticRoot != null) {
            staticFiles(remotePath = "/", dir = config.webRoot.toFile(), index = "index.html") {
                exclude { candidate ->
                    candidate.isHidden ||
                        !isSafeStaticCandidate(staticRoot, candidate.toPath())
                }
            }
        } else {
            get("/") {
                call.respondText(
                    text = "Web console artifact is unavailable",
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        }
    }
}

private const val OUTBOUND_FRAME_CAPACITY = 128
private const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"
private const val X_FRAME_OPTIONS_HEADER = "X-Frame-Options"
private const val X_CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
private const val REFERRER_POLICY_HEADER = "Referrer-Policy"

private data class SafeStaticRoot(
    val lexicalPath: Path,
    val realPath: Path,
)

private fun io.ktor.server.application.ApplicationCall.hasExactBrowserOrigin(
    config: ConsoleServerConfig,
): Boolean {
    val requestOrigins = request.headers.getAll(HttpHeaders.Origin)
    return requestOrigins?.size == 1 && requestOrigins.single() == config.allowedBrowserOrigin
}

private fun contentSecurityPolicy(config: ConsoleServerConfig): String {
    val origin = URI(config.allowedBrowserOrigin)
    val webSocketScheme = if (origin.scheme == "https") "wss" else "ws"
    val webSocketOrigin = browserOrigin(webSocketScheme, checkNotNull(origin.host), effectivePort(origin))
    return listOf(
        "default-src 'self'",
        "base-uri 'none'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "frame-src 'none'",
        "form-action 'none'",
        "script-src 'self'",
        "style-src 'self'",
        "img-src 'self'",
        "font-src 'self'",
        "connect-src 'self' $webSocketOrigin",
    ).joinToString("; ")
}

private fun safeStaticRoot(webRoot: Path): SafeStaticRoot? {
    val lexicalPath = webRoot.toAbsolutePath().normalize()
    if (!Files.isDirectory(lexicalPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lexicalPath)) {
        return null
    }
    val realPath = runCatching { lexicalPath.toRealPath() }.getOrNull() ?: return null
    return SafeStaticRoot(lexicalPath, realPath)
}

private fun isSafeStaticCandidate(
    root: SafeStaticRoot,
    candidate: Path,
): Boolean {
    val lexicalCandidate = candidate.toAbsolutePath().normalize()
    if (!lexicalCandidate.startsWith(root.lexicalPath)) return false

    var cursor = root.lexicalPath
    for (component in root.lexicalPath.relativize(lexicalCandidate)) {
        cursor = cursor.resolve(component)
        if (Files.isSymbolicLink(cursor)) return false
    }

    val realCandidate = runCatching { lexicalCandidate.toRealPath() }.getOrNull() ?: return false
    return realCandidate.startsWith(root.realPath)
}

private fun browserOrigin(
    scheme: String,
    host: String,
    port: Int,
): String {
    val uriPort =
        when {
            scheme == "http" && port == 80 -> -1
            scheme == "https" && port == 443 -> -1
            else -> port
        }
    return runCatching {
        URI(scheme, null, host.removeSurrounding("[", "]"), uriPort, null, null, null)
            .toASCIIString()
    }.getOrElse { throw IllegalArgumentException("browser origin host is invalid", it) }
}

private fun requireCanonicalBrowserOrigin(origin: String) {
    val uri =
        runCatching { URI(origin) }
            .getOrElse { throw IllegalArgumentException("allowedBrowserOrigin must be a URI", it) }
    require(uri.scheme == "http" || uri.scheme == "https") {
        "allowedBrowserOrigin must use http or https"
    }
    require(uri.host != null && uri.rawUserInfo == null) {
        "allowedBrowserOrigin must contain only a host authority"
    }
    require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
        "allowedBrowserOrigin must not contain a path, query, or fragment"
    }
    val effectivePort = effectivePort(uri)
    require(effectivePort in 0..65_535) { "allowedBrowserOrigin port is invalid" }
    require(origin == browserOrigin(uri.scheme, uri.host, effectivePort)) {
        "allowedBrowserOrigin must use canonical origin syntax"
    }
}

private fun effectivePort(uri: URI): Int =
    when {
        uri.port >= 0 -> uri.port
        uri.scheme == "http" || uri.scheme == "ws" -> 80
        else -> 443
    }
