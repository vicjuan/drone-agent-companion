package com.durendal.droneagent.companion.commissioning.network

import java.util.ArrayList
import java.util.Collections

/** Platform-neutral transport identity reported by the inventory collector. */
enum class ObservedInterfaceTransport {
    ETHERNET,
    WIFI,
    CELLULAR,
    LOOPBACK,
    OTHER,
}

/** Stable, bounded reasons why a platform collector could not prove a complete inventory. */
enum class ObservedInventoryCollectionFailure {
    INTERFACE_QUERY_FAILED,
    ADDRESS_QUERY_FAILED,
    ROUTE_QUERY_FAILED,
    DNS_QUERY_FAILED,
    NETWORK_IDENTITY_QUERY_FAILED,
}

/** A numeric IP address and the prefix observed on one interface. */
data class ObservedIpAddress(
    val numericAddress: String,
    val prefixLength: Int,
)

/**
 * One observed route. A direct on-link route has a null [gatewayAddress].
 *
 * [isDefaultRoute] is retained from the platform observation, while the policy also independently
 * recognizes numeric `0.0.0.0/0` and `::/0` destinations so a false flag cannot open the gate.
 */
data class ObservedIpRoute(
    val destinationAddress: String,
    val prefixLength: Int,
    val gatewayAddress: String? = null,
    val isDefaultRoute: Boolean = false,
)

/** Immutable snapshot of a single platform interface. */
class ObservedNetworkInterface(
    /** Persistent identity such as a commissioned Linux interface name or hardware-backed ID. */
    val stableInterfaceId: String,
    /** Opaque identity valid only for this inventory generation, such as an Android Network handle. */
    val runtimeNetworkId: String,
    val transport: ObservedInterfaceTransport,
    val isUp: Boolean,
    addresses: Collection<ObservedIpAddress>,
    routes: Collection<ObservedIpRoute> = emptyList(),
) {
    val addresses: List<ObservedIpAddress> = immutableCopy(addresses)
    val routes: List<ObservedIpRoute> = immutableCopy(routes)
}

/**
 * Immutable, point-in-time network inventory. DNS is inventory-wide because Android and JVM
 * collectors do not necessarily expose a reliable per-interface DNS association.
 */
class ObservedNetworkInventory(
    /** Must only be true after every required platform query completed successfully. */
    val isComplete: Boolean,
    interfaces: Collection<ObservedNetworkInterface>,
    dnsServers: Collection<String> = emptyList(),
    collectionFailures: Collection<ObservedInventoryCollectionFailure> = emptyList(),
) {
    val interfaces: List<ObservedNetworkInterface> = immutableCopy(interfaces)
    val dnsServers: List<String> = immutableCopy(dnsServers)
    val collectionFailures: List<ObservedInventoryCollectionFailure> = immutableCopy(collectionFailures)
}

private fun <T> immutableCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
