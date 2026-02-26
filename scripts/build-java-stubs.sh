#!/usr/bin/env bash
# build-java-stubs.sh
#
# Compiles the Java 8 API stub classes from forge-gui-ios/src-java-stubs/ and
# installs the resulting jar into forge-gui-ios/local-repo/ as:
#
#     forge:java-stubs:1.0
#
# MobiVM's runtime (robovm-rt) is based on Android's class library, which predates
# Java 8 SE and is missing:
#
#   java.nio.file.*        – Paths, Files, Path, OpenOption
#   java.util.function.*   – Function, Consumer, Supplier, Predicate, BiFunction,
#                            BiConsumer, BinaryOperator, UnaryOperator, IntFunction,
#                            ToIntFunction, Int*/Double* primitive specialisations
#   java.util.stream.*     – Stream, IntStream, Collectors, Collector, StreamSupport
#   java.util.Optional     – Optional, OptionalInt, OptionalDouble
#   java.util.Spliterator  – Spliterator, Spliterator.OfInt
#
# The stubs implement just the subset used by forge-gui-mobile and its dependencies.
# Stream/IntStream are backed by ArrayList so all stream() / filter() / collect()
# calls work correctly at runtime on iOS.
#
# The stubs live in forge-gui-ios/src-java-stubs/. Because Java 17's module system
# rejects 'java.*' package declarations in the unnamed module, they must be compiled
# with --patch-module java.base=src-java-stubs. The resulting .class files are jarred
# and installed into forge-gui-ios/local-repo/ as a regular Maven compile dependency.
#
# forge-gui-ios/local-repo/ is listed in forge-gui-ios/.gitignore so the built jar
# is never committed. forge-gui-ios/pom.xml declares forge-local as a repository and
# lists forge:java-stubs:1.0 as a compile dependency.
#
# Usage:  bash scripts/build-java-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work on
# repeated runs.

set -euo pipefail

GROUP_ID="forge"
ARTIFACT_ID="java-stubs"
VERSION="1.0"
GROUP_PATH="forge/java-stubs"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

STUBS_SRC="$REPO_ROOT/forge-gui-ios/src-java-stubs"
LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/${GROUP_PATH}/${VERSION}"
MARKER="$DEST_DIR/.forge-built"

if [ -f "$MARKER" ]; then
    echo "[build-java-stubs] Already built (marker found). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[build-java-stubs] Compiling stubs from $STUBS_SRC ..."
mkdir -p "$WORK_DIR/classes"

# --patch-module java.base=<stubs dir> is required because the stubs declare classes
# in packages that belong to the java.base module (java.nio.file, java.util.function,
# java.util.stream, java.util.*).  Without it javac rejects them with
# "package exists in another module: java.base".
# This flag is only needed for this small compilation unit.
javac \
    --patch-module java.base="$STUBS_SRC" \
    -source 17 -target 17 \
    -d "$WORK_DIR/classes" \
    $(find "$STUBS_SRC" -name "*.java")

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

# Write SHA-1 and MD5 checksums so Maven doesn't warn about missing integrity files.
_sha1() { sha1sum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" | cut -d' ' -f1; }
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.md5"
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"

touch "$MARKER"

echo "[build-java-stubs] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
