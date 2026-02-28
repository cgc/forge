#!/usr/bin/env bash
# desugar-streams.sh
#
# Build-time bytecode transformer for the iOS (MobiVM/RoboVM) build.
#
# MobiVM's robovm-rt is based on Android's Java-7 class library and lacks several
# Java-8 methods:
#   • Collection.stream()          (NoSuchMethodError at runtime on iOS)
#   • Iterable.spliterator()
#   • Arrays.stream(T[])
#   • File.toPath()
#   • BufferedReader.lines()
#
# Those methods cannot be patched via stub JARs because the pre-compiled
# librobovm-rt.a has fixed dispatch tables.  Instead, this script rewrites the
# compiled .class files to replace call sites with equivalent calls to
# forge.util.StreamUtil, which provides compatible implementations.
#
# This is equivalent to what Android's D8/R8 "core library desugaring" does, but
# implemented as a lightweight build step for the RoboVM toolchain.
#
# USAGE
# -----
#   bash scripts/desugar-streams.sh <dir|jar> [<dir2|jar2> ...]
#
# Each argument may be a directory of .class files OR a .jar file.
# JARs are unpacked to a temporary directory, transformed in-place, then repacked.
#
# Run this AFTER "mvn compile" for the relevant modules and BEFORE the iOS package
# phase so that RoboVM AOT-compiles the transformed class files.
#
# When invoked from Maven's process-classes phase via forge-gui-ios/pom.xml, both
# the sibling modules' target/classes/ directories AND their packaged JARs are
# passed.  Both are needed: in a Maven reactor build the sibling modules complete
# their package phase before forge-gui-ios starts, so RoboVM receives the JAR
# artifacts (not target/classes) on its compile classpath.
#
# DEPENDENCIES
# ------------
# Requires: Java (javac + java), unzip, zip, curl (to download ASM on first run).
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
UNPACK_DIRS=()
trap 'rm -rf "$TRANSFORMER_CLASS_DIR" "${UNPACK_DIRS[@]+"${UNPACK_DIRS[@]}"}";' EXIT
javac -cp "$ASM_JAR" -d "$TRANSFORMER_CLASS_DIR" "$TRANSFORMER_SRC"

# ── Run transformer ───────────────────────────────────────────────────────────
if [ $# -eq 0 ]; then
    echo "[desugar-streams] No directories supplied – nothing to transform." >&2
    exit 0
fi

# Separate directories from JAR files; expand JARs into temporary directories
# so that StreamDesugar receives only directory paths.
DIRS_TO_TRANSFORM=()
for arg in "$@"; do
    if [ -d "$arg" ]; then
        DIRS_TO_TRANSFORM+=("$arg")
    elif [ -f "$arg" ] && [[ "$arg" == *.jar ]]; then
        tmp_dir="$(mktemp -d)"
        UNPACK_DIRS+=("$tmp_dir")
        # Record the original JAR path so we can repack it afterwards.
        echo "$arg" > "$tmp_dir/.source_jar"
        unzip -q "$arg" -d "$tmp_dir"
        DIRS_TO_TRANSFORM+=("$tmp_dir")
    else
        echo "[desugar-streams] WARNING: skipping '$arg' (not a directory or .jar file)" >&2
    fi
done

if [ ${#DIRS_TO_TRANSFORM[@]} -eq 0 ]; then
    echo "[desugar-streams] No valid targets – nothing to transform." >&2
    exit 0
fi

echo "[desugar-streams] Transforming class files in: ${DIRS_TO_TRANSFORM[*]}"
java -cp "$ASM_JAR:$TRANSFORMER_CLASS_DIR" StreamDesugar "${DIRS_TO_TRANSFORM[@]}"

# Repack any JARs that were unpacked into temporary directories.
for tmp_dir in "${UNPACK_DIRS[@]+"${UNPACK_DIRS[@]}"}"; do
    source_jar="$(cat "$tmp_dir/.source_jar")"
    rm "$tmp_dir/.source_jar"
    # Create a unique temp path for the repacked JAR.  We get a safe unique
    # name from mktemp (portable across GNU/Linux and BSD/macOS), remove the
    # empty placeholder file mktemp creates, then add a .jar extension so zip
    # creates a fresh archive.
    tmp_jar_base="$(mktemp)"
    rm -f "$tmp_jar_base"
    tmp_jar="${tmp_jar_base}.jar"
    (cd "$tmp_dir" && zip -qr "$tmp_jar" .)
    mv "$tmp_jar" "$source_jar"
    echo "[desugar-streams] Repacked $source_jar"
done
