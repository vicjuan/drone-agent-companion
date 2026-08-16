#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ADB="${ANDROID_HOME:-/Users/vic/Library/Android/sdk}/platform-tools/adb"
G520_SERIAL="${G520_ADB_SERIAL:-}"
APK="$REPO_ROOT/host-headless/build/outputs/apk/dji/debug/host-headless-dji-debug.apk"
PACKAGE="${DJI_APPLICATION_ID:-}"
ACTIVITY="$PACKAGE/com.durendal.droneagent.companion.host.DjiCommissioningActivity"
LEGACY_DJI_PACKAGE="com.durendal.droneagent.app"

[[ -x "$ADB" ]] || { printf 'adb not found: %s\n' "$ADB" >&2; exit 1; }
[[ -n "$G520_SERIAL" ]] || {
    printf 'G520_ADB_SERIAL is required; refusing to guess when an emulator or another device is attached.\n' >&2
    exit 1
}
[[ -n "${DJI_API_KEY:-}" ]] || {
    printf 'DJI_API_KEY is required and is never written into this repository.\n' >&2
    exit 1
}
[[ -n "$PACKAGE" ]] || {
    printf 'DJI_APPLICATION_ID is required and must exactly match the package registered to DJI_API_KEY.\n' >&2
    printf 'Use com.durendal.droneagent.companion.host for a new companion key, or explicitly approve the legacy package path.\n' >&2
    exit 1
}
[[ "$PACKAGE" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || {
    printf 'DJI_APPLICATION_ID is not a valid Android application ID: %s\n' "$PACKAGE" >&2
    exit 1
}
if [[ "$PACKAGE" == "$LEGACY_DJI_PACKAGE" && "${ALLOW_REPLACE_EXISTING_DJI_APP:-}" != "1" ]]; then
    printf 'Refusing to install over the existing DJI app package %s.\n' "$PACKAGE" >&2
    printf 'Set ALLOW_REPLACE_EXISTING_DJI_APP=1 only after approving replacement/data impact.\n' >&2
    exit 1
fi

ADB_STATE="$("$ADB" -s "$G520_SERIAL" get-state 2>/dev/null || true)"
[[ "$ADB_STATE" == "device" ]] || {
    printf 'Selected G520 ADB target is not ready or authorized: %s\n' "$G520_SERIAL" >&2
    "$ADB" devices -l >&2
    exit 1
}

cd "$REPO_ROOT"
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
export ANDROID_HOME="${ANDROID_HOME:-/Users/vic/Library/Android/sdk}"

./gradlew :host-headless:assembleDjiDebug \
    -PdjiApiKey="$DJI_API_KEY" \
    -PdjiApplicationId="$PACKAGE" \
    --no-daemon
[[ -f "$APK" ]] || { printf 'APK output missing: %s\n' "$APK" >&2; exit 1; }

"$ADB" -s "$G520_SERIAL" install -r "$APK"
"$ADB" -s "$G520_SERIAL" shell am start -n "$ACTIVITY"

ETHERNET_STATE="$("$ADB" -s "$G520_SERIAL" shell ip -o -4 addr show dev eth0 2>/dev/null || true)"
if [[ "$ETHERNET_STATE" != *"10.52.0.2/30"* ]]; then
    printf 'WARNING: G520 eth0 is not currently observed as 10.52.0.2/30.\n' >&2
    printf 'Configure the G520 Ethernet settings; the app will keep Console/RTMP fail-closed until then.\n' >&2
fi

printf 'Installed %s and launched its commissioning Activity on %s.\n' "$PACKAGE" "$G520_SERIAL"
printf 'Complete the visible Android app/USB permission prompts, then confirm REGISTERED and AIRCRAFT_CONNECTED in logcat.\n'
printf 'After G520 Ethernet is 10.52.0.2/30, open http://10.52.0.2:8080 on Windows.\n'
printf 'Keep scripts/commissioning/start-windows-g520-media.ps1 running for DJI video.\n'
printf 'Live diagnosis: %s -s %s logcat -s DjiPlatformRuntime DjiObservationMedia DjiHeadlessHost DjiRegistrar\n' "$ADB" "$G520_SERIAL"
