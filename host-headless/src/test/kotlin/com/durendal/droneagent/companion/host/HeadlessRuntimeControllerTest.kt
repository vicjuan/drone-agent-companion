package com.durendal.droneagent.companion.host

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessRuntimeControllerTest {
    @Test
    fun `foreground release requires both a closed runtime and durable completion evidence`() {
        assertTrue(isSafeStopDurablyComplete(RuntimeCloseResult.CLOSED, true))
        assertFalse(isSafeStopDurablyComplete(RuntimeCloseResult.CLOSED, false))
        assertFalse(isSafeStopDurablyComplete(RuntimeCloseResult.TIMED_OUT, true))
        assertFalse(isSafeStopDurablyComplete(RuntimeCloseResult.FAILED, true))
    }

    @Test
    fun `starts once and closes before recording the close outcome`() {
        val order = mutableListOf<String>()
        val evidence = RecordingEvidence(order)
        val runtime =
            FakeRuntime(
                onStart = { order += "runtime-start" },
                onClose = {
                    order += "runtime-close"
                    RuntimeCloseResult.CLOSED
                },
            )
        val controller = HeadlessRuntimeController(HeadlessRuntimeFactory { runtime }, evidence)

        assertEquals(
            RuntimeStartResult.STARTED,
            controller.start(LifecycleTrigger.BOOT_COMPLETED),
        )
        assertEquals(
            RuntimeStartResult.ALREADY_STARTED,
            controller.start(LifecycleTrigger.BOOT_COMPLETED),
        )
        assertTrue(controller.isRunning())
        assertEquals(RuntimeCloseResult.CLOSED, controller.closeWithin(500L))
        assertFalse(controller.isRunning())
        assertEquals(1, runtime.startCalls)
        assertEquals(1, runtime.closeCalls)
        assertTrue(order.indexOf("runtime-close") < order.indexOf("evidence-runtime_closed"))
    }

    @Test
    fun `timeout remains observable and retains runtime for a best-effort retry`() {
        val runtime = FakeRuntime(onClose = { RuntimeCloseResult.TIMED_OUT })
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(mutableListOf()),
            )
        controller.start(LifecycleTrigger.EXPLICIT_START)

        assertEquals(RuntimeCloseResult.TIMED_OUT, controller.closeWithin(25L))
        assertTrue(controller.isRunning())
        assertEquals(RuntimeCloseResult.TIMED_OUT, controller.closeWithin(25L))
        assertEquals(2, runtime.closeCalls)
    }

    @Test
    fun `closed runtime is not reported closed until terminal evidence retry succeeds`() {
        val runtime = FakeRuntime(onClose = { RuntimeCloseResult.CLOSED })
        var rejectClosedEvidence = true
        val evidence =
            object : DurableLifecycleEvidence {
                override fun record(event: LifecycleEvent, trigger: LifecycleTrigger) {
                    if (event == LifecycleEvent.RUNTIME_CLOSED && rejectClosedEvidence) {
                        throw IOException("disk")
                    }
                }
            }
        val controller = HeadlessRuntimeController(HeadlessRuntimeFactory { runtime }, evidence)
        controller.start(LifecycleTrigger.EXPLICIT_START)

        assertEquals(RuntimeCloseResult.FAILED, controller.closeWithin(100L))
        assertFalse(controller.isRunning())
        assertTrue(controller.hasPendingCloseEvidence())
        assertEquals(1, runtime.closeCalls)

        assertEquals(RuntimeCloseResult.FAILED, controller.closeWithin(100L))
        assertEquals(1, runtime.closeCalls)
        rejectClosedEvidence = false
        assertEquals(RuntimeCloseResult.CLOSED, controller.closeWithin(100L))
        assertFalse(controller.hasPendingCloseEvidence())
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun `partial startup is closed and never retained`() {
        val runtime = FakeRuntime(onStart = { throw IllegalStateException("start failed") })
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(mutableListOf()),
            )

        assertEquals(
            RuntimeStartResult.FAILED,
            controller.start(LifecycleTrigger.EXPLICIT_START),
        )
        assertFalse(controller.isRunning())
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun `partial startup with timed out cleanup is retained for safe-stop retry`() {
        var firstClose = true
        val runtime =
            FakeRuntime(
                onStart = { throw IllegalStateException("partially started") },
                onClose = {
                    if (firstClose) {
                        firstClose = false
                        RuntimeCloseResult.TIMED_OUT
                    } else {
                        RuntimeCloseResult.CLOSED
                    }
                },
            )
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(mutableListOf()),
            )

        assertEquals(
            RuntimeStartResult.FAILED,
            controller.start(LifecycleTrigger.EXPLICIT_START),
        )
        assertTrue(controller.isRunning())
        assertEquals(RuntimeCloseResult.CLOSED, controller.closeWithin(100L))
        assertFalse(controller.isRunning())
        assertEquals(2, runtime.closeCalls)
    }

    private class RecordingEvidence(private val order: MutableList<String>) :
        DurableLifecycleEvidence {
        override fun record(event: LifecycleEvent, trigger: LifecycleTrigger) {
            order += "evidence-${event.wireName}"
        }
    }

    private class FakeRuntime(
        private val onStart: () -> Unit = {},
        private val onClose: () -> RuntimeCloseResult = { RuntimeCloseResult.CLOSED },
    ) : HeadlessRuntime {
        var startCalls = 0
        var closeCalls = 0

        override fun start() {
            startCalls++
            onStart()
        }

        override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
            closeCalls++
            return onClose()
        }
    }
}
