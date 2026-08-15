package com.durendal.droneagent.companion.host

import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

enum class LifecycleEvent(val wireName: String) {
    BOOT_RECEIVED("boot_received"),
    BOOT_START_REJECTED("boot_start_rejected"),
    FOREGROUND_STARTED("foreground_started"),
    FORCE_STOP_RECOVERY_UNAVAILABLE("force_stop_recovery_unavailable"),
    SERVICE_START_REQUESTED("service_start_requested"),
    STICKY_RESTART_OBSERVED("sticky_restart_observed"),
    PROCESS_RECREATED("process_recreated"),
    RESTART_TRACKER_FAILED("restart_tracker_failed"),
    RUNTIME_START_REQUESTED("runtime_start_requested"),
    RUNTIME_FACTORY_UNAVAILABLE("runtime_factory_unavailable"),
    RUNTIME_FACTORY_FAILED("runtime_factory_failed"),
    RUNTIME_STARTED("runtime_started"),
    RUNTIME_START_FAILED("runtime_start_failed"),
    SAFE_STOP_CLOSE_ATTEMPTED("safe_stop_close_attempted"),
    RUNTIME_CLOSED("runtime_closed"),
    RUNTIME_CLOSE_TIMED_OUT("runtime_close_timed_out"),
    RUNTIME_CLOSE_FAILED("runtime_close_failed"),
    SAFE_STOP_COMPLETED("safe_stop_completed"),
    SAFE_STOP_INCOMPLETE("safe_stop_incomplete"),
    SERVICE_DESTROYED("service_destroyed"),
}

enum class LifecycleTrigger(val wireName: String) {
    NONE("none"),
    LOCKED_BOOT_COMPLETED("locked_boot_completed"),
    BOOT_COMPLETED("boot_completed"),
    PACKAGE_REPLACED("package_replaced"),
    EXPLICIT_START("explicit_start"),
    STICKY_RESTART("sticky_restart"),
    SAFE_STOP("safe_stop"),
    DESTROY("destroy"),
}

fun interface DurableLifecycleEvidence {
    @Throws(IOException::class)
    fun record(event: LifecycleEvent, trigger: LifecycleTrigger)
}

/**
 * A deliberately small, bounded JSONL journal in device-protected storage.
 *
 * Every record is made from enums plus time/PID only. There is no free-form
 * field where aircraft serials, credentials, network addresses, or exception
 * messages could accidentally enter lifecycle evidence.
 */
class LifecycleEvidenceJournal(
    directory: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pid: () -> Int = Process::myPid,
) : DurableLifecycleEvidence {
    private val directory = directory.absoluteFile
    private val currentFile = File(this.directory, CURRENT_FILE_NAME)
    private val previousFile = File(this.directory, PREVIOUS_FILE_NAME)
    private val lockFile = File(this.directory, LOCK_FILE_NAME)

    init {
        require(maxFileBytes >= MIN_MAX_FILE_BYTES) {
            "maxFileBytes must be at least $MIN_MAX_FILE_BYTES"
        }
    }

    @Throws(IOException::class)
    override fun record(event: LifecycleEvent, trigger: LifecycleTrigger) {
        val bytes = encode(event, trigger).toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= maxFileBytes) { "lifecycle record exceeds journal bound" }

        synchronized(IO_LOCK) {
            ensureDirectory()
            validateLockFile()
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { lockChannel ->
                lockChannel.lock().use {
                    validateBoundedFile(currentFile)
                    validateBoundedFile(previousFile)
                    if (currentFile.exists() && currentFile.length() + bytes.size > maxFileBytes) {
                        rotate()
                    }
                    FileOutputStream(currentFile, true).use { output ->
                        output.write(bytes)
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
        }
    }

    private fun encode(event: LifecycleEvent, trigger: LifecycleTrigger): String =
        "{\"schema\":1,\"event\":\"${event.wireName}\",\"trigger\":\"${trigger.wireName}\"," +
            "\"epoch_ms\":${clock()},\"pid\":${pid()}}\n"

    @Throws(IOException::class)
    private fun ensureDirectory() {
        if (directory.exists()) {
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
                throw IOException("lifecycle journal path is not a real directory")
            }
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create lifecycle journal directory")
        }
    }

    @Throws(IOException::class)
    private fun validateLockFile() {
        if (
            lockFile.exists() &&
                (!lockFile.isFile || Files.isSymbolicLink(lockFile.toPath()) || lockFile.length() != 0L)
        ) {
            throw IOException("lifecycle journal lock file is invalid")
        }
    }

    @Throws(IOException::class)
    private fun rotate() {
        if (previousFile.exists() && !previousFile.delete()) {
            throw IOException("cannot remove previous lifecycle journal")
        }
        if (currentFile.exists() && !currentFile.renameTo(previousFile)) {
            throw IOException("cannot rotate lifecycle journal")
        }
    }

    @Throws(IOException::class)
    private fun validateBoundedFile(file: File) {
        if (!file.exists()) return
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > maxFileBytes) {
            throw IOException("lifecycle journal file violates its bound")
        }
    }

    companion object {
        const val CURRENT_FILE_NAME = "lifecycle.jsonl"
        const val PREVIOUS_FILE_NAME = "lifecycle.previous.jsonl"
        internal const val LOCK_FILE_NAME = ".lifecycle.lock"
        const val DEFAULT_MAX_FILE_BYTES = 128L * 1024L
        internal const val MIN_MAX_FILE_BYTES = 256L

        private val IO_LOCK = Any()
    }
}
