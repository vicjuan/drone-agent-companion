package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.vision.opencv.OpenCvNativeInitializer
import com.durendal.droneagent.companion.vision.opencv.OpenCvRuntimeIdentity
import com.durendal.droneagent.observation.DecodedFrameFormat
import com.durendal.droneagent.observation.DecodedFrameListener
import com.durendal.droneagent.observation.DecodedFrameObservation
import com.durendal.droneagent.observation.DecodedFrameStartResult
import com.durendal.droneagent.observation.DecodedFrameStream
import com.durendal.droneagent.observation.DecodedFrameStreamFactory
import com.durendal.droneagent.vision.frame.LuminanceFrame
import com.durendal.droneagent.vision.segment.SegmentationMask
import com.durendal.droneagent.vision.segment.SegmentationResult
import com.durendal.droneagent.vision.segment.Segmenter
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessOpenCvObservationSessionTest {
    @Test
    fun `decoded callback is copied before producer buffer recycling and final evidence freezes`() {
        val source = FakeDecodedFrameStream()
        val segmenterEntered = CountDownLatch(1)
        val inspectPixels = CountDownLatch(1)
        val observedPixels = AtomicReference<ByteArray>()
        val session = session(source) {
            Segmenter { frame ->
                segmenterEntered.countDown()
                assertTrue(inspectPixels.await(2, TimeUnit.SECONDS))
                observedPixels.set(frame.pixels.copyOf())
                thresholdMask(frame)
            }
        }
        val originalLuma = canonicalLuma()
        val decoderOwned = nv21(originalLuma)

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        source.emitAsync(decoderOwned)
        assertTrue(segmenterEntered.await(2, TimeUnit.SECONDS))
        decoderOwned.fill(0)
        inspectPixels.countDown()
        val live = awaitSnapshot(session) { it.firstFrame != null }

        assertArrayEquals(originalLuma, observedPixels.get())
        assertArrayEquals(
            originalLuma.map { (it.toInt() and 0xff) < 100 }.toBooleanArray(),
            live.firstFrame!!.copyTapeMask(),
        )
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))

        val frozen = session.snapshot()
        assertTrue(frozen.finalMeasurement)
        assertEquals(OpenCvObservationLifecycleState.CLOSED, frozen.lifecycleState)
        assertEquals(1L, frozen.offeredFrameCount)
        assertEquals(1L, frozen.polledFrameCount)
        assertEquals(0L, frozen.shutdownDiscardedFrameCount)
        assertEquals(1, source.closeCount.get())
        assertTrue(source.listeners.isEmpty())
    }

    @Test
    fun `drop to latest processes first and newest without building a queue`() {
        val source = FakeDecodedFrameStream()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val processed = AtomicInteger(0)
        val session = session(source) {
            Segmenter { frame ->
                if (processed.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                thresholdMask(frame)
            }
        }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        source.emit(nv21(canonicalLuma(fill = 30)))
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        repeat(4) { index -> source.emit(nv21(canonicalLuma(fill = 120 + index))) }
        releaseFirst.countDown()
        awaitSnapshot(session) { it.processedFrameCount == 2L }
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))

        val frozen = session.snapshot()
        assertEquals(5L, frozen.offeredFrameCount)
        assertEquals(3L, frozen.droppedFrameCount)
        assertEquals(2L, frozen.polledFrameCount)
        assertEquals(2L, frozen.processedFrameCount)
    }

    @Test
    fun `full mask evidence is retained only for the first analyzed frame`() {
        val source = FakeDecodedFrameStream()
        val session = session(source) { Segmenter(::thresholdMask) }
        val firstLuma = canonicalLuma()

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        source.emit(nv21(firstLuma))
        awaitSnapshot(session) { it.processedFrameCount == 1L }
        source.emit(nv21(ByteArray(WIDTH * HEIGHT) { 30.toByte() }))
        val afterSecond = awaitSnapshot(session) { it.processedFrameCount == 2L }

        assertArrayEquals(
            firstLuma.map { (it.toInt() and 0xff) < 100 }.toBooleanArray(),
            requireNotNull(afterSecond.firstFrame).copyTapeMask(),
        )
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
    }

    @Test
    fun `stale callback snapshot after stop cannot enter the finalized generation`() {
        val source = FakeDecodedFrameStream()
        val session = session(source) { Segmenter(::thresholdMask) }
        val snapshotTaken = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        val callback = source.emitAfterSnapshotBarrier(
            nv21(canonicalLuma()),
            snapshotTaken,
            releaseSnapshot,
        )
        assertTrue(snapshotTaken.await(2, TimeUnit.SECONDS))
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertTrue(session.snapshot().finalMeasurement)

        releaseSnapshot.countDown()
        callback.join(2_000L)
        assertFalse(callback.isAlive)
        Thread.sleep(25L)
        val frozen = session.snapshot()
        assertEquals(0L, frozen.offeredFrameCount)
        assertEquals(0L, frozen.processedFrameCount)
        assertNull(frozen.firstFrame)
    }

    @Test
    fun `blocking source stop returns timeout and later call observes the same graph closing`() {
        val source = FakeDecodedFrameStream()
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        source.stopEntered = stopEntered
        source.releaseStop = releaseStop
        val session = session(source) { Segmenter(::thresholdMask) }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        assertEquals(OpenCvObservationStopResult.TIMED_OUT, session.stopWithin(30L))
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        assertEquals(OpenCvObservationLifecycleState.STOPPING, session.snapshot().lifecycleState)
        assertFalse(session.snapshot().finalMeasurement)
        assertTrue(
            session.start() is OpenCvObservationStartResult.Failure,
        )

        releaseStop.countDown()
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertTrue(session.snapshot().finalMeasurement)
        assertEquals(1, source.startCount.get())
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun `interrupted waiter does not corrupt cleanup evidence`() {
        val source = FakeDecodedFrameStream()
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        source.stopEntered = stopEntered
        source.releaseStop = releaseStop
        val session = session(source) { Segmenter(::thresholdMask) }
        val waiterResult = AtomicReference<OpenCvObservationStopResult>()
        val waiterRestoredInterrupt = AtomicReference(false)

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        val waiter = Thread {
            waiterResult.set(session.stopWithin(2_000L))
            waiterRestoredInterrupt.set(Thread.currentThread().isInterrupted)
        }.also(Thread::start)
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        waiter.interrupt()
        waiter.join(2_000L)

        assertEquals(OpenCvObservationStopResult.TIMED_OUT, waiterResult.get())
        assertTrue(waiterRestoredInterrupt.get())
        assertEquals(OpenCvObservationLifecycleState.STOPPING, session.snapshot().lifecycleState)
        assertNull(session.snapshot().failureReason)

        releaseStop.countDown()
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(OpenCvObservationLifecycleState.CLOSED, session.snapshot().lifecycleState)
        assertNull(session.snapshot().failureReason)
    }

    @Test
    fun `source induced cleanup interrupt is consumed and ownership still closes`() {
        val source = FakeDecodedFrameStream()
        source.interruptStopOnce = true
        val session = session(source) { Segmenter(::thresholdMask) }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))

        val frozen = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.CLOSED, frozen.lifecycleState)
        assertNull(frozen.failureReason)
        assertTrue(frozen.finalMeasurement)
        assertEquals(1L, frozen.cleanupAttemptFailureCount)
        assertTrue(frozen.bridgeStopTimeoutCount >= 1L)
        assertEquals(2, source.stopCount.get())
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun `wedged worker timeout remains visible after the same graph closes`() {
        val source = FakeDecodedFrameStream()
        val segmenterEntered = CountDownLatch(1)
        val releaseSegmenter = CountDownLatch(1)
        val session = session(source) {
            Segmenter { frame ->
                segmenterEntered.countDown()
                awaitUninterruptibly(releaseSegmenter)
                thresholdMask(frame)
            }
        }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        source.emit(nv21(canonicalLuma()))
        assertTrue(segmenterEntered.await(2, TimeUnit.SECONDS))
        assertEquals(OpenCvObservationStopResult.TIMED_OUT, session.stopWithin(80L))
        assertTrue(session.snapshot().bridgeStopTimeoutCount >= 1L)
        assertFalse(session.snapshot().finalMeasurement)

        releaseSegmenter.countDown()
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertTrue(session.snapshot().bridgeStopTimeoutCount >= 1L)
        assertTrue(session.snapshot().finalMeasurement)
    }

    @Test
    fun `late worker quiescence keeps ownership until source can close`() {
        val source = FakeDecodedFrameStream()
        val segmenterEntered = CountDownLatch(1)
        val releaseSegmenter = CountDownLatch(1)
        val session = session(source) {
            Segmenter { frame ->
                segmenterEntered.countDown()
                awaitUninterruptibly(releaseSegmenter)
                thresholdMask(frame)
            }
        }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        source.emit(nv21(canonicalLuma()))
        assertTrue(segmenterEntered.await(2, TimeUnit.SECONDS))
        assertEquals(OpenCvObservationStopResult.TIMED_OUT, session.stopWithin(160L))
        val waiting = awaitSnapshot(session) { it.bridgeStopTimeoutCount >= 2L }
        assertEquals(OpenCvObservationLifecycleState.STOPPING, waiting.lifecycleState)
        assertFalse(waiting.finalMeasurement)
        assertEquals(0, source.closeCount.get())

        releaseSegmenter.countDown()
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(1, source.closeCount.get())
        assertTrue(session.snapshot().finalMeasurement)
    }

    @Test
    fun `terminal stop during listener registration cannot reopen callback admission`() {
        val addEntered = CountDownLatch(1)
        val releaseAdd = CountDownLatch(1)
        val source = FakeDecodedFrameStream()
        source.addListenerEntered = addEntered
        source.releaseAddListener = releaseAdd
        source.payloadEmittedByStart = nv21(canonicalLuma())
        val session = session(source) { Segmenter(::thresholdMask) }
        val startResult = AtomicReference<OpenCvObservationStartResult>()
        val starter = Thread { startResult.set(session.start()) }.also(Thread::start)
        assertTrue(addEntered.await(2, TimeUnit.SECONDS))

        assertEquals(OpenCvObservationStopResult.TIMED_OUT, session.stopWithin(30L))
        releaseAdd.countDown()
        starter.join(2_000L)
        assertFalse(starter.isAlive)
        assertEquals(
            OpenCvObservationStartResult.Failure(OpenCvObservationFailureReason.CANCELLED),
            startResult.get(),
        )
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(0, source.startCount.get())
        assertEquals(0L, session.snapshot().offeredFrameCount)
        assertNull(session.snapshot().firstFrame)
        assertEquals(OpenCvObservationFailureReason.CANCELLED, session.snapshot().failureReason)
    }

    @Test
    fun `closed cleanup future cannot consume interrupt or reopen lifecycle`() {
        val source = FakeDecodedFrameStream()
        val cleanupExecutor = CompletingWindowExecutor()
        val session =
            HeadlessOpenCvObservationSession(
                streamFactory = DecodedFrameStreamFactory { source },
                nativeInitializer = OpenCvNativeInitializer { TEST_IDENTITY },
                segmenterFactory = { Segmenter(::thresholdMask) },
                bridgeStopAwaitMillis = 50L,
                cleanupExecutor = cleanupExecutor,
            )

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))

        val retryResult = AtomicReference<OpenCvObservationStopResult>()
        val retryFailure = AtomicReference<Throwable?>()
        val retryInterruptRestored = AtomicBoolean(false)
        val retry = Thread {
            Thread.currentThread().interrupt()
            runCatching { session.stopWithin(2_000L) }
                .onSuccess(retryResult::set)
                .onFailure(retryFailure::set)
            retryInterruptRestored.set(Thread.currentThread().isInterrupted)
        }.also(Thread::start)
        retry.join(2_000L)

        assertFalse(retry.isAlive)
        assertNull(retryFailure.get())
        assertEquals(OpenCvObservationStopResult.CLOSED, retryResult.get())
        assertTrue(retryInterruptRestored.get())
        assertEquals(OpenCvObservationLifecycleState.CLOSED, session.snapshot().lifecycleState)
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun `deterministic source stop failure terminates cleanup honestly`() {
        val source = FakeDecodedFrameStream()
        source.failStop = true
        val session = session(source) { Segmenter(::thresholdMask) }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        assertEquals(OpenCvObservationStopResult.FAILED, session.stopWithin(2_000L))
        val failed = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.FAILED, failed.lifecycleState)
        assertEquals(OpenCvObservationFailureReason.CLEANUP, failed.failureReason)
        assertEquals(2L, failed.cleanupAttemptFailureCount)
        assertFalse(failed.finalMeasurement)
        assertEquals(2, source.stopCount.get())
        assertEquals(0, source.closeCount.get())

        source.failStop = false
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(OpenCvObservationLifecycleState.CLOSED, session.snapshot().lifecycleState)
        assertNull(session.snapshot().failureReason)
        assertEquals(3, source.stopCount.get())
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun `deterministic source close failure terminates cleanup honestly`() {
        val source = FakeDecodedFrameStream()
        source.failClose = true
        val session = session(source) { Segmenter(::thresholdMask) }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        assertEquals(OpenCvObservationStopResult.FAILED, session.stopWithin(2_000L))
        val failed = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.FAILED, failed.lifecycleState)
        assertEquals(OpenCvObservationFailureReason.CLEANUP, failed.failureReason)
        assertEquals(2L, failed.cleanupAttemptFailureCount)
        assertFalse(failed.finalMeasurement)
        assertEquals(2, source.closeCount.get())

        source.failClose = false
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(OpenCvObservationLifecycleState.CLOSED, session.snapshot().lifecycleState)
        assertNull(session.snapshot().failureReason)
        assertEquals(3, source.closeCount.get())
    }

    @Test
    fun `source start failure closes ownership and produces no plausible result`() {
        val source = FakeDecodedFrameStream(
            startResult = DecodedFrameStartResult.Failure("synthetic unavailable"),
        )
        val session = session(source) { Segmenter(::thresholdMask) }

        assertEquals(
            OpenCvObservationStartResult.Failure(OpenCvObservationFailureReason.SOURCE_START),
            session.start(),
        )
        assertNull(session.snapshot().firstFrame)
        assertFalse(session.snapshot().finalMeasurement)
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun `cleanup retry preserves the primary source start failure`() {
        val source = FakeDecodedFrameStream(
            startResult = DecodedFrameStartResult.Failure("synthetic unavailable"),
        )
        source.failClose = true
        val session = session(source) { Segmenter(::thresholdMask) }

        assertEquals(
            OpenCvObservationStartResult.Failure(OpenCvObservationFailureReason.SOURCE_START),
            session.start(),
        )
        assertEquals(OpenCvObservationStopResult.FAILED, session.stopWithin(2_000L))
        val cleanupFailed = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.FAILED, cleanupFailed.lifecycleState)
        assertEquals(OpenCvObservationFailureReason.CLEANUP, cleanupFailed.failureReason)
        assertFalse(cleanupFailed.finalMeasurement)

        source.failClose = false
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        val closed = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.CLOSED, closed.lifecycleState)
        assertEquals(OpenCvObservationFailureReason.SOURCE_START, closed.failureReason)
        assertFalse(closed.finalMeasurement)
        assertEquals(3, source.closeCount.get())
    }

    @Test
    fun `native initialization failure never opens the decoded source`() {
        val openCount = AtomicInteger(0)
        val session = HeadlessOpenCvObservationSession(
            streamFactory = DecodedFrameStreamFactory {
                openCount.incrementAndGet()
                FakeDecodedFrameStream()
            },
            nativeInitializer = OpenCvNativeInitializer { error("synthetic native failure") },
            segmenterFactory = { Segmenter(::thresholdMask) },
            bridgeStopAwaitMillis = 50L,
        )

        assertEquals(
            OpenCvObservationStartResult.Failure(
                OpenCvObservationFailureReason.NATIVE_INITIALIZATION,
            ),
            session.start(),
        )
        assertEquals(0, openCount.get())
        assertNull(session.snapshot().runtimeIdentity)
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
    }

    @Test
    fun `stop during a blocked source open cannot finalize before startup settles`() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val source = FakeDecodedFrameStream()
        val startResult = AtomicReference<OpenCvObservationStartResult>()
        val session = HeadlessOpenCvObservationSession(
            streamFactory = DecodedFrameStreamFactory {
                openEntered.countDown()
                awaitUninterruptibly(releaseOpen)
                source
            },
            nativeInitializer = OpenCvNativeInitializer { TEST_IDENTITY },
            segmenterFactory = { Segmenter(::thresholdMask) },
            bridgeStopAwaitMillis = 50L,
        )
        val starter = Thread { startResult.set(session.start()) }.also(Thread::start)
        assertTrue(openEntered.await(2, TimeUnit.SECONDS))

        assertEquals(OpenCvObservationStopResult.TIMED_OUT, session.stopWithin(30L))
        assertFalse(session.snapshot().finalMeasurement)
        releaseOpen.countDown()
        starter.join(2_000L)
        assertFalse(starter.isAlive)
        assertEquals(
            OpenCvObservationStartResult.Failure(OpenCvObservationFailureReason.CANCELLED),
            startResult.get(),
        )
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertFalse(session.snapshot().finalMeasurement)
        assertEquals(OpenCvObservationLifecycleState.CLOSED, session.snapshot().lifecycleState)
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun `stop before blocked source open failure remains cancelled`() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val openCount = AtomicInteger(0)
        val startResult = AtomicReference<OpenCvObservationStartResult>()
        val session =
            HeadlessOpenCvObservationSession(
                streamFactory =
                    DecodedFrameStreamFactory {
                        openCount.incrementAndGet()
                        openEntered.countDown()
                        awaitUninterruptibly(releaseOpen)
                        error("synthetic source open failure")
                    },
                nativeInitializer = OpenCvNativeInitializer { TEST_IDENTITY },
                segmenterFactory = { Segmenter(::thresholdMask) },
                bridgeStopAwaitMillis = 50L,
            )
        val starter = Thread { startResult.set(session.start()) }.also(Thread::start)
        assertTrue(openEntered.await(2, TimeUnit.SECONDS))

        assertEquals(OpenCvObservationStopResult.TIMED_OUT, session.stopWithin(30L))
        releaseOpen.countDown()
        starter.join(2_000L)

        assertFalse(starter.isAlive)
        assertEquals(1, openCount.get())
        assertEquals(
            OpenCvObservationStartResult.Failure(OpenCvObservationFailureReason.CANCELLED),
            startResult.get(),
        )
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        val frozen = session.snapshot()
        assertEquals(OpenCvObservationLifecycleState.CLOSED, frozen.lifecycleState)
        assertEquals(OpenCvObservationFailureReason.CANCELLED, frozen.failureReason)
        assertFalse(frozen.finalMeasurement)
    }

    @Test
    fun `pipeline failure is counted without a fallback frame result`() {
        val source = FakeDecodedFrameStream()
        val entered = CountDownLatch(1)
        val session = session(source) {
            Segmenter {
                entered.countDown()
                error("synthetic segmentation failure")
            }
        }

        assertEquals(OpenCvObservationStartResult.Started, session.start())
        source.emit(nv21(canonicalLuma()))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val failed = awaitSnapshot(session) { it.pipelineFailureCount == 1L }
        assertNull(failed.firstFrame)
        assertEquals(OpenCvObservationStopResult.CLOSED, session.stopWithin(2_000L))
        assertEquals(1L, session.snapshot().pipelineFailureCount)
    }

    private fun session(
        source: FakeDecodedFrameStream,
        segmenterFactory: () -> Segmenter,
    ) = HeadlessOpenCvObservationSession(
        streamFactory = DecodedFrameStreamFactory { source },
        nativeInitializer = OpenCvNativeInitializer { TEST_IDENTITY },
        segmenterFactory = { segmenterFactory() },
        bridgeStopAwaitMillis = 50L,
    )

    private fun awaitSnapshot(
        session: HeadlessOpenCvObservationSession,
        predicate: (OpenCvObservationSnapshot) -> Boolean,
    ): OpenCvObservationSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (System.nanoTime() < deadline) {
            val snapshot = session.snapshot()
            if (predicate(snapshot)) return snapshot
            Thread.sleep(5L)
        }
        val last = session.snapshot()
        assertTrue("condition not reached; last snapshot=$last", predicate(last))
        return last
    }

    private class FakeDecodedFrameStream(
        private val startResult: DecodedFrameStartResult = DecodedFrameStartResult.Started,
    ) : DecodedFrameStream {
        val listeners = CopyOnWriteArrayList<DecodedFrameListener>()
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        @Volatile var stopEntered: CountDownLatch? = null
        @Volatile var releaseStop: CountDownLatch? = null
        @Volatile var addListenerEntered: CountDownLatch? = null
        @Volatile var releaseAddListener: CountDownLatch? = null
        @Volatile var payloadEmittedByStart: ByteArray? = null
        @Volatile var failStop = false
        @Volatile var failClose = false
        @Volatile var interruptStopOnce = false

        override fun start(format: DecodedFrameFormat): DecodedFrameStartResult {
            assertEquals(DecodedFrameFormat.NV21, format)
            startCount.incrementAndGet()
            payloadEmittedByStart?.let(::emit)
            return startResult
        }

        override fun stop() {
            stopCount.incrementAndGet()
            stopEntered?.countDown()
            releaseStop?.let(::awaitUninterruptibly)
            if (interruptStopOnce) {
                interruptStopOnce = false
                Thread.currentThread().interrupt()
            }
            if (failStop) error("synthetic deterministic stop failure")
        }

        override fun addListener(listener: DecodedFrameListener) {
            addListenerEntered?.countDown()
            releaseAddListener?.let(::awaitUninterruptibly)
            listeners += listener
        }

        override fun removeListener(listener: DecodedFrameListener) {
            listeners -= listener
        }

        override fun close() {
            closeCount.incrementAndGet()
            if (failClose) error("synthetic deterministic close failure")
        }

        fun emit(payload: ByteArray) {
            val observation = observation(payload)
            listeners.forEach { it.onDecodedFrame(observation, payload, 0, payload.size) }
        }

        fun emitAsync(payload: ByteArray): Thread = Thread { emit(payload) }.also(Thread::start)

        fun emitAfterSnapshotBarrier(
            payload: ByteArray,
            snapshotTaken: CountDownLatch,
            releaseSnapshot: CountDownLatch,
        ): Thread = Thread {
            val snapshot = listeners.toList()
            snapshotTaken.countDown()
            awaitUninterruptibly(releaseSnapshot)
            val observation = observation(payload)
            snapshot.forEach { it.onDecodedFrame(observation, payload, 0, payload.size) }
        }.also(Thread::start)

        private fun observation(payload: ByteArray) = DecodedFrameObservation(
            sequence = 1L,
            receivedAtEpochMs = 1L,
            receivedAtMonotonicNanos = System.nanoTime(),
            widthPixels = WIDTH,
            heightPixels = HEIGHT,
            format = DecodedFrameFormat.NV21,
            payloadSizeBytes = payload.size,
            callbackThreadName = Thread.currentThread().name,
        )
    }

    companion object {
        private const val WIDTH = 16
        private const val HEIGHT = 16
        private val TEST_IDENTITY = OpenCvRuntimeIdentity(
            version = "test-4.9.0",
            platform = "jvm-test",
            buildInformationSha256 = "a".repeat(64),
        )

        private fun canonicalLuma(fill: Int = 200): ByteArray =
            ByteArray(WIDTH * HEIGHT) { index ->
                val x = index % WIDTH
                if (x in 6..9) 40.toByte() else fill.toByte()
            }

        private fun nv21(luma: ByteArray): ByteArray =
            luma + ByteArray(WIDTH * HEIGHT / 2) { 128.toByte() }

        private fun thresholdMask(frame: LuminanceFrame): SegmentationResult {
            val tape = BooleanArray(frame.pixels.size) { index ->
                (frame.pixels[index].toInt() and 0xff) < 100
            }
            return SegmentationResult.accepted(SegmentationMask(frame.width, frame.height, tape))
        }

        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                    // Test fixture intentionally models a source method that ignores interruption.
                }
            }
        }
    }

    /**
     * Models FutureTask's narrow COMPLETING window: isDone is true, but an untimed get can still
     * observe and consume a caller interrupt. Timed get represents the subsequently completed
     * result returned to stopWithin.
     */
    private class CompletingWindowExecutor : AbstractExecutorService() {
        private val shutdown = AtomicBoolean(false)

        override fun shutdown() {
            shutdown.set(true)
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown.set(true)
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown.get()

        override fun isTerminated(): Boolean = shutdown.get()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown.get()

        override fun execute(command: Runnable) {
            check(!shutdown.get()) { "synthetic cleanup executor is already shut down" }
            command.run()
        }

        override fun <T : Any?> submit(task: Callable<T>): Future<T> {
            check(!shutdown.get()) { "synthetic cleanup executor is already shut down" }
            val result = task.call()
            return object : Future<T> {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

                override fun isCancelled(): Boolean = false

                override fun isDone(): Boolean = true

                override fun get(): T {
                    if (Thread.interrupted()) throw InterruptedException()
                    return result
                }

                override fun get(timeout: Long, unit: TimeUnit): T = result
            }
        }
    }
}
