#!/usr/bin/env bash
# desugar-streams.sh
#
# Build-time bytecode transformer for the iOS (MobiVM/RoboVM) build.
#
# MobiVM's robovm-rt is based on Android's Java-7 class library and lacks several
# Java-8 default methods added to java.util.Collection / java.lang.Iterable:
#   • Collection.stream()   (NoSuchMethodError at runtime on iOS)
#   • Iterable.spliterator()
#   • Arrays.stream(T[])
#
# Those methods cannot be patched via stub JARs because the pre-compiled
# librobovm-rt.a has fixed dispatch tables.  Instead, this script rewrites the
# compiled .class files to replace:
#
#   INVOKEINTERFACE/VIRTUAL *.stream   ()Stream            →  INVOKESTATIC StreamUtil.stream  (Iterable)Stream
#   INVOKEINTERFACE/VIRTUAL *.spliterator ()Spliterator    →  INVOKESTATIC StreamUtil.spliterator (Iterable)Spliterator
#   INVOKESTATIC java/util/Arrays.stream ([Object)Stream   →  INVOKESTATIC StreamUtil.stream  ([Object)Stream
#
# This is equivalent to what Android's D8/R8 "core library desugaring" does, but
# implemented as a lightweight build step for the RoboVM toolchain.
#
# USAGE
# -----
#   bash scripts/desugar-streams.sh <dir> [<dir2> ...]
#
# Run this AFTER "mvn compile" for the relevant modules and BEFORE the iOS package
# phase so that RoboVM AOT-compiles the transformed class files.
#
# When invoked from Maven's process-classes phase via forge-gui-ios/pom.xml, the
# sibling modules' target/classes/ directories are passed as arguments automatically.
#
# DEPENDENCIES
# ------------
# Requires: Java (javac + java), curl (to download ASM on first run).
# ASM 9.7 is downloaded once from Maven Central and cached in
# forge-gui-ios/local-repo/org/ow2/asm/ so subsequent runs are offline.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# ── ASM dependency ────────────────────────────────────────────────────────────
ASM_VERSION="9.7"
ASM_CACHE_DIR="$REPO_ROOT/forge-gui-ios/local-repo/org/ow2/asm/asm/$ASM_VERSION"
ASM_JAR="$ASM_CACHE_DIR/asm-${ASM_VERSION}.jar"

if [ ! -f "$ASM_JAR" ]; then
    echo "[desugar-streams] Downloading ASM ${ASM_VERSION} from Maven Central ..."
    mkdir -p "$ASM_CACHE_DIR"
    curl -fsSL \
        "https://repo1.maven.org/maven2/org/ow2/asm/asm/${ASM_VERSION}/asm-${ASM_VERSION}.jar" \
        -o "$ASM_JAR"
    echo "[desugar-streams]   ASM jar cached at $ASM_JAR"
fi

# ── Compile StreamDesugar.java ────────────────────────────────────────────────
TRANSFORMER_SRC="$SCRIPT_DIR/StreamDesugar.java"
echo "[desugar-streams] Compiling StreamDesugar.java ..."
TRANSFORMER_CLASS_DIR="$(mktemp -d)"
if [ ! -d "$TRANSFORMER_CLASS_DIR" ]; then
    echo "[desugar-streams] ERROR: failed to create temporary directory" >&2
    exit 1
fi
trap 'rm -rf "$TRANSFORMER_CLASS_DIR"' EXIT
javac -cp "$ASM_JAR" -d "$TRANSFORMER_CLASS_DIR" "$TRANSFORMER_SRC"

# ── Run transformer ───────────────────────────────────────────────────────────
if [ $# -eq 0 ]; then
    echo "[desugar-streams] No directories supplied – nothing to transform." >&2
    exit 0
fi

echo "[desugar-streams] Transforming class files in: $*"
java -cp "$ASM_JAR:$TRANSFORMER_CLASS_DIR" StreamDesugar "$@"
