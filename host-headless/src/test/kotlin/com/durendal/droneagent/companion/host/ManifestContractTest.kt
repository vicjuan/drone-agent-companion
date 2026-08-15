package com.durendal.droneagent.companion.host

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ManifestContractTest {
    @Test
    fun `manifest is activity-free and declares target-34 foreground host contract`() {
        val manifest = findProjectFile("src/main/AndroidManifest.xml")
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        assertEquals(0, document.getElementsByTagName("activity").length)

        val permissions =
            elements(document.getElementsByTagName("uses-permission"))
                .map { it.getAttributeNS(androidNamespace, "name") }
                .toSet()
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in permissions)
        assertTrue("android.permission.CHANGE_NETWORK_STATE" in permissions)
        assertTrue("android.permission.INTERNET" in permissions)

        val receiver = document.getElementsByTagName("receiver").item(0) as? Element
        assertNotNull(receiver)
        assertEquals("false", receiver!!.getAttributeNS(androidNamespace, "exported"))
        assertEquals("true", receiver.getAttributeNS(androidNamespace, "directBootAware"))
        val actions =
            elements(receiver.getElementsByTagName("action"))
                .map { it.getAttributeNS(androidNamespace, "name") }
                .toSet()
        assertTrue("android.intent.action.LOCKED_BOOT_COMPLETED" in actions)
        assertTrue("android.intent.action.BOOT_COMPLETED" in actions)

        val application = document.getElementsByTagName("application").item(0) as? Element
        assertNotNull(application)
        assertEquals("false", application!!.getAttributeNS(androidNamespace, "usesCleartextTraffic"))
        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS(androidNamespace, "networkSecurityConfig"),
        )

        val service = document.getElementsByTagName("service").item(0) as? Element
        assertNotNull(service)
        assertEquals("false", service!!.getAttributeNS(androidNamespace, "exported"))
        assertEquals("true", service.getAttributeNS(androidNamespace, "directBootAware"))
        assertEquals("connectedDevice", service.getAttributeNS(androidNamespace, "foregroundServiceType"))
        assertEquals(":agent", service.getAttributeNS(androidNamespace, "process"))

        val networkSecurity = findProjectFile("src/main/res/xml/network_security_config.xml")
        val networkDocument =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(networkSecurity)
        val baseConfig = networkDocument.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", baseConfig.getAttribute("cleartextTrafficPermitted"))
        val domainConfig = networkDocument.getElementsByTagName("domain-config").item(0) as Element
        assertEquals("true", domainConfig.getAttribute("cleartextTrafficPermitted"))
        val domains = elements(domainConfig.getElementsByTagName("domain"))
        assertEquals(1, domains.size)
        assertEquals("127.0.0.1", domains.single().textContent.trim())
        assertEquals("false", domains.single().getAttribute("includeSubdomains"))

        assertFalse(HeadlessHostModule.FORCE_STOP_AUTO_RECOVERY_SUPPORTED)
        assertTrue(HeadlessHostModule.FORCE_STOP_LIMITATION.contains("cannot auto-recover"))
    }

    private fun findProjectFile(relativePath: String): File {
        var cursor = File(".").canonicalFile
        while (true) {
            val direct = cursor.resolve(relativePath)
            if (direct.isFile && cursor.name == "host-headless") return direct
            val nested = cursor.resolve("host-headless/$relativePath")
            if (nested.isFile) return nested
            cursor = cursor.parentFile ?: throw AssertionError("host-headless project not found")
        }
    }

    private fun elements(nodes: org.w3c.dom.NodeList): List<Element> =
        (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}
