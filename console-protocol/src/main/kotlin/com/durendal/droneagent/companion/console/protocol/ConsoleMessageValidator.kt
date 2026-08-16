package com.durendal.droneagent.companion.console.protocol

import java.time.LocalDate

/** Semantic validation performed after strict JSON shape decoding. */
object ConsoleMessageValidator {
    private val idPattern =
        Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,${ConsoleProtocolModule.MAX_IDENTIFIER_LENGTH - 1}}$")
    private val capabilityIdPattern = Regex("^[a-z][a-z0-9_]*$")
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val isoDatePattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val canonicalDecimalPattern = Regex("^(?:0|[1-9][0-9]{0,18})$")
    private val uuidV4Pattern =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun validate(message: ConsoleClientMessage) =
        validate(message, ConsoleProtocolModule.PROTOCOL_VERSION)

    fun validate(
        message: ConsoleClientMessage,
        protocolVersion: String,
    ) {
        requireId(message.messageId)
        validate(message.payload, protocolVersion)
    }

    fun validate(message: ConsoleServerMessage) =
        validate(message, ConsoleProtocolModule.PROTOCOL_VERSION)

    fun validate(
        message: ConsoleServerMessage,
        protocolVersion: String,
    ) {
        requireId(message.messageId)
        validate(message.payload, protocolVersion)
    }

    fun validate(payload: ConsoleClientPayload) =
        validate(payload, ConsoleProtocolModule.PROTOCOL_VERSION)

    fun validate(
        payload: ConsoleClientPayload,
        protocolVersion: String,
    ) {
        requirePayloadAvailable(payload.messageType(), protocolVersion)
        when (payload) {
            is ClientHelloPayload -> {
                require(protocolVersion == ConsoleProtocolModule.BOOTSTRAP_PROTOCOL_VERSION)
                requireName(payload.clientName)
                requireName(payload.clientVersion)
                require(
                    payload.supportedProtocolVersions.size in
                        1..ConsoleProtocolModule.MAX_NEGOTIATION_VALUES,
                )
                require(payload.supportedProtocolVersions.size == payload.supportedProtocolVersions.toSet().size)
                require(payload.supportedProtocolVersions.all(::isValidName))
                require(ConsoleProtocolModule.PROTOCOL_VERSION in payload.supportedProtocolVersions)
                payload.authentication?.let {
                    requireName(it.scheme)
                    require(it.credential.isNotBlank())
                    require(it.credential.length <= ConsoleProtocolModule.MAX_CREDENTIAL_LENGTH)
                }
            }

            is LeaseAcquirePayload -> requireLeaseTtl(payload.requestedTtlMs)
            is LeaseRenewPayload -> {
                requireId(payload.leaseId)
                requireLeaseTtl(payload.requestedTtlMs)
            }

            is LeaseReleasePayload -> requireId(payload.leaseId)
            is DiscreteCommandRequestPayload -> {
                requireId(payload.commandId)
                requireId(payload.leaseId)
                require(
                    payload.ttlMs in
                        ConsoleProtocolModule.MIN_COMMAND_TTL_MS..ConsoleProtocolModule.MAX_COMMAND_TTL_MS,
                )
            }

            is ControlFramePayload -> {
                requireId(payload.leaseId)
                requirePositiveSafeInteger(payload.inputSequence)
                require(
                    payload.ttlMs in
                        ConsoleProtocolModule.MIN_CONTROL_TTL_MS..ConsoleProtocolModule.MAX_CONTROL_TTL_MS,
                )
                requireNormalizedAxis(payload.forward)
                requireNormalizedAxis(payload.right)
                requireNormalizedAxis(payload.up)
                requireNormalizedAxis(payload.yaw)
            }

            is ControlNeutralPayload -> {
                requireId(payload.leaseId)
                requirePositiveSafeInteger(payload.inputSequence)
            }
        }
    }

    fun validate(payload: ConsoleServerPayload) =
        validate(payload, ConsoleProtocolModule.PROTOCOL_VERSION)

    fun validate(
        payload: ConsoleServerPayload,
        protocolVersion: String,
    ) {
        requirePayloadAvailable(payload.messageType(), protocolVersion)
        when (payload) {
            is ServerHelloPayload -> {
                requireId(payload.sessionId)
                requireName(payload.serverVersion)
                require(payload.selectedProtocolVersion == protocolVersion)
                require(
                    payload.acceptedAuthenticationSchemes.size <=
                        ConsoleProtocolModule.MAX_NEGOTIATION_VALUES,
                )
                require(
                    payload.acceptedAuthenticationSchemes.size ==
                        payload.acceptedAuthenticationSchemes.toSet().size,
                )
                require(payload.acceptedAuthenticationSchemes.all(::isValidName))
                require(!payload.authenticationRequired || payload.acceptedAuthenticationSchemes.isNotEmpty())
            }

            is RuntimeStatePayload -> Unit
            is CommissioningAuthorityStatePayload -> validateCommissioningAuthorityState(payload)
            is TelemetryPayload -> {
                requirePositiveSafeInteger(payload.sequence)
                payload.batteryPercent?.let { require(it in 0..100) }
                payload.latitude?.let { requireFiniteRange(it, -90.0, 90.0) }
                payload.longitude?.let { requireFiniteRange(it, -180.0, 180.0) }
                payload.altitudeM?.let { requireFiniteRange(it, -1_000.0, 10_000.0) }
                payload.gimbalPitchDeg?.let { requireFiniteRange(it, -180.0, 180.0) }
            }

            is CapabilitySnapshotPayload -> {
                requireName(payload.matrixId)
                require(payload.matrixId == ConsoleProtocolModule.G520_MATRIX_ID)
                require(payload.schemaVersion == 1)
                require(isoDatePattern.matches(payload.lastUpdated))
                LocalDate.parse(payload.lastUpdated)
                require(sha256Pattern.matches(payload.sourceDigestSha256))
                require(payload.rows.size in 1..ConsoleProtocolModule.MAX_CAPABILITY_ROWS)
                require(payload.rows.map(CapabilitySnapshotRow::id).toSet().size == payload.rows.size)
                payload.rows.forEach { row ->
                    require(row.id.length <= ConsoleProtocolModule.MAX_NAME_LENGTH)
                    require(capabilityIdPattern.matches(row.id))
                    requireText(row.assessment)
                }
            }

            is HealthPayload -> {
                requireSafeNonNegativeInteger(payload.uptimeMs)
                requireNullableNonBlank(payload.detail)
            }

            is LeaseStatePayload -> {
                payload.requestMessageId?.let(::requireId)
                payload.leaseId?.let(::requireId)
                payload.holderSessionId?.let(::requireId)
                payload.expiresInMs?.let(::requireSafeNonNegativeInteger)
                requireNullableNonBlank(payload.reason)
                when (payload.state) {
                    LeaseState.AVAILABLE -> {
                        require(payload.leaseId == null)
                        require(payload.holderSessionId == null)
                        require(payload.expiresInMs == null)
                        require(payload.reason == null)
                    }

                    LeaseState.HELD -> {
                        require(payload.leaseId != null)
                        require(payload.holderSessionId != null)
                        require(payload.expiresInMs != null)
                        require(payload.reason == null)
                    }

                    LeaseState.DENIED -> {
                        require(payload.leaseId == null)
                        require(payload.holderSessionId == null)
                        require(payload.expiresInMs == null)
                        require(payload.reason != null)
                    }

                    LeaseState.RELEASED -> {
                        require(payload.leaseId != null)
                        require(payload.holderSessionId == null)
                        require(payload.expiresInMs == null)
                        require(payload.reason == null)
                    }

                    LeaseState.EXPIRED -> {
                        require(payload.leaseId != null)
                        require(payload.holderSessionId == null)
                        require(payload.expiresInMs == null)
                        require(payload.reason != null)
                    }
                }
            }

            is CommandAckPayload -> {
                requireId(payload.commandId)
                require(sha256Pattern.matches(payload.intentDigestSha256))
                requireNullableNonBlank(payload.reason)
                require((payload.decision == CommandDecision.ACCEPTED) == (payload.reason == null))
            }

            is CommandResultPayload -> {
                requireId(payload.commandId)
                requireNullableNonBlank(payload.reason)
                requireNullableNonBlank(payload.detail)
                require((payload.status == CommandResultStatus.SUCCEEDED) == (payload.reason == null))
            }

            is ControlAckPayload -> {
                requireId(payload.leaseId)
                requirePositiveSafeInteger(payload.inputSequence)
                requireNullableNonBlank(payload.reason)
                require((payload.status == ControlAckStatus.APPLIED) == (payload.reason == null))
            }

            is SafetyEventPayload -> {
                payload.leaseId?.let(::requireId)
                payload.lastInputSequence?.let(::requirePositiveSafeInteger)
                requireNullableNonBlank(payload.detail)
                require(payload.outcome != SafetyOutcome.FAILED || payload.detail != null)
            }

            is ProtocolErrorPayload -> {
                payload.relatedMessageId?.let(::requireId)
                requireNullableNonBlank(payload.detail)
            }
        }
    }

    fun requireMessageId(value: String) = requireId(value)

    private fun validateCommissioningAuthorityState(payload: CommissioningAuthorityStatePayload) {
        val stateRevision = requireCanonicalDecimal(payload.stateRevision)
        val generation = requireCanonicalDecimal(payload.generation)
        require(payload.allowedIntents == payload.allowedIntents.distinct().sortedBy { it.ordinal })

        when (payload.state) {
            CommissioningAuthorityState.ACTIVE -> {
                require(stateRevision >= 1)
                requireUuidV4(payload.commissioningId)
                require(generation >= 1)
                require(payload.allowedIntents.isNotEmpty())
                val expiresInMs = requireNotNull(payload.expiresInMs)
                require(
                    expiresInMs in
                        ConsoleProtocolModule.MIN_COMMISSIONING_AUTHORITY_REMAINING_TTL_MS..
                        ConsoleProtocolModule.MAX_COMMISSIONING_AUTHORITY_REMAINING_TTL_MS,
                )
                require(payload.reason == null)
            }

            CommissioningAuthorityState.INACTIVE -> {
                require(payload.allowedIntents.isEmpty())
                require(payload.expiresInMs == null)
                if (payload.reason == CommissioningAuthorityReason.NO_ACTIVE_SESSION) {
                    require(payload.commissioningId == null)
                    require(generation == 0L)
                } else {
                    require(stateRevision >= 1)
                    requireUuidV4(payload.commissioningId)
                    require(generation >= 1)
                    require(payload.reason != null)
                }
            }
        }
    }

    private fun requirePayloadAvailable(
        type: ConsoleMessageType,
        protocolVersion: String,
    ) {
        require(ConsoleProtocolModule.isSupportedProtocolVersion(protocolVersion))
        require(type.isAvailableIn(protocolVersion))
    }

    private fun requireCanonicalDecimal(value: String): Long {
        require(canonicalDecimalPattern.matches(value))
        return requireNotNull(value.toLongOrNull())
    }

    private fun requireUuidV4(value: String?) {
        require(value != null && uuidV4Pattern.matches(value))
    }

    private fun requireLeaseTtl(value: Int) {
        require(
            value in
                ConsoleProtocolModule.MIN_LEASE_TTL_MS..ConsoleProtocolModule.MAX_LEASE_TTL_MS,
        )
    }

    private fun requireId(value: String) {
        require(idPattern.matches(value))
    }

    private fun requireName(value: String) {
        require(isValidName(value))
    }

    private fun isValidName(value: String): Boolean =
        value.isNotBlank() && value.length <= ConsoleProtocolModule.MAX_NAME_LENGTH

    private fun requireText(value: String) {
        require(value.isNotBlank() && value.length <= ConsoleProtocolModule.MAX_TEXT_LENGTH)
    }

    private fun requireNullableNonBlank(value: String?) {
        require(
            value == null ||
                (value.isNotBlank() && value.length <= ConsoleProtocolModule.MAX_TEXT_LENGTH),
        )
    }

    private fun requirePositiveSafeInteger(value: Long) {
        require(value in 1..ConsoleProtocolModule.MAX_SAFE_INTEGER)
    }

    private fun requireSafeNonNegativeInteger(value: Long) {
        require(value in 0..ConsoleProtocolModule.MAX_SAFE_INTEGER)
    }

    private fun requireNormalizedAxis(value: Double) {
        requireFiniteRange(value, -1.0, 1.0)
    }

    private fun requireFiniteRange(value: Double, minimum: Double, maximum: Double) {
        require(value.isFinite() && value in minimum..maximum)
    }
}
