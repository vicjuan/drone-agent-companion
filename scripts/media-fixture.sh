#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_FILE="$REPO_ROOT/config/media/mediamtx-mac-fixture.yml"
SOURCE_SCRIPT="$SCRIPT_DIR/media_fixture_source.py"
PUBLISHER_SCRIPT="$SCRIPT_DIR/media_fixture_publisher.py"
STOP_REQUEST_SCRIPT="$SCRIPT_DIR/media_fixture_stop_request.py"
STATE_DIR="$REPO_ROOT/.drone-agent-companion/media-fixture"
CID_FILE="$STATE_DIR/mediamtx.cid"
PUBLISHER_PID_FILE="$STATE_DIR/publisher-supervisor.pid"
PUBLISHER_LOG="$STATE_DIR/publisher.log"
RUN_NONCE_FILE="$STATE_DIR/run-nonce"
LOCK_DIR="$STATE_DIR/.operation.lock"
LOCK_OWNER_FILE="$LOCK_DIR/pid"

MEDIAMTX_VERSION="1.19.1"
MEDIAMTX_DIGEST="sha256:61ebddaa43a6da78d4c6e98b9f9c12066856ffd85893656f5c000d870b88bbe4"
MEDIAMTX_IMAGE="bluenviron/mediamtx:${MEDIAMTX_VERSION}@${MEDIAMTX_DIGEST}"
CONTAINER_NAME="drone-agent-companion-media-fixture"
OWNERSHIP_LABEL_KEY="com.durendal.drone-agent-companion.fixture"
OWNERSHIP_LABEL_VALUE="issue-7a-mac-whep"
PUBLISHER_OWNERSHIP_TAG="drone-agent-companion-issue-7a-mac-whep"

RTMP_PORT="1936"
WEBRTC_HTTP_PORT="8891"
WEBRTC_ICE_UDP_PORT="8190"
STREAM_PATH="mock-main"
RTMP_URL="rtmp://127.0.0.1:${RTMP_PORT}/${STREAM_PATH}"
WEBRTC_PAGE_URL="http://127.0.0.1:${WEBRTC_HTTP_PORT}/${STREAM_PATH}"
WHEP_URL="${WEBRTC_PAGE_URL}/whep"

SOURCE_WIDTH="640"
SOURCE_HEIGHT="360"
SOURCE_FPS="30"
SOURCE_GOP="30"

LOCK_HELD=0
STARTED_CONTAINER_ID=""
STARTED_PUBLISHER_PID=""
PUBLISHER_REAPED=0
RUN_NONCE=""
STOP_REQUEST_FILE=""

log() {
    printf '[media-fixture] %s\n' "$*"
}

warn() {
    printf '[media-fixture] WARNING: %s\n' "$*" >&2
}

die() {
    printf '[media-fixture] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: scripts/media-fixture.sh <command>

Commands:
  run            Own this repo's MediaMTX + publisher in the foreground until HUP/INT/TERM.
  verify         Verify ownership, exact loopback publishes, host listeners and HTTP page.
  status         Report owned container and publisher-supervisor state without changing it.
  stop           Create only this foreground run's nonce-scoped cooperative stop request.
  static-verify  Validate pinned source/config without starting Docker, MediaMTX or ffmpeg.
EOF
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
}

ensure_state_directory() {
    if [[ -L "$STATE_DIR" ]]; then
        die "state path must not be a symlink: $STATE_DIR"
    fi
    if [[ -e "$STATE_DIR" && ! -d "$STATE_DIR" ]]; then
        die "state path is not a directory: $STATE_DIR"
    fi
    mkdir -p "$STATE_DIR"
    chmod 700 "$STATE_DIR"
}

acquire_operation_lock() {
    ensure_state_directory
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        LOCK_HELD=1
        printf '%s\n' "$$" > "$LOCK_OWNER_FILE"
        chmod 600 "$LOCK_OWNER_FILE"
        return
    fi

    local owner="unknown"
    if [[ -f "$LOCK_OWNER_FILE" && ! -L "$LOCK_OWNER_FILE" ]]; then
        owner="$(sed -n '1p' "$LOCK_OWNER_FILE")"
    fi
    die "another operation owns $LOCK_DIR (pid=$owner); verify it is not running before manual cleanup"
}

release_operation_lock() {
    if [[ "$LOCK_HELD" -eq 1 ]]; then
        rm -f "$LOCK_OWNER_FILE"
        rmdir "$LOCK_DIR" 2>/dev/null || true
        LOCK_HELD=0
    fi
}

write_state_file() {
    local destination="$1"
    local value="$2"
    local temporary="${destination}.tmp.$$"
    if [[ -L "$destination" ]]; then
        die "refusing to replace symlinked state file: $destination"
    fi
    printf '%s\n' "$value" > "$temporary"
    chmod 600 "$temporary"
    mv -f "$temporary" "$destination"
}

read_state_file() {
    local source="$1"
    [[ -f "$source" && ! -L "$source" ]] || return 1
    local line_count
    line_count="$(wc -l < "$source" | tr -d '[:space:]')"
    [[ "$line_count" == "1" ]] || return 1
    sed -n '1p' "$source"
}

load_run_control_state() {
    local nonce
    nonce="$(read_state_file "$RUN_NONCE_FILE" 2>/dev/null || true)"
    [[ "${#nonce}" -eq 64 && "$nonce" != *[!0-9a-f]* ]] || return 1
    RUN_NONCE="$nonce"
    STOP_REQUEST_FILE="$STATE_DIR/stop-request.$RUN_NONCE"
}

require_exact_config_assignment() {
    local key="$1"
    local expected="$2"
    local line_count
    line_count="$(grep -Fxc "${key}: ${expected}" "$CONFIG_FILE" || true)"
    [[ "$line_count" == "1" ]] || die "config must contain exactly '${key}: ${expected}'"
}

verify_config_static() {
    [[ -f "$CONFIG_FILE" && ! -L "$CONFIG_FILE" ]] || die "MediaMTX config is missing or symlinked"
    [[ -f "$SOURCE_SCRIPT" && ! -L "$SOURCE_SCRIPT" ]] || die "RGB fixture source is missing or symlinked"
    [[ -f "$PUBLISHER_SCRIPT" && ! -L "$PUBLISHER_SCRIPT" ]] || {
        die "publisher supervisor is missing or symlinked"
    }
    [[ -f "$STOP_REQUEST_SCRIPT" && ! -L "$STOP_REQUEST_SCRIPT" ]] || {
        die "stop-request helper is missing or symlinked"
    }

    require_exact_config_assignment "api" "false"
    require_exact_config_assignment "metrics" "false"
    require_exact_config_assignment "pprof" "false"
    require_exact_config_assignment "playback" "false"
    require_exact_config_assignment "rtsp" "false"
    require_exact_config_assignment "rtmp" "true"
    require_exact_config_assignment "rtmpAddress" ":${RTMP_PORT}"
    require_exact_config_assignment "hls" "false"
    require_exact_config_assignment "webrtc" "true"
    require_exact_config_assignment "webrtcAddress" ":${WEBRTC_HTTP_PORT}"
    require_exact_config_assignment "webrtcLocalUDPAddress" ":${WEBRTC_ICE_UDP_PORT}"
    require_exact_config_assignment "webrtcLocalTCPAddress" '""'
    require_exact_config_assignment "webrtcIPsFromInterfaces" "false"
    require_exact_config_assignment "webrtcAdditionalHosts" "[127.0.0.1]"
    require_exact_config_assignment "webrtcICEServers2" "[]"
    require_exact_config_assignment "webrtcAllowOrigins" '["http://127.0.0.1:8891"]'
    require_exact_config_assignment "srt" "false"
    require_exact_config_assignment "moq" "false"

    local configured_paths
    configured_paths="$(
        awk '
            /^paths:$/ { in_paths = 1; next }
            in_paths && /^  [^[:space:]#][^:]*:$/ { print }
        ' "$CONFIG_FILE"
    )"
    [[ "$configured_paths" == "  ${STREAM_PATH}:" ]] || {
        printf '%s\n' "$configured_paths" >&2
        die "config must define exactly one literal path: $STREAM_PATH"
    }
    if grep -Eq 'all_others|^[[:space:]]*~' "$CONFIG_FILE"; then
        die "wildcard or regex MediaMTX paths are forbidden in the Mac fixture"
    fi
    [[ "$(grep -Fxc '        path: mock-main' "$CONFIG_FILE" || true)" == "2" ]] || {
        die "anonymous permissions must be restricted to publish/read on mock-main"
    }
    [[ "$(grep -Fxc '    overridePublisher: false' "$CONFIG_FILE" || true)" == "1" ]] || {
        die "mock-main must reject a second publisher"
    }
    [[ "$(grep -Fxc '    record: false' "$CONFIG_FILE" || true)" == "1" ]] || {
        die "mock-main recording must remain disabled"
    }
}

verify_fixed_constants() {
    [[ "$MEDIAMTX_IMAGE" == \
        "bluenviron/mediamtx:1.19.1@sha256:61ebddaa43a6da78d4c6e98b9f9c12066856ffd85893656f5c000d870b88bbe4" ]] || {
        die "MediaMTX image lock changed"
    }
    [[ "$CONTAINER_NAME" == "drone-agent-companion-media-fixture" ]] || {
        die "fixture container name changed"
    }
    [[ "$OWNERSHIP_LABEL_KEY" == "com.durendal.drone-agent-companion.fixture" &&
        "$OWNERSHIP_LABEL_VALUE" == "issue-7a-mac-whep" ]] || {
        die "container ownership label changed"
    }
    [[ "$PUBLISHER_OWNERSHIP_TAG" == "drone-agent-companion-issue-7a-mac-whep" ]] || {
        die "publisher ownership marker changed"
    }
    [[ "$RTMP_URL" == "rtmp://127.0.0.1:1936/mock-main" ]] || die "RTMP fixture URL changed"
    [[ "$WHEP_URL" == "http://127.0.0.1:8891/mock-main/whep" ]] || die "WHEP fixture URL changed"
}

verify_python_source() {
    require_command python3
    python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)' || {
        die "Python 3.9 or newer is required for the raw-RGB fixture"
    }
    PYTHONDONTWRITEBYTECODE=1 python3 "$SOURCE_SCRIPT" --self-test >/dev/null || {
        die "raw-RGB fixture self-test failed"
    }
    PYTHONDONTWRITEBYTECODE=1 python3 "$PUBLISHER_SCRIPT" --help >/dev/null || {
        die "publisher supervisor import/argument self-check failed"
    }
    PYTHONDONTWRITEBYTECODE=1 python3 "$STOP_REQUEST_SCRIPT" --help >/dev/null || {
        die "stop-request helper import/argument self-check failed"
    }
}

static_verify() {
    local invocation="${1:-embedded}"
    bash -n "$0"
    verify_fixed_constants
    verify_config_static
    verify_python_source
    log "STATIC PASS image=$MEDIAMTX_IMAGE path=$STREAM_PATH"
    if [[ "$invocation" == "standalone" ]]; then
        log "No Docker, MediaMTX or streaming process was started."
    fi
}

require_runtime_tools() {
    require_command docker
    require_command ffmpeg
    require_command curl
    require_command lsof
    verify_python_source
    docker info >/dev/null 2>&1 || die "Docker daemon is unavailable"
    if ! ffmpeg -hide_banner -h encoder=libx264 2>&1 | grep -Fq 'Encoder libx264'; then
        die "ffmpeg does not provide the required libx264 encoder"
    fi
}

validate_container_id() {
    local container_id="$1"
    [[ "${#container_id}" -eq 64 && "$container_id" != *[!0-9a-f]* ]]
}

validate_owned_container() {
    local container_id="$1"
    validate_container_id "$container_id" || {
        warn "invalid MediaMTX CID in $CID_FILE"
        return 1
    }
    if ! docker container inspect "$container_id" >/dev/null 2>&1; then
        command -v docker >/dev/null 2>&1 || return 3
        docker info >/dev/null 2>&1 || return 3
        return 2
    fi

    local actual_id actual_name actual_image actual_label
    actual_id="$(docker inspect --format '{{.Id}}' "$container_id")"
    actual_name="$(docker inspect --format '{{.Name}}' "$container_id")"
    actual_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
    actual_label="$(
        docker inspect \
            --format '{{ index .Config.Labels "com.durendal.drone-agent-companion.fixture" }}' \
            "$container_id"
    )"
    if [[ "$actual_id" != "$container_id" ||
        "$actual_name" != "/${CONTAINER_NAME}" ||
        "$actual_image" != "$MEDIAMTX_IMAGE" ||
        "$actual_label" != "$OWNERSHIP_LABEL_VALUE" ]]; then
        warn "container identity does not match this fixture; refusing ownership"
        return 1
    fi
}

validate_pid() {
    local pid="$1"
    [[ -n "$pid" && "$pid" != *[!0-9]* && "$pid" -gt 1 ]]
}

validate_owned_publisher() {
    local pid="$1"
    validate_pid "$pid" || {
        warn "invalid publisher-supervisor PID in $PUBLISHER_PID_FILE"
        return 1
    }
    kill -0 "$pid" 2>/dev/null || return 2

    local command_line
    command_line="$(ps -ww -p "$pid" -o command= 2>/dev/null || true)"
    if [[ "$command_line" != *"$PUBLISHER_SCRIPT"* ||
        "$command_line" != *"--source-script $SOURCE_SCRIPT"* ||
        "$command_line" != *"--width $SOURCE_WIDTH"* ||
        "$command_line" != *"--height $SOURCE_HEIGHT"* ||
        "$command_line" != *"--fps $SOURCE_FPS"* ||
        "$command_line" != *"--gop $SOURCE_GOP"* ||
        "$command_line" != *"--ownership-tag $PUBLISHER_OWNERSHIP_TAG"* ||
        "$command_line" != *"--stop-request-file $STOP_REQUEST_FILE"* ||
        "$command_line" != *"$RTMP_URL"* ]]; then
        warn "PID $pid is not this fixture's publisher supervisor; refusing ownership"
        return 1
    fi
}

cleanup_started_resources() {
    local publisher_cleanup_safe=1
    if [[ -n "$STARTED_PUBLISHER_PID" ]]; then
        if [[ "$PUBLISHER_REAPED" -ne 1 ]]; then
            if PYTHONDONTWRITEBYTECODE=1 python3 "$STOP_REQUEST_SCRIPT" request \
                --state-dir "$STATE_DIR" >/dev/null; then
                # wait addresses the shell's exact child job; it never signals a numeric PID.
                # The supervisor consumes the nonce request and owns child termination/reaping.
                wait "$STARTED_PUBLISHER_PID" 2>/dev/null || true
                PUBLISHER_REAPED=1
            else
                publisher_cleanup_safe=0
                warn "could not create the run-owned stop request; publisher/container state was retained"
            fi
        fi
        if [[ "$publisher_cleanup_safe" -eq 1 && -f "$PUBLISHER_PID_FILE" ]] &&
            [[ "$(read_state_file "$PUBLISHER_PID_FILE" 2>/dev/null || true)" == "$STARTED_PUBLISHER_PID" ]]; then
            rm -f "$PUBLISHER_PID_FILE"
        fi
    fi
    if [[ "$publisher_cleanup_safe" -ne 1 ]]; then
        return
    fi
    if [[ -n "$STARTED_CONTAINER_ID" ]]; then
        local container_cleanup_safe=1
        if validate_owned_container "$STARTED_CONTAINER_ID" >/dev/null 2>&1; then
            if ! docker rm -f "$STARTED_CONTAINER_ID" >/dev/null 2>&1; then
                if validate_owned_container "$STARTED_CONTAINER_ID" >/dev/null 2>&1; then
                    container_cleanup_safe=0
                else
                    local container_validation=$?
                    [[ "$container_validation" -eq 2 ]] || container_cleanup_safe=0
                fi
            fi
        else
            local container_validation=$?
            [[ "$container_validation" -eq 2 ]] || container_cleanup_safe=0
        fi
        if [[ "$container_cleanup_safe" -eq 1 && -f "$CID_FILE" ]] &&
            [[ "$(read_state_file "$CID_FILE" 2>/dev/null || true)" == "$STARTED_CONTAINER_ID" ]]; then
            rm -f "$CID_FILE"
        elif [[ "$container_cleanup_safe" -ne 1 ]]; then
            warn "container cleanup could not prove an owned removal; CID state was retained"
        fi
    fi
    if [[ "$publisher_cleanup_safe" -eq 1 && -n "$RUN_NONCE" ]]; then
        PYTHONDONTWRITEBYTECODE=1 python3 "$STOP_REQUEST_SCRIPT" finish \
            --state-dir "$STATE_DIR" \
            --nonce "$RUN_NONCE" >/dev/null || {
            warn "run nonce cleanup failed; the next run will refuse stale control state"
        }
    fi
}

on_run_exit() {
    local exit_code="$1"
    cleanup_started_resources
    release_operation_lock
    return "$exit_code"
}

wait_for_http_page() {
    local attempt=0
    while [[ "$attempt" -lt 50 ]]; do
        if curl --noproxy '*' --fail --silent --show-error --max-time 1 \
            "$WEBRTC_PAGE_URL" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.1
        attempt=$((attempt + 1))
    done
    return 1
}

wait_for_publisher_stability() {
    local pid="$1"
    local attempt=0
    while [[ "$attempt" -lt 10 ]]; do
        kill -0 "$pid" 2>/dev/null || return 1
        sleep 0.1
        attempt=$((attempt + 1))
    done
    validate_owned_publisher "$pid"
}

run_fixture() {
    acquire_operation_lock
    trap 'on_run_exit $?' EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    static_verify
    require_runtime_tools
    if [[ -e "$CID_FILE" || -L "$CID_FILE" ||
        -e "$PUBLISHER_PID_FILE" || -L "$PUBLISHER_PID_FILE" ||
        -e "$RUN_NONCE_FILE" || -L "$RUN_NONCE_FILE" ]]; then
        die "fixture state already exists; run status, then the ownership-safe stop command"
    fi
    RUN_NONCE="$(
        PYTHONDONTWRITEBYTECODE=1 python3 "$STOP_REQUEST_SCRIPT" init --state-dir "$STATE_DIR"
    )" || die "could not initialize the run-specific stop nonce"
    STOP_REQUEST_FILE="$STATE_DIR/stop-request.$RUN_NONCE"
    if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
        die "a container already uses the exact fixture name; no ownership token exists, so it will not be touched"
    fi
    if [[ -L "$PUBLISHER_LOG" ]]; then
        die "publisher log path must not be a symlink"
    fi
    printf '\n=== media fixture run %s ===\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$PUBLISHER_LOG"

    log "starting pinned MediaMTX container"
    STARTED_CONTAINER_ID="$(
        docker run --detach --pull missing \
            --name "$CONTAINER_NAME" \
            --label "${OWNERSHIP_LABEL_KEY}=${OWNERSHIP_LABEL_VALUE}" \
            --restart no \
            --network bridge \
            --read-only \
            --tmpfs /tmp:rw,noexec,nosuid,size=16m \
            --cap-drop ALL \
            --security-opt no-new-privileges \
            --publish "127.0.0.1:${RTMP_PORT}:${RTMP_PORT}/tcp" \
            --publish "127.0.0.1:${WEBRTC_HTTP_PORT}:${WEBRTC_HTTP_PORT}/tcp" \
            --publish "127.0.0.1:${WEBRTC_ICE_UDP_PORT}:${WEBRTC_ICE_UDP_PORT}/udp" \
            --mount "type=bind,src=${CONFIG_FILE},dst=/mediamtx.yml,readonly" \
            "$MEDIAMTX_IMAGE"
    )"
    validate_owned_container "$STARTED_CONTAINER_ID" || die "started container failed ownership validation"
    write_state_file "$CID_FILE" "$STARTED_CONTAINER_ID"

    if ! wait_for_http_page; then
        docker logs "$STARTED_CONTAINER_ID" >&2 || true
        die "MediaMTX player page did not become ready within 5 seconds"
    fi

    log "starting foreground-owned publisher supervisor"
    PYTHONDONTWRITEBYTECODE=1 python3 -u "$PUBLISHER_SCRIPT" \
        --source-script "$SOURCE_SCRIPT" \
        --ffmpeg-bin ffmpeg \
        --width "$SOURCE_WIDTH" \
        --height "$SOURCE_HEIGHT" \
        --fps "$SOURCE_FPS" \
        --gop "$SOURCE_GOP" \
        --rtmp-url "$RTMP_URL" \
        --ownership-tag "$PUBLISHER_OWNERSHIP_TAG" \
        --stop-request-file "$STOP_REQUEST_FILE" \
        >> "$PUBLISHER_LOG" 2>&1 < /dev/null &
    STARTED_PUBLISHER_PID="$!"
    write_state_file "$PUBLISHER_PID_FILE" "$STARTED_PUBLISHER_PID"

    if ! wait_for_publisher_stability "$STARTED_PUBLISHER_PID"; then
        docker logs "$STARTED_CONTAINER_ID" >&2 || true
        tail -n 40 "$PUBLISHER_LOG" >&2 || true
        die "publisher supervisor did not remain healthy for its first second"
    fi

    release_operation_lock
    log "RUNNING foreground_owner=$$ publisher_pid=$STARTED_PUBLISHER_PID"
    log "RTMP=$RTMP_URL browser=$WEBRTC_PAGE_URL"
    log "Keep this process open; use another shell for '$0 verify' and browser acceptance."

    local publisher_status=0
    if wait "$STARTED_PUBLISHER_PID"; then
        publisher_status=0
    else
        publisher_status=$?
    fi
    PUBLISHER_REAPED=1
    if [[ "$publisher_status" -eq 0 ]]; then
        log "publisher supervisor stopped cooperatively; foreground fixture is shutting down"
        return 0
    fi
    warn "publisher supervisor failed with status=$publisher_status; foreground fixture is shutting down"
    return "$publisher_status"
}

container_running() {
    local container_id="$1"
    [[ "$(docker inspect --format '{{.State.Running}}' "$container_id")" == "true" ]]
}

status_fixture() {
    local status_code=0
    local container_id=""
    local publisher_pid=""

    if ! load_run_control_state; then
        warn "active run nonce is missing or invalid"
        status_code=1
    fi

    if [[ -f "$CID_FILE" || -L "$CID_FILE" ]]; then
        container_id="$(read_state_file "$CID_FILE" 2>/dev/null || true)"
        if ! validate_owned_container "$container_id"; then
            warn "MediaMTX state is stale or not owned"
            status_code=1
        elif container_running "$container_id"; then
            log "MediaMTX=RUNNING cid=$container_id image=$MEDIAMTX_IMAGE"
        else
            warn "owned MediaMTX container is stopped"
            status_code=1
        fi
    else
        log "MediaMTX=ABSENT (no owned CID file)"
        if command -v docker >/dev/null 2>&1 &&
            docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
            warn "exact fixture container name exists without this script's CID token; it will not be touched"
            status_code=1
        else
            status_code=1
        fi
    fi

    if [[ -f "$PUBLISHER_PID_FILE" || -L "$PUBLISHER_PID_FILE" ]]; then
        publisher_pid="$(read_state_file "$PUBLISHER_PID_FILE" 2>/dev/null || true)"
        if validate_owned_publisher "$publisher_pid"; then
            log "publisher-supervisor=RUNNING pid=$publisher_pid output=$RTMP_URL"
        else
            warn "publisher-supervisor state is stale or not owned"
            status_code=1
        fi
    else
        log "publisher-supervisor=ABSENT (no owned PID file)"
        status_code=1
    fi
    return "$status_code"
}

verify_docker_port_bindings() {
    local container_id="$1"
    local network_mode
    network_mode="$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$container_id")"
    [[ "$network_mode" == "bridge" ]] || die "fixture must use Docker bridge networking, found: $network_mode"

    local actual expected
    actual="$(docker port "$container_id" | LC_ALL=C sort)"
    expected="$(
        printf '%s\n' \
            "${RTMP_PORT}/tcp -> 127.0.0.1:${RTMP_PORT}" \
            "${WEBRTC_ICE_UDP_PORT}/udp -> 127.0.0.1:${WEBRTC_ICE_UDP_PORT}" \
            "${WEBRTC_HTTP_PORT}/tcp -> 127.0.0.1:${WEBRTC_HTTP_PORT}" |
            LC_ALL=C sort
    )"
    if [[ "$actual" != "$expected" ]]; then
        printf 'expected:\n%s\nactual:\n%s\n' "$expected" "$actual" >&2
        die "Docker host publishes differ from the exact loopback allowlist"
    fi
}

assert_tcp_loopback_listener() {
    local port="$1"
    local entries
    entries="$(lsof -nP -a -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 {print $9}')"
    [[ -n "$entries" ]] || die "no Mac TCP listener is visible on port $port"
    while IFS= read -r entry; do
        [[ "$entry" == "127.0.0.1:${port}" ]] || {
            printf 'unexpected TCP listener: %s\n' "$entry" >&2
            die "Mac TCP port $port is not loopback-only"
        }
    done <<< "$entries"
}

assert_udp_loopback_listener() {
    local port="$1"
    local entries
    entries="$(lsof -nP -a -iUDP:"$port" 2>/dev/null | awk 'NR > 1 {print $9}')"
    [[ -n "$entries" ]] || die "no Mac UDP listener is visible on port $port"
    while IFS= read -r entry; do
        [[ "$entry" == "127.0.0.1:${port}" ]] || {
            printf 'unexpected UDP listener: %s\n' "$entry" >&2
            die "Mac UDP port $port is not loopback-only"
        }
    done <<< "$entries"
}

verify_fixture() {
    static_verify
    require_runtime_tools
    load_run_control_state || die "active run nonce is missing or invalid"

    local container_id publisher_pid
    container_id="$(read_state_file "$CID_FILE" 2>/dev/null || true)"
    publisher_pid="$(read_state_file "$PUBLISHER_PID_FILE" 2>/dev/null || true)"
    validate_owned_container "$container_id" || die "owned MediaMTX container is unavailable"
    container_running "$container_id" || die "owned MediaMTX container is not running"
    validate_owned_publisher "$publisher_pid" || die "owned publisher supervisor is unavailable"

    verify_docker_port_bindings "$container_id"
    assert_tcp_loopback_listener "$RTMP_PORT"
    assert_tcp_loopback_listener "$WEBRTC_HTTP_PORT"
    assert_udp_loopback_listener "$WEBRTC_ICE_UDP_PORT"
    curl --noproxy '*' --fail --silent --show-error --max-time 3 \
        "$WEBRTC_PAGE_URL" >/dev/null || die "MediaMTX WebRTC page is unavailable"

    log "VERIFY PASS container=$container_id publisher_pid=$publisher_pid"
    log "Host listeners are exactly 127.0.0.1:${RTMP_PORT}/tcp, 127.0.0.1:${WEBRTC_HTTP_PORT}/tcp and 127.0.0.1:${WEBRTC_ICE_UDP_PORT}/udp."
    log "Browser playback still requires a visible frame and POST /${STREAM_PATH}/whep evidence."
}

stop_fixture() {
    ensure_state_directory
    [[ -f "$STOP_REQUEST_SCRIPT" && ! -L "$STOP_REQUEST_SCRIPT" ]] || {
        die "stop-request helper is missing or symlinked"
    }
    require_command python3
    local request_path
    request_path="$(
        PYTHONDONTWRITEBYTECODE=1 python3 "$STOP_REQUEST_SCRIPT" request --state-dir "$STATE_DIR"
    )" || die "no active foreground run accepted the cooperative stop request"
    log "STOP REQUESTED path=$request_path"
    log "No PID was inspected or signaled; the foreground supervisor will reap its direct children."
}

main() {
    umask 077
    local command="${1:-}"
    [[ "$#" -le 1 ]] || {
        usage >&2
        exit 2
    }
    case "$command" in
        run) run_fixture ;;
        verify) verify_fixture ;;
        status) status_fixture ;;
        stop) stop_fixture ;;
        static-verify) static_verify standalone ;;
        -h|--help|help) usage ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
}

main "$@"
