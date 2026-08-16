package com.durendal.droneagent.companion.console.server

import com.durendal.droneagent.companion.console.protocol.AdapterKind
import com.durendal.droneagent.companion.console.protocol.AircraftConnectionState
import com.durendal.droneagent.companion.console.protocol.OperatingProfile
import java.util.Collections
import java.util.EnumSet
import java.util.UUID

/**
 * Host-owned adapter observation used only to decide whether a G520 commissioning grant may exist.
 *
 * This is deliberately separate from the browser-visible runtime actuation lock. A DJI adapter can
 * report that its actuation boundary is ready while the effective runtime state remains locked
 * until [ConsoleCommissioningLifecycle] owns an explicit, unexpired grant.
 */
class ConsoleActuationObservation(
    val adapter: AdapterKind?,
    val aircraftConnection: AircraftConnectionState,
    val adapterActuationReady: Boolean,
    val operatingProfile: OperatingProfile?,
) {
    internal fun sameStateAs(other: ConsoleActuationObservation): Boolean =
        adapter == other.adapter &&
            aircraftConnection == other.aircraftConnection &&
            adapterActuationReady == other.adapterActuationReady &&
            operatingProfile == other.operatingProfile

    internal fun isEligibleForG520Commissioning(): Boolean =
        adapter == AdapterKind.DJI &&
            aircraftConnection == AircraftConnectionState.CONNECTED &&
            adapterActuationReady &&
            operatingProfile == OperatingProfile.HARDWARE_COMMISSIONING

    companion object {
        val UNAVAILABLE =
            ConsoleActuationObservation(
                adapter = null,
                aircraftConnection = AircraftConnectionState.DISCONNECTED,
                adapterActuationReady = false,
                operatingProfile = null,
            )
    }
}

/** Every start outcome is bounded; no caller-controlled text crosses this lifecycle boundary. */
enum class ConsoleCommissioningStartDecision {
    STARTED,
    INVALID_OPERATOR_SESSION_ID,
    OPERATOR_SESSION_NOT_OPEN,
    INVALID_TTL,
    EMPTY_INTENT_ALLOW_LIST,
    OBSERVATION_NOT_READY,
    SESSION_ALREADY_ACTIVE,
    COMMISSIONING_ID_UNAVAILABLE,
    GENERATION_EXHAUSTED,
    SERVER_CLOSED,
    AUDIT_UNAVAILABLE,
    STATE_CHANGED,
    DEADLINE_UNAVAILABLE,
}

internal enum class ConsoleCommissioningCommitDecision {
    COMMITTED,
    STALE_RESERVATION,
}

internal class ConsoleCommissioningCommitResult internal constructor(
    val decision: ConsoleCommissioningCommitDecision,
    val session: ConsoleCommissioningSessionView?,
) {
    init {
        require((decision == ConsoleCommissioningCommitDecision.COMMITTED) == (session != null)) {
            "only a committed commissioning reservation may carry a session"
        }
    }
}

internal enum class ConsoleCommissioningAbortDecision {
    ABORTED,
    STALE_RESERVATION,
}

/** A stale revoke is distinguishable from an already-idle lifecycle without mutating either. */
enum class ConsoleCommissioningRevokeDecision {
    REVOKED,
    REVOKED_AUDIT_UNAVAILABLE,
    NO_ACTIVE_SESSION,
    STALE_SESSION,
}

enum class ConsoleCommissioningTerminationReason {
    HOST_REVOKED,
    TTL_EXPIRED,
    OPERATOR_DISCONNECTED,
    OBSERVATION_LOST,
    RUNTIME_STATE_CHANGED,
    SERVER_CLOSED,
    AUDIT_UNAVAILABLE,
    DEADLINE_UNAVAILABLE,
}

/**
 * Pure scheduler decision. [DUE] does not revoke state; the owner must call [revoke] while holding
 * the same higher-level server lock that protects leases, audit transitions and neutral planning.
 */
enum class ConsoleCommissioningExpiryDecision {
    DUE,
    NOT_DUE,
    NO_ACTIVE_SESSION,
    STALE_SESSION,
}

/**
 * Immutable grant identity returned to the server owner.
 *
 * This intentionally is not a data class: authority cannot be expanded or renewed with `copy`.
 * The intent set is a defensive, unmodifiable snapshot.
 */
class ConsoleCommissioningSessionView internal constructor(
    val commissioningId: String,
    val generation: Long,
    val operatorSessionId: String,
    allowedIntents: Set<ConsoleActuationIntent>,
    val expiresAtMonotonicNanos: Long,
) {
    val allowedIntents: Set<ConsoleActuationIntent> = immutableIntentSet(allowedIntents)

    fun remainingTtlMillis(nowNanos: Long): Long =
        remainingMillis(expiresAtMonotonicNanos, nowNanos)
}

/** A start decision carries a view only when a new generation was committed. */
class ConsoleCommissioningStartResult internal constructor(
    val decision: ConsoleCommissioningStartDecision,
    val session: ConsoleCommissioningSessionView?,
) {
    init {
        require((decision == ConsoleCommissioningStartDecision.STARTED) == (session != null)) {
            "only a started commissioning decision may carry a session"
        }
    }
}

internal class ConsoleCommissioningReservation internal constructor(
    val commissioningId: String,
    val generation: Long,
    val operatorSessionId: String,
    allowedIntents: Set<ConsoleActuationIntent>,
    val requestedTtlMillis: Long,
) {
    val allowedIntents: Set<ConsoleActuationIntent> = immutableIntentSet(allowedIntents)
}

internal class ConsoleCommissioningReservationResult internal constructor(
    val refusal: ConsoleCommissioningStartDecision?,
    val reservation: ConsoleCommissioningReservation?,
) {
    init {
        require((refusal == null) == (reservation != null)) {
            "a commissioning reservation must have exactly one outcome"
        }
        require(refusal != ConsoleCommissioningStartDecision.STARTED) {
            "a reservation is not an effective commissioning session"
        }
    }
}

/**
 * Single-active, non-renewable hardware commissioning authority state machine.
 *
 * The class owns no clock, scheduler, audit, executor or transport. Callers provide monotonic time
 * and Core-owned operator-session truth. A start is explicitly split into reservation and commit so
 * required audit can complete before the generation becomes effective. State-changing methods only
 * commit, abort or revoke; expiry inspection and authorization checks are pure. Every method is
 * synchronized so the class remains fail-closed outside the ConsoleServerCore state lock.
 */
class ConsoleCommissioningLifecycle internal constructor(
    private val commissioningIdFactory: () -> String,
) {
    constructor() : this({ UUID.randomUUID().toString() })

    private var active: ActiveCommissioning? = null
    private var pending: ConsoleCommissioningReservation? = null
    private var generation: Long = 0L

    /** ID generation is intentionally outside every lifecycle/Core monitor. */
    internal fun generateCommissioningId(): String? =
        try {
            commissioningIdFactory().takeIf(COMMISSIONING_ID::matches)
        } catch (_: Exception) {
            null
        }

    @Synchronized
    internal fun reserveStart(
        commissioningId: String?,
        observation: ConsoleActuationObservation,
        operatorSessionId: String,
        operatorSessionIsOpen: Boolean,
        allowedIntents: Set<ConsoleActuationIntent>,
        ttlMillis: Long,
    ): ConsoleCommissioningReservationResult {
        val refusal =
            when {
                !OPERATOR_SESSION_ID.matches(operatorSessionId) ->
                    ConsoleCommissioningStartDecision.INVALID_OPERATOR_SESSION_ID
                !operatorSessionIsOpen ->
                    ConsoleCommissioningStartDecision.OPERATOR_SESSION_NOT_OPEN
                ttlMillis !in MINIMUM_TTL_MILLIS..MAXIMUM_TTL_MILLIS ->
                    ConsoleCommissioningStartDecision.INVALID_TTL
                allowedIntents.isEmpty() ->
                    ConsoleCommissioningStartDecision.EMPTY_INTENT_ALLOW_LIST
                !observation.isEligibleForG520Commissioning() ->
                    ConsoleCommissioningStartDecision.OBSERVATION_NOT_READY
                active != null || pending != null ->
                    ConsoleCommissioningStartDecision.SESSION_ALREADY_ACTIVE
                generation == Long.MAX_VALUE ->
                    ConsoleCommissioningStartDecision.GENERATION_EXHAUSTED
                commissioningId == null || !COMMISSIONING_ID.matches(commissioningId) ->
                    ConsoleCommissioningStartDecision.COMMISSIONING_ID_UNAVAILABLE
                else -> null
            }
        if (refusal != null) return refusedReservation(refusal)

        val nextGeneration = generation + 1L
        val intentSnapshot = immutableIntentSet(allowedIntents)
        val reservation =
            ConsoleCommissioningReservation(
                commissioningId = checkNotNull(commissioningId),
                generation = nextGeneration,
                operatorSessionId = operatorSessionId,
                allowedIntents = intentSnapshot,
                requestedTtlMillis = ttlMillis,
            )
        pending = reservation
        return ConsoleCommissioningReservationResult(null, reservation)
    }

    @Synchronized
    internal fun commit(
        reservation: ConsoleCommissioningReservation,
        nowNanos: Long,
    ): ConsoleCommissioningCommitResult {
        if (pending !== reservation || active != null || reservation.generation != generation + 1L) {
            return ConsoleCommissioningCommitResult(
                ConsoleCommissioningCommitDecision.STALE_RESERVATION,
                null,
            )
        }
        val committed =
            ActiveCommissioning(
                commissioningId = reservation.commissioningId,
                generation = reservation.generation,
                operatorSessionId = reservation.operatorSessionId,
                allowedIntents = reservation.allowedIntents,
                expiresAtMonotonicNanos = deadlineAfter(nowNanos, reservation.requestedTtlMillis),
            )
        active = committed
        generation = reservation.generation
        pending = null
        return ConsoleCommissioningCommitResult(
            ConsoleCommissioningCommitDecision.COMMITTED,
            committed.toView(),
        )
    }

    @Synchronized
    internal fun abort(
        reservation: ConsoleCommissioningReservation,
    ): ConsoleCommissioningAbortDecision {
        if (pending !== reservation) return ConsoleCommissioningAbortDecision.STALE_RESERVATION
        pending = null
        return ConsoleCommissioningAbortDecision.ABORTED
    }

    @Synchronized
    fun currentSession(): ConsoleCommissioningSessionView? = active?.toView()

    /**
     * Lease-level effective gate. Observation readiness alone can never authorize a lease.
     */
    @Synchronized
    fun allowsLease(
        observation: ConsoleActuationObservation,
        expectedSession: ConsoleCommissioningSessionView,
        operatorSessionId: String,
        nowNanos: Long,
    ): Boolean =
        currentMatches(expectedSession, operatorSessionId, nowNanos) &&
            observation.isEligibleForG520Commissioning()

    /**
     * Intent-level effective gate for admission and the final executor preflight. Requiring the
     * expected generation prevents a revoke/start ABA from authorizing work admitted earlier.
     */
    @Synchronized
    fun authorizes(
        observation: ConsoleActuationObservation,
        expectedSession: ConsoleCommissioningSessionView,
        operatorSessionId: String,
        intent: ConsoleActuationIntent,
        nowNanos: Long,
    ): Boolean =
        currentMatches(expectedSession, operatorSessionId, nowNanos) &&
            observation.isEligibleForG520Commissioning() &&
            intent in checkNotNull(active).allowedIntents

    /** Pure decision: stale or early scheduler callbacks leave the current generation untouched. */
    @Synchronized
    fun expiryDecision(
        expectedSession: ConsoleCommissioningSessionView,
        nowNanos: Long,
    ): ConsoleCommissioningExpiryDecision {
        val current = active ?: return ConsoleCommissioningExpiryDecision.NO_ACTIVE_SESSION
        if (!current.matches(expectedSession)) return ConsoleCommissioningExpiryDecision.STALE_SESSION
        return if (deadlineReached(nowNanos, current.expiresAtMonotonicNanos)) {
            ConsoleCommissioningExpiryDecision.DUE
        } else {
            ConsoleCommissioningExpiryDecision.NOT_DUE
        }
    }

    /** The only terminal state mutation. There is intentionally no renew or expand operation. */
    @Synchronized
    internal fun revoke(expectedSession: ConsoleCommissioningSessionView): ConsoleCommissioningRevokeDecision {
        val current = active ?: return ConsoleCommissioningRevokeDecision.NO_ACTIVE_SESSION
        if (!current.matches(expectedSession)) return ConsoleCommissioningRevokeDecision.STALE_SESSION
        active = null
        return ConsoleCommissioningRevokeDecision.REVOKED
    }

    private fun currentMatches(
        expectedSession: ConsoleCommissioningSessionView,
        operatorSessionId: String,
        nowNanos: Long,
    ): Boolean {
        val current = active ?: return false
        return current.matches(expectedSession) &&
            current.operatorSessionId == operatorSessionId &&
            !deadlineReached(nowNanos, current.expiresAtMonotonicNanos)
    }

    private fun refusedReservation(decision: ConsoleCommissioningStartDecision) =
        ConsoleCommissioningReservationResult(decision, null)

    private class ActiveCommissioning(
        val commissioningId: String,
        val generation: Long,
        val operatorSessionId: String,
        val allowedIntents: Set<ConsoleActuationIntent>,
        val expiresAtMonotonicNanos: Long,
    ) {
        fun matches(expected: ConsoleCommissioningSessionView): Boolean =
            commissioningId == expected.commissioningId &&
                generation == expected.generation &&
                operatorSessionId == expected.operatorSessionId &&
                allowedIntents == expected.allowedIntents &&
                expiresAtMonotonicNanos == expected.expiresAtMonotonicNanos

        fun toView() =
            ConsoleCommissioningSessionView(
                commissioningId = commissioningId,
                generation = generation,
                operatorSessionId = operatorSessionId,
                allowedIntents = allowedIntents,
                expiresAtMonotonicNanos = expiresAtMonotonicNanos,
            )
    }

    companion object {
        const val MINIMUM_TTL_MILLIS = 1_000L
        const val MAXIMUM_TTL_MILLIS = 300_000L

        private val OPERATOR_SESSION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
        private val COMMISSIONING_ID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

private fun immutableIntentSet(intents: Set<ConsoleActuationIntent>): Set<ConsoleActuationIntent> =
    if (intents.isEmpty()) {
        emptySet()
    } else {
        Collections.unmodifiableSet(EnumSet.copyOf(intents))
    }

/** Monotonic arithmetic remains correct across Long wrap for this lifecycle's bounded 5m TTL. */
private fun deadlineAfter(nowNanos: Long, ttlMillis: Long): Long =
    nowNanos + ttlMillis * NANOS_PER_MILLI

private fun deadlineReached(nowNanos: Long, deadlineNanos: Long): Boolean =
    nowNanos - deadlineNanos >= 0L

private fun remainingMillis(deadlineNanos: Long, nowNanos: Long): Long {
    if (deadlineReached(nowNanos, deadlineNanos)) return 0L
    val remainingNanos = deadlineNanos - nowNanos
    return remainingNanos / NANOS_PER_MILLI +
        if (remainingNanos % NANOS_PER_MILLI == 0L) 0L else 1L
}
