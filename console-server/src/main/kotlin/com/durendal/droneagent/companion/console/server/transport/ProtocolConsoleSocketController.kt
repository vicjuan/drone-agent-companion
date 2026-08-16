package com.durendal.droneagent.companion.console.server.transport

import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleMessageValidator
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolException
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.protocol.ConsoleServerMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleServerPayload
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import com.durendal.droneagent.companion.console.protocol.ServerHelloPayload
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque

/**
 * Transport-facing session lifecycle. Implementations map validated client messages into the
 * transport-independent lease/admission core and publish its events through
 * [ProtocolConsoleSocketController.emit].
 */
interface ConsoleClientSessionHandler {
    fun onSessionOpened(sessionId: String)

    /** Transport-owned negotiation result; Core/session authority remains version-neutral. */
    fun onProtocolSelected(
        sessionId: String,
        selectedProtocolVersion: String,
    ) = Unit

    fun onClientMessage(
        sessionId: String,
        message: ConsoleClientMessage,
    )

    fun onSessionClosed(
        sessionId: String,
        reason: String,
    )
}

/** Expected, sanitized session-state rejection raised by a validated-message handler. */
class ConsoleSessionProtocolException(
    val code: ProtocolErrorCode,
    val closeSession: Boolean = true,
) : IllegalArgumentException("Console session rejected: ${code.wireName}")

/**
 * Strict console-protocol boundary for Ktor WebSocket sessions.
 *
 * The transport-generated [sessionId] is the authority identity. A browser must first send a
 * valid `client_hello`; malformed, wrong-direction and out-of-state messages never reach the
 * handler. No browser field can replace the transport identity.
 */
class ProtocolConsoleSocketController(
    private val handler: ConsoleClientSessionHandler,
    private val codec: ConsoleProtocolCodec = ConsoleProtocolCodec(),
    private val messageIdFactory: () -> String = {
        "server-${DEFAULT_SERVER_MESSAGE_SEQUENCE.incrementAndGet()}"
    },
    private val maxBufferedServerFramesPerSession: Int = DEFAULT_SERVER_FRAME_QUEUE_CAPACITY,
) : ConsoleSocketController {
    private val lock = Any()
    private val sessions = mutableMapOf<String, SessionTransport>()

    init {
        require(maxBufferedServerFramesPerSession > 0) {
            "maxBufferedServerFramesPerSession must be positive"
        }
    }

    override fun onOpen(
        sessionId: String,
        sink: ConsoleFrameSink,
    ) {
        val validSessionId =
            runCatching { ConsoleMessageValidator.requireMessageId(sessionId) }.isSuccess
        if (!validSessionId) {
            sink.close("invalid_server_session_id")
            return
        }
        val transport = SessionTransport(sink)
        val registered =
            synchronized(lock) {
                if (sessions.containsKey(sessionId)) {
                    false
                } else {
                    sessions[sessionId] = transport
                    true
                }
            }
        if (!registered) {
            sink.close("duplicate_server_session_id")
            return
        }
        try {
            synchronized(transport.clientFrameLock) {
                if (synchronized(lock) { sessions[sessionId] !== transport || transport.closing }) {
                    return
                }
                handler.onSessionOpened(sessionId)
            }
        } catch (_: Exception) {
            failSession(sessionId, transport, relatedMessageId = null)
        }
    }

    override fun onText(
        sessionId: String,
        text: String,
        expectedSink: ConsoleFrameSink,
    ) {
        val session =
            synchronized(lock) {
                sessions[sessionId]?.takeIf { it.sink === expectedSink }
            } ?: return
        // The Ktor receive loop is sequential today, but the controller is also a public
        // transport boundary. Serialize frames per session so a second caller cannot dispatch
        // authority-bearing input while the first hello is still initializing the core.
        synchronized(session.clientFrameLock) {
            val decodeProtocolVersion =
                synchronized(lock) {
                    val current = sessions[sessionId]
                    if (current !== session || current.closing) return
                    if (current.phase == HandshakePhase.READY) {
                        checkNotNull(current.selectedProtocolVersion)
                    } else {
                        ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION
                    }
                }
            val message =
                try {
                    codec.decodeClient(text, decodeProtocolVersion)
                } catch (failure: ConsoleProtocolException) {
                    val relatedMessageId =
                        runCatching {
                            codec.inspectEnvelope(text, decodeProtocolVersion).messageId
                        }.getOrNull()
                    sendProtocolError(sessionId, session, failure.code, relatedMessageId)
                    return
                } catch (_: Exception) {
                    sendProtocolError(
                        sessionId,
                        session,
                        ProtocolErrorCode.SERVER_UNAVAILABLE,
                        relatedMessageId = null,
                    )
                    return
                }

            var selectedDuringHello: String? = null
            var closeAfterStateError = false
            val stateError =
                synchronized(lock) {
                    val current = sessions[sessionId] ?: return
                    when {
                        current !== session || current.closing -> return

                        current.phase == HandshakePhase.WAITING && message.payload !is ClientHelloPayload ->
                            ProtocolErrorCode.HANDSHAKE_REQUIRED

                        current.phase == HandshakePhase.READY && message.payload is ClientHelloPayload ->
                            ProtocolErrorCode.UNEXPECTED_MESSAGE

                        // Only a reentrant call on this same thread can observe HANDSHAKING because
                        // other callers wait on clientFrameLock. Never let it cross the hello gate.
                        current.phase == HandshakePhase.HANDSHAKING ->
                            if (message.payload is ClientHelloPayload) {
                                closeAfterStateError = true
                                ProtocolErrorCode.UNEXPECTED_MESSAGE
                            } else {
                                closeAfterStateError = true
                                ProtocolErrorCode.HANDSHAKE_REQUIRED
                            }

                        current.phase == HandshakePhase.WAITING -> {
                            val selected =
                                ConsoleProtocolModule.selectProtocolVersion(
                                    (message.payload as ClientHelloPayload)
                                        .supportedProtocolVersions,
                                )
                            if (selected == null) {
                                ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION
                            } else {
                                current.selectedProtocolVersion = selected
                                current.phase = HandshakePhase.HANDSHAKING
                                selectedDuringHello = selected
                                null
                            }
                        }

                        else -> null
                    }
                }
            if (stateError != null) {
                sendProtocolError(sessionId, session, stateError, message.messageId)
                if (closeAfterStateError ||
                    stateError == ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION
                ) {
                    terminateSession(
                        sessionId,
                        if (closeAfterStateError) {
                            "handshake_state_violation"
                        } else {
                            "unsupported_protocol_version"
                        },
                        expected = session,
                    )
                }
                return
            }

            try {
                selectedDuringHello?.let { selected ->
                    handler.onProtocolSelected(sessionId, selected)
                }
                handler.onClientMessage(sessionId, message)
            } catch (failure: ConsoleSessionProtocolException) {
                sendProtocolError(sessionId, session, failure.code, message.messageId)
                if (failure.closeSession) {
                    terminateSession(
                        sessionId,
                        "session_rejected:${failure.code.wireName}",
                        expected = session,
                    )
                }
                return
            } catch (_: Exception) {
                failSession(sessionId, session, message.messageId)
                return
            }

            if (message.payload is ClientHelloPayload) {
                markReadyAndDrain(sessionId, session)
            }
        }
    }

    override fun onProtocolViolation(
        sessionId: String,
        reason: String,
        expectedSink: ConsoleFrameSink,
    ) {
        val expected =
            synchronized(lock) {
                sessions[sessionId]?.takeIf { it.sink === expectedSink }
            } ?: return
        terminateSession(sessionId, "protocol_violation:$reason", expected = expected)
    }

    override fun onClose(
        sessionId: String,
        reason: String,
        expectedSink: ConsoleFrameSink,
    ) {
        val expected =
            synchronized(lock) {
                sessions[sessionId]?.takeIf { it.sink === expectedSink }
            } ?: return
        terminateSession(
            sessionId,
            reason,
            requestTransportClose = false,
            expected = expected,
        )
    }

    /**
     * Sends one validated server payload. A null target broadcasts to READY sessions and buffers
     * for sessions whose hello handler is still running. A WAITING session receives nothing before
     * supplying hello. Any encode or bounded-queue failure closes the affected session, which in
     * turn invokes the handler's disconnect/neutral path.
     */
    fun emit(
        targetSessionId: String?,
        payload: ConsoleServerPayload,
    ): Boolean {
        val isServerHello = payload is ServerHelloPayload
        if (isServerHello && targetSessionId == null) return false
        val targets =
            synchronized(lock) {
                if (targetSessionId == null) {
                    sessions
                        .filterValues {
                            it.phase != HandshakePhase.WAITING && !it.closing
                        }
                        .mapNotNull { (sessionId, transport) ->
                            transport.selectedProtocolVersion
                                ?.let { EncodeTarget(sessionId, transport, it) }
                        }
                } else {
                    val transport = sessions[targetSessionId]
                    if (transport?.phase != null && transport.phase != HandshakePhase.WAITING) {
                        transport.selectedProtocolVersion
                            ?.let { listOf(EncodeTarget(targetSessionId, transport, it)) }
                            ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
            }
        if (targets.isEmpty()) return targetSessionId == null

        val message =
            ConsoleServerMessage(
                messageId = messageIdFactory(),
                payload = payload,
            )

        var allDelivered = true
        targets.forEach { target ->
            val encoded =
                runCatching {
                    codec.encodeServer(message, target.protocolVersion)
                }.getOrElse {
                    terminateSession(
                        target.sessionId,
                        "invalid_server_event",
                        expected = target.transport,
                    )
                    allDelivered = false
                    return@forEach
                }
            if (!enqueueAndMaybeDrain(
                    target.sessionId,
                    target.transport,
                    encoded,
                    isBroadcast = targetSessionId == null,
                    isServerHello = isServerHello,
                )
            ) {
                allDelivered = false
            }
        }
        return allDelivered
    }

    private fun enqueueAndMaybeDrain(
        sessionId: String,
        expected: SessionTransport,
        encoded: String,
        isBroadcast: Boolean,
        isServerHello: Boolean,
    ): Boolean {
        val enqueueResult =
            synchronized(lock) {
                val current = sessions[sessionId]
                when {
                    current !== expected -> EnqueueResult.SESSION_GONE
                    current.phase == HandshakePhase.WAITING -> EnqueueResult.NOT_READY
                    current.serverFrames.size + current.handshakePendingFrames.size >=
                        maxBufferedServerFramesPerSession ->
                        EnqueueResult.OVERFLOW

                    current.phase == HandshakePhase.HANDSHAKING -> {
                        if (isServerHello) {
                            if (isBroadcast || current.serverHelloQueued) {
                                EnqueueResult.INVALID_EVENT
                            } else {
                                current.serverFrames.addLast(encoded)
                                current.serverHelloQueued = true
                                EnqueueResult.QUEUED
                            }
                        } else {
                            current.handshakePendingFrames.addLast(encoded)
                            EnqueueResult.QUEUED
                        }
                    }

                    isServerHello -> EnqueueResult.INVALID_EVENT

                    else -> {
                        current.serverFrames.addLast(encoded)
                        if (current.phase == HandshakePhase.READY && !current.draining) {
                            current.draining = true
                            EnqueueResult.DRAIN
                        } else {
                            EnqueueResult.QUEUED
                        }
                    }
                }
            }
        return when (enqueueResult) {
            EnqueueResult.QUEUED -> true
            EnqueueResult.DRAIN -> drainSession(sessionId, expected)
            EnqueueResult.OVERFLOW -> {
                terminateSession(sessionId, "outbound_backpressure", expected = expected)
                false
            }
            EnqueueResult.INVALID_EVENT -> {
                terminateSession(sessionId, "invalid_server_event", expected = expected)
                false
            }
            EnqueueResult.NOT_READY,
            EnqueueResult.SESSION_GONE,
            -> false
        }
    }

    private fun markReadyAndDrain(
        sessionId: String,
        expected: SessionTransport,
    ) {
        val readyResult =
            synchronized(lock) {
                val current = sessions[sessionId]
                if (current !== expected || current.phase != HandshakePhase.HANDSHAKING) {
                    ReadyResult.SESSION_GONE
                } else if (!current.serverHelloQueued || current.serverFrames.isEmpty()) {
                    ReadyResult.MISSING_SERVER_HELLO
                } else {
                    current.phase = HandshakePhase.READY
                    // Initial directed hello/snapshot output is protocol-critical. Ambient
                    // broadcasts and any authority event that raced it follow server_hello.
                    current.serverFrames.addAll(current.handshakePendingFrames)
                    current.handshakePendingFrames.clear()
                    if (current.serverFrames.isNotEmpty() && !current.draining) {
                        current.draining = true
                        ReadyResult.DRAIN
                    } else {
                        ReadyResult.READY
                    }
                }
            }
        when (readyResult) {
            ReadyResult.DRAIN -> drainSession(sessionId, expected)
            ReadyResult.MISSING_SERVER_HELLO ->
                terminateSession(sessionId, "missing_server_hello", expected = expected)
            ReadyResult.READY,
            ReadyResult.SESSION_GONE,
            -> Unit
        }
    }

    private fun drainSession(
        sessionId: String,
        expected: SessionTransport,
    ): Boolean {
        while (true) {
            val encoded =
                synchronized(lock) {
                    val current = sessions[sessionId]
                    if (current !== expected) return false
                    if (current.phase != HandshakePhase.READY) {
                        current.draining = false
                        return true
                    }
                    if (current.serverFrames.isEmpty()) {
                        current.draining = false
                        return true
                    }
                    current.serverFrames.removeFirst()
            }
            if (!expected.sink.offer(encoded)) {
                terminateSession(sessionId, "outbound_backpressure", expected = expected)
                return false
            }
        }
    }

    private fun failSession(
        sessionId: String,
        expected: SessionTransport,
        relatedMessageId: String?,
    ) {
        val session = synchronized(lock) { sessions[sessionId] }
        if (session !== expected) return
        sendProtocolError(
            sessionId,
            session,
            ProtocolErrorCode.SERVER_UNAVAILABLE,
            relatedMessageId,
        )
        terminateSession(sessionId, "server_unavailable", expected = session)
    }

    private fun sendProtocolError(
        sessionId: String,
        session: SessionTransport,
        code: ProtocolErrorCode,
        relatedMessageId: String?,
    ) {
        val protocolVersion =
            synchronized(lock) {
                val current = sessions[sessionId]
                if (current !== session || current.closing) return
                when (current.phase) {
                    // Failed initialization never reaches READY, so its sanitized error remains
                    // on the frozen bootstrap envelope. A successful initialization still drains
                    // server_hello before every buffered non-error frame.
                    HandshakePhase.WAITING,
                    HandshakePhase.HANDSHAKING,
                    -> ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION
                    HandshakePhase.READY -> checkNotNull(current.selectedProtocolVersion)
                }
            }
        val payload = ConsoleProtocolException(code).toPayload(relatedMessageId)
        val encoded =
            runCatching {
                codec.encodeServer(
                    ConsoleServerMessage(
                        messageId = messageIdFactory(),
                        payload = payload,
                    ),
                    protocolVersion,
                )
            }.getOrElse {
                terminateSession(
                    sessionId,
                    "protocol_error_encode_failure",
                    expected = session,
                )
                return
            }
        if (!session.sink.offer(encoded)) {
            terminateSession(sessionId, "outbound_backpressure", expected = session)
        }
    }

    private fun terminateSession(
        sessionId: String,
        reason: String,
        expected: SessionTransport,
        requestTransportClose: Boolean = true,
    ) {
        val closing =
            synchronized(lock) {
                val current = sessions[sessionId] ?: return
                if (current !== expected || current.closing) return
                current.closing = true
                current
            }
        try {
            // Keep the closing transport as an exact-identity tombstone until any in-flight
            // client callback has returned. This prevents a same-id replacement from being
            // opened and then mutated by the tail of the old hello/message handler.
            synchronized(closing.clientFrameLock) {
                try {
                    handler.onSessionClosed(sessionId, reason)
                } finally {
                    synchronized(lock) {
                        if (sessions[sessionId] === closing) sessions.remove(sessionId)
                    }
                }
            }
        } finally {
            if (requestTransportClose) {
                closing.sink.close(reason)
            }
        }
    }

    private data class SessionTransport(
        val sink: ConsoleFrameSink,
        val clientFrameLock: Any = Any(),
        var phase: HandshakePhase = HandshakePhase.WAITING,
        var selectedProtocolVersion: String? = null,
        var serverHelloQueued: Boolean = false,
        val serverFrames: ArrayDeque<String> = ArrayDeque(),
        val handshakePendingFrames: ArrayDeque<String> = ArrayDeque(),
        var draining: Boolean = false,
        var closing: Boolean = false,
    )

    private data class EncodeTarget(
        val sessionId: String,
        val transport: SessionTransport,
        val protocolVersion: String,
    )

    private enum class HandshakePhase {
        WAITING,
        HANDSHAKING,
        READY,
    }

    private enum class EnqueueResult {
        QUEUED,
        DRAIN,
        OVERFLOW,
        INVALID_EVENT,
        NOT_READY,
        SESSION_GONE,
    }

    private enum class ReadyResult {
        READY,
        DRAIN,
        MISSING_SERVER_HELLO,
        SESSION_GONE,
    }

    private companion object {
        const val DEFAULT_SERVER_FRAME_QUEUE_CAPACITY = 128
        val DEFAULT_SERVER_MESSAGE_SEQUENCE = AtomicLong(0L)
    }
}
