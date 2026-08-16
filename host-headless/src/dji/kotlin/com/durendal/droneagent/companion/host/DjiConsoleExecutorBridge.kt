package com.durendal.droneagent.companion.host

import com.durendal.droneagent.companion.console.dji.DjiActuationReadinessListener
import com.durendal.droneagent.companion.console.dji.DjiActuationReadinessSource
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor

/**
 * Adapts the Android-library executor readiness contract into the host-local runtime seam.
 *
 * Command execution remains delegated unchanged. Listener identities are retained exactly so the
 * runtime can remove its readiness callback before the outer platform releases the DJI agent.
 */
internal class DjiConsoleExecutorBridge(
    private val executor: ConsoleCommandExecutor,
    private val readiness: DjiActuationReadinessSource,
) : ConsoleCommandExecutor by executor, DjiConsoleActuationReadiness, AutoCloseable {
    constructor(executor: ConsoleCommandExecutor) : this(
        executor = executor,
        readiness =
            requireNotNull(executor as? DjiActuationReadinessSource) {
                "DJI console executor must expose evented actuation readiness"
            },
    )

    private val lock = Any()
    private val listenerBridges =
        mutableMapOf<DjiConsoleActuationReadinessListener, DjiActuationReadinessListener>()
    private var closed = false

    override fun isReady(): Boolean = synchronized(lock) {
        !closed && readiness.isReady()
    }

    override fun addListener(listener: DjiConsoleActuationReadinessListener) {
        synchronized(lock) {
            check(!closed) { "DJI console executor bridge is closed" }
            check(listener !in listenerBridges) { "actuation-readiness listener is already added" }
            val bridge =
                DjiActuationReadinessListener(listener::onActuationReadinessChanged)
            listenerBridges[listener] = bridge
            try {
                // The adapter deliberately publishes its current value synchronously here. The
                // monitor is reentrant, so that callback can safely call isReady() immediately.
                readiness.addReadinessListener(bridge)
            } catch (failure: Throwable) {
                listenerBridges.remove(listener)
                runCatching { readiness.removeReadinessListener(bridge) }
                throw failure
            }
        }
    }

    override fun removeListener(listener: DjiConsoleActuationReadinessListener) {
        synchronized(lock) {
            listenerBridges.remove(listener)?.let(readiness::removeReadinessListener)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            var firstFailure: Throwable? = null
            listenerBridges.values.toList().forEach { bridge ->
                runCatching { readiness.removeReadinessListener(bridge) }
                    .onFailure { failure ->
                        if (firstFailure == null) firstFailure = failure
                    }
            }
            listenerBridges.clear()
            runCatching { (executor as? AutoCloseable)?.close() }
                .onFailure { failure ->
                    if (firstFailure == null) firstFailure = failure
                }
            firstFailure?.let { throw it }
        }
    }
}
