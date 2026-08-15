package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.capability.CapabilityMatrixLoader
import com.durendal.droneagent.companion.console.protocol.ConsoleProtocolCodec
import com.durendal.droneagent.companion.console.protocol.ConsoleServerMessage
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class G520ProtocolCapabilitySourceTest {
    @Test
    fun `wire snapshot is derived from the same canonical bytes and exact digest`() {
        val classLoader = G520ProtocolCapabilitySource::class.java.classLoader
        val bytes =
            checkNotNull(classLoader.getResourceAsStream(CapabilityMatrixLoader.BUNDLED_RESOURCE))
                .use { it.readBytes() }
        val document = CapabilityMatrixLoader().load(bytes.toString(Charsets.UTF_8))
        val payload = G520ProtocolCapabilitySource.loadBundled(classLoader).snapshot()

        assertEquals(document.matrixId, payload.matrixId)
        assertEquals(document.schemaVersion, payload.schemaVersion)
        assertEquals(document.lastUpdated, payload.lastUpdated)
        assertEquals(document.rows.map { it.id }, payload.rows.map { it.id })
        assertEquals(document.rows.map { it.status.name }, payload.rows.map { it.status.name })
        assertEquals(document.rows.map { it.assessment }, payload.rows.map { it.assessment })
        assertEquals(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) },
            payload.sourceDigestSha256,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (payload.rows as MutableList<Any?>).clear()
        }

        // The protocol validator must accept the production snapshot, not only a hand fixture.
        ConsoleProtocolCodec().encodeServer(ConsoleServerMessage("capability-source-test", payload))
    }

    @Test
    fun `protocol projection does not initialize the vendor core capability provider`() {
        val implementationBytes =
            listOf(
                G520ProtocolCapabilitySource::class.java,
                G520ProtocolCapabilitySource.Companion::class.java,
            ).flatMap { implementationClass ->
                val resource = implementationClass.name.replace('.', '/') + ".class"
                checkNotNull(implementationClass.classLoader.getResourceAsStream(resource)) {
                    "Missing compiled implementation class $resource"
                }.use { it.readBytes().asIterable() }
            }.toByteArray()
        val classConstants = implementationBytes.toString(Charsets.ISO_8859_1)

        assertFalse(
            "protocol-only projection must not initialize G520CapabilityMatrixProvider/toCoreMatrix",
            classConstants.contains("G520CapabilityMatrixProvider"),
        )
    }
}
