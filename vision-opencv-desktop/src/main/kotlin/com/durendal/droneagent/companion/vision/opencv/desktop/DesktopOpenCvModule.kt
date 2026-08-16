package com.durendal.droneagent.companion.vision.opencv.desktop

import com.durendal.droneagent.companion.vision.opencv.OpenCvNativeInitializer
import com.durendal.droneagent.companion.vision.opencv.OpenCvRuntimeIdentity
import com.durendal.droneagent.companion.vision.opencv.OpenCvTapeSegmenter
import com.durendal.droneagent.companion.vision.opencv.OpenCvTapeSegmenterConfig
import org.opencv.core.Core
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Mac/JVM composition root for the OpenCV tape segmenter. OpenCV loader and
 * native types remain private to this platform module.
 */
object DesktopOpenCvModule {
    fun initialize(): OpenCvRuntimeIdentity = DesktopOpenCvNativeInitializer.initialize()

    fun createTapeSegmenter(
        config: OpenCvTapeSegmenterConfig = OpenCvTapeSegmenterConfig(),
    ): OpenCvTapeSegmenter = OpenCvTapeSegmenter(
        initializer = DesktopOpenCvNativeInitializer,
        config = config,
    )
}

private object DesktopOpenCvNativeInitializer : OpenCvNativeInitializer {
    private val lock = Any()

    @Volatile
    private var identity: OpenCvRuntimeIdentity? = null

    override fun initialize(): OpenCvRuntimeIdentity = synchronized(lock) {
        identity ?: run {
            nu.pattern.OpenCV.loadLocally()
            val buildInformation = Core.getBuildInformation()
            OpenCvRuntimeIdentity(
                version = Core.VERSION,
                platform = "desktop-${System.getProperty("os.name")}-${System.getProperty("os.arch")}",
                buildInformationSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(buildInformation.toByteArray(StandardCharsets.UTF_8))
                    .joinToString(separator = "") { byte -> "%02x".format(byte) },
            ).also { loaded -> identity = loaded }
        }
    }
}
