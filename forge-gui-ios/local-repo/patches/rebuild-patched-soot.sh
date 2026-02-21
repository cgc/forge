#!/usr/bin/env bash
# rebuild-patched-soot.sh
#
# Rebuilds forge-gui-ios/local-repo/.../robovm-soot-<VER>-forge-patched.jar
# from scratch, applying all four Java-record bug fixes to robovm-soot.
#
# Usage:
#   cd forge-gui-ios/local-repo/patches/
#   ./rebuild-patched-soot.sh [SOOT_VERSION]
#
# Default SOOT_VERSION is 2.5.0-9 (bundled with MobiVM 2.3.24).
#
# Requirements: javac (JDK 11+), jar, git, curl, python3
#
# See PATCHING.md for a detailed explanation of each bug and its fix.
set -euo pipefail

SOOT_VER="${1:-2.5.0-9}"
ASM_VER="9.7"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
DEST_DIR="${REPO_ROOT}/forge-gui-ios/local-repo/com/mobidevelop/robovm/robovm-soot/${SOOT_VER}-forge-patched"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT
cd "${WORK_DIR}"

echo "====================================================="
echo " rebuild-patched-soot.sh"
echo " SOOT_VER : ${SOOT_VER}"
echo " Repo root: ${REPO_ROOT}"
echo " Work dir : ${WORK_DIR}"
echo "====================================================="
echo ""

# ── Step 1: Download original robovm-soot jar ─────────────────────────────────
echo "[1/8] Downloading robovm-soot-${SOOT_VER}.jar from Maven Central ..."
curl -fsSL -o "robovm-soot-${SOOT_VER}.jar" \
    "https://repo1.maven.org/maven2/com/mobidevelop/robovm/robovm-soot/${SOOT_VER}/robovm-soot-${SOOT_VER}.jar"
CLASS_COUNT=$(jar tf "robovm-soot-${SOOT_VER}.jar" | grep -c '\.class$')
echo "    ${CLASS_COUNT} class files in original jar."

# ── Step 2: Download ASM (needed for Bug 4 patcher) ──────────────────────────
echo "[2/8] Downloading asm-${ASM_VER}.jar from Maven Central ..."
curl -fsSL -o "asm-${ASM_VER}.jar" \
    "https://repo1.maven.org/maven2/org/ow2/asm/asm/${ASM_VER}/asm-${ASM_VER}.jar"

# ── Step 3: Clone MobiVM/soot source ─────────────────────────────────────────
echo "[3/8] Cloning MobiVM/soot at tag ${SOOT_VER} ..."
git clone --quiet --depth 1 --branch "${SOOT_VER}" \
    https://github.com/MobiVM/soot.git soot-src
echo "    Clone complete."

# ── Step 4: Bug 1 — CONSTANT_Fieldref_info ────────────────────────────────────
echo "[4/8] Patching Bug 1: CONSTANT_Fieldref_info (missing slash→dot conversion) ..."
FIELDREF="soot-src/src/main/java/soot/coffi/CONSTANT_Fieldref_info.java"

cat > patch_bug1.py << 'PYEOF'
import sys

path = sys.argv[1]
content = open(path).read()
old = 'String className = cc.toString(constant_pool);'
new = "String className = cc.toString(constant_pool).replace('/', '.'); // RoboVM note: Replace / with ."
if old not in content:
    print("ERROR: Bug 1 pattern not found in " + path, file=sys.stderr)
    sys.exit(1)
open(path, 'w').write(content.replace(old, new, 1))
print("    Source patched OK")
PYEOF

python3 patch_bug1.py "${FIELDREF}"
javac -source 8 -target 8 -d . \
    -cp "robovm-soot-${SOOT_VER}.jar" \
    -sourcepath soot-src/src/main/java \
    "${FIELDREF}"
echo "    Compiled OK -> soot/coffi/CONSTANT_Fieldref_info.class"

# ── Step 5: Bug 2 — CONSTANT_MethodHandle_info ───────────────────────────────
echo "[5/8] Patching Bug 2: CONSTANT_MethodHandle_info (field-ref handle kinds not handled) ..."
METHODHANDLE="soot-src/src/main/java/soot/coffi/CONSTANT_MethodHandle_info.java"

cat > patch_bug2.py << 'PYEOF'
import sys, re

path = sys.argv[1]
content = open(path).read()

# Add Collections import if absent
if 'java.util.Collections' not in content:
    # Insert after the last import statement in the file
    content = re.sub(
        r'(import [^\n]+;\n)(?!import)',
        r'\1import java.util.Collections;\n',
        content, count=1
    )

# The block to insert — Java char literals '/' and '.' are fine inside
# Python triple-quoted strings (they are single chars, not string delimiters).
insert = '''        if (kind >= 1 && kind <= 4) {
            // Field-ref handle kinds: REF_getField=1, REF_getStatic=2,
            //                         REF_putField=3,  REF_putStatic=4
            // JMethodHandle stores only a SootMethodRef, so model the field as
            // a synthetic zero-arg getter. InvokeDynamicCompilerPlugin routes
            // record invokedynamic to UnrecognizedBootstrapDelegate anyway.
            CONSTANT_Fieldref_info fieldRef =
                    (CONSTANT_Fieldref_info) constant_pool[target_index];
            CONSTANT_Class_info classInfo =
                    (CONSTANT_Class_info) constant_pool[fieldRef.class_index];
            CONSTANT_NameAndType_info nat =
                    (CONSTANT_NameAndType_info) constant_pool[fieldRef.name_and_type_index];
            CONSTANT_Utf8_info fieldNameUtf8 =
                    (CONSTANT_Utf8_info) constant_pool[nat.name_index];
            CONSTANT_Utf8_info fieldDescUtf8 =
                    (CONSTANT_Utf8_info) constant_pool[nat.descriptor_index];
            String className = classInfo.toString(constant_pool).replace('/', '.'); // RoboVM note: Replace / with .
            SootClass declaringClass = Scene.v().getSootClass(className);
            String fieldName = fieldNameUtf8.toString(constant_pool);
            Type fieldType =
                    Util.v().jimpleTypeOfFieldDescriptor(fieldDescUtf8.toString(constant_pool));
            boolean isStatic = (kind == 2 || kind == 4);
            SootMethodRef ref = Scene.v().makeMethodRef(
                    declaringClass, fieldName, Collections.emptyList(), fieldType, isStatic);
            return Jimple.v().newMethodHandle(kind, ref);
        }
'''

anchor = '        InvokeExpr expr = (InvokeExpr) target.createJimpleConstantValue(constant_pool);'
if anchor not in content:
    print("ERROR: Bug 2 anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
open(path, 'w').write(content.replace(anchor, insert + anchor, 1))
print("    Source patched OK")
PYEOF

python3 patch_bug2.py "${METHODHANDLE}"
javac -source 8 -target 8 -d . \
    -cp "robovm-soot-${SOOT_VER}.jar" \
    -sourcepath soot-src/src/main/java \
    "${METHODHANDLE}"
echo "    Compiled OK -> soot/coffi/CONSTANT_MethodHandle_info.class"

# ── Step 6: Bug 3 — JDynamicInvokeExpr ───────────────────────────────────────
echo "[6/8] Patching Bug 3: JDynamicInvokeExpr (strict CallSite return-type check) ..."
JDIE="soot-src/src/main/java/soot/jimple/internal/JDynamicInvokeExpr.java"

cat > patch_bug3.py << 'PYEOF'
import sys, re

path = sys.argv[1]
content = open(path).read()

# Match the strict CallSite check regardless of surrounding whitespace/indentation
pattern = (
    r'if\s*\(\s*!\s*bootstrapMethodRef\.returnType\(\)\.equals\('
    r'RefType\.v\("java\.lang\.invoke\.CallSite"\)\)\s*\)\s*\{'
    r'\s*throw new IllegalArgumentException\('
    r'"Return type of bootstrap method must be java\.lang\.invoke\.CallSite!"\);\s*\}'
)
replacement = (
    '// RoboVM note: Relaxed check — Java 9+ allows bootstrap methods whose return type is\n'
    '        // Object (e.g. java.lang.runtime.ObjectMethods.bootstrap used by Java 16+ records).\n'
    '        if(!(bootstrapMethodRef.returnType() instanceof RefType)) {\n'
    '            throw new IllegalArgumentException("Return type of bootstrap method must be a reference type!");\n'
    '        }'
)
new_content, count = re.subn(pattern, replacement, content, flags=re.DOTALL)
if count == 0:
    print("ERROR: Bug 3 pattern not found in " + path, file=sys.stderr)
    sys.exit(1)
open(path, 'w').write(new_content)
print("    Source patched OK")
PYEOF

python3 patch_bug3.py "${JDIE}"
javac -source 8 -target 8 -d . \
    -cp "robovm-soot-${SOOT_VER}.jar" \
    -sourcepath soot-src/src/main/java \
    "${JDIE}"
echo "    Compiled OK -> soot/jimple/internal/JDynamicInvokeExpr.class"

# ── Step 7: Bug 4 — AugEvalFunction (ASM bytecode patcher) ───────────────────
echo "[7/8] Patching Bug 4: AugEvalFunction (ASM patcher — exception-handler fallback) ..."
cp "${SCRIPT_DIR}/PatchAugEvalFunction.java" .
javac -cp "asm-${ASM_VER}.jar" PatchAugEvalFunction.java
java -cp "asm-${ASM_VER}.jar:." PatchAugEvalFunction \
    "robovm-soot-${SOOT_VER}.jar" \
    "robovm-soot-${SOOT_VER}-forge-patched.jar"

# ── Inject Bugs 1–3 class files into the Bug 4-patched jar ───────────────────
echo "    Injecting Bug 1–3 class files ..."
jar uf "robovm-soot-${SOOT_VER}-forge-patched.jar" \
    soot/coffi/CONSTANT_Fieldref_info.class \
    soot/coffi/CONSTANT_MethodHandle_info.class \
    soot/jimple/internal/JDynamicInvokeExpr.class

echo "    Verifying all 4 patched classes are present in jar:"
jar tf "robovm-soot-${SOOT_VER}-forge-patched.jar" | grep -E \
    'soot/coffi/CONSTANT_Fieldref_info\.class|soot/coffi/CONSTANT_MethodHandle_info\.class|soot/jimple/internal/JDynamicInvokeExpr\.class|soot/toolkits/scalar/AugEvalFunction\.class' \
    | sed 's/^/      /'

# ── Step 8: Install to local-repo and regenerate checksums ───────────────────
echo "[8/8] Installing to local-repo and regenerating checksums ..."
mkdir -p "${DEST_DIR}"
DEST_JAR="${DEST_DIR}/robovm-soot-${SOOT_VER}-forge-patched.jar"
cp "robovm-soot-${SOOT_VER}-forge-patched.jar" "${DEST_JAR}"

# Checksum generation (macOS uses BSD md5/shasum; Linux uses md5sum/sha1sum)
if [[ "$(uname)" == "Darwin" ]]; then
    md5 -q "${DEST_JAR}" > "${DEST_JAR}.md5"
    shasum -a 1 "${DEST_JAR}" | awk '{print $1}' > "${DEST_JAR}.sha1"
else
    md5sum "${DEST_JAR}" | awk '{print $1}' > "${DEST_JAR}.md5"
    sha1sum "${DEST_JAR}" | awk '{print $1}' > "${DEST_JAR}.sha1"
fi

echo ""
echo "====================================================="
echo " Done!"
echo " JAR:  ${DEST_JAR}"
echo " MD5:  $(cat "${DEST_JAR}.md5")"
echo " SHA1: $(cat "${DEST_JAR}.sha1")"
echo "====================================================="
echo ""
echo " To verify, run from the repo root:"
echo "   mvn -pl forge-gui-ios -am -P ios-device \\"
echo "       -Drobovm.iosSkipSigning=true -Dmaven.test.skip=true install"
