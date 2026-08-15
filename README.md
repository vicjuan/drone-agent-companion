# drone-agent-companion

Headless DJI Mini 4 Pro agent for the G520 (MediaTek Genio 520) companion
computer running Android. Reuses the vendor-neutral modules of
[`drone-agent-android`](https://github.com/vicjuan/drone-agent-android); the
touch UI is replaced by an on-device web server operated from a browser.

> **Evidence boundary:** capability evidence recorded against the
> Pixel 8 Pro stack does **not** transfer to this stack. Every hardware row for
> **Mini 4 Pro + RC-N3 + G520 (Android)** starts at `UNKNOWN` until first-hand
> evidence exists on this stack. See [`docs/capability-matrix.md`](docs/capability-matrix.md).

## What changes vs. drone-agent-android

| Concern | drone-agent-android | drone-agent-companion |
| --- | --- | --- |
| Host device | Pixel 8 Pro (touch UI) | G520, headless |
| Operator UI | Compose (`app-debug-ui`) | Browser SPA served from the device |
| Operator protocol | Compose UI reads the in-process state model | Own console protocol, served over WebSocket |
| `gateway` role | Client of the drone-platform backend | Unchanged. The frozen agent protocol is not bent to serve the browser; the console protocol is separate and shares only the `core` state model and the `gateway.admission` policy objects |
| OpenCV runtime | Desktop replay/test only; not packaged in the Android APK | Companion-owned OpenCV core/desktop/Android modules must load and execute OpenCV on G520 Android for visual recognition |
| Reused modules | — | `core`, `drone-actuation`, `gateway`, `vision`, `drone-observation`, `adapter-dji`, `adapter-mock` |

## Documents

- [`AGENTS.md`](AGENTS.md): engineering discipline and hard boundaries,
  inherited from `drone-agent-android`. Agents start here.
- [`docs/architecture.md`](docs/architecture.md): system architecture, module
  reuse strategy, safety boundaries, and open decisions.
- [`docs/implementation-order.md`](docs/implementation-order.md): Weekend Web
  Control MVP-first coding order, with per-step completion criteria.
- [`docs/capability-matrix.md`](docs/capability-matrix.md): generated human-readable
  view of the canonical G520 evidence matrix.
- [`docs/headless-emulator-runbook.md`](docs/headless-emulator-runbook.md): frozen API 34
  emulator build/runtime procedure and the force-stop safety boundary.

## Workspace bootstrap

Clone with the read-only vendor submodule and use JDK 17:

```bash
git clone --recurse-submodules https://github.com/vicjuan/drone-agent-companion.git
cd drone-agent-companion
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew build
npm ci --prefix web-console
npm test --prefix web-console
```

Without a shared Android SDK, Gradle intentionally includes only the JDK modules. To configure
`host-headless` and `vision-opencv-android`, expose the same SDK to this build and the included
vendor build through `ANDROID_HOME` or `ANDROID_SDK_ROOT`.

> The companion repository is public, but the read-only `drone-agent-android` submodule may still
> require GitHub access. A public clone or GitHub Actions token cannot fetch a private submodule
> unless a maintainer supplies a read-only deploy key or equivalent credential.

## Weekend Web Control MVP

Run the Mac/JVM mock vertical slice with JDK 17:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :console-runner:run
```

Then open <http://127.0.0.1:8080>. The Gradle task installs and builds the SPA before starting the
server. The runner binds only to loopback and records audit events in
`.drone-agent-companion/audit/console-events.jsonl`.

If `8080` is already occupied, the optional third runner argument may select another loopback
port without widening the bind address:

```bash
./gradlew :console-runner:run --args='web-console/dist .drone-agent-companion/audit/console-events.jsonl 18081'
```

Open the matching URL (for example <http://127.0.0.1:18081>); the WebSocket Origin gate follows
that exact loopback port.

The browser shows the actual runtime adapter, connection and actuation-lock state. Takeoff,
landing and RTH require confirmation; the six continuous directions use press-and-hold controls
with server-side lease, TTL, dead-man and neutral enforcement. Mock RTH is a companion-owned,
observable simulation because the upstream mock adapter has no RTH action port. It is not G520 or
DJI evidence.

All G520 hardware capabilities remain `UNKNOWN` until first-hand commissioning evidence exists.
