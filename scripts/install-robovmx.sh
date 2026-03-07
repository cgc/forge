#!/usr/bin/env bash
# install-robovmx.sh
#
# Clones the robovmx fork of RoboVM (experiment/2-libcore-10 branch) and
# installs its Maven artifacts into the local Maven repository (~/.m2).
#
# robovmx ships a significantly newer robovm-rt that includes full Java 8 (and
# selected Java 11) APIs in the runtime static library.  This replaces the
# hand-written stub JARs and bytecode-rewrite machinery that were required with
# MobiVM's older robovm-rt:
#
#   APIs now baked into robovmx robovm-rt (no longer need stubs/StreamDesugar):
#     • java.util.stream.*           (Stream, Collectors, IntStream, …)
#     • java.util.function.*         (Predicate, Function, Consumer, …)
#     • java.time.*                  (Instant, LocalDate, Duration, …)
#     • java.nio.file.*              (Path, Paths, Files, …)
#     • java.util.Optional (+ isEmpty())
#     • java.util.Spliterators
#     • java.util.StringJoiner
#     • java.util.concurrent.CompletableFuture
#     • java.util.Collection.stream(), removeIf(), spliterator(), forEach()
#     • java.util.List.sort(), replaceAll(), of(), copyOf()
#     • java.util.Set.of()
#     • java.util.Map.getOrDefault, computeIfAbsent, merge, putIfAbsent,
#                    forEach, of(), entry(), replace()
#     • java.util.Map.Entry.comparingByValue()
#     • java.util.Comparator.comparing(), naturalOrder(), reverseOrder(),
#                            thenComparing(), reversed(), comparingLong()
#     • java.util.Objects.nonNull(), isNull(), requireNonNullElse()
#     • java.lang.Iterable.forEach(), spliterator()
#     • java.lang.String.isBlank(), repeat(), codePoints(), join()
#     • java.lang.CharSequence.codePoints()
#     • java.lang.Math.floorMod(), toIntExact()
#     • java.lang.Integer.toUnsignedString(), max(), min()
#     • java.lang.Long.compareUnsigned()
#
#   Still absent from robovmx robovm-rt (stubs still required):
#     • java.lang.Record  (needed as an app-classpath stub for record classes)
#
# USAGE
# -----
#   bash scripts/install-robovmx.sh
#
# This script is idempotent: it skips the clone if a clone directory already
# exists and skips the Maven install if the robovm-rt artifact is already
# present in ~/.m2.
#
# Run once before building forge-gui-ios with the ios-device or ios-simulator
# Maven profiles.  The test-ios-build CI step (mvn compile only) requires only
# the robovm-rt jar; a full IPA build additionally requires the native compiler
# (robovm-dist-compiler) which needs a complete robovmx build with LLVM.

set -euo pipefail

ROBOVMX_BRANCH="experiment/2-libcore-10"
ROBOVMX_REPO="https://github.com/robovmx/robovmx.git"
ROBOVMX_VERSION="10.2.2.4-SNAPSHOT"
CLONE_DIR="${ROBOVMX_CLONE_DIR:-$HOME/.robovmx-build}"

RT_M2="$HOME/.m2/repository/com/robovmx/robovm-rt/${ROBOVMX_VERSION}/robovm-rt-${ROBOVMX_VERSION}.jar"

echo "[install-robovmx] robovmx branch : $ROBOVMX_BRANCH"
echo "[install-robovmx] clone directory: $CLONE_DIR"

# ── Step 1: Clone ─────────────────────────────────────────────────────────────
if [ -d "$CLONE_DIR/.git" ]; then
    echo "[install-robovmx] Clone already present at $CLONE_DIR – skipping clone."
else
    echo "[install-robovmx] Cloning robovmx ..."
    git clone --depth 1 --branch "$ROBOVMX_BRANCH" "$ROBOVMX_REPO" "$CLONE_DIR"
    echo "[install-robovmx] Clone complete."
fi

# ── Step 2: Build and install robovm-rt ───────────────────────────────────────
if [ -f "$RT_M2" ]; then
    echo "[install-robovmx] robovm-rt already installed at $RT_M2 – skipping."
else
    echo "[install-robovmx] Building robovm-rt (pure-Java, no LLVM required) ..."
    (cd "$CLONE_DIR" && mvn -T 4 -pl compiler/rt install -DskipTests -q)
    echo "[install-robovmx] robovm-rt installed to $RT_M2"
fi

# ── Step 3: Attempt full compiler build (optional, requires LLVM) ─────────────
# Building the full robovmx compiler (robovm-dist-compiler + robovm-maven-plugin)
# requires LLVM, robovm-soot, and other native dependencies.  This step is
# optional for the test-ios-build CI gate (compile only) but required for
# producing a real IPA.
#
# Skip this step if the maven plugin is already installed OR if the build
# has already been attempted.
PLUGIN_M2="$HOME/.m2/repository/com/robovmx/robovm-maven-plugin/${ROBOVMX_VERSION}/robovm-maven-plugin-${ROBOVMX_VERSION}.jar"

if [ -f "$PLUGIN_M2" ]; then
    echo "[install-robovmx] robovm-maven-plugin already installed – skipping full build."
else
    echo "[install-robovmx] Attempting full robovmx build (compiler + maven-plugin) ..."
    echo "[install-robovmx] NOTE: This requires LLVM and native build tools."
    echo "[install-robovmx]       It will be skipped if network/tools are unavailable."
    FULL_BUILD_LOG="$(mktemp)"
    if (cd "$CLONE_DIR" && mvn -T 4 clean install -DskipTests -q 2>"$FULL_BUILD_LOG"); then
        echo "[install-robovmx] Full robovmx build complete."
    else
        echo "[install-robovmx] Full build not available (LLVM/soot dependencies missing)."
        echo "[install-robovmx] robovm-rt is installed; compile-only builds will work."
        echo "[install-robovmx] IPA production requires a full robovmx installation."
        echo "[install-robovmx] Build output:"
        cat "$FULL_BUILD_LOG" >&2
    fi
    rm -f "$FULL_BUILD_LOG"
fi

# ── Step 4: Patch robovm-dist-compiler Soot ───────────────────────────────────
# If the full compiler build succeeded, the dist-compiler is now in ~/.m2.  Apply
# the Soot patches that fix AOT compilation of Java Record classes and install the
# patched jar into forge-gui-ios/local-repo/ so the Maven plugin can find it.
DIST_COMPILER_M2="$HOME/.m2/repository/com/robovmx/robovm-dist-compiler/${ROBOVMX_VERSION}/robovm-dist-compiler-${ROBOVMX_VERSION}.jar"
if [ -f "$DIST_COMPILER_M2" ]; then
    echo "[install-robovmx] Patching robovm-dist-compiler Soot (record class fixes) ..."
    ROBOVMX_VERSION="$ROBOVMX_VERSION" bash "$(dirname "${BASH_SOURCE[0]}")/patch-robovm-soot.sh"
else
    echo "[install-robovmx] robovm-dist-compiler not found in ~/.m2; Soot patch skipped."
    echo "[install-robovmx] (Soot patch is only needed for IPA/simulator AOT builds.)"
fi

echo "[install-robovmx] Done."
