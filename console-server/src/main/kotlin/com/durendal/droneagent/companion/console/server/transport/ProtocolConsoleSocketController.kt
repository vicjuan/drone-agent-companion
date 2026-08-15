package com.durendal.droneagent.companion.console.server.transport

import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleMessageValidator
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolException
import com.durendal.droneagent.companion.console.protocol.ConsoleServerMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleServerPayload
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque

/**
 * Transport-facing session lifecycle. Implementations map validated client messages into the
 * transport-independent lease/admission core and publish its events through
 * [ProtocolConsoleSocketController.emit].
 */
interface ConsoleClientSessionHandler {
    fun onSessionOpened(sessionId: String)

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
        val registered =
            synchronized(lock) {
                if (sessions.containsKey(sessionId)) {
                    false
                } else {
                    sessions[sessionId] = SessionTransport(sink)
                    true
                }
            }
        if (!registered) {
            sink.close("duplicate_server_session_id")
            return
        }
        try {
            handler.onSessionOpened(sessionId)
        } catch (_: Exception) {
            failSession(sessionId, relatedMessageId = null)
        }
    }

    override fun onText(
        sessionId: String,
        text: String,
    ) {
        val session = synchronized(lock) { sessions[sessionId] } ?: return
        // The Ktor receive loop is sequential today, but the controller is also a public
        // transport boundary. Serialize frames per session so a second caller cannot dispatch
        // authority-bearing input while the first hello is still initializing the core.
        synchronized(session.clientFrameLock) {
            if (synchronized(lock) { sessions[sessionId] !== session }) return
            val message =
                try {
                    codec.decodeClient(text)
                } catch (failure: ConsoleProtocolException) {
                    val relatedMessageId =
                        runCatching { codec.inspectEnvelope(text).messageId }.getOrNull()
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

            val stateError =
                synchronized(lock) {
                    val current = sessions[sessionId] ?: return
                    when {
                        current.phase == HandshakePhase.WAITING && message.payload !is ClientHelloPayload ->
                            ProtocolErrorCode.HANDSHAKE_REQUIRED

                        current.phase == HandshakePhase.READY && message.payload is ClientHelloPayload ->
                            ProtocolErrorCode.UNEXPECTED_MESSAGE

                        // Only a reentrant call on this same thread can observe HANDSHAKING because
                        // other callers wait on clientFrameLock. Never let it cross the hello gate.
                        current.phase == HandshakePhase.HANDSHAKING ->
                            if (message.payload is ClientHelloPayload) {
                                ProtocolErrorCode.UNEXPECTED_MESSAGE
                            } else {
                                ProtocolErrorCode.HANDSHAKE_REQUIRED
                            }

                        current.phase == HandshakePhase.WAITING -> {
                            current.phase = HandshakePhase.HANDSHAKING
                            null
                        }

                        else -> null
                    }
                }
            if (stateError != null) {
                sendProtocolError(sessionId, session, stateError, message.messageId)
                return
            }

            try {
                handler.onClientMessage(sessionId, message)
            } catch (failure: ConsoleSessionProtocolException) {
                sendProtocolError(sessionId, session, failure.code, message.messageId)
                if (failure.closeSession) {
                    terminateSession(sessionId, "session_rejected:${failure.code.wireName}")
                }
                return
            } catch (_: Exception) {
                failSession(sessionId, message.messageId)
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
    ) {
        terminateSession(sessionId, "protocol_violation:$reason")
    }

    override fun onClose(
        sessionId: String,
        reason: String,
    ) {
        terminateSession(sessionId, reason, requestTransportClose = false)
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
        val targets =
            synchronized(lock) {
                if (targetSessionId == null) {
                    sessions
                        .filterValues { it.phase != HandshakePhase.WAITING }
                        .map { (sessionId, transport) -> sessionId to transport }
                } else {
                    val transport = sessions[targetSessionId]
                    if (transport?.phase != null && transport.phase != HandshakePhase.WAITING) {
                        listOf(targetSessionId to transport)
                    } else {
                        emptyList()
                    }
                }
            }
        if (targets.isEmpty()) return targetSessionId == null

        val encoded =
            runCatching {
                codec.encodeServer(
                    ConsoleServerMessage(
                        messageId = messageIdFactory(),
                        payload = payload,
                    ),
                )
            }.getOrElse {
                targets.forEach { (sessionId, _) ->
                    terminateSession(sessionId, "invalid_server_event")
                }
                return false
            }

        var allDelivered = true
        targets.forEach { (sessionId, transport) ->
            if (!enqueueAndMaybeDrain(
                    sessionId,
                    transport,
                    encoded,
                    isBroadcast = targetSessionId == null,
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
    ): Boolean {
        val enqueueResult =
            synchronized(lock) {
                val current = sessions[sessionId]
                when {
                    current !== expected -> EnqueueResult.SESSION_GONE
                    current.phase == HandshakePhase.WAITING -> EnqueueResult.NOT_READY
                    current.serverFrames.size + current.handshakeBroadcastFrames.size >=
                        maxBufferedServerFramesPerSession ->
                        EnqueueResult.OVERFLOW

                    else -> {
                        if (current.phase == HandshakePhase.HANDSHAKING && isBroadcast) {
                            current.handshakeBroadcastFrames.addLast(encoded)
                        } else {
                            current.serverFrames.addLast(encoded)
                        }
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
                terminateSession(sessionId, "outbound_backpressure")
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
        val shouldDrain =
            synchronized(lock) {
                val current = sessions[sessionId]
                if (current !== expected || current.phase != HandshakePhase.HANDSHAKING) {
                    false
                } else {
                    current.phase = HandshakePhase.READY
                    // Initial directed hello/snapshot output is protocol-critical. Ambient
                    // telemetry/health broadcasts that raced the hello handler follow it.
                    current.serverFrames.addAll(current.handshakeBroadcastFrames)
                    current.handshakeBroadcastFrames.clear()
                    if (current.serverFrames.isNotEmpty() && !current.draining) {
                        current.draining = true
                        true
                    } else {
                        false
                    }
                }
            }
        if (shouldDrain) drainSession(sessionId, expected)
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
                terminateSession(sessionId, "outbound_backpressure")
                return false
            }
        }
    }

    private fun failSession(
        sessionId: String,
        relatedMessageId: String?,
    ) {
        val session = synchronized(lock) { sessions[sessionId] } ?: return
        sendProtocolError(
            sessionId,
            session,
            ProtocolErrorCode.SERVER_UNAVAILABLE,
            relatedMessageId,
        )
        terminateSession(sessionId, "server_unavailable")
    }

    private fun sendProtocolError(
        sessionId: String,
        session: SessionTransport,
        code: ProtocolErrorCode,
        relatedMessageId: String?,
    ) {
        val payload = ConsoleProtocolException(code).toPayload(relatedMessageId)
        val encoded =
            runCatching {
                codec.encodeServer(
                    ConsoleServerMessage(
                        messageId = messageIdFactory(),
                        payload = payload,
                    ),
                )
            }.getOrElse {
                terminateSession(sessionId, "protocol_error_encode_failure")
                return
            }
        if (!session.sink.offer(encoded)) {
            terminateSession(sessionId, "outbound_backpressure")
        }
    }

    private fun terminateSession(
        sessionId: String,
        reason: String,
        requestTransportClose: Boolean = true,
    ) {
        val removed = synchronized(lock) { sessions.remove(sessionId) } ?: return
        try {
            handler.onSessionClosed(sessionId, reason)
        } finally {
            if (requestTransportClose) {
                removed.sink.close(reason)
            }
        }
    }

    private data class SessionTransport(
        val sink: ConsoleFrameSink,
        val clientFrameLock: Any = Any(),
        var phase: HandshakePhase = HandshakePhase.WAITING,
        val serverFrames: ArrayDeque<String> = ArrayDeque(),
        val handshakeBroadcastFrames: ArrayDeque<String> = ArrayDeque(),
        var draining: Boolean = false,
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
        NOT_READY,
        SESSION_GONE,
    }

    private companion object {
        const val DEFAULT_SERVER_FRAME_QUEUE_CAPACITY = 128
        val DEFAULT_SERVER_MESSAGE_SEQUENCE = AtomicLong(0L)
    }
}
