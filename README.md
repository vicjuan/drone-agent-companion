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
- [`docs/g520-office-mvp-runbook.md`](docs/g520-office-mvp-runbook.md): compatibility pointer to
  the consolidated G520 office instructions in this README.

## G520 office MVP candidate

The cumulative branch `codex/g520-office-mvp-integration` adds the `djiDebug` flavor, RC-N3 USB
accessory lifecycle, MSDK registration/connection, the fixed `10.52.0.0/30` Console, DJI RTMP to
Windows MediaMTX, and decoded NV21 → OpenCV observation. Follow the complete procedure below and
use `scripts/install-g520-office-mvp.sh`; do not substitute the Mac synthetic fixture for DJI video.

> **Headless first-boot gate:** the target G520 exposes RJ45 but no HDMI display path. Its Android
> image must therefore expose an already enabled and authorized ADB-over-TCP endpoint on a known
> bootstrap IP, or provide equivalent platform-signed provisioning. Runtime permissions can be
> granted through ADB, and a reachable Android virtual display can be operated through `scrcpy`,
> but a normal APK cannot silently grant its own first USB-accessory authorization. If network ADB,
> a controllable System UI, and a pre-provisioned privileged/default USB handler are all absent,
> first-time RC-N3 commissioning is blocked. Switching the board to `10.52.0.2/30` also drops the
> bootstrap ADB connection; confirm that `adbd` remains reachable and reconnect at the new address.

The complete office procedure is consolidated below so the operator needs only this README.

### Required order

1. Before installing anything, connect to the G520's known bootstrap IP through RJ45 and prove
   that its Android image already exposes an authorized ADB-over-TCP endpoint. The target board
   has no HDMI fallback. If network ADB is unavailable, stop: a normal APK cannot provision the
   debug channel or approve its own first USB-accessory dialog.
2. While the G520 still has ordinary Internet access, install/start the APK, attach RC-N3 and
   Mini 4 Pro, grant the Android runtime permissions through ADB, and operate the first USB
   accessory prompt through an ADB-backed virtual System UI such as `scrcpy`. Select the
   persistent/default association when the image offers it. Wait for logcat to show
   `registration=REGISTERED` and `connection=AIRCRAFT_CONNECTED`.
3. Only after registration succeeds, disable Wi-Fi/cellular and configure G520 `eth0` as
   `10.52.0.2/30` with no gateway or DNS. The app intentionally refuses the office listener while
   any other routed/DNS-bearing interface is active. The bootstrap ADB connection will drop; prove
   that `adbd` is still listening and reconnect to `10.52.0.2` on the image's documented ADB TCP
   port before continuing.
4. Configure/connect the Windows adapter, start MediaMTX, then open the Console. Starting with the
   isolated `/30` before first-time MSDK registration can strand registration because that network
   deliberately has no Internet route.

### Windows

1. Configure the dedicated Ethernet adapter manually as `10.52.0.1/30`, with DHCP disabled and
   no gateway or DNS. Keep Internet Connection Sharing, network bridge and IP forwarding off.
2. Connect that Ethernet adapter directly to the already powered G520. The launcher requires the
   physical 802.3 link to be `Up`; it will not start against a disconnected or virtual adapter.
3. From the repository root, start the receiver in Windows PowerShell 5.1 or PowerShell 7:

   ```powershell
   .\scripts\commissioning\start-windows-g520-media.ps1
   ```

   Start PowerShell with **Run as administrator**. The launcher refuses to run without elevation.

   If the executable is absent, the script downloads only the official
   [`mediamtx_v1.19.1_windows_amd64.zip`](https://github.com/bluenviron/mediamtx/releases/download/v1.19.1/mediamtx_v1.19.1_windows_amd64.zip)
   and official [`checksums.sha256`](https://github.com/bluenviron/mediamtx/releases/download/v1.19.1/checksums.sha256).
   It verifies the fixed hashes in
   [`mediamtx-v1.19.1-windows-amd64.lock.json`](config/media/mediamtx-v1.19.1-windows-amd64.lock.json),
   cross-checks the archive entry in the verified official checksum file, and extracts into the
   repo-local ignored `.drone-agent-companion\tools\mediamtx\v1.19.1-windows-amd64\` cache.
   Every later launch re-verifies the executable. An optional `-MediaMtxExe` path is accepted only
   when that file has the exact pinned executable hash; it is not an unverified bypass.
4. The launcher fails closed unless the selected physical adapter has only manual
   `10.52.0.1/30`, DHCP/DNS/gateway and system-wide IP forwarding are absent, and Windows selects
   that same interface and source address for `10.52.0.2`. After verifying the pinned
   `mediamtx.exe`, it creates one temporary inbound firewall rule limited to that executable,
   local `10.52.0.1:1935/TCP`, remote `10.52.0.2` and wired interfaces. An existing rule with the
   same fixed name causes a fail-closed exit; the launcher never edits or replaces it. It then runs
   MediaMTX in the foreground. Leave that window open; `Ctrl+C` stops the receiver and the
   launcher's `finally` block removes exactly the rule it created.
5. Browse to `http://10.52.0.2:8080`. The expected media endpoints are
   `rtmp://10.52.0.1:1935/dji-main` for G520 ingest and
   `http://10.52.0.1:8891/dji-main` for the browser WHEP page.

Do not approve an additional generic application, subnet or Internet-facing firewall exception.
Closing the PowerShell host forcibly can prevent `finally` cleanup; if that happens, do not rerun
the launcher until an administrator has inspected and removed only the fixed
`DroneAgentCompanion-G520-MediaMTX-Temporary` rule.

### G520 installation details

#### Headless bootstrap over RJ45

The target G520 has no HDMI connector. Record the image's bootstrap IP from its provisioning
record or DHCP lease, then verify network ADB before relying on this procedure:

```bash
export G520_BOOTSTRAP_IP='…'
export G520_ADB_TCP_PORT='5555' # Replace only when the exact image documents another port.
adb connect "${G520_BOOTSTRAP_IP}:${G520_ADB_TCP_PORT}"
export G520_ADB_SERIAL="${G520_BOOTSTRAP_IP}:${G520_ADB_TCP_PORT}"
adb -s "$G520_ADB_SERIAL" get-state
adb -s "$G520_ADB_SERIAL" shell getprop ro.build.fingerprint
adb -s "$G520_ADB_SERIAL" shell getprop ro.debuggable
adb -s "$G520_ADB_SERIAL" shell wm size
```

`get-state` must report `device`. A reachable port that remains `unauthorized` is not sufficient,
because there is no local display on which to accept the ADB host key. If the image does not expose
an already authorized network ADB endpoint, obtain a vendor image or platform-signed provisioning
component that does; do not assume the APK or Console can create this authority after installation.

After the APK is installed, the ordinary dangerous permissions can be granted without a physical
display. Use the exact application ID registered to the supplied DJI key:

```bash
# Use this package only when the DJI key is registered for the companion app.
export DJI_APPLICATION_ID='com.durendal.droneagent.companion.host'
# If the available key is registered for the legacy app, export this value instead:
# export DJI_APPLICATION_ID='com.durendal.droneagent.app'

adb -s "$G520_ADB_SERIAL" shell pm grant "$DJI_APPLICATION_ID" \
  android.permission.ACCESS_COARSE_LOCATION
adb -s "$G520_ADB_SERIAL" shell pm grant "$DJI_APPLICATION_ID" \
  android.permission.ACCESS_FINE_LOCATION
adb -s "$G520_ADB_SERIAL" shell pm grant "$DJI_APPLICATION_ID" \
  android.permission.READ_PHONE_STATE
adb -s "$G520_ADB_SERIAL" shell am start -n \
  "$DJI_APPLICATION_ID/com.durendal.droneagent.companion.host.DjiCommissioningActivity"
```

These commands do **not** grant USB accessory permission. The app can request that permission, but
Android owns the first authorization dialog. Use `scrcpy` over the authorized network ADB channel
to operate a real or virtual Android display, or use an exact-image `uiautomator` procedure whose
visible labels and target bounds have been inspected first. Select the persistent/default handler
when available. If the image supplies neither a controllable System UI nor a pre-provisioned
privileged/default USB handler, RC-N3 commissioning is blocked.

Changing `eth0` from its bootstrap network to `10.52.0.2/30` terminates the old ADB socket. Before
removing the bootstrap route, confirm that the image keeps `adbd` listening on TCP and then reconnect:

```bash
adb connect "10.52.0.2:${G520_ADB_TCP_PORT}"
export G520_ADB_SERIAL="10.52.0.2:${G520_ADB_TCP_PORT}"
adb -s "$G520_ADB_SERIAL" get-state
```

Do not change to the isolated `/30` until MSDK has completed its first Internet-backed registration.

#### Fixed Ethernet configuration

Before expecting the listener to open, configure the G520 Ethernet interface as `10.52.0.2/30`
with no gateway or DNS. The installer performs a read-only `eth0` check and warns when that exact
address is not present; the APK does not require or assume privileged Android network-setting
rights.

There is no portable unprivileged Android API for changing a physical Ethernet interface to a
static address. AOSP's runtime `EthernetManager` mutation API requires the signature-only
`MANAGE_ETHERNET_NETWORKS` permission, and MediaTek's public G520 Android documentation does not
publish a board-specific static-IP command. In particular, do not assume that a Yocto `nmcli`
recipe or an undocumented `cmd ethernet` mutation is available on the installed Android image.
First capture the exact image and the read-only vendor surface:

```bash
adb -s "$G520_ADB_SERIAL" shell getprop ro.build.fingerprint
adb -s "$G520_ADB_SERIAL" shell getprop ro.build.type
adb -s "$G520_ADB_SERIAL" shell getprop ro.debuggable
adb -s "$G520_ADB_SERIAL" shell ip link show
adb -s "$G520_ADB_SERIAL" shell dumpsys ethernet
adb -s "$G520_ADB_SERIAL" shell cmd ethernet help
adb -s "$G520_ADB_SERIAL" shell cmd package query-activities --brief \
  -a android.settings.ETHERNET_SETTINGS
```

Use an Ethernet Settings page only when the final command proves that the installed image exposes
one, or use a vendor shell command only when that exact image's own `help` output documents it.
Configure address `10.52.0.2`, prefix `30`, and leave gateway/DNS empty. If neither surface exists
on a non-debuggable production image, the APK-only path is blocked: a platform-signed provisioning
component or an Android image rebuilt with the AOSP Ethernet resource overlay is required. Do not
try an unreviewed root or `ip addr add` workaround on the office flight candidate.

Primary references: [AOSP Ethernet management](https://source.android.com/docs/automotive/connectivity/ethernet-manage),
[AOSP EthernetManager](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework-t/src/android/net/EthernetManager.java),
and [MediaTek G520 EVK Android hardware](https://genio.mediatek.com/doc/android/hw/g520-evk.html).

Verify the board-side result before opening the Console:

```bash
adb -s "$G520_ADB_SERIAL" shell ip -o -4 addr show dev eth0
adb -s "$G520_ADB_SERIAL" shell ip route show dev eth0
adb -s "$G520_ADB_SERIAL" shell ip route show default
```

The first command must contain `10.52.0.2/30`; the second must contain only the directly connected
`10.52.0.0/30` route needed for this link; the default-route command must produce no output. These
commands verify but do not configure the G520 image.

Install the `djiDebug` APK built from `codex/g520-office-mvp-integration`. The flavor expects the
DJI app key through Gradle property `djiApiKey`; the key is never committed. The default package
in Gradle is `com.durendal.droneagent.companion.host`, but the installer deliberately requires an
explicit `DJI_APPLICATION_ID` so it cannot silently build an APK whose package does not match the
DJI key. Use that companion package only when the key is registered for that exact package.
If tomorrow's available key is instead bound to the legacy `com.durendal.droneagent.app`, set both
`DJI_APPLICATION_ID=com.durendal.droneagent.app` and
`ALLOW_REPLACE_EXISTING_DJI_APP=1`; this explicitly authorizes replacing any installed app that
uses that package. Run one of the following from macOS; never paste the actual key into a tracked
file:

```bash
# Key registered for the new companion package
DJI_API_KEY='…' \
DJI_APPLICATION_ID='com.durendal.droneagent.companion.host' \
G520_ADB_SERIAL='…' \
./scripts/install-g520-office-mvp.sh

# Only when the available key is registered for the legacy package and replacement is approved
DJI_API_KEY='…' \
DJI_APPLICATION_ID='com.durendal.droneagent.app' \
ALLOW_REPLACE_EXISTING_DJI_APP=1 \
G520_ADB_SERIAL='…' \
./scripts/install-g520-office-mvp.sh
```

If `adb install -r` reports `UPDATE_INCOMPATIBLE` or `INSTALL_FAILED_VERSION_DOWNGRADE`, stop and
inspect the installed package, signing identity, version and data-retention requirements; the script
intentionally does not uninstall, downgrade or erase another app automatically. It requests USB
accessory permission at startup, initializes/registers MSDK, connects the shared DJI agent,
publishes `dji-main` RTMP to Windows and exposes decoded NV21 frames to the OpenCV observation
session.

The first USB accessory grant is an Android system interaction, not a silent permission the APK can
self-authorize. This G520 has no HDMI fallback, so complete the RJ45 ADB and virtual-System-UI gate
above before attaching RC-N3. Replug RC-N3 once before flight work and confirm logcat returns to
`USB state=GRANTED` without another unattended prompt.

The browser-visible DJI lock remains `LOCKED`. A five-minute, server-owned office-demo grant is
created for the first exact point-to-point operator session and is still constrained by lease,
per-intent allowlist, command TTL, generation fencing and neutral-on-disconnect.

### Truth boundary

Until the exact APK has run on the physical G520 with RC-N3 and Mini 4 Pro, all hardware matrix
rows remain `UNKNOWN`. An accepted DJI SDK callback means only that the request was accepted; it
does not by itself prove airborne, landed, returned home, video decoded, or RC takeover safety.

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
