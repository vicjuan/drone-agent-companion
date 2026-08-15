package com.durendal.droneagent.companion.commissioning.network

/** Stable fail-closed outcomes suitable for audit records and commissioning UI projection. */
enum class CommissioningNetworkRejection {
    INCOMPLETE_INVENTORY,
    MALFORMED_INVENTORY,
    DUPLICATE_STABLE_INTERFACE_ID,
    DUPLICATE_RUNTIME_NETWORK_ID,
    UNEXPECTED_DNS,
    UNEXPECTED_DEFAULT_ROUTE,
    UNEXPECTED_GATEWAY,
    UNEXPECTED_ROUTE,
    MISSING_EXPECTED_CONNECTED_ROUTE,
    MULTIPLE_EXPECTED_CONNECTED_ROUTE,
    MISSING_EXPECTED_ADDRESS,
    WRONG_PREFIX,
    MULTIPLE_EXPECTED_ADDRESS,
    TARGET_INTERFACE_DOWN,
    TARGET_NOT_ETHERNET,
    UNEXPECTED_TARGET_INTERFACE_ID,
    TARGET_HAS_UNEXPECTED_ROUTABLE_ADDRESS,
    OTHER_ACTIVE_ROUTABLE_INTERFACE,
}

sealed interface CommissioningNetworkDecision {
    data class Accepted(val binding: CommissioningNetworkBinding) : CommissioningNetworkDecision

    data class Rejected(val reason: CommissioningNetworkRejection) : CommissioningNetworkDecision
}

/** Canonical read-only output consumed by the composition root after policy acceptance. */
sealed interface CommissioningNetworkBinding {
    val stableInterfaceId: String
    val runtimeNetworkId: String
    val bindIpv4: String
    val operatorPeerIpv4: String
    val prefixLength: Int
    val httpOrigin: String
}

/**
 * Selects the single safe point-to-point Ethernet identity from a complete observed inventory.
 *
 * Version 1 intentionally accepts only an isolated RFC1918 `/30`: one operator address, one G520
 * address, one exact on-link route, no DNS/gateway/default/broad route, and no other active routed
 * interface. Every invocation reevaluates a complete immutable snapshot, so link loss cannot reuse
 * an earlier accepted binding.
 *
 * A successful local-IP bind does **not** provide Linux `SO_BINDTODEVICE` semantics. Android
 * composition must additionally pin and verify the runtime Network identity and firewall boundary,
 * and G520 commissioning must retain negative probes from every non-target interface.
 */
class CommissioningNetworkPolicy(
    expectedStableInterfaceId: String,
    operatorPeerIpv4: String,
    expectedG520Ipv4: String,
    val expectedPrefixLength: Int,
    val httpPort: Int,
) {
    val expectedStableInterfaceId: String =
        expectedStableInterfaceId.also {
            require(INTERFACE_ID_PATTERN.matches(it)) {
                "expectedStableInterfaceId must be a bounded canonical identifier"
            }
        }

    private val operatorAddress = parsePrivateIpv4(operatorPeerIpv4, "operatorPeerIpv4")
    private val g520Address = parsePrivateIpv4(expectedG520Ipv4, "expectedG520Ipv4")
    val operatorPeerIpv4: String = operatorAddress.canonical
    val expectedG520Ipv4: String = g520Address.canonical
    private val expectedNetwork: Int

    init {
        require(expectedPrefixLength == 30) { "commissioning profile v1 requires prefixLength 30" }
        require(httpPort == 8080) { "commissioning profile v1 requires HTTP port 8080" }
        val operatorNetwork = requireNotNull(operatorAddress.ipv4Network(expectedPrefixLength))
        val g520Network = requireNotNull(g520Address.ipv4Network(expectedPrefixLength))
        require(operatorNetwork == g520Network) { "operator and G520 addresses must share the same /30" }
        require(!operatorAddress.sameAddress(g520Address)) { "operator and G520 addresses must be distinct" }

        val operatorValue = requireNotNull(operatorAddress.ipv4Int()).toUInt()
        val g520Value = requireNotNull(g520Address.ipv4Int()).toUInt()
        val networkValue = operatorNetwork.toUInt()
        val usableHosts = setOf(networkValue + 1u, networkValue + 2u)
        require(operatorValue in usableHosts && g520Value in usableHosts) {
            "operator and G520 addresses must be the two usable /30 hosts"
        }
        expectedNetwork = operatorNetwork
    }

    fun select(inventory: ObservedNetworkInventory): CommissioningNetworkDecision {
        if (!inventory.isComplete || inventory.collectionFailures.isNotEmpty()) {
            return rejected(CommissioningNetworkRejection.INCOMPLETE_INVENTORY)
        }
        if (
            inventory.interfaces.any {
                !INTERFACE_ID_PATTERN.matches(it.stableInterfaceId) ||
                    !RUNTIME_NETWORK_ID_PATTERN.matches(it.runtimeNetworkId)
            }
        ) {
            return rejected(CommissioningNetworkRejection.MALFORMED_INVENTORY)
        }
        if (inventory.interfaces.map { it.stableInterfaceId }.distinct().size != inventory.interfaces.size) {
            return rejected(CommissioningNetworkRejection.DUPLICATE_STABLE_INTERFACE_ID)
        }
        if (inventory.interfaces.map { it.runtimeNetworkId }.distinct().size != inventory.interfaces.size) {
            return rejected(CommissioningNetworkRejection.DUPLICATE_RUNTIME_NETWORK_ID)
        }
        if (inventory.dnsServers.isNotEmpty()) {
            return rejected(CommissioningNetworkRejection.UNEXPECTED_DNS)
        }

        val parsedAddresses = mutableListOf<ParsedObservedAddress>()
        inventory.interfaces
            .asSequence()
            .forEach { observedInterface ->
                observedInterface.addresses.forEach { observedAddress ->
                    val parsed =
                        IpLiteral.parse(observedAddress.numericAddress)
                            ?: return rejected(CommissioningNetworkRejection.MALFORMED_INVENTORY)
                    if (!parsed.hasValidPrefix(observedAddress.prefixLength)) {
                        return rejected(CommissioningNetworkRejection.MALFORMED_INVENTORY)
                    }
                    parsedAddresses += ParsedObservedAddress(observedInterface, observedAddress, parsed)
                }
            }

        val addressMatches = parsedAddresses.filter { it.literal.sameAddress(g520Address) }
        if (addressMatches.isEmpty()) {
            return rejected(CommissioningNetworkRejection.MISSING_EXPECTED_ADDRESS)
        }
        if (addressMatches.size > 1) {
            return rejected(CommissioningNetworkRejection.MULTIPLE_EXPECTED_ADDRESS)
        }

        val selected = addressMatches.single()
        if (selected.observedAddress.prefixLength != expectedPrefixLength) {
            return rejected(CommissioningNetworkRejection.WRONG_PREFIX)
        }
        if (!selected.observedInterface.isUp) {
            return rejected(CommissioningNetworkRejection.TARGET_INTERFACE_DOWN)
        }
        if (selected.observedInterface.transport != ObservedInterfaceTransport.ETHERNET) {
            return rejected(CommissioningNetworkRejection.TARGET_NOT_ETHERNET)
        }
        if (selected.observedInterface.stableInterfaceId != expectedStableInterfaceId) {
            return rejected(CommissioningNetworkRejection.UNEXPECTED_TARGET_INTERFACE_ID)
        }

        val routeDecision = validateRoutes(inventory, selected.observedInterface)
        if (routeDecision != null) return routeDecision

        if (
            parsedAddresses.any {
                it.observedInterface === selected.observedInterface &&
                    it !== selected &&
                    it.observedInterface.isUp &&
                    !it.isBenignLocalAddress()
            }
        ) {
            return rejected(CommissioningNetworkRejection.TARGET_HAS_UNEXPECTED_ROUTABLE_ADDRESS)
        }
        if (
            parsedAddresses.any {
                it.observedInterface !== selected.observedInterface &&
                    it.observedInterface.isUp &&
                    !it.isBenignLocalAddress()
            }
        ) {
            return rejected(CommissioningNetworkRejection.OTHER_ACTIVE_ROUTABLE_INTERFACE)
        }

        return CommissioningNetworkDecision.Accepted(
            AcceptedCommissioningNetworkBinding(
                stableInterfaceId = selected.observedInterface.stableInterfaceId,
                runtimeNetworkId = selected.observedInterface.runtimeNetworkId,
                bindIpv4 = g520Address.canonical,
                operatorPeerIpv4 = operatorAddress.canonical,
                prefixLength = expectedPrefixLength,
                httpOrigin = "http://${g520Address.canonical}:$httpPort",
            ),
        )
    }

    private fun validateRoutes(
        inventory: ObservedNetworkInventory,
        selectedInterface: ObservedNetworkInterface,
    ): CommissioningNetworkDecision.Rejected? {
        var expectedConnectedRouteCount = 0
        inventory.interfaces
            .asSequence()
            .filter { it.isUp }
            .forEach { observedInterface ->
                observedInterface.routes.forEach { route ->
                    val destination =
                        IpLiteral.parse(route.destinationAddress)
                            ?: return rejected(CommissioningNetworkRejection.MALFORMED_INVENTORY)
                    if (!destination.hasValidPrefix(route.prefixLength)) {
                        return rejected(CommissioningNetworkRejection.MALFORMED_INVENTORY)
                    }
                    if (
                        route.isDefaultRoute ||
                        (route.prefixLength == 0 && destination.inetAddress.isAnyLocalAddress)
                    ) {
                        return rejected(CommissioningNetworkRejection.UNEXPECTED_DEFAULT_ROUTE)
                    }
                    if (route.gatewayAddress != null) {
                        return rejected(CommissioningNetworkRejection.UNEXPECTED_GATEWAY)
                    }

                    val isExpectedConnectedRoute =
                        observedInterface === selectedInterface &&
                            destination.family == IpLiteral.Family.IPV4 &&
                            route.prefixLength == expectedPrefixLength &&
                            destination.ipv4Network(route.prefixLength) == expectedNetwork &&
                            destination.ipv4Int() == expectedNetwork
                    if (isExpectedConnectedRoute) {
                        expectedConnectedRouteCount += 1
                    } else if (!isAllowedLocalRoute(observedInterface, route, destination)) {
                        return rejected(CommissioningNetworkRejection.UNEXPECTED_ROUTE)
                    }
                }
            }

        return when (expectedConnectedRouteCount) {
            0 -> rejected(CommissioningNetworkRejection.MISSING_EXPECTED_CONNECTED_ROUTE)
            1 -> null
            else -> rejected(CommissioningNetworkRejection.MULTIPLE_EXPECTED_CONNECTED_ROUTE)
        }
    }

    private fun rejected(reason: CommissioningNetworkRejection): CommissioningNetworkDecision.Rejected =
        CommissioningNetworkDecision.Rejected(reason)

    private fun isAllowedLocalRoute(
        observedInterface: ObservedNetworkInterface,
        route: ObservedIpRoute,
        destination: IpLiteral,
    ): Boolean =
        destination.isCanonicalNetwork(route.prefixLength) &&
            (
                destination.prefixIsContainedWithinLinkLocal(route.prefixLength) ||
                    (
                        observedInterface.transport == ObservedInterfaceTransport.LOOPBACK &&
                            destination.prefixIsContainedWithinLoopback(route.prefixLength)
                    )
            )

    private data class ParsedObservedAddress(
        val observedInterface: ObservedNetworkInterface,
        val observedAddress: ObservedIpAddress,
        val literal: IpLiteral,
    ) {
        fun isBenignLocalAddress(): Boolean =
            literal.prefixIsContainedWithinLinkLocal(observedAddress.prefixLength) ||
                (
                    observedInterface.transport == ObservedInterfaceTransport.LOOPBACK &&
                        literal.prefixIsContainedWithinLoopback(observedAddress.prefixLength)
                )
    }

    private class AcceptedCommissioningNetworkBinding(
        override val stableInterfaceId: String,
        override val runtimeNetworkId: String,
        override val bindIpv4: String,
        override val operatorPeerIpv4: String,
        override val prefixLength: Int,
        override val httpOrigin: String,
    ) : CommissioningNetworkBinding

    private companion object {
        val INTERFACE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$")
        val RUNTIME_NETWORK_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")

        fun parsePrivateIpv4(raw: String, field: String): IpLiteral =
            requireNotNull(IpLiteral.parse(raw)) { "$field must be a numeric IPv4 literal" }.also { parsed ->
                require(raw == parsed.canonical) { "$field must use canonical dotted-decimal syntax" }
                require(parsed.family == IpLiteral.Family.IPV4 && parsed.isPrivateIpv4 && !parsed.isProhibitedTarget) {
                    "$field must be a private, unicast IPv4 address"
                }
            }
    }
}
