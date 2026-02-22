#!/usr/bin/env bash
# ios-device-build.sh — Build the Forge iOS IPA and optionally deploy it to a
# connected physical device.
#
# Usage:
#   ./forge-gui-ios/scripts/ios-device-build.sh [OPTIONS]
#
# Options:
#   --deploy              Install the IPA on the first connected device via
#                         ios-deploy after a successful build.
#   --sign-identity ID    Signing identity (overrides robovm.properties).
#                         Example: "iPhone Developer: Your Name (TEAMID)"
#   --provisioning NAME   Provisioning profile name or UUID (overrides
#                         robovm.properties).
#   --skip-build          Skip the Maven build; only deploy an existing IPA.
#   --help                Show this message and exit.
#
# Prerequisites:
#   - macOS with Xcode and command-line tools installed.
#   - Java 17 and Maven on PATH.
#   - Signing identity and provisioning profile configured (see
#     docs/Development/iOS-Builds.md).
#   - ios-deploy (brew install ios-deploy) if --deploy is used.
#
# Run from the repository root or from any directory — the script locates the
# repo root via the location of this script file.

set -euo pipefail

# ---------------------------------------------------------------------------
# Resolve paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IOS_MODULE="${REPO_ROOT}/forge-gui-ios"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
DEPLOY=false
SKIP_BUILD=false
SIGN_IDENTITY=""
PROVISIONING_PROFILE=""

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --deploy)
            DEPLOY=true
            shift
            ;;
        --sign-identity)
            SIGN_IDENTITY="$2"
            shift 2
            ;;
        --provisioning)
            PROVISIONING_PROFILE="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --help|-h)
            sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | grep '^#' | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Run with --help for usage." >&2
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Prerequisite checks
# ---------------------------------------------------------------------------
check_command() {
    local cmd="$1" hint="$2"
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: '$cmd' not found. ${hint}" >&2
        exit 1
    fi
}

check_command java  "Install JDK 17 and ensure it is on PATH."
check_command mvn   "Install Maven and ensure it is on PATH."

if [[ "$(uname)" != "Darwin" ]]; then
    echo "ERROR: iOS builds require macOS." >&2
    exit 1
fi

if ! xcode-select -p &>/dev/null; then
    echo "ERROR: Xcode command-line tools not found." >&2
    echo "       Run: xcode-select --install" >&2
    exit 1
fi

if [[ "${DEPLOY}" == true ]]; then
    check_command ios-deploy "Install via: brew install ios-deploy"
fi

# ---------------------------------------------------------------------------
# Determine the project version (used to locate the IPA)
# ---------------------------------------------------------------------------
VERSION="$(cd "${REPO_ROOT}" && mvn help:evaluate \
    -Dexpression=revision -q -DforceStdout 2>/dev/null)"
IPA_PATH="${IOS_MODULE}/target/forge-ios-${VERSION}.ipa"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD}" == false ]]; then
    echo "==> Building Forge iOS (version ${VERSION}) …"

    EXTRA_ARGS=()
    if [[ -n "${SIGN_IDENTITY}" ]]; then
        EXTRA_ARGS+=("-Drobovm.iosSignIdentity=${SIGN_IDENTITY}")
    fi
    if [[ -n "${PROVISIONING_PROFILE}" ]]; then
        EXTRA_ARGS+=("-Drobovm.iosProvisioningProfile=${PROVISIONING_PROFILE}")
    fi

    (cd "${REPO_ROOT}" && mvn -U -B clean -P ios-device install "${EXTRA_ARGS[@]}")

    echo "==> Build complete."
    echo "    IPA: ${IPA_PATH}"
else
    echo "==> Skipping build (--skip-build)."
    if [[ ! -f "${IPA_PATH}" ]]; then
        echo "ERROR: IPA not found at ${IPA_PATH}" >&2
        echo "       Remove --skip-build to trigger a fresh build." >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------
if [[ "${DEPLOY}" == true ]]; then
    echo "==> Checking for connected iOS device …"
    if ! ios-deploy --detect --timeout 5 &>/dev/null; then
        echo "ERROR: No device detected. Connect a device and trust this Mac." >&2
        exit 1
    fi

    echo "==> Installing ${IPA_PATH} on device …"
    ios-deploy --bundle "${IPA_PATH}" --justlaunch --debug

    echo "==> Done. Forge should now be launching on the device."
    echo "    For on-device logs run:"
    echo "      idevicesyslog | grep -E 'forge|Forge|GDX'"
fi
