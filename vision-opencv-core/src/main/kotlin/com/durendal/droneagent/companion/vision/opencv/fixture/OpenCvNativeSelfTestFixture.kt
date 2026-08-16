package com.durendal.droneagent.companion.vision.opencv.fixture

import com.durendal.droneagent.vision.frame.LuminanceFrame

/** One isolated scene instance shared by desktop and Android runtime checks. */
data class OpenCvNativeSelfTestScene(
    val frame: LuminanceFrame,
    val expectedTape: BooleanArray,
    val minimumIntersectionOverUnion: Double,
) {
    fun intersectionOverUnion(actualTape: BooleanArray): Double {
        require(actualTape.size == expectedTape.size) {
            "actual mask size ${actualTape.size} does not match fixture size ${expectedTape.size}"
        }
        var intersection = 0
        var union = 0
        for (index in actualTape.indices) {
            if (actualTape[index] && expectedTape[index]) intersection++
            if (actualTape[index] || expectedTape[index]) union++
        }
        return if (union == 0) 1.0 else intersection.toDouble() / union
    }
}

/**
 * Synthetic first recognition target: a six-pixel dark floor-tape stripe on a
 * light floor. This production-visible fixture is intentionally tiny: desktop,
 * emulator, and G520 call the same input as a native runtime health check. A
 * fresh scene is returned each time so mutable arrays cannot contaminate
 * another runtime's evidence.
 */
object OpenCvNativeSelfTestFixture {
    const val WIDTH: Int = 32
    const val HEIGHT: Int = 24
    const val TAPE_LEFT: Int = 13
    const val TAPE_RIGHT_EXCLUSIVE: Int = 19
    const val MINIMUM_INTERSECTION_OVER_UNION: Double = 0.90

    fun scene(): OpenCvNativeSelfTestScene {
        val groundTruth = BooleanArray(WIDTH * HEIGHT)
        val pixels = ByteArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                val isTape = x in TAPE_LEFT until TAPE_RIGHT_EXCLUSIVE
                groundTruth[index] = isTape
                pixels[index] = if (isTape) TAPE_LUMINANCE.toByte() else FLOOR_LUMINANCE.toByte()
            }
        }
        return OpenCvNativeSelfTestScene(
            frame = LuminanceFrame(
                width = WIDTH,
                height = HEIGHT,
                pixels = pixels,
                capturedAtNanos = 0L,
            ),
            expectedTape = groundTruth,
            minimumIntersectionOverUnion = MINIMUM_INTERSECTION_OVER_UNION,
        )
    }

    private const val FLOOR_LUMINANCE = 200
    private const val TAPE_LUMINANCE = 40
}
