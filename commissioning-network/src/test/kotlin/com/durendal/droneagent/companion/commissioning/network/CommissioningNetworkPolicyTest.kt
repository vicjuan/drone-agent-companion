package com.durendal.droneagent.companion.commissioning.network

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CommissioningNetworkPolicyTest {
    private val policy =
        CommissioningNetworkPolicy(
            expectedStableInterfaceId = "ethernet-port-0",
            operatorPeerIpv4 = "10.52.0.1",
            expectedG520Ipv4 = "10.52.0.2",
            expectedPrefixLength = 30,
            httpPort = 8080,
        )

    @Test
    fun `complete unique Ethernet with exact address identity and route is accepted`() {
        val accepted = policy.select(inventory(ethernet())) as CommissioningNetworkDecision.Accepted
        with(accepted.binding) {
            assertEquals("ethernet-port-0", stableInterfaceId)
            assertEquals("network-42", runtimeNetworkId)
            assertEquals("10.52.0.2", bindIpv4)
            assertEquals("10.52.0.1", operatorPeerIpv4)
            assertEquals(30, prefixLength)
            assertEquals("http://10.52.0.2:8080", httpOrigin)
        }
    }

    @Test
    fun `accepted binding has no externally constructible or copyable implementation surface`() {
        val binding = (policy.select(inventory(ethernet())) as CommissioningNetworkDecision.Accepted).binding

        assertFalse(Modifier.isPublic(binding.javaClass.modifiers))
        assertEquals(emptyList<String>(), binding.javaClass.methods.map { it.name }.filter { it == "copy" })
    }

    @Test
    fun `incomplete inventory or any collector failure cannot open the gate`() {
        assertRejected(
            CommissioningNetworkRejection.INCOMPLETE_INVENTORY,
            inventory(ethernet(), isComplete = false),
        )
        assertRejected(
            CommissioningNetworkRejection.INCOMPLETE_INVENTORY,
            inventory(
                ethernet(),
                failures = listOf(ObservedInventoryCollectionFailure.ROUTE_QUERY_FAILED),
            ),
        )
    }

    @Test
    fun `missing and down target fail closed with distinct reasons`() {
        assertRejected(
            CommissioningNetworkRejection.MISSING_EXPECTED_ADDRESS,
            inventory(ethernet(addresses = emptyList())),
        )
        assertRejected(
            CommissioningNetworkRejection.TARGET_INTERFACE_DOWN,
            inventory(ethernet(isUp = false)),
        )
    }

    @Test
    fun `WiFi holding the expected address cannot masquerade as Ethernet`() {
        assertRejected(
            CommissioningNetworkRejection.TARGET_NOT_ETHERNET,
            inventory(ethernet(transport = ObservedInterfaceTransport.WIFI)),
        )
    }

    @Test
    fun `wrong prefix or stable interface identity is rejected`() {
        assertRejected(
            CommissioningNetworkRejection.WRONG_PREFIX,
            inventory(ethernet(addresses = listOf(g520Address(prefix = 29)))),
        )
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_TARGET_INTERFACE_ID,
            inventory(ethernet(stableInterfaceId = "usb-ethernet-uncommissioned")),
        )
    }

    @Test
    fun `same target address on multiple interfaces is rejected`() {
        assertRejected(
            CommissioningNetworkRejection.MULTIPLE_EXPECTED_ADDRESS,
            inventory(
                ethernet(),
                ethernet(stableInterfaceId = "ethernet-port-1", runtimeNetworkId = "network-43"),
            ),
        )
    }

    @Test
    fun `duplicate stable or runtime identities make the inventory malformed`() {
        assertRejected(
            CommissioningNetworkRejection.DUPLICATE_STABLE_INTERFACE_ID,
            inventory(
                ethernet(),
                ethernet(
                    stableInterfaceId = "ethernet-port-0",
                    runtimeNetworkId = "network-43",
                    isUp = false,
                    addresses = emptyList(),
                    routes = emptyList(),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.DUPLICATE_RUNTIME_NETWORK_ID,
            inventory(
                ethernet(),
                ethernet(
                    stableInterfaceId = "ethernet-port-1",
                    runtimeNetworkId = "network-42",
                    isUp = false,
                    addresses = emptyList(),
                    routes = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `default route is rejected even when collector flag is false`() {
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_DEFAULT_ROUTE,
            inventory(
                ethernet(
                    routes =
                        listOf(
                            expectedConnectedRoute(),
                            ObservedIpRoute("0.0.0.0", 0, isDefaultRoute = false),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `gateway and DNS observations are independently rejected`() {
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_GATEWAY,
            inventory(
                ethernet(
                    routes =
                        listOf(
                            expectedConnectedRoute(),
                            ObservedIpRoute("10.0.0.0", 8, gatewayAddress = "10.52.0.1"),
                        ),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_DNS,
            inventory(ethernet(), dnsServers = listOf("10.52.0.1")),
        )
    }

    @Test
    fun `exact connected route is mandatory and unique`() {
        assertRejected(
            CommissioningNetworkRejection.MISSING_EXPECTED_CONNECTED_ROUTE,
            inventory(ethernet(routes = emptyList())),
        )
        assertRejected(
            CommissioningNetworkRejection.MULTIPLE_EXPECTED_CONNECTED_ROUTE,
            inventory(ethernet(routes = listOf(expectedConnectedRoute(), expectedConnectedRoute()))),
        )
    }

    @Test
    fun `split default routes and broad on-link routes cannot bypass default detection`() {
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_ROUTE,
            inventory(
                ethernet(
                    routes =
                        listOf(
                            expectedConnectedRoute(),
                            ObservedIpRoute("0.0.0.0", 1),
                            ObservedIpRoute("128.0.0.0", 1),
                        ),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_ROUTE,
            inventory(
                ethernet(
                    routes =
                        listOf(
                            expectedConnectedRoute(),
                            ObservedIpRoute("10.0.0.0", 8),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `a later link-loss snapshot cannot reuse an earlier binding`() {
        assertEquals(CommissioningNetworkDecision.Accepted::class, policy.select(inventory(ethernet()))::class)
        assertRejected(
            CommissioningNetworkRejection.TARGET_INTERFACE_DOWN,
            inventory(ethernet(isUp = false)),
        )
    }

    @Test
    fun `another active interface with routable IPv4 or IPv6 is rejected`() {
        assertRejected(
            CommissioningNetworkRejection.OTHER_ACTIVE_ROUTABLE_INTERFACE,
            inventory(
                ethernet(),
                alternate(
                    transport = ObservedInterfaceTransport.WIFI,
                    addresses = listOf(ObservedIpAddress("192.168.1.50", 24)),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.OTHER_ACTIVE_ROUTABLE_INTERFACE,
            inventory(
                ethernet(),
                alternate(
                    transport = ObservedInterfaceTransport.CELLULAR,
                    addresses = listOf(ObservedIpAddress("2001:db8::50", 64)),
                ),
            ),
        )
    }

    @Test
    fun `loopback and link-local-only alternate paths do not invent a routed surface`() {
        val result =
            policy.select(
                inventory(
                    ethernet(),
                    ObservedNetworkInterface(
                        stableInterfaceId = "loopback-0",
                        runtimeNetworkId = "loopback-network",
                        transport = ObservedInterfaceTransport.LOOPBACK,
                        isUp = true,
                        addresses =
                            listOf(
                                ObservedIpAddress("127.0.0.1", 8),
                                ObservedIpAddress("::1", 128),
                            ),
                        routes =
                            listOf(
                                ObservedIpRoute("127.0.0.0", 8),
                                ObservedIpRoute("::1", 128),
                            ),
                    ),
                    alternate(
                        transport = ObservedInterfaceTransport.WIFI,
                        addresses =
                            listOf(
                                ObservedIpAddress("169.254.10.20", 16),
                                ObservedIpAddress("fe80::20", 64),
                            ),
                        routes = listOf(ObservedIpRoute("fe80::", 64)),
                    ),
                ),
            )

        assertEquals(CommissioningNetworkDecision.Accepted::class, result::class)
    }

    @Test
    fun `loopback label cannot hide a routable address or default route`() {
        assertRejected(
            CommissioningNetworkRejection.OTHER_ACTIVE_ROUTABLE_INTERFACE,
            inventory(
                ethernet(),
                ObservedNetworkInterface(
                    stableInterfaceId = "loopback-0",
                    runtimeNetworkId = "loopback-network",
                    transport = ObservedInterfaceTransport.LOOPBACK,
                    isUp = true,
                    addresses = listOf(ObservedIpAddress("192.168.50.10", 24)),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_DEFAULT_ROUTE,
            inventory(
                ethernet(),
                ObservedNetworkInterface(
                    stableInterfaceId = "loopback-0",
                    runtimeNetworkId = "loopback-network",
                    transport = ObservedInterfaceTransport.LOOPBACK,
                    isUp = true,
                    addresses = listOf(ObservedIpAddress("127.0.0.1", 8)),
                    routes = listOf(ObservedIpRoute("0.0.0.0", 0, isDefaultRoute = true)),
                ),
            ),
        )
    }

    @Test
    fun `broad prefixes cannot disguise routable addresses as local-only`() {
        assertRejected(
            CommissioningNetworkRejection.OTHER_ACTIVE_ROUTABLE_INTERFACE,
            inventory(
                ethernet(),
                alternate(
                    transport = ObservedInterfaceTransport.WIFI,
                    addresses = listOf(ObservedIpAddress("169.254.10.20", 0)),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.OTHER_ACTIVE_ROUTABLE_INTERFACE,
            inventory(
                ethernet(),
                ObservedNetworkInterface(
                    stableInterfaceId = "loopback-0",
                    runtimeNetworkId = "loopback-network",
                    transport = ObservedInterfaceTransport.LOOPBACK,
                    isUp = true,
                    addresses = listOf(ObservedIpAddress("127.0.0.1", 0)),
                ),
            ),
        )
        assertRejected(
            CommissioningNetworkRejection.TARGET_HAS_UNEXPECTED_ROUTABLE_ADDRESS,
            inventory(
                ethernet(
                    addresses =
                        listOf(
                            g520Address(),
                            ObservedIpAddress("fe80::20", 0),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `broad or noncanonical local route prefixes are rejected`() {
        listOf(
            ObservedIpRoute("169.254.0.0", 0),
            ObservedIpRoute("fe80::", 0),
            ObservedIpRoute("169.254.10.1", 24),
        ).forEach { spoofedRoute ->
            assertRejected(
                CommissioningNetworkRejection.UNEXPECTED_ROUTE,
                inventory(ethernet(routes = listOf(expectedConnectedRoute(), spoofedRoute))),
            )
        }
        assertRejected(
            CommissioningNetworkRejection.UNEXPECTED_ROUTE,
            inventory(
                ethernet(),
                ObservedNetworkInterface(
                    stableInterfaceId = "loopback-0",
                    runtimeNetworkId = "loopback-network",
                    transport = ObservedInterfaceTransport.LOOPBACK,
                    isUp = true,
                    addresses = listOf(ObservedIpAddress("127.0.0.1", 8)),
                    routes = listOf(ObservedIpRoute("127.0.0.0", 1)),
                ),
            ),
        )
    }

    @Test
    fun `down alternate interface cannot keep a stale routed path active`() {
        val result =
            policy.select(
                inventory(
                    ethernet(),
                    alternate(
                        transport = ObservedInterfaceTransport.WIFI,
                        isUp = false,
                        addresses = listOf(ObservedIpAddress("192.168.1.50", 24)),
                        routes =
                            listOf(
                                ObservedIpRoute(
                                    destinationAddress = "0.0.0.0",
                                    prefixLength = 0,
                                    gatewayAddress = "192.168.1.1",
                                    isDefaultRoute = true,
                                ),
                            ),
                    ),
                ),
            )

        assertEquals(CommissioningNetworkDecision.Accepted::class, result::class)
    }

    @Test
    fun `additional routable address on target Ethernet is rejected`() {
        assertRejected(
            CommissioningNetworkRejection.TARGET_HAS_UNEXPECTED_ROUTABLE_ADDRESS,
            inventory(
                ethernet(
                    addresses =
                        listOf(
                            g520Address(),
                            ObservedIpAddress("192.168.2.2", 24),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `snapshot collections are defensive immutable copies`() {
        val mutableAddresses = mutableListOf(g520Address())
        val mutableInterfaces = mutableListOf(ethernet(addresses = mutableAddresses))
        val mutableDns = mutableListOf<String>()
        val mutableFailures = mutableListOf<ObservedInventoryCollectionFailure>()
        val snapshot =
            ObservedNetworkInventory(
                isComplete = true,
                interfaces = mutableInterfaces,
                dnsServers = mutableDns,
                collectionFailures = mutableFailures,
            )

        mutableAddresses.clear()
        mutableInterfaces.clear()
        mutableDns += "8.8.8.8"
        mutableFailures += ObservedInventoryCollectionFailure.DNS_QUERY_FAILED

        assertEquals(1, snapshot.interfaces.size)
        assertEquals(1, snapshot.interfaces.single().addresses.size)
        assertEquals(emptyList<String>(), snapshot.dnsServers)
        assertEquals(emptyList<ObservedInventoryCollectionFailure>(), snapshot.collectionFailures)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.interfaces as MutableList<ObservedNetworkInterface>).clear()
        }
    }

    @Test
    fun `profile v1 rejects non-private unsafe or noncanonical topology values`() {
        listOf("g520.local", "127.0.0.1", "169.254.1.2", "8.8.8.8", "224.0.0.1", "010.052.0.2").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                CommissioningNetworkPolicy("ethernet-port-0", "10.52.0.1", address, 30, 8080)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommissioningNetworkPolicy("ethernet-port-0", "10.52.0.1", "10.52.0.2", 24, 8080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommissioningNetworkPolicy("ethernet-port-0", "10.52.0.1", "10.52.0.2", 30, 8081)
        }
    }

    @Test
    fun `operator and G520 must be distinct usable hosts in one private subnet`() {
        listOf(
            Triple("10.52.0.1", "10.52.0.1", "same host"),
            Triple("10.52.0.0", "10.52.0.2", "network address"),
            Triple("10.52.0.1", "10.52.0.3", "broadcast address"),
            Triple("10.52.0.1", "10.52.0.6", "different subnet"),
        ).forEach { (operator, g520, _) ->
            assertThrows(IllegalArgumentException::class.java) {
                CommissioningNetworkPolicy("ethernet-port-0", operator, g520, 30, 8080)
            }
        }
    }

    @Test
    fun `blank or malformed inventory identity and address fail closed`() {
        assertRejected(
            CommissioningNetworkRejection.MALFORMED_INVENTORY,
            inventory(ethernet(stableInterfaceId = "")),
        )
        assertRejected(
            CommissioningNetworkRejection.MALFORMED_INVENTORY,
            inventory(ethernet(runtimeNetworkId = "")),
        )
        assertRejected(
            CommissioningNetworkRejection.MALFORMED_INVENTORY,
            inventory(ethernet(runtimeNetworkId = "network-42\nforged")),
        )
        assertRejected(
            CommissioningNetworkRejection.MALFORMED_INVENTORY,
            inventory(ethernet(addresses = listOf(ObservedIpAddress("not-an-address", 30)))),
        )
        assertRejected(
            CommissioningNetworkRejection.MALFORMED_INVENTORY,
            inventory(ethernet(addresses = listOf(ObservedIpAddress("010.052.0.2", 30)))),
        )
        assertRejected(
            CommissioningNetworkRejection.MALFORMED_INVENTORY,
            inventory(ethernet(routes = listOf(ObservedIpRoute("010.052.0.0", 30)))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CommissioningNetworkPolicy(" ethernet-port-0", "10.52.0.1", "10.52.0.2", 30, 8080)
        }
    }

    private fun assertRejected(
        reason: CommissioningNetworkRejection,
        inventory: ObservedNetworkInventory,
    ) {
        assertEquals(CommissioningNetworkDecision.Rejected(reason), policy.select(inventory))
    }

    private fun inventory(
        vararg interfaces: ObservedNetworkInterface,
        isComplete: Boolean = true,
        dnsServers: Collection<String> = emptyList(),
        failures: Collection<ObservedInventoryCollectionFailure> = emptyList(),
    ): ObservedNetworkInventory =
        ObservedNetworkInventory(
            isComplete = isComplete,
            interfaces = interfaces.toList(),
            dnsServers = dnsServers,
            collectionFailures = failures,
        )

    private fun g520Address(prefix: Int = 30): ObservedIpAddress =
        ObservedIpAddress("10.52.0.2", prefix)

    private fun expectedConnectedRoute(): ObservedIpRoute =
        ObservedIpRoute("10.52.0.0", 30)

    private fun ethernet(
        stableInterfaceId: String = "ethernet-port-0",
        runtimeNetworkId: String = "network-42",
        transport: ObservedInterfaceTransport = ObservedInterfaceTransport.ETHERNET,
        isUp: Boolean = true,
        addresses: Collection<ObservedIpAddress> = listOf(g520Address()),
        routes: Collection<ObservedIpRoute> = listOf(expectedConnectedRoute()),
    ): ObservedNetworkInterface =
        ObservedNetworkInterface(
            stableInterfaceId = stableInterfaceId,
            runtimeNetworkId = runtimeNetworkId,
            transport = transport,
            isUp = isUp,
            addresses = addresses,
            routes = routes,
        )

    private fun alternate(
        transport: ObservedInterfaceTransport,
        isUp: Boolean = true,
        addresses: Collection<ObservedIpAddress>,
        routes: Collection<ObservedIpRoute> = emptyList(),
    ): ObservedNetworkInterface =
        ObservedNetworkInterface(
            stableInterfaceId = "alternate-interface",
            runtimeNetworkId = "network-99",
            transport = transport,
            isUp = isUp,
            addresses = addresses,
            routes = routes,
        )
}
