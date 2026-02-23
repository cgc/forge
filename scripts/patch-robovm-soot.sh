#!/usr/bin/env bash
# patch-robovm-soot.sh
#
# Patches the soot bytecode analyser bundled inside robovm-dist-compiler, fixing
# four bugs that crash AOT compilation of Java Record classes, then installs the
# patched jar into forge-gui-ios/local-repo/ so Maven can find it via the
# file:// repository declared in forge-gui-ios/pom.xml.
#
# Background: robovm-maven-plugin 2.3.23 depends on robovm-dist-compiler-2.3.23,
# a shaded fat-jar that embeds soot classes directly.  The pom for
# robovm-dist-compiler declares no Maven dependencies (everything is shaded in),
# so patching the standalone robovm-soot artifact has no effect.  We must patch
# robovm-dist-compiler itself.
#
# The local-repo directory is gitignored so the binary jar is never committed.
# Run this script once after cloning (or from a CI workflow step) to populate it.
#
# Usage: bash scripts/patch-robovm-soot.sh

set -euo pipefail

DIST_GROUP_ID="com.mobidevelop.robovm"
DIST_ARTIFACT_ID="robovm-dist-compiler"
DIST_VERSION="2.3.23"
DIST_GROUP_PATH="com/mobidevelop/robovm"

SOOT_ARTIFACT_ID="robovm-soot"
SOOT_VERSION="2.5.0-9"

MAVEN_CENTRAL="https://repo1.maven.org/maven2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PATCHES_DIR="$REPO_ROOT/patches/robovm-soot"

# The local file-system Maven repository inside the iOS module.
LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
MARKER="$LOCAL_REPO/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION}/.forge-patched"

if [ -f "$MARKER" ]; then
    echo "[patch-robovm-soot] Already patched (marker found). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

DIST_JAR_URL="${MAVEN_CENTRAL}/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION}/${DIST_ARTIFACT_ID}-${DIST_VERSION}.jar"
SOOT_SOURCES_URL="${MAVEN_CENTRAL}/${DIST_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/${SOOT_ARTIFACT_ID}-${SOOT_VERSION}-sources.jar"

echo "[patch-robovm-soot] Downloading ${DIST_ARTIFACT_ID}-${DIST_VERSION} (shaded fat-jar) from Maven Central..."
curl --fail --silent --show-error --location "$DIST_JAR_URL" -o "$WORK_DIR/robovm-dist-compiler.jar"

echo "[patch-robovm-soot] Downloading ${SOOT_ARTIFACT_ID}-${SOOT_VERSION} sources from Maven Central..."
curl --fail --silent --show-error --location "$SOOT_SOURCES_URL" -o "$WORK_DIR/robovm-soot-sources.jar"

echo "[patch-robovm-soot] Extracting..."
mkdir -p "$WORK_DIR/classes" "$WORK_DIR/src"
(cd "$WORK_DIR/classes" && jar xf "../robovm-dist-compiler.jar")
(cd "$WORK_DIR/src"     && jar xf "../robovm-soot-sources.jar")

echo "[patch-robovm-soot] Normalizing line endings in extracted sources..."
# Use portable sed: GNU sed uses -i, BSD/macOS sed requires -i ''.
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
# The fat-jar contains all transitive soot dependencies inline — use it as the classpath.
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

# Install into the project-local Maven repository so no global cache mutation occurs.
DEST_DIR="$LOCAL_REPO/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION}"
mkdir -p "$DEST_DIR"

echo "[patch-robovm-soot] Installing patched jar into $DEST_DIR ..."
cp "$WORK_DIR/robovm-dist-compiler-patched.jar" "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION}.jar"

# Write a minimal POM so Maven treats this as a valid repository entry.
cat > "$DEST_DIR/${DIST_ARTIFACT_ID}-${DIST_VERSION}.pom" << POM_EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>${DIST_GROUP_ID}</groupId>
  <artifactId>${DIST_ARTIFACT_ID}</artifactId>
  <version>${DIST_VERSION}</version>
</project>
POM_EOF

touch "$MARKER"

echo "[patch-robovm-soot] Done. Patched ${DIST_ARTIFACT_ID}-${DIST_VERSION} installed to local-repo."
