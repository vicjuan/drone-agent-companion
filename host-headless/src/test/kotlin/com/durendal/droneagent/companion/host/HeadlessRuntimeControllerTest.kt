package com.durendal.droneagent.companion.host

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
                onAdmitActuation = { order += "runtime-admit" },
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
        assertEquals(1, runtime.admitActuationCalls)
        assertEquals(1, runtime.closeCalls)
        assertTrue(
            order.indexOf("runtime-start") <
                order.indexOf("evidence-runtime_admission_committed"),
        )
        assertTrue(
            order.indexOf("evidence-runtime_admission_committed") <
                order.indexOf("runtime-admit"),
        )
        assertTrue(order.indexOf("runtime-admit") < order.indexOf("evidence-runtime_started"))
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

    @Test
    fun `safe stop during a slow factory prevents runtime start`() {
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val runtime = FakeRuntime()
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory {
                    factoryEntered.countDown()
                    assertTrue(releaseFactory.await(2, TimeUnit.SECONDS))
                    runtime
                },
                RecordingEvidence(mutableListOf()),
            )
        val result = AtomicReference<RuntimeStartResult>()
        val startThread =
            Thread {
                result.set(controller.start(LifecycleTrigger.EXPLICIT_START))
            }

        startThread.start()
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
        controller.requestStop()
        releaseFactory.countDown()
        startThread.join(2_000L)

        assertEquals(RuntimeStartResult.CANCELLED, result.get())
        assertEquals(0, runtime.startCalls)
        assertEquals(0, runtime.admitActuationCalls)
        assertEquals(1, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
    }

    @Test
    fun `safe stop during runtime initialization closes before startup can be published`() {
        val runtimeEntered = CountDownLatch(1)
        val releaseRuntime = CountDownLatch(1)
        val order = mutableListOf<String>()
        val commandAdmissionOpen = java.util.concurrent.atomic.AtomicBoolean(false)
        val runtime =
            FakeRuntime(
                onStart = {
                    commandAdmissionOpen.set(true)
                    runtimeEntered.countDown()
                    assertTrue(releaseRuntime.await(2, TimeUnit.SECONDS))
                },
                onRequestStop = { commandAdmissionOpen.set(false) },
            )
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(order),
            )
        val result = AtomicReference<RuntimeStartResult>()
        val startThread =
            Thread {
                result.set(controller.start(LifecycleTrigger.EXPLICIT_START))
            }

        startThread.start()
        assertTrue(runtimeEntered.await(2, TimeUnit.SECONDS))
        controller.requestStop()
        assertFalse(
            "safe-stop callback must close command admission before startup returns",
            commandAdmissionOpen.get(),
        )
        releaseRuntime.countDown()
        startThread.join(2_000L)

        assertEquals(RuntimeStartResult.CANCELLED, result.get())
        assertEquals(1, runtime.startCalls)
        assertEquals(0, runtime.admitActuationCalls)
        assertEquals(2, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("cancelled startup must not be recorded as running", "evidence-runtime_started" !in order)
        assertTrue("cancelled startup must be durable", "evidence-runtime_start_cancelled" in order)
    }

    @Test
    fun `safe stop callback remains nonthrowing when an injected runtime violates its contract`() {
        val runtime =
            FakeRuntime(
                onRequestStop = { throw IllegalStateException("blocking adapter failure") },
            )
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(mutableListOf()),
            )
        assertEquals(RuntimeStartResult.STARTED, controller.start(LifecycleTrigger.EXPLICIT_START))

        assertTrue(runCatching(controller::requestStop).isSuccess)
        assertEquals(RuntimeCloseResult.CLOSED, controller.closeWithin(100L))
        assertFalse(controller.isRunning())
        assertEquals(2, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun `durable admission commit precedes runtime admission`() {
        val order = mutableListOf<String>()
        val runtime = FakeRuntime(onAdmitActuation = { order += "runtime-admit" })
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(order),
            )

        assertEquals(RuntimeStartResult.STARTED, controller.start(LifecycleTrigger.EXPLICIT_START))

        assertTrue(
            order.indexOf("evidence-runtime_admission_committed") <
                order.indexOf("runtime-admit"),
        )
        assertTrue(order.indexOf("runtime-admit") < order.indexOf("evidence-runtime_started"))
    }

    @Test
    fun `failed admission commit closes without admitting or recording started`() {
        val order = mutableListOf<String>()
        var rejectCommit = true
        val evidence =
            object : DurableLifecycleEvidence {
                override fun record(event: LifecycleEvent, trigger: LifecycleTrigger) {
                    order += "evidence-${event.wireName}"
                    if (event == LifecycleEvent.RUNTIME_ADMISSION_COMMITTED && rejectCommit) {
                        rejectCommit = false
                        throw IOException("commit failed")
                    }
                }
            }
        val runtime = FakeRuntime()
        val controller = HeadlessRuntimeController(HeadlessRuntimeFactory { runtime }, evidence)

        assertEquals(RuntimeStartResult.FAILED, controller.start(LifecycleTrigger.EXPLICIT_START))

        assertEquals(0, runtime.admitActuationCalls)
        assertEquals(1, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_start_failed" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    @Test
    fun `admission failure closes after durable commit without recording started`() {
        val order = mutableListOf<String>()
        val runtime =
            FakeRuntime(
                onAdmitActuation = { throw IllegalStateException("admission failed") },
            )
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(order),
            )

        assertEquals(RuntimeStartResult.FAILED, controller.start(LifecycleTrigger.EXPLICIT_START))

        assertEquals(1, runtime.admitActuationCalls)
        assertEquals(1, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_admission_committed" in order)
        assertTrue("evidence-runtime_start_failed" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    @Test
    fun `safe stop racing durable admission commit prevents runtime admission`() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val order = mutableListOf<String>()
        val evidence =
            object : DurableLifecycleEvidence {
                override fun record(event: LifecycleEvent, trigger: LifecycleTrigger) {
                    order += "evidence-${event.wireName}"
                    if (event == LifecycleEvent.RUNTIME_ADMISSION_COMMITTED) {
                        commitEntered.countDown()
                        assertTrue(releaseCommit.await(2, TimeUnit.SECONDS))
                    }
                }
            }
        val runtime = FakeRuntime()
        val controller = HeadlessRuntimeController(HeadlessRuntimeFactory { runtime }, evidence)
        val result = AtomicReference<RuntimeStartResult>()
        val startThread =
            Thread {
                result.set(controller.start(LifecycleTrigger.EXPLICIT_START))
            }

        startThread.start()
        assertTrue(commitEntered.await(2, TimeUnit.SECONDS))
        controller.requestStop()
        releaseCommit.countDown()
        startThread.join(2_000L)

        assertEquals(RuntimeStartResult.CANCELLED, result.get())
        assertEquals(0, runtime.admitActuationCalls)
        assertEquals(2, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_start_cancelled" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    @Test
    fun `safe stop racing admission closes and never records started`() {
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val order = mutableListOf<String>()
        val runtime =
            FakeRuntime(
                onAdmitActuation = {
                    admissionEntered.countDown()
                    assertTrue(releaseAdmission.await(2, TimeUnit.SECONDS))
                },
            )
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(order),
            )
        val result = AtomicReference<RuntimeStartResult>()
        val startThread =
            Thread {
                result.set(controller.start(LifecycleTrigger.EXPLICIT_START))
            }

        startThread.start()
        assertTrue(admissionEntered.await(2, TimeUnit.SECONDS))
        controller.requestStop()
        releaseAdmission.countDown()
        startThread.join(2_000L)

        assertEquals(RuntimeStartResult.CANCELLED, result.get())
        assertEquals(1, runtime.admitActuationCalls)
        assertEquals(2, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_admission_committed" in order)
        assertTrue("evidence-runtime_start_cancelled" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    private class RecordingEvidence(private val order: MutableList<String>) :
        DurableLifecycleEvidence {
        override fun record(event: LifecycleEvent, trigger: LifecycleTrigger) {
            order += "evidence-${event.wireName}"
        }
    }

    private class FakeRuntime(
        private val onStart: () -> Unit = {},
        private val onAdmitActuation: () -> Unit = {},
        private val onRequestStop: () -> Unit = {},
        private val onClose: () -> RuntimeCloseResult = { RuntimeCloseResult.CLOSED },
    ) : HeadlessRuntime {
        var startCalls = 0
        var admitActuationCalls = 0
        var requestStopCalls = 0
        var closeCalls = 0

        override fun requestStop() {
            requestStopCalls++
            onRequestStop()
        }

        override fun start() {
            startCalls++
            onStart()
        }

        override fun admitActuation() {
            admitActuationCalls++
            onAdmitActuation()
        }

        override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
            closeCalls++
            return onClose()
        }
    }
}
