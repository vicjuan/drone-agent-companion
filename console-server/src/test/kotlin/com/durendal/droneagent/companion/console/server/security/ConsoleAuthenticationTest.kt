package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.console.protocol.AuthenticationPresentation
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleAuthenticationTest {
    @Test
    fun `canonical high entropy bearer token resolves only its server owned identity and role`() {
        val verifier = verifier()
        val operatorToken = operatorToken()
        val observerToken = observerToken()

        assertEquals(
            AuthenticatedConsolePrincipal("operator-1", ConsoleSessionRole.OPERATOR),
            verifier.verify(AuthenticationPresentation("bearer", operatorToken)),
        )
        assertEquals(
            AuthenticatedConsolePrincipal("observer-1", ConsoleSessionRole.OBSERVER),
            verifier.verify(AuthenticationPresentation("bearer", observerToken)),
        )
        assertNull(verifier.verify(AuthenticationPresentation("Bearer", operatorToken)))
        assertNull(verifier.verify(AuthenticationPresentation("bearer", "A".repeat(43))))
        assertNull(verifier.verify(AuthenticationPresentation("bearer", "$operatorToken=")))
        assertNull(verifier.verify(AuthenticationPresentation("bearer", "not-a-token")))
    }

    @Test
    fun `domain separated provisioning digest is stable and does not retain raw token`() {
        val operatorToken = operatorToken()
        assertEquals(
            OPERATOR_DIGEST,
            ConsoleBearerTokenVerifier.digestTokenForProvisioning(operatorToken),
        )
        val credential =
            ConsoleBearerCredentialDigest(
                subjectId = "operator-1",
                role = ConsoleSessionRole.OPERATOR,
                tokenDigestSha256 = OPERATOR_DIGEST,
            )

        assertFalse(credential.toString().contains(operatorToken))
        assertFalse(credential.toString().contains(OPERATOR_DIGEST))
        assertTrue(credential.toString().contains("<redacted>"))
        assertFalse(isDataLike(credential.javaClass))
    }

    @Test
    fun `digest configuration rejects ambiguous identities and malformed inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleBearerCredentialDigest("bad subject", ConsoleSessionRole.OPERATOR, OPERATOR_DIGEST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleBearerCredentialDigest("operator-1", ConsoleSessionRole.OPERATOR, "A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleBearerTokenVerifier(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleBearerTokenVerifier(
                listOf(
                    credential("same", ConsoleSessionRole.OPERATOR, OPERATOR_DIGEST),
                    credential("same", ConsoleSessionRole.OBSERVER, OBSERVER_DIGEST),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleBearerTokenVerifier(
                listOf(
                    credential("operator-1", ConsoleSessionRole.OPERATOR, OPERATOR_DIGEST),
                    credential("observer-1", ConsoleSessionRole.OBSERVER, OPERATOR_DIGEST),
                ),
            )
        }
    }

    @Test
    fun `handshake security exposes only fixed server owned modes`() {
        assertFalse(ConsoleHandshakeSecurity.DISABLED.authenticationRequired)
        assertEquals(emptyList<String>(), ConsoleHandshakeSecurity.DISABLED.acceptedAuthenticationSchemes)
        assertTrue(ConsoleHandshakeSecurity.BEARER_REQUIRED.authenticationRequired)
        assertEquals(
            listOf("bearer"),
            ConsoleHandshakeSecurity.BEARER_REQUIRED.acceptedAuthenticationSchemes,
        )
        val hello = ConsoleHandshakeSecurity.BEARER_REQUIRED.serverHello("session-1", "server-1")
        assertEquals("session-1", hello.sessionId)
        assertEquals("server-1", hello.serverVersion)
        assertEquals("1.0", hello.selectedProtocolVersion)
        assertTrue(hello.authenticationRequired)
        assertEquals(listOf("bearer"), hello.acceptedAuthenticationSchemes)
        assertTrue(ConsoleHandshakeSecurity::class.java.constructors.isEmpty())
    }

    private fun verifier() =
        ConsoleBearerTokenVerifier(
            listOf(
                credential("operator-1", ConsoleSessionRole.OPERATOR, OPERATOR_DIGEST),
                credential("observer-1", ConsoleSessionRole.OBSERVER, OBSERVER_DIGEST),
            ),
        )

    private fun credential(
        subjectId: String,
        role: ConsoleSessionRole,
        digest: String,
    ) = ConsoleBearerCredentialDigest(subjectId, role, digest)

    private fun isDataLike(type: Class<*>): Boolean =
        type.methods.any { method -> method.name == "copy" || method.name.startsWith("component") }

    private fun operatorToken(): String = canonicalToken { index -> index.toByte() }

    private fun observerToken(): String = canonicalToken { index -> (255 - index).toByte() }

    private fun canonicalToken(byteAt: (Int) -> Byte): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32, byteAt))

    private companion object {
        const val OPERATOR_DIGEST = "888ec2dbf9347b28333c385bb9293b10f3223bba94763cd779608dece8c81dcc"
        const val OBSERVER_DIGEST = "46d81bc27bcf9ac4b03db04c8b245e1b8f31d7eb7eef24a0a45851e6d9744840"
    }
}
