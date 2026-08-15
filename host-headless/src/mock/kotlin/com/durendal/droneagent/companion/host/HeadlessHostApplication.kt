package com.durendal.droneagent.companion.host

import android.app.Application
import android.os.SystemClock

/** Mock-only Android composition used by the API 34 emulator acceptance lane. */
class HeadlessHostApplication : Application(), HeadlessRuntimeFactoryOwner {
    override val headlessRuntimeFactory: HeadlessRuntimeFactory by lazy {
        HeadlessRuntimeFactory {
            val storageContext = HeadlessHostStorage.deviceProtectedContext(this)
            val webRoot = HeadlessHostStorage.webAssetInstaller(storageContext).install()
            AndroidConsoleRuntime(
                config =
                    AndroidConsoleRuntimeConfig(
                        webRoot = webRoot,
                        auditFile = HeadlessHostStorage.consoleAuditFile(storageContext),
                    ),
                monotonicNanos = SystemClock::elapsedRealtimeNanos,
                epochMillis = System::currentTimeMillis,
            )
        }
    }
}
