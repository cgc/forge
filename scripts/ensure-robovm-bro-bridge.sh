#!/usr/bin/env bash
# ensure-robovm-bro-bridge.sh
#
# Ensures forge-gui-ios/robovm-home/ is populated with the robovmx libcore12
# dist so that robovm-maven-plugin uses it as the robovm home directory.
#
# This is called by forge-gui-ios/pom.xml (exec-maven-plugin, prepare-package
# phase) as a safety net for developers who run 'mvn' directly without first
# running scripts/install-robovmx.sh.
#
# All logic has been consolidated into install-robovmx.sh, which:
#   • Installs the robovmx AOT compiler fat JAR to forge-gui-ios/local-repo/
#   • Populates forge-gui-ios/robovm-home/robovm-2.3.23/ with the robovmx
#     libcore12 dist extracted from the IDEA plugin zip, providing:
#       - lib/robovm-rt.jar         (libcore12, Android 12-based runtime)
#       - lib/robovm-bro-bridge.jar (required by Config$Home.validate())
#       - lib/vm/*/librobovm-bro.a  (new bro native lib)
#     This fixes both:
#       • "Path .../robovm-2.3.23 is not a valid RoboVM install directory:
#         ../.. missing or invalid"  (Config$Home.validate() bro-bridge check)
#       • "Root class java/net/Inet6Address$Inet6AddressHolder not found"
#         (ROOT_CLASSES in AppCompiler.java requires libcore12 inner classes)
#
# The robovm-maven-plugin's <home> configuration in pom.xml points at
# robovm-home/; AbstractRoboVMMojo.unpackRoboVMDist() skips extraction when
# the home directory already exists, so our pre-populated content is used
# directly without any ~/.m2 cache manipulation.
#
# Usage:  bash scripts/ensure-robovm-bro-bridge.sh
#         (also called automatically from forge-gui-ios/pom.xml)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/install-robovmx.sh"
