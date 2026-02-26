#!/usr/bin/env bash
# build-java-stubs.sh
#
# Builds a supplement jar containing the Java 8 APIs missing from MobiVM's robovm-rt
# and installs it into forge-gui-ios/local-repo/ as:
#
#     forge:java-stubs:1.0
#
# MobiVM's runtime (robovm-rt) is based on Android's class library, which predates
# Java 8 SE and is missing:
#
#   java.util.function.*   – all 43 functional interfaces
#   java.util.Optional     – Optional, OptionalInt, OptionalDouble, OptionalLong
#   java.util.Spliterator  – Spliterator and its primitive specialisation inner interfaces
#   java.util.stream.*     – Stream, IntStream, Collector, Collectors, StreamSupport
#   java.nio.file.*        – Paths, Files, Path, OpenOption
#
# HOW THE JAR IS BUILT
# --------------------
# java.util.function.*, java.util.Optional*, and java.util.Spliterator* are pure
# interfaces / value types with no JDK-internal dependencies.  Rather than
# maintaining hand-written copies, this script extracts the real .class files
# directly from the running JVM's class library (java.base module on JDK 9+, or
# rt.jar on JDK 8).  This guarantees correctness and eliminates the maintenance
# burden of keeping stub sources in sync.
#
# java.util.stream.* and java.nio.file.* cannot be taken wholesale from the JVM
# because their implementations reference jdk.internal.* classes absent from
# robovm-rt (e.g. jdk.internal.access.SharedSecrets used by Collectors and
# ReferencePipeline), and because java.nio.file requires a FileSystemProvider
# infrastructure that does not exist on iOS.  These packages are instead compiled
# from the minimal source stubs in forge-gui-ios/src-java-stubs/:
#
#   java/util/stream/Stream.java          – interface (subset of methods used by forge)
#   java/util/stream/IntStream.java       – interface
#   java/util/stream/Collector.java       – interface
#   java/util/stream/Collectors.java      – toList/toSet/toMap/joining/groupingBy
#   java/util/stream/StreamSupport.java   – factory (unused at runtime, satisfies javac)
#   java/util/stream/ListStream.java      – Stream impl backed by ArrayList
#   java/util/stream/ArrayIntStream.java  – IntStream impl backed by int[]
#   java/nio/file/Path.java               – thin wrapper over java.io.File
#   java/nio/file/Paths.java              – Paths.get() factory
#   java/nio/file/Files.java              – exists/newInputStream/newOutputStream
#   java/nio/file/OpenOption.java         – marker interface
#
# INSTALL LOCATION
# ----------------
# forge-gui-ios/local-repo/ is listed in .gitignore so the built jar is never
# committed.  forge-gui-ios/pom.xml declares forge-local as a repository and
# lists forge:java-stubs:1.0 as a compile dependency so that RoboVM's AOT
# compiler includes these classes in the native binary.
#
# Usage:  bash scripts/build-java-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work.

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
mkdir -p "$WORK_DIR/classes/java/util/function" \
         "$WORK_DIR/classes/java/util" \
         "$WORK_DIR/jmod-extract"

# ---------------------------------------------------------------------------
# Step 1 – Extract java.util.function.*, java.util.Optional*, and
#          java.util.Spliterator* .class files directly from the running JVM.
#
# These are pure interfaces / value types with no jdk.internal dependencies,
# so they are safe to lift verbatim and will work correctly on MobiVM.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Extracting JVM classes (function/Optional/Spliterator) ..."

JAVA_EXECUTABLE="$(which java)"
# readlink -f is GNU-only (not available on macOS/BSD); resolve symlinks portably
_realpath() { local p="$1"; while [ -L "$p" ]; do p="$(readlink "$p")"; done; echo "$p"; }
JAVA_EXECUTABLE="$(_realpath "$JAVA_EXECUTABLE")"
JAVA_HOME_DETECTED="$(dirname "$(dirname "$JAVA_EXECUTABLE")")"

if [ -f "$JAVA_HOME_DETECTED/jmods/java.base.jmod" ]; then
    # JDK 9+ – extract from the java.base module
    "$JAVA_HOME_DETECTED/bin/jmod" extract \
        --dir "$WORK_DIR/jmod-extract" \
        "$JAVA_HOME_DETECTED/jmods/java.base.jmod"
    JVM_CLASSES="$WORK_DIR/jmod-extract/classes"
elif [ -f "$JAVA_HOME_DETECTED/jre/lib/rt.jar" ]; then
    # JDK 8 (Linux / older macOS) – extract from rt.jar
    cd "$WORK_DIR/jmod-extract"
    jar xf "$JAVA_HOME_DETECTED/jre/lib/rt.jar" \
        java/util/function \
        java/util/Optional.class java/util/OptionalDouble.class \
        java/util/OptionalInt.class java/util/OptionalLong.class \
        java/util/Spliterator.class
    cd - > /dev/null
    JVM_CLASSES="$WORK_DIR/jmod-extract"
elif [ -f "$JAVA_HOME_DETECTED/lib/rt.jar" ]; then
    # JDK 8 (some macOS layouts)
    cd "$WORK_DIR/jmod-extract"
    jar xf "$JAVA_HOME_DETECTED/lib/rt.jar" \
        java/util/function \
        java/util/Optional.class java/util/OptionalDouble.class \
        java/util/OptionalInt.class java/util/OptionalLong.class \
        java/util/Spliterator.class
    cd - > /dev/null
    JVM_CLASSES="$WORK_DIR/jmod-extract"
else
    echo "[build-java-stubs] ERROR: cannot locate java.base.jmod or rt.jar under $JAVA_HOME_DETECTED" >&2
    exit 1
fi

# Copy the selected packages into our staging classes dir
cp -r "$JVM_CLASSES/java/util/function/." "$WORK_DIR/classes/java/util/function/"
for f in Optional.class OptionalDouble.class OptionalInt.class OptionalLong.class; do
    src="$JVM_CLASSES/java/util/$f"
    if [ -f "$src" ]; then
        cp "$src" "$WORK_DIR/classes/java/util/"
    else
        echo "[build-java-stubs]   WARNING: $f not found in JVM classes (skipping)" >&2
    fi
done
# Spliterator interface and its primitive specialisation inner classes
found_spliterator=0
for f in "$JVM_CLASSES/java/util/Spliterator.class" \
         "$JVM_CLASSES/java/util/Spliterator\$OfDouble.class" \
         "$JVM_CLASSES/java/util/Spliterator\$OfInt.class" \
         "$JVM_CLASSES/java/util/Spliterator\$OfLong.class" \
         "$JVM_CLASSES/java/util/Spliterator\$OfPrimitive.class"; do
    if [ -f "$f" ]; then
        cp "$f" "$WORK_DIR/classes/java/util/"
        found_spliterator=$((found_spliterator + 1))
    fi
done
[ "$found_spliterator" -eq 0 ] && echo "[build-java-stubs]   WARNING: no Spliterator classes found in JVM" >&2
echo "[build-java-stubs]   extracted $(find "$WORK_DIR/classes" -name '*.class' | wc -l | tr -d ' ') JVM classes"

# ---------------------------------------------------------------------------
# Step 2 – Compile the remaining stubs from source.
#
# java.util.stream.* and java.nio.file.* cannot be taken from the JVM because
# their implementations reference jdk.internal.* absent from robovm-rt, so we
# compile minimal working implementations from forge-gui-ios/src-java-stubs/.
#
# --patch-module java.base=<src> is required for javac to accept source files
# in java.* packages without the "package exists in another module" error.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Compiling stream/nio stubs from $STUBS_SRC ..."
# shellcheck disable=SC2046
javac \
    --patch-module java.base="$STUBS_SRC" \
    -source 17 -target 17 \
    -d "$WORK_DIR/classes" \
    $(find "$STUBS_SRC" -name "*.java")
echo "[build-java-stubs]   total classes: $(find "$WORK_DIR/classes" -name '*.class' | wc -l | tr -d ' ')"

# ---------------------------------------------------------------------------
# Step 3 – Package and install.
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

# Write SHA-1 and MD5 checksums so Maven doesn't warn about missing integrity files.
_sha1() { sha1sum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" | cut -d' ' -f1; }
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.md5"
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"

touch "$MARKER"
echo "[build-java-stubs] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
