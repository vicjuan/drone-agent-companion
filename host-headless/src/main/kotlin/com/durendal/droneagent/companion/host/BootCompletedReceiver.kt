package com.durendal.droneagent.companion.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = TRIGGERS[intent.action] ?: return
        val pendingResult = goAsync()
        val applicationContext = context.applicationContext
        try {
            BOOT_EXECUTOR.execute {
                try {
                    startFromBoot(applicationContext, trigger)
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingResult.finish()
        }
    }

    private fun startFromBoot(context: Context, trigger: LifecycleTrigger) {
        val evidence = HeadlessHostStorage.lifecycleJournal(context)

        // If boot evidence cannot be durably committed, do not open the runtime.
        try {
            evidence.record(LifecycleEvent.BOOT_RECEIVED, trigger)
        } catch (_: Exception) {
            return
        }

        try {
            // BOOT_COMPLETED and LOCKED_BOOT_COMPLETED are Android's explicit
            // background-FGS-start exceptions. This does not apply to force-stop:
            // a stopped package receives neither broadcast until explicitly started.
            context.startForegroundService(HeadlessAgentService.startIntent(context, trigger))
        } catch (_: RuntimeException) {
            runCatching { evidence.record(LifecycleEvent.BOOT_START_REJECTED, trigger) }
        }
    }

    private companion object {
        val BOOT_EXECUTOR =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "headless-host-boot").apply { isDaemon = true }
            }
        val TRIGGERS =
            mapOf(
                Intent.ACTION_LOCKED_BOOT_COMPLETED to LifecycleTrigger.LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED to LifecycleTrigger.BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED to LifecycleTrigger.PACKAGE_REPLACED,
            )
    }
}
