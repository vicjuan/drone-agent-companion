package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.companion.console.protocol.FlightState
import com.durendal.droneagent.companion.console.server.AdmittedDiscreteCommand
import com.durendal.droneagent.companion.console.server.ConsoleAircraftActionPort
import com.durendal.droneagent.companion.console.server.ConsoleDiscreteAction
import com.durendal.droneagent.companion.console.server.ConsoleExecutionResult
import java.util.concurrent.atomic.AtomicReference

/**
 * Companion-owned, observable RTH simulation seam.
 *
 * The vendor adapter-mock has no return-to-home action port. This class therefore does not pretend
 * to be a vendor side effect: it records an explicit companion simulation state which the mock
 * snapshot provider exposes as RETURNING_HOME. Hardware composition must replace this port.
 */
class ObservableMockReturnToHomePort : ConsoleAircraftActionPort {
    private val state = AtomicReference<State>(State.Idle)

    override fun execute(
        command: AdmittedDiscreteCommand,
        callback: (ConsoleExecutionResult) -> Unit,
    ) {
        if (command.command.action != ConsoleDiscreteAction.RETURN_TO_HOME) {
            callback(ConsoleExecutionResult(false, "mock_rth_wrong_action"))
            return
        }
        state.set(State.ReturningHome(command.command.commandId))
        callback(
            ConsoleExecutionResult(
                succeeded = true,
                detail =
                    "Companion-owned RTH simulation entered RETURNING_HOME; " +
                        "vendor adapter-mock has no RTH action port.",
            ),
        )
    }

    fun clear() {
        state.set(State.Idle)
    }

    fun flightStateOverride(): FlightState? =
        when (state.get()) {
            State.Idle -> null
            is State.ReturningHome -> FlightState.RETURNING_HOME
        }

    fun activeCommandId(): String? = (state.get() as? State.ReturningHome)?.commandId

    private sealed interface State {
        data object Idle : State
        data class ReturningHome(val commandId: String) : State
    }
}
