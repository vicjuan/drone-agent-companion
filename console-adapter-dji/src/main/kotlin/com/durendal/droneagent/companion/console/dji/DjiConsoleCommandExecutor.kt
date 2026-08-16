package com.durendal.droneagent.companion.console.dji

import com.durendal.droneagent.adapter.dji.DjiDroneAgent
import com.durendal.droneagent.companion.console.server.AdmittedControlFrame
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleCommandExecutor
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import com.durendal.droneagent.companion.console.server.ConsoleMonotonicClock
import com.durendal.droneagent.companion.console.server.ConsoleSafetyTrigger
import java.util.concurrent.CopyOnWriteArrayList

fun interface DjiActuationReadinessListener {
    fun onReadinessChanged()
}

/** Read-only readiness notification consumed by the DJI host composition. */
interface DjiActuationReadinessSource {
    fun isReady(): Boolean

    fun addReadinessListener(listener: DjiActuationReadinessListener)

    fun removeReadinessListener(listener: DjiActuationReadinessListener)
}

/**
 * Temporary fail-closed implementation while physical-aircraft actuation awaits explicit approval.
 *
 * It exists only so the rest of the G520 production composition can be compiled and reviewed. No
 * method touches [DjiDroneAgent.actuationPorts], readiness is permanently false, and Core therefore
 * cannot create an effective hardware commissioning authority or reach a DJI command port.
 */
class DjiConsoleCommandExecutor(
    @Suppress("UNUSED_PARAMETER") agent: DjiDroneAgent,
    @Suppress("UNUSED_PARAMETER") monotonicClock: ConsoleMonotonicClock,
) : ConsoleCommandExecutor, DjiActuationReadinessSource, AutoCloseable {
    private val listeners = CopyOnWriteArrayList<DjiActuationReadinessListener>()

    override fun isReady(): Boolean = false

    override fun addReadinessListener(listener: DjiActuationReadinessListener) {
        listeners += listener
        listener.onReadinessChanged()
    }

    override fun removeReadinessListener(listener: DjiActuationReadinessListener) {
        listeners -= listener
    }

    override fun executeDiscrete(
        command: AdmittedDiscreteCommand,
        callback: (ConsoleExecutionResult) -> Unit,
    ) = callback(ConsoleExecutionResult(false, "dji_actuation_not_approved"))

    override fun submitControl(
        frame: AdmittedControlFrame,
        callback: (ConsoleExecutionResult) -> Unit,
    ) = callback(ConsoleExecutionResult(false, "dji_actuation_not_approved"))

    override fun neutralize(
        leaseId: String,
        controlEpoch: Long,
        trigger: ConsoleSafetyTrigger,
        callback: (ConsoleExecutionResult) -> Unit,
    ) = callback(ConsoleExecutionResult(true))

    override fun close() {
        listeners.clear()
    }
}
