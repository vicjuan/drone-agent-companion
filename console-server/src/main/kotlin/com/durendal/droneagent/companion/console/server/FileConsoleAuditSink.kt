package com.durendal.droneagent.companion.console.server

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * Append-only, forced-to-disk JSONL audit used by the runner and headless host.
 *
 * Construction fails when the destination is a symlink. A required audit write propagates any
 * I/O failure back to [ConsoleServerCore], so command/control dispatch remains fail closed.
 */
class FileConsoleAuditSink(
    path: Path,
) : ConsoleAuditSink, AutoCloseable {
    private val lock = Any()
    private val auditPath = path.toAbsolutePath().normalize()
    private val channel: FileChannel
    private var closed = false

    init {
        require(!Files.isSymbolicLink(auditPath)) { "audit path must not be a symbolic link" }
        val parent = requireNotNull(auditPath.parent) { "audit path must have a parent directory" }
        Files.createDirectories(parent)
        val opened =
            FileChannel.open(
                auditPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            )
        try {
            setOwnerOnlyPermissionsWhenSupported(auditPath)
        } catch (failure: Exception) {
            opened.close()
            throw failure
        }
        channel = opened
    }

    override fun record(event: ConsoleAuditEvent) {
        validate(event)
        val bytes = (encode(event) + "\n").toByteArray(StandardCharsets.UTF_8)
        synchronized(lock) {
            check(!closed) { "console audit sink is closed" }
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            channel.force(true)
            channel.close()
        }
    }

    private fun validate(event: ConsoleAuditEvent) {
        require(event.timestampEpochMillis >= 0L) { "audit epoch timestamp must be non-negative" }
        requireBounded(event.sessionId, MAX_ID_LENGTH, "sessionId")
        requireBounded(event.leaseId, MAX_ID_LENGTH, "leaseId")
        requireBounded(event.subjectId, MAX_ID_LENGTH, "subjectId")
        event.intentDigestSha256?.let { digest ->
            require(SHA256.matches(digest)) { "intent digest must be lowercase SHA-256" }
        }
        requireBounded(event.authorityDecisionId, MAX_ID_LENGTH, "authorityDecisionId")
        requireBounded(event.outcome, MAX_REASON_LENGTH, "outcome", required = true)
        requireBounded(event.reason, MAX_REASON_LENGTH, "reason")
        requireBounded(event.detail, MAX_DETAIL_LENGTH, "detail")
        require(
            event.clientRequestReason == null ||
                event.clientRequestReason in CLIENT_REQUEST_REASONS
        ) { "clientRequestReason must be null or a canonical neutral reason" }
    }

    private fun requireBounded(
        value: String?,
        maximumLength: Int,
        name: String,
        required: Boolean = false,
    ) {
        require(!required || !value.isNullOrBlank()) { "$name must not be blank" }
        require(value == null || (value.isNotBlank() && value.length <= maximumLength)) {
            "$name must be null or non-blank and at most $maximumLength characters"
        }
    }

    private fun encode(event: ConsoleAuditEvent): String =
        buildString(512) {
            append('{')
            field("schemaVersion", 1L)
            append(',')
            field("kind", event.kind.name.lowercase())
            append(',')
            field("timestampEpochMillis", event.timestampEpochMillis)
            append(',')
            field("monotonicNanos", event.monotonicNanos)
            append(',')
            nullableField("sessionId", event.sessionId)
            append(',')
            nullableField("leaseId", event.leaseId)
            append(',')
            nullableField("subjectId", event.subjectId)
            append(',')
            nullableField("intentDigestSha256", event.intentDigestSha256)
            append(',')
            nullableField("authorityDecisionId", event.authorityDecisionId)
            append(',')
            field("outcome", event.outcome)
            append(',')
            nullableField("reason", event.reason)
            append(',')
            nullableField("detail", event.detail)
            append(',')
            nullableField("clientRequestReason", event.clientRequestReason)
            append('}')
        }

    private fun StringBuilder.field(
        name: String,
        value: String,
    ) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.field(
        name: String,
        value: Long,
    ) {
        appendJsonString(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.nullableField(
        name: String,
        value: String?,
    ) {
        appendJsonString(name)
        append(':')
        if (value == null) append("null") else appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

    private fun setOwnerOnlyPermissionsWhenSupported(path: Path) {
        // Android's default java.nio provider deliberately throws SecurityException from
        // Files.getFileStore(Path), even for app-private storage. Query the attribute view on the
        // file itself instead; providers without POSIX permissions return null.
        Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.setPermissions(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }

    private companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_REASON_LENGTH = 128
        const val MAX_DETAIL_LENGTH = 1_024
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val CLIENT_REQUEST_REASONS =
            ConsoleNeutralRequestReason.entries.mapTo(mutableSetOf()) { it.name.lowercase() }
    }
}
