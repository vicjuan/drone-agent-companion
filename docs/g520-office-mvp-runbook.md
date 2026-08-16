# G520 office MVP

This is the single-candidate path for the Mini 4 Pro + RC-N3 office session. It is deliberately
fixed to the isolated Ethernet `/30`; it is not a general LAN deployment profile.

## Required order

1. While the G520 still has ordinary Internet access, install/start the APK, attach RC-N3 and
   Mini 4 Pro, accept the Android USB accessory prompt, and wait for logcat to show
   `registration=REGISTERED` and `connection=AIRCRAFT_CONNECTED`.
2. Only after registration succeeds, disable Wi-Fi/cellular and configure G520 `eth0` as
   `10.52.0.2/30` with no gateway or DNS. The app intentionally refuses the office listener while
   any other routed/DNS-bearing interface is active.
3. Configure/connect the Windows adapter, start MediaMTX, then open the Console. Starting with the
   isolated `/30` before first-time MSDK registration can strand registration because that network
   deliberately has no Internet route.

## Windows

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
   [`mediamtx-v1.19.1-windows-amd64.lock.json`](../config/media/mediamtx-v1.19.1-windows-amd64.lock.json),
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
5. Browse to `http://10.52.0.2:8080`. The expected
   media endpoints are `rtmp://10.52.0.1:1935/dji-main` for G520 ingest and
   `http://10.52.0.1:8891/dji-main` for the browser WHEP page.

Do not approve an additional generic application, subnet or Internet-facing firewall exception.
Closing the PowerShell host forcibly can prevent `finally` cleanup; if that happens, do not rerun
the launcher until an administrator has inspected and removed only the fixed
`DroneAgentCompanion-G520-MediaMTX-Temporary` rule.

## G520 installation details

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
self-authorize. Before attaching RC-N3 for the first time, connect a temporary HDMI display and an
input device to G520 (or use another explicitly approved system-UI control path), accept the USB
and app runtime permission prompts (choose precise location when Android offers the choice),
accessory prompt, and select the persistent/default association when the G520 image offers it. If
the prompt cannot be operated and the image has no pre-provisioned system/priv-app USB grant, stop:
MSDK registration will remain gated. Replug RC-N3 once before flight work and confirm logcat returns
to `USB state=GRANTED` without an unattended prompt.

The browser-visible DJI lock remains `LOCKED`. A five-minute, server-owned office-demo grant is
created for the first exact point-to-point operator session and is still constrained by lease,
per-intent allowlist, command TTL, generation fencing and neutral-on-disconnect.

## Truth boundary

Until the exact APK has run on the physical G520 with RC-N3 and Mini 4 Pro, all hardware matrix
rows remain `UNKNOWN`. An accepted DJI SDK callback means only that the request was accepted; it
does not by itself prove airborne, landed, returned home, video decoded, or RC takeover safety.
