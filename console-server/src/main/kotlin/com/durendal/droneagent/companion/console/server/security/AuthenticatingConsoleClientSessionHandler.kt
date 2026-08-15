package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import com.durendal.droneagent.companion.console.server.ConsoleAuditEvent
import com.durendal.droneagent.companion.console.server.ConsoleAuditKind
import com.durendal.droneagent.companion.console.server.ConsoleAuditSink
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.transport.ConsoleClientSessionHandler
import com.durendal.droneagent.companion.console.server.transport.ConsoleSessionProtocolException

/**
 * Authentication and role-authorization gate placed in front of the protocol/core handler.
 *
 * A transport open is kept private until a bearer credential has verified and its required audit
 * record is durable. Only then is the inner session opened and given a `client_hello` whose
 * credential presentation has been stripped. Observer sessions can consume server output, but
 * every lease, command, and continuous-control input is durably refused before it can reach the
 * inner handler. Required audit failure always fails closed.
 *
 * External verifier, audit, clock, and inner-handler callbacks are never invoked while this
 * decorator holds a state lock. Token provisioning/rotation/revocation, credential lifecycle,
 * session expiry, and TLS are intentionally outside this kernel; shared/routable production
 * exposure must remain disabled until all of those controls are implemented and verified.
 */
internal class AuthenticatingConsoleClientSessionHandler(
    private val inner: ConsoleClientSessionHandler,
    private val verifier: ConsoleAuthenticationVerifier,
    private val auditSink: ConsoleAuditSink,
    private val epochClock: ConsoleEpochClock = ConsoleEpochClock(System::currentTimeMillis),
    private val monotonicClock: ConsoleMonotonicClock = ConsoleMonotonicClock(System::nanoTime),
) : ConsoleClientSessionHandler {
    private val sessionsLock = Any()
    private val sessions = mutableMapOf<String, SessionState>()

    override fun onSessionOpened(sessionId: String) {
        synchronized(sessionsLock) {
            check(sessions.putIfAbsent(sessionId, SessionState()) == null) {
                "console authentication session is already registered"
            }
        }
    }

    override fun onClientMessage(
        sessionId: String,
        message: ConsoleClientMessage,
    ) {
        val session = synchronized(sessionsLock) { sessions[sessionId] }
            ?: throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
        val phase =
            synchronized(session.lock) {
                if (session.closeReason != null) {
                    null
                } else {
                    session.activeOperations += 1
                    session.phase
                }
            } ?: throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
        var authenticatedHelloCompleted = false
        try {
            when (phase) {
                SessionPhase.REGISTERED ->
                    authenticatedHelloCompleted = authenticateOrRefuse(sessionId, session, message)
                SessionPhase.AUTHENTICATING ->
                    throw ConsoleSessionProtocolException(ProtocolErrorCode.AUTHENTICATION_REQUIRED)
                SessionPhase.OPENING_INNER ->
                    throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
                SessionPhase.AUTHENTICATED -> authorizeAndForward(sessionId, session, message)
                SessionPhase.REJECTED,
                SessionPhase.CLOSED,
                -> throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
            }
        } finally {
            completeOperation(
                sessionId = sessionId,
                session = session,
                authenticatedHelloCompleted = authenticatedHelloCompleted,
            )
        }
    }

    override fun onSessionClosed(
        sessionId: String,
        reason: String,
    ) {
        val session = synchronized(sessionsLock) { sessions[sessionId] } ?: return
        val closeAction =
            synchronized(session.lock) {
                if (session.closeReason == null) session.closeReason = reason
                session.phase = SessionPhase.CLOSED
                session.claimInnerCloseLocked(sessionId)
            }
        if (closeAction != null) {
            invokeInnerCloseAndRetire(sessionId, session, closeAction)
        } else {
            retireIfQuiescent(sessionId, session)
        }
    }

    private fun authenticateOrRefuse(
        sessionId: String,
        session: SessionState,
        message: ConsoleClientMessage,
    ): Boolean {
        val claimed =
            synchronized(session.lock) {
                if (
                    session.phase != SessionPhase.REGISTERED ||
                        session.closeReason != null
                ) {
                    false
                } else {
                    session.phase = SessionPhase.AUTHENTICATING
                    true
                }
            }
        if (!claimed) {
            throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
        }

        val hello = message.payload as? ClientHelloPayload
        val authentication =
            hello?.authentication
                ?: rejectAuthentication(
                    sessionId = sessionId,
                    session = session,
                    code = ProtocolErrorCode.AUTHENTICATION_REQUIRED,
                )

        val principal =
            try {
                verifier.verify(authentication)
            } catch (_: Exception) {
                recordRequired(
                    session = session,
                    event =
                        auditEvent(
                            kind = ConsoleAuditKind.AUTHENTICATION_FAILED,
                            sessionId = sessionId,
                            principal = null,
                            outcome = "failed",
                            reason = "verifier_unavailable",
                            detail = null,
                        ),
                )
                markRejected(session)
                throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
            }
        if (principal == null) {
            rejectAuthentication(
                sessionId = sessionId,
                session = session,
                code = ProtocolErrorCode.AUTHENTICATION_FAILED,
            )
        }

        recordRequired(
            session = session,
            event =
                auditEvent(
                    kind = ConsoleAuditKind.AUTHENTICATION_SUCCEEDED,
                    sessionId = sessionId,
                    principal = principal,
                    outcome = "succeeded",
                    reason = null,
                    detail = "role=${principal.role.name.lowercase()}",
                ),
        )

        val beginInnerWorkflow =
            synchronized(session.lock) {
                if (
                    session.phase != SessionPhase.AUTHENTICATING ||
                        session.closeReason != null
                ) {
                    false
                } else {
                    session.phase = SessionPhase.OPENING_INNER
                    session.principal = principal
                    session.innerOpened = true
                    true
                }
            }
        if (!beginInnerWorkflow) return false

        var helloCompleted = false
        inner.onSessionOpened(sessionId)
        val mayForwardHello =
            synchronized(session.lock) {
                session.closeReason == null && !session.innerClosed
            }
        if (mayForwardHello) {
            inner.onClientMessage(
                sessionId,
                message.copy(payload = hello.copy(authentication = null)),
            )
            helloCompleted = true
        }
        return helloCompleted
    }

    private fun authorizeAndForward(
        sessionId: String,
        session: SessionState,
        message: ConsoleClientMessage,
    ) {
        if (message.payload is ClientHelloPayload) {
            throw ConsoleSessionProtocolException(ProtocolErrorCode.UNEXPECTED_MESSAGE)
        }
        val principal =
            synchronized(session.lock) {
                if (
                    session.phase != SessionPhase.AUTHENTICATED ||
                        session.closeReason != null ||
                        session.innerClosed
                ) {
                    null
                } else {
                    checkNotNull(session.principal)
                }
            } ?: throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)

        if (principal.role == ConsoleSessionRole.OBSERVER) {
            recordRequired(
                session = session,
                event =
                    auditEvent(
                        kind = ConsoleAuditKind.AUTHORIZATION_REFUSED,
                        sessionId = sessionId,
                        principal = principal,
                        outcome = "refused",
                        reason = "observer_role",
                        detail = "message_type=${message.type.wireName}",
                    ),
            )
            throw ConsoleSessionProtocolException(
                code = ProtocolErrorCode.AUTHORIZATION_FAILED,
                closeSession = false,
            )
        }

        val mayForward =
            synchronized(session.lock) {
                session.phase == SessionPhase.AUTHENTICATED &&
                    session.closeReason == null &&
                    !session.innerClosed
            }
        if (!mayForward) {
            throw ConsoleSessionProtocolException(ProtocolErrorCode.SERVER_UNAVAILABLE)
        }
        inner.onClientMessage(sessionId, message)
    }

    private fun rejectAuthentication(
        sessionId: String,
        session: SessionState,
        code: ProtocolErrorCode,
    ): Nothing {
        recordRequired(
            session = session,
            event =
                auditEvent(
                    kind = ConsoleAuditKind.AUTHENTICATION_FAILED,
                    sessionId = sessionId,
                    principal = null,
                    outcome = "failed",
                    reason = code.wireName,
                    detail = null,
                ),
        )
        markRejected(session)
        throw ConsoleSessionProtocolException(code)
    }

    private fun recordRequired(
        session: SessionState,
        event: ConsoleAuditEvent,
    ) {
        try {
            auditSink.record(event)
        } catch (failure: Exception) {
            markRejected(session)
            throw failure
        }
    }

    private fun markRejected(session: SessionState) {
        synchronized(session.lock) {
            if (session.phase != SessionPhase.CLOSED) session.phase = SessionPhase.REJECTED
        }
    }

    private fun completeOperation(
        sessionId: String,
        session: SessionState,
        authenticatedHelloCompleted: Boolean,
    ) {
        val closeAction =
            synchronized(session.lock) {
                check(session.activeOperations > 0) { "authentication operation accounting underflow" }
                if (
                    authenticatedHelloCompleted &&
                        session.phase == SessionPhase.OPENING_INNER &&
                        session.closeReason == null &&
                        !session.innerClosed
                ) {
                    session.phase = SessionPhase.AUTHENTICATED
                }
                session.activeOperations -= 1
                session.claimInnerCloseLocked(sessionId)
            }
        if (closeAction != null) {
            invokeInnerCloseAndRetire(sessionId, session, closeAction)
        } else {
            retireIfQuiescent(sessionId, session)
        }
    }

    private fun invokeInnerCloseAndRetire(
        sessionId: String,
        session: SessionState,
        closeAction: InnerCloseAction,
    ) {
        try {
            closeAction.invoke(inner)
        } finally {
            synchronized(session.lock) {
                session.innerCloseInFlight = false
                session.innerClosed = true
            }
            retireIfQuiescent(sessionId, session)
        }
    }

    private fun retireIfQuiescent(
        sessionId: String,
        session: SessionState,
    ) {
        val quiescent = synchronized(session.lock) { session.canRetireLocked() }
        if (!quiescent) return
        synchronized(sessionsLock) {
            if (sessions[sessionId] === session) sessions.remove(sessionId)
        }
    }

    private fun auditEvent(
        kind: ConsoleAuditKind,
        sessionId: String,
        principal: AuthenticatedConsolePrincipal?,
        outcome: String,
        reason: String?,
        detail: String?,
    ): ConsoleAuditEvent =
        ConsoleAuditEvent(
            kind = kind,
            timestampEpochMillis = epochClock.nowMillis(),
            monotonicNanos = monotonicClock.nowNanos(),
            sessionId = sessionId,
            leaseId = null,
            subjectId = principal?.subjectId,
            intentDigestSha256 = null,
            outcome = outcome,
            reason = reason,
            detail = detail,
        )

    private class SessionState {
        val lock = Any()
        var phase = SessionPhase.REGISTERED
        var principal: AuthenticatedConsolePrincipal? = null
        var innerOpened = false
        var activeOperations = 0
        var closeReason: String? = null
        var innerCloseInFlight = false
        var innerClosed = false

        fun claimInnerCloseLocked(sessionId: String): InnerCloseAction? {
            val reason = closeReason
            return if (
                innerOpened &&
                    activeOperations == 0 &&
                    !innerCloseInFlight &&
                    !innerClosed &&
                    reason != null
            ) {
                innerCloseInFlight = true
                InnerCloseAction(sessionId, reason)
            } else {
                null
            }
        }

        fun canRetireLocked(): Boolean =
            closeReason != null &&
                activeOperations == 0 &&
                !innerCloseInFlight &&
                (!innerOpened || innerClosed)
    }

    private enum class SessionPhase {
        REGISTERED,
        AUTHENTICATING,
        OPENING_INNER,
        AUTHENTICATED,
        REJECTED,
        CLOSED,
    }

    private data class InnerCloseAction(
        val sessionId: String,
        val reason: String,
    ) {
        fun invoke(inner: ConsoleClientSessionHandler) {
            inner.onSessionClosed(sessionId, reason)
        }
    }
}
