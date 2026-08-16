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

for module in capability-matrix commissioning-network console-protocol console-server console-adapter-mock; do
    source_root="$REPO_ROOT/$module/src"
    if rg -n --glob '*.kt' \
        '^[[:space:]]*import[[:space:]]+(android|androidx|dji|org\.opencv)(\.|$)' \
        "$source_root"; then
        echo "[companion-boundary] $module must stay free of Android, DJI and OpenCV imports" >&2
        exit 1
    fi
done

for module in vision-opencv-core vision-opencv-desktop; do
    source_root="$REPO_ROOT/$module/src"
    if rg -n --glob '*.kt' \
        '^[[:space:]]*import[[:space:]]+(android|androidx|dji)(\.|$)' \
        "$source_root"; then
        echo "[companion-boundary] $module must stay free of Android and DJI imports" >&2
        exit 1
    fi
done

if rg -n --glob '*.kt' \
    '^[[:space:]]*import[[:space:]]+(org\.opencv|nu\.pattern)(\.|$)' \
    "$REPO_ROOT/host-headless/src/main"; then
    echo "[companion-boundary] host-headless must consume only the vendor-neutral OpenCV facade" >&2
    exit 1
fi

vision_wiring=(
    "$REPO_ROOT/host-headless/src/main/kotlin/com/durendal/droneagent/companion/host/HeadlessOpenCvVision.kt"
    "$REPO_ROOT/host-headless/src/main/kotlin/com/durendal/droneagent/companion/host/HeadlessOpenCvObservationSession.kt"
    "$REPO_ROOT/host-headless/src/main/kotlin/com/durendal/droneagent/companion/host/OpenCvObservationEvidence.kt"
)
if rg -n \
    '^[[:space:]]*import[[:space:]]+.*(adapter|gateway|console|admission|actuation|virtualstick|closedloop)|BodyFrameVelocityCommand|CommandAdmissionPolicy' \
    "${vision_wiring[@]}"; then
    echo "[companion-boundary] OpenCV observation wiring must not reach control or admission" >&2
    exit 1
fi
if rg -n \
    'DecodedFrameToLuminance|nu\.pattern|org\.openpnp|Executors\.newFixedThreadPool|Executors\.newCachedThreadPool' \
    "${vision_wiring[@]}"; then
    echo "[companion-boundary] OpenCV observation wiring must reuse the reviewed bridge and Android runtime" >&2
    exit 1
fi
if ! rg -q 'LiveVisionBridge' \
    "$REPO_ROOT/host-headless/src/main/kotlin/com/durendal/droneagent/companion/host/HeadlessOpenCvObservationSession.kt"; then
    echo "[companion-boundary] decoded-frame observation must use vendor LiveVisionBridge" >&2
    exit 1
fi

if rg -n --glob '*.kt' \
    '^[[:space:]]*import[[:space:]]+nu\.pattern(\.|$)' \
    "$REPO_ROOT/vision-opencv-android/src"; then
    echo "[companion-boundary] Android artifacts must not import the desktop OpenCV loader" >&2
    exit 1
fi

if rg -n \
    '^[[:space:]]*(api|implementation|runtimeOnly|compileOnly)\([^)]*org\.openpnp:opencv' \
    "$REPO_ROOT/vision-opencv-android/build.gradle.kts" \
    "$REPO_ROOT/host-headless/build.gradle.kts"; then
    echo "[companion-boundary] Android artifacts must not depend on the desktop OpenCV runtime" >&2
    exit 1
fi

if rg -n \
    'com\.durendal\.droneagent:|project\(":(core|gateway|vision|drone-actuation|adapter)' \
    "$REPO_ROOT/console-protocol/build.gradle.kts"; then
    echo "[companion-boundary] console-protocol must not depend on vendor implementation modules" >&2
    exit 1
fi

if rg -n --glob '*.kt' \
    '^[[:space:]]*import[[:space:]]+(com\.durendal\.droneagent\.(adapter\.mock|companion\.console\.mock)|dji)(\.|$)' \
    "$REPO_ROOT/host-headless/src/main"; then
    echo "[companion-boundary] Android common source must remain free of mock and DJI implementations" >&2
    exit 1
fi

if rg -n \
    '^[[:space:]]*implementation\((project\(":console-adapter-mock"\)|"com\.durendal\.droneagent:adapter-mock:)' \
    "$REPO_ROOT/host-headless/build.gradle.kts"; then
    echo "[companion-boundary] mock adapter dependencies must remain mock-flavor-only" >&2
    exit 1
fi

if ! rg -q \
    '"mockImplementation"\(project\(":console-adapter-mock"\)\)' \
    "$REPO_ROOT/host-headless/build.gradle.kts"; then
    echo "[companion-boundary] mock flavor must consume the shared console-adapter-mock module" >&2
    exit 1
fi

echo "[companion-boundary] PASS"
