package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.vision.opencv.OpenCvRuntimeIdentity
import com.durendal.droneagent.vision.pipeline.LiveVisionResult
import com.durendal.droneagent.vision.pipeline.VisionFrameState

/** One-shot lifecycle of the observation-only decoded-frame pipeline. */
internal enum class OpenCvObservationLifecycleState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    CLOSED,
    FAILED,
}

/** Bounded failure categories; exception text never becomes evidence. */
internal enum class OpenCvObservationFailureReason {
    CANCELLED,
    NATIVE_INITIALIZATION,
    SOURCE_OPEN,
    PIPELINE_COMPOSITION,
    SOURCE_START,
    CLEANUP,
}

internal sealed interface OpenCvObservationStartResult {
    data object Started : OpenCvObservationStartResult

    data class Failure(val reason: OpenCvObservationFailureReason) : OpenCvObservationStartResult
}

internal enum class OpenCvObservationStopResult {
    CLOSED,
    TIMED_OUT,
    FAILED,
}

/** Immutable latency summary detached from the vendor performance meter. */
internal data class OpenCvLatencyEvidence(
    val sampleCount: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val maxNanos: Long,
)

/**
 * Immutable, vendor-neutral evidence for the first analyzed frame.
 *
 * The copied mask is never returned directly. Tests and future evidence writers obtain a fresh
 * copy through [copyTapeMask], so a consumer cannot mutate the session's retained truth.
 */
internal class OpenCvFrameEvidence private constructor(
    val sequence: Long,
    val decoderReceivedAtMonotonicNanos: Long,
    val decoderReceiveToCvPollNanos: Long,
    val cvProcessingNanos: Long,
    val analysisState: VisionFrameState,
    val segmentationAccepted: Boolean,
    val widthPixels: Int,
    val heightPixels: Int,
    val tapePixelCount: Int,
    val centerlineConfidence: Double,
    private val tapeMask: BooleanArray,
) {
    fun copyTapeMask(): BooleanArray = tapeMask.copyOf()

    companion object {
        fun from(result: LiveVisionResult): OpenCvFrameEvidence {
            val segmentation = result.analysis.segmentation
            return OpenCvFrameEvidence(
                sequence = result.sequence,
                decoderReceivedAtMonotonicNanos = result.capturedAtNanos,
                decoderReceiveToCvPollNanos = result.frameAgeNanos,
                cvProcessingNanos = result.cvProcessingNanos,
                analysisState = result.analysis.state,
                segmentationAccepted = segmentation.accepted,
                widthPixels = segmentation.mask.width,
                heightPixels = segmentation.mask.height,
                tapePixelCount = segmentation.mask.tapePixelCount,
                centerlineConfidence = result.confidence,
                tapeMask = segmentation.mask.tape.copyOf(),
            )
        }
    }
}

/**
 * Pollable evidence surface. [finalMeasurement] is true only after the source, callback fence and
 * CV worker all stopped and the owned source closed successfully.
 */
internal data class OpenCvObservationSnapshot(
    val lifecycleState: OpenCvObservationLifecycleState,
    val failureReason: OpenCvObservationFailureReason?,
    val runtimeIdentity: OpenCvRuntimeIdentity?,
    /** First analyzed frame only; retaining every full mask would create unbounded churn. */
    val firstFrame: OpenCvFrameEvidence?,
    val processedFrameCount: Long,
    val offeredFrameCount: Long,
    val droppedFrameCount: Long,
    val polledFrameCount: Long,
    val shutdownDiscardedFrameCount: Long,
    val frameConversionFailureCount: Long,
    val pipelineFailureCount: Long,
    val cleanupAttemptFailureCount: Long,
    /** Number of bridge stop attempts that could not yet freeze the CV worker. */
    val bridgeStopTimeoutCount: Long,
    /** Decoder-callback receive time to CV gate poll; not sensor/camera E2E latency. */
    val decoderReceiveToCvPoll: OpenCvLatencyEvidence?,
    val cvProcessing: OpenCvLatencyEvidence?,
    val finalMeasurement: Boolean,
)
