package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.console.protocol.AuthenticationPresentation
import com.durendal.droneagent.companion.console.protocol.ClientHelloPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleClientMessage
import com.durendal.droneagent.companion.console.protocol.ConsoleClientPayload
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolModule
import com.durendal.droneagent.companion.console.protocol.ControlFramePayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralPayload
import com.durendal.droneagent.companion.console.protocol.ControlNeutralReason
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandAction
import com.durendal.droneagent.companion.console.protocol.DiscreteCommandRequestPayload
import com.durendal.droneagent.companion.console.protocol.LeaseAcquirePayload
import com.durendal.droneagent.companion.console.protocol.LeaseReleasePayload
import com.durendal.droneagent.companion.console.protocol.LeaseRenewPayload
import com.durendal.droneagent.companion.console.protocol.ProtocolErrorCode
import com.durendal.droneagent.companion.console.server.ConsoleAuditEvent
import com.durendal.droneagent.companion.console.server.ConsoleAuditKind
import com.durendal.droneagent.companion.console.server.ConsoleAuditSink
import com.durendal.droneagent.companion.console.server.ConsoleEpochClock
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.transport.ConsoleClientSessionHandler
import com.durendal.droneagent.companion.console.server.transport.ConsoleSessionProtocolException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatingConsoleClientSessionHandlerTest {
    @Test
    fun `operator authentication is audited before inner open and forwards only stripped hello`() {
        val order = CopyOnWriteArrayList<String>()
        val audit = RecordingAuditSink(order)
        val inner = RecordingHandler(order)
        val handler = handler(ConsoleSessionRole.OPERATOR, inner, audit)

        openSession(handler, "session-1")
        val token = token()
        handler.onClientMessage("session-1", hello("hello-1", token))

        assertEquals(
            listOf(
                "audit:authentication_succeeded",
                "inner:open",
                "inner:protocol",
                "inner:message",
            ),
            order,
        )
        assertEquals(listOf("session-1"), inner.opened)
        assertEquals(listOf("session-1" to ConsoleProtocolModule.PROTOCOL_VERSION), inner.selectedVersions)
        val forwarded = inner.messages.single().second
        assertEquals("hello-1", forwarded.messageId)
        assertNull((forwarded.payload as ClientHelloPayload).authentication)
        assertEquals("operator-1", audit.events.single().subjectId)
        assertEquals("role=operator", audit.events.single().detail)
        assertFalse(audit.events.single().toString().contains(token))
    }

    @Test
    fun `missing and invalid authentication are durably distinguished without opening inner`() {
        listOf(
            null to ProtocolErrorCode.AUTHENTICATION_REQUIRED,
            "A".repeat(43) to ProtocolErrorCode.AUTHENTICATION_FAILED,
        ).forEachIndexed { index, (token, expectedCode) ->
            val audit = RecordingAuditSink()
            val inner = RecordingHandler()
            val handler = handler(ConsoleSessionRole.OPERATOR, inner, audit)
            val sessionId = "session-$index"
            openSession(handler, sessionId)

            val error =
                assertThrows(ConsoleSessionProtocolException::class.java) {
                    handler.onClientMessage(sessionId, hello("hello-$index", token))
                }

            assertEquals(expectedCode, error.code)
            assertEquals(ConsoleAuditKind.AUTHENTICATION_FAILED, audit.events.single().kind)
            assertEquals(expectedCode.wireName, audit.events.single().reason)
            assertTrue(inner.opened.isEmpty())
            assertTrue(inner.messages.isEmpty())
            handler.onSessionClosed(sessionId, "rejected")
            assertTrue(inner.closed.isEmpty())
        }
    }

    @Test
    fun `observer remains connected but every lease command and control input is audited and refused`() {
        val audit = RecordingAuditSink()
        val inner = RecordingHandler()
        val handler = handler(ConsoleSessionRole.OBSERVER, inner, audit)
        openSession(handler, "session-1")
        handler.onClientMessage("session-1", hello("hello-1", token()))

        privilegedPayloads().forEachIndexed { index, payload ->
            val error =
                assertThrows(ConsoleSessionProtocolException::class.java) {
                    handler.onClientMessage(
                        "session-1",
                        ConsoleClientMessage("privileged-$index", payload),
                    )
                }
            assertEquals(ProtocolErrorCode.AUTHORIZATION_FAILED, error.code)
            assertFalse(error.closeSession)
        }

        assertEquals(1, inner.messages.size)
        val refusals = audit.events.filter { it.kind == ConsoleAuditKind.AUTHORIZATION_REFUSED }
        assertEquals(privilegedPayloads().size, refusals.size)
        assertTrue(refusals.all { it.subjectId == "observer-1" && it.reason == "observer_role" })
        assertEquals(
            privilegedPayloads().map { "message_type=${it.messageType().wireName}" },
            refusals.map { it.detail },
        )
    }

    @Test
    fun `operator privileged message reaches inner after authenticated hello`() {
        val audit = RecordingAuditSink()
        val inner = RecordingHandler()
        val handler = handler(ConsoleSessionRole.OPERATOR, inner, audit)
        openSession(handler, "session-1")
        handler.onClientMessage("session-1", hello("hello-1", token()))

        val lease = ConsoleClientMessage("lease-1", LeaseAcquirePayload(5_000))
        handler.onClientMessage("session-1", lease)

        assertEquals(listOf("hello-1", "lease-1"), inner.messages.map { it.second.messageId })
        assertTrue(audit.events.none { it.kind == ConsoleAuditKind.AUTHORIZATION_REFUSED })
    }

    @Test
    fun `required authentication audit failure is terminal and never opens inner`() {
        val inner = RecordingHandler()
        val handler =
            handler(
                role = ConsoleSessionRole.OPERATOR,
                inner = inner,
                audit = ConsoleAuditSink { throw IllegalStateException("disk unavailable") },
            )
        openSession(handler, "session-1")

        assertThrows(IllegalStateException::class.java) {
            handler.onClientMessage("session-1", hello("hello-1", token()))
        }
        assertThrows(ConsoleSessionProtocolException::class.java) {
            handler.onClientMessage("session-1", hello("hello-2", token()))
        }
        handler.onSessionClosed("session-1", "audit_failed")

        assertTrue(inner.opened.isEmpty())
        assertTrue(inner.messages.isEmpty())
        assertTrue(inner.closed.isEmpty())
    }

    @Test
    fun `close while authentication audit is blocked never delegates pre-auth lifecycle`() {
        val auditEntered = CountDownLatch(1)
        val releaseAudit = CountDownLatch(1)
        val inner = RecordingHandler()
        val handler =
            handler(
                role = ConsoleSessionRole.OPERATOR,
                inner = inner,
                audit =
                    ConsoleAuditSink {
                        auditEntered.countDown()
                        check(releaseAudit.await(2, TimeUnit.SECONDS))
                    },
            )
        openSession(handler, "session-1")
        val failure = AtomicReference<Throwable?>()
        val authenticationThread =
            Thread {
                try {
                    handler.onClientMessage("session-1", hello("hello-1", token()))
                } catch (caught: Throwable) {
                    failure.set(caught)
                }
            }
        authenticationThread.start()
        assertTrue(auditEntered.await(1, TimeUnit.SECONDS))

        handler.onSessionClosed("session-1", "peer_closed")
        releaseAudit.countDown()
        authenticationThread.join(2_000L)

        assertFalse(authenticationThread.isAlive)
        assertNull(failure.get())
        assertTrue(inner.opened.isEmpty())
        assertTrue(inner.messages.isEmpty())
        assertTrue(inner.closed.isEmpty())
    }

    @Test
    fun `close racing inner open is deferred and delegated exactly once without hello`() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val inner =
            RecordingHandler(
                onOpen = {
                    openEntered.countDown()
                    check(releaseOpen.await(2, TimeUnit.SECONDS))
                },
            )
        val handler = handler(ConsoleSessionRole.OPERATOR, inner, RecordingAuditSink())
        openSession(handler, "session-1")
        val thread = Thread { handler.onClientMessage("session-1", hello("hello-1", token())) }
        thread.start()
        assertTrue(openEntered.await(1, TimeUnit.SECONDS))

        handler.onSessionClosed("session-1", "peer_closed")
        releaseOpen.countDown()
        thread.join(2_000L)

        assertFalse(thread.isAlive)
        assertEquals(listOf("session-1"), inner.opened)
        assertTrue(inner.messages.isEmpty())
        assertEquals(listOf("session-1" to "peer_closed"), inner.closed)
    }

    @Test
    fun `authorization audit failure does not reach inner privileged handler`() {
        var writes = 0
        val inner = RecordingHandler()
        val handler =
            handler(
                role = ConsoleSessionRole.OBSERVER,
                inner = inner,
                audit =
                    ConsoleAuditSink {
                        writes += 1
                        if (writes > 1) throw IllegalStateException("disk unavailable")
                    },
            )
        openSession(handler, "session-1")
        handler.onClientMessage("session-1", hello("hello-1", token()))

        assertThrows(IllegalStateException::class.java) {
            handler.onClientMessage(
                "session-1",
                ConsoleClientMessage("lease-1", LeaseAcquirePayload(5_000)),
            )
        }

        assertEquals(1, inner.messages.size)
        handler.onSessionClosed("session-1", "audit_failed")
        assertEquals(listOf("session-1" to "audit_failed"), inner.closed)
    }

    @Test
    fun `verifier exception is durably sanitized and never reaches inner`() {
        val token = token()
        val audit = RecordingAuditSink()
        val inner = RecordingHandler()
        val handler =
            AuthenticatingConsoleClientSessionHandler(
                inner = inner,
                verifier =
                    ConsoleAuthenticationVerifier {
                        throw IllegalStateException("secret verifier failure $token")
                    },
                auditSink = audit,
                epochClock = ConsoleEpochClock { 1_700_000_000_000L },
                monotonicClock = ConsoleMonotonicClock { 123_000_000L },
            )
        openSession(handler, "session-1")

        val error =
            assertThrows(ConsoleSessionProtocolException::class.java) {
                handler.onClientMessage("session-1", hello("hello-1", token))
            }

        assertEquals(ProtocolErrorCode.SERVER_UNAVAILABLE, error.code)
        val event = audit.events.single()
        assertEquals(ConsoleAuditKind.AUTHENTICATION_FAILED, event.kind)
        assertEquals("verifier_unavailable", event.reason)
        assertFalse(event.toString().contains(token))
        assertFalse(event.toString().contains("secret verifier failure"))
        assertTrue(inner.opened.isEmpty())
        assertTrue(inner.messages.isEmpty())
    }

    @Test
    fun `operator input cannot overtake blocked inner open and stripped hello`() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val inner =
            RecordingHandler(
                onOpen = {
                    openEntered.countDown()
                    check(releaseOpen.await(2, TimeUnit.SECONDS))
                },
            )
        val handler = handler(ConsoleSessionRole.OPERATOR, inner, RecordingAuditSink())
        openSession(handler, "session-1")
        val firstFailure = AtomicReference<Throwable?>()
        val authenticationThread =
            Thread {
                try {
                    handler.onClientMessage("session-1", hello("hello-1", token()))
                } catch (caught: Throwable) {
                    firstFailure.set(caught)
                }
            }
        authenticationThread.start()
        assertTrue(openEntered.await(1, TimeUnit.SECONDS))

        val overtaking =
            assertThrows(ConsoleSessionProtocolException::class.java) {
                handler.onClientMessage(
                    "session-1",
                    ConsoleClientMessage("lease-early", LeaseAcquirePayload(5_000)),
                )
            }
        assertEquals(ProtocolErrorCode.SERVER_UNAVAILABLE, overtaking.code)
        assertTrue(inner.messages.isEmpty())

        releaseOpen.countDown()
        authenticationThread.join(2_000L)
        assertFalse(authenticationThread.isAlive)
        assertNull(firstFailure.get())
        handler.onClientMessage(
            "session-1",
            ConsoleClientMessage("lease-after-hello", LeaseAcquirePayload(5_000)),
        )
        assertEquals(listOf("hello-1", "lease-after-hello"), inner.messages.map { it.second.messageId })
    }

    @Test
    fun `closed session tombstone prevents id reuse until old callback and inner close quiesce`() {
        val oldMessageEntered = CountDownLatch(1)
        val releaseOldMessage = CountDownLatch(1)
        val inner =
            RecordingHandler(
                onMessage = { message ->
                    if (message.messageId == "lease-blocked") {
                        oldMessageEntered.countDown()
                        check(releaseOldMessage.await(2, TimeUnit.SECONDS))
                    }
                },
            )
        val handler = handler(ConsoleSessionRole.OPERATOR, inner, RecordingAuditSink())
        openSession(handler, "session-reused")
        handler.onClientMessage("session-reused", hello("hello-old", token()))
        val oldFailure = AtomicReference<Throwable?>()
        val oldThread =
            Thread {
                try {
                    handler.onClientMessage(
                        "session-reused",
                        ConsoleClientMessage("lease-blocked", LeaseAcquirePayload(5_000)),
                    )
                } catch (caught: Throwable) {
                    oldFailure.set(caught)
                }
            }
        oldThread.start()
        assertTrue(oldMessageEntered.await(1, TimeUnit.SECONDS))

        handler.onSessionClosed("session-reused", "old_peer_closed")
        assertTrue(inner.closed.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            handler.onSessionOpened("session-reused")
        }

        releaseOldMessage.countDown()
        oldThread.join(2_000L)
        assertFalse(oldThread.isAlive)
        assertNull(oldFailure.get())
        assertEquals(listOf("session-reused" to "old_peer_closed"), inner.closed)

        openSession(handler, "session-reused")
        handler.onSessionClosed("session-reused", "new_pre_auth_close")
        assertEquals(listOf("session-reused" to "old_peer_closed"), inner.closed)
    }

    private fun handler(
        role: ConsoleSessionRole,
        inner: ConsoleClientSessionHandler,
        audit: ConsoleAuditSink,
    ): AuthenticatingConsoleClientSessionHandler {
        val subject = if (role == ConsoleSessionRole.OPERATOR) "operator-1" else "observer-1"
        return AuthenticatingConsoleClientSessionHandler(
            inner = inner,
            verifier =
                ConsoleBearerTokenVerifier(
                    listOf(
                        ConsoleBearerCredentialDigest(
                            subjectId = subject,
                            role = role,
                            tokenDigestSha256 = digest(),
                        ),
                    ),
                ),
            auditSink = audit,
            epochClock = ConsoleEpochClock { 1_700_000_000_000L },
            monotonicClock = ConsoleMonotonicClock { 123_000_000L },
        )
    }

    private fun openSession(
        handler: ConsoleClientSessionHandler,
        sessionId: String,
    ) {
        handler.onSessionOpened(sessionId)
        handler.onProtocolSelected(sessionId, ConsoleProtocolModule.PROTOCOL_VERSION)
    }

    private fun hello(
        messageId: String,
        token: String?,
    ): ConsoleClientMessage =
        ConsoleClientMessage(
            messageId = messageId,
            payload =
                ClientHelloPayload(
                    clientName = "web-console",
                    clientVersion = "0.1.0",
                    supportedProtocolVersions = listOf("1.0"),
                    authentication = token?.let { AuthenticationPresentation("bearer", it) },
                ),
        )

    private fun privilegedPayloads(): List<ConsoleClientPayload> =
        listOf(
            LeaseAcquirePayload(5_000),
            LeaseRenewPayload("lease-1", 5_000),
            LeaseReleasePayload("lease-1"),
            DiscreteCommandRequestPayload(
                commandId = "command-1",
                leaseId = "lease-1",
                action = DiscreteCommandAction.TAKEOFF,
                ttlMs = 500,
            ),
            ControlFramePayload(
                leaseId = "lease-1",
                inputSequence = 1L,
                ttlMs = 100,
                forward = 0.0,
                right = 0.0,
                up = 0.0,
                yaw = 0.0,
            ),
            ControlNeutralPayload(
                leaseId = "lease-1",
                inputSequence = 2L,
                reason = ControlNeutralReason.OPERATOR_RELEASE,
            ),
        )

    private class RecordingAuditSink(
        private val order: MutableList<String>? = null,
    ) : ConsoleAuditSink {
        val events = CopyOnWriteArrayList<ConsoleAuditEvent>()

        override fun record(event: ConsoleAuditEvent) {
            events += event
            order?.add("audit:${event.kind.name.lowercase()}")
        }
    }

    private class RecordingHandler(
        private val order: MutableList<String>? = null,
        private val onOpen: () -> Unit = {},
        private val onMessage: (ConsoleClientMessage) -> Unit = {},
    ) : ConsoleClientSessionHandler {
        val opened = CopyOnWriteArrayList<String>()
        val selectedVersions = CopyOnWriteArrayList<Pair<String, String>>()
        val messages = CopyOnWriteArrayList<Pair<String, ConsoleClientMessage>>()
        val closed = CopyOnWriteArrayList<Pair<String, String>>()

        override fun onSessionOpened(sessionId: String) {
            opened += sessionId
            order?.add("inner:open")
            onOpen()
        }

        override fun onProtocolSelected(
            sessionId: String,
            selectedProtocolVersion: String,
        ) {
            selectedVersions += sessionId to selectedProtocolVersion
            order?.add("inner:protocol")
        }

        override fun onClientMessage(
            sessionId: String,
            message: ConsoleClientMessage,
        ) {
            messages += sessionId to message
            order?.add("inner:message")
            onMessage(message)
        }

        override fun onSessionClosed(
            sessionId: String,
            reason: String,
        ) {
            closed += sessionId to reason
            order?.add("inner:close")
        }
    }

    private fun token(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { index -> index.toByte() })

    private fun digest(): String = ConsoleBearerTokenVerifier.digestTokenForProvisioning(token())
}
