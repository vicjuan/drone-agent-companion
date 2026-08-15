package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.vision.opencv.OpenCvTapeSegmenter
import com.durendal.droneagent.companion.vision.opencv.android.AndroidOpenCvRuntime
import com.durendal.droneagent.observation.DecodedFrameStreamFactory
import com.durendal.droneagent.vision.segment.Segmenter

/** Android host composition for the on-device OpenCV segmentation boundary. */
internal object HeadlessOpenCvVision {
    fun createTapeSegmenter(): Segmenter = OpenCvTapeSegmenter(AndroidOpenCvRuntime)

    /** Creates the observation-only production graph; the caller owns its one-shot lifecycle. */
    fun createObservationSession(
        streamFactory: DecodedFrameStreamFactory,
    ): HeadlessOpenCvObservationSession =
        HeadlessOpenCvObservationSession(
            streamFactory = streamFactory,
            nativeInitializer = AndroidOpenCvRuntime,
            segmenterFactory = ::OpenCvTapeSegmenter,
        )
}
