package com.durendal.droneagent.companion.vision.opencv

import com.durendal.droneagent.vision.frame.LuminanceFrame
import com.durendal.droneagent.vision.segment.MaskRejectionReason
import com.durendal.droneagent.vision.segment.SegmentationDiagnostics
import com.durendal.droneagent.vision.segment.SegmentationMask
import com.durendal.droneagent.vision.segment.SegmentationResult
import com.durendal.droneagent.vision.segment.Segmenter
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Vendor-neutral configuration for dark-tape segmentation. */
data class OpenCvTapeSegmenterConfig(
    val blurKernelPixels: Int = 5,
    val morphologyKernelPixels: Int = 5,
    val maxPlausibleTapeFraction: Double = 0.35,
    val minClassSeparationLuminance: Double = 30.0,
) {
    init {
        require(blurKernelPixels > 0 && blurKernelPixels % 2 == 1) {
            "blurKernelPixels must be a positive odd number"
        }
        require(morphologyKernelPixels > 0 && morphologyKernelPixels % 2 == 1) {
            "morphologyKernelPixels must be a positive odd number"
        }
        require(maxPlausibleTapeFraction in 0.0..1.0) {
            "maxPlausibleTapeFraction must be within 0..1"
        }
        require(minClassSeparationLuminance >= 0.0) {
            "minClassSeparationLuminance must be >= 0"
        }
    }
}

/**
 * OpenCV implementation of the upstream vendor-neutral [Segmenter] contract.
 *
 * Native loading is deliberately supplied by the platform composition root.
 * The initializer is invoked lazily and at most once. Initialization or native
 * execution failure throws [OpenCvUnavailableException] and never returns a
 * plausible-looking fallback mask.
 */
class OpenCvTapeSegmenter(
    initializer: OpenCvNativeInitializer,
    private val config: OpenCvTapeSegmenterConfig = OpenCvTapeSegmenterConfig(),
) : Segmenter {
    private val initialization = NativeInitializationGate(initializer)

    /** The successfully loaded runtime identity, or `null` before first use. */
    val runtimeIdentity: OpenCvRuntimeIdentity?
        get() = initialization.identityOrNull()

    override fun segment(frame: LuminanceFrame): SegmentationResult {
        initialization.ensureInitialized()
        return try {
            segmentWithOpenCv(frame)
        } catch (error: RuntimeException) {
            throw initialization.nativeExecutionFailed(error)
        } catch (error: LinkageError) {
            throw initialization.nativeExecutionFailed(error)
        }
    }

    private fun segmentWithOpenCv(frame: LuminanceFrame): SegmentationResult {
        var source: Mat? = null
        var blurred: Mat? = null
        var binary: Mat? = null
        var opened: Mat? = null
        var kernel: Mat? = null
        var primaryFailure: Throwable? = null
        try {
            val allocatedSource = Mat(frame.height, frame.width, CvType.CV_8UC1)
                .also { source = it }
            val allocatedBlurred = Mat().also { blurred = it }
            val allocatedBinary = Mat().also { binary = it }
            val allocatedOpened = Mat().also { opened = it }
            val allocatedKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(
                    config.morphologyKernelPixels.toDouble(),
                    config.morphologyKernelPixels.toDouble(),
                ),
            ).also { kernel = it }

            allocatedSource.put(0, 0, frame.pixels)
            Imgproc.GaussianBlur(
                allocatedSource,
                allocatedBlurred,
                Size(config.blurKernelPixels.toDouble(), config.blurKernelPixels.toDouble()),
                0.0,
            )
            val otsuThreshold = Imgproc.threshold(
                allocatedBlurred,
                allocatedBinary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU,
            )
            val classStats = otsuClassStats(allocatedBlurred, otsuThreshold)
            val diagnostics = SegmentationDiagnostics(
                candidateFraction = classStats.darkFraction,
                effectiveThreshold = otsuThreshold,
            )
            if (classStats.separation < config.minClassSeparationLuminance) {
                return SegmentationResult.rejected(
                    width = frame.width,
                    height = frame.height,
                    reason = MaskRejectionReason.LOW_CLASS_SEPARATION,
                    diagnostics = diagnostics,
                )
            }

            Imgproc.morphologyEx(
                allocatedBinary,
                allocatedOpened,
                Imgproc.MORPH_OPEN,
                allocatedKernel,
            )
            val bytes = ByteArray(frame.width * frame.height)
            allocatedOpened.get(0, 0, bytes)
            val mask = SegmentationMask(
                width = frame.width,
                height = frame.height,
                tape = BooleanArray(bytes.size) { bytes[it].toInt() != 0 },
            )
            return if (mask.tapeFraction > config.maxPlausibleTapeFraction) {
                SegmentationResult.rejected(
                    width = frame.width,
                    height = frame.height,
                    reason = MaskRejectionReason.IMPLAUSIBLE_TAPE_FRACTION,
                    diagnostics = diagnostics,
                )
            } else {
                SegmentationResult.accepted(mask, diagnostics)
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            releaseAllocatedMats(
                primaryFailure = primaryFailure,
                kernel,
                opened,
                binary,
                blurred,
                source,
            )
        }
    }

    /** Releases every successfully allocated native handle, even if an earlier release fails. */
    private fun releaseAllocatedMats(
        primaryFailure: Throwable?,
        vararg mats: Mat?,
    ) {
        var releaseFailure: Throwable? = null
        for (mat in mats) {
            try {
                mat?.release()
            } catch (error: RuntimeException) {
                releaseFailure = rememberReleaseFailure(primaryFailure, releaseFailure, error)
            } catch (error: LinkageError) {
                releaseFailure = rememberReleaseFailure(primaryFailure, releaseFailure, error)
            }
        }
        if (primaryFailure == null && releaseFailure != null) throw releaseFailure
    }

    private fun rememberReleaseFailure(
        primaryFailure: Throwable?,
        priorReleaseFailure: Throwable?,
        currentFailure: Throwable,
    ): Throwable {
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(currentFailure)
            return priorReleaseFailure ?: currentFailure
        }
        if (priorReleaseFailure != null) {
            priorReleaseFailure.addSuppressed(currentFailure)
            return priorReleaseFailure
        }
        return currentFailure
    }

    private fun otsuClassStats(blurred: Mat, otsuThreshold: Double): OtsuClassStats {
        val bytes = ByteArray(blurred.total().toInt())
        blurred.get(0, 0, bytes)
        var darkSum = 0L
        var darkCount = 0L
        var lightSum = 0L
        var lightCount = 0L
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value <= otsuThreshold) {
                darkSum += value
                darkCount++
            } else {
                lightSum += value
                lightCount++
            }
        }
        val separation = if (darkCount == 0L || lightCount == 0L) {
            0.0
        } else {
            lightSum.toDouble() / lightCount - darkSum.toDouble() / darkCount
        }
        return OtsuClassStats(
            separation = separation,
            darkFraction = darkCount.toDouble() / bytes.size,
        )
    }

    private data class OtsuClassStats(
        val separation: Double,
        val darkFraction: Double,
    )

    private class NativeInitializationGate(
        private val initializer: OpenCvNativeInitializer,
    ) {
        private val lock = Any()

        @Volatile
        private var state: InitializationState = InitializationState.Pending

        fun ensureInitialized(): OpenCvRuntimeIdentity = synchronized(lock) {
            when (val current = state) {
                is InitializationState.Ready -> current.identity
                is InitializationState.Failed -> throw current.error
                InitializationState.Pending -> initializeLocked()
            }
        }

        fun identityOrNull(): OpenCvRuntimeIdentity? =
            (state as? InitializationState.Ready)?.identity

        fun nativeExecutionFailed(cause: Throwable): OpenCvUnavailableException = synchronized(lock) {
            val current = state
            if (current is InitializationState.Failed) return@synchronized current.error
            val failure = OpenCvUnavailableException(
                message = "OpenCV native execution failed",
                cause = cause,
            )
            state = InitializationState.Failed(failure)
            failure
        }

        private fun initializeLocked(): OpenCvRuntimeIdentity {
            return try {
                initializer.initialize().also { identity ->
                    state = InitializationState.Ready(identity)
                }
            } catch (error: RuntimeException) {
                throw rememberInitializationFailure(error)
            } catch (error: LinkageError) {
                throw rememberInitializationFailure(error)
            }
        }

        private fun rememberInitializationFailure(cause: Throwable): OpenCvUnavailableException {
            val failure = OpenCvUnavailableException(
                message = "OpenCV native initialization failed",
                cause = cause,
            )
            state = InitializationState.Failed(failure)
            return failure
        }
    }

    private sealed interface InitializationState {
        data object Pending : InitializationState
        data class Ready(val identity: OpenCvRuntimeIdentity) : InitializationState
        data class Failed(val error: OpenCvUnavailableException) : InitializationState
    }
}
