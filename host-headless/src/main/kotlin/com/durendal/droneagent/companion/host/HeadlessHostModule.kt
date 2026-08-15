package com.durendal.droneagent.companion.host

/** Stable lifecycle limits that acceptance tests and operator docs can reference. */
object HeadlessHostModule {
    const val FORCE_STOP_AUTO_RECOVERY_SUPPORTED: Boolean = false

    /**
     * Android force-stop places the package in the stopped state. The system will
     * not deliver boot broadcasts or recreate this service until an explicit
     * external/user start clears that state. START_STICKY is not a workaround.
     */
    const val FORCE_STOP_LIMITATION: String =
        "Android force-stop cannot auto-recover; explicit commissioning/start is required"
}
