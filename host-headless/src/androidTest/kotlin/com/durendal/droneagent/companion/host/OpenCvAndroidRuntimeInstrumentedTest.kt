package com.durendal.droneagent.companion.host

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.durendal.droneagent.companion.vision.opencv.OpenCvTapeSegmenter
import com.durendal.droneagent.companion.vision.opencv.android.AndroidOpenCvRuntime
import com.durendal.droneagent.companion.vision.opencv.fixture.OpenCvNativeSelfTestFixture
import com.durendal.droneagent.observation.DecodedFrameFormat
import com.durendal.droneagent.observation.DecodedFrameListener
import com.durendal.droneagent.observation.DecodedFrameObservation
import com.durendal.droneagent.observation.DecodedFrameStartResult
import com.durendal.droneagent.observation.DecodedFrameStream
import com.durendal.droneagent.observation.DecodedFrameStreamFactory
import com.durendal.droneagent.vision.pipeline.VisionFrameState
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
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

    @Test
    fun productionObservationSessionProcessesRecycledCanonicalNv21Frame() {
        assertEquals("the frozen emulator lane is API 34", 34, Build.VERSION.SDK_INT)
        assertEquals("the packaged target ABI is arm64", "arm64-v8a", Build.SUPPORTED_ABIS.first())

        val scene = OpenCvNativeSelfTestFixture.scene()
        val source = FakeDecodedFrameStream(scene.frame.width, scene.frame.height)
        val factoryOpenCount = AtomicInteger(0)
        val segmenterEntered = CountDownLatch(1)
        val releaseSegmenter = CountDownLatch(1)
        val session =
            HeadlessOpenCvObservationSession(
                streamFactory =
                    DecodedFrameStreamFactory {
                        factoryOpenCount.incrementAndGet()
                        source
                    },
                nativeInitializer = AndroidOpenCvRuntime,
                segmenterFactory = { loadedInitializer ->
                    val realSegmenter = OpenCvTapeSegmenter(loadedInitializer)
                    com.durendal.droneagent.vision.segment.Segmenter { frame ->
                        segmenterEntered.countDown()
                        assertTrue(
                            "OpenCV worker did not wait for decoder-buffer recycling",
                            releaseSegmenter.await(
                                OBSERVATION_RESULT_TIMEOUT_MILLIS,
                                TimeUnit.MILLISECONDS,
                            ),
                        )
                        realSegmenter.segment(frame)
                    }
                },
            )
        val decoderOwnedPayload =
            scene.frame.pixels.copyOf() +
                ByteArray(nv21ChromaLength(scene.frame.width, scene.frame.height)) { 128.toByte() }
        var liveSnapshot: OpenCvObservationSnapshot? = null
        var stopResult = OpenCvObservationStopResult.FAILED

        try {
            assertEquals(OpenCvObservationStartResult.Started, session.start())
            source.emitThenRecycle(decoderOwnedPayload)
            assertTrue(
                "the CV worker must reach the recycle barrier",
                segmenterEntered.await(
                    OBSERVATION_RESULT_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                ),
            )
            assertTrue(
                "the fake decoder must recycle its payload immediately after callback return",
                decoderOwnedPayload.all { it == 0.toByte() },
            )
            releaseSegmenter.countDown()
            liveSnapshot = awaitObservationSnapshot(session) { it.firstFrame != null }
        } finally {
            releaseSegmenter.countDown()
            stopResult = session.stopWithin(OBSERVATION_STOP_TIMEOUT_MILLIS)
        }

        assertEquals(OpenCvObservationStopResult.CLOSED, stopResult)
        val live = requireNotNull(liveSnapshot)
        val frame = requireNotNull(live.firstFrame)
        val identity = requireNotNull(live.runtimeIdentity)
        val intersectionOverUnion = scene.intersectionOverUnion(frame.copyTapeMask())

        assertEquals("4.9.0", identity.version)
        assertTrue(
            "native build identity must be a lowercase SHA-256",
            Regex("[0-9a-f]{64}").matches(identity.buildInformationSha256),
        )
        assertTrue(identity.platform.startsWith("android-api34-arm64-v8a"))
        assertTrue("production decoded-frame segmentation must be accepted", frame.segmentationAccepted)
        assertEquals(scene.frame.width, frame.widthPixels)
        assertEquals(scene.frame.height, frame.heightPixels)
        assertEquals(VisionFrameState.IMAGE_SPACE_READY, frame.analysisState)
        assertTrue(
            "decoded-frame mask IoU $intersectionOverUnion is below " +
                scene.minimumIntersectionOverUnion,
            intersectionOverUnion >= scene.minimumIntersectionOverUnion,
        )

        val frozen = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.CLOSED, frozen.lifecycleState)
        assertTrue("measurement must be immutable only after ordered close", frozen.finalMeasurement)
        assertEquals(1L, frozen.offeredFrameCount)
        assertEquals(1L, frozen.polledFrameCount)
        assertEquals(1L, frozen.processedFrameCount)
        assertEquals(0L, frozen.droppedFrameCount)
        assertEquals(0L, frozen.shutdownDiscardedFrameCount)
        assertEquals(0L, frozen.frameConversionFailureCount)
        assertEquals(0L, frozen.pipelineFailureCount)
        assertEquals(0L, frozen.cleanupAttemptFailureCount)
        assertEquals(0L, frozen.bridgeStopTimeoutCount)
        assertEquals(1L, requireNotNull(frozen.decoderReceiveToCvPoll).sampleCount)
        assertEquals(1L, requireNotNull(frozen.cvProcessing).sampleCount)
        assertEquals(1, factoryOpenCount.get())
        assertEquals(listOf(DecodedFrameFormat.NV21), source.startedFormats.toList())
        assertTrue("production session must unregister the decoder listener", source.listeners.isEmpty())
        assertEquals("production session must close its source exactly once", 1, source.closeCount.get())

        val evidence =
            "opencv_decoded_frame version=${identity.version} " +
                "build_sha256=${identity.buildInformationSha256} " +
                "platform=${identity.platform} " +
                "format=${DecodedFrameFormat.NV21} " +
                "analysis_state=${frame.analysisState} " +
                "accepted=${frame.segmentationAccepted} " +
                "mask_iou=${String.format(Locale.US, "%.6f", intersectionOverUnion)} " +
                "offered=${frozen.offeredFrameCount} polled=${frozen.polledFrameCount} " +
                "processed=${frozen.processedFrameCount} dropped=${frozen.droppedFrameCount} " +
                "conversion_failures=${frozen.frameConversionFailureCount} " +
                "pipeline_failures=${frozen.pipelineFailureCount} " +
                "final=${frozen.finalMeasurement} source_close_count=${source.closeCount.get()}"
        assertTrue("decoded-frame evidence must stay bounded", evidence.length <= MAX_EVIDENCE_CHARS)
        Log.i(LOG_TAG, evidence)
        println(evidence)
    }

    private fun awaitObservationSnapshot(
        session: HeadlessOpenCvObservationSession,
        predicate: (OpenCvObservationSnapshot) -> Boolean,
    ): OpenCvObservationSnapshot {
        val deadline = SystemClock.elapsedRealtime() + OBSERVATION_RESULT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = session.snapshot()
            if (predicate(snapshot)) return snapshot
            Thread.sleep(OBSERVATION_POLL_INTERVAL_MILLIS)
        }
        val last = session.snapshot()
        assertTrue("observation result was not produced; last snapshot=$last", predicate(last))
        return last
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

    private class FakeDecodedFrameStream(
        private val widthPixels: Int,
        private val heightPixels: Int,
    ) : DecodedFrameStream {
        val listeners = CopyOnWriteArrayList<DecodedFrameListener>()
        val startedFormats = CopyOnWriteArrayList<DecodedFrameFormat>()
        val closeCount = AtomicInteger(0)

        override fun start(format: DecodedFrameFormat): DecodedFrameStartResult {
            startedFormats += format
            return DecodedFrameStartResult.Started
        }

        override fun stop() = Unit

        override fun addListener(listener: DecodedFrameListener) {
            listeners += listener
        }

        override fun removeListener(listener: DecodedFrameListener) {
            listeners -= listener
        }

        override fun close() {
            closeCount.incrementAndGet()
        }

        fun emitThenRecycle(payload: ByteArray) {
            val observation =
                DecodedFrameObservation(
                    sequence = 1L,
                    receivedAtEpochMs = System.currentTimeMillis(),
                    receivedAtMonotonicNanos = System.nanoTime(),
                    widthPixels = widthPixels,
                    heightPixels = heightPixels,
                    format = DecodedFrameFormat.NV21,
                    payloadSizeBytes = payload.size,
                    callbackThreadName = Thread.currentThread().name,
                )
            try {
                listeners.forEach { listener ->
                    listener.onDecodedFrame(observation, payload, 0, payload.size)
                }
            } finally {
                payload.fill(0)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "DroneCompanionOpenCV"
        const val OBSERVATION_RESULT_TIMEOUT_MILLIS = 5_000L
        const val OBSERVATION_STOP_TIMEOUT_MILLIS = 5_000L
        const val OBSERVATION_POLL_INTERVAL_MILLIS = 10L
        const val MAX_EVIDENCE_CHARS = 1_024
        val NATIVE_LIBRARY_EXTENSIONS = setOf(".so", ".dylib", ".dll", ".jnilib")

        fun nv21ChromaLength(widthPixels: Int, heightPixels: Int): Int =
            ((widthPixels + 1) / 2) * ((heightPixels + 1) / 2) * 2
    }
}
