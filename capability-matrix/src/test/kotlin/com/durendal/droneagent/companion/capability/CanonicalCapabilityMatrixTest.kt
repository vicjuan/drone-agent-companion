package com.durendal.droneagent.companion.capability

import com.durendal.droneagent.core.capability.Capability
import com.durendal.droneagent.core.capability.CapabilityStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalCapabilityMatrixTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("companion.repoRoot")))
    private val canonicalPath = repoRoot.resolve("config/capability-matrix/g520-stack.json")
    private val markdownPath = repoRoot.resolve("docs/capability-matrix.md")
    private val loader = CapabilityMatrixLoader()

    @Test
    fun `canonical target-stack inventory is complete and valid`() {
        val document = loadCanonical()

        assertEquals(RequiredG520Capabilities.rowIds, document.rows.map { it.id }.toSet())
    }

    @Test
    fun `canonical source projects the five upstream evidence rows exactly`() {
        val document = loadCanonical()
        val rowsById = document.rows.associateBy { it.id }
        val core = document.toCoreMatrix()

        assertEquals(Capability.entries.size, core.entries().size)
        Capability.entries.forEach { capability ->
            assertEquals(
                CapabilityStatus.valueOf(requireNotNull(rowsById[capability.key]).status.name),
                core.statusOf(capability),
            )
        }
    }

    @Test
    fun `companion status vocabulary stays within issue 12 scope`() {
        assertEquals(
            setOf("CONFIRMED", "LIMITED", "UNKNOWN"),
            G520CapabilityStatus.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `bundled production resource is the canonical source`() {
        assertEquals(loadCanonical(), CapabilityMatrixLoader.loadBundled())
    }

    @Test
    fun `generated markdown is byte-for-byte current`() {
        val expected = CapabilityMatrixMarkdownRenderer.render(loadCanonical())
        val committed = Files.readString(markdownPath, StandardCharsets.UTF_8).replace("\r\n", "\n")

        assertEquals(
            "Run ./gradlew :capability-matrix:generateCapabilityMatrixMarkdown after editing the JSON source",
            expected,
            committed,
        )
    }

    private fun loadCanonical(): G520CapabilityMatrixDocument =
        loader.load(Files.readString(canonicalPath, StandardCharsets.UTF_8))
}
