#!/usr/bin/env bash
# build-nio-file-stubs.sh
#
# Compiles the java.nio.file stub classes from forge-gui-ios/src-nio-stubs/ and
# installs the resulting jar into forge-gui-ios/local-repo/ as:
#
#     forge:nio-file-stubs:1.0
#
# MobiVM's runtime (robovm-rt) ships java.nio.charset and java.nio.channels but
# NOT java.nio.file.*. The stubs implement just the subset of the API used by
# forge-gui-mobile (Files.exists, Files.newInputStream, Files.newOutputStream,
# Paths.get, Path, OpenOption) on top of java.io.File so that RoboVM's AOT
# compiler can resolve all java.nio.file references without NoClassDefFoundError.
#
# The stubs live in forge-gui-ios/src-nio-stubs/java/nio/file/. Because Java 17's
# module system rejects 'java.*' package declarations in the unnamed module,
# they must be compiled with --patch-module java.base=src-nio-stubs. The resulting
# .class files are then jarred and treated as an ordinary Maven compile dependency
# so that both javac and RoboVM's AOT pass see them on the classpath.
#
# forge-gui-ios/local-repo/ is listed in forge-gui-ios/.gitignore so the built jar
# is never committed. forge-gui-ios/pom.xml declares forge-local as a repository
# and lists forge:nio-file-stubs:1.0 as a compile dependency, so Maven resolves it
# automatically once this script has run.
#
# Usage:  bash scripts/build-nio-file-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work on
# repeated runs.

set -euo pipefail

GROUP_ID="forge"
ARTIFACT_ID="nio-file-stubs"
VERSION="1.0"
GROUP_PATH="forge/nio-file-stubs"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

STUBS_SRC="$REPO_ROOT/forge-gui-ios/src-nio-stubs"
LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/${GROUP_PATH}/${VERSION}"
MARKER="$DEST_DIR/.forge-built"

if [ -f "$MARKER" ]; then
    echo "[build-nio-file-stubs] Already built (marker found). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[build-nio-file-stubs] Compiling stubs from $STUBS_SRC ..."
mkdir -p "$WORK_DIR/classes"

# --patch-module java.base=<stubs dir> is required because the stubs declare
# classes in the java.nio.file package, which belongs to the java.base module.
# Without it javac rejects the package declaration as "package exists in
# another module: java.base".  This flag is only needed for this small
# compilation unit; the rest of forge-gui-ios compiles normally.
javac \
    --patch-module java.base="$STUBS_SRC" \
    -source 17 -target 17 \
    -d "$WORK_DIR/classes" \
    "$STUBS_SRC/java/nio/file/OpenOption.java" \
    "$STUBS_SRC/java/nio/file/Path.java" \
    "$STUBS_SRC/java/nio/file/Paths.java" \
    "$STUBS_SRC/java/nio/file/Files.java"

echo "[build-nio-file-stubs] Packaging jar ..."
jar cf "$WORK_DIR/${ARTIFACT_ID}-${VERSION}.jar" -C "$WORK_DIR/classes" .

mkdir -p "$DEST_DIR"

echo "[build-nio-file-stubs] Installing as ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} into $DEST_DIR ..."
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

# Write SHA-1 and MD5 checksums so Maven doesn't warn about missing integrity files.
_sha1() { sha1sum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" | cut -d' ' -f1; }
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.md5"
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"

touch "$MARKER"

echo "[build-nio-file-stubs] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
