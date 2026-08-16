package com.durendal.droneagent.companion.host

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.durendal.droneagent.companion.console.dji.DjiConsoleCommandExecutor

/** DJI/G520 composition root. No MSDK-backed class is constructed before its loader is installed. */
class HeadlessHostApplication : Application(), HeadlessRuntimeFactoryOwner {
    private var msdkLoaderInstalled = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        msdkLoaderInstalled =
            runCatching {
                val helper = Class.forName("com.cySdkyc.clx.Helper")
                helper.getMethod("install", Application::class.java).invoke(null, this)
                true
            }.onFailure { error ->
                Log.e(TAG, "DJI runtime loader is unavailable; runtime will remain fail-closed", error)
            }.getOrDefault(false)
    }

    override val headlessRuntimeFactory: HeadlessRuntimeFactory by lazy {
        check(msdkLoaderInstalled) { "DJI runtime loader was not installed" }
        HeadlessRuntimeFactory {
            val storageContext = HeadlessHostStorage.deviceProtectedContext(this)
            val webRoot = HeadlessHostStorage.webAssetInstaller(storageContext).install()
            DjiPlatformRuntime(
                context = this,
                webRoot = webRoot,
                auditFile = HeadlessHostStorage.consoleAuditFile(storageContext),
                consoleRuntimeFactory =
                    DjiOfficeConsoleRuntime(
                        executorFactory = { session, monotonicClock ->
                            DjiConsoleExecutorBridge(
                                DjiConsoleCommandExecutor(
                                    agent = session.agent,
                                    monotonicClock = monotonicClock,
                                ),
                            )
                        },
                    ),
                monotonicNanos = SystemClock::elapsedRealtimeNanos,
            )
        }
    }

    private companion object {
        const val TAG = "DjiHeadlessHost"
    }
}
