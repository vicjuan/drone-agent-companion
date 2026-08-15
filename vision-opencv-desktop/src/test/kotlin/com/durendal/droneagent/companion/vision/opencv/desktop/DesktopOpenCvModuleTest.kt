package com.durendal.droneagent.companion.vision.opencv.desktop

import com.durendal.droneagent.companion.vision.opencv.fixture.OpenCvNativeSelfTestFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class DesktopOpenCvModuleTest {
    @Test
    fun `desktop initializer reports actual JNI runtime identity`() {
        val first = DesktopOpenCvModule.initialize()
        val second = DesktopOpenCvModule.initialize()

        assertSame(first, second)
        assertTrue(first.version.startsWith("4.9.0"))
        assertTrue(first.platform.startsWith("desktop-"))
        assertTrue(first.buildInformationSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.buildInformationSha256.any { it != '0' })
        println(
            "opencv.desktop.identity version=${first.version} " +
                "platform=${first.platform} buildInformationSha256=${first.buildInformationSha256}",
        )
    }

    @Test
    fun `desktop native runtime segments canonical dark tape fixture`() {
        val segmenter = DesktopOpenCvModule.createTapeSegmenter()
        val scene = OpenCvNativeSelfTestFixture.scene()

        val warmup = segmenter.segment(scene.frame)
        val startedAtNanos = System.nanoTime()
        val result = segmenter.segment(scene.frame)
        val latencyNanos = System.nanoTime() - startedAtNanos
        val intersectionOverUnion = scene.intersectionOverUnion(result.mask.tape)

        assertTrue("warmup must be accepted", warmup.accepted)
        assertTrue("canonical dark tape must be accepted", result.accepted)
        assertTrue(
            "canonical mask IoU was $intersectionOverUnion",
            intersectionOverUnion >= scene.minimumIntersectionOverUnion,
        )
        assertEquals(DesktopOpenCvModule.initialize(), segmenter.runtimeIdentity)
        assertTrue("warm segment latency must be positive", latencyNanos > 0L)
        assertTrue("warm segment latency must be below five seconds", latencyNanos < 5_000_000_000L)
        println(
            "opencv.desktop.self_test accepted=${result.accepted} " +
                "iou=$intersectionOverUnion minimum=${scene.minimumIntersectionOverUnion} " +
                "warmLatencyNanos=$latencyNanos",
        )
    }

    @Test
    fun `desktop public API does not expose loader or OpenCV types`() {
        val exposedTypes = DesktopOpenCvModule::class.java.declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)
            }
            .flatMap { method -> listOf(method.returnType) + method.parameterTypes }

        assertTrue(
            "desktop API leaked: ${exposedTypes.map { it.name }}",
            exposedTypes.none {
                it.name.startsWith("org.opencv.") ||
                    it.name.startsWith("nu.pattern.") ||
                    it.name.startsWith("android.") ||
                    it.name.startsWith("androidx.")
            },
        )
    }
}
