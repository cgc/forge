#!/usr/bin/env bash
# patch-commons-lang3.sh
#
# Downloads commons-lang3-3.18.0.jar from Maven Central, applies the
# stream-desugaring transformation (replacing String.codePoints() with
# StreamUtil.codePoints()), and installs the result to forge-gui-ios/local-repo/
# as:
#
#     org.apache.commons:commons-lang3:3.18.0-ios
#
# WHY THIS IS NEEDED
# ------------------
# commons-lang3 3.12+ rewrote StringUtils.capitalize() and uncapitalize()
# to use String.codePoints(), a Java 8 method that is absent from MobiVM's
# robovm-rt (the Android 7-era runtime).  Calling either method at runtime
# on iOS throws:
#
#   NoSuchMethodError: java.lang.String.codePoints()Ljava/util/stream/IntStream;
#
# Because String is a class PRESENT in robovm-rt (not a missing class), it
# cannot be patched via stub JARs — the bootstrap classloader always serves
# robovm-rt's version.  The bytecode transformer in scripts/StreamDesugar.java
# already knows how to rewrite String.codePoints() call sites to
# StreamUtil.codePoints(), but the transformer is only applied to the Forge
# module JARs during the build, not to third-party dependencies.
#
# This script applies that same transformation to a copy of commons-lang3 and
# installs the result under a distinct version string (3.18.0-ios) into the
# forge-local Maven repository (forge-gui-ios/local-repo/).  forge-gui-ios/pom.xml
# declares commons-lang3:3.18.0-ios as a direct dependency; Maven's
# "nearest-wins" mediation then selects this patched version over the transitive
# 3.18.0 that the forge-* modules bring in, ensuring RoboVM AOT-compiles the
# desugared bytecode.
#
# USAGE
# -----
#   bash scripts/patch-commons-lang3.sh
#
# Must be run once per development environment, after cloning.  It is idempotent:
# a hash-based marker file prevents redundant work.  Re-run automatically if the
# script itself changes.
#
# DEPENDENCIES
# ------------
# Requires: Java (javac + java), curl.
# ASM 9.7 is downloaded on first run by desugar-streams.sh and cached in
# forge-gui-ios/local-repo/org/ow2/asm/.

set -euo pipefail

COMMONS_LANG3_VERSION="3.18.0"
IOS_VERSION="${COMMONS_LANG3_VERSION}-ios"
GROUP_PATH="org/apache/commons/commons-lang3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/$GROUP_PATH/$IOS_VERSION"
MARKER="$DEST_DIR/.forge-built"

# ── Idempotency check ─────────────────────────────────────────────────────────
_script_hash() {
    if command -v sha1sum >/dev/null 2>&1; then
        sha1sum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    else
        echo "[patch-commons-lang3] ERROR: neither sha1sum nor shasum found." >&2
        exit 1
    fi
}
SCRIPT_HASH="$(_script_hash)"

if [ -n "$SCRIPT_HASH" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$SCRIPT_HASH" ]; then
    echo "[patch-commons-lang3] Already patched (script unchanged). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# ── Download commons-lang3 from Maven Central ─────────────────────────────────
MAVEN_URL="https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/${COMMONS_LANG3_VERSION}/commons-lang3-${COMMONS_LANG3_VERSION}.jar"
echo "[patch-commons-lang3] Downloading commons-lang3-${COMMONS_LANG3_VERSION}.jar ..."
curl -fsSL "$MAVEN_URL" -o "$WORK_DIR/commons-lang3.jar"
echo "[patch-commons-lang3]   download complete"

# ── Apply stream-desugaring transformation ────────────────────────────────────
# desugar-streams.sh rewrites String.codePoints() call sites inside the JAR to
# StreamUtil.codePoints(CharSequence), among many other Java-8+ desugaring
# rewrites.  The transformation is idempotent and operates in-place on the file
# passed to it.
echo "[patch-commons-lang3] Applying stream-desugaring transformation ..."
bash "$SCRIPT_DIR/desugar-streams.sh" "$WORK_DIR/commons-lang3.jar"
echo "[patch-commons-lang3]   transformation complete"

# ── Install patched JAR to forge-local repository ─────────────────────────────
JAR_NAME="commons-lang3-${IOS_VERSION}.jar"
POM_NAME="commons-lang3-${IOS_VERSION}.pom"

mkdir -p "$DEST_DIR"
cp "$WORK_DIR/commons-lang3.jar" "$DEST_DIR/$JAR_NAME"

cat > "$DEST_DIR/$POM_NAME" << POM_EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-lang3</artifactId>
  <version>${IOS_VERSION}</version>
</project>
POM_EOF

# Write SHA-1 and MD5 checksums so Maven doesn't warn about missing integrity files.
_sha1() { sha1sum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" | cut -d' ' -f1; }
_sha1 "$DEST_DIR/$JAR_NAME" > "$DEST_DIR/$JAR_NAME.sha1"
_md5  "$DEST_DIR/$JAR_NAME" > "$DEST_DIR/$JAR_NAME.md5"
_sha1 "$DEST_DIR/$POM_NAME" > "$DEST_DIR/$POM_NAME.sha1"
_md5  "$DEST_DIR/$POM_NAME" > "$DEST_DIR/$POM_NAME.md5"

echo "$SCRIPT_HASH" > "$MARKER"
echo "[patch-commons-lang3] Done. org.apache.commons:commons-lang3:${IOS_VERSION} installed to local-repo."
