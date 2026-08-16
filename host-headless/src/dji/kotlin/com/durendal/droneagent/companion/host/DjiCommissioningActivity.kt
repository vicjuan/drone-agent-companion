package com.durendal.droneagent.companion.host

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Explicit installer/USB entrypoint for an otherwise headless package.
 *
 * Starting this activity only clears Android's stopped-package state and starts the locked
 * foreground lifecycle owner. Commissioning authority remains server-owned and unavailable from
 * this Activity or its Intent extras.
 */
class DjiCommissioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissionsOrStart()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestRequiredPermissionsOrStart()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != DJI_PERMISSION_REQUEST_CODE) return
        if (permissions.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startLockedHostAndFinish()
        } else {
            Log.e(TAG, "Required DJI runtime permissions were denied; host remains stopped")
            finishAndRemoveTask()
        }
    }

    private fun requestRequiredPermissionsOrStart() {
        val missing = requiredRuntimePermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startLockedHostAndFinish()
        } else {
            requestPermissions(missing.toTypedArray(), DJI_PERMISSION_REQUEST_CODE)
        }
    }

    private fun startLockedHostAndFinish() {
        runCatching { HeadlessAgentService.requestStart(applicationContext) }
            .onFailure { Log.e(TAG, "Unable to start the locked DJI host", it) }
        finishAndRemoveTask()
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        // POST_NOTIFICATIONS is intentionally not an MSDK admission gate. Android can still run
        // the connected-device foreground service when the operator declines notification UI.
        // Legacy storage grants are meaningful only through Android 10. Requesting them on newer
        // releases can return permanently denied for a target-34 app and must not strand MSDK.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private companion object {
        const val TAG = "DjiCommissioningActivity"
        const val DJI_PERMISSION_REQUEST_CODE = 52_002
    }
}
