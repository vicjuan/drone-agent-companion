#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENDOR_ROOT="$REPO_ROOT/vendor/drone-agent-android"

if [[ ! -f "$VENDOR_ROOT/AGENTS.md" ]]; then
    echo "[companion-boundary] vendor submodule is missing; clone with --recurse-submodules" >&2
    exit 1
fi

if [[ -n "$(git -C "$VENDOR_ROOT" status --porcelain)" ]]; then
    echo "[companion-boundary] vendor submodule must remain read-only and clean" >&2
    git -C "$VENDOR_ROOT" status --short >&2
    exit 1
fi

for module in capability-matrix console-protocol console-server; do
    source_root="$REPO_ROOT/$module/src"
    if rg -n --glob '*.kt' \
        '^[[:space:]]*import[[:space:]]+(android|androidx|dji|org\.opencv)(\.|$)' \
        "$source_root"; then
        echo "[companion-boundary] $module must stay free of Android, DJI and OpenCV imports" >&2
        exit 1
    fi
done

if rg -n \
    'com\.durendal\.droneagent:|project\(":(core|gateway|vision|drone-actuation|adapter)' \
    "$REPO_ROOT/console-protocol/build.gradle.kts"; then
    echo "[companion-boundary] console-protocol must not depend on vendor implementation modules" >&2
    exit 1
fi

echo "[companion-boundary] PASS"
