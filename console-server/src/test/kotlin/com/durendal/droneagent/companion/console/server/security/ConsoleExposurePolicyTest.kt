package com.durendal.droneagent.companion.console.server.security

import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkDecision
import com.durendal.droneagent.companion.commissioning.network.CommissioningNetworkPolicy
import com.durendal.droneagent.companion.commissioning.network.ObservedInterfaceTransport
import com.durendal.droneagent.companion.commissioning.network.ObservedIpAddress
import com.durendal.droneagent.companion.commissioning.network.ObservedIpRoute
import com.durendal.droneagent.companion.commissioning.network.ObservedNetworkInterface
import com.durendal.droneagent.companion.commissioning.network.ObservedNetworkInventory
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleExposurePolicyTest {
    @Test
    fun `localhost development fixes listener and browser origin to loopback`() {
        val exposure =
            ConsoleExposurePolicy.localhostDevelopment(
                bindPort = 8080,
                browserPort = 18_080,
            )

        assertEquals(ConsoleExposureProfile.LOCALHOST_DEVELOPMENT, exposure.profile)
        assertEquals("127.0.0.1", exposure.bindHost)
        assertEquals(8080, exposure.bindPort)
        assertEquals("http://127.0.0.1:18080", exposure.allowedBrowserOrigin)
        assertNull(exposure.expectedRemotePeerIpv4)
        assertNull(exposure.runtimeNetworkId)
        assertTrue(ConsoleExposurePolicy.acceptsRemoteAddress(exposure, "127.0.0.1"))
        assertTrue(ConsoleExposurePolicy.acceptsRemoteAddress(exposure, "127.20.30.40"))
        assertFalse(ConsoleExposurePolicy.acceptsRemoteAddress(exposure, "192.168.1.10"))
        assertFalse(ConsoleExposurePolicy.acceptsRemoteAddress(exposure, "127.000.0.1"))
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleExposurePolicy.localhostDevelopment(bindPort = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsoleExposurePolicy.localhostDevelopment(bindPort = 8080, browserPort = 65_536)
        }
    }

    @Test
    fun `commissioning endpoint and peer come only from an accepted immutable binding`() {
        val binding = acceptedBinding()
        val candidate = ConsoleExposurePolicy.pointToPointCommissioningCandidate(binding)

        assertEquals(ConsoleExposureProfile.POINT_TO_POINT_COMMISSIONING, candidate.profile)
        assertEquals("10.52.0.2", candidate.bindHost)
        assertEquals(8080, candidate.bindPort)
        assertEquals("http://10.52.0.2:8080", candidate.allowedBrowserOrigin)
        assertEquals("10.52.0.1", candidate.expectedRemotePeerIpv4)
        assertEquals("network-42", candidate.runtimeNetworkId)
        assertThrows(UnsupportedOperationException::class.java) {
            ConsoleExposurePolicy.activatePointToPointCommissioning(candidate)
        }
    }

    @Test
    fun `exposure and candidate implementations are neither public nor copyable`() {
        assertTrue(ConsoleExposure::class.java.isSealed)
        assertTrue(CommissioningExposureCandidate::class.java.isSealed)
        assertThrows(IllegalArgumentException::class.java) {
            Proxy.newProxyInstance(
                ConsoleExposure::class.java.classLoader,
                arrayOf(ConsoleExposure::class.java),
            ) { _, _, _ -> null }
        }
        listOf(
            ConsoleExposurePolicy.localhostDevelopment(8080),
            ConsoleExposurePolicy.pointToPointCommissioningCandidate(acceptedBinding()),
        ).forEach { exposure ->
            assertFalse(Modifier.isPublic(exposure.javaClass.modifiers))
            assertFalse(exposure.javaClass.methods.any { it.name == "copy" })
        }
    }

    @Test
    fun `shared routable production remains fail closed`() {
        assertThrows(UnsupportedOperationException::class.java) {
            ConsoleExposurePolicy.sharedRoutableProduction()
        }
    }

    private fun acceptedBinding() =
        (
            CommissioningNetworkPolicy(
                expectedStableInterfaceId = "ethernet-port-0",
                operatorPeerIpv4 = "10.52.0.1",
                expectedG520Ipv4 = "10.52.0.2",
                expectedPrefixLength = 30,
                httpPort = 8080,
            ).select(
                ObservedNetworkInventory(
                    isComplete = true,
                    interfaces =
                        listOf(
                            ObservedNetworkInterface(
                                stableInterfaceId = "ethernet-port-0",
                                runtimeNetworkId = "network-42",
                                transport = ObservedInterfaceTransport.ETHERNET,
                                isUp = true,
                                addresses = listOf(ObservedIpAddress("10.52.0.2", 30)),
                                routes = listOf(ObservedIpRoute("10.52.0.0", 30)),
                            ),
                        ),
                ),
            ) as CommissioningNetworkDecision.Accepted
        ).binding
}
