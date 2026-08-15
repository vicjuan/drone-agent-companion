# Headless Android host lifecycle

This application intentionally has no `Activity`. `BootCompletedReceiver` starts
`HeadlessAgentService`, which becomes a `connectedDevice` foreground service before
performing journal, asset, server, or adapter work. Runtime composition is supplied by
an `Application` implementing `HeadlessRuntimeFactoryOwner`.

The emulator mock binds device loopback port `8080`, but its sole admitted browser origin is
`http://127.0.0.1:18080`; the lifecycle runbook creates that exact host-to-device adb forward.
This keeps the browser boundary deterministic without taking over unrelated Mac services on 8080.

The app-owned **Safe stop** action is the only graceful-stop guarantee: it requests a
bounded runtime close, fsyncs the lifecycle outcome, then removes the foreground
notification and stops the service. If neutral or cleanup is not confirmed within the
deadline, the service stays in the foreground with command handling closed and an
explicit incomplete status; a later Safe stop can observe late completion. It never
turns a timeout into a false successful stop.

`START_STICKY` covers ordinary Android service/process recreation, including a null
restart intent. It does **not** recover an `am force-stop`: Android places the package
in the stopped state and withholds boot broadcasts/service recreation until an
explicit user or commissioning start clears that state. SIGKILL, force-stop, and the
Android 13+ Task Manager stop also cannot guarantee `onDestroy` or a final fsync.
