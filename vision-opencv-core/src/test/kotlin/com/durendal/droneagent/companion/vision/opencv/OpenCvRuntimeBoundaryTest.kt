package com.durendal.droneagent.companion.vision.opencv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenCvRuntimeBoundaryTest {
    @Test
    fun `boundary uses only vendor-neutral frame segmenter and result types`() {
        assertEquals(
            setOf(
                "com.durendal.droneagent.vision.frame.LuminanceFrame",
                "com.durendal.droneagent.vision.segment.Segmenter",
                "com.durendal.droneagent.vision.segment.SegmentationResult",
            ),
            OpenCvVisionContracts.typeNames,
        )
    }

    @Test
    fun `runtime identity rejects missing evidence fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenCvRuntimeIdentity(version = "", platform = "android-arm64")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenCvRuntimeIdentity(version = "4.9.0", platform = "")
        }
    }
}
