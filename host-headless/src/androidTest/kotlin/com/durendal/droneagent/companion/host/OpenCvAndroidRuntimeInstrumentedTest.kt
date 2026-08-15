package com.durendal.droneagent.companion.host

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.durendal.droneagent.companion.vision.opencv.android.AndroidOpenCvRuntime
import com.durendal.droneagent.companion.vision.opencv.fixture.OpenCvNativeSelfTestFixture
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the shared native self-test through the production Android composition. */
@RunWith(AndroidJUnit4::class)
class OpenCvAndroidRuntimeInstrumentedTest {
    @Test
    fun officialArm64RuntimeSegmentsCanonicalDarkTapeFixture() {
        assertEquals("the frozen emulator lane is API 34", 34, Build.VERSION.SDK_INT)
        assertEquals("the packaged target ABI is arm64", "arm64-v8a", Build.SUPPORTED_ABIS.first())
        assertPublicApiDoesNotLeakPlatformTypes()
        assertApkRuntimeBoundary()

        val scene = OpenCvNativeSelfTestFixture.scene()
        val segmenter = HeadlessOpenCvVision.createTapeSegmenter()
        val initializationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val identity = AndroidOpenCvRuntime.initialize()
        val initializationLatencyNanos =
            SystemClock.elapsedRealtimeNanos() - initializationStartedAtNanos
        val segmentationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val result = segmenter.segment(scene.frame)
        val segmentationLatencyNanos =
            SystemClock.elapsedRealtimeNanos() - segmentationStartedAtNanos
        val intersectionOverUnion = scene.intersectionOverUnion(result.mask.tape)

        assertEquals("4.9.0", identity.version)
        assertTrue(
            "native build identity must be a lowercase SHA-256",
            Regex("[0-9a-f]{64}").matches(identity.buildInformationSha256),
        )
        assertTrue(identity.platform.startsWith("android-api34-arm64-v8a"))
        assertTrue("native initialization must have measurable latency", initializationLatencyNanos > 0L)
        assertTrue("warm native segmentation must have measurable latency", segmentationLatencyNanos > 0L)
        assertTrue("canonical dark-tape segmentation must be accepted", result.accepted)
        assertEquals(scene.frame.width, result.mask.width)
        assertEquals(scene.frame.height, result.mask.height)
        assertTrue(
            "mask IoU $intersectionOverUnion is below ${scene.minimumIntersectionOverUnion}",
            intersectionOverUnion >= scene.minimumIntersectionOverUnion,
        )

        val evidence =
                "opencv_runtime version=${identity.version} " +
                "build_sha256=${identity.buildInformationSha256} " +
                "platform=${identity.platform} " +
                "initialization_latency_nanos=$initializationLatencyNanos " +
                "warm_segmentation_latency_nanos=$segmentationLatencyNanos " +
                "accepted=${result.accepted} " +
                "mask_iou=${String.format(Locale.US, "%.6f", intersectionOverUnion)} " +
                "tape_pixels=${result.mask.tapePixelCount}"
        Log.i(LOG_TAG, evidence)
        println(evidence)
    }

    private fun assertPublicApiDoesNotLeakPlatformTypes() {
        val runtimeClass = AndroidOpenCvRuntime::class.java
        val apiTypes =
            runtimeClass.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
                .flatMap { method ->
                    listOf(method.returnType) + method.parameterTypes + method.exceptionTypes
                } +
                runtimeClass.declaredFields
                    .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
                    .map { it.type }
        val leakedTypes = apiTypes.map(Class<*>::getName).filter(::isPlatformType).toSet()
        assertTrue("public OpenCV facade leaks platform types: $leakedTypes", leakedTypes.isEmpty())
    }

    private fun isPlatformType(className: String): Boolean =
        className.startsWith("org.opencv.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.")

    private fun assertApkRuntimeBoundary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entries = ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.entries().asSequence().map { it.name }.toSet()
        }
        val openCvNativeEntries = entries.filter { entry ->
            val normalized = entry.lowercase(Locale.ROOT)
            normalized.contains("opencv") &&
                NATIVE_LIBRARY_EXTENSIONS.any(normalized::endsWith)
        }.toSet()
        assertEquals(
            "APK must package exactly the target ABI OpenCV JNI runtime",
            setOf("lib/arm64-v8a/libopencv_java4.so"),
            openCvNativeEntries,
        )
        assertFalse(
            "APK must not package desktop native resources",
            entries.any { entry ->
                entry.endsWith(".dylib") || entry.endsWith(".dll") || entry.endsWith(".jnilib") ||
                    entry.startsWith("natives/") || entry.contains("/natives/")
            },
        )
        assertClassAbsent("nu.pattern.OpenCV")
        assertClassAbsent("org.openpnp.OpenCV")
    }

    private fun assertClassAbsent(className: String) {
        try {
            Class.forName(className, false, javaClass.classLoader)
            fail("desktop OpenCV loader class must be absent: $className")
        } catch (_: ClassNotFoundException) {
            // Required boundary: only the official OpenCV Android loader may be packaged.
        }
    }

    private companion object {
        const val LOG_TAG = "DroneCompanionOpenCV"
        val NATIVE_LIBRARY_EXTENSIONS = setOf(".so", ".dylib", ".dll", ".jnilib")
    }
}
