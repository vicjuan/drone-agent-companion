package com.durendal.droneagent.companion.console.server

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleServerCoreTest {
    @Test
    fun `lease uses monotonic time rejects a second client and expires to neutral`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a", "browser-a")
        fixture.core.openSession("session-b", "browser-b")

        val held = fixture.core.acquireLease("session-a", 1_000L)
        fixture.clock.epochMillis += TimeUnit.DAYS.toMillis(30)
        val denied = fixture.core.acquireLease("session-b", 1_000L)

        assertEquals(ConsoleLeaseStatus.HELD, held.status)
        assertEquals(ConsoleLeaseStatus.DENIED, denied.status)
        assertEquals("lease_held", denied.reason)
        assertEquals(1_000L, fixture.core.currentLease()?.expiresInMillis)

        fixture.clock.advanceMillis(1_001L)
        fixture.scheduler.runDue()

        assertNull(fixture.core.currentLease())
        assertEquals(listOf(ConsoleSafetyTrigger.LEASE_EXPIRED), fixture.executor.neutralCalls.map { it.second })
        assertTrue(fixture.audit.events.any { it.kind == ConsoleAuditKind.LEASE_REFUSED })
        assertTrue(fixture.audit.events.any { it.kind == ConsoleAuditKind.LEASE_EXPIRED })
    }

    @Test
    fun `all discrete actions cross authority audit and companion executor boundaries`() {
        ConsoleDiscreteAction.entries.forEach { action ->
            val fixture = Fixture()
            fixture.core.openSession("session-a")
            val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
            val ack =
                fixture.core.handleDiscreteCommand(
                    "session-a",
                    ConsoleDiscreteCommand("command-1", lease, action, 500L),
                )
            assertEquals(ConsoleCommandDecision.ACCEPTED, ack.decision)
            assertEquals(listOf(action), fixture.executor.discrete.map { it.command.action })
            assertTrue(fixture.executor.discrete.single().authorityDecisionId.startsWith("authority-"))
            assertEquals(1, fixture.audit.events.count { it.kind == ConsoleAuditKind.COMMAND_ADMITTED })
            assertEquals(1, fixture.audit.events.count { it.kind == ConsoleAuditKind.COMMAND_COMPLETED })
        }
    }

    @Test
    fun `same command replay is idempotent and changed full intent conflicts`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        val original = ConsoleDiscreteCommand("command-1", lease, ConsoleDiscreteAction.TAKEOFF, 500L)

        val first = fixture.core.handleDiscreteCommand("session-a", original)
        val replay = fixture.core.handleDiscreteCommand("session-a", original)
        val conflict =
            fixture.core.handleDiscreteCommand(
                "session-a",
                original.copy(action = ConsoleDiscreteAction.LANDING),
            )

        assertEquals(ConsoleCommandDecision.ACCEPTED, first.decision)
        assertEquals(first, replay)
        assertEquals(ConsoleCommandDecision.REJECTED, conflict.decision)
        assertEquals("command_id_conflict", conflict.reason)
        assertEquals(1, fixture.executor.discrete.size)
    }

    @Test
    fun `audit persistence failure prevents command and control side effects`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.audit.failKinds += ConsoleAuditKind.COMMAND_ADMITTED

        val command =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("command-1", lease, ConsoleDiscreteAction.TAKEOFF, 500L),
            )

        fixture.audit.failKinds.clear()
        fixture.audit.failKinds += ConsoleAuditKind.CONTROL_ADMITTED
        val control = fixture.core.handleControlFrame("session-a", frame(lease, 1L))

        assertEquals("audit_unavailable", command.reason)
        assertEquals("audit_unavailable", control.reason)
        assertTrue(fixture.executor.discrete.isEmpty())
        assertTrue(fixture.executor.controls.isEmpty())
    }

    @Test
    fun `completion audit failure never presents an evidenced success and revokes actuation`() {
        val commandFixture = Fixture()
        commandFixture.core.openSession("session-a")
        val commandLease = checkNotNull(commandFixture.core.acquireLease("session-a", 5_000L).leaseId)
        commandFixture.audit.failKinds += ConsoleAuditKind.COMMAND_COMPLETED

        commandFixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("command-audit-fail", commandLease, ConsoleDiscreteAction.TAKEOFF, 500L),
        )
        val commandResult =
            commandFixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .single().result
        assertEquals(ConsoleCommandOutcome.FAILED, commandResult.outcome)
        assertEquals("audit_unavailable", commandResult.reason)
        assertNull(commandFixture.core.currentLease())
        assertEquals(2, commandFixture.executor.neutralCalls.size)

        val controlFixture = Fixture()
        controlFixture.core.openSession("session-a")
        val controlLease = checkNotNull(controlFixture.core.acquireLease("session-a", 5_000L).leaseId)
        controlFixture.audit.failKinds += ConsoleAuditKind.CONTROL_COMPLETED

        val control = controlFixture.core.handleControlFrame("session-a", frame(controlLease, 1L))
        assertEquals(ConsoleControlStatus.REJECTED, control.status)
        assertEquals("audit_unavailable", control.reason)
        assertNull(controlFixture.core.currentLease())
        assertEquals(1, controlFixture.executor.neutralCalls.size)
    }

    @Test
    fun `control sequence ttl and normalized axes fail closed`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

        fixture.core.handleControlFrame("session-a", frame(lease, 2L))
        val duplicate = fixture.core.handleControlFrame("session-a", frame(lease, 2L))
        val outOfOrder = fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        val shortTtl = fixture.core.handleControlFrame("session-a", frame(lease, 3L).copy(ttlMillis = 49L))
        val nonFinite = fixture.core.handleControlFrame("session-a", frame(lease, 3L).copy(forward = Double.NaN))
        val outsideRange = fixture.core.handleControlFrame("session-a", frame(lease, 3L).copy(yaw = 1.01))

        assertEquals(ConsoleControlStatus.STALE, duplicate.status)
        assertEquals(ConsoleControlStatus.STALE, outOfOrder.status)
        assertEquals("invalid_control_ttl", shortTtl.reason)
        assertEquals("invalid_control_axes", nonFinite.reason)
        assertEquals("invalid_control_axes", outsideRange.reason)
        assertEquals(1, fixture.executor.controls.size)
    }

    @Test
    fun `new frame fences a stale watchdog callback and input stop neutralizes once`() {
        val fixture = Fixture(config = ConsoleServerCoreConfig(deadManTimeoutMillis = 100L))
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

        fixture.core.handleControlFrame("session-a", frame(lease, 1L, ttlMillis = 200L))
        val firstWatchdog = fixture.scheduler.tasks.last()
        fixture.clock.advanceMillis(40L)
        fixture.core.handleControlFrame("session-a", frame(lease, 2L, ttlMillis = 200L))

        fixture.scheduler.forceRun(firstWatchdog)
        assertTrue(fixture.executor.neutralCalls.isEmpty())

        fixture.clock.advanceMillis(100L)
        fixture.scheduler.runDue()
        fixture.scheduler.runDue()

        assertEquals(1, fixture.executor.neutralCalls.size)
        assertEquals(ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED, fixture.executor.neutralCalls.single().second)
    }

    @Test
    fun `control that expires while its watchdog is being registered never reaches executor`() {
        val fixture = Fixture(config = ConsoleServerCoreConfig(deadManTimeoutMillis = 50L))
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.scheduler.afterNextSchedule = { fixture.clock.advanceMillis(51L) }

        val ack = fixture.core.handleControlFrame("session-a", frame(lease, 1L, ttlMillis = 100L))

        assertEquals(ConsoleControlStatus.REJECTED, ack.status)
        assertEquals("control_ttl_expired", ack.reason)
        assertTrue(fixture.executor.controls.isEmpty())
        assertEquals(1, fixture.executor.neutralCalls.size)
    }

    @Test
    fun `control deadline samples monotonic time only after entering the dispatch gate`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        fixture.executor.beforeSubmitControl = {
            dispatchEntered.countDown()
            check(releaseDispatch.await(2L, TimeUnit.SECONDS)) { "timed out holding dispatch gate" }
        }
        val pool = Executors.newFixedThreadPool(2)
        val control =
            pool.submit(Callable { fixture.core.handleControlFrame("session-a", frame(lease, 1L)) })
        assertTrue(dispatchEntered.await(1L, TimeUnit.SECONDS))
        val controlDeadline = fixture.scheduler.tasks.last()
        val deadlineThread = AtomicReference<Thread>()
        val deadlineStarted = CountDownLatch(1)
        val deadline =
            pool.submit(
                Callable {
                    deadlineThread.set(Thread.currentThread())
                    deadlineStarted.countDown()
                    fixture.scheduler.forceRun(controlDeadline)
                },
            )
        assertTrue(deadlineStarted.await(1L, TimeUnit.SECONDS))
        awaitBlocked(checkNotNull(deadlineThread.get()))

        fixture.clock.advanceMillis(101L)
        releaseDispatch.countDown()
        control.get(1L, TimeUnit.SECONDS)
        deadline.get(1L, TimeUnit.SECONDS)

        assertEquals(
            listOf(ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED),
            fixture.executor.neutralCalls.map { it.second },
        )
        pool.shutdownNow()
    }

    @Test
    fun `lease deadline samples monotonic time only after entering the dispatch gate`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 500L).leaseId)
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        fixture.executor.beforeNeutralize = {
            dispatchEntered.countDown()
            check(releaseDispatch.await(2L, TimeUnit.SECONDS)) { "timed out holding dispatch gate" }
        }
        val pool = Executors.newFixedThreadPool(2)
        val explicitNeutral =
            pool.submit(
                Callable {
                    fixture.core.handleControlNeutral(
                        "session-a",
                        ConsoleControlNeutral(
                            lease,
                            1L,
                            ConsoleNeutralRequestReason.OPERATOR_RELEASE,
                        ),
                    )
                },
            )
        assertTrue(dispatchEntered.await(1L, TimeUnit.SECONDS))
        val leaseDeadline = fixture.scheduler.tasks.first()
        val deadlineThread = AtomicReference<Thread>()
        val deadlineStarted = CountDownLatch(1)
        val deadline =
            pool.submit(
                Callable {
                    deadlineThread.set(Thread.currentThread())
                    deadlineStarted.countDown()
                    fixture.scheduler.forceRun(leaseDeadline)
                },
            )
        assertTrue(deadlineStarted.await(1L, TimeUnit.SECONDS))
        awaitBlocked(checkNotNull(deadlineThread.get()))

        fixture.clock.advanceMillis(501L)
        releaseDispatch.countDown()
        explicitNeutral.get(1L, TimeUnit.SECONDS)
        deadline.get(1L, TimeUnit.SECONDS)

        assertTrue(fixture.audit.events.any { it.kind == ConsoleAuditKind.LEASE_EXPIRED })
        assertTrue(
            fixture.events.events.any {
                (it.second as? ConsoleCoreEvent.LeaseChanged)?.state?.status ==
                    ConsoleLeaseStatus.EXPIRED
            },
        )
        pool.shutdownNow()
    }

    @Test
    fun `synchronous due-work check samples time only after entering the dispatch gate`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 500L).leaseId)
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        fixture.executor.beforeNeutralize = {
            dispatchEntered.countDown()
            check(releaseDispatch.await(2L, TimeUnit.SECONDS)) { "timed out holding dispatch gate" }
        }
        val pool = Executors.newFixedThreadPool(2)
        val explicitNeutral =
            pool.submit(
                Callable {
                    fixture.core.handleControlNeutral(
                        "session-a",
                        ConsoleControlNeutral(
                            lease,
                            1L,
                            ConsoleNeutralRequestReason.OPERATOR_RELEASE,
                        ),
                    )
                },
            )
        assertTrue(dispatchEntered.await(1L, TimeUnit.SECONDS))
        val currentLeaseThread = AtomicReference<Thread>()
        val currentLeaseStarted = CountDownLatch(1)
        val current =
            pool.submit(
                Callable {
                    currentLeaseThread.set(Thread.currentThread())
                    currentLeaseStarted.countDown()
                    fixture.core.currentLease()
                },
            )
        assertTrue(currentLeaseStarted.await(1L, TimeUnit.SECONDS))
        awaitBlocked(checkNotNull(currentLeaseThread.get()))

        fixture.clock.advanceMillis(501L)
        releaseDispatch.countDown()
        explicitNeutral.get(1L, TimeUnit.SECONDS)
        assertNull(current.get(1L, TimeUnit.SECONDS))
        assertTrue(fixture.audit.events.any { it.kind == ConsoleAuditKind.LEASE_EXPIRED })
        pool.shutdownNow()
    }

    @Test
    fun `scheduler failures revoke lease and neutralize before any control side effect`() {
        val leaseFailure = Fixture()
        leaseFailure.core.openSession("session-a")
        leaseFailure.scheduler.throwOnNextSchedule = true

        val deniedLease = leaseFailure.core.acquireLease("session-a", 5_000L)

        assertEquals(ConsoleLeaseStatus.DENIED, deniedLease.status)
        assertEquals("lease_deadline_unavailable", deniedLease.reason)
        assertNull(leaseFailure.core.currentLease())
        assertEquals(1, leaseFailure.executor.neutralCalls.size)

        val recoveredLease =
            checkNotNull(leaseFailure.core.acquireLease("session-a", 5_000L).leaseId)
        leaseFailure.scheduler.throwOnNextSchedule = true
        val deniedRenewal =
            leaseFailure.core.renewLease("session-a", recoveredLease, 5_000L)
        assertEquals("lease_deadline_unavailable", deniedRenewal.reason)
        assertNull(leaseFailure.core.currentLease())
        assertEquals(2, leaseFailure.executor.neutralCalls.size)

        val controlFailure = Fixture()
        controlFailure.core.openSession("session-a")
        val lease = checkNotNull(controlFailure.core.acquireLease("session-a", 5_000L).leaseId)
        controlFailure.scheduler.throwOnNextSchedule = true

        val ack = controlFailure.core.handleControlFrame("session-a", frame(lease, 1L))

        assertEquals("control_deadline_unavailable", ack.reason)
        assertTrue(controlFailure.executor.controls.isEmpty())
        assertEquals(1, controlFailure.executor.neutralCalls.size)
    }

    @Test
    fun `lease id factory failure clears reservation for the next client`() {
        val calls = AtomicInteger()
        val fixture =
            Fixture(
                leaseIdFactoryOverride = {
                    if (calls.incrementAndGet() == 1) "invalid lease id" else "lease-recovered"
                },
            )
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")

        val first = fixture.core.acquireLease("session-a", 5_000L)
        val second = fixture.core.acquireLease("session-b", 5_000L)

        assertEquals("lease_id_unavailable", first.reason)
        assertEquals(ConsoleLeaseStatus.HELD, second.status)
        assertEquals("lease-recovered", second.leaseId)
    }

    @Test
    fun `lease TTL starts at durable commit and expiry can never overtake HELD publication`() {
        val blocked = Fixture()
        blocked.core.openSession("session-a")
        blocked.audit.blockKind = ConsoleAuditKind.LEASE_ACQUIRED
        val pool = Executors.newSingleThreadExecutor()
        val acquire = pool.submit(Callable { blocked.core.acquireLease("session-a", 1_000L) })
        assertTrue(blocked.audit.blockEntered.await(1L, TimeUnit.SECONDS))
        blocked.clock.advanceMillis(5_000L)
        blocked.audit.unblock.countDown()
        val held = acquire.get(1L, TimeUnit.SECONDS)

        assertEquals(ConsoleLeaseStatus.HELD, held.status)
        assertEquals(1_000L, held.expiresInMillis)
        assertEquals(1_000L, blocked.core.currentLease()?.expiresInMillis)
        pool.shutdownNow()

        val immediateExpiry = Fixture()
        immediateExpiry.core.openSession("session-a")
        immediateExpiry.scheduler.afterNextSchedule = {
            immediateExpiry.clock.advanceMillis(1_001L)
            immediateExpiry.scheduler.runDue()
        }
        immediateExpiry.core.acquireLease("session-a", 1_000L)
        val states =
            immediateExpiry.events.events.mapNotNull {
                (it.second as? ConsoleCoreEvent.LeaseChanged)?.state?.status
            }
        assertTrue(states.indexOf(ConsoleLeaseStatus.HELD) < states.indexOf(ConsoleLeaseStatus.EXPIRED))
        assertNull(immediateExpiry.core.currentLease())
    }

    @Test
    fun `renew cannot revive a lease which expires while durable audit is blocked`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 500L).leaseId)
        fixture.audit.blockKind = ConsoleAuditKind.LEASE_RENEWED
        val pool = Executors.newSingleThreadExecutor()
        val renewal =
            pool.submit(
                Callable { fixture.core.renewLease("session-a", lease, 5_000L) },
            )
        assertTrue(fixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))

        // Model a delayed deadline worker: monotonic authority is already dead, but its scheduled
        // callback has not run yet when the durable renewal reservation returns.
        fixture.clock.advanceMillis(501L)
        fixture.audit.unblock.countDown()
        val denied = renewal.get(1L, TimeUnit.SECONDS)

        assertEquals(ConsoleLeaseStatus.DENIED, denied.status)
        assertEquals("lease_ttl_expired", denied.reason)
        assertNull(fixture.core.currentLease())
        assertEquals(
            listOf(ConsoleSafetyTrigger.LEASE_EXPIRED),
            fixture.executor.neutralCalls.map { it.second },
        )
        assertTrue(fixture.audit.events.any { it.kind == ConsoleAuditKind.LEASE_EXPIRED })
        pool.shutdownNow()
    }

    @Test
    fun `renew audit delay expiry terminates an already dispatched takeoff`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 500L).leaseId)
        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("takeoff-during-renew", lease, ConsoleDiscreteAction.TAKEOFF, 5_000L),
        )
        assertEquals(1, fixture.executor.discrete.size)

        fixture.audit.blockKind = ConsoleAuditKind.LEASE_RENEWED
        val pool = Executors.newSingleThreadExecutor()
        val renewal =
            pool.submit(
                Callable { fixture.core.renewLease("session-a", lease, 5_000L) },
            )
        assertTrue(fixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
        fixture.clock.advanceMillis(501L)
        fixture.audit.unblock.countDown()

        val denied = renewal.get(1L, TimeUnit.SECONDS)
        assertEquals(ConsoleLeaseStatus.DENIED, denied.status)
        assertEquals("lease_ttl_expired", denied.reason)
        assertEquals(2, fixture.executor.neutralCalls.size)
        fixture.executor.completeNextDiscrete(ConsoleExecutionResult(true, detail = "late success"))

        val completions =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
        assertEquals(1, completions.size)
        assertEquals(ConsoleCommandOutcome.FAILED, completions.single().result.outcome)
        assertEquals("lease_ttl_expired", completions.single().result.reason)
        assertEquals(1, fixture.audit.events.count { it.kind == ConsoleAuditKind.COMMAND_COMPLETED })
        assertNull(fixture.core.currentLease())
        pool.shutdownNow()
    }

    @Test
    fun `command deadline times out and suppresses a late executor callback`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("command-1", lease, ConsoleDiscreteAction.TAKEOFF, 500L),
        )
        fixture.clock.advanceMillis(501L)
        fixture.scheduler.runDue()
        fixture.executor.completeNextDiscrete(ConsoleExecutionResult(true, detail = "late success"))

        val completions =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
        assertEquals(1, completions.size)
        assertEquals(ConsoleCommandOutcome.TIMED_OUT, completions.single().result.outcome)
        assertEquals("command_ttl_expired", completions.single().result.reason)
        assertEquals(1, fixture.audit.events.count { it.kind == ConsoleAuditKind.COMMAND_COMPLETED })
        assertEquals(2, fixture.executor.neutralCalls.size)
        assertNull(fixture.core.currentLease())
        assertEquals("actuation_locked", fixture.core.handleControlFrame("session-a", frame(lease, 1L)).reason)
    }

    @Test
    fun `discrete executor failure invokes fresh neutral before terminal audit can block`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("executor-failed", lease, ConsoleDiscreteAction.TAKEOFF, 1_000L),
        )
        assertEquals(1, fixture.executor.neutralCalls.size)
        fixture.executor.completeNeutralImmediately = false
        fixture.audit.blockKind = ConsoleAuditKind.COMMAND_COMPLETED
        val pool = Executors.newSingleThreadExecutor()
        val completion =
            pool.submit(
                Callable {
                    fixture.executor.completeNextDiscrete(
                        ConsoleExecutionResult(false, "executor_rejected", "action rejected"),
                    )
                },
            )
        assertTrue(fixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))

        assertEquals(2, fixture.executor.neutralCalls.size)
        assertEquals(
            ConsoleSafetyTrigger.CONTROL_TTL_EXPIRED,
            fixture.executor.neutralCalls.last().second,
        )
        fixture.audit.unblock.countDown()
        completion.get(1L, TimeUnit.SECONDS)
        val whileNeutralPending = fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        assertEquals(ConsoleControlStatus.REJECTED, whileNeutralPending.status)
        assertEquals("neutral_in_progress", whileNeutralPending.reason)
        fixture.executor.completeNextNeutral(
            ConsoleExecutionResult(false, "neutral_failed", "post-action neutral failed"),
        )
        val afterNeutralFailure = fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        assertEquals("actuation_locked", afterNeutralFailure.reason)
        val result =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .single().result
        assertEquals(ConsoleCommandOutcome.FAILED, result.outcome)
        assertEquals("executor_rejected", result.reason)
        assertEquals(
            2,
            fixture.audit.events.count { it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED },
        )
        pool.shutdownNow()
    }

    @Test
    fun `command scheduler failure neutralizes and never reaches the action executor`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.scheduler.throwOnNextSchedule = true

        val ack =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("command-1", lease, ConsoleDiscreteAction.TAKEOFF, 500L),
            )
        val result =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .single().result

        assertEquals(ConsoleCommandDecision.ACCEPTED, ack.decision)
        assertEquals("command_deadline_unavailable", result.reason)
        assertTrue(fixture.executor.discrete.isEmpty())
        assertEquals(1, fixture.executor.neutralCalls.size)
    }

    @Test
    fun `command ledger retains current lease receipts and recovers capacity on a new lease`() {
        val fixture = Fixture(config = ConsoleServerCoreConfig(maximumCommandRecords = 2))
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fun submit(id: String) =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand(id, lease, ConsoleDiscreteAction.TAKEOFF, 500L),
            )

        assertEquals(ConsoleCommandDecision.ACCEPTED, submit("command-1").decision)
        assertEquals(ConsoleCommandDecision.ACCEPTED, submit("command-2").decision)
        val refused = submit("command-3")
        val replay = submit("command-1")

        assertEquals("command_inventory_full", refused.reason)
        assertEquals(ConsoleCommandDecision.ACCEPTED, replay.decision)
        assertEquals(2, fixture.executor.discrete.size)

        fixture.core.releaseLease("session-a", lease)
        val nextLease = checkNotNull(fixture.core.acquireLease("session-b", 5_000L).leaseId)
        val recovered =
            fixture.core.handleDiscreteCommand(
                "session-b",
                ConsoleDiscreteCommand(
                    "command-3",
                    nextLease,
                    ConsoleDiscreteAction.TAKEOFF,
                    500L,
                ),
            )

        assertEquals(ConsoleCommandDecision.ACCEPTED, recovered.decision)
        assertEquals(3, fixture.executor.discrete.size)
    }

    @Test
    fun `landing receipt replays after its lease was released without a second action`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        val command =
            ConsoleDiscreteCommand("command-land", lease, ConsoleDiscreteAction.LANDING, 500L)

        val first = fixture.core.handleDiscreteCommand("session-a", command)
        val replay = fixture.core.handleDiscreteCommand("session-a", command)

        assertEquals(ConsoleCommandDecision.ACCEPTED, first.decision)
        assertEquals(first, replay)
        assertEquals(1, fixture.executor.discrete.size)
        assertTrue(
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .count { it.result.commandId == command.commandId } >= 2,
        )
    }

    @Test
    fun `discrete action gate serializes actions and excludes continuous control`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

        val first =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("command-1", lease, ConsoleDiscreteAction.TAKEOFF, 500L),
            )
        val second =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("command-2", lease, ConsoleDiscreteAction.LANDING, 500L),
            )
        val control = fixture.core.handleControlFrame("session-a", frame(lease, 1L))

        assertEquals(ConsoleCommandDecision.ACCEPTED, first.decision)
        assertEquals("discrete_action_in_progress", second.reason)
        assertEquals("discrete_action_in_progress", control.reason)
        assertEquals(1, fixture.executor.discrete.size)

        fixture.executor.completeNextDiscrete(ConsoleExecutionResult(true, detail = "takeoff complete"))
        val after = fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        assertEquals(ConsoleControlStatus.APPLIED, after.status)
    }

    @Test
    fun `landing holds arbitration until its asynchronous action completes`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("command-land", lease, ConsoleDiscreteAction.LANDING, 500L),
        )
        val whileLanding = fixture.core.acquireLease("session-b", 5_000L)

        assertEquals(ConsoleLeaseStatus.DENIED, whileLanding.status)
        assertTrue(whileLanding.reason in setOf("discrete_action_in_progress", "neutral_in_progress"))

        fixture.executor.completeNextDiscrete(ConsoleExecutionResult(true, detail = "landing complete"))
        val afterLanding = fixture.core.acquireLease("session-b", 5_000L)
        assertEquals(ConsoleLeaseStatus.HELD, afterLanding.status)
    }

    @Test
    fun `latest control failure neutralizes immediately and returns the failure`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        fixture.executor.controlResult = ConsoleExecutionResult(false, "adapter_rejected", null)

        val failed = fixture.core.handleControlFrame("session-a", frame(lease, 2L))

        assertEquals(ConsoleControlStatus.REJECTED, failed.status)
        assertEquals("adapter_rejected", failed.reason)
        assertEquals(1, fixture.executor.neutralCalls.size)
        assertTrue(fixture.executor.neutralEpochs.single() >= fixture.executor.controls.first().controlEpoch)
    }

    @Test
    fun `executor exceptions are sanitized and a control throw still neutralizes`() {
        val commandFixture = Fixture()
        commandFixture.executor.discreteFailure = IllegalStateException("secret credential")
        commandFixture.core.openSession("session-a")
        val commandLease = checkNotNull(commandFixture.core.acquireLease("session-a", 5_000L).leaseId)
        commandFixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("command-1", commandLease, ConsoleDiscreteAction.TAKEOFF, 500L),
        )
        val commandResult =
            commandFixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .single().result
        assertEquals("executor_failure", commandResult.reason)
        assertNull(commandResult.detail)

        val controlFixture = Fixture()
        controlFixture.core.openSession("session-a")
        val controlLease = checkNotNull(controlFixture.core.acquireLease("session-a", 5_000L).leaseId)
        controlFixture.executor.controlFailure = IllegalStateException("secret credential")
        val control = controlFixture.core.handleControlFrame("session-a", frame(controlLease, 1L))
        assertEquals("executor_failure", control.reason)
        assertEquals(1, controlFixture.executor.neutralCalls.size)

        val neutralFixture = Fixture()
        neutralFixture.core.openSession("session-a")
        val neutralLease = checkNotNull(neutralFixture.core.acquireLease("session-a", 5_000L).leaseId)
        neutralFixture.executor.neutralFailure = IllegalStateException("secret credential")
        neutralFixture.core.releaseLease("session-a", neutralLease)
        val safety =
            neutralFixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.SafetyActionObserved }
                .single().action
        assertFalse(safety.succeeded)
        assertEquals("neutralization failed", safety.detail)
        assertFalse(safety.detail.orEmpty().contains("secret"))
    }

    @Test
    fun `neutral reaches executor before a blocking safety audit`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        fixture.audit.blockKind = ConsoleAuditKind.SAFETY_NEUTRAL_REQUESTED
        val pool = Executors.newSingleThreadExecutor()

        val release = pool.submit(Callable { fixture.core.releaseLease("session-a", lease) })
        assertTrue(fixture.audit.blockEntered.await(1, TimeUnit.SECONDS))
        assertEquals(1, fixture.executor.neutralCalls.size)
        assertFalse(release.isDone)

        fixture.audit.unblock.countDown()
        assertEquals(ConsoleLeaseStatus.RELEASED, release.get(1, TimeUnit.SECONDS).status)
        pool.shutdownNow()
    }

    @Test
    fun `control execution failure reaches neutral before completion audit can block`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.executor.controlResult = ConsoleExecutionResult(false, "adapter_rejected", null)
        fixture.audit.blockKind = ConsoleAuditKind.CONTROL_COMPLETED
        val pool = Executors.newSingleThreadExecutor()

        val control = pool.submit(Callable { fixture.core.handleControlFrame("session-a", frame(lease, 1L)) })
        assertTrue(fixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
        assertEquals(1, fixture.executor.neutralCalls.size)
        assertFalse(control.isDone)

        fixture.audit.unblock.countDown()
        assertEquals(ConsoleControlStatus.REJECTED, control.get(1L, TimeUnit.SECONDS).status)
        pool.shutdownNow()
    }

    @Test
    fun `missing neutral completion evidence latches all later actuation closed`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.audit.failKinds += ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED

        fixture.core.releaseLease("session-a", lease)
        fixture.audit.failKinds.clear()
        val denied = fixture.core.acquireLease("session-b", 5_000L)

        assertEquals(ConsoleLeaseStatus.DENIED, denied.status)
        assertEquals("audit_unavailable", denied.reason)
    }

    @Test
    fun `close cancels command deadlines and suppresses late callbacks`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("command-1", lease, ConsoleDiscreteAction.TAKEOFF, 500L),
        )
        val deadline = fixture.scheduler.tasks.last()

        fixture.core.close()
        fixture.executor.completeNextDiscrete(ConsoleExecutionResult(true, detail = "late success"))
        fixture.scheduler.forceRun(deadline)

        assertTrue(deadline.cancelled)
        assertTrue(
            fixture.events.events.none {
                (it.second as? ConsoleCoreEvent.CommandCompleted)?.result?.commandId == "command-1"
            },
        )
        assertTrue(
            fixture.audit.events.any {
                it.kind == ConsoleAuditKind.COMMAND_COMPLETED && it.outcome == "cancelled"
            },
        )
    }

    @Test
    fun `terminal admission gate returns while dispatch is blocked and fences every later control invocation`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        fixture.executor.beforeSubmitControl = {
            dispatchEntered.countDown()
            check(releaseDispatch.await(5L, TimeUnit.SECONDS)) { "timed out holding dispatch gate" }
        }
        val queuedThread = AtomicReference<Thread>()
        val queuedStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)

        try {
            val active =
                pool.submit(Callable { fixture.core.handleControlFrame("session-a", frame(lease, 1L)) })
            assertTrue(dispatchEntered.await(1L, TimeUnit.SECONDS))
            assertEquals(1, fixture.executor.controlInvocations.get())

            val queued =
                pool.submit(
                    Callable {
                        queuedThread.set(Thread.currentThread())
                        queuedStarted.countDown()
                        fixture.core.handleControlFrame("session-a", frame(lease, 2L))
                    },
                )
            assertTrue(queuedStarted.await(1L, TimeUnit.SECONDS))
            awaitBlocked(checkNotNull(queuedThread.get()))

            val admissionClosed =
                pool.submit(
                    Callable {
                        fixture.core.closeActuationAdmission()
                        true
                    },
                )
            assertTrue(
                "atomic admission close must not wait for the dispatch lock",
                admissionClosed.get(1L, TimeUnit.SECONDS),
            )
            assertEquals(1, fixture.executor.controlInvocations.get())

            releaseDispatch.countDown()
            assertEquals(ConsoleControlStatus.APPLIED, active.get(1L, TimeUnit.SECONDS).status)
            val rejected = queued.get(1L, TimeUnit.SECONDS)
            assertEquals(ConsoleControlStatus.REJECTED, rejected.status)
            assertEquals("actuation_admission_closed", rejected.reason)
            assertEquals(1, fixture.executor.controlInvocations.get())

            assertTrue(
                fixture.core.updateActuationReadiness(
                    ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                        actuationLock = ActuationLockState.LOCKED,
                    ),
                ),
            )
            assertEquals(
                listOf(ConsoleSafetyTrigger.ACTUATION_READINESS_LOST),
                fixture.executor.neutralCalls.map { it.second },
            )

            // A late listener may republish the old ready state, but the terminal gate stays shut.
            assertTrue(fixture.core.updateActuationReadiness(ConsoleActuationReadinessSnapshot.MOCK_READY))
            val afterReadyAba = fixture.core.acquireLease("session-a", 5_000L)
            assertEquals(ConsoleLeaseStatus.DENIED, afterReadyAba.status)
            assertEquals("actuation_admission_closed", afterReadyAba.reason)
        } finally {
            releaseDispatch.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `terminal admission gate fences lease command and control commits blocked on durable audit`() {
        val acquireFixture = Fixture()
        acquireFixture.core.openSession("session-a")
        acquireFixture.audit.blockKind = ConsoleAuditKind.LEASE_ACQUIRED
        val acquirePool = Executors.newSingleThreadExecutor()
        try {
            val acquisition =
                acquirePool.submit(Callable { acquireFixture.core.acquireLease("session-a", 5_000L) })
            assertTrue(acquireFixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
            acquireFixture.core.closeActuationAdmission()
            acquireFixture.audit.unblock.countDown()
            val denied = acquisition.get(1L, TimeUnit.SECONDS)
            assertEquals(ConsoleLeaseStatus.DENIED, denied.status)
            assertNull(acquireFixture.core.currentLease())
        } finally {
            acquireFixture.audit.unblock.countDown()
            acquirePool.shutdownNow()
        }

        val renewFixture = Fixture()
        renewFixture.core.openSession("session-a")
        val renewLease = checkNotNull(renewFixture.core.acquireLease("session-a", 5_000L).leaseId)
        renewFixture.audit.blockKind = ConsoleAuditKind.LEASE_RENEWED
        val renewPool = Executors.newSingleThreadExecutor()
        try {
            val renewal =
                renewPool.submit(
                    Callable { renewFixture.core.renewLease("session-a", renewLease, 5_000L) },
                )
            assertTrue(renewFixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
            renewFixture.core.closeActuationAdmission()
            renewFixture.audit.unblock.countDown()
            assertEquals(ConsoleLeaseStatus.DENIED, renewal.get(1L, TimeUnit.SECONDS).status)
        } finally {
            renewFixture.audit.unblock.countDown()
            renewPool.shutdownNow()
        }

        val commandFixture = Fixture()
        commandFixture.core.openSession("session-a")
        val commandLease = checkNotNull(commandFixture.core.acquireLease("session-a", 5_000L).leaseId)
        commandFixture.audit.blockKind = ConsoleAuditKind.COMMAND_ADMITTED
        val commandPool = Executors.newSingleThreadExecutor()
        try {
            val command =
                commandPool.submit(
                    Callable {
                        commandFixture.core.handleDiscreteCommand(
                            "session-a",
                            ConsoleDiscreteCommand(
                                "stop-during-command-audit",
                                commandLease,
                                ConsoleDiscreteAction.TAKEOFF,
                                1_000L,
                            ),
                        )
                    },
                )
            assertTrue(commandFixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
            commandFixture.core.closeActuationAdmission()
            commandFixture.audit.unblock.countDown()
            assertEquals(ConsoleCommandDecision.REJECTED, command.get(1L, TimeUnit.SECONDS).decision)
            assertTrue(commandFixture.executor.discrete.isEmpty())
        } finally {
            commandFixture.audit.unblock.countDown()
            commandPool.shutdownNow()
        }

        val controlFixture = Fixture()
        controlFixture.core.openSession("session-a")
        val controlLease = checkNotNull(controlFixture.core.acquireLease("session-a", 5_000L).leaseId)
        controlFixture.audit.blockKind = ConsoleAuditKind.CONTROL_ADMITTED
        val controlPool = Executors.newSingleThreadExecutor()
        try {
            val control =
                controlPool.submit(
                    Callable { controlFixture.core.handleControlFrame("session-a", frame(controlLease, 1L)) },
                )
            assertTrue(controlFixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
            controlFixture.core.closeActuationAdmission()
            controlFixture.audit.unblock.countDown()
            assertEquals(ConsoleControlStatus.REJECTED, control.get(1L, TimeUnit.SECONDS).status)
            assertTrue(controlFixture.executor.controls.isEmpty())
        } finally {
            controlFixture.audit.unblock.countDown()
            controlPool.shutdownNow()
        }
    }

    @Test
    fun `terminal gate fences final discrete and control dispatch without blocking safety paths`() {
        val discrete = Fixture()
        discrete.executor.completeNeutralImmediately = false
        discrete.core.openSession("session-a")
        val discreteLease = checkNotNull(discrete.core.acquireLease("session-a", 5_000L).leaseId)
        discrete.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand(
                "stop-before-discrete-dispatch",
                discreteLease,
                ConsoleDiscreteAction.TAKEOFF,
                1_000L,
            ),
        )
        discrete.core.closeActuationAdmission()
        discrete.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "pre-action neutral"))
        assertTrue(discrete.executor.discrete.isEmpty())
        val commandResult =
            discrete.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .single().result
        assertEquals("actuation_admission_closed", commandResult.reason)

        val control = Fixture()
        control.core.openSession("session-a")
        val controlLease = checkNotNull(control.core.acquireLease("session-a", 5_000L).leaseId)
        control.scheduler.afterNextSchedule = { control.core.closeActuationAdmission() }
        val controlAck = control.core.handleControlFrame("session-a", frame(controlLease, 1L))
        assertEquals(ConsoleControlStatus.REJECTED, controlAck.status)
        assertEquals("actuation_admission_closed", controlAck.reason)
        assertTrue(control.executor.controls.isEmpty())

        val neutral = Fixture()
        neutral.core.openSession("session-a")
        val neutralLease = checkNotNull(neutral.core.acquireLease("session-a", 5_000L).leaseId)
        neutral.core.handleControlFrame("session-a", frame(neutralLease, 1L))
        neutral.core.closeActuationAdmission()
        neutral.core.handleControlNeutral(
            "session-a",
            ConsoleControlNeutral(neutralLease, 2L, ConsoleNeutralRequestReason.OPERATOR_RELEASE),
        )
        assertEquals(1, neutral.executor.neutralCalls.size)

        val release = Fixture()
        release.core.openSession("session-a")
        val releaseLease = checkNotNull(release.core.acquireLease("session-a", 5_000L).leaseId)
        release.core.closeActuationAdmission()
        assertEquals(ConsoleLeaseStatus.RELEASED, release.core.releaseLease("session-a", releaseLease).status)
        assertEquals(ConsoleSafetyTrigger.CLIENT_REQUEST, release.executor.neutralCalls.single().second)

        val close = Fixture()
        close.core.openSession("session-a")
        close.core.acquireLease("session-a", 5_000L)
        close.core.closeActuationAdmission()
        close.core.close()
        assertEquals(ConsoleSafetyTrigger.SERVER_STOP, close.executor.neutralCalls.single().second)
    }

    @Test
    fun `neutral request emits applied ack only after the barrier completes`() {
        val fixture = Fixture()
        fixture.executor.completeNeutralImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))

        val immediate =
            fixture.core.handleControlNeutral(
                "session-a",
                ConsoleControlNeutral(lease, 2L, ConsoleNeutralRequestReason.WINDOW_BLUR),
            )
        val before =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.ControlAcknowledged }
                .filter { it.ack.inputSequence == 2L }

        assertNull(immediate)
        assertTrue(before.isEmpty())

        fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "neutral confirmed"))
        val after =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.ControlAcknowledged }
                .single { it.ack.inputSequence == 2L }
        assertEquals(ConsoleControlStatus.APPLIED, after.ack.status)
        assertTrue(fixture.audit.events.any { it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED })
    }

    @Test
    fun `watchdog winning between core recheck and executor apply is a hard barrier`() {
        val fixture = Fixture(config = ConsoleServerCoreConfig(deadManTimeoutMillis = 50L))
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.executor.beforeSubmitControl = {
            fixture.clock.advanceMillis(51L)
            fixture.scheduler.runDue()
        }

        fixture.core.handleControlFrame("session-a", frame(lease, 1L, ttlMillis = 100L))

        assertTrue(fixture.executor.controls.isEmpty())
        assertEquals(1, fixture.executor.neutralCalls.size)
        val ack =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.ControlAcknowledged }
                .last { it.ack.inputSequence == 1L }
        assertEquals(ConsoleControlStatus.STALE, ack.ack.status)
    }

    @Test
    fun `global control epoch lets a new lease proceed while fencing a late old frame`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")
        val leaseA = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(leaseA, 1L))
        val oldFrame = fixture.executor.controls.single()
        fixture.core.releaseLease("session-a", leaseA)
        val leaseB = checkNotNull(fixture.core.acquireLease("session-b", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-b", frame(leaseB, 1L))
        val newFrame = fixture.executor.controls.last()
        var late: ConsoleExecutionResult? = null

        fixture.executor.submitControl(oldFrame) { late = it }

        assertTrue(newFrame.controlEpoch > oldFrame.controlEpoch)
        assertFalse(checkNotNull(late).succeeded)
        assertEquals("stale_control_epoch", late?.reason)
        assertEquals(2, fixture.executor.controls.size)
    }

    @Test
    fun `landing and RTH neutralize and release control before the action executes`() {
        listOf(ConsoleDiscreteAction.LANDING, ConsoleDiscreteAction.RETURN_TO_HOME).forEach { action ->
            val fixture = Fixture()
            fixture.core.openSession("session-a")
            val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
            fixture.core.handleControlFrame("session-a", frame(lease, 1L))

            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("command-1", lease, action, 500L),
            )
            val afterAction = fixture.core.handleControlFrame("session-a", frame(lease, 2L))

            assertEquals(listOf("control", "neutral", "discrete:$action"), fixture.executor.operations)
            assertEquals("not_lease_holder", afterAction.reason)
            assertEquals(1, fixture.executor.controls.size)
            assertNull(fixture.core.currentLease())
        }
    }

    @Test
    fun `release disconnect and close converge on one in-flight neutral barrier`() {
        val fixture = Fixture()
        fixture.executor.completeNeutralImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))

        fixture.core.releaseLease("session-a", lease)
        fixture.core.disconnect("session-a")
        fixture.core.close()

        assertEquals(1, fixture.executor.neutralCalls.size)
        fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "neutral confirmed"))
        assertEquals(
            1,
            fixture.events.events.count { it.second is ConsoleCoreEvent.SafetyActionObserved },
        )
    }

    @Test
    fun `disconnect and server stop each neutralize their owned lease`() {
        val disconnect = Fixture()
        disconnect.core.openSession("session-a")
        disconnect.core.acquireLease("session-a", 5_000L)
        disconnect.core.disconnect("session-a")
        assertEquals(ConsoleSafetyTrigger.CLIENT_DISCONNECT, disconnect.executor.neutralCalls.single().second)

        val stop = Fixture()
        stop.core.openSession("session-a")
        stop.core.acquireLease("session-a", 5_000L)
        stop.core.close()
        assertEquals(ConsoleSafetyTrigger.SERVER_STOP, stop.executor.neutralCalls.single().second)
    }

    @Test
    fun `server-owned readiness is fail closed and cannot be bypassed by an old lease`() {
        val fixture = Fixture(initialReadiness = ConsoleActuationReadinessSnapshot.UNAVAILABLE)
        fixture.core.openSession("session-a")

        val initiallyDenied = fixture.core.acquireLease("session-a", 5_000L)
        assertEquals("actuation_not_ready", initiallyDenied.reason)

        assertTrue(fixture.core.updateActuationReadiness(ConsoleActuationReadinessSnapshot.MOCK_READY))
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.updateActuationReadiness(
            ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                actuationLock = ActuationLockState.LOCKED,
            ),
        )

        ConsoleDiscreteAction.entries.forEachIndexed { index, action ->
            val ack =
                fixture.core.handleDiscreteCommand(
                    "session-a",
                    ConsoleDiscreteCommand("blocked-$index", lease, action, 500L),
                )
            assertEquals(ConsoleCommandDecision.REJECTED, ack.decision)
        }
        val control = fixture.core.handleControlFrame("session-a", frame(lease, 1L))

        assertTrue(fixture.executor.discrete.isEmpty())
        assertTrue(fixture.executor.controls.isEmpty())
        assertEquals(ConsoleControlStatus.REJECTED, control.status)
        assertNull(fixture.core.currentLease())
        assertEquals(
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
            fixture.executor.neutralCalls.single().second,
        )
    }

    @Test
    fun `disconnect or lease expiry before delayed takeoff neutral prevents dispatch`() {
        listOf("disconnect", "expiry").forEach { authorityLoss ->
            val fixture = Fixture()
            fixture.executor.completeNeutralImmediately = false
            fixture.core.openSession("session-a")
            val lease = checkNotNull(fixture.core.acquireLease("session-a", 500L).leaseId)

            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("takeoff-$authorityLoss", lease, ConsoleDiscreteAction.TAKEOFF, 1_000L),
            )
            if (authorityLoss == "disconnect") {
                fixture.core.disconnect("session-a")
            } else {
                fixture.clock.advanceMillis(501L)
                fixture.scheduler.runDue()
            }
            fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "neutral confirmed"))

            assertTrue(fixture.executor.discrete.isEmpty())
            val result =
                fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                    .single().result
            assertEquals(ConsoleCommandOutcome.FAILED, result.outcome)
            assertTrue(result.reason in setOf("authority_lost", "actuation_readiness_changed"))
        }
    }

    @Test
    fun `authority loss after takeoff dispatch creates a fresh neutral and suppresses late success`() {
        listOf("disconnect", "readiness", "expiry", "release", "renew_failure").forEach { loss ->
            val fixture = Fixture()
            fixture.executor.completeDiscreteImmediately = false
            fixture.core.openSession("session-a")
            val leaseTtl = if (loss == "expiry") 500L else 5_000L
            val lease = checkNotNull(fixture.core.acquireLease("session-a", leaseTtl).leaseId)
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("inflight-$loss", lease, ConsoleDiscreteAction.TAKEOFF, 1_000L),
            )
            assertEquals(1, fixture.executor.neutralCalls.size)
            assertEquals(1, fixture.executor.discrete.size)
            fixture.executor.completeNeutralImmediately = false

            if (loss == "disconnect") {
                fixture.core.disconnect("session-a")
            } else if (loss == "expiry") {
                fixture.clock.advanceMillis(501L)
                fixture.scheduler.runDue()
            } else if (loss == "release") {
                fixture.core.releaseLease("session-a", lease)
            } else if (loss == "renew_failure") {
                fixture.scheduler.throwOnNextSchedule = true
                fixture.core.renewLease("session-a", lease, 5_000L)
            } else {
                fixture.core.updateActuationReadiness(
                    ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                        actuationLock = ActuationLockState.LOCKED,
                    ),
                )
            }

            assertEquals(2, fixture.executor.neutralCalls.size)
            fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "fresh neutral"))
            fixture.executor.completeNextDiscrete(ConsoleExecutionResult(true, detail = "late takeoff success"))
            val completions =
                fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
            assertEquals(1, completions.size)
            assertEquals(ConsoleCommandOutcome.FAILED, completions.single().result.outcome)
            assertTrue(
                completions.single().result.reason in
                    setOf(
                        "authority_lost",
                        "actuation_readiness_lost",
                        "lease_ttl_expired",
                        "lease_deadline_unavailable",
                    ),
            )
        }
    }

    @Test
    fun `authority loss during terminal audit rebuilds the completed pre-action neutral barrier`() {
        listOf("disconnect", "readiness", "release", "expiry").forEach { loss ->
            val fixture = Fixture()
            fixture.core.openSession("session-a")
            val leaseTtl = if (loss == "expiry") 500L else 5_000L
            val lease = checkNotNull(fixture.core.acquireLease("session-a", leaseTtl).leaseId)
            fixture.audit.blockKind = ConsoleAuditKind.COMMAND_COMPLETED
            val pool = Executors.newSingleThreadExecutor()
            val command =
                pool.submit(
                    Callable {
                        fixture.core.handleDiscreteCommand(
                            "session-a",
                            ConsoleDiscreteCommand(
                                "completing-$loss",
                                lease,
                                ConsoleDiscreteAction.TAKEOFF,
                                5_000L,
                            ),
                        )
                    },
                )
            assertTrue(fixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
            assertEquals(1, fixture.executor.neutralCalls.size)

            when (loss) {
                "disconnect" -> fixture.core.disconnect("session-a")
                "readiness" ->
                    fixture.core.updateActuationReadiness(
                        ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                            actuationLock = ActuationLockState.LOCKED,
                        ),
                    )
                "release" -> fixture.core.releaseLease("session-a", lease)
                "expiry" -> {
                    fixture.clock.advanceMillis(501L)
                    fixture.scheduler.runDue()
                }
            }

            assertEquals("loss=$loss must invoke a fresh neutral", 2, fixture.executor.neutralCalls.size)
            fixture.audit.unblock.countDown()
            assertEquals(ConsoleCommandDecision.ACCEPTED, command.get(1L, TimeUnit.SECONDS).decision)
            assertEquals(
                1,
                fixture.audit.events.count { it.kind == ConsoleAuditKind.COMMAND_COMPLETED },
            )
            pool.shutdownNow()
        }
    }

    @Test
    fun `shutdown waits for fresh neutral evidence before executor may be closed`() {
        val fixture = Fixture()
        fixture.executor.completeDiscreteImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("shutdown-inflight", lease, ConsoleDiscreteAction.TAKEOFF, 1_000L),
        )
        fixture.executor.completeNeutralImmediately = false

        fixture.core.close()
        assertEquals(2, fixture.executor.neutralCalls.size)
        val pool = Executors.newSingleThreadExecutor()
        val awaiting = pool.submit(Callable { fixture.core.awaitShutdownNeutral(1_000L) })
        assertFalse(awaiting.isDone)

        fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "shutdown neutral"))
        assertTrue(awaiting.get(1L, TimeUnit.SECONDS))
        assertTrue(
            fixture.audit.events.any {
                it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_COMPLETED && it.outcome == "succeeded"
            },
        )
        pool.shutdownNow()
    }

    @Test
    fun `shutdown retries unresolved authority-loss neutral after the lease was revoked`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        fixture.executor.neutralResult =
            ConsoleExecutionResult(false, "neutral_failed", "authority-loss neutral failed")

        fixture.core.releaseLease("session-a", lease)
        assertEquals(1, fixture.executor.neutralCalls.size)
        fixture.executor.completeNeutralImmediately = false
        fixture.core.close()

        assertEquals(2, fixture.executor.neutralCalls.size)
        assertEquals(ConsoleSafetyTrigger.SERVER_STOP, fixture.executor.neutralCalls.last().second)
        val pool = Executors.newSingleThreadExecutor()
        val awaiting = pool.submit(Callable { fixture.core.awaitShutdownNeutral(1_000L) })
        assertFalse(awaiting.isDone)
        fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "shutdown retry neutral"))
        assertTrue(awaiting.get(1L, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun `failed shutdown neutral gets one bounded retry and is never reported confirmed`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        fixture.executor.completeNeutralImmediately = false

        fixture.core.close()
        val pool = Executors.newSingleThreadExecutor()
        val awaiting = pool.submit(Callable { fixture.core.awaitShutdownNeutral(1_000L) })
        assertFalse(awaiting.isDone)
        fixture.executor.completeNextNeutral(
            ConsoleExecutionResult(false, "neutral_failed", "first shutdown neutral failed"),
        )
        assertEquals(2, fixture.executor.neutralCalls.size)
        assertFalse(awaiting.isDone)
        fixture.executor.completeNextNeutral(
            ConsoleExecutionResult(false, "neutral_failed", "retry shutdown neutral failed"),
        )

        assertFalse(awaiting.get(1L, TimeUnit.SECONDS))
        assertEquals(2, fixture.executor.neutralCalls.size)
        pool.shutdownNow()
    }

    @Test
    fun `landing and RTH remain safety-directed after client disconnect`() {
        listOf(ConsoleDiscreteAction.LANDING, ConsoleDiscreteAction.RETURN_TO_HOME).forEach { action ->
            val fixture = Fixture()
            fixture.executor.completeNeutralImmediately = false
            fixture.core.openSession("session-a")
            val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("safe-${action.name.lowercase()}", lease, action, 1_000L),
            )
            fixture.core.disconnect("session-a")
            fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "neutral confirmed"))

            assertEquals(listOf(action), fixture.executor.discrete.map { it.command.action })
        }
    }

    @Test
    fun `dispatched landing and RTH keep their terminal result after client disconnect`() {
        listOf(ConsoleDiscreteAction.LANDING, ConsoleDiscreteAction.RETURN_TO_HOME).forEach { action ->
            val fixture = Fixture()
            fixture.executor.completeDiscreteImmediately = false
            fixture.core.openSession("session-a")
            val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("dispatched-${action.name.lowercase()}", lease, action, 1_000L),
            )
            assertEquals(listOf(action), fixture.executor.discrete.map { it.command.action })
            assertEquals(1, fixture.executor.neutralCalls.size)

            fixture.core.disconnect("session-a")
            assertEquals(2, fixture.executor.neutralCalls.size)
            assertEquals(
                ConsoleSafetyTrigger.CLIENT_DISCONNECT,
                fixture.executor.neutralCalls.last().second,
            )
            fixture.executor.completeNextDiscrete(
                ConsoleExecutionResult(true, detail = "safety-directed action completed"),
            )

            val completions =
                fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
            assertEquals(1, completions.size)
            assertEquals(ConsoleCommandOutcome.SUCCEEDED, completions.single().result.outcome)
            assertEquals(
                1,
                fixture.audit.events.count { it.kind == ConsoleAuditKind.COMMAND_COMPLETED },
            )
        }
    }

    @Test
    fun `readiness epoch fences locked-ready ABA while command waits for neutral`() {
        val fixture = Fixture()
        fixture.executor.completeNeutralImmediately = false
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleDiscreteCommand(
            "session-a",
            ConsoleDiscreteCommand("command-aba", lease, ConsoleDiscreteAction.TAKEOFF, 1_000L),
        )

        fixture.core.updateActuationReadiness(
            ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                aircraftConnection = AircraftConnectionState.DISCONNECTED,
            ),
        )
        fixture.core.updateActuationReadiness(ConsoleActuationReadinessSnapshot.MOCK_READY)
        fixture.executor.completeNextNeutral(ConsoleExecutionResult(true, detail = "neutral confirmed"))

        assertTrue(fixture.executor.discrete.isEmpty())
        val result =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.CommandCompleted }
                .single().result
        assertEquals("actuation_readiness_changed", result.reason)
    }

    @Test
    fun `readiness loss during admission audit prevents commit and executor side effects`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.audit.blockKind = ConsoleAuditKind.COMMAND_ADMITTED
        val pool = Executors.newSingleThreadExecutor()

        val command =
            pool.submit(
                Callable {
                    fixture.core.handleDiscreteCommand(
                        "session-a",
                        ConsoleDiscreteCommand("audit-race", lease, ConsoleDiscreteAction.TAKEOFF, 1_000L),
                    )
                },
            )
        assertTrue(fixture.audit.blockEntered.await(1L, TimeUnit.SECONDS))
        fixture.core.updateActuationReadiness(
            ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                actuationLock = ActuationLockState.LOCKED,
            ),
        )
        fixture.audit.unblock.countDown()

        assertEquals(ConsoleCommandDecision.REJECTED, command.get(1L, TimeUnit.SECONDS).decision)
        assertTrue(fixture.executor.discrete.isEmpty())
        assertNull(fixture.core.currentLease())
        pool.shutdownNow()
    }

    @Test
    fun `readiness loss revokes active control and invokes neutral before returning`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))

        val updated =
            fixture.core.updateActuationReadiness(
                ConsoleActuationReadinessSnapshot.MOCK_READY.copy(
                    aircraftConnection = AircraftConnectionState.ERROR,
                ),
            )

        assertTrue(updated)
        assertNull(fixture.core.currentLease())
        assertEquals(1, fixture.executor.neutralCalls.size)
        assertEquals(
            ConsoleSafetyTrigger.ACTUATION_READINESS_LOST,
            fixture.executor.neutralCalls.single().second,
        )
    }

    @Test
    fun `DJI commissioning requires server-owned session and per-intent allow-list`() {
        val takeoffOnly =
            ConsoleActuationReadinessSnapshot(
                adapter = AdapterKind.DJI,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                actuationLock = ActuationLockState.UNLOCKED,
                operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
                commissioningAuthority =
                    ConsoleCommissioningAuthority(
                        "session-a",
                        setOf(ConsoleActuationIntent.TAKEOFF),
                    ),
            )
        val fixture = Fixture(initialReadiness = takeoffOnly)
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")

        assertEquals("actuation_not_ready", fixture.core.acquireLease("session-b", 5_000L).reason)
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        val landing =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("landing-denied", lease, ConsoleDiscreteAction.LANDING, 500L),
            )
        val takeoff =
            fixture.core.handleDiscreteCommand(
                "session-a",
                ConsoleDiscreteCommand("takeoff-allowed", lease, ConsoleDiscreteAction.TAKEOFF, 500L),
            )

        assertEquals("actuation_not_ready", landing.reason)
        assertEquals(ConsoleCommandDecision.ACCEPTED, takeoff.decision)
        assertEquals(listOf(ConsoleDiscreteAction.TAKEOFF), fixture.executor.discrete.map { it.command.action })
    }

    @Test
    fun `authority decision and every explicit neutral reason are durable audit evidence`() {
        ConsoleNeutralRequestReason.entries.forEach { reason ->
            val fixture = Fixture()
            fixture.core.openSession("session-a")
            val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

            fixture.core.handleControlFrame("session-a", frame(lease, 1L))
            fixture.core.handleControlNeutral(
                "session-a",
                ConsoleControlNeutral(lease, 2L, reason),
            )

            val admitted = fixture.audit.events.single { it.kind == ConsoleAuditKind.CONTROL_ADMITTED }
            val completed = fixture.audit.events.single { it.kind == ConsoleAuditKind.CONTROL_COMPLETED }
            val neutral = fixture.audit.events.single { it.kind == ConsoleAuditKind.SAFETY_NEUTRAL_REQUESTED }
            assertNotNull(admitted.authorityDecisionId)
            assertEquals(admitted.authorityDecisionId, completed.authorityDecisionId)
            assertEquals(reason.name.lowercase(), neutral.clientRequestReason)
        }
    }

    @Test
    fun `failed neutral locks out the next lease instead of claiming success`() {
        val fixture = Fixture()
        fixture.executor.neutralResult = ConsoleExecutionResult(false, "neutral_failed", "mock refused neutral")
        fixture.core.openSession("session-a")
        fixture.core.openSession("session-b")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)

        fixture.core.releaseLease("session-a", lease)
        val denied = fixture.core.acquireLease("session-b", 5_000L)
        val safety =
            fixture.events.events.mapNotNull { it.second as? ConsoleCoreEvent.SafetyActionObserved }.single().action

        assertEquals("actuation_locked", denied.reason)
        assertFalse(safety.succeeded)
        assertEquals("mock refused neutral", safety.detail)
    }

    @Test
    fun `authority loss retries neutral after an earlier neutral failure`() {
        val fixture = Fixture()
        fixture.core.openSession("session-a")
        val lease = checkNotNull(fixture.core.acquireLease("session-a", 5_000L).leaseId)
        fixture.core.handleControlFrame("session-a", frame(lease, 1L))
        fixture.executor.neutralResult =
            ConsoleExecutionResult(false, "neutral_failed", "first neutral failed")

        fixture.core.handleControlNeutral(
            "session-a",
            ConsoleControlNeutral(lease, 2L, ConsoleNeutralRequestReason.WINDOW_BLUR),
        )
        fixture.executor.neutralResult = ConsoleExecutionResult(true, detail = "retry neutral")
        fixture.core.disconnect("session-a")

        assertEquals(2, fixture.executor.neutralCalls.size)
        assertEquals(ConsoleSafetyTrigger.CLIENT_DISCONNECT, fixture.executor.neutralCalls.last().second)
    }

    @Test
    fun `event sink is invoked outside the state lock`() {
        val coreRef = AtomicReference<ConsoleServerCore>()
        val observedUnlocked = AtomicBoolean(false)
        val callbackPool = Executors.newSingleThreadExecutor()
        val fixture =
            Fixture(
                eventSinkOverride =
                    ConsoleEventSink { _, event ->
                        if (event is ConsoleCoreEvent.LeaseChanged && event.state.status == ConsoleLeaseStatus.HELD) {
                            val future = callbackPool.submit(Callable { coreRef.get().currentLease() })
                            observedUnlocked.set(future.get(1, TimeUnit.SECONDS) != null)
                        }
                    },
            )
        coreRef.set(fixture.core)
        fixture.core.openSession("session-a")

        fixture.core.acquireLease("session-a", 5_000L)

        callbackPool.shutdownNow()
        assertTrue(observedUnlocked.get())
    }

    private fun frame(
        leaseId: String,
        sequence: Long,
        ttlMillis: Long = 100L,
    ) = ConsoleControlFrame(
        leaseId = leaseId,
        inputSequence = sequence,
        ttlMillis = ttlMillis,
        forward = 0.5,
        right = -0.25,
        up = 0.1,
        yaw = -0.5,
    )

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private class Fixture(
        config: ConsoleServerCoreConfig = ConsoleServerCoreConfig(),
        eventSinkOverride: ConsoleEventSink? = null,
        leaseIdFactoryOverride: (() -> String)? = null,
        initialReadiness: ConsoleActuationReadinessSnapshot = ConsoleActuationReadinessSnapshot.MOCK_READY,
    ) {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val executor = FakeExecutor()
        val audit = RecordingAuditSink()
        val events = RecordingEventSink()
        private val leaseIds = AtomicInteger()
        private val authorityIds = AtomicInteger()
        val core =
            ConsoleServerCore(
                config = config,
                admission =
                    ConsoleCommandAdmission(
                        agentId = "agent-1",
                        streamId = "stream-1",
                        epochClock = clock,
                        authorityDecisionId = { "authority-${authorityIds.incrementAndGet()}" },
                    ),
                executor = executor,
                monotonicClock = clock,
                epochClock = clock,
                scheduler = scheduler,
                auditSink = audit,
                eventSink = eventSinkOverride ?: events,
                initialActuationReadiness = initialReadiness,
                leaseIdFactory = leaseIdFactoryOverride ?: { "lease-${leaseIds.incrementAndGet()}" },
            )
    }

    private class FakeClock : ConsoleMonotonicClock, ConsoleEpochClock {
        var nanos: Long = 1_000_000_000L
        var epochMillis: Long = 1_800_000_000_000L

        override fun nowNanos(): Long = nanos
        override fun nowMillis(): Long = epochMillis
        fun advanceMillis(millis: Long) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis)
        }
    }

    private class FakeScheduler(private val clock: FakeClock) : ConsoleDeadlineScheduler {
        val tasks = mutableListOf<Task>()
        var throwOnNextSchedule = false
        var afterNextSchedule: (() -> Unit)? = null

        override fun scheduleAt(deadlineNanos: Long, task: () -> Unit): ConsoleScheduledTask {
            if (throwOnNextSchedule) {
                throwOnNextSchedule = false
                error("scheduler unavailable")
            }
            val scheduled = Task(deadlineNanos, task)
            tasks += scheduled
            afterNextSchedule?.also { afterNextSchedule = null }?.invoke()
            return ConsoleScheduledTask { scheduled.cancelled = true }
        }

        fun runDue() {
            tasks.filter { !it.cancelled && !it.ran && it.deadlineNanos <= clock.nanos }
                .toList()
                .forEach(::forceRun)
        }

        fun forceRun(task: Task) {
            if (task.ran) return
            task.ran = true
            task.callback()
        }

        class Task(val deadlineNanos: Long, val callback: () -> Unit) {
            var cancelled = false
            var ran = false
        }
    }

    private class FakeExecutor : ConsoleCommandExecutor {
        val discrete = mutableListOf<AdmittedDiscreteCommand>()
        val controls = mutableListOf<AdmittedControlFrame>()
        val neutralCalls = mutableListOf<Pair<String, ConsoleSafetyTrigger>>()
        val neutralEpochs = mutableListOf<Long>()
        val operations = mutableListOf<String>()
        val controlInvocations = AtomicInteger()
        private val pendingDiscrete = ArrayDeque<(ConsoleExecutionResult) -> Unit>()
        private val pendingNeutral = ArrayDeque<(ConsoleExecutionResult) -> Unit>()
        private var maximumNeutralizedEpoch = -1L
        var completeDiscreteImmediately = true
        var completeNeutralImmediately = true
        var neutralResult = ConsoleExecutionResult(true, detail = "mock neutral")
        var controlResult = ConsoleExecutionResult(true, detail = "SIMULATED control")
        var discreteFailure: RuntimeException? = null
        var controlFailure: RuntimeException? = null
        var neutralFailure: RuntimeException? = null
        var beforeSubmitControl: (() -> Unit)? = null
        var beforeNeutralize: (() -> Unit)? = null

        override fun executeDiscrete(
            command: AdmittedDiscreteCommand,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            discreteFailure?.let { throw it }
            discrete += command
            operations += "discrete:${command.command.action}"
            if (completeDiscreteImmediately) {
                callback(ConsoleExecutionResult(true, detail = "SIMULATED ${command.command.action}"))
            } else {
                pendingDiscrete += callback
            }
        }

        override fun submitControl(
            frame: AdmittedControlFrame,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            controlInvocations.incrementAndGet()
            beforeSubmitControl?.invoke()
            controlFailure?.let { throw it }
            if (frame.controlEpoch <= maximumNeutralizedEpoch) {
                callback(ConsoleExecutionResult(false, "stale_control_epoch", null))
                return
            }
            controls += frame
            operations += "control"
            callback(controlResult)
        }

        override fun neutralize(
            leaseId: String,
            controlEpoch: Long,
            trigger: ConsoleSafetyTrigger,
            callback: (ConsoleExecutionResult) -> Unit,
        ) {
            beforeNeutralize?.invoke()
            neutralFailure?.let { throw it }
            maximumNeutralizedEpoch = maxOf(maximumNeutralizedEpoch, controlEpoch)
            neutralCalls += leaseId to trigger
            neutralEpochs += controlEpoch
            operations += "neutral"
            if (completeNeutralImmediately) callback(neutralResult) else pendingNeutral += callback
        }

        fun completeNextNeutral(result: ConsoleExecutionResult) {
            pendingNeutral.removeFirst()(result)
        }

        fun completeNextDiscrete(result: ConsoleExecutionResult) {
            pendingDiscrete.removeFirst()(result)
        }
    }

    private class RecordingAuditSink : ConsoleAuditSink {
        val events = mutableListOf<ConsoleAuditEvent>()
        val failKinds = mutableSetOf<ConsoleAuditKind>()
        var blockKind: ConsoleAuditKind? = null
        val blockEntered = CountDownLatch(1)
        val unblock = CountDownLatch(1)

        override fun record(event: ConsoleAuditEvent) {
            if (event.kind in failKinds) error("audit unavailable")
            if (event.kind == blockKind) {
                blockEntered.countDown()
                check(unblock.await(2, TimeUnit.SECONDS)) { "timed out waiting to unblock audit" }
            }
            events += event
        }
    }

    private class RecordingEventSink : ConsoleEventSink {
        val events = mutableListOf<Pair<String?, ConsoleCoreEvent>>()
        override fun emit(targetSessionId: String?, event: ConsoleCoreEvent) {
            events += targetSessionId to event
        }
    }
}
