package com.durendal.droneagent.companion.host

import android.content.Context
import java.io.File

/** Device-protected paths required before the user unlocks after boot. */
object HeadlessHostStorage {
    private const val ROOT_DIRECTORY = "headless-host"
    private const val LIFECYCLE_DIRECTORY = "lifecycle"
    private const val CONSOLE_AUDIT_DIRECTORY = "console-audit"
    private const val WEB_RELEASES_DIRECTORY = "web-releases"

    fun deviceProtectedContext(context: Context): Context =
        context.createDeviceProtectedStorageContext()

    fun root(context: Context): File =
        File(deviceProtectedContext(context).filesDir, ROOT_DIRECTORY)

    fun lifecycleDirectory(context: Context): File = File(root(context), LIFECYCLE_DIRECTORY)

    fun restartStateFile(context: Context): File =
        File(lifecycleDirectory(context), RestartTracker.FILE_NAME)

    fun webReleaseRoot(context: Context): File = File(root(context), WEB_RELEASES_DIRECTORY)

    fun consoleAuditFile(context: Context): File =
        File(File(root(context), CONSOLE_AUDIT_DIRECTORY), "console-events.jsonl")

    fun lifecycleJournal(context: Context): LifecycleEvidenceJournal =
        LifecycleEvidenceJournal(lifecycleDirectory(context))

    fun restartTracker(context: Context): RestartTracker = RestartTracker(restartStateFile(context))

    fun webAssetInstaller(context: Context): WebAssetInstaller =
        WebAssetInstaller(AndroidWebAssetSource(context.assets), webReleaseRoot(context))
}
