#!/usr/bin/env bash
# patch-robovm-soot.sh
#
# Patches the Soot bytecode analyser bundled inside com.robovmx:robovm-dist-compiler,
# fixing four bugs that crash AOT compilation of Java Record classes, then installs
# the patched jar into forge-gui-ios/local-repo/ as version 10.2.2.4-patched.
#
# Prerequisites:
#   bash scripts/install-robovmx.sh   (must complete its full compiler build first,
#                                       producing robovm-dist-compiler in ~/.m2)
#
# Using a distinct version number (10.2.2.4-patched instead of 10.2.2.4-SNAPSHOT)
# is essential: Maven caches release artifacts permanently in ~/.m2 and will use a
# previously resolved jar rather than re-checking any file:// repo.  The -patched
# version is unknown to Maven Central / Sonatype so Maven always resolves it from
# forge-local, regardless of what is already in ~/.m2.
#
# forge-gui-ios/pom.xml instructs the robovm-maven-plugin to load
# robovm-dist-compiler:10.2.2.4-patched via <plugin><dependencies>, which places it
# first on the plugin classpath so its Soot classes shadow the originals.
#
# Soot sources: robovmx uses com.robovmx:robovm-soot:2.5.0.8-SNAPSHOT, which shares
# the same code base as com.mobidevelop.robovm:robovm-soot:2.5.0-9 available on
# Maven Central.  The 2.5.0-9 sources are used here; the four patched files are
# identical in both versions.
#
# Usage:  bash scripts/patch-robovm-soot.sh
# The local-repo/ directory is gitignored; run this once after install-robovmx.sh.

set -euo pipefail

DIST_GROUP_ID="com.robovmx"
DIST_ARTIFACT_ID="robovm-dist-compiler"
# Allow the caller (install-robovmx.sh) to pass the exact version via ROBOVMX_VERSION.
DIST_VERSION_ORIG="${ROBOVMX_VERSION:-10.2.2.4-SNAPSHOT}"
DIST_VERSION_PATCHED="${DIST_VERSION_ORIG%-SNAPSHOT}-patched"   # e.g. 10.2.2.4-patched
DIST_GROUP_PATH="com/robovmx"

# Soot sources: use the mobidevelop 2.5.0-9 release from Maven Central
# (same code base as robovmx's com.robovmx:robovm-soot:2.5.0.8-SNAPSHOT)
SOOT_SOURCES_GROUP_PATH="com/mobidevelop/robovm"
SOOT_ARTIFACT_ID="robovm-soot"
SOOT_VERSION="2.5.0-9"

MAVEN_CENTRAL="https://repo1.maven.org/maven2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PATCHES_DIR="$REPO_ROOT/patches/robovm-soot"

LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
MARKER="$LOCAL_REPO/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION_PATCHED}/.forge-patched"

if [ -f "$MARKER" ]; then
    echo "[patch-robovm-soot] Already patched (marker found). Skipping."
    exit 0
fi

# The dist-compiler is installed by install-robovmx.sh into ~/.m2 when the full
# robovmx compiler build succeeds (requires LLVM + robovm-soot on the build machine).
DIST_JAR="$HOME/.m2/repository/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION_ORIG}/${DIST_ARTIFACT_ID}-${DIST_VERSION_ORIG}.jar"
if [ ! -f "$DIST_JAR" ]; then
    echo "[patch-robovm-soot] ERROR: dist-compiler not found at:"
    echo "  $DIST_JAR"
    echo "[patch-robovm-soot] Run 'bash scripts/install-robovmx.sh' first to build the"
    echo "[patch-robovm-soot] full robovmx compiler (requires LLVM)."
    exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

SOOT_SOURCES_URL="${MAVEN_CENTRAL}/${SOOT_SOURCES_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/${SOOT_ARTIFACT_ID}-${SOOT_VERSION}-sources.jar"

echo "[patch-robovm-soot] Using ${DIST_ARTIFACT_ID}-${DIST_VERSION_ORIG} from ~/.m2 ..."
cp "$DIST_JAR" "$WORK_DIR/robovm-dist-compiler.jar"

echo "[patch-robovm-soot] Downloading ${SOOT_ARTIFACT_ID}-${SOOT_VERSION} sources from Maven Central..."
curl --fail --silent --show-error --location "$SOOT_SOURCES_URL" -o "$WORK_DIR/robovm-soot-sources.jar"

echo "[patch-robovm-soot] Extracting..."
mkdir -p "$WORK_DIR/classes" "$WORK_DIR/src"
(cd "$WORK_DIR/classes" && jar xf "../robovm-dist-compiler.jar")
(cd "$WORK_DIR/src"     && jar xf "../robovm-soot-sources.jar")

echo "[patch-robovm-soot] Normalizing line endings in extracted sources..."
if sed --version >/dev/null 2>&1; then
    find "$WORK_DIR/src" -name "*.java" -exec sed -i 's/\r$//' {} +
else
    find "$WORK_DIR/src" -name "*.java" -exec sed -i '' 's/\r$//' {} +
fi

echo "[patch-robovm-soot] Applying patches..."
for patch_file in "$PATCHES_DIR"/0*.patch; do
    echo "  $(basename "$patch_file")"
    (cd "$WORK_DIR/src" && patch --no-backup-if-mismatch -p1 < "$patch_file")
done

echo "[patch-robovm-soot] Compiling patched sources against ${DIST_ARTIFACT_ID}..."
mkdir -p "$WORK_DIR/classes-patched"
javac \
    -source 8 -target 8 \
    -cp "$WORK_DIR/robovm-dist-compiler.jar" \
    -d  "$WORK_DIR/classes-patched" \
    "$WORK_DIR/src/soot/coffi/CONSTANT_Fieldref_info.java" \
    "$WORK_DIR/src/soot/coffi/CONSTANT_MethodHandle_info.java" \
    "$WORK_DIR/src/soot/jimple/internal/JDynamicInvokeExpr.java" \
    "$WORK_DIR/src/soot/jimple/toolkits/typing/fast/AugEvalFunction.java"

echo "[patch-robovm-soot] Merging patched classes into jar..."
cp -r "$WORK_DIR/classes-patched/." "$WORK_DIR/classes/"

echo "[patch-robovm-soot] Repacking jar..."
(cd "$WORK_DIR/classes" && jar cf "../robovm-dist-compiler-patched.jar" .)

DEST_DIR="$LOCAL_REPO/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION_PATCHED}"
mkdir -p "$DEST_DIR"

echo "[patch-robovm-soot] Installing as ${DIST_ARTIFACT_ID}:${DIST_VERSION_PATCHED} into $DEST_DIR ..."
cp "$WORK_DIR/robovm-dist-compiler-patched.jar" "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.jar"

cat > "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.pom" << POM_EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>${DIST_GROUP_ID}</groupId>
  <artifactId>${DIST_ARTIFACT_ID}</artifactId>
  <version>${DIST_VERSION_PATCHED}</version>
</project>
POM_EOF

# Write SHA-1 and MD5 checksums so Maven doesn't warn about missing integrity files.
_sha1() { sha1sum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" | cut -d' ' -f1; }
_sha1 "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.jar" > "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.jar.sha1"
_md5  "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.jar" > "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.jar.md5"
_sha1 "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.pom" > "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.pom.sha1"
_md5  "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.pom" > "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION_PATCHED}.pom.md5"

touch "$MARKER"

echo "[patch-robovm-soot] Done. Patched ${DIST_ARTIFACT_ID}:${DIST_VERSION_PATCHED} installed to local-repo."
