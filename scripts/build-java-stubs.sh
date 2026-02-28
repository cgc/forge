#!/usr/bin/env bash
# build-java-stubs.sh
#
# Builds a supplement jar containing the Java 8 APIs missing from MobiVM's robovm-rt
# and installs it into forge-gui-ios/local-repo/ as:
#
#     forge:java-stubs:1.3
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
# java.util.function.*, java.util.Optional*, and java.util.Spliterator* are
# downloaded directly from a pinned OpenJDK 17 source release on GitHub and
# compiled from source.  This avoids any dependency on the locally-installed JDK
# version, keeps the process transparent and reproducible, and requires only
# curl + javac (any JDK 9+).
#
# The remaining 11 files (java.util.stream.* and java.nio.file.*) cannot be
# downloaded from OpenJDK and must remain as custom stubs in
# forge-gui-ios/src-java-stubs/.  The per-file reasons are:
#
# java.nio.file
#   Path.java       – The real Path is an interface importing
#                     java.nio.file.spi.FileSystemProvider, java.net.URI,
#                     WatchService, WatchKey, WatchEvent — all absent from
#                     robovm-rt.  Our stub is a concrete class wrapping
#                     java.io.File instead.
#   Paths.java      – The real Paths.get() delegates to
#                     FileSystems.getDefault().getPath(), which requires
#                     FileSystemProvider infrastructure absent on iOS.
#                     Our stub constructs the custom Path class directly.
#   Files.java      – The real Files.java is ~3 000 lines routed through
#                     FileSystemProvider.  Our stub exposes only the three
#                     methods forge uses (exists/newInputStream/newOutputStream)
#                     backed by java.io.File/FileInputStream/FileOutputStream.
#   OpenOption.java – The real version is also an empty marker interface and
#                     could in principle be downloaded, but it references
#                     StandardOpenOption in its Javadoc which would pull in
#                     more dependencies.  As a 4-line file the maintenance
#                     burden of keeping it as a stub is negligible.
#
# java.util.stream
#   Stream.java     – The real Stream<T> extends BaseStream<T,Stream<T>> and
#                     has many default methods with lambda bodies.  Those lambda
#                     bodies would produce $$Lambda$N synthetic classes with
#                     [lookup] symbols that are undefined when the interface
#                     lives in an app-classpath jar (not robovm-rt) — exactly
#                     the linker error fixed in Step 1.5.  Our stub deliberately
#                     does NOT extend BaseStream and omits lambda default methods.
#   IntStream.java  – Same issue: extends BaseStream<Integer,IntStream>.
#   Collector.java  – The real static Collector.of() methods reference
#                     package-private CollectorImpl, which is not in scope when
#                     only Collector.java is downloaded.  Our stub uses an
#                     anonymous class for the static factory instead.
#   Collectors.java – The real Collectors references CollectorImpl,
#                     ReferencePipeline, StreamShape, AbstractTask — all
#                     internal jdk classes absent from robovm-rt.
#   StreamSupport.java – The real StreamSupport wraps ReferencePipeline,
#                     LongPipeline, DoublePipeline, IntPipeline — all absent
#                     from robovm-rt.
#   ListStream.java    – Concrete Stream implementation backed by ArrayList.
#                     No OpenJDK equivalent (robovm-rt's ReferencePipeline is
#                     the closest, but it depends on jdk.internal.*).
#   ArrayIntStream.java – Concrete IntStream implementation backed by int[].
#                     Same reasoning as ListStream.
#
# INSTALL LOCATION
# ----------------
# forge-gui-ios/local-repo/ is listed in .gitignore so the built jar is never
# committed.  forge-gui-ios/pom.xml declares forge-local as a repository and
# lists forge:java-stubs:1.3 as a compile dependency so that RoboVM's AOT
# compiler includes these classes in the native binary.
#
# Usage:  bash scripts/build-java-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work.

set -euo pipefail

GROUP_ID="forge"
ARTIFACT_ID="java-stubs"
VERSION="1.3"
GROUP_PATH="forge/java-stubs"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

STUBS_SRC="$REPO_ROOT/forge-gui-ios/src-java-stubs"
LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/${GROUP_PATH}/${VERSION}"
MARKER="$DEST_DIR/.forge-built"

# Compute a SHA-1 of this script and store it in the marker file.
# If the script has changed since the last build, the stored hash won't
# match the current hash and the jar is rebuilt automatically.  This
# ensures that changes to this script (e.g. adding the lambda-stripping
# step) are always picked up without requiring users to manually delete
# local-repo/.
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
mkdir -p "$WORK_DIR/src/java/util/function" \
         "$WORK_DIR/src/java/util" \
         "$WORK_DIR/classes"

# ---------------------------------------------------------------------------
# Step 1 – Download java.util.function.*, java.util.Optional*, and
#          java.util.Spliterator* directly from a pinned OpenJDK 17 source
#          release on GitHub.
#
# These 48 source files (43 java.util.function interfaces + Optional/OptionalDouble/
# OptionalInt/OptionalLong/Spliterator) are pure interfaces / value types with no
# jdk.internal.* dependencies.  Downloading from source avoids any dependency
# on the locally-installed JDK version and keeps the build transparent and
# reproducible from any machine that has curl + JDK 9+.
#
# Pinned tag: jdk-17+35  (JDK 17 GA, https://github.com/openjdk/jdk/releases/tag/jdk-17%2B35)
# ---------------------------------------------------------------------------
OPENJDK_TAG="jdk-17+35"
OPENJDK_TAG_URL="jdk-17%2B35"   # URL-encoded form of OPENJDK_TAG for use in curl
OPENJDK_BASE="https://raw.githubusercontent.com/openjdk/jdk/${OPENJDK_TAG_URL}/src/java.base/share/classes"

echo "[build-java-stubs] Downloading OpenJDK ${OPENJDK_TAG} source (function/Optional/Spliterator) ..."

FUNCTION_CLASSES=(
  BiConsumer BiFunction BiPredicate BinaryOperator BooleanSupplier Consumer
  DoubleBinaryOperator DoubleConsumer DoubleFunction DoublePredicate DoubleSupplier
  DoubleToIntFunction DoubleToLongFunction DoubleUnaryOperator Function
  IntBinaryOperator IntConsumer IntFunction IntPredicate IntSupplier
  IntToDoubleFunction IntToLongFunction IntUnaryOperator
  LongBinaryOperator LongConsumer LongFunction LongPredicate LongSupplier
  LongToDoubleFunction LongToIntFunction LongUnaryOperator
  ObjDoubleConsumer ObjIntConsumer ObjLongConsumer
  Predicate Supplier
  ToDoubleBiFunction ToDoubleFunction ToIntBiFunction ToIntFunction
  ToLongBiFunction ToLongFunction UnaryOperator
)

for cls in "${FUNCTION_CLASSES[@]}"; do
    curl -fsSL "$OPENJDK_BASE/java/util/function/${cls}.java" \
        -o "$WORK_DIR/src/java/util/function/${cls}.java"
done
echo "[build-java-stubs]   downloaded ${#FUNCTION_CLASSES[@]} function interfaces"

for cls in Optional OptionalDouble OptionalInt OptionalLong Spliterator; do
    curl -fsSL "$OPENJDK_BASE/java/util/${cls}.java" \
        -o "$WORK_DIR/src/java/util/${cls}.java"
done
echo "[build-java-stubs]   downloaded Optional/Spliterator classes"

# ---------------------------------------------------------------------------
# Step 1.5 – Strip lambda bodies from downloaded function interface sources.
#
# OpenJDK's java.util.function interfaces have default methods (andThen,
# compose, negate, and, or) whose bodies use lambda expressions.  RoboVM's
# AOT linker generates $$Lambda$N synthetic classes for each lambda and emits
# references to [lookup] symbols from the enclosing interface.  When the
# interface is in an app-classpath jar (not in the native robovm-rt), those
# [lookup] symbols are never exported → "Undefined symbols" linker errors.
#
# We replace every default-method body that contains a lambda (->) with a
# stub that throws UnsupportedOperationException.  The abstract method (the
# actual functional contract) is left untouched.  Forge's iOS code path only
# calls the abstract methods; the default combinators are never invoked.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Stripping lambda bodies from default methods (RoboVM AOT compat) ..."
python3 - "$WORK_DIR/src" << 'PYEOF'
import re, sys, pathlib

# Compile once; reused for every file.
_DEFAULT_RE = re.compile(r'\bdefault\b')

def strip_lambda_defaults(src):
    """Replace bodies of default methods containing -> with UnsupportedOperationException.

    Scope: only the downloaded OpenJDK java.util.function.* interface sources.
    Those files contain no string literals or block comments with braces or ->,
    so simple brace-counting and substring search are sufficient and safe here.
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        m = _DEFAULT_RE.search(src, i)
        if m is None:
            out.append(src[i:])
            break
        start = m.start()
        out.append(src[i:start + len('default')])
        i = start + len('default')
        brace = src.find('{', i)
        if brace == -1:
            out.append(src[i:])
            break
        out.append(src[i:brace])
        i = brace
        depth, j = 0, i
        while j < n:
            c = src[j]
            if c == '{': depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0: break
            j += 1
        body = src[i:j + 1]
        if '->' in body:
            out.append(' { throw new UnsupportedOperationException(); }')
        else:
            out.append(body)
        i = j + 1
    return ''.join(out)

src_dir = pathlib.Path(sys.argv[1])
for java_file in sorted(src_dir.rglob('*.java')):
    text = java_file.read_text(encoding='utf-8')
    new_text = strip_lambda_defaults(text)
    if new_text != text:
        java_file.write_text(new_text, encoding='utf-8')
        print(f'  patched: {java_file.relative_to(src_dir)}')
PYEOF
echo "[build-java-stubs]   default-method lambda stripping complete"

# ---------------------------------------------------------------------------
# Step 2 – Compile all sources together: downloaded OpenJDK sources and the
#          custom stubs for java.util.stream.* and java.nio.file.* from
#          forge-gui-ios/src-java-stubs/.
#
# java.util.stream.* and java.nio.file.* cannot be taken from the JVM because
# their implementations reference jdk.internal.* absent from robovm-rt, so we
# compile minimal working implementations from forge-gui-ios/src-java-stubs/.
#
# --patch-module java.base=<src1>:<src2> is required for javac to accept
# source files in java.* packages without "package exists in another module".
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Compiling all sources (downloaded + stream/nio stubs) ..."
# shellcheck disable=SC2046
javac \
    --patch-module java.base="$WORK_DIR/src:$STUBS_SRC" \
    -source 17 -target 17 \
    -d "$WORK_DIR/classes" \
    $(find "$WORK_DIR/src" "$STUBS_SRC" -name "*.java")
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

echo "$SCRIPT_HASH" > "$MARKER"
echo "[build-java-stubs] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
