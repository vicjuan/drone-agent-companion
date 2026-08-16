package com.durendal.droneagent.companion.host

import android.util.Log
import com.durendal.droneagent.adapter.dji.DjiDroneAgent
import com.durendal.droneagent.core.connection.ConnectionState
import com.durendal.droneagent.core.registration.RegistrationState
import com.durendal.droneagent.core.stream.RtmpConfig
import com.durendal.droneagent.core.stream.StreamState
import com.durendal.droneagent.core.stream.StreamStatus
import com.durendal.droneagent.observation.DecodedFrameStreamFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Owns DJI live video and the observation-only decoded-frame graph, but never telemetry. */
internal class DjiObservationMediaLifecycle(
    private val agent: DjiDroneAgent,
    private val requestSerialReconcile: () -> Unit = {},
) {
    private val stopRequested = AtomicBoolean(false)
    private var closed = false
    private var hardwareReady = false
    private var statusListenerInstalled = false
    private var streamTargetNetworkHandle: Long? = null
    private var streamNetworkHandle: Long? = null
    private var streamStartAttempts = 0
    private var visionStartAttempts = 0
    private var visionSession: HeadlessOpenCvObservationSession? = null

    private val streamStatusListener: (StreamStatus) -> Unit = { status ->
        if (
            status.state == StreamState.STREAMING ||
            status.state == StreamState.IDLE ||
            status.state == StreamState.ERROR ||
            status.state == StreamState.BLOCKED
        ) {
            // DJI invokes this off the platform owner. Never mutate lifecycle state here.
            requestSerialReconcile()
        }
    }

    /** Called only on DjiPlatformRuntime's serial lifecycle executor. */
    fun reconcile(
        usbPermissionGranted: Boolean,
        ethernetBinding: AndroidFixedEthernetBinding?,
        msdk: DjiMsdkStartupSnapshot,
    ) {
        if (closed || stopRequested.get()) return
        installStatusListenerOnce()
        val nextHardwareReady =
            usbPermissionGranted &&
                msdk.registrationState == RegistrationState.REGISTERED &&
                msdk.connectionState == ConnectionState.AIRCRAFT_CONNECTED

        if (!nextHardwareReady) {
            if (hardwareReady) {
                stopStream()
                stopVisionWithin(VISION_STOP_MILLIS)
            }
            hardwareReady = false
            resetStreamTarget()
            visionStartAttempts = 0
            return
        }

        if (!hardwareReady) {
            hardwareReady = true
            visionStartAttempts = 0
        }
        ensureVisionStarted()

        if (ethernetBinding == null) {
            stopStream()
            resetStreamTarget()
            return
        }

        val networkHandle = ethernetBinding.androidNetwork.networkHandle
        if (streamTargetNetworkHandle != networkHandle) {
            stopStream()
            streamTargetNetworkHandle = networkHandle
            streamStartAttempts = 0
        }

        when (agent.liveStream.status().state) {
            StreamState.STREAMING -> streamNetworkHandle = networkHandle
            StreamState.STARTING -> streamNetworkHandle = networkHandle
            StreamState.STOPPING -> return
            StreamState.IDLE,
            StreamState.ERROR,
            StreamState.BLOCKED,
            -> streamNetworkHandle = null
        }
        if (streamNetworkHandle == null) {
            runCatching { startStream(ethernetBinding) }
                .onFailure { failure ->
                    streamNetworkHandle = null
                    Log.e(TAG, "DJI RTMP start attempt failed", failure)
                    requestSerialReconcile()
                }
        }
    }

    /** Allocation-free terminal signal suitable for the service callback thread. */
    fun requestStop() {
        stopRequested.set(true)
        visionSession?.requestStop()
    }

    /** Requests stream stop and waits at most [timeoutMillis] for decoded-frame cleanup. */
    fun closeWithin(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        if (closed) return true
        requestStop()
        closed = true
        if (statusListenerInstalled) {
            runCatching { agent.liveStream.removeStatusListener(streamStatusListener) }
            statusListenerInstalled = false
        }
        val streamStopped = stopStream()
        val visionStopped = stopVisionWithin(timeoutMillis)
        return streamStopped && visionStopped
    }

    private fun ensureVisionStarted() {
        visionSession?.let { existing ->
            if (existing.snapshot().lifecycleState == OpenCvObservationLifecycleState.CLOSED) {
                visionSession = null
            } else {
                Log.w(TAG, "Previous OpenCV observation graph is still closing; refusing overlap")
                return
            }
        }
        if (visionStartAttempts < Int.MAX_VALUE) visionStartAttempts += 1
        val session =
            HeadlessOpenCvVision.createObservationSession(
                DecodedFrameStreamFactory(agent::decodedFrameSource),
            )
        visionSession = session
        when (val result = session.start()) {
            OpenCvObservationStartResult.Started ->
                Log.i(TAG, "DJI decoded-frame OpenCV observation started")
            is OpenCvObservationStartResult.Failure -> {
                Log.e(TAG, "DJI decoded-frame OpenCV observation start failed: ${result.reason}")
                if (session.stopWithin(VISION_STOP_MILLIS) == OpenCvObservationStopResult.CLOSED) {
                    visionSession = null
                    // A receiver or camera decoder can lag the hardware-ready callback. Retry
                    // through the platform owner's delayed serial executor; never recurse on a
                    // DJI callback thread or overlap a graph that is still closing.
                    requestSerialReconcile()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startStream(binding: AndroidFixedEthernetBinding) {
        // The destination is derived only from the freshly accepted isolated /30 policy binding;
        // no arbitrary host, route, DNS result or browser input can select a publisher target.
        val peer = binding.policyBinding.operatorPeerIpv4
        val endpoint = "rtmp://$peer:$RTMP_PORT/$RTMP_PATH"
        if (streamStartAttempts < Int.MAX_VALUE) streamStartAttempts += 1
        agent.liveStream.configure(RtmpConfig(endpoint))
        val status = agent.liveStream.start()
        streamNetworkHandle =
            if (status.state == StreamState.STARTING || status.state == StreamState.STREAMING) {
                binding.androidNetwork.networkHandle
            } else {
                null
            }
        Log.i(TAG, "DJI RTMP start requested on accepted point-to-point peer: ${status.state}")
        if (streamNetworkHandle == null) {
            // A synchronous ERROR/BLOCKED/IDLE result is not guaranteed to produce another
            // status callback. Re-enter only through the platform's serial executor so the
            // retry loop cannot recurse on the DJI callback thread.
            requestSerialReconcile()
        }
    }

    private fun stopStream(): Boolean {
        if (streamNetworkHandle == null) return true
        streamNetworkHandle = null
        return runCatching {
            agent.liveStream.stop()
            true
        }.onFailure { Log.e(TAG, "DJI RTMP stop request failed", it) }
            .getOrDefault(false)
    }

    private fun installStatusListenerOnce() {
        if (statusListenerInstalled) return
        agent.liveStream.addStatusListener(streamStatusListener)
        statusListenerInstalled = true
    }

    private fun resetStreamTarget() {
        streamTargetNetworkHandle = null
        streamStartAttempts = 0
    }

    private fun stopVisionWithin(timeoutMillis: Long): Boolean {
        val session = visionSession ?: return true
        session.requestStop()
        return when (session.stopWithin(timeoutMillis)) {
            OpenCvObservationStopResult.CLOSED -> {
                visionSession = null
                true
            }
            OpenCvObservationStopResult.TIMED_OUT -> {
                Log.e(TAG, "DJI decoded-frame OpenCV observation stop timed out")
                false
            }
            OpenCvObservationStopResult.FAILED -> {
                Log.e(TAG, "DJI decoded-frame OpenCV observation stop failed")
                false
            }
        }
    }

    private companion object {
        const val TAG = "DjiObservationMedia"
        const val RTMP_PORT = 1935
        const val RTMP_PATH = "dji-main"
        const val VISION_STOP_MILLIS = 2_000L
    }
}
