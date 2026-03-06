#!/usr/bin/env bash
# ensure-robovm-bro-bridge.sh
#
# Ensures the robovmx libcore12 dist is installed in the robovm-maven-plugin's
# unpacked dist directory.  This is called by forge-gui-ios/pom.xml as a
# prepare-package hook so that 'mvn' works correctly even if
# scripts/install-robovmx.sh has not yet been run.
#
# All logic has been consolidated into install-robovmx.sh, which:
#   • Installs the robovmx AOT compiler fat JAR (Step 2-3)
#   • Pre-populates ~/.m2/.../robovm-dist/2.3.23/unpacked/ with the robovmx
#     libcore12 dist extracted from the IDEA plugin zip (Step 4), providing:
#       - lib/robovm-rt.jar        (libcore12, Android 12-based runtime)
#       - lib/robovm-bro-bridge.jar (required by Config$Home.validate())
#       - lib/vm/*/librobovm-bro.a  (new bro native lib)
#     This fixes both:
#       • "Path .../robovm-2.3.23 is not a valid RoboVM install directory:
#         ../.. missing or invalid"  (Config$Home.validate() bro-bridge check)
#       • "Root class java/net/Inet6Address$Inet6AddressHolder not found"
#         (ROOT_CLASSES in AppCompiler.java requires libcore12 inner classes)
#
# Usage:  bash scripts/ensure-robovm-bro-bridge.sh
#         (also called automatically from forge-gui-ios/pom.xml)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/install-robovmx.sh"
