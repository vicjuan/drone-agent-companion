package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.vision.opencv.OpenCvNativeInitializer
import com.durendal.droneagent.companion.vision.opencv.OpenCvRuntimeIdentity
import com.durendal.droneagent.observation.DecodedFrameFormat
import com.durendal.droneagent.observation.DecodedFrameListener
import com.durendal.droneagent.observation.DecodedFrameStartResult
import com.durendal.droneagent.observation.DecodedFrameStream
import com.durendal.droneagent.observation.DecodedFrameStreamFactory
import com.durendal.droneagent.observation.LiveVisionBridge
import com.durendal.droneagent.vision.centerline.CenterlineExtractor
import com.durendal.droneagent.vision.frame.LatestFrameCounts
import com.durendal.droneagent.vision.frame.LatestFrameGate
import com.durendal.droneagent.vision.imagespace.ImageSpaceErrorEstimator
import com.durendal.droneagent.vision.perf.LatencySummary
import com.durendal.droneagent.vision.pipeline.LiveVisionPipeline
import com.durendal.droneagent.vision.pipeline.VisionFrameAnalyzer
import com.durendal.droneagent.vision.pipeline.VisionMode
import com.durendal.droneagent.vision.segment.Segmenter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * One-shot observation-only session from a decoded NV21 stream to the companion OpenCV backend.
 *
 * Pixel conversion, buffer ownership, drop-to-latest and the sole CV worker remain owned by the
 * vendor [LiveVisionBridge]. This class adds only lifecycle fencing: source ownership, a terminal
 * callback-admission bit, in-flight callback draining and a cleanup daemon around source methods
 * whose interface has no bounded-stop contract. It has no command, admission or actuation surface.
 * The injected vendor source must make stop/close idempotent: bridge timeout recovery retries stop
 * on the same owned graph, and an explicit safe-stop may retry close after a reported failure.
 */
internal class HeadlessOpenCvObservationSession(
    private val streamFactory: DecodedFrameStreamFactory,
    private val nativeInitializer: OpenCvNativeInitializer,
    private val segmenterFactory: (OpenCvNativeInitializer) -> Segmenter,
    private val bridgeStopAwaitMillis: Long = DEFAULT_BRIDGE_STOP_AWAIT_MILLIS,
    private val cleanupExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, CLEANUP_THREAD_NAME).apply { isDaemon = true }
        },
) {
    init {
        require(bridgeStopAwaitMillis > 0L) { "bridgeStopAwaitMillis must be positive" }
    }

    private val lifecycleLock = Object()
    private val stopRequested = AtomicBoolean(false)
    private val firstFrame = AtomicReference<OpenCvFrameEvidence?>()
    private val cleanupAttemptFailureCount = AtomicLong(0L)
    private val bridgeStopTimeoutCount = AtomicLong(0L)

    @Volatile
    private var graph: ObservationGraph? = null

    private var state = OpenCvObservationLifecycleState.NEW
    private var startInProgress = false
    private var primaryFailureReason: OpenCvObservationFailureReason? = null
    private var failureReason: OpenCvObservationFailureReason? = null
    private var runtimeIdentity: OpenCvRuntimeIdentity? = null
    private var measurementStarted = false
    private var finalCounts: FrameCounts? = null
    private var finalMeasurement = false
    private var cleanupFuture: Future<OpenCvObservationStopResult>? = null

    fun start(): OpenCvObservationStartResult {
        synchronized(lifecycleLock) {
            if (state != OpenCvObservationLifecycleState.NEW) {
                return OpenCvObservationStartResult.Failure(
                    failureReason ?: OpenCvObservationFailureReason.CANCELLED,
                )
            }
            state = OpenCvObservationLifecycleState.STARTING
            startInProgress = true
        }

        var openedSource: DecodedFrameStream? = null
        var createdGraph: ObservationGraph? = null
        var stage = OpenCvObservationFailureReason.NATIVE_INITIALIZATION
        return try {
            ensureNotCancelled()
            val identity = nativeInitializer.initialize()
            synchronized(lifecycleLock) { runtimeIdentity = identity }

            ensureNotCancelled()
            stage = OpenCvObservationFailureReason.PIPELINE_COMPOSITION
            val gate = LatestFrameGate()
            val loadedInitializer = OpenCvNativeInitializer { identity }
            val pipeline =
                LiveVisionPipeline(
                    analyzer =
                        VisionFrameAnalyzer(
                            segmenter = segmenterFactory(loadedInitializer),
                            centerlineExtractor = CenterlineExtractor(),
                            imageSpaceEstimator = ImageSpaceErrorEstimator(),
                            trackingErrorEstimatorFactory = { _, _ ->
                                error("observation-only BENCH pipeline must never project")
                            },
                            mode = VisionMode.BENCH_IMAGE_SPACE,
                            poseProvider = {
                                error("observation-only BENCH pipeline must never request pose")
                            },
                        ),
                    gate = gate,
                    listener = { result ->
                        // Evidence retains at most one full mask per one-shot session. Copying a
                        // 720p BooleanArray on every camera frame would reintroduce the sustained
                        // allocation pressure the vendor bridge deliberately removes.
                        if (firstFrame.get() == null) {
                            firstFrame.compareAndSet(null, OpenCvFrameEvidence.from(result))
                        }
                    },
                )

            ensureNotCancelled()
            val source = try {
                streamFactory.open()
            } catch (_: Throwable) {
                val reason =
                    if (stopRequested.get()) {
                        OpenCvObservationFailureReason.CANCELLED
                    } else {
                        OpenCvObservationFailureReason.SOURCE_OPEN
                    }
                return failStart(reason)
            }
            openedSource = source
            val fencedSource = QuiescingDecodedFrameStream(source)
            val bridge =
                LiveVisionBridge(
                    frameSource = fencedSource,
                    pipeline = pipeline,
                    stopAwaitMillis = bridgeStopAwaitMillis,
                )
            createdGraph = ObservationGraph(fencedSource, gate, pipeline, bridge)
            graph = createdGraph

            ensureNotCancelled()
            stage = OpenCvObservationFailureReason.SOURCE_START
            when (bridge.start(DecodedFrameFormat.NV21)) {
                DecodedFrameStartResult.Started -> synchronized(lifecycleLock) {
                    measurementStarted = true
                }
                is DecodedFrameStartResult.Failure -> {
                    createdGraph.source.closeAdmission()
                    val reason =
                        if (stopRequested.get()) {
                            OpenCvObservationFailureReason.CANCELLED
                        } else {
                            OpenCvObservationFailureReason.SOURCE_START
                        }
                    val failure = failStart(reason)
                    beginCleanup()
                    return failure
                }
            }

            val committedRunning = synchronized(lifecycleLock) {
                if (
                    !stopRequested.get() &&
                    state == OpenCvObservationLifecycleState.STARTING &&
                    cleanupFuture == null
                ) {
                    state = OpenCvObservationLifecycleState.RUNNING
                    lifecycleLock.notifyAll()
                    true
                } else {
                    false
                }
            }
            if (!committedRunning) {
                createdGraph.source.closeAdmission()
                beginCleanup()
                failStart(OpenCvObservationFailureReason.CANCELLED)
            } else {
                OpenCvObservationStartResult.Started
            }
        } catch (_: Throwable) {
            val reason = if (stopRequested.get()) OpenCvObservationFailureReason.CANCELLED else stage
            val failure = failStart(reason)
            if (createdGraph != null) {
                createdGraph.source.closeAdmission()
                beginCleanup()
            } else {
                openedSource?.let { runCatching(it::close) }
            }
            failure
        } finally {
            synchronized(lifecycleLock) {
                startInProgress = false
                lifecycleLock.notifyAll()
            }
        }
    }

    /** Terminal, allocation-free signal suitable for the service callback thread. */
    fun requestStop() {
        stopRequested.set(true)
        graph?.source?.closeAdmission()
    }

    /**
     * Requests ordered cleanup and waits no longer than [timeoutMillis]. The cleanup task is never
     * cancelled on timeout; later calls wait for the same owned graph instead of creating another
     * worker or source.
     */
    fun stopWithin(timeoutMillis: Long): OpenCvObservationStopResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        requestStop()
        val cleanup = beginCleanup()
        return try {
            cleanup.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            OpenCvObservationStopResult.TIMED_OUT
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            OpenCvObservationStopResult.TIMED_OUT
        } catch (_: Throwable) {
            markCleanupFailed()
            OpenCvObservationStopResult.FAILED
        }
    }

    /** Called only from the host's already-bounded cleanup daemon, never from Android main. */
    fun awaitClosed() {
        requestStop()
        check(beginCleanup().get() == OpenCvObservationStopResult.CLOSED) {
            "OpenCV observation cleanup failed"
        }
    }

    fun snapshot(): OpenCvObservationSnapshot {
        val capturedState: OpenCvObservationLifecycleState
        val capturedFailure: OpenCvObservationFailureReason?
        val capturedIdentity: OpenCvRuntimeIdentity?
        val capturedFinalCounts: FrameCounts?
        val capturedFinal: Boolean
        synchronized(lifecycleLock) {
            capturedState = state
            capturedFailure = failureReason
            capturedIdentity = runtimeIdentity
            capturedFinalCounts = finalCounts
            capturedFinal = finalMeasurement
        }
        val capturedGraph = graph
        val counts = capturedFinalCounts ?: capturedGraph?.gate?.snapshotCounts() ?: FrameCounts()
        val bridgeFailures = capturedGraph?.bridge?.failureReport()
        val report = capturedGraph?.bridge?.report()
        return OpenCvObservationSnapshot(
            lifecycleState = capturedState,
            failureReason = capturedFailure,
            runtimeIdentity = capturedIdentity,
            firstFrame = firstFrame.get(),
            processedFrameCount = capturedGraph?.pipeline?.processedFrameCount() ?: 0L,
            offeredFrameCount = counts.offered,
            droppedFrameCount = counts.dropped,
            polledFrameCount = counts.polled,
            shutdownDiscardedFrameCount =
                (counts.offered - counts.dropped - counts.polled).coerceAtLeast(0L),
            frameConversionFailureCount = bridgeFailures?.frameConversionFailureCount ?: 0L,
            pipelineFailureCount = bridgeFailures?.pipelineFailureCount ?: 0L,
            cleanupAttemptFailureCount = cleanupAttemptFailureCount.get(),
            bridgeStopTimeoutCount = bridgeStopTimeoutCount.get(),
            decoderReceiveToCvPoll = report?.frameAge?.toEvidence(),
            cvProcessing = report?.cvProcessing?.toEvidence(),
            finalMeasurement = capturedFinal,
        )
    }

    private fun ensureNotCancelled() {
        check(!stopRequested.get()) { "OpenCV observation startup was cancelled" }
    }

    private fun failStart(reason: OpenCvObservationFailureReason): OpenCvObservationStartResult {
        synchronized(lifecycleLock) {
            primaryFailureReason = reason
            failureReason = reason
            if (state != OpenCvObservationLifecycleState.STOPPING) {
                state = OpenCvObservationLifecycleState.FAILED
            }
            lifecycleLock.notifyAll()
        }
        return OpenCvObservationStartResult.Failure(reason)
    }

    private fun beginCleanup(): Future<OpenCvObservationStopResult> = synchronized(lifecycleLock) {
        cleanupFuture?.let { existing ->
            if (state == OpenCvObservationLifecycleState.CLOSED || !existing.isDone) {
                return@synchronized existing
            }
            // A prior bounded attempt failed deterministically. It stopped after two identical
            // failures, but a later explicit safe-stop is allowed to retry the same owned graph
            // after external state may have changed.
            cleanupFuture = null
        }
        state = OpenCvObservationLifecycleState.STOPPING
        cleanupExecutor.submit(Callable(::cleanupUntilClosed)).also { cleanupFuture = it }
    }

    private fun cleanupUntilClosed(): OpenCvObservationStopResult {
        val capturedGraph = awaitGraphOrFinishedStart()
        if (capturedGraph == null) {
            synchronized(lifecycleLock) {
                state = OpenCvObservationLifecycleState.CLOSED
                lifecycleLock.notifyAll()
            }
            cleanupExecutor.shutdown()
            return OpenCvObservationStopResult.CLOSED
        }

        var deterministicStopFailures = 0
        while (true) {
            val stopped =
                try {
                    capturedGraph.bridge.stop()
                } catch (_: Throwable) {
                    cleanupAttemptFailureCount.incrementAndGet()
                    deterministicStopFailures++
                    if (deterministicStopFailures >= MAX_DETERMINISTIC_CLEANUP_FAILURES) {
                        return finishCleanupFailed()
                    }
                    null
                }
            if (stopped == LiveVisionBridge.StopResult.STOPPED) break
            if (stopped == LiveVisionBridge.StopResult.TIMED_OUT) {
                // shutdownNow() has already been issued. The same idempotent stop may be retried
                // while the owned worker quiesces; callers keep their own stopWithin() waits
                // bounded and no new graph/source is created.
                bridgeStopTimeoutCount.incrementAndGet()
                // LiveVisionBridge restores the interrupt flag when its await is interrupted.
                // This daemon has no cancellation contract: leaving the flag set would make every
                // later await fail immediately and permanently strand the owned source. Consume
                // and bound repeated source-induced interrupts as deterministic cleanup failures.
                if (Thread.interrupted()) {
                    cleanupAttemptFailureCount.incrementAndGet()
                    deterministicStopFailures++
                    if (deterministicStopFailures >= MAX_DETERMINISTIC_CLEANUP_FAILURES) {
                        return finishCleanupFailed()
                    }
                }
            }
            LockSupport.parkNanos(CLEANUP_RETRY_NANOS)
        }

        capturedGraph.source.awaitCallbacksDrained()
        val frozenCounts = capturedGraph.gate.snapshotCounts()
        var deterministicCloseFailures = 0
        while (true) {
            try {
                capturedGraph.source.close()
                break
            } catch (_: Throwable) {
                cleanupAttemptFailureCount.incrementAndGet()
                deterministicCloseFailures++
                if (deterministicCloseFailures >= MAX_DETERMINISTIC_CLEANUP_FAILURES) {
                    return finishCleanupFailed()
                }
                LockSupport.parkNanos(CLEANUP_RETRY_NANOS)
            }
        }
        synchronized(lifecycleLock) {
            finalCounts = frozenCounts
            finalMeasurement = measurementStarted
            if (failureReason == OpenCvObservationFailureReason.CLEANUP) {
                failureReason = primaryFailureReason
            }
            state = OpenCvObservationLifecycleState.CLOSED
            lifecycleLock.notifyAll()
        }
        cleanupExecutor.shutdown()
        return OpenCvObservationStopResult.CLOSED
    }

    private fun awaitGraphOrFinishedStart(): ObservationGraph? = synchronized(lifecycleLock) {
        while (startInProgress) lifecycleLock.wait()
        graph
    }

    private fun markCleanupFailed() {
        synchronized(lifecycleLock) {
            failureReason = OpenCvObservationFailureReason.CLEANUP
            state = OpenCvObservationLifecycleState.FAILED
            lifecycleLock.notifyAll()
        }
    }

    private fun finishCleanupFailed(): OpenCvObservationStopResult {
        markCleanupFailed()
        return OpenCvObservationStopResult.FAILED
    }

    private data class ObservationGraph(
        val source: QuiescingDecodedFrameStream,
        val gate: LatestFrameGate,
        val pipeline: LiveVisionPipeline,
        val bridge: LiveVisionBridge,
    )

    private data class FrameCounts(
        val offered: Long = 0L,
        val dropped: Long = 0L,
        val polled: Long = 0L,
    )

    private fun LatestFrameGate.snapshotCounts(): FrameCounts {
        val counts = LatestFrameCounts()
        snapshotFrameCounts(counts)
        return FrameCounts(counts.offeredFrameCount, counts.droppedFrameCount, counts.polledFrameCount)
    }

    private fun LatencySummary.toEvidence() =
        OpenCvLatencyEvidence(sampleCount, p50Nanos, p95Nanos, maxNanos)

    /**
     * One-session forwarding fence around the vendor stream. No pixels are copied or queued here;
     * the downstream LiveVisionBridge remains the only buffer owner and consumer pipeline.
     */
    private class QuiescingDecodedFrameStream(
        private val delegate: DecodedFrameStream,
    ) : DecodedFrameStream {
        private val callbackLock = Object()
        private val terminalAdmissionClosed = AtomicBoolean(false)
        private val acceptingGeneration = AtomicLong(0L)
        private var nextGeneration = 0L
        private var downstream: DecodedFrameListener? = null
        private var inFlightCallbacks = 0
        private var upstreamRegistered = false

        private val upstream = DecodedFrameListener { observation, data, offset, length ->
            val generation = acceptingGeneration.get()
            if (generation == 0L) return@DecodedFrameListener
            val target = synchronized(callbackLock) {
                if (acceptingGeneration.get() != generation) return@synchronized null
                downstream?.also { inFlightCallbacks++ }
            } ?: return@DecodedFrameListener
            try {
                target.onDecodedFrame(observation, data, offset, length)
            } finally {
                synchronized(callbackLock) {
                    inFlightCallbacks--
                    if (inFlightCallbacks == 0) callbackLock.notifyAll()
                }
            }
        }

        override fun addListener(listener: DecodedFrameListener) {
            synchronized(callbackLock) {
                check(downstream == null) { "observation session accepts exactly one listener" }
                downstream = listener
            }
        }

        override fun start(format: DecodedFrameFormat): DecodedFrameStartResult {
            synchronized(callbackLock) {
                if (terminalAdmissionClosed.get()) {
                    return DecodedFrameStartResult.Failure("observation session is stopping")
                }
                check(downstream != null) { "bridge listener must be registered before source start" }
                check(!upstreamRegistered) { "decoded source is one-shot" }
                nextGeneration++
                acceptingGeneration.set(nextGeneration)
                upstreamRegistered = true
            }
            try {
                delegate.addListener(upstream)
            } catch (failure: Throwable) {
                acceptingGeneration.set(0L)
                synchronized(callbackLock) { upstreamRegistered = false }
                throw failure
            }
            if (terminalAdmissionClosed.get()) {
                acceptingGeneration.set(0L)
                unregisterUpstream()
                return DecodedFrameStartResult.Failure("observation session is stopping")
            }
            val result = delegate.start(format)
            if (result is DecodedFrameStartResult.Failure) {
                acceptingGeneration.set(0L)
                unregisterUpstream()
            }
            return result
        }

        override fun removeListener(listener: DecodedFrameListener) {
            acceptingGeneration.set(0L)
            synchronized(callbackLock) {
                if (downstream === listener) downstream = null
            }
        }

        override fun stop() {
            closeAdmission()
            unregisterUpstream()
            delegate.stop()
        }

        override fun close() {
            closeAdmission()
            unregisterUpstream()
            delegate.close()
        }

        fun closeAdmission() {
            synchronized(callbackLock) {
                terminalAdmissionClosed.set(true)
                acceptingGeneration.set(0L)
            }
        }

        fun awaitCallbacksDrained() {
            synchronized(callbackLock) {
                while (inFlightCallbacks != 0) callbackLock.wait()
            }
        }

        private fun unregisterUpstream() {
            val shouldRemove = synchronized(callbackLock) {
                if (!upstreamRegistered) false else {
                    upstreamRegistered = false
                    true
                }
            }
            if (shouldRemove) delegate.removeListener(upstream)
        }
    }

    private companion object {
        const val DEFAULT_BRIDGE_STOP_AWAIT_MILLIS = 2_000L
        const val MAX_DETERMINISTIC_CLEANUP_FAILURES = 2
        const val CLEANUP_RETRY_NANOS = 10_000_000L
        const val CLEANUP_THREAD_NAME = "opencv-observation-cleanup"
    }
}
