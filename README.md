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
- [`docs/mac-media-fixture-runbook.md`](docs/mac-media-fixture-runbook.md): pinned
  MediaMTX/ffmpeg synthetic RTMP → WHEP fixture, loopback proof and Mac-only evidence ceiling.
- [`docs/evidence/video-path-mac-runtime.md`](docs/evidence/video-path-mac-runtime.md): frozen
  Mac synthetic runtime result, conservative latency samples and explicit hardware limitations.
- [`docs/g520-point-to-point-ethernet-commissioning.md`](docs/g520-point-to-point-ethernet-commissioning.md):
  fail-closed Windows/G520 direct-Ethernet preparation and the remaining hardware gates.
- [`docs/production-web-console-security.md`](docs/production-web-console-security.md):
  fail-closed exposure/authentication foundation and the remaining TLS/credential lifecycle gate.
- [`docs/g520-office-mvp-runbook.md`](docs/g520-office-mvp-runbook.md): the single-branch,
  fixed-Ethernet DJI office installation and startup path.

## G520 office MVP candidate

The cumulative branch `codex/g520-office-mvp-integration` adds the `djiDebug` flavor, RC-N3 USB
accessory lifecycle, MSDK registration/connection, the fixed `10.52.0.0/30` Console, DJI RTMP to
Windows MediaMTX, and decoded NV21 → OpenCV observation. Use the runbook above and
`scripts/install-g520-office-mvp.sh`; do not substitute the Mac synthetic fixture for DJI video.

Until a physical G520, RC-N3 and Mini 4 Pro have produced first-hand evidence from the exact APK,
the capability matrix remains 17/17 `UNKNOWN` and this section is a candidate handoff, not a
hardware-verification claim.

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

The console keeps the frozen v1.0 wire inventory for legacy clients and negotiates v1.1 only when
both peers advertise it. v1.1 adds one server-to-browser, per-session commissioning-authority
observation; it adds no browser command for starting, renewing, widening, or revoking authority.
During DJI commissioning the public runtime lock remains `LOCKED`, and the SPA enables only the
exact intents in a still-live server-owned grant while an owned control lease is held. This read
model is not hardware evidence and never promotes a capability-matrix row.

All G520 hardware capabilities remain `UNKNOWN` until first-hand commissioning evidence exists.

## Mac synthetic video fixture

Issue #7's no-hardware half uses a digest-pinned MediaMTX 1.19.1 container and a stdlib Python
clock source piped through ffmpeg. It publishes only `mock-main`; Docker exposes RTMP, WebRTC HTTP
and ICE UDP on Mac `127.0.0.1` only.

```bash
./scripts/test-media-fixture-static.sh
# Terminal A: keep the foreground owner running.
./scripts/media-fixture.sh run
# Terminal B:
./scripts/media-fixture.sh verify
# Perform the browser/WHEP checks in the runbook.
# Return to Terminal A and press Ctrl-C for ownership-safe cleanup.
```

See [`docs/mac-media-fixture-runbook.md`](docs/mac-media-fixture-runbook.md) before starting it. The
latest frozen Mac-only result is recorded in
[`docs/evidence/video-path-mac-runtime.md`](docs/evidence/video-path-mac-runtime.md).
Mac synthetic playback and latency evidence do not verify G520 or the aircraft camera and cannot
close issue #7.
