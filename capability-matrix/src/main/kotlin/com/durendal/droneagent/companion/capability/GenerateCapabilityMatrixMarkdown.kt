package com.durendal.droneagent.companion.capability

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object GenerateCapabilityMatrixMarkdown {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) {
            "Usage: GenerateCapabilityMatrixMarkdown <matrix-json> <output-markdown>"
        }
        val source = Path.of(args[0])
        val output = Path.of(args[1])
        val document = CapabilityMatrixLoader().load(Files.readString(source, StandardCharsets.UTF_8))
        Files.createDirectories(output.parent)
        Files.writeString(
            output,
            CapabilityMatrixMarkdownRenderer.render(document),
            StandardCharsets.UTF_8,
        )
    }
}
