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
# OUTPUT
# ------
# Writes to stdout; redirect to scripts/ios-bytecode-scan.txt to persist:
#   bash scripts/ios-bytecode-scan.sh > scripts/ios-bytecode-scan.txt
#
# CATEGORIES
# ----------
#   RISK:HIGH — NOT patched by StreamDesugar, NOT claimed by robovmx robovm-rt.
#               Will throw NoSuchMethodError on iOS if reachable.
#   CLAIMED   — Listed in build-java-stubs.sh as natively provided by robovmx.
#               Should be safe; listed for awareness.
#   PATCHED   — Rewritten at build time by StreamDesugar.java (patterns 49-60).
#
# PREREQUISITES
# -------------
#   mvn compile -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-mobile
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
         "Run 'mvn compile -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-mobile -am' first." >&2
    exit 1
fi

echo "[ios-bytecode-scan] Scanning ${#CLASS_DIRS[@]} module(s): ${MODULES[*]}" >&2
echo "[ios-bytecode-scan] Total class dirs: ${CLASS_DIRS[*]}" >&2
echo "" >&2

# ── Run ApiScan ───────────────────────────────────────────────────────────────
java -cp "$ASM_JAR:$SCANNER_CLASS_DIR" ApiScan "${CLASS_DIRS[@]}"
