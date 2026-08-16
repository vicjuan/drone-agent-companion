package com.durendal.droneagent.companion.vision.opencv.android

import android.os.Build
import com.durendal.droneagent.companion.vision.opencv.OpenCvNativeInitializer
import com.durendal.droneagent.companion.vision.opencv.OpenCvRuntimeIdentity
import java.security.MessageDigest
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core

/**
 * Official OpenCV Android runtime boundary.
 *
 * OpenCV and Android types remain private to this module. Callers receive only
 * the vendor-neutral [OpenCvRuntimeIdentity] required by the shared segmenter.
 */
object AndroidOpenCvRuntime : OpenCvNativeInitializer {
    @Volatile
    private var loadedIdentity: OpenCvRuntimeIdentity? = null

    override fun initialize(): OpenCvRuntimeIdentity {
        loadedIdentity?.let { return it }
        return synchronized(this) {
            loadedIdentity ?: loadRuntime().also { loadedIdentity = it }
        }
    }

    private fun loadRuntime(): OpenCvRuntimeIdentity {
        check(OpenCVLoader.initLocal()) { "OpenCV Android native runtime initialization failed" }

        // This is a JNI call, not merely a Java constant. A non-empty result proves
        // the just-loaded native runtime can execute before an identity is emitted.
        val buildInformation = Core.getBuildInformation()
        check(buildInformation.isNotBlank()) { "OpenCV Android native build identity is empty" }

        val abis = Build.SUPPORTED_ABIS.toList()
        check(abis.isNotEmpty()) { "Android runtime reported no supported ABI" }
        return OpenCvRuntimeIdentity(
            version = Core.VERSION,
            platform = "android-api${Build.VERSION.SDK_INT}-${abis.joinToString("+")}",
            buildInformationSha256 = buildInformation.sha256(),
        )
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
