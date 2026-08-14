package com.durendal.droneagent.companion.capability

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class CapabilityMatrixLoader private constructor(private val json: Json) {
    constructor() : this(STRICT_JSON)

    fun load(text: String): G520CapabilityMatrixDocument =
        json.decodeFromString<G520CapabilityMatrixDocument>(text).also(CapabilityMatrixValidator::validate)

    fun load(input: InputStream): G520CapabilityMatrixDocument =
        input.bufferedReader(Charsets.UTF_8).use { reader -> load(reader.readText()) }

    fun loadTemplates(text: String): CapabilityEvidenceTemplateSet =
        json.decodeFromString<CapabilityEvidenceTemplateSet>(text).also(
            CapabilityMatrixValidator::validateTemplates,
        )

    companion object {
        const val BUNDLED_RESOURCE = "capability-matrix/g520-stack.json"

        @OptIn(ExperimentalSerializationApi::class)
        val STRICT_JSON: Json =
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = false
                isLenient = false
                coerceInputValues = false
            }

        fun loadBundled(
            classLoader: ClassLoader = CapabilityMatrixLoader::class.java.classLoader,
        ): G520CapabilityMatrixDocument {
            val stream = requireNotNull(classLoader.getResourceAsStream(BUNDLED_RESOURCE)) {
                "Missing bundled capability matrix resource: $BUNDLED_RESOURCE"
            }
            return CapabilityMatrixLoader().load(stream)
        }
    }
}
