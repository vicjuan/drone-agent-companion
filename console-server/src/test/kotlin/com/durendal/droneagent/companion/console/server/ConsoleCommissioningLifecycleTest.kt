package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.ActuationLockState
import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCommissioningLifecycleTest {
    @Test
    fun `DJI observation can be ready while public actuation stays locked until explicit grant`() {
        val lifecycle = deterministicLifecycle()
        val publicRuntime =
            ConsoleActuationReadinessSnapshot(
                adapter = AdapterKind.DJI,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                actuationLock = ActuationLockState.LOCKED,
                operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
            )

        assertEquals(ActuationLockState.LOCKED, publicRuntime.actuationLock)
        assertFalse(publicRuntime.allowsLease(OPERATOR_SESSION_ID))
        assertNull(lifecycle.currentSession())

        val reserved =
            lifecycle.reserveStart(
                lifecycle.generateCommissioningId(),
                observation = READY_OBSERVATION,
                operatorSessionId = OPERATOR_SESSION_ID,
                operatorSessionIsOpen = true,
                allowedIntents = setOf(ConsoleActuationIntent.TAKEOFF),
                ttlMillis = 1_000L,
            )
        val reservation = checkNotNull(reserved.reservation)

        assertNull(reserved.refusal)
        assertNull(lifecycle.currentSession())
        val commit = lifecycle.commit(reservation, START_NANOS)
        val grant = checkNotNull(commit.session)
        assertEquals(ConsoleCommissioningCommitDecision.COMMITTED, commit.decision)
        assertTrue(
            lifecycle.allowsLease(
                READY_OBSERVATION,
                grant,
                OPERATOR_SESSION_ID,
                START_NANOS,
            ),
        )
        assertTrue(
            lifecycle.authorizes(
                READY_OBSERVATION,
                grant,
                OPERATOR_SESSION_ID,
                ConsoleActuationIntent.TAKEOFF,
                START_NANOS,
            ),
        )
        assertFalse(
            lifecycle.authorizes(
                READY_OBSERVATION,
                grant,
                OPERATOR_SESSION_ID,
                ConsoleActuationIntent.LANDING,
                START_NANOS,
            ),
        )
        assertFalse(
            lifecycle.allowsLease(
                READY_OBSERVATION,
                grant,
                "different-operator",
                START_NANOS,
            ),
        )
        assertFalse(
            lifecycle.allowsLease(
                ConsoleActuationObservation(
                    adapter = AdapterKind.DJI,
                    aircraftConnection = AircraftConnectionState.CONNECTED,
                    adapterActuationReady = false,
                    operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
                ),
                grant,
                OPERATOR_SESSION_ID,
                START_NANOS,
            ),
        )
        assertEquals(
            "commissioning authority must not rewrite browser-visible lock truth",
            ActuationLockState.LOCKED,
            publicRuntime.actuationLock,
        )
    }

    @Test
    fun `start requires every G520 commissioning observation predicate`() {
        val ineligible =
            listOf(
                ConsoleActuationObservation(
                    AdapterKind.MOCK,
                    AircraftConnectionState.CONNECTED,
                    adapterActuationReady = true,
                    operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
                ),
                ConsoleActuationObservation(
                    AdapterKind.DJI,
                    AircraftConnectionState.CONNECTING,
                    adapterActuationReady = true,
                    operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
                ),
                ConsoleActuationObservation(
                    AdapterKind.DJI,
                    AircraftConnectionState.CONNECTED,
                    adapterActuationReady = false,
                    operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
                ),
                ConsoleActuationObservation(
                    AdapterKind.DJI,
                    AircraftConnectionState.CONNECTED,
                    adapterActuationReady = true,
                    operatingProfile = OperatingProfile.OPERATIONAL,
                ),
                ConsoleActuationObservation.UNAVAILABLE,
            )

        ineligible.forEachIndexed { index, observation ->
            val lifecycle = deterministicLifecycle()
            val result =
                lifecycle.reserveStart(
                    lifecycle.generateCommissioningId(),
                    observation,
                    OPERATOR_SESSION_ID,
                    operatorSessionIsOpen = true,
                    allowedIntents = setOf(ConsoleActuationIntent.VIRTUAL_STICK),
                    ttlMillis = 1_000L,
                )

            assertEquals(
                "predicate case $index",
                ConsoleCommissioningStartDecision.OBSERVATION_NOT_READY,
                result.refusal,
            )
            assertNull(result.reservation)
            assertNull(lifecycle.currentSession())
        }
    }

    @Test
    fun `start validates open operator allow-list and fixed TTL bounds without state mutation`() {
        fun start(
            lifecycle: ConsoleCommissioningLifecycle,
            operatorSessionId: String = OPERATOR_SESSION_ID,
            operatorSessionIsOpen: Boolean = true,
            allowedIntents: Set<ConsoleActuationIntent> = setOf(ConsoleActuationIntent.TAKEOFF),
            ttlMillis: Long = 1_000L,
        ) = lifecycle.reserveStart(
            lifecycle.generateCommissioningId(),
            READY_OBSERVATION,
            operatorSessionId,
            operatorSessionIsOpen,
            allowedIntents,
            ttlMillis,
        )

        assertEquals(
            ConsoleCommissioningStartDecision.INVALID_OPERATOR_SESSION_ID,
            start(deterministicLifecycle(), operatorSessionId = "invalid operator").refusal,
        )
        assertEquals(
            ConsoleCommissioningStartDecision.OPERATOR_SESSION_NOT_OPEN,
            start(deterministicLifecycle(), operatorSessionIsOpen = false).refusal,
        )
        assertEquals(
            ConsoleCommissioningStartDecision.EMPTY_INTENT_ALLOW_LIST,
            start(deterministicLifecycle(), allowedIntents = emptySet()).refusal,
        )
        listOf(999L, 300_001L).forEach { invalidTtl ->
            val lifecycle = deterministicLifecycle()
            assertEquals(
                ConsoleCommissioningStartDecision.INVALID_TTL,
                start(lifecycle, ttlMillis = invalidTtl).refusal,
            )
            assertNull(lifecycle.currentSession())
        }

        val lifecycle = deterministicLifecycle()
        val minimum = start(lifecycle, ttlMillis = ConsoleCommissioningLifecycle.MINIMUM_TTL_MILLIS)
        val minimumReservation = checkNotNull(minimum.reservation)
        val minimumCommit = lifecycle.commit(minimumReservation, START_NANOS)
        val minimumGrant = checkNotNull(minimumCommit.session)
        assertEquals(ConsoleCommissioningCommitDecision.COMMITTED, minimumCommit.decision)
        assertEquals(1_000L, minimumGrant.remainingTtlMillis(START_NANOS))
        assertEquals(ConsoleCommissioningRevokeDecision.REVOKED, lifecycle.revoke(minimumGrant))

        val maximum = start(lifecycle, ttlMillis = ConsoleCommissioningLifecycle.MAXIMUM_TTL_MILLIS)
        val maximumReservation = checkNotNull(maximum.reservation)
        val maximumCommit = lifecycle.commit(maximumReservation, START_NANOS)
        assertEquals(ConsoleCommissioningCommitDecision.COMMITTED, maximumCommit.decision)
        assertEquals(300_000L, checkNotNull(maximumCommit.session).remainingTtlMillis(START_NANOS))
    }

    @Test
    fun `single active generation cannot be renewed expanded or mutated through its view`() {
        val lifecycle = deterministicLifecycle()
        val callerIntents = mutableSetOf(ConsoleActuationIntent.TAKEOFF)
        val first =
            lifecycle.reserveStart(
                lifecycle.generateCommissioningId(),
                READY_OBSERVATION,
                OPERATOR_SESSION_ID,
                operatorSessionIsOpen = true,
                allowedIntents = callerIntents,
                ttlMillis = 1_000L,
            )
        val firstReservation = checkNotNull(first.reservation)
        val firstCommit = lifecycle.commit(firstReservation, START_NANOS)
        val original = checkNotNull(firstCommit.session)
        assertEquals(ConsoleCommissioningCommitDecision.COMMITTED, firstCommit.decision)
        callerIntents += ConsoleActuationIntent.LANDING

        val attemptedExpansion =
            lifecycle.reserveStart(
                lifecycle.generateCommissioningId(),
                READY_OBSERVATION,
                OPERATOR_SESSION_ID,
                operatorSessionIsOpen = true,
                allowedIntents = ConsoleActuationIntent.entries.toSet(),
                ttlMillis = 300_000L,
            )
        val current = checkNotNull(lifecycle.currentSession())

        assertEquals(
            ConsoleCommissioningStartDecision.SESSION_ALREADY_ACTIVE,
            attemptedExpansion.refusal,
        )
        assertNull(attemptedExpansion.reservation)
        assertEquals(setOf(ConsoleActuationIntent.TAKEOFF), original.allowedIntents)
        assertEquals(original.commissioningId, current.commissioningId)
        assertEquals(original.generation, current.generation)
        assertEquals(original.expiresAtMonotonicNanos, current.expiresAtMonotonicNanos)
        assertTrue(
            ConsoleCommissioningSessionView::class.java.declaredMethods.none {
                it.name == "copy" || it.name.startsWith("copy\$")
            },
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (original.allowedIntents as MutableSet<ConsoleActuationIntent>) += ConsoleActuationIntent.LANDING
        }
    }

    @Test
    fun `default lifecycle generates opaque UUIDs and advances generation only after commit`() {
        val lifecycle = ConsoleCommissioningLifecycle()
        val first = startValid(lifecycle, START_NANOS)
        assertTrue(UUID_V4.matches(first.commissioningId))
        assertEquals(1L, first.generation)
        assertEquals(ConsoleCommissioningRevokeDecision.REVOKED, lifecycle.revoke(first))

        val second = startValid(lifecycle, START_NANOS + 2_000_000_000L)
        assertTrue(UUID_V4.matches(second.commissioningId))
        assertNotEquals(first.commissioningId, second.commissioningId)
        assertEquals(2L, second.generation)

        val calls = AtomicInteger()
        val recoverable =
            ConsoleCommissioningLifecycle {
                if (calls.incrementAndGet() == 1) "operator-controlled-id" else uuid(7)
            }
        val refused =
            recoverable.reserveStart(
                recoverable.generateCommissioningId(),
                READY_OBSERVATION,
                OPERATOR_SESSION_ID,
                true,
                setOf(ConsoleActuationIntent.TAKEOFF),
                1_000L,
            )
        assertEquals(ConsoleCommissioningStartDecision.COMMISSIONING_ID_UNAVAILABLE, refused.refusal)
        assertNull(recoverable.currentSession())
        assertEquals(1L, startValid(recoverable, START_NANOS).generation)
    }

    @Test
    fun `expiry decision is pure and stale generations cannot affect a replacement grant`() {
        val lifecycle = deterministicLifecycle(reuseId = true)
        val first = startValid(lifecycle, START_NANOS)
        val deadline = first.expiresAtMonotonicNanos

        assertEquals(
            ConsoleCommissioningExpiryDecision.NOT_DUE,
            lifecycle.expiryDecision(first, deadline - 1L),
        )
        assertEquals(
            ConsoleCommissioningExpiryDecision.DUE,
            lifecycle.expiryDecision(first, deadline),
        )
        assertEquals(
            ConsoleCommissioningExpiryDecision.DUE,
            lifecycle.expiryDecision(first, deadline),
        )
        assertEquals(first.generation, lifecycle.currentSession()?.generation)
        assertFalse(
            lifecycle.authorizes(
                READY_OBSERVATION,
                first,
                OPERATOR_SESSION_ID,
                ConsoleActuationIntent.TAKEOFF,
                deadline,
            ),
        )

        assertEquals(ConsoleCommissioningRevokeDecision.REVOKED, lifecycle.revoke(first))
        val replacement = startValid(lifecycle, deadline)
        assertEquals(first.commissioningId, replacement.commissioningId)
        assertTrue(replacement.generation > first.generation)

        assertEquals(
            ConsoleCommissioningExpiryDecision.STALE_SESSION,
            lifecycle.expiryDecision(first, deadline + 1L),
        )
        assertEquals(ConsoleCommissioningRevokeDecision.STALE_SESSION, lifecycle.revoke(first))
        assertEquals(replacement.generation, lifecycle.currentSession()?.generation)
        assertTrue(
            lifecycle.authorizes(
                READY_OBSERVATION,
                replacement,
                OPERATOR_SESSION_ID,
                ConsoleActuationIntent.TAKEOFF,
                deadline + 1L,
            ),
        )
    }

    @Test
    fun `bounded monotonic expiry remains correct across Long wrap`() {
        val lifecycle = deterministicLifecycle()
        val startNanos = Long.MAX_VALUE - 500_000_000L
        val grant = startValid(lifecycle, startNanos)
        val justBefore = startNanos + 999_000_000L
        val due = startNanos + 1_000_000_000L

        assertTrue(grant.expiresAtMonotonicNanos < 0L)
        assertEquals(1_000L, grant.remainingTtlMillis(startNanos))
        assertEquals(1L, grant.remainingTtlMillis(justBefore))
        assertEquals(
            ConsoleCommissioningExpiryDecision.NOT_DUE,
            lifecycle.expiryDecision(grant, justBefore),
        )
        assertEquals(
            ConsoleCommissioningExpiryDecision.DUE,
            lifecycle.expiryDecision(grant, due),
        )
        assertEquals(0L, grant.remainingTtlMillis(due))
    }

    private fun startValid(
        lifecycle: ConsoleCommissioningLifecycle,
        nowNanos: Long,
    ): ConsoleCommissioningSessionView =
        checkNotNull(
            lifecycle.reserveStart(
                lifecycle.generateCommissioningId(),
                READY_OBSERVATION,
                OPERATOR_SESSION_ID,
                operatorSessionIsOpen = true,
                allowedIntents = setOf(ConsoleActuationIntent.TAKEOFF),
                ttlMillis = 1_000L,
            ).reservation,
        ).let { reservation ->
            val commit = lifecycle.commit(reservation, nowNanos)
            check(ConsoleCommissioningCommitDecision.COMMITTED == commit.decision)
            checkNotNull(commit.session)
        }

    private fun deterministicLifecycle(reuseId: Boolean = false): ConsoleCommissioningLifecycle {
        val ids = AtomicInteger()
        return ConsoleCommissioningLifecycle {
            if (reuseId) uuid(1) else uuid(ids.incrementAndGet())
        }
    }

    private fun uuid(suffix: Int): String =
        "00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}"

    private companion object {
        const val OPERATOR_SESSION_ID = "operator-session"
        const val START_NANOS = 1_000_000_000L
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val READY_OBSERVATION =
            ConsoleActuationObservation(
                adapter = AdapterKind.DJI,
                aircraftConnection = AircraftConnectionState.CONNECTED,
                adapterActuationReady = true,
                operatingProfile = OperatingProfile.HARDWARE_COMMISSIONING,
            )
    }
}
