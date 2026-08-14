package com.durendal.droneagent.companion.console.protocol

/**
 * Module boundary for the browser-facing protocol introduced by issue #3.
 *
 * The wire model deliberately starts in this companion-owned package instead
 * of extending the frozen drone-platform agent protocol in the vendor build.
 */
object ConsoleProtocolModule {
    const val NAME: String = "companion-console"
}
