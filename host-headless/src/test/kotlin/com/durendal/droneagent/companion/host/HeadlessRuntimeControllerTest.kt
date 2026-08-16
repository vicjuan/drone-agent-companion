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
                onCompleteStartup = { order += "runtime-complete-startup" },
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
        assertEquals(1, runtime.completeStartupCalls)
        assertEquals(1, runtime.closeCalls)
        assertTrue(
            order.indexOf("runtime-start") <
                order.indexOf("evidence-runtime_admission_committed"),
        )
        assertTrue(
            order.indexOf("evidence-runtime_admission_committed") <
                order.indexOf("runtime-complete-startup"),
        )
        assertTrue(
            order.indexOf("runtime-complete-startup") < order.indexOf("evidence-runtime_started"),
        )
        assertTrue(order.indexOf("runtime-close") < order.indexOf("evidence-runtime_closed"))
    }

    @Test
    fun `controller startup cannot infer DJI authorization from connected readiness`() {
        val runtime = FakeDjiRuntime()
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(mutableListOf()),
            )

        assertEquals(RuntimeStartResult.STARTED, controller.start(LifecycleTrigger.BOOT_COMPLETED))

        assertTrue(runtime.listenersStarted)
        assertTrue(runtime.aircraftConnected)
        assertTrue(runtime.networkReady)
        assertTrue(runtime.telemetryStarted)
        assertEquals(1, runtime.completeStartupCalls)
        assertEquals(0, runtime.commissioningApiCalls)
        assertFalse(runtime.actuationAuthorized)
        assertEquals(RuntimeCloseResult.CLOSED, controller.closeWithin(100L))
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
        assertEquals(0, runtime.completeStartupCalls)
        assertEquals(1, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
    }

    @Test
    fun `safe stop during runtime initialization closes before startup can be published`() {
        val runtimeEntered = CountDownLatch(1)
        val releaseRuntime = CountDownLatch(1)
        val order = mutableListOf<String>()
        val startupPublicationVisible = java.util.concurrent.atomic.AtomicBoolean(false)
        val runtime =
            FakeRuntime(
                onStart = {
                    startupPublicationVisible.set(true)
                    runtimeEntered.countDown()
                    assertTrue(releaseRuntime.await(2, TimeUnit.SECONDS))
                },
                onRequestStop = { startupPublicationVisible.set(false) },
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
            "safe-stop callback must revoke partial startup publication before start returns",
            startupPublicationVisible.get(),
        )
        releaseRuntime.countDown()
        startThread.join(2_000L)

        assertEquals(RuntimeStartResult.CANCELLED, result.get())
        assertEquals(1, runtime.startCalls)
        assertEquals(0, runtime.completeStartupCalls)
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
    fun `durable startup commit precedes runtime completion`() {
        val order = mutableListOf<String>()
        val runtime = FakeRuntime(onCompleteStartup = { order += "runtime-complete-startup" })
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(order),
            )

        assertEquals(RuntimeStartResult.STARTED, controller.start(LifecycleTrigger.EXPLICIT_START))

        assertTrue(
            order.indexOf("evidence-runtime_admission_committed") <
                order.indexOf("runtime-complete-startup"),
        )
        assertTrue(
            order.indexOf("runtime-complete-startup") < order.indexOf("evidence-runtime_started"),
        )
    }

    @Test
    fun `failed startup commit closes without completing or recording started`() {
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

        assertEquals(0, runtime.completeStartupCalls)
        assertEquals(1, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_start_failed" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    @Test
    fun `startup completion failure closes after durable commit without recording started`() {
        val order = mutableListOf<String>()
        val runtime =
            FakeRuntime(
                onCompleteStartup = { throw IllegalStateException("startup completion failed") },
            )
        val controller =
            HeadlessRuntimeController(
                HeadlessRuntimeFactory { runtime },
                RecordingEvidence(order),
            )

        assertEquals(RuntimeStartResult.FAILED, controller.start(LifecycleTrigger.EXPLICIT_START))

        assertEquals(1, runtime.completeStartupCalls)
        assertEquals(1, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_admission_committed" in order)
        assertTrue("evidence-runtime_start_failed" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    @Test
    fun `safe stop racing durable startup commit prevents runtime completion`() {
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
        assertEquals(0, runtime.completeStartupCalls)
        assertEquals(2, runtime.requestStopCalls)
        assertEquals(1, runtime.closeCalls)
        assertFalse(controller.isRunning())
        assertTrue("evidence-runtime_start_cancelled" in order)
        assertTrue("evidence-runtime_started" !in order)
    }

    @Test
    fun `safe stop racing startup completion closes and never records started`() {
        val completionEntered = CountDownLatch(1)
        val releaseCompletion = CountDownLatch(1)
        val order = mutableListOf<String>()
        val runtime =
            FakeRuntime(
                onCompleteStartup = {
                    completionEntered.countDown()
                    assertTrue(releaseCompletion.await(2, TimeUnit.SECONDS))
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
        assertTrue(completionEntered.await(2, TimeUnit.SECONDS))
        controller.requestStop()
        releaseCompletion.countDown()
        startThread.join(2_000L)

        assertEquals(RuntimeStartResult.CANCELLED, result.get())
        assertEquals(1, runtime.completeStartupCalls)
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
        private val onCompleteStartup: () -> Unit = {},
        private val onRequestStop: () -> Unit = {},
        private val onClose: () -> RuntimeCloseResult = { RuntimeCloseResult.CLOSED },
    ) : HeadlessRuntime {
        var startCalls = 0
        var completeStartupCalls = 0
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

        override fun completeStartup() {
            completeStartupCalls++
            onCompleteStartup()
        }

        override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult {
            closeCalls++
            return onClose()
        }
    }

    private class FakeDjiRuntime : HeadlessRuntime {
        var listenersStarted = false
        var aircraftConnected = false
        var networkReady = false
        var telemetryStarted = false
        var completeStartupCalls = 0
        var commissioningApiCalls = 0
        var actuationAuthorized = false

        override fun requestStop() = Unit

        override fun start() {
            listenersStarted = true
            aircraftConnected = true
            networkReady = true
        }

        override fun completeStartup() {
            completeStartupCalls++
            telemetryStarted = true
        }

        /** Intentionally outside [HeadlessRuntime]; only an explicit commissioning owner may call it. */
        @Suppress("unused")
        fun authorizeCommissioningCapability() {
            commissioningApiCalls++
            actuationAuthorized = true
        }

        override fun closeWithin(timeoutMillis: Long): RuntimeCloseResult = RuntimeCloseResult.CLOSED
    }
}
