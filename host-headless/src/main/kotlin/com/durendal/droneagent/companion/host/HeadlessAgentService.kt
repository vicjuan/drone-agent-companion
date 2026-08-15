package com.durendal.droneagent.companion.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Activity-free Android host lifecycle.
 *
 * onCreate performs no runtime/composition work before startForeground. Runtime
 * creation and all shutdown work are serialized on one executor. Safe stop is an
 * app-owned action; force-stop/process SIGKILL cannot promise callbacks or fsync.
 */
class HeadlessAgentService : Service() {
    private val lifecycleExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "headless-host-lifecycle").apply { isDaemon = true }
        }
    private val shutdownRequested = AtomicBoolean(false)
    private val startAdmissionClosed = AtomicBoolean(false)

    private lateinit var evidence: LifecycleEvidenceJournal
    private lateinit var restartTracker: RestartTracker
    @Volatile private var controller: HeadlessRuntimeController? = null
    @Volatile private var lifecycleReady = false
    @Volatile private var safeStopIncomplete = false

    override fun onCreate() {
        super.onCreate()

        // Android requires this within five seconds of startForegroundService.
        // Keep it before journal I/O, asset installation, Ktor, or adapter work.
        createNotificationChannel()
        startForegroundImmediately(buildNotification("Starting local headless control host"))

        evidence = HeadlessHostStorage.lifecycleJournal(this)
        restartTracker = HeadlessHostStorage.restartTracker(this)
        try {
            evidence.record(LifecycleEvent.FOREGROUND_STARTED, LifecycleTrigger.NONE)
            evidence.record(
                LifecycleEvent.FORCE_STOP_RECOVERY_UNAVAILABLE,
                LifecycleTrigger.NONE,
            )

            val owner = application as? HeadlessRuntimeFactoryOwner
            if (owner == null) {
                evidence.record(
                    LifecycleEvent.RUNTIME_FACTORY_UNAVAILABLE,
                    LifecycleTrigger.NONE,
                )
            } else {
                controller = HeadlessRuntimeController(owner.headlessRuntimeFactory, evidence)
                lifecycleReady = true
            }
        } catch (_: Exception) {
            // onStartCommand will take the fail-closed path. A runtime is never
            // created when lifecycle evidence cannot be made durable.
            lifecycleReady = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SAFE_STOP) {
            // Close admission synchronously at the Android callback boundary. Work already queued
            // before this action rechecks the same latch on the lifecycle executor.
            startAdmissionClosed.set(true)
            submitLifecycleWork { performSafeStop() }
            return START_NOT_STICKY
        }

        if (startAdmissionClosed.get() || safeStopIncomplete) {
            return START_NOT_STICKY
        }

        if (intent != null && intent.action != ACTION_START) {
            startAdmissionClosed.set(true)
            submitLifecycleWork { performSafeStop() }
            return START_NOT_STICKY
        }

        val stickyRestart = intent == null
        val trigger =
            if (stickyRestart) {
                LifecycleTrigger.STICKY_RESTART
            } else {
                intent?.getStringExtra(EXTRA_TRIGGER)
                    ?.let(::triggerFromName)
                    ?: LifecycleTrigger.EXPLICIT_START
            }

        submitLifecycleWork {
            if (startAdmissionClosed.get()) return@submitLifecycleWork
            if (!lifecycleReady) {
                performFailClosedStop()
                return@submitLifecycleWork
            }
            handleStart(trigger, stickyRestart)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownRequested.set(true)
        lifecycleExecutor.shutdownNow()

        // Best effort only. Android force-stop, SIGKILL, and the Android 13+
        // Task Manager stop do not guarantee onDestroy or evidence flushing.
        controller?.closeWithin(ON_DESTROY_CLOSE_TIMEOUT_MILLIS)
        if (::evidence.isInitialized) {
            runCatching {
                evidence.record(LifecycleEvent.SERVICE_DESTROYED, LifecycleTrigger.DESTROY)
            }
        }
        super.onDestroy()
    }

    private fun handleStart(trigger: LifecycleTrigger, stickyRestart: Boolean) {
        try {
            evidence.record(LifecycleEvent.SERVICE_START_REQUESTED, trigger)
        } catch (_: Exception) {
            performFailClosedStop()
            return
        }

        val observation =
            try {
                restartTracker.observe(Process.myPid(), stickyRestart)
            } catch (_: Exception) {
                runCatching {
                    evidence.record(LifecycleEvent.RESTART_TRACKER_FAILED, trigger)
                }
                performFailClosedStop()
                return
            }

        try {
            if (observation.stickyRestart) {
                evidence.record(LifecycleEvent.STICKY_RESTART_OBSERVED, trigger)
            }
            if (observation.processChanged) {
                evidence.record(LifecycleEvent.PROCESS_RECREATED, trigger)
            }

            when (controller?.start(trigger) ?: RuntimeStartResult.FAILED) {
                RuntimeStartResult.STARTED,
                RuntimeStartResult.ALREADY_STARTED,
                -> publishRunningNotification()
                RuntimeStartResult.FAILED -> performFailClosedStop()
            }
        } catch (_: Exception) {
            performFailClosedStop()
        }
    }

    private fun performSafeStop() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        val result =
            controller?.closeWithin(SAFE_STOP_CLOSE_TIMEOUT_MILLIS)
                ?: RuntimeCloseResult.CLOSED
        val runtimeClosed = result == RuntimeCloseResult.CLOSED
        // Both lifecycle records follow the safety close attempt. The first event is deliberately
        // named as an attempted close, not as request receipt time: journal contention must never
        // sit in front of neutralization or connector shutdown.
        runCatching {
            evidence.record(LifecycleEvent.SAFE_STOP_CLOSE_ATTEMPTED, LifecycleTrigger.SAFE_STOP)
        }
        val outcomeEvidenceCommitted = runCatching {
            evidence.record(
                if (runtimeClosed) LifecycleEvent.SAFE_STOP_COMPLETED
                else LifecycleEvent.SAFE_STOP_INCOMPLETE,
                LifecycleTrigger.SAFE_STOP,
            )
        }.isSuccess
        val safelyClosed = isSafeStopDurablyComplete(result, outcomeEvidenceCommitted)

        if (!safelyClosed) {
            // Keep the foreground owner alive while an asynchronous neutral/cleanup may still
            // complete. The safe-stop action can retry; ordinary START requests remain rejected.
            safeStopIncomplete = true
            try {
                publishIncompleteNotification()
            } finally {
                // Notification publication is informative, not the retry authority. Even if the
                // platform rejects an update, retain the locked FGS owner and admit a later
                // SAFE_STOP retry instead of stranding shutdownRequested=true forever.
                shutdownRequested.set(false)
            }
            return
        }

        // Runtime close and its fsynced evidence always precede these calls.
        stopForegroundCompat()
        stopSelf()
        lifecycleExecutor.shutdown()
    }

    private fun performFailClosedStop() {
        startAdmissionClosed.set(true)
        if (!shutdownRequested.compareAndSet(false, true)) return
        val result = controller?.closeWithin(SAFE_STOP_CLOSE_TIMEOUT_MILLIS)
        if (result != null && result != RuntimeCloseResult.CLOSED) {
            safeStopIncomplete = true
            try {
                publishIncompleteNotification()
            } finally {
                shutdownRequested.set(false)
            }
            return
        }
        stopForegroundCompat()
        stopSelf()
        lifecycleExecutor.shutdown()
    }

    private fun submitLifecycleWork(block: () -> Unit) {
        try {
            lifecycleExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // A stop is already underway. Never create a second runtime/executor.
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Drone Agent Companion host",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the local drone control host running"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(statusText: String): Notification {
        val safeStopIntent =
            PendingIntent.getService(
                this,
                SAFE_STOP_REQUEST_CODE,
                safeStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Drone Agent Companion")
            .setContentText(statusText)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Safe stop",
                    safeStopIntent,
                ).build(),
            )
            .build()
    }

    private fun publishRunningNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("Local headless control host is running"),
        )
    }

    private fun publishIncompleteNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("Control host locked; safe stop needs attention"),
        )
    }

    private fun startForegroundImmediately(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val ACTION_START =
            "com.durendal.droneagent.companion.host.action.START"
        private const val ACTION_SAFE_STOP =
            "com.durendal.droneagent.companion.host.action.SAFE_STOP"
        private const val EXTRA_TRIGGER =
            "com.durendal.droneagent.companion.host.extra.TRIGGER"
        private const val NOTIFICATION_CHANNEL_ID = "headless-host"
        private const val NOTIFICATION_ID = 7_201
        private const val SAFE_STOP_REQUEST_CODE = 7_202
        private const val SAFE_STOP_CLOSE_TIMEOUT_MILLIS = 4_000L
        private const val ON_DESTROY_CLOSE_TIMEOUT_MILLIS = 1_000L

        fun startIntent(context: Context, trigger: LifecycleTrigger): Intent =
            Intent(context, HeadlessAgentService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRIGGER, trigger.name)

        fun safeStopIntent(context: Context): Intent =
            Intent(context, HeadlessAgentService::class.java).setAction(ACTION_SAFE_STOP)

        fun requestStart(context: Context) {
            context.startForegroundService(startIntent(context, LifecycleTrigger.EXPLICIT_START))
        }

        fun requestSafeStop(context: Context) {
            context.startService(safeStopIntent(context))
        }

        private fun triggerFromName(name: String): LifecycleTrigger? =
            LifecycleTrigger.entries.firstOrNull { it.name == name }
    }
}
