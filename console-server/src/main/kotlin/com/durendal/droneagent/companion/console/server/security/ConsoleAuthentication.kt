package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.console.protocol.AuthenticationPresentation
import com.durendal.droneagent.companion.console.protocol.ConsoleMessageValidator
import com.durendal.droneagent.companion.console.protocol.ServerHelloPayload
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Server-owned authorization role. No browser field can select or upgrade this value. */
enum class ConsoleSessionRole {
    OBSERVER,
    OPERATOR,
}

/** Identity returned only after a configured credential digest has matched. */
data class AuthenticatedConsolePrincipal(
    val subjectId: String,
    val role: ConsoleSessionRole,
)

/**
 * Immutable, server-owned authentication input. It contains only a domain-separated digest, never
 * the presented bearer token. Provisioning must inject this value at runtime; raw tokens must not
 * be accepted through command-line arguments, URLs, BuildConfig, logs, or checked-in files.
 */
class ConsoleBearerCredentialDigest(
    val subjectId: String,
    val role: ConsoleSessionRole,
    val tokenDigestSha256: String,
) {
    init {
        ConsoleMessageValidator.requireMessageId(subjectId)
        require(SHA256_PATTERN.matches(tokenDigestSha256)) {
            "tokenDigestSha256 must be lowercase SHA-256"
        }
    }

    override fun toString(): String =
        "ConsoleBearerCredentialDigest(subjectId=$subjectId, role=$role, tokenDigestSha256=<redacted>)"

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Authentication boundary used by the session decorator.
 *
 * Implementations must treat [AuthenticationPresentation.credential] as ephemeral: never retain,
 * log, reflect, or include it in an exception. [ConsoleBearerTokenVerifier] is the production
 * implementation supplied by this kernel.
 */
fun interface ConsoleAuthenticationVerifier {
    fun verify(presentation: AuthenticationPresentation): AuthenticatedConsolePrincipal?
}

/**
 * Verifies canonical 32-byte (43-character unpadded base64url) bearer tokens against immutable
 * domain-separated SHA-256 digests. Every configured digest is compared with
 * [MessageDigest.isEqual]; verification never exits early after a match.
 *
 * This class deliberately does not implement token generation, persistence, rotation, revocation,
 * credential lifecycle, or session expiry. Until those controls and authenticated TLS transport
 * are implemented, shared/routable production console exposure must remain disabled.
 */
class ConsoleBearerTokenVerifier(
    credentials: Collection<ConsoleBearerCredentialDigest>,
) : ConsoleAuthenticationVerifier {
    private val records: List<CredentialRecord>

    init {
        require(credentials.isNotEmpty()) { "at least one bearer credential digest is required" }
        require(credentials.map { it.subjectId }.toSet().size == credentials.size) {
            "bearer credential subject ids must be unique"
        }
        require(credentials.map { it.tokenDigestSha256 }.toSet().size == credentials.size) {
            "bearer credential digests must be unique"
        }
        records =
            credentials.map { credential ->
                CredentialRecord(
                    principal =
                        AuthenticatedConsolePrincipal(
                            subjectId = credential.subjectId,
                            role = credential.role,
                        ),
                    expectedDigest = credential.tokenDigestSha256.hexToBytes(),
                )
            }
    }

    override fun verify(presentation: AuthenticationPresentation): AuthenticatedConsolePrincipal? {
        if (presentation.scheme != BEARER_SCHEME) return null
        val tokenBytes = decodeCanonicalToken(presentation.credential) ?: return null
        return try {
            val presentedDigest = digest(tokenBytes)
            try {
                var matchedPrincipal: AuthenticatedConsolePrincipal? = null
                records.forEach { record ->
                    if (MessageDigest.isEqual(presentedDigest, record.expectedDigest)) {
                        matchedPrincipal = record.principal
                    }
                }
                matchedPrincipal
            } finally {
                presentedDigest.fill(0)
            }
        } finally {
            tokenBytes.fill(0)
        }
    }

    private data class CredentialRecord(
        val principal: AuthenticatedConsolePrincipal,
        val expectedDigest: ByteArray,
    )

    companion object {
        const val BEARER_SCHEME = "bearer"
        const val TOKEN_LENGTH = 43
        private const val TOKEN_BYTES = 32
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{$TOKEN_LENGTH}$")
        private val DIGEST_DOMAIN =
            "drone-agent-companion/console-bearer/v1\u0000"
                .toByteArray(StandardCharsets.US_ASCII)

        /**
         * Produces the digest value consumed by [ConsoleBearerCredentialDigest]. This helper keeps
         * no token state and emits no output other than the digest; a future provisioning workflow
         * must still provide protected input/output handling and lifecycle controls.
         */
        internal fun digestTokenForProvisioning(token: String): String {
            val tokenBytes =
                requireNotNull(decodeCanonicalToken(token)) {
                    "bearer token must be canonical 43-character base64url for exactly 32 bytes"
                }
            return try {
                val tokenDigest = digest(tokenBytes)
                try {
                    tokenDigest.toHex()
                } finally {
                    tokenDigest.fill(0)
                }
            } finally {
                tokenBytes.fill(0)
            }
        }

        private fun decodeCanonicalToken(token: String): ByteArray? {
            if (!TOKEN_PATTERN.matches(token)) return null
            val decoded =
                try {
                    Base64.getUrlDecoder().decode(token)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            if (decoded.size != TOKEN_BYTES || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != token) {
                decoded.fill(0)
                return null
            }
            return decoded
        }

        private fun digest(tokenBytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .apply {
                    update(DIGEST_DOMAIN)
                    update(tokenBytes)
                }.digest()

        private fun String.hexToBytes(): ByteArray =
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

/** Server-owned metadata emitted in `server_hello`; clients cannot influence either value. */
internal class ConsoleHandshakeSecurity private constructor(
    val authenticationRequired: Boolean,
    val acceptedAuthenticationSchemes: List<String>,
) {
    internal fun serverHello(
        sessionId: String,
        serverVersion: String,
        selectedProtocolVersion: String,
    ): ServerHelloPayload =
        ServerHelloPayload(
            sessionId = sessionId,
            serverVersion = serverVersion,
            selectedProtocolVersion = selectedProtocolVersion,
            authenticationRequired = authenticationRequired,
            acceptedAuthenticationSchemes = acceptedAuthenticationSchemes,
        )

    companion object {
        val DISABLED = ConsoleHandshakeSecurity(false, emptyList())
        val BEARER_REQUIRED =
            ConsoleHandshakeSecurity(
                authenticationRequired = true,
                acceptedAuthenticationSchemes = listOf(ConsoleBearerTokenVerifier.BEARER_SCHEME),
            )
    }
}
