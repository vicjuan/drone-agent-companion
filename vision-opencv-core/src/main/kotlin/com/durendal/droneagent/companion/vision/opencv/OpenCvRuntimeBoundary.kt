package com.durendal.droneagent.companion.vision.opencv

import com.durendal.droneagent.vision.frame.LuminanceFrame
import com.durendal.droneagent.vision.segment.SegmentationResult
import com.durendal.droneagent.vision.segment.Segmenter

/** Runtime identity emitted by a platform-specific OpenCV initializer. */
data class OpenCvRuntimeIdentity(
    val version: String,
    val platform: String,
) {
    init {
        require(version.isNotBlank()) { "version must not be blank" }
        require(platform.isNotBlank()) { "platform must not be blank" }
    }
}

/**
 * Native initialization seam implemented separately by desktop and Android.
 * No OpenCV or Android type may cross this pure-JVM API.
 */
fun interface OpenCvNativeInitializer {
    fun initialize(): OpenCvRuntimeIdentity
}

/** Compile-time pin for the vendor-neutral frame-to-result vision seam. */
object OpenCvVisionContracts {
    val typeNames: Set<String> = setOf(
        requireNotNull(LuminanceFrame::class.qualifiedName),
        requireNotNull(Segmenter::class.qualifiedName),
        requireNotNull(SegmentationResult::class.qualifiedName),
    )
}
