package com.durendal.droneagent.companion.console.protocol

/** Stable identity and wire limits for the companion-owned browser protocol. */
object ConsoleProtocolModule {
    const val NAME: String = "companion-console"
    const val PROTOCOL_VERSION: String = "1.0"
    const val G520_MATRIX_ID: String = "mini4pro-rcn3-g520-android"
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    const val MIN_CONTROL_TTL_MS: Int = 50
    const val MAX_CONTROL_TTL_MS: Int = 1_000
    const val MIN_COMMAND_TTL_MS: Int = 100
    const val MAX_COMMAND_TTL_MS: Int = 30_000
    const val MIN_LEASE_TTL_MS: Int = 500
    const val MAX_LEASE_TTL_MS: Int = 30_000

    const val MAX_NAME_LENGTH: Int = 128
    const val MAX_IDENTIFIER_LENGTH: Int = 64
    const val MAX_CREDENTIAL_LENGTH: Int = 4_096
    const val MAX_TEXT_LENGTH: Int = 1_024
    const val MAX_NEGOTIATION_VALUES: Int = 16
    const val MAX_CAPABILITY_ROWS: Int = 256
    const val MAX_WIRE_MESSAGE_UTF8_BYTES: Int = 65_536
    const val MAX_JSON_NESTING_DEPTH: Int = 16
}
