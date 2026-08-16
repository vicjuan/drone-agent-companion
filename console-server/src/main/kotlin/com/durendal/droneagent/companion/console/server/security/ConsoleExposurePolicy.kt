package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkBinding
import java.net.URI

/** Browser-console exposure modes whose transport boundary has been explicitly selected. */
enum class ConsoleExposureProfile {
    LOCALHOST_DEVELOPMENT,
    POINT_TO_POINT_COMMISSIONING,
    SHARED_ROUTABLE_PRODUCTION,
}

/**
 * Immutable endpoint selection consumed by [ConsoleServerConfig][com.durendal.droneagent.companion.console.server.transport.ConsoleServerConfig].
 *
 * Implementations are private and non-data classes. Callers can observe the selected boundary but
 * cannot construct, copy, or override its bind address, browser origin, expected peer, or runtime
 * network identity.
 */
sealed interface ConsoleExposure {
    val profile: ConsoleExposureProfile
    val bindHost: String
    val bindPort: Int
    val allowedBrowserOrigin: String
    val expectedRemotePeerIpv4: String?
    val runtimeNetworkId: String?
}

/**
 * Validated commissioning endpoint metadata that is deliberately not a runnable
 * [ConsoleExposure]. A future Android composition may consume it only together with a fresh
 * inventory generation and a live Network-bound listener/pinner.
 */
sealed interface CommissioningExposureCandidate {
    val profile: ConsoleExposureProfile
    val bindHost: String
    val bindPort: Int
    val allowedBrowserOrigin: String
    val expectedRemotePeerIpv4: String
    val runtimeNetworkId: String
}

/** The only construction boundary for an unauthenticated cleartext console endpoint. */
object ConsoleExposurePolicy {
    private const val IPV4_LOOPBACK = "127.0.0.1"
    private const val COMMISSIONING_HTTP_PORT = 8080

    /**
     * Selects the development surface. Both the listener and browser origin are fixed to IPv4
     * loopback; only their ports are selectable for test isolation and adb forwarding.
     */
    fun localhostDevelopment(
        bindPort: Int,
        browserPort: Int = bindPort,
    ): ConsoleExposure {
        requirePort(bindPort, allowEphemeral = true, field = "bindPort")
        requirePort(browserPort, allowEphemeral = true, field = "browserPort")
        return LocalhostExposure(
            bindPort = bindPort,
            allowedBrowserOrigin = httpOrigin(IPV4_LOOPBACK, browserPort),
        )
    }

    /**
     * Validates isolated commissioning metadata from the policy's unforgeable accepted binding.
     * The result is deliberately not runnable: binding a numeric local address alone is not
     * Android Network identity or inventory-freshness proof.
     */
    fun pointToPointCommissioningCandidate(
        binding: CommissioningNetworkBinding,
    ): CommissioningExposureCandidate {
        val bindAddress = requireCanonicalIpv4(binding.bindIpv4, "binding.bindIpv4")
        val peerAddress = requireCanonicalIpv4(binding.operatorPeerIpv4, "binding.operatorPeerIpv4")
        require(binding.prefixLength == 30) { "commissioning exposure requires prefixLength 30" }
        require(bindAddress != peerAddress && bindAddress ushr 2 == peerAddress ushr 2) {
            "commissioning endpoints must be distinct addresses in the same /30"
        }
        require(binding.runtimeNetworkId.isNotBlank()) {
            "commissioning runtime network identity must not be blank"
        }
        val expectedOrigin = httpOrigin(binding.bindIpv4, COMMISSIONING_HTTP_PORT)
        require(binding.httpOrigin == expectedOrigin) {
            "commissioning browser origin must exactly match the accepted bind endpoint"
        }
        return CommissioningExposure(
            bindHost = binding.bindIpv4,
            allowedBrowserOrigin = expectedOrigin,
            expectedRemotePeerIpv4 = binding.operatorPeerIpv4,
            runtimeNetworkId = binding.runtimeNetworkId,
        )
    }

    /**
     * Commissioning cannot become runnable from a previously accepted snapshot in this slice.
     * Activation requires a live Android Network pinner plus inventory-generation freshness, so a
     * link-loss or route-change event cannot reuse a stale candidate.
     */
    fun activatePointToPointCommissioning(
        candidate: CommissioningExposureCandidate,
    ): ConsoleExposure {
        check(candidate.profile == ConsoleExposureProfile.POINT_TO_POINT_COMMISSIONING)
        throw UnsupportedOperationException(
            "commissioning exposure requires live Network pinning and freshness verification",
        )
    }

    /** Shared or routable production exposure remains unavailable until authentication and TLS exist. */
    fun sharedRoutableProduction(): ConsoleExposure =
        throw UnsupportedOperationException(
            "shared routable production console exposure requires authentication and TLS",
        )

    internal fun acceptsRemoteAddress(
        exposure: ConsoleExposure,
        remoteAddress: String,
    ): Boolean =
        when (exposure.profile) {
            ConsoleExposureProfile.LOCALHOST_DEVELOPMENT ->
                remoteAddress == "localhost" || isCanonicalIpv4Loopback(remoteAddress)
            ConsoleExposureProfile.POINT_TO_POINT_COMMISSIONING -> false
            ConsoleExposureProfile.SHARED_ROUTABLE_PRODUCTION -> false
        }

    private class LocalhostExposure(
        override val bindPort: Int,
        override val allowedBrowserOrigin: String,
    ) : ConsoleExposure {
        override val profile: ConsoleExposureProfile = ConsoleExposureProfile.LOCALHOST_DEVELOPMENT
        override val bindHost: String = IPV4_LOOPBACK
        override val expectedRemotePeerIpv4: String? = null
        override val runtimeNetworkId: String? = null
    }

    private class CommissioningExposure(
        override val bindHost: String,
        override val allowedBrowserOrigin: String,
        override val expectedRemotePeerIpv4: String,
        override val runtimeNetworkId: String,
    ) : CommissioningExposureCandidate {
        override val profile: ConsoleExposureProfile =
            ConsoleExposureProfile.POINT_TO_POINT_COMMISSIONING
        override val bindPort: Int = COMMISSIONING_HTTP_PORT
    }

    private fun requirePort(
        value: Int,
        allowEphemeral: Boolean,
        field: String,
    ) {
        val minimum = if (allowEphemeral) 0 else 1
        require(value in minimum..65_535) { "$field must be between $minimum and 65535" }
    }

    private fun httpOrigin(
        host: String,
        port: Int,
    ): String =
        URI("http", null, host, port, null, null, null).toASCIIString()

    private fun isCanonicalIpv4Loopback(raw: String): Boolean =
        parseCanonicalIpv4(raw)?.let { address -> address ushr 24 == 127 } == true

    private fun requireCanonicalIpv4(
        raw: String,
        field: String,
    ): Int = requireNotNull(parseCanonicalIpv4(raw)) { "$field must be canonical numeric IPv4" }

    private fun parseCanonicalIpv4(raw: String): Int? {
        if (raw.isEmpty() || raw != raw.trim() || raw.any { !it.isDigit() && it != '.' }) return null
        val components = raw.split('.')
        if (components.size != 4) return null
        var result = 0
        components.forEach { component ->
            val octet =
                component.toIntOrNull()?.takeIf { value ->
                    value in 0..255 && component == value.toString()
                } ?: return null
            result = (result shl 8) or octet
        }
        return result
    }
}
