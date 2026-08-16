package com.durendal.droneagent.companion.vision.opencv

import com.durendal.droneagent.companion.vision.opencv.fixture.OpenCvNativeSelfTestFixture
import com.durendal.droneagent.companion.vision.opencv.fixture.OpenCvNativeSelfTestScene
import com.durendal.droneagent.vision.frame.LuminanceFrame
import com.durendal.droneagent.vision.segment.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
    fun `runtime identity rejects missing or noncanonical evidence fields`() {
        val digest = "0".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            OpenCvRuntimeIdentity("", "android-arm64", digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenCvRuntimeIdentity("4.9.0", "", digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenCvRuntimeIdentity("4.9.0", "android-arm64", "ABC")
        }
    }

    @Test
    fun `initializer failure is sticky and cannot return a fallback mask`() {
        var initializationAttempts = 0
        val segmenter = OpenCvTapeSegmenter(
            initializer = OpenCvNativeInitializer {
                initializationAttempts++
                throw UnsatisfiedLinkError("native deliberately absent")
            },
        )
        val frame = OpenCvNativeSelfTestFixture.scene().frame

        val first = assertThrows(OpenCvUnavailableException::class.java) {
            segmenter.segment(frame)
        }
        val second = assertThrows(OpenCvUnavailableException::class.java) {
            segmenter.segment(frame)
        }

        assertEquals(1, initializationAttempts)
        assertSame(first, second)
        assertTrue(first.cause is UnsatisfiedLinkError)
        assertNull(segmenter.runtimeIdentity)
    }

    @Test
    fun `injected native initializer runs once and canonical tape is segmented`() {
        var initializationAttempts = 0
        val concreteSegmenter = OpenCvTapeSegmenter(
            initializer = OpenCvNativeInitializer {
                initializationAttempts++
                nu.pattern.OpenCV.loadLocally()
                OpenCvRuntimeIdentity(
                    version = Core.VERSION,
                    platform = "core-test",
                    buildInformationSha256 = sha256(Core.getBuildInformation()),
                )
            },
        )
        val segmenter: Segmenter = concreteSegmenter
        val scene = OpenCvNativeSelfTestFixture.scene()

        val first = segmenter.segment(scene.frame)
        val second = segmenter.segment(scene.frame)

        assertTrue(first.accepted)
        assertTrue(
            scene.intersectionOverUnion(first.mask.tape) >= scene.minimumIntersectionOverUnion,
        )
        assertTrue(second.accepted)
        assertEquals(1, initializationAttempts)
        assertEquals("core-test", concreteSegmenter.runtimeIdentity?.platform)
    }

    @Test
    fun `uniform floor is explicitly rejected instead of hallucinating tape`() {
        val segmenter = OpenCvTapeSegmenter(initializer = testNativeInitializer())
        val frame = LuminanceFrame(
            width = 32,
            height = 24,
            pixels = ByteArray(32 * 24) { 180.toByte() },
        )

        val result = segmenter.segment(frame)

        assertFalse(result.accepted)
        assertEquals(0, result.mask.tapePixelCount)
    }

    @Test
    fun `native self-test fixture is fresh and scores exact expected mask`() {
        val first = OpenCvNativeSelfTestFixture.scene()
        val second = OpenCvNativeSelfTestFixture.scene()

        first.frame.pixels[0] = 0
        first.expectedTape[0] = true

        assertFalse(second.expectedTape[0])
        assertEquals(200, second.frame.luminanceAt(0, 0))
        assertEquals(1.0, second.intersectionOverUnion(second.expectedTape), 0.0)
    }

    @Test
    fun `public core API does not expose OpenCV types`() {
        val apiClasses = listOf(
            OpenCvNativeInitializer::class.java,
            OpenCvRuntimeIdentity::class.java,
            OpenCvTapeSegmenter::class.java,
            OpenCvTapeSegmenterConfig::class.java,
            OpenCvUnavailableException::class.java,
            OpenCvVisionContracts::class.java,
            OpenCvNativeSelfTestFixture::class.java,
            OpenCvNativeSelfTestScene::class.java,
        )
        fun isApiVisible(modifiers: Int): Boolean =
            Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)

        val exposedTypes = apiClasses.flatMap { apiClass ->
            val methodTypes = apiClass.declaredMethods
                .filter { isApiVisible(it.modifiers) }
                .flatMap { method -> listOf(method.returnType) + method.parameterTypes }
            val constructorTypes = apiClass.declaredConstructors
                .filter { isApiVisible(it.modifiers) }
                .flatMap { constructor -> constructor.parameterTypes.toList() }
            val fieldTypes = apiClass.declaredFields
                .filter { isApiVisible(it.modifiers) }
                .map { field -> field.type }
            methodTypes + constructorTypes + fieldTypes
        }

        assertTrue(
            "public API leaked: ${exposedTypes.map { it.name }}",
            exposedTypes.none { type ->
                type.name.startsWith("org.opencv.") ||
                    type.name.startsWith("android.") ||
                    type.name.startsWith("androidx.")
            },
        )
    }

    private fun testNativeInitializer(): OpenCvNativeInitializer = OpenCvNativeInitializer {
        nu.pattern.OpenCV.loadLocally()
        OpenCvRuntimeIdentity(
            version = Core.VERSION,
            platform = "core-test",
            buildInformationSha256 = sha256(Core.getBuildInformation()),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
