#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONTROLLER="$SCRIPT_DIR/media-fixture.sh"
SOURCE_TEST="$SCRIPT_DIR/media_fixture_source_test.py"
PUBLISHER="$SCRIPT_DIR/media_fixture_publisher.py"
PUBLISHER_TEST="$SCRIPT_DIR/media_fixture_publisher_test.py"
STOP_REQUEST="$SCRIPT_DIR/media_fixture_stop_request.py"
STOP_REQUEST_TEST="$SCRIPT_DIR/media_fixture_stop_request_test.py"
CONFIG="$REPO_ROOT/config/media/mediamtx-mac-fixture.yml"

fail() {
    printf '[media-fixture-static-test] FAIL: %s\n' "$*" >&2
    exit 1
}

assert_fixed_count() {
    local expected="$1"
    local needle="$2"
    local file="$3"
    local actual
    actual="$(grep -F -c -- "$needle" "$file" || true)"
    [[ "$actual" == "$expected" ]] || {
        fail "expected $expected occurrence(s) of '$needle' in $file, found $actual"
    }
}

bash -n "$CONTROLLER"
"$CONTROLLER" static-verify
PYTHONDONTWRITEBYTECODE=1 python3 "$SOURCE_TEST"
PYTHONDONTWRITEBYTECODE=1 python3 "$PUBLISHER_TEST"
PYTHONDONTWRITEBYTECODE=1 python3 "$STOP_REQUEST_TEST"

assert_fixed_count 1 \
    'bluenviron/mediamtx:1.19.1@sha256:61ebddaa43a6da78d4c6e98b9f9c12066856ffd85893656f5c000d870b88bbe4' \
    "$CONTROLLER"
assert_fixed_count 1 'CONTAINER_NAME="drone-agent-companion-media-fixture"' "$CONTROLLER"
assert_fixed_count 1 'OWNERSHIP_LABEL_VALUE="issue-7a-mac-whep"' "$CONTROLLER"
assert_fixed_count 1 'PUBLISHER_OWNERSHIP_TAG="drone-agent-companion-issue-7a-mac-whep"' "$CONTROLLER"
assert_fixed_count 1 '--network bridge' "$CONTROLLER"
assert_fixed_count 0 '--network host' "$CONTROLLER"
assert_fixed_count 1 'run) run_fixture ;;' "$CONTROLLER"
assert_fixed_count 0 'start) start_fixture ;;' "$CONTROLLER"
assert_fixed_count 1 'if wait "$STARTED_PUBLISHER_PID"; then' "$CONTROLLER"
assert_fixed_count 0 'nohup' "$CONTROLLER"
assert_fixed_count 1 'STOP REQUESTED path=$request_path' "$CONTROLLER"
assert_fixed_count 0 'kill -TERM' "$CONTROLLER"
assert_fixed_count 0 'kill -KILL' "$CONTROLLER"
assert_fixed_count 3 '--publish "127.0.0.1:' "$CONTROLLER"
assert_fixed_count 3 '--publish ' "$CONTROLLER"
assert_fixed_count 0 'drone-platform-mediamtx' "$CONTROLLER"
assert_fixed_count 0 'docker stop --time 5 "$CONTAINER_NAME"' "$CONTROLLER"
assert_fixed_count 0 'docker rm "$CONTAINER_NAME"' "$CONTROLLER"
assert_fixed_count 0 'docker rm -f "$CONTAINER_NAME"' "$CONTROLLER"
assert_fixed_count 0 'docker container prune' "$CONTROLLER"
assert_fixed_count 0 'pkill' "$CONTROLLER"
assert_fixed_count 0 'killall' "$CONTROLLER"
assert_fixed_count 1 'rtmp://127.0.0.1:${RTMP_PORT}/${STREAM_PATH}' "$CONTROLLER"

assert_fixed_count 1 'EXPECTED_OWNERSHIP_TAG = "drone-agent-companion-issue-7a-mac-whep"' "$PUBLISHER"
assert_fixed_count 1 '"-profile:v",' "$PUBLISHER"
assert_fixed_count 1 '"-bf",' "$PUBLISHER"
assert_fixed_count 1 '"-g",' "$PUBLISHER"
assert_fixed_count 1 '"-keyint_min",' "$PUBLISHER"
assert_fixed_count 1 '"-sc_threshold",' "$PUBLISHER"
assert_fixed_count 1 'config.rtmp_url,' "$PUBLISHER"
assert_fixed_count 1 'STOP_REQUEST_NAME_PATTERN = re.compile' "$PUBLISHER"

assert_fixed_count 1 'secrets.token_hex(32)' "$STOP_REQUEST"
assert_fixed_count 1 'os.O_WRONLY | os.O_CREAT | os.O_EXCL' "$STOP_REQUEST"
assert_fixed_count 1 'STOP_REQUEST_PREFIX = "stop-request."' "$STOP_REQUEST"

assert_fixed_count 1 'rtmpAddress: :1936' "$CONFIG"
assert_fixed_count 1 'webrtcAddress: :8891' "$CONFIG"
assert_fixed_count 1 'webrtcLocalUDPAddress: :8190' "$CONFIG"
assert_fixed_count 1 'webrtcAdditionalHosts: [127.0.0.1]' "$CONFIG"
assert_fixed_count 1 'webrtcAllowOrigins: ["http://127.0.0.1:8891"]' "$CONFIG"
assert_fixed_count 0 '127.0.0.1:8080' "$CONFIG"
assert_fixed_count 0 '127.0.0.1:18081' "$CONFIG"
assert_fixed_count 1 'moq: false' "$CONFIG"
assert_fixed_count 1 '  mock-main:' "$CONFIG"
assert_fixed_count 0 'all_others' "$CONFIG"

printf '[media-fixture-static-test] PASS: source/config/ownership invariants; no runtime started\n'
