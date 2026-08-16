package com.durendal.droneagent.companion.host

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build

internal enum class UsbAccessoryPermissionState {
    IDLE,
    NO_ACCESSORY,
    AMBIGUOUS_ACCESSORY,
    REQUESTING,
    GRANTED,
    DENIED,
    QUERY_FAILED,
    CLOSED,
}

/** Bounded USB truth; deliberately excludes accessory serial/manufacturer/product strings. */
internal data class UsbAccessoryPermissionSnapshot(
    val state: UsbAccessoryPermissionState,
    val connectedAccessoryCount: Int,
)

/**
 * Owns the RC-N3 Android Open Accessory permission lifecycle for the headless process.
 *
 * Permission is requested for exactly one attached accessory. Zero or multiple accessories keep
 * the DJI rail closed. Broadcast contents are never trusted as device identity; every event causes
 * a fresh query through [UsbManager].
 */
internal class UsbAccessoryPermissionCoordinator(
    context: Context,
    private val onStateChanged: (UsbAccessoryPermissionSnapshot) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val lock = Any()
    private var started = false
    private var closed = false
    private var permissionRequestOutstanding = false
    private var snapshot =
        UsbAccessoryPermissionSnapshot(UsbAccessoryPermissionState.IDLE, connectedAccessoryCount = 0)

    private val permissionIntent =
        PendingIntent.getBroadcast(
            appContext,
            PERMISSION_REQUEST_CODE,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                synchronized(lock) { permissionRequestOutstanding = false }
                refresh(requestIfMissing = false, explicitlyDenied = !granted)
            }
        }

    private val attachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_ACCESSORY_ATTACHED,
                    UsbManager.ACTION_USB_ACCESSORY_DETACHED,
                    -> refresh(requestIfMissing = true)
                }
            }
        }

    fun start(): UsbAccessoryPermissionSnapshot {
        synchronized(lock) {
            check(!started) { "USB permission coordinator may be started only once" }
            check(!closed) { "USB permission coordinator is closed" }
            started = true
        }
        try {
            registerReceiver(
                permissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                exported = false,
            )
            registerReceiver(
                attachReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                    addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                },
                // These are privileged platform broadcasts. Their contents still do not select an
                // accessory; the callback only triggers a fresh UsbManager inventory query.
                exported = true,
            )
        } catch (failure: Throwable) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            runCatching { appContext.unregisterReceiver(attachReceiver) }
            synchronized(lock) { started = false }
            throw failure
        }
        return refresh(requestIfMissing = true)
    }

    fun currentSnapshot(): UsbAccessoryPermissionSnapshot = synchronized(lock) { snapshot }

    override fun close() {
        val shouldUnregister =
            synchronized(lock) {
                if (closed) return
                closed = true
                val registered = started
                started = false
                permissionRequestOutstanding = false
                snapshot =
                    UsbAccessoryPermissionSnapshot(
                        UsbAccessoryPermissionState.CLOSED,
                        connectedAccessoryCount = 0,
                    )
                registered
            }
        if (shouldUnregister) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            runCatching { appContext.unregisterReceiver(attachReceiver) }
        }
        permissionIntent.cancel()
        onStateChanged(currentSnapshot())
    }

    private fun refresh(
        requestIfMissing: Boolean,
        explicitlyDenied: Boolean = false,
    ): UsbAccessoryPermissionSnapshot {
        val accessories =
            runCatching { usbManager.accessoryList?.toList().orEmpty() }
                .getOrElse {
                    return publish(
                        UsbAccessoryPermissionSnapshot(
                            UsbAccessoryPermissionState.QUERY_FAILED,
                            connectedAccessoryCount = 0,
                        ),
                    )
                }
        val next =
            when {
                accessories.isEmpty() ->
                    UsbAccessoryPermissionSnapshot(
                        UsbAccessoryPermissionState.NO_ACCESSORY,
                        connectedAccessoryCount = 0,
                    )
                accessories.size != 1 ->
                    UsbAccessoryPermissionSnapshot(
                        UsbAccessoryPermissionState.AMBIGUOUS_ACCESSORY,
                        connectedAccessoryCount = accessories.size,
                    )
                runCatching { usbManager.hasPermission(accessories.single()) }.getOrDefault(false) ->
                    UsbAccessoryPermissionSnapshot(
                        UsbAccessoryPermissionState.GRANTED,
                        connectedAccessoryCount = 1,
                    )
                explicitlyDenied ->
                    UsbAccessoryPermissionSnapshot(
                        UsbAccessoryPermissionState.DENIED,
                        connectedAccessoryCount = 1,
                    )
                requestIfMissing -> requestPermission(accessories.single())
                else ->
                    UsbAccessoryPermissionSnapshot(
                        UsbAccessoryPermissionState.DENIED,
                        connectedAccessoryCount = 1,
                    )
            }
        return publish(next)
    }

    private fun requestPermission(accessory: UsbAccessory): UsbAccessoryPermissionSnapshot {
        val shouldRequest =
            synchronized(lock) {
                if (closed || permissionRequestOutstanding) {
                    false
                } else {
                    permissionRequestOutstanding = true
                    true
                }
            }
        if (!shouldRequest) {
            return UsbAccessoryPermissionSnapshot(
                UsbAccessoryPermissionState.REQUESTING,
                connectedAccessoryCount = 1,
            )
        }
        return runCatching {
            usbManager.requestPermission(accessory, permissionIntent)
            UsbAccessoryPermissionSnapshot(
                UsbAccessoryPermissionState.REQUESTING,
                connectedAccessoryCount = 1,
            )
        }.getOrElse {
            synchronized(lock) { permissionRequestOutstanding = false }
            UsbAccessoryPermissionSnapshot(
                UsbAccessoryPermissionState.QUERY_FAILED,
                connectedAccessoryCount = 1,
            )
        }
    }

    private fun publish(next: UsbAccessoryPermissionSnapshot): UsbAccessoryPermissionSnapshot {
        val shouldNotify =
            synchronized(lock) {
                if (closed && next.state != UsbAccessoryPermissionState.CLOSED) return snapshot
                val changed = snapshot != next
                snapshot = next
                changed
            }
        if (shouldNotify) onStateChanged(next)
        return next
    }

    private fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        exported: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                filter,
                if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 52_001
        const val ACTION_USB_PERMISSION =
            "com.durendal.droneagent.companion.host.action.USB_ACCESSORY_PERMISSION"
    }
}
