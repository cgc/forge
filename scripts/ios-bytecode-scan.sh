#!/usr/bin/env bash
# ios-bytecode-scan.sh
#
# Bytecode-level iOS API compatibility scanner.
#
# Compiles scripts/ApiScan.java (using the same cached ASM jar as desugar-streams.sh)
# then runs it against all compiled class directories for the Forge modules that are
# bundled into the iOS IPA.  Unlike the source-level grep in ios-compat-scan.sh, this
# scanner finds Java 9-11 API calls hidden inside:
#
#   • compiler-generated synthetic methods (lambda helpers, bridge methods)
#   • enum static initializers
#   • auto-generated inner classes (anonymous Runnable/Callable, etc.)
#   • any call site that does not appear verbatim in .java source
#
# It also detects serializable-lambda INVOKEDYNAMIC patterns (Pattern 65 target):
# LambdaMetafactory.altMetafactory with FLAG_SERIALIZABLE set.  These cause
# ClassCastException on iOS (RoboVM does not generate Serializable lambda proxies).
# Fixed by StreamDesugar Pattern 65.
#
# NATIVE METHOD DECLARATION SCAN (RISK:NATIVE)
# --------------------------------------------
# In addition to call-site scanning, ApiScan also detects `native` method
# declarations in scanned classes.  On iOS with RoboVM's AOT compiler, each
# `native` method generates a JNI trampoline.  If the corresponding JNI library
# (xcframework) is not linked, the trampoline's function pointer is null → calling
# the method at runtime causes EXC_BAD_ACCESS (KERN_INVALID_ADDRESS at 0x0).
#
# This scan includes the libgdx JARs (gdx-backend-robovm, gdx-freetype, gdx-box2d,
# gdx-controllers-ios) when they are available in the Maven local repository, so that
# all native method declarations in those JARs are reported.  Verify that each class
# reported has a corresponding *-platform:natives-ios xcframework in
# forge-gui-ios/pom.xml.
#
# Previously fixed EXC_BAD_ACCESS at 0x0 examples from this scan:
#   com.badlogic.gdx.graphics.glutils.IOSGLES20.glTexImage2DJNI → gdx-platform:natives-ios
#   com.badlogic.gdx.physics.box2d.World.newWorld              → gdx-box2d-platform:natives-ios
#
# In addition to the Forge module classes, the scanner also scans the JGraphT JAR
# (jgrapht-core-1.5.2) when it is present in the Maven local repository, since
# JGraphT's SupplierUtil uses serializable lambdas.
#
# OUTPUT
# ------
# Writes to stdout; redirect to scripts/ios-bytecode-scan.txt to persist:
#   bash scripts/ios-bytecode-scan.sh > scripts/ios-bytecode-scan.txt
#
# CATEGORIES
# ----------
#   RISK:HIGH           — NOT patched by StreamDesugar, NOT claimed by robovmx robovm-rt.
#                         Will throw NoSuchMethodError on iOS if reachable.
#   CLAIMED             — Listed in build-java-stubs.sh as natively provided by robovmx.
#                         Should be safe; listed for awareness.
#   PATCHED             — Rewritten at build time by StreamDesugar.java (patterns 49-65).
#   RISK:NATIVE         — native method declaration; crashes at 0x0 if JNI library not linked.
#   SERIALIZABLE_LAMBDA — INVOKEDYNAMIC with altMetafactory+FLAG_SERIALIZABLE.
#                         Fixed by Pattern 65 (CHECKCAST java/io/Serializable stripped).
#
# PREREQUISITES
# -------------
#   mvn compile -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-mobile,forge-gui-ios -am
#
# DEPENDENCIES
# ------------
# Requires: java 8+ (javac + java), unzip, zip, curl (to download ASM on first run).
# ApiScan.java itself is written to compile and run on Java 8+ (no Java 9+ APIs used
# in the scanner's own code), so the scanner can be run from any modern JDK.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# ── ASM dependency (shared cache with desugar-streams.sh) ────────────────────
ASM_VERSION="9.7"
ASM_CACHE_DIR="$REPO_ROOT/forge-gui-ios/local-repo/org/ow2/asm/asm/$ASM_VERSION"
ASM_JAR="$ASM_CACHE_DIR/asm-${ASM_VERSION}.jar"

if [ ! -f "$ASM_JAR" ]; then
    echo "[ios-bytecode-scan] Downloading ASM ${ASM_VERSION} from Maven Central ..." >&2
    mkdir -p "$ASM_CACHE_DIR"
    curl -fsSL \
        "https://repo1.maven.org/maven2/org/ow2/asm/asm/${ASM_VERSION}/asm-${ASM_VERSION}.jar" \
        -o "$ASM_JAR"
    echo "[ios-bytecode-scan] ASM jar cached at $ASM_JAR" >&2
fi

# ── Compile ApiScan.java ──────────────────────────────────────────────────────
SCANNER_SRC="$SCRIPT_DIR/ApiScan.java"
SCANNER_CLASS_DIR="$(mktemp -d)"
trap 'rm -rf "$SCANNER_CLASS_DIR"' EXIT

echo "[ios-bytecode-scan] Compiling ApiScan.java ..." >&2
javac -cp "$ASM_JAR" -d "$SCANNER_CLASS_DIR" "$SCANNER_SRC"

# ── Locate compiled class directories ────────────────────────────────────────
# Scan the modules that are bundled into the iOS IPA.
MODULES=(
    forge-core
    forge-game
    forge-ai
    forge-gui
    forge-gui-mobile
    forge-gui-ios
)

CLASS_DIRS=()
for module in "${MODULES[@]}"; do
    dir="$REPO_ROOT/$module/target/classes"
    if [ -d "$dir" ]; then
        CLASS_DIRS+=("$dir")
    else
        echo "[ios-bytecode-scan] WARNING: $module/target/classes not found." \
             "Run 'mvn compile' first." >&2
    fi
done

if [ ${#CLASS_DIRS[@]} -eq 0 ]; then
    echo "[ios-bytecode-scan] ERROR: No compiled class directories found." \
         "Run 'mvn compile -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-mobile,forge-gui-ios -am' first." >&2
    exit 1
fi

# ── Also scan third-party JARs known to use serializable lambdas ─────────────
# JGraphT 1.5.2 SupplierUtil uses altMetafactory+FLAG_SERIALIZABLE in its <clinit>.
JGRAPHT_JAR="$HOME/.m2/repository/org/jgrapht/jgrapht-core/1.5.2/jgrapht-core-1.5.2.jar"
EXTRA_JARS=()
if [ -f "$JGRAPHT_JAR" ]; then
    EXTRA_JARS+=("$JGRAPHT_JAR")
    echo "[ios-bytecode-scan] Also scanning: $JGRAPHT_JAR" >&2
else
    echo "[ios-bytecode-scan] NOTE: JGraphT JAR not in ~/.m2 (not yet downloaded); skipping." >&2
fi

# ── Also scan libgdx JARs for native method declarations ─────────────────────
# libgdx's gdx-backend-robovm JAR contains the iOS-specific Java classes with
# `native` method declarations (e.g. IOSGLES20, IOSGLES30).  Each native method
# needs a corresponding xcframework in forge-gui-ios/pom.xml (*-platform:natives-ios).
# If a native symbol is absent from all linked xcframeworks the RoboVM-compiled
# trampoline holds a null function pointer → EXC_BAD_ACCESS at 0x0 on device.
#
# Currently linked xcframeworks:
#   gdx-platform:natives-ios        → gdx core GL/OpenAL/etc.
#   gdx-freetype-platform:natives-ios → FreeType font rasteriser
#   gdx-box2d-platform:natives-ios  → Box2D physics
GDX_VERSION="1.13.5"
GDX_CONTROLLERS_VERSION="2.2.4"
GDX_M2="$HOME/.m2/repository/com/badlogicgames/gdx"
GDX_CTRL_M2="$HOME/.m2/repository/com/badlogicgames/gdx-controllers"

for gdx_jar in \
    "$GDX_M2/gdx/${GDX_VERSION}/gdx-${GDX_VERSION}.jar" \
    "$GDX_M2/gdx-backend-robovm/${GDX_VERSION}/gdx-backend-robovm-${GDX_VERSION}.jar" \
    "$GDX_M2/gdx-freetype/${GDX_VERSION}/gdx-freetype-${GDX_VERSION}.jar" \
    "$GDX_M2/gdx-box2d/${GDX_VERSION}/gdx-box2d-${GDX_VERSION}.jar" \
    "$GDX_CTRL_M2/gdx-controllers-ios/${GDX_CONTROLLERS_VERSION}/gdx-controllers-ios-${GDX_CONTROLLERS_VERSION}.jar"; do
    if [ -f "$gdx_jar" ]; then
        EXTRA_JARS+=("$gdx_jar")
        echo "[ios-bytecode-scan] Also scanning (native methods): $gdx_jar" >&2
    else
        echo "[ios-bytecode-scan] NOTE: $gdx_jar not in ~/.m2 (not yet downloaded); skipping." >&2
    fi
done

echo "[ios-bytecode-scan] Scanning ${#CLASS_DIRS[@]} module(s): ${MODULES[*]}" >&2
echo "[ios-bytecode-scan] Total class dirs: ${CLASS_DIRS[*]}" >&2
echo "" >&2

# ── Run ApiScan ───────────────────────────────────────────────────────────────
java -cp "$ASM_JAR:$SCANNER_CLASS_DIR" ApiScan "${CLASS_DIRS[@]}" "${EXTRA_JARS[@]+"${EXTRA_JARS[@]}"}"
