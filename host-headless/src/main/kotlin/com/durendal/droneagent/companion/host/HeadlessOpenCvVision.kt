package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.vision.opencv.OpenCvTapeSegmenter
import com.durendal.droneagent.companion.vision.opencv.android.AndroidOpenCvRuntime
import com.durendal.droneagent.vision.segment.Segmenter

/** Android host composition for the on-device OpenCV segmentation boundary. */
internal object HeadlessOpenCvVision {
    fun createTapeSegmenter(): Segmenter = OpenCvTapeSegmenter(AndroidOpenCvRuntime)
}
