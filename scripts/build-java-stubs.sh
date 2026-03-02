#!/usr/bin/env bash
# build-java-stubs.sh
#
# Builds a supplement jar containing the Java 8 APIs missing from MobiVM's robovm-rt
# and installs it into forge-gui-ios/local-repo/ as:
#
#     forge:java-stubs:1.7
#
# MobiVM's runtime (robovm-rt) is based on Android's class library, which predates
# Java 8 SE and is missing or incomplete for:
#
#   java.util.function.*   – all 43 functional interfaces
#   java.util.Comparator   – naturalOrder/reverseOrder/comparing/comparingInt (Java 8 statics)
#                            and reversed/thenComparing (Java 8 defaults) absent from robovm-rt
#   java.util.Objects      – isNull/nonNull (Java 8) and requireNonNullElse/checkIndex
#                            (Java 9) absent from robovm-rt's Android 4.4-era Objects
#   java.util.Optional     – Optional, OptionalInt, OptionalDouble, OptionalLong
#   java.util.Spliterator  – Spliterator and its primitive specialisation inner interfaces
#   java.util.stream.*     – Stream, IntStream, Collector, Collectors, StreamSupport
#   java.nio.file.*        – Paths, Files, Path, OpenOption
#   java.time.*            – Instant, Duration, LocalDate, LocalDateTime, LocalTime,
#                            ZonedDateTime, OffsetDateTime, ZoneId, DateTimeFormatter, …
#
# HOW THE JAR IS BUILT
# --------------------
# java.util.function.*, java.util.Optional*, and java.util.Spliterator* are
# downloaded directly from a pinned OpenJDK 17 source release on GitHub and
# compiled from source.  This avoids any dependency on the locally-installed JDK
# version, keeps the process transparent and reproducible, and requires only
# curl + javac (any JDK 9+).
#
# java.time.* is provided by downloading ThreeTen-Backport (a Java 6/7-compatible
# backport of the java.time API, https://www.threeten.org/threetenbp/) and
# renaming its package from org.threeten.bp to java.time.  ThreeTen-Backport is
# published under a BSD 3-clause licence that is compatible with forge's licence.
# We rename the package rather than using it as-is because third-party libraries
# (e.g. commons-lang3's StopWatch) reference java.time.* directly and cannot be
# changed without forking.
#
# The remaining 13 files (java.util.Comparator, java.util.Objects, java.util.stream.*,
# and java.nio.file.*) cannot be downloaded from OpenJDK and must remain as custom stubs
# in forge-gui-ios/src-java-stubs/.  The per-file reasons are:
#
# java.util
#   Comparator.java – The real JDK 17 Comparator.java uses lambda bodies in its default
#                     and static methods.  Lambda bodies in interface default/static methods
#                     in an app-classpath jar produce $$Lambda$N synthetic classes with
#                     unresolvable [lookup] symbols → RoboVM linker error (same issue as
#                     Stream.java).  Our stub re-implements all public methods using
#                     anonymous inner classes instead of lambdas and adds all Java 8
#                     static (naturalOrder, reverseOrder, comparing, comparingInt, …) and
#                     default (reversed, thenComparing, …) methods absent from robovm-rt.
#   Objects.java    – The real JDK 17 Objects.java imports jdk.internal.util.Preconditions
#                     and jdk.internal.vm.annotation.ForceInline — both absent from the
#                     compilation classpath.  Our stub re-implements all public methods
#                     directly and adds the Java 8 (isNull/nonNull) and Java 9
#                     (requireNonNullElse, checkIndex, …) additions that are absent from
#                     robovm-rt's Android 4.4-era partial implementation.
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
# lists forge:java-stubs:1.7 as a compile dependency so that RoboVM's AOT
# compiler includes these classes in the native binary.
#
# Usage:  bash scripts/build-java-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work.

set -euo pipefail

GROUP_ID="forge"
ARTIFACT_ID="java-stubs"
VERSION="1.8"
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
# Step 1b – ThreeTen-Backport as java.time.* sources.
#
# ThreeTen-Backport (https://www.threeten.org/threetenbp/) provides a Java 6/7-
# compatible implementation of the java.time API (JSR-310) under its original
# org.threeten.bp package.  MobiVM's robovm-rt (Android 7-era) lacks java.time.*
# entirely, so we download ThreeTen-Backport's sources and rename the package
# from org.threeten.bp to java.time before compiling.  This lets both forge's
# own code and third-party libraries (e.g. commons-lang3's StopWatch) use
# java.time.* without NoClassDefFoundError at runtime on iOS.
#
# The TZDB.dat timezone database and ChronologyText.properties resource bundle
# are taken from the ThreeTen-Backport binary JAR and placed at their renamed
# paths (java/time/TZDB.dat, java/time/format/ChronologyText.properties) in
# the compiled classes directory so that ZoneRulesProvider and
# DateTimeFormatterBuilder can locate them at runtime.
#
# DateTimeUtils.java is excluded because it references java.sql.* which is
# not part of the java.base module; forge does not use its sql-conversion
# methods and they are not needed on iOS.
#
# Pinned: threetenbp 1.7.0 (latest stable, BSD 3-clause licence)
# ---------------------------------------------------------------------------
THREETEN_VERSION="1.7.0"
THREETEN_BASE="https://repo1.maven.org/maven2/org/threeten/threetenbp/${THREETEN_VERSION}"

echo "[build-java-stubs] Downloading ThreeTen-Backport ${THREETEN_VERSION} (sources + binary for resources) ..."
curl -fsSL "${THREETEN_BASE}/threetenbp-${THREETEN_VERSION}-sources.jar" \
    -o "$WORK_DIR/threetenbp-sources.jar"
curl -fsSL "${THREETEN_BASE}/threetenbp-${THREETEN_VERSION}.jar" \
    -o "$WORK_DIR/threetenbp-binary.jar"

# Extract sources into a staging directory, then rename package tree.
mkdir -p "$WORK_DIR/threeten_stage"
(cd "$WORK_DIR/threeten_stage" && jar xf "$WORK_DIR/threetenbp-sources.jar")

# Move org/threeten/bp/ tree to java/time/ under the shared src directory.
mkdir -p "$WORK_DIR/src/java/time"
if [ -d "$WORK_DIR/threeten_stage/org/threeten/bp" ]; then
    cp -a "$WORK_DIR/threeten_stage/org/threeten/bp/." "$WORK_DIR/src/java/time/"
fi
rm -rf "$WORK_DIR/threeten_stage" "$WORK_DIR/threetenbp-sources.jar"

# Rename package declarations, imports, and any hardcoded resource paths.
#   org.threeten.bp  → java.time  (package / import / string literal references)
#   org/threeten/bp  → java/time  (classpath resource path strings, e.g. TZDB.dat)
# We use two separate patterns because dots and slashes appear in different contexts.
#
# BSD sed (macOS) requires an explicit empty backup suffix for in-place editing:
#   sed -i '' ...
# GNU sed (Linux) accepts both "sed -i ''" and bare "sed -i", but some versions
# treat the empty string as a filename argument if it appears as a separate word,
# so we detect the variant once and use the correct form throughout.
if sed --version 2>/dev/null | grep -q GNU; then
    _sed_inplace() { sed -i "$@"; }
else
    _sed_inplace() { sed -i '' "$@"; }
fi

find "$WORK_DIR/src/java/time" -name "*.java" | while IFS= read -r f; do
    _sed_inplace \
        -e 's|org\.threeten\.bp|java.time|g' \
        -e 's|org/threeten/bp|java/time|g' \
        "$f"
done

# Remove DateTimeUtils.java: it references java.sql.* which is in a separate
# module (java.sql) not readable by java.base under --patch-module.
# forge does not call any of its sql-conversion methods on iOS.
rm -f "$WORK_DIR/src/java/time/DateTimeUtils.java"

# Extract resources from the binary JAR and copy them to the renamed paths
# inside the classes output directory (resources are not produced by javac).
mkdir -p "$WORK_DIR/threeten_res"
(cd "$WORK_DIR/threeten_res" && jar xf "$WORK_DIR/threetenbp-binary.jar")
mkdir -p "$WORK_DIR/classes/java/time/format"
[ -f "$WORK_DIR/threeten_res/org/threeten/bp/TZDB.dat" ] && \
    cp "$WORK_DIR/threeten_res/org/threeten/bp/TZDB.dat" \
       "$WORK_DIR/classes/java/time/TZDB.dat"
[ -f "$WORK_DIR/threeten_res/org/threeten/bp/format/ChronologyText.properties" ] && \
    cp "$WORK_DIR/threeten_res/org/threeten/bp/format/ChronologyText.properties" \
       "$WORK_DIR/classes/java/time/format/ChronologyText.properties"
# ServiceLoader configuration for ZoneRulesProvider (loaded by ZoneRulesInitializer).
mkdir -p "$WORK_DIR/classes/META-INF/services"
echo "java.time.zone.TzdbZoneRulesProvider" \
    > "$WORK_DIR/classes/META-INF/services/java.time.zone.ZoneRulesProvider"
rm -rf "$WORK_DIR/threeten_res" "$WORK_DIR/threetenbp-binary.jar"
echo "[build-java-stubs]   ThreeTen-Backport sources prepared under java/time/"

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
# Step 2 – Compile all sources together: downloaded OpenJDK sources, the
#          renamed ThreeTen-Backport (java.time.*), and the custom stubs for
#          java.util.stream.* and java.nio.file.* from forge-gui-ios/src-java-stubs/.
#
# java.util.stream.* and java.nio.file.* cannot be taken from the JVM because
# their implementations reference jdk.internal.* absent from robovm-rt, so we
# compile minimal working implementations from forge-gui-ios/src-java-stubs/.
#
# --patch-module java.base=<src1>:<src2> is required for javac to accept
# source files in java.* packages without "package exists in another module".
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Compiling all sources (downloaded + ThreeTen-Backport + stream/nio stubs) ..."
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
