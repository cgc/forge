#!/usr/bin/env bash
# ensure-robovm-bro-bridge.sh
#
# Ensures robovm-bro-bridge.jar is present in the unpacked MobiVM 2.3.23
# distribution directory used by robovm-maven-plugin.
#
# WHY THIS IS NEEDED
# ------------------
# The robovmx 10.2.2.4-SNAPSHOT compiler (build #13, Nov 2025, synchronized
# with MobiVM 2.3.24-SNAPSHOT) added a new field to Config$Home:
#
#   broBridgeJarPath = new File(homeDir, "lib/robovm-bro-bridge.jar")
#
# and a corresponding check in Config$Home.validate():
#
#   if (!broBridgeJarPath.exists() || !broBridgeJarPath.isFile()) {
#       throw new IllegalArgumentException(error
#           + relativize(broBridgeJarPath, homeDir) + " missing or invalid");
#   }
#
# Since relativize(homeDir/lib/robovm-bro-bridge.jar, homeDir) evaluates to
# "../..", this produces the error:
#
#   "Path .../unpacked/robovm-2.3.23 is not a valid RoboVM install directory:
#    ../.. missing or invalid"
#
# The standard MobiVM 2.3.23 dist tarball does NOT include robovm-bro-bridge.jar
# because in MobiVM the bro-bridge classes (org.robovm.rt.bro.*) are bundled
# inside robovm-rt.jar.  robovmx moved them to a separate module to keep its
# new libcore12 runtime clean.
#
# FIX
# ---
# We create robovm-bro-bridge.jar by extracting the org/robovm/rt/bro/**
# classes from the standard robovm-rt.jar (which IS in the MobiVM 2.3.23
# dist) and repackaging them.  The robovmx compiler adds both robovm-rt.jar
# and robovm-bro-bridge.jar to the AOT bootclasspath; since our robovm-rt.jar
# already contains the bro classes, the additional bro-bridge.jar is redundant
# for the standard-dist path but required to pass validation.
#
# This script is called from forge-gui-ios/pom.xml (exec-maven-plugin,
# prepare-package phase) so it runs before the robovm-maven-plugin's package
# phase where Config$Home.validate() is invoked.  It is also called from
# scripts/install-robovmx.sh so the dist is pre-populated before the first
# Maven build.
#
# Usage:
#   bash scripts/ensure-robovm-bro-bridge.sh
#
# Environment:
#   M2_REPO   Override the Maven local repository path.
#             Defaults to $HOME/.m2/repository.

set -euo pipefail

ROBOVM_VERSION="2.3.23"
DIST_URL="https://repo1.maven.org/maven2/com/mobidevelop/robovm/robovm-dist/${ROBOVM_VERSION}/robovm-dist-${ROBOVM_VERSION}-nocompiler.tar.gz"

M2_REPO="${M2_REPO:-${HOME}/.m2/repository}"
DIST_BASE="${M2_REPO}/com/mobidevelop/robovm/robovm-dist/${ROBOVM_VERSION}"
UNPACKED="${DIST_BASE}/unpacked/robovm-${ROBOVM_VERSION}"
BRO_BRIDGE="${UNPACKED}/lib/robovm-bro-bridge.jar"
RT_JAR="${UNPACKED}/lib/robovm-rt.jar"
DIST_TAR="${DIST_BASE}/robovm-dist-${ROBOVM_VERSION}-nocompiler.tar.gz"

# ---- Fast path: nothing to do -------------------------------------------------
if [ -f "${BRO_BRIDGE}" ]; then
    echo "[ensure-bro-bridge] robovm-bro-bridge.jar already present. OK."
    exit 0
fi

echo "[ensure-bro-bridge] robovm-bro-bridge.jar missing — creating it now..."

_sha1() {
    if command -v sha1sum >/dev/null 2>&1; then
        sha1sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 1 "$1" | cut -d' ' -f1
    else
        echo "[ensure-bro-bridge] WARNING: sha1sum/shasum not found; skipping checksum" >&2
        echo ""
    fi
}
_md5() {
    if command -v md5sum >/dev/null 2>&1; then
        md5sum "$1" | cut -d' ' -f1
    elif command -v md5 >/dev/null 2>&1; then
        md5 -q "$1"
    else
        echo "[ensure-bro-bridge] WARNING: md5sum/md5 not found; skipping checksum" >&2
        echo ""
    fi
}

# ---- Ensure robovm-rt.jar is available ----------------------------------------
# We need it to extract the org/robovm/rt/bro/ classes.
if [ ! -f "${RT_JAR}" ]; then
    # The unpacked dist directory does not exist yet (Maven has not run the
    # package phase, or ~/.m2 was cleaned).  We must pre-extract the tarball
    # so Maven's unpack() call finds the directory already present and skips
    # re-extraction — which would overwrite our bro-bridge.jar.

    if [ ! -f "${DIST_TAR}" ]; then
        echo "[ensure-bro-bridge] Downloading robovm-dist-${ROBOVM_VERSION}-nocompiler.tar.gz (~45 MB)..."
        mkdir -p "${DIST_BASE}"
        curl -fsSL -L "${DIST_URL}" -o "${DIST_TAR}"
        # Write Maven-compatible checksums so the plugin does not re-download.
        _sha1 "${DIST_TAR}" > "${DIST_TAR}.sha1"
        _md5  "${DIST_TAR}" > "${DIST_TAR}.md5"
    fi

    echo "[ensure-bro-bridge] Extracting dist tarball to ${DIST_BASE}/unpacked/ ..."
    mkdir -p "${DIST_BASE}/unpacked"
    tar -xzf "${DIST_TAR}" -C "${DIST_BASE}/unpacked"
fi

# ---- Build robovm-bro-bridge.jar from bro classes in robovm-rt.jar -----------
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "[ensure-bro-bridge] Extracting org/robovm/rt/bro/ classes from robovm-rt.jar ..."
(cd "${WORK}" && jar xf "${RT_JAR}" org/robovm/rt/bro)

# Minimal manifest; Implementation-Version must match the compiler version so
# Config.Home.validate()'s version check passes (it reads this attribute from
# the rt jar, not from bro-bridge, but we set it consistently).
printf 'Manifest-Version: 1.0\nImplementation-Version: %s\n\n' "${ROBOVM_VERSION}" \
    > "${WORK}/MANIFEST.MF"

(cd "${WORK}" && jar cfm "${BRO_BRIDGE}" MANIFEST.MF org/)
echo "[ensure-bro-bridge] Created ${BRO_BRIDGE}"
