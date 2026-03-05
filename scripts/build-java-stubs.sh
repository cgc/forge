#!/usr/bin/env bash
# build-java-stubs.sh
#
# Builds a minimal stub jar containing the one Java API missing from robovmx's
# libcore12 runtime and installs it into forge-gui-ios/local-repo/ as:
#
#     forge:java-stubs:3.0
#
# robovmx's runtime is based on Android 12's libcore (libcore12).  It natively
# provides all Java 8+ APIs including java.util.stream.*, java.util.function.*,
# java.util.Optional*, java.util.StringJoiner, java.util.concurrent.CompletableFuture,
# java.nio.file.*, java.time.*, and all Java 8 default methods on Collection, Map,
# Iterable, Comparator, Objects, etc.
#
# The ONE class still missing from libcore12 is:
#
#   java.lang.Record  – abstract base class for Java 16+ record types.
#                       Android 12 predates Java 16 records, so this class is
#                       absent from libcore12.  Every Java record implicitly
#                       extends Record; without it RoboVM's AOT compiler fails
#                       with NoClassDefFoundError.
#
# java.lang.Record is downloaded directly from the OpenJDK 17 source tree (it
# has no jdk.internal.* imports) and compiled with --patch-module java.base.
#
# INSTALL LOCATION
# ----------------
# forge-gui-ios/local-repo/ is listed in .gitignore so the built jar is never
# committed.  forge-gui-ios/pom.xml declares forge-local as a repository and
# lists forge:java-stubs:3.0 as a compile dependency so that RoboVM's AOT
# compiler includes the class in the native binary.
#
# Usage:  bash scripts/build-java-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work.

set -euo pipefail

GROUP_ID="forge"
ARTIFACT_ID="java-stubs"
VERSION="3.0"
GROUP_PATH="forge/java-stubs"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/${GROUP_PATH}/${VERSION}"
MARKER="$DEST_DIR/.forge-built"

_script_hash() {
    if command -v sha1sum >/dev/null 2>&1; then
        sha1sum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    else
        echo "[build-java-stubs] ERROR: neither sha1sum nor shasum found; cannot compute script hash." >&2
        exit 1
    fi
}
SCRIPT_HASH="$(_script_hash)"

if [ -n "$SCRIPT_HASH" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$SCRIPT_HASH" ]; then
    echo "[build-java-stubs] Already built (script unchanged). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$WORK_DIR/src/java/lang" \
         "$WORK_DIR/classes"

# ---------------------------------------------------------------------------
# Download java.lang.Record from a pinned OpenJDK 17 source release.
#
# Record is a trivial abstract class (no jdk.internal.* imports) that every
# Java record implicitly extends.  Android 12's libcore predates Java 16 records
# so Record is absent from robovmx's runtime.
#
# Pinned tag: jdk-17+35  (JDK 17 GA)
# ---------------------------------------------------------------------------
OPENJDK_TAG_URL="jdk-17%2B35"
OPENJDK_BASE="https://raw.githubusercontent.com/openjdk/jdk/${OPENJDK_TAG_URL}/src/java.base/share/classes"

echo "[build-java-stubs] Downloading java.lang.Record from OpenJDK jdk-17+35 ..."
curl -fsSL "$OPENJDK_BASE/java/lang/Record.java" \
    -o "$WORK_DIR/src/java/lang/Record.java"
echo "[build-java-stubs]   downloaded java.lang.Record"

# ---------------------------------------------------------------------------
# Compile.
# --patch-module java.base is required for javac to accept source files in the
# java.* package without "package exists in another module" errors.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Compiling ..."
javac \
    --patch-module java.base="$WORK_DIR/src" \
    -source 17 -target 17 \
    -d "$WORK_DIR/classes" \
    "$WORK_DIR/src/java/lang/Record.java"
echo "[build-java-stubs]   compiled $(find "$WORK_DIR/classes" -name '*.class' | wc -l | tr -d ' ') class file(s)"

# ---------------------------------------------------------------------------
# Package and install.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Packaging jar ..."
jar cf "$WORK_DIR/${ARTIFACT_ID}-${VERSION}.jar" -C "$WORK_DIR/classes" .

mkdir -p "$DEST_DIR"
echo "[build-java-stubs] Installing as ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} into $DEST_DIR ..."
cp "$WORK_DIR/${ARTIFACT_ID}-${VERSION}.jar" "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar"

cat > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" << POM_EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP_ID}</groupId>
  <artifactId>${ARTIFACT_ID}</artifactId>
  <version>${VERSION}</version>
</project>
POM_EOF

_sha1() { sha1sum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" | cut -d' ' -f1; }
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.md5"
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"

echo "$SCRIPT_HASH" > "$MARKER"
echo "[build-java-stubs] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
