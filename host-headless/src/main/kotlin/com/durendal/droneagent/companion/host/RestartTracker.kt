package com.durendal.droneagent.companion.host

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class RestartObservation(
    val generation: Long,
    val previousPid: Int?,
    val processChanged: Boolean,
    val stickyRestart: Boolean,
)

/**
 * Persists only a local generation and PID. It is lifecycle evidence, not an
 * identity store, and intentionally has no device/aircraft/network fields.
 */
class RestartTracker(private val stateFile: File) {
    @Throws(IOException::class)
    fun observe(currentPid: Int, stickyRestart: Boolean): RestartObservation {
        require(currentPid > 0) { "currentPid must be positive" }
        synchronized(IO_LOCK) {
            val previous = readState()
            val nextGeneration = Math.addExact(previous?.generation ?: 0L, 1L)
            writeState(State(nextGeneration, currentPid))
            return RestartObservation(
                generation = nextGeneration,
                previousPid = previous?.pid,
                processChanged = previous != null && previous.pid != currentPid,
                stickyRestart = stickyRestart,
            )
        }
    }

    @Throws(IOException::class)
    private fun readState(): State? {
        if (!stateFile.exists()) return null
        if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) {
            throw IOException("restart state is invalid")
        }
        val values =
            stateFile.readLines(StandardCharsets.UTF_8).associate { line ->
                val separator = line.indexOf('=')
                if (separator <= 0 || separator == line.lastIndex) {
                    throw IOException("restart state is malformed")
                }
                line.substring(0, separator) to line.substring(separator + 1)
            }
        if (values.keys != REQUIRED_KEYS || values["version"] != STATE_VERSION) {
            throw IOException("restart state schema is unsupported")
        }
        val generation = values["generation"]?.toLongOrNull()
            ?: throw IOException("restart generation is malformed")
        val pid = values["pid"]?.toIntOrNull()
            ?: throw IOException("restart pid is malformed")
        if (generation <= 0L || pid <= 0) throw IOException("restart state is out of range")
        return State(generation, pid)
    }

    @Throws(IOException::class)
    private fun writeState(state: State) {
        val parent = stateFile.absoluteFile.parentFile
            ?: throw IOException("restart state has no parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create restart state directory")
        }
        if (!parent.isDirectory) throw IOException("restart state parent is not a directory")

        val staging = File(parent, "${stateFile.name}.staging")
        val bytes =
            "version=$STATE_VERSION\ngeneration=${state.generation}\npid=${state.pid}\n"
                .toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_STATE_BYTES) throw IOException("restart state exceeds bound")

        FileOutputStream(staging, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                staging.toPath(),
                stateFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            staging.delete()
            throw IOException("restart state requires an atomic rename", unsupported)
        } catch (failure: IOException) {
            staging.delete()
            throw failure
        }
    }

    private data class State(val generation: Long, val pid: Int)

    companion object {
        const val FILE_NAME = "restart-state.v1"
        private const val STATE_VERSION = "1"
        private const val MAX_STATE_BYTES = 256L
        private val REQUIRED_KEYS = setOf("version", "generation", "pid")
        private val IO_LOCK = Any()
    }
}
