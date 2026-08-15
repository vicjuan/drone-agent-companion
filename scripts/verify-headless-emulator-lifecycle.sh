#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "usage: $0 <mock-debug-apk> [adb-serial]" >&2
    exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
ADB_SERIAL="${2:-emulator-5554}"
HOST_FORWARD_PORT=18080
ADB_BIN="${ANDROID_HOME:-/Users/vic/Library/Android/sdk}/platform-tools/adb"
PACKAGE_NAME="com.durendal.droneagent.companion.host.mock"
AGENT_PROCESS="$PACKAGE_NAME:agent"
DEVICE_PORT=8080
HEALTH_READY_TIMEOUT_SECONDS=45
DEVICE_LIFECYCLE_DIR="/data/user_de/0/$PACKAGE_NAME/files/headless-host/lifecycle"
DEVICE_LIFECYCLE_FILE="$DEVICE_LIFECYCLE_DIR/lifecycle.jsonl"
DEVICE_LIFECYCLE_PREVIOUS_FILE="$DEVICE_LIFECYCLE_DIR/lifecycle.previous.jsonl"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
EVIDENCE_PARENT="$REPO_ROOT/host-headless/build/emulator-evidence"
EVIDENCE_DIR="$EVIDENCE_PARENT/$RUN_ID"
LOCK_PARENT="/tmp/drone-agent-companion-headless-locks-$UID"
SERIAL_LOCK_DIR=""
PORT_LOCK_DIR="$LOCK_PARENT/host-port-$HOST_FORWARD_PORT"
SERIAL_LOCK_ACQUIRED=false
PORT_LOCK_ACQUIRED=false
FORWARD_CREATED=false

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "required command is not executable: $command_name" >&2
        exit 69
    fi
}

for command_name in git curl rg python3 shasum node unzip; do
    require_command "$command_name"
done

if [[ ! -x "$ADB_BIN" ]]; then
    echo "adb not executable: $ADB_BIN" >&2
    exit 69
fi
if [[ ! -f "$APK_PATH" ]]; then
    echo "mock debug APK not found: $APK_PATH" >&2
    exit 66
fi

adb_cmd() {
    "$ADB_BIN" -s "$ADB_SERIAL" "$@"
}

cleanup() {
    if [[ "$FORWARD_CREATED" == true ]]; then
        adb_cmd forward --remove "tcp:$HOST_FORWARD_PORT" >/dev/null 2>&1 || true
    fi
    if [[ "$PORT_LOCK_ACQUIRED" == true ]]; then
        rmdir "$PORT_LOCK_DIR" >/dev/null 2>&1 || true
    fi
    if [[ "$SERIAL_LOCK_ACQUIRED" == true && -n "$SERIAL_LOCK_DIR" ]]; then
        rmdir "$SERIAL_LOCK_DIR" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

if [[ -n "$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all --ignore-submodules=none)" ]]; then
    echo "lifecycle evidence requires a clean, committed HEAD" >&2
    git -C "$REPO_ROOT" status --short --branch >&2
    exit 65
fi
CANDIDATE_COMMIT="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}')"
if [[ ! "$CANDIDATE_COMMIT" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
    echo "could not resolve a bounded commit identity for HEAD" >&2
    exit 65
fi

mkdir -p "$EVIDENCE_PARENT" "$LOCK_PARENT"
SERIAL_LOCK_ID="$(printf '%s' "$ADB_SERIAL" | shasum -a 256 | awk '{print $1}')"
if [[ ! "$SERIAL_LOCK_ID" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "could not resolve a bounded lock identity for the adb serial" >&2
    exit 1
fi
SERIAL_LOCK_DIR="$LOCK_PARENT/adb-serial-$SERIAL_LOCK_ID"
if ! mkdir "$SERIAL_LOCK_DIR"; then
    echo "another lifecycle verification already owns adb serial $ADB_SERIAL" >&2
    exit 73
fi
SERIAL_LOCK_ACQUIRED=true
if ! mkdir "$PORT_LOCK_DIR"; then
    echo "another lifecycle verification already owns host port $HOST_FORWARD_PORT" >&2
    exit 73
fi
PORT_LOCK_ACQUIRED=true
if ! mkdir "$EVIDENCE_DIR"; then
    echo "evidence directory already exists: $EVIDENCE_DIR" >&2
    exit 73
fi

wait_for_boot() {
    adb_cmd wait-for-device
    local deadline=$((SECONDS + 120))
    while (( SECONDS < deadline )); do
        if [[ "$(adb_cmd shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; then
            return 0
        fi
        sleep 1
    done
    echo "emulator did not report sys.boot_completed=1" >&2
    return 1
}

wait_for_disconnect() {
    local deadline=$((SECONDS + 30))
    while (( SECONDS < deadline )); do
        if [[ "$(adb_cmd get-state 2>/dev/null || true)" != "device" ]]; then
            return 0
        fi
        sleep 0.2
    done
    echo "emulator never disconnected during reboot" >&2
    return 1
}

sanitize_single_line() {
    local value="$1"
    value="${value//$'\r'/ }"
    value="${value//$'\n'/ }"
    value="${value//$'\t'/ }"
    value="$(printf '%s' "$value" | LC_ALL=C tr -d '\000-\010\013\014\016-\037\177')"
    while [[ "$value" == *"  "* ]]; do
        value="${value//  / }"
    done
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "${value:0:512}"
}

read_device_property() {
    local property_name="$1"
    local raw_value
    raw_value="$(adb_cmd shell getprop "$property_name" 2>/dev/null || true)"
    sanitize_single_line "$raw_value"
}

capture_device_identity() {
    DEVICE_QEMU="$(read_device_property ro.kernel.qemu)"
    DEVICE_SDK="$(read_device_property ro.build.version.sdk)"
    DEVICE_ABI="$(read_device_property ro.product.cpu.abi)"
    DEVICE_FINGERPRINT="$(read_device_property ro.build.fingerprint)"
    DEVICE_AVD_BOOT="$(read_device_property ro.boot.qemu.avd_name)"
    DEVICE_AVD_KERNEL="$(read_device_property ro.kernel.qemu.avd_name)"
    DEVICE_AVD_ENV="$(sanitize_single_line "$(adb_cmd shell printenv AVD_NAME 2>/dev/null || true)")"
    DEVICE_AVD_SOURCE="ro.boot.qemu.avd_name"
    DEVICE_AVD="$DEVICE_AVD_BOOT"

    if [[ -z "$DEVICE_AVD" ]]; then
        DEVICE_AVD_SOURCE="ro.kernel.qemu.avd_name"
        DEVICE_AVD="$DEVICE_AVD_KERNEL"
    fi
    if [[ -z "$DEVICE_AVD" ]]; then
        DEVICE_AVD_SOURCE="AVD_NAME"
        DEVICE_AVD="$DEVICE_AVD_ENV"
    fi
    if [[ -z "$DEVICE_AVD" ]]; then
        echo "refusing lifecycle evidence: emulator AVD name is unavailable" >&2
        exit 1
    fi

    if [[ "$DEVICE_QEMU" != "1" ]]; then
        echo "refusing lifecycle evidence: ro.kernel.qemu must be 1 (got '$DEVICE_QEMU')" >&2
        exit 1
    fi
    if [[ "$DEVICE_SDK" != "34" ]]; then
        echo "refusing lifecycle evidence: ro.build.version.sdk must be 34 (got '$DEVICE_SDK')" >&2
        exit 1
    fi
    if [[ -z "$DEVICE_ABI" ]]; then
        echo "refusing lifecycle evidence: ro.product.cpu.abi is empty" >&2
        exit 1
    fi
    if [[ -z "$DEVICE_FINGERPRINT" ]]; then
        echo "refusing lifecycle evidence: ro.build.fingerprint is empty" >&2
        exit 1
    fi

    SANITIZED_ADB_SERIAL="$(sanitize_single_line "$ADB_SERIAL")"
    {
        printf '%s\n' "serial=$SANITIZED_ADB_SERIAL"
        printf '%s\n' "ro.kernel.qemu=$DEVICE_QEMU"
        printf '%s\n' "ro.build.version.sdk=$DEVICE_SDK"
        printf '%s\n' "ro.product.cpu.abi=$DEVICE_ABI"
        printf '%s\n' "ro.build.fingerprint=$DEVICE_FINGERPRINT"
        printf '%s\n' "ro.boot.qemu.avd_name=$DEVICE_AVD_BOOT"
        printf '%s\n' "ro.kernel.qemu.avd_name=$DEVICE_AVD_KERNEL"
        printf '%s\n' "AVD_NAME=$DEVICE_AVD_ENV"
        printf '%s\n' "resolved_avd_name_source=$DEVICE_AVD_SOURCE"
        printf '%s\n' "resolved_avd_name=$DEVICE_AVD"
    } > "$EVIDENCE_DIR/device-identity.txt"
}

wait_for_agent_pid() {
    local deadline=$((SECONDS + 45))
    while (( SECONDS < deadline )); do
        local pid
        pid="$(adb_cmd shell pidof "$AGENT_PROCESS" 2>/dev/null | tr -d '\r' || true)"
        if [[ "$pid" =~ ^[0-9]+$ ]]; then
            echo "$pid"
            return 0
        fi
        sleep 1
    done
    echo "headless agent process did not start" >&2
    return 1
}

require_agent_pid() {
    local expected_pid="$1"
    local phase="$2"
    local actual_pid
    actual_pid="$(adb_cmd shell pidof "$AGENT_PROCESS" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$actual_pid" != "$expected_pid" ]]; then
        echo "agent PID changed during $phase (expected $expected_pid, got '$actual_pid')" >&2
        return 1
    fi
}

wait_for_health() {
    local deadline=$((SECONDS + HEALTH_READY_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        local body
        body="$(curl --noproxy '*' -fsS --max-time 1 "http://127.0.0.1:$HOST_FORWARD_PORT/healthz" 2>/dev/null || true)"
        if [[ "$body" == '{"status":"ok"}' ]]; then
            echo "$body"
            return 0
        fi
        sleep 1
    done
    echo "headless health endpoint did not become ready" >&2
    return 1
}

wait_for_reboot_evidence() {
    local expected_pid="$1"
    local epoch_floor_ms="$2"
    local deadline=$((SECONDS + 30))
    while (( SECONDS < deadline )); do
        : > "$EVIDENCE_DIR/lifecycle-after-reboot.jsonl"
        if adb_cmd shell run-as "$PACKAGE_NAME" test -f "$DEVICE_LIFECYCLE_PREVIOUS_FILE" \
            >/dev/null 2>&1; then
            adb_cmd exec-out run-as "$PACKAGE_NAME" cat "$DEVICE_LIFECYCLE_PREVIOUS_FILE" \
                >> "$EVIDENCE_DIR/lifecycle-after-reboot.jsonl"
        fi
        adb_cmd exec-out run-as "$PACKAGE_NAME" cat "$DEVICE_LIFECYCLE_FILE" \
            >> "$EVIDENCE_DIR/lifecycle-after-reboot.jsonl"
        if python3 -c \
            'import json,sys; src,out,floor,pid=sys.argv[1],sys.argv[2],int(sys.argv[3]),int(sys.argv[4]); records=[json.loads(line) for line in open(src,encoding="utf-8") if line.strip()]; fresh=[r for r in records if isinstance(r.get("epoch_ms"),int) and r["epoch_ms"]>floor]; boots=[(i,r) for i,r in enumerate(fresh) if r.get("event")=="boot_received" and r.get("trigger") in ("locked_boot_completed","boot_completed")]; ok=any(any(j>i and candidate.get("event")=="runtime_started" and candidate.get("pid")==pid and candidate.get("trigger")==boot.get("trigger") and candidate.get("epoch_ms")>=boot.get("epoch_ms") for j,candidate in enumerate(fresh)) for i,boot in boots); open(out,"w",encoding="utf-8").writelines(json.dumps(r,separators=(",",":"))+"\n" for r in fresh); raise SystemExit(0 if ok else 1)' \
            "$EVIDENCE_DIR/lifecycle-after-reboot.jsonl" \
            "$EVIDENCE_DIR/lifecycle-appended-by-reboot.jsonl" \
            "$epoch_floor_ms" "$expected_pid"; then
            return 0
        fi
        sleep 1
    done
    echo "fresh boot/runtime lifecycle evidence did not appear" >&2
    return 1
}

echo "[headless-emulator] target=$ADB_SERIAL package=$PACKAGE_NAME"
wait_for_boot
capture_device_identity
adb_cmd shell pm path "$PACKAGE_NAME" > "$EVIDENCE_DIR/package-path-before-reboot.txt"

REMOTE_APK="$(sed -n 's/^package://p' "$EVIDENCE_DIR/package-path-before-reboot.txt" | head -1 | tr -d '\r')"
if [[ -z "$REMOTE_APK" ]]; then
    echo "package is not installed; run the connected instrumentation lane first" >&2
    exit 65
fi
adb_cmd pull "$REMOTE_APK" "$EVIDENCE_DIR/installed-base.apk" >/dev/null
shasum -a 256 "$APK_PATH" "$EVIDENCE_DIR/installed-base.apk" > "$EVIDENCE_DIR/apk-sha256.txt"
APK_SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
INSTALLED_APK_SHA256="$(shasum -a 256 "$EVIDENCE_DIR/installed-base.apk" | awk '{print $1}')"
if [[ ! "$APK_SHA256" =~ ^[0-9a-fA-F]{64}$ || ! "$INSTALLED_APK_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "could not resolve bounded SHA-256 identities for the APKs" >&2
    exit 1
fi
if [[ "$APK_SHA256" != "$INSTALLED_APK_SHA256" ]]; then
    echo "installed APK does not match the frozen candidate" >&2
    exit 1
fi
BUILT_APK_COMMIT="$(unzip -p "$APK_PATH" assets/companion-candidate/commit.txt 2>/dev/null || true)"
INSTALLED_APK_COMMIT="$(unzip -p "$EVIDENCE_DIR/installed-base.apk" assets/companion-candidate/commit.txt 2>/dev/null || true)"
if [[ ! "$BUILT_APK_COMMIT" =~ ^[0-9a-fA-F]{40,64}$ || \
    "$BUILT_APK_COMMIT" != "$CANDIDATE_COMMIT" || \
    "$INSTALLED_APK_COMMIT" != "$CANDIDATE_COMMIT" ]]; then
    echo "APK embedded candidate commit does not match the clean HEAD" >&2
    exit 1
fi
BUILT_APK_WORKTREE_STATE="$(unzip -p "$APK_PATH" assets/companion-candidate/worktree-state.txt 2>/dev/null || true)"
INSTALLED_APK_WORKTREE_STATE="$(unzip -p "$EVIDENCE_DIR/installed-base.apk" assets/companion-candidate/worktree-state.txt 2>/dev/null || true)"
if [[ "$BUILT_APK_WORKTREE_STATE" != "clean" || "$INSTALLED_APK_WORKTREE_STATE" != "clean" ]]; then
    echo "APK was not built from a clean worktree" >&2
    exit 1
fi
INSTALLED_PROTOCOL_DECODER="$EVIDENCE_DIR/installed-console-protocol.mjs"
INSTALLED_CAPABILITY_MATRIX="$EVIDENCE_DIR/installed-g520-stack.json"
unzip -p "$EVIDENCE_DIR/installed-base.apk" \
    assets/companion-web/assets/console-protocol.js > "$INSTALLED_PROTOCOL_DECODER"
unzip -p "$EVIDENCE_DIR/installed-base.apk" \
    assets/companion-web/capability-matrix/g520-stack.json > "$INSTALLED_CAPABILITY_MATRIX"
if [[ ! -s "$INSTALLED_PROTOCOL_DECODER" || ! -s "$INSTALLED_CAPABILITY_MATRIX" ]]; then
    echo "installed APK is missing the canonical console decoder or capability matrix" >&2
    exit 1
fi
INSTALLED_PROTOCOL_DECODER_SHA256="$(shasum -a 256 "$INSTALLED_PROTOCOL_DECODER" | awk '{print $1}')"
INSTALLED_CAPABILITY_MATRIX_SHA256="$(shasum -a 256 "$INSTALLED_CAPABILITY_MATRIX" | awk '{print $1}')"
if [[ ! "$INSTALLED_PROTOCOL_DECODER_SHA256" =~ ^[0-9a-fA-F]{64}$ || \
    ! "$INSTALLED_CAPABILITY_MATRIX_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "could not resolve bounded SHA-256 identities for installed console assets" >&2
    exit 1
fi

DEVICE_EPOCH_SECONDS="$(sanitize_single_line "$(adb_cmd shell date +%s 2>/dev/null || true)")"
if [[ ! "$DEVICE_EPOCH_SECONDS" =~ ^[0-9]{9,12}$ ]]; then
    echo "could not capture a bounded device epoch before reboot" >&2
    exit 1
fi
REBOOT_EPOCH_FLOOR_MS=$((DEVICE_EPOCH_SECONDS * 1000))
: > "$EVIDENCE_DIR/lifecycle-before-reboot.jsonl"
if adb_cmd shell run-as "$PACKAGE_NAME" test -f "$DEVICE_LIFECYCLE_PREVIOUS_FILE" \
    >/dev/null 2>&1; then
    adb_cmd exec-out run-as "$PACKAGE_NAME" cat "$DEVICE_LIFECYCLE_PREVIOUS_FILE" \
        >> "$EVIDENCE_DIR/lifecycle-before-reboot.jsonl"
fi
adb_cmd exec-out run-as "$PACKAGE_NAME" cat "$DEVICE_LIFECYCLE_FILE" \
    >> "$EVIDENCE_DIR/lifecycle-before-reboot.jsonl"
PRE_REBOOT_MAX_EPOCH_MS="$(python3 -c \
    'import json,sys; records=[json.loads(line) for line in open(sys.argv[1],encoding="utf-8") if line.strip()]; values=[r.get("epoch_ms") for r in records]; assert all(isinstance(v,int) and v>0 for v in values); print(max(values,default=0))' \
    "$EVIDENCE_DIR/lifecycle-before-reboot.jsonl")"
if [[ ! "$PRE_REBOOT_MAX_EPOCH_MS" =~ ^[0-9]{1,16}$ ]]; then
    echo "could not capture a bounded pre-reboot lifecycle epoch" >&2
    exit 1
fi
if (( PRE_REBOOT_MAX_EPOCH_MS > REBOOT_EPOCH_FLOOR_MS )); then
    REBOOT_EPOCH_FLOOR_MS=$PRE_REBOOT_MAX_EPOCH_MS
fi

echo "[headless-emulator] rebooting to exercise LOCKED/BOOT_COMPLETED start"
adb_cmd reboot
wait_for_disconnect
wait_for_boot
BOOT_COMPLETED_HOST_SECONDS=$SECONDS
adb_cmd forward --no-rebind "tcp:$HOST_FORWARD_PORT" "tcp:$DEVICE_PORT" >/dev/null
FORWARD_CREATED=true
BOOT_PID="$(wait_for_agent_pid)"
adb_cmd shell pm path "$PACKAGE_NAME" > "$EVIDENCE_DIR/package-path-before-runtime.txt"
PRE_RUNTIME_REMOTE_APK="$(sed -n 's/^package://p' "$EVIDENCE_DIR/package-path-before-runtime.txt" | head -1 | tr -d '\r')"
if [[ -z "$PRE_RUNTIME_REMOTE_APK" ]]; then
    echo "package disappeared before runtime verification" >&2
    exit 1
fi
adb_cmd pull "$PRE_RUNTIME_REMOTE_APK" "$EVIDENCE_DIR/installed-base-before-runtime.apk" >/dev/null
PRE_RUNTIME_APK_SHA256="$(shasum -a 256 "$EVIDENCE_DIR/installed-base-before-runtime.apk" | awk '{print $1}')"
if [[ "$PRE_RUNTIME_APK_SHA256" != "$APK_SHA256" ]]; then
    echo "installed APK changed during reboot" >&2
    exit 1
fi
wait_for_health > "$EVIDENCE_DIR/health-after-reboot.json"
BOOT_TO_HEALTH_SECONDS=$((SECONDS - BOOT_COMPLETED_HOST_SECONDS))
require_agent_pid "$BOOT_PID" "pre-smoke readiness"
curl --noproxy '*' -fsS --max-time 3 \
    "http://127.0.0.1:$HOST_FORWARD_PORT/" \
    > "$EVIDENCE_DIR/spa-after-reboot.html"
if ! rg -q 'Drone Agent Companion' "$EVIDENCE_DIR/spa-after-reboot.html"; then
    echo "forwarded SPA is missing the expected application shell" >&2
    exit 1
fi
node "$REPO_ROOT/scripts/verify-forwarded-console.mjs" \
    127.0.0.1 "$HOST_FORWARD_PORT" \
    "$INSTALLED_PROTOCOL_DECODER" "$INSTALLED_CAPABILITY_MATRIX" \
    > "$EVIDENCE_DIR/ws-forwarded.json"
python3 -c \
    'import json,sys; d=json.load(open(sys.argv[1], encoding="utf-8")); assert d["ok"] is True; assert d["command"]["status"] == "succeeded"' \
    "$EVIDENCE_DIR/ws-forwarded.json"
WS_EVIDENCE_SHA256="$(shasum -a 256 "$EVIDENCE_DIR/ws-forwarded.json" | awk '{print $1}')"
if [[ ! "$WS_EVIDENCE_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "could not resolve a bounded SHA-256 identity for forwarded WebSocket evidence" >&2
    exit 1
fi
require_agent_pid "$BOOT_PID" "forwarded WebSocket smoke"
adb_cmd shell pm path "$PACKAGE_NAME" > "$EVIDENCE_DIR/package-path-after-runtime.txt"
RUNTIME_REMOTE_APK="$(sed -n 's/^package://p' "$EVIDENCE_DIR/package-path-after-runtime.txt" | head -1 | tr -d '\r')"
if [[ -z "$RUNTIME_REMOTE_APK" ]]; then
    echo "package disappeared during runtime verification" >&2
    exit 1
fi
adb_cmd pull "$RUNTIME_REMOTE_APK" "$EVIDENCE_DIR/installed-base-after-runtime.apk" >/dev/null
RUNTIME_APK_SHA256="$(shasum -a 256 "$EVIDENCE_DIR/installed-base-after-runtime.apk" | awk '{print $1}')"
if [[ "$RUNTIME_APK_SHA256" != "$APK_SHA256" ]]; then
    echo "installed APK changed during runtime verification" >&2
    exit 1
fi
adb_cmd shell dumpsys package "$PACKAGE_NAME" > "$EVIDENCE_DIR/package-after-reboot.txt"
wait_for_reboot_evidence "$BOOT_PID" "$REBOOT_EPOCH_FLOOR_MS"
python3 -c \
    'import json,sys; [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]' \
    "$EVIDENCE_DIR/lifecycle-after-reboot.jsonl"
require_agent_pid "$BOOT_PID" "pre-force-stop evidence capture"

echo "[headless-emulator] force-stop negative test (automatic recovery must not occur)"
adb_cmd shell am force-stop "$PACKAGE_NAME"
sleep 2
for _ in {1..10}; do
    if adb_cmd shell pidof "$AGENT_PROCESS" 2>/dev/null | rg -q '[0-9]'; then
        echo "force-stopped package unexpectedly recreated its agent process" >&2
        exit 1
    fi
    if curl --noproxy '*' -fsS --max-time 1 \
        "http://127.0.0.1:$HOST_FORWARD_PORT/healthz" >/dev/null 2>&1; then
        echo "force-stopped package unexpectedly kept the console reachable" >&2
        exit 1
    fi
    sleep 1
done
adb_cmd shell dumpsys package "$PACKAGE_NAME" > "$EVIDENCE_DIR/package-after-force-stop.txt"
adb_cmd shell pm path "$PACKAGE_NAME" > "$EVIDENCE_DIR/package-path-after-force-stop.txt"
FORCE_STOP_REMOTE_APK="$(sed -n 's/^package://p' "$EVIDENCE_DIR/package-path-after-force-stop.txt" | head -1 | tr -d '\r')"
if [[ -z "$FORCE_STOP_REMOTE_APK" ]]; then
    echo "package disappeared during force-stop verification" >&2
    exit 1
fi
adb_cmd pull "$FORCE_STOP_REMOTE_APK" "$EVIDENCE_DIR/installed-base-after-force-stop.apk" >/dev/null
FORCE_STOP_APK_SHA256="$(shasum -a 256 "$EVIDENCE_DIR/installed-base-after-force-stop.apk" | awk '{print $1}')"
if [[ "$FORCE_STOP_APK_SHA256" != "$APK_SHA256" ]]; then
    echo "installed APK changed during force-stop verification" >&2
    exit 1
fi

{
    printf '%s\n' "run_id=$RUN_ID"
    printf '%s\n' "commit=$CANDIDATE_COMMIT"
    printf '%s\n' "serial=$SANITIZED_ADB_SERIAL"
    printf '%s\n' "package=$PACKAGE_NAME"
    printf '%s\n' "environment=API 34 emulator"
    printf '%s\n' "fingerprint=$DEVICE_FINGERPRINT"
    printf '%s\n' "avd=$DEVICE_AVD"
    printf '%s\n' "avd_source=$DEVICE_AVD_SOURCE"
    printf '%s\n' "abi=$DEVICE_ABI"
    printf '%s\n' "apk_sha256=$APK_SHA256"
    printf '%s\n' "pre_runtime_apk_sha256=$PRE_RUNTIME_APK_SHA256"
    printf '%s\n' "runtime_apk_sha256=$RUNTIME_APK_SHA256"
    printf '%s\n' "force_stop_apk_sha256=$FORCE_STOP_APK_SHA256"
    printf '%s\n' "apk_build_worktree_state=$BUILT_APK_WORKTREE_STATE"
    printf '%s\n' "installed_console_protocol_sha256=$INSTALLED_PROTOCOL_DECODER_SHA256"
    printf '%s\n' "installed_capability_matrix_sha256=$INSTALLED_CAPABILITY_MATRIX_SHA256"
    printf '%s\n' "forwarded_websocket_evidence_sha256=$WS_EVIDENCE_SHA256"
    printf '%s\n' "boot_pid=$BOOT_PID"
    printf '%s\n' "boot_to_health_seconds=$BOOT_TO_HEALTH_SECONDS"
    printf '%s\n' "boot_to_health_deadline_seconds=$HEALTH_READY_TIMEOUT_SECONDS"
    printf '%s\n' "force_stop_expected_recovery=false"
    printf '%s\n' "scope=boot_console_force_stop"
    printf '%s\n' "connected_instrumentation_verified_by_this_script=false"
    printf '%s\n' "highest_claim=RUNTIME_VERIFIED"
    printf '%s\n' "g520_hardware_verified=false"
} > "$EVIDENCE_DIR/summary.txt"

echo "[headless-emulator] PASS evidence=$EVIDENCE_DIR"
