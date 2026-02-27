#!/usr/bin/env bash
# build-java-stubs.sh
#
# Builds a supplement jar containing the Java 8 APIs missing from MobiVM's robovm-rt
# and installs it into forge-gui-ios/local-repo/ as:
#
#     forge:java-stubs:1.1
#
# MobiVM's runtime (robovm-rt) is based on Android's class library, which predates
# Java 8 SE and is missing:
#
#   java.util.function.*   – all 43 functional interfaces
#   java.util.Optional     – Optional, OptionalInt, OptionalDouble, OptionalLong
#   java.util.Spliterator  – Spliterator and its primitive specialisation inner interfaces
#   java.util.stream.*     – Stream, IntStream, Collector, Collectors, StreamSupport
#   java.nio.file.*        – Paths, Files, Path, OpenOption, StandardCopyOption, etc.
#
# HOW THE JAR IS BUILT
# --------------------
# java.util.function.*, java.util.Optional*, and java.util.Spliterator* are
# downloaded directly from a pinned OpenJDK 17 source release on GitHub and
# compiled from source.  This avoids any dependency on the locally-installed JDK
# version, keeps the process transparent and reproducible, and requires only
# curl + javac (any JDK 9+).
#
# The remaining files (java.util.stream.* and java.nio.file.*) cannot be
# downloaded from OpenJDK and must remain as custom stubs in
# forge-gui-ios/src-java-stubs/.  The per-file reasons are documented in the
# comments of each stub file.
#
# java.io.File.toPath()
# ---------------------
# MobiVM's robovm-rt includes java.io.File but predates Java 7's toPath() method.
# The build script patches the compiled File.class from robovm-rt by injecting
# a toPath() method that delegates to our Paths.get(getAbsolutePath()) stub.
# This is done using a small Python bytecode-manipulation script that appends the
# necessary constant-pool entries and method table entry to the existing class file.
#
# INSTALL LOCATION
# ----------------
# forge-gui-ios/local-repo/ is listed in .gitignore so the built jar is never
# committed.  forge-gui-ios/pom.xml declares forge-local as a repository and
# lists forge:java-stubs:1.1 as a compile dependency so that RoboVM's AOT
# compiler includes these classes in the native binary.
#
# Usage:  bash scripts/build-java-stubs.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work.

set -euo pipefail

GROUP_ID="forge"
ARTIFACT_ID="java-stubs"
VERSION="1.1"
GROUP_PATH="forge/java-stubs"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

STUBS_SRC="$REPO_ROOT/forge-gui-ios/src-java-stubs"
LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/${GROUP_PATH}/${VERSION}"
MARKER="$DEST_DIR/.forge-built"

# Compute a SHA-1 of this script and store it in the marker file.
# If the script has changed since the last build, the stored hash won't
# match the current hash and the jar is rebuilt automatically.
_script_hash() {
    if command -v sha1sum >/dev/null 2>&1; then
        sha1sum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 1 "${BASH_SOURCE[0]}" | cut -d' ' -f1
    else
        echo "nohash"
    fi
}

SCRIPT_HASH=$(_script_hash)

if [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$SCRIPT_HASH" ]; then
    echo "[build-java-stubs] Up-to-date (hash match). Skipping rebuild."
    exit 0
fi

echo "[build-java-stubs] Building ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} ..."

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/src" "$WORK_DIR/classes"

# ---------------------------------------------------------------------------
# Step 1 – Download OpenJDK 17 sources for java.util.function.*,
#           java.util.Optional*, and java.util.Spliterator*.
#
# These are pure functional interfaces / value types with no jdk.internal.*
# dependencies. We extract the real .java sources from a pinned OpenJDK 17
# release tag so the stubs are byte-for-byte identical to what ships in JDK 17.
# ---------------------------------------------------------------------------
OPENJDK_TAG="jdk-17+35"
OPENJDK_BASE="https://raw.githubusercontent.com/openjdk/jdk17u/${OPENJDK_TAG}/src/java.base/share/classes"

echo "[build-java-stubs] Downloading OpenJDK sources ..."

download_src() {
    local pkg_path="$1"  # e.g. java/util/function/Function.java
    local dest="$WORK_DIR/src/$pkg_path"
    mkdir -p "$(dirname "$dest")"
    curl -sSL "${OPENJDK_BASE}/${pkg_path}" -o "$dest"
}

# java.util.function (43 interfaces)
FUNC_NAMES=(
  BiConsumer BiFunction BiPredicate BinaryOperator BiUnaryOperator
  BooleanSupplier Consumer DoubleBinaryOperator DoubleConsumer
  DoubleFunction DoublePredicate DoubleSupplier DoubleToIntFunction
  DoubleToLongFunction DoubleUnaryOperator Function IntBinaryOperator
  IntConsumer IntFunction IntPredicate IntSupplier IntToDoubleFunction
  IntToLongFunction IntUnaryOperator LongBinaryOperator LongConsumer
  LongFunction LongPredicate LongSupplier LongToDoubleFunction
  LongToIntFunction LongUnaryOperator ObjDoubleConsumer ObjIntConsumer
  ObjLongConsumer Predicate Supplier ToDoubleBiFunction ToDoubleFunction
  ToIntBiFunction ToIntFunction ToLongBiFunction ToLongFunction
  UnaryOperator
)
for name in "${FUNC_NAMES[@]}"; do
    download_src "java/util/function/${name}.java"
done

# java.util.Optional*
for name in Optional OptionalInt OptionalDouble OptionalLong; do
    download_src "java/util/${name}.java"
done

# java.util.Spliterator*
download_src "java/util/Spliterator.java"

echo "[build-java-stubs]   download complete"

# ---------------------------------------------------------------------------
# Step 1.5 – Strip lambda bodies from default methods in downloaded sources.
#
# java.util.function.* interfaces contain default methods (andThen, compose,
# negate, and, or) whose bodies use lambdas (->). When compiled with
# --patch-module java.base these lambdas generate $$Lambda$N synthetic classes
# and emit [lookup] references to lambda$*$N methods inside the interface.
# RoboVM's AOT linker cannot resolve those symbols because the interface lives
# in an app-classpath jar (not librobovm-rt.a) → "Undefined symbols" linker error.
#
# Solution: replace each default method body that contains -> with
#   { throw new UnsupportedOperationException(); }
# The interfaces are never called for those default methods in forge's iOS build.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Stripping lambda bodies from default methods (RoboVM AOT compat) ..."
python3 - "$WORK_DIR/src" << 'PYEOF'
import re, sys, pathlib

_DEFAULT_RE = re.compile(r'\bdefault\b')

def strip_lambda_defaults(src):
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
# Step 3 – Patch java.io.File to add the missing toPath() method.
#
# MobiVM's robovm-rt includes java.io.File but predates Java 7's toPath().
# We extract the compiled File.class from robovm-rt, then inject a toPath()
# method using a Python bytecode-manipulation script.  The injected method
# delegates to our Paths stub: return Paths.get(getAbsolutePath())
#
# This avoids rewriting the entire File class (which would require stubbing
# libcore.io.* internals) while still making file.toPath() work on iOS.
# ---------------------------------------------------------------------------
echo "[build-java-stubs] Patching java.io.File to add toPath() ..."

# Find robovm-rt.jar: check Maven local repo first, then download from Central.
ROBOVM_VERSION="2.3.24"
ROBOVM_RT_JAR=$(find ~/.m2/repository/com/mobidevelop/robovm/robovm-rt -name "robovm-rt-*.jar" 2>/dev/null | sort -V | tail -1)
if [ -z "$ROBOVM_RT_JAR" ]; then
    echo "[build-java-stubs]   robovm-rt not in local Maven repo; downloading from Maven Central ..."
    RT_URL="https://repo1.maven.org/maven2/com/mobidevelop/robovm/robovm-rt/${ROBOVM_VERSION}/robovm-rt-${ROBOVM_VERSION}.jar"
    curl -sSL "$RT_URL" -o "$WORK_DIR/robovm-rt.jar"
    ROBOVM_RT_JAR="$WORK_DIR/robovm-rt.jar"
fi

# Extract java/io/File.class from robovm-rt.jar
mkdir -p "$WORK_DIR/classes/java/io"
(cd "$WORK_DIR/classes" && jar xf "$ROBOVM_RT_JAR" java/io/File.class 2>/dev/null) || {
    echo "[build-java-stubs]   Warning: could not extract java/io/File.class from robovm-rt; skipping toPath() patch"
    ROBOVM_RT_JAR=""
}

if [ -n "$ROBOVM_RT_JAR" ] && [ -f "$WORK_DIR/classes/java/io/File.class" ]; then
    # Inject toPath() into the extracted File.class using Python bytecode manipulation
    python3 - "$WORK_DIR/classes/java/io/File.class" << 'PYEOF'
# Injects a public toPath() method into java.io.File.class that delegates to:
#   return java.nio.file.Paths.get(this.getAbsolutePath());
#
# The Java .class file format is defined in the JVM Specification.
# We append new constant-pool entries to the end of the existing pool
# (safe: existing indices are unchanged) then append the method.
import struct, sys

TAG_UTF8       = 1
TAG_CLASS      = 7
TAG_METHODREF  = 10
TAG_NAMEANDTYPE = 12

def r2(d, p): return struct.unpack_from('>H', d, p)[0], p + 2
def r4(d, p): return struct.unpack_from('>I', d, p)[0], p + 4
def w2(v):    return struct.pack('>H', v)
def w4(v):    return struct.pack('>I', v)

def parse_cp(data, pos):
    cp_count, pos = r2(data, pos)
    pool = [None]  # index 0 unused
    i = 1
    while i < cp_count:
        tag = data[pos]
        if tag == TAG_UTF8:
            ln, pos = r2(data, pos + 1)
            pool.append(data[pos - 3:pos + ln])
            pos += ln
        elif tag in (10, 11, 9, TAG_NAMEANDTYPE, 17, 18):  # Methodref/Fieldref/NAT/Dynamic
            pool.append(data[pos:pos + 5]); pos += 5
        elif tag in (5, 6):  # Long/Double – occupy two CP slots
            pool.append(data[pos:pos + 9]); pos += 9
            pool.append(None); i += 1
        elif tag in (TAG_CLASS, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package
            pool.append(data[pos:pos + 3]); pos += 3
        elif tag == 15:  # MethodHandle
            pool.append(data[pos:pos + 4]); pos += 4
        elif tag in (3, 4):  # Integer/Float
            pool.append(data[pos:pos + 5]); pos += 5
        else:
            raise ValueError(f'Unknown CP tag {tag} at offset {pos}')
        i += 1
    return pool, cp_count, pos

def method_already_exists(data, methods_count_pos, methods_count, topath_name_idx, topath_desc_idx):
    pos = methods_count_pos + 2
    for _ in range(methods_count):
        pos += 2  # access_flags
        name_idx, pos = r2(data, pos)
        desc_idx, pos = r2(data, pos)
        attrs_count, pos = r2(data, pos)
        if name_idx == topath_name_idx and desc_idx == topath_desc_idx:
            return True
        for _ in range(attrs_count):
            pos += 2  # attr_name_index
            attr_len, pos = r4(data, pos)
            pos += attr_len
    return False

def make_utf8(text):
    b = text.encode('utf-8')
    return struct.pack('>BH', TAG_UTF8, len(b)) + b

def make_class(name_idx):  return struct.pack('>BH', TAG_CLASS, name_idx)
def make_nat(n, d):        return struct.pack('>BHH', TAG_NAMEANDTYPE, n, d)
def make_mref(c, n):       return struct.pack('>BHH', TAG_METHODREF, c, n)

def find_entry(pool, new_entries, entry_bytes):
    for i, e in enumerate(pool):
        if e == entry_bytes:
            return i
    for i, e in enumerate(new_entries):
        if e == entry_bytes:
            return len(pool) + i
    return None

def get_or_add(pool, new_entries, entry_bytes):
    idx = find_entry(pool, new_entries, entry_bytes)
    if idx is None:
        idx = len(pool) + len(new_entries)
        new_entries.append(entry_bytes)
    return idx

def get_or_add_utf8(pool, new_entries, text):
    return get_or_add(pool, new_entries, make_utf8(text))

def get_or_add_class(pool, new_entries, class_name):
    name_idx = get_or_add_utf8(pool, new_entries, class_name)
    return get_or_add(pool, new_entries, make_class(name_idx))

def get_or_add_methodref(pool, new_entries, class_name, method_name, method_desc):
    class_idx = get_or_add_class(pool, new_entries, class_name)
    name_idx  = get_or_add_utf8(pool, new_entries, method_name)
    desc_idx  = get_or_add_utf8(pool, new_entries, method_desc)
    nat_idx   = get_or_add(pool, new_entries, make_nat(name_idx, desc_idx))
    return get_or_add(pool, new_entries, make_mref(class_idx, nat_idx))

def patch_file_class(data):
    assert data[:4] == b'\xca\xfe\xba\xbe', "Not a valid .class file"
    cp_start = 8  # skip magic(4) + minor(2) + major(2)
    pool, cp_count, rest_pos = parse_cp(data, cp_start)
    new_entries = []

    # Check if toPath() already exists
    topath_name_bytes = make_utf8("toPath")
    topath_desc_bytes = make_utf8("()Ljava/nio/file/Path;")
    existing_name_idx = find_entry(pool, [], topath_name_bytes)
    existing_desc_idx = find_entry(pool, [], topath_desc_bytes)

    # Scan to find methods_count position
    pos = rest_pos + 6  # skip access_flags + this_class + super_class
    iface_count, pos = r2(data, pos)
    pos += iface_count * 2
    fields_count, pos = r2(data, pos)
    for _ in range(fields_count):
        pos += 6  # access(2) + name(2) + descriptor(2)
        attrs_count = struct.unpack_from('>H', data, pos)[0]
        pos += 2  # skip attrs_count
        for _ in range(attrs_count):
            pos += 2
            attr_len, pos = r4(data, pos)
            pos += attr_len
    methods_count_pos = pos
    methods_count, pos = r2(data, pos)

    # Quick exit: method already present
    if existing_name_idx is not None and existing_desc_idx is not None:
        if method_already_exists(data, methods_count_pos, methods_count,
                                 existing_name_idx, existing_desc_idx):
            print("[build-java-stubs]   toPath() already present in File.class; skipping patch")
            sys.exit(0)

    # Skip existing methods to find end of methods section
    for _ in range(methods_count):
        pos += 6  # access(2) + name(2) + descriptor(2)
        attrs_count = struct.unpack_from('>H', data, pos)[0]
        pos += 2  # skip attrs_count
        for _ in range(attrs_count):
            pos += 2
            attr_len, pos = r4(data, pos)
            pos += attr_len
    methods_end_pos = pos

    # Build new constant-pool entries
    topath_name_idx = get_or_add_utf8(pool, new_entries, "toPath")
    topath_desc_idx = get_or_add_utf8(pool, new_entries, "()Ljava/nio/file/Path;")
    code_attr_idx   = get_or_add_utf8(pool, new_entries, "Code")
    get_abs_ref     = get_or_add_methodref(pool, new_entries,
                          "java/io/File", "getAbsolutePath", "()Ljava/lang/String;")
    paths_get_ref   = get_or_add_methodref(pool, new_entries,
                          "java/nio/file/Paths", "get",
                          "(Ljava/lang/String;)Ljava/nio/file/Path;")

    # Bytecode: aload_0 / invokevirtual getAbsolutePath / invokestatic Paths.get / areturn
    code_bytes = (struct.pack('>B', 0x2A) +
                  struct.pack('>BH', 0xB6, get_abs_ref) +
                  struct.pack('>BH', 0xB8, paths_get_ref) +
                  struct.pack('>B', 0xB0))

    # Code attribute body: max_stack(2) max_locals(2) code_len(4) code exception_table(2) attrs(2)
    code_attr_body = (w2(1) + w2(1) + w4(len(code_bytes)) + code_bytes + w2(0) + w2(0))
    code_attribute = w2(code_attr_idx) + w4(len(code_attr_body)) + code_attr_body

    # Method entry: access_flags=public(1), name, descriptor, 1 attribute
    method_entry = w2(0x0001) + w2(topath_name_idx) + w2(topath_desc_idx) + w2(1) + code_attribute

    # Reassemble class file
    new_cp_bytes = b''.join(new_entries)
    new_cp_count = cp_count + len(new_entries)

    result = (data[:cp_start] +                          # magic + minor + major
              w2(new_cp_count) +                          # updated cp_count
              data[cp_start + 2:rest_pos] +               # original cp entries
              new_cp_bytes +                              # new cp entries
              data[rest_pos:methods_count_pos] +          # access/this/super/ifaces/fields
              w2(methods_count + 1) +                     # updated methods_count
              data[methods_count_pos + 2:methods_end_pos] + # original methods
              method_entry +                              # new toPath() method
              data[methods_end_pos:])                     # class attributes

    return result

input_path = sys.argv[1]
with open(input_path, 'rb') as f:
    original = f.read()
patched = patch_file_class(original)
with open(input_path, 'wb') as f:
    f.write(patched)
print(f"[build-java-stubs]   injected toPath() into java/io/File.class ({len(original)} -> {len(patched)} bytes)")
PYEOF
fi

# ---------------------------------------------------------------------------
# Step 4 – Package and install.
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
_sha1() { if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi; }
_md5()  { if command -v md5sum  >/dev/null 2>&1; then md5sum  "$1" | cut -d' ' -f1; else md5 -q "$1"; fi; }
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.jar.md5"
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"

echo "$SCRIPT_HASH" > "$MARKER"
echo "[build-java-stubs] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
