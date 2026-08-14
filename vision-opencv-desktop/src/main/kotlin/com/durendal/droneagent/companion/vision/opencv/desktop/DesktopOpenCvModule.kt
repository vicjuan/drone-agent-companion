package com.durendal.droneagent.companion.vision.opencv.desktop

/**
 * Desktop runtime module marker. Native dependency and loader selection belong
 * to issue #14; this S0 module must not pretend OpenCV has already been loaded.
 */
object DesktopOpenCvModule
