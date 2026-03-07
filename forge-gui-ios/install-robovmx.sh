#!/bin/bash
# Clones and installs robovmx to the local Maven repository.
# Required before building forge-gui-ios locally.
# Run from the repository root or from within forge-gui-ios/.

set -euo pipefail

# Resolve the repository root regardless of where the script is invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROBOVMX_DIR="${REPO_ROOT}/robovmx"

ROBOVMX_REPO="https://github.com/robovmx/robovmx.git"
ROBOVMX_BRANCH="experiment/2-libcore-10"

# Clone robovmx if not already present, otherwise update it.
if [ ! -d "${ROBOVMX_DIR}/.git" ]; then
    echo "==> Cloning robovmx (branch: ${ROBOVMX_BRANCH}) ..."
    git clone --depth 1 --branch "${ROBOVMX_BRANCH}" "${ROBOVMX_REPO}" "${ROBOVMX_DIR}"
else
    echo "==> robovmx already cloned, pulling latest changes ..."
    git -C "${ROBOVMX_DIR}" fetch --depth 1 origin "${ROBOVMX_BRANCH}"
    git -C "${ROBOVMX_DIR}" reset --hard FETCH_HEAD
fi

# Install robovmx to the local Maven repository.
echo "==> Installing robovmx to local Maven repository ..."
command -v mvn >/dev/null 2>&1 || { echo "Error: Maven (mvn) not found. Please install Maven and try again." >&2; exit 1; }
cd "${ROBOVMX_DIR}"
mvn install -Dmaven.test.skip=true

echo "==> Done. You can now build forge-gui-ios with:"
echo "    mvn -pl forge-gui-ios -am install -P ios-build -Dmaven.test.skip=true"
