package com.durendal.droneagent.companion.console.runner

import com.durendal.droneagent.adapter.mock.MockDroneAgent

object ConsoleRunnerProfile {
    const val BIND_HOST: String = "127.0.0.1"
    const val BIND_PORT: Int = 8080
}

/** Composition-root placeholder; the actual server lifecycle is issue #3. */
fun main() {
    println(
        "[console-runner] workspace scaffold (${MockDroneAgent::class.simpleName}); " +
            "HTTP/WebSocket runtime is not implemented yet",
    )
}
