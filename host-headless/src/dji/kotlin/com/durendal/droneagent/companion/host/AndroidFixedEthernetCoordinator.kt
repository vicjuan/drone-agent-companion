package com.durendal.droneagent.companion.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkBinding
import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkDecision
import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkPolicy
import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkRejection
import com.durendal.droneagent.companion.commissioning.network.ObservedInterfaceTransport
import com.durendal.droneagent.companion.commissioning.network.ObservedInventoryCollectionFailure
import com.durendal.droneagent.companion.commissioning.network.ObservedIpAddress
import com.durendal.droneagent.companion.commissioning.network.ObservedIpRoute
import com.durendal.droneagent.companion.commissioning.network.ObservedNetworkInterface
import com.durendal.droneagent.companion.commissioning.network.ObservedNetworkInventory
import java.net.NetworkInterface

internal data class AndroidFixedEthernetConfig(
    val expectedInterfaceName: String = EXPECTED_INTERFACE_NAME,
    val g520Ipv4: String = G520_IPV4,
    val windowsIpv4: String = WINDOWS_IPV4,
    val prefixLength: Int = PREFIX_LENGTH,
    val consolePort: Int = CONSOLE_PORT,
) {
    init {
        require(expectedInterfaceName == EXPECTED_INTERFACE_NAME) {
            "G520 production candidate requires interface $EXPECTED_INTERFACE_NAME"
        }
        require(g520Ipv4 == G520_IPV4) { "G520 production candidate requires $G520_IPV4" }
        require(windowsIpv4 == WINDOWS_IPV4) {
            "G520 production candidate requires Windows peer $WINDOWS_IPV4"
        }
        require(prefixLength == PREFIX_LENGTH) { "G520 production candidate requires /$PREFIX_LENGTH" }
        require(consolePort == CONSOLE_PORT) {
            "G520 production candidate requires console port $CONSOLE_PORT"
        }
    }

    companion object {
        const val EXPECTED_INTERFACE_NAME = "eth0"
        const val G520_IPV4 = "10.52.0.2"
        const val WINDOWS_IPV4 = "10.52.0.1"
        const val PREFIX_LENGTH = 30
        const val CONSOLE_PORT = 8080
    }
}

internal enum class AndroidFixedEthernetFailure {
    POLICY_REJECTED,
    RUNTIME_NETWORK_MISSING,
    PROCESS_BIND_FAILED,
    PROCESS_BIND_NOT_OBSERVED,
    CLOSED,
}

internal sealed interface AndroidFixedEthernetState {
    data class Waiting(
        val failure: AndroidFixedEthernetFailure,
        val policyRejection: CommissioningNetworkRejection? = null,
    ) : AndroidFixedEthernetState

    data class Bound(val binding: AndroidFixedEthernetBinding) : AndroidFixedEthernetState

    data object Closed : AndroidFixedEthernetState
}

/** The accepted pure-policy binding paired with the exact live Android Network generation. */
internal data class AndroidFixedEthernetBinding(
    val policyBinding: CommissioningNetworkBinding,
    val androidNetwork: Network,
    val inventoryGeneration: Long,
)

internal data class AndroidObservedNetworkInventory(
    val inventory: ObservedNetworkInventory,
    val networksByRuntimeId: Map<String, Network>,
)

/** Converts Android ConnectivityManager truth into the pure commissioning policy inventory. */
internal class AndroidNetworkInventoryCollector(
    private val connectivityManager: ConnectivityManager,
) {
    fun collect(): AndroidObservedNetworkInventory {
        val failures = linkedSetOf<ObservedInventoryCollectionFailure>()
        val networks =
            runCatching { connectivityManager.allNetworks.toList() }
                .getOrElse {
                    return failedInventory(ObservedInventoryCollectionFailure.NETWORK_IDENTITY_QUERY_FAILED)
                }
        val observedInterfaces = mutableListOf<ObservedNetworkInterface>()
        val dnsServers = linkedSetOf<String>()
        val networksByRuntimeId = linkedMapOf<String, Network>()

        networks.forEach { network ->
            val capabilities =
                runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
            if (capabilities == null) {
                failures += ObservedInventoryCollectionFailure.NETWORK_IDENTITY_QUERY_FAILED
                return@forEach
            }
            val linkProperties =
                runCatching { connectivityManager.getLinkProperties(network) }.getOrNull()
            if (linkProperties == null) {
                failures += ObservedInventoryCollectionFailure.ADDRESS_QUERY_FAILED
                failures += ObservedInventoryCollectionFailure.ROUTE_QUERY_FAILED
                failures += ObservedInventoryCollectionFailure.DNS_QUERY_FAILED
                return@forEach
            }
            val interfaceName = linkProperties.interfaceName
            if (interfaceName.isNullOrBlank()) {
                failures += ObservedInventoryCollectionFailure.INTERFACE_QUERY_FAILED
                return@forEach
            }
            val isUp =
                runCatching { NetworkInterface.getByName(interfaceName)?.isUp }
                    .getOrElse {
                        failures += ObservedInventoryCollectionFailure.INTERFACE_QUERY_FAILED
                        null
                    }
            if (isUp == null) {
                failures += ObservedInventoryCollectionFailure.INTERFACE_QUERY_FAILED
                return@forEach
            }

            val addresses = collectAddresses(linkProperties, failures)
            val routes = collectRoutes(linkProperties, failures)
            val observedDns = collectDns(linkProperties, failures)
            dnsServers += observedDns
            val runtimeNetworkId = runtimeNetworkId(network)
            observedInterfaces +=
                ObservedNetworkInterface(
                    stableInterfaceId = interfaceName,
                    runtimeNetworkId = runtimeNetworkId,
                    transport = capabilities.toObservedTransport(),
                    isUp = isUp,
                    addresses = addresses,
                    routes = routes,
                )
            networksByRuntimeId[runtimeNetworkId] = network
        }

        return AndroidObservedNetworkInventory(
            inventory =
                ObservedNetworkInventory(
                    isComplete = failures.isEmpty(),
                    interfaces = observedInterfaces,
                    dnsServers = dnsServers,
                    collectionFailures = failures,
                ),
            networksByRuntimeId = networksByRuntimeId.toMap(),
        )
    }

    private fun collectAddresses(
        linkProperties: LinkProperties,
        failures: MutableSet<ObservedInventoryCollectionFailure>,
    ): List<ObservedIpAddress> =
        runCatching {
            linkProperties.linkAddresses.map { address ->
                ObservedIpAddress(
                    numericAddress = address.address.numericHostAddress(),
                    prefixLength = address.prefixLength,
                )
            }
        }.getOrElse {
            failures += ObservedInventoryCollectionFailure.ADDRESS_QUERY_FAILED
            emptyList()
        }

    private fun collectRoutes(
        linkProperties: LinkProperties,
        failures: MutableSet<ObservedInventoryCollectionFailure>,
    ): List<ObservedIpRoute> =
        runCatching {
            linkProperties.routes.map { route ->
                ObservedIpRoute(
                    destinationAddress = route.destination.address.numericHostAddress(),
                    prefixLength = route.destination.prefixLength,
                    gatewayAddress = route.gateway?.numericHostAddress(),
                    isDefaultRoute = route.isDefaultRoute,
                )
            }
        }.getOrElse {
            failures += ObservedInventoryCollectionFailure.ROUTE_QUERY_FAILED
            emptyList()
        }

    private fun collectDns(
        linkProperties: LinkProperties,
        failures: MutableSet<ObservedInventoryCollectionFailure>,
    ): List<String> =
        runCatching { linkProperties.dnsServers.map { it.numericHostAddress() } }
            .getOrElse {
                failures += ObservedInventoryCollectionFailure.DNS_QUERY_FAILED
                emptyList()
            }

    private fun failedInventory(
        failure: ObservedInventoryCollectionFailure,
    ): AndroidObservedNetworkInventory =
        AndroidObservedNetworkInventory(
            ObservedNetworkInventory(
                isComplete = false,
                interfaces = emptyList(),
                collectionFailures = listOf(failure),
            ),
            emptyMap(),
        )

    private fun NetworkCapabilities.toObservedTransport(): ObservedInterfaceTransport =
        when {
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ObservedInterfaceTransport.ETHERNET
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ObservedInterfaceTransport.WIFI
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ObservedInterfaceTransport.CELLULAR
            else -> ObservedInterfaceTransport.OTHER
        }

    private fun java.net.InetAddress.numericHostAddress(): String =
        checkNotNull(hostAddress).substringBefore('%')

    companion object {
        fun runtimeNetworkId(network: Network): String = "android-network-${network.networkHandle}"
    }
}

/**
 * Continuously validates and process-pins the one isolated G520 Ethernet Network.
 *
 * Process binding is intentionally paired with the accepted runtime Network identity. It is still
 * not a claim of SO_BINDTODEVICE or firewall isolation; the hardware lane must retain listener-table
 * and negative-reachability evidence.
 */
internal class AndroidFixedEthernetCoordinator(
    context: Context,
    private val config: AndroidFixedEthernetConfig = AndroidFixedEthernetConfig(),
    private val onStateChanged: (AndroidFixedEthernetState) -> Unit,
) : AutoCloseable {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val collector = AndroidNetworkInventoryCollector(connectivityManager)
    private val policy =
        CommissioningNetworkPolicy(
            expectedStableInterfaceId = config.expectedInterfaceName,
            operatorPeerIpv4 = config.windowsIpv4,
            expectedG520Ipv4 = config.g520Ipv4,
            expectedPrefixLength = config.prefixLength,
            httpPort = config.consolePort,
        )
    private val refreshLock = Any()
    private var started = false
    private var closed = false
    private var inventoryGeneration = 0L
    private var boundNetwork: Network? = null
    private var state: AndroidFixedEthernetState =
        AndroidFixedEthernetState.Waiting(AndroidFixedEthernetFailure.POLICY_REJECTED)

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refresh()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) = refresh()
        }

    fun start(): AndroidFixedEthernetState {
        synchronized(refreshLock) {
            check(!started) { "fixed Ethernet coordinator may be started only once" }
            check(!closed) { "fixed Ethernet coordinator is closed" }
            started = true
        }
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().clearCapabilities().build(),
                networkCallback,
            )
        } catch (failure: Throwable) {
            synchronized(refreshLock) { started = false }
            throw failure
        }
        refresh()
        return currentState()
    }

    fun currentState(): AndroidFixedEthernetState = synchronized(refreshLock) { state }

    override fun close() {
        val shouldUnregister =
            synchronized(refreshLock) {
                if (closed) return
                closed = true
                val registered = started
                started = false
                registered
            }
        if (shouldUnregister) runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        synchronized(refreshLock) {
            unbindIfOwned()
            publishLocked(AndroidFixedEthernetState.Closed)
        }
    }

    private fun refresh() {
        synchronized(refreshLock) {
            if (!started || closed) return
            inventoryGeneration += 1L
            val observed = collector.collect()
            when (val decision = policy.select(observed.inventory)) {
                is CommissioningNetworkDecision.Rejected -> {
                    unbindIfOwned()
                    publishLocked(
                        AndroidFixedEthernetState.Waiting(
                            AndroidFixedEthernetFailure.POLICY_REJECTED,
                            decision.reason,
                        ),
                    )
                }
                is CommissioningNetworkDecision.Accepted -> bindAccepted(decision.binding, observed)
            }
        }
    }

    private fun bindAccepted(
        binding: CommissioningNetworkBinding,
        observed: AndroidObservedNetworkInventory,
    ) {
        val network = observed.networksByRuntimeId[binding.runtimeNetworkId]
        if (network == null) {
            unbindIfOwned()
            publishLocked(
                AndroidFixedEthernetState.Waiting(
                    AndroidFixedEthernetFailure.RUNTIME_NETWORK_MISSING,
                ),
            )
            return
        }
        val alreadyBound =
            connectivityManager.boundNetworkForProcess?.networkHandle == network.networkHandle
        if (!alreadyBound && !runCatching { connectivityManager.bindProcessToNetwork(network) }.getOrDefault(false)) {
            unbindIfOwned()
            publishLocked(
                AndroidFixedEthernetState.Waiting(AndroidFixedEthernetFailure.PROCESS_BIND_FAILED),
            )
            return
        }
        if (connectivityManager.boundNetworkForProcess?.networkHandle != network.networkHandle) {
            boundNetwork = network
            unbindIfOwned()
            publishLocked(
                AndroidFixedEthernetState.Waiting(
                    AndroidFixedEthernetFailure.PROCESS_BIND_NOT_OBSERVED,
                ),
            )
            return
        }
        boundNetwork = network
        publishLocked(
            AndroidFixedEthernetState.Bound(
                AndroidFixedEthernetBinding(binding, network, inventoryGeneration),
            ),
        )
    }

    private fun unbindIfOwned() {
        val owned = boundNetwork ?: return
        if (connectivityManager.boundNetworkForProcess?.networkHandle == owned.networkHandle) {
            runCatching { connectivityManager.bindProcessToNetwork(null) }
        }
        boundNetwork = null
    }

    private fun publishLocked(next: AndroidFixedEthernetState) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }
}
