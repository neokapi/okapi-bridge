#!/bin/bash
# Update the neokapi plugin registry with release artifacts.
#
# Usage: ./scripts/update-registry.sh <tag> <registry-dir> <artifacts-dir> <latest-okapi-version>
#
# Example:
#   ./scripts/update-registry.sh v2.20.0 ./registry ./artifacts 1.48.0

set -euo pipefail

if [ $# -lt 4 ]; then
    echo "Usage: $0 <tag> <registry-dir> <artifacts-dir> <latest-okapi-version>"
    exit 1
fi

TAG="$1"
REGISTRY_DIR="$2"
ARTIFACTS_DIR="$3"
OKAPI_LATEST="$4"
VERSION="${TAG#v}"
BASE_URL="https://github.com/neokapi/okapi-bridge/releases/download/${TAG}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# Read capabilities generated from the bridge JAR
CAPABILITIES=$(cat "${ARTIFACTS_DIR}/capabilities.json")

cd "$REGISTRY_DIR"
echo '{"version":1,"plugins":[]}' > plugins.json

# Add each Okapi version as a plugin entry
for okapi_version in $(ls -1 "${ROOT_DIR}/okapi-releases" | sort -V); do
    CHECKSUM=$(awk '{print $1}' "${ARTIFACTS_DIR}/okapi-bridge-${TAG}-okapi${okapi_version}.tar.gz.sha256")
    URL="${BASE_URL}/okapi-bridge-${TAG}-okapi${okapi_version}.tar.gz"

    if [ "$okapi_version" = "$OKAPI_LATEST" ]; then
        PLUGIN_NAME="okapi"
        DESCRIPTION="Okapi filters and steps (v${okapi_version}, latest)"
    else
        PLUGIN_NAME="okapi-${okapi_version}"
        DESCRIPTION="Okapi filters and steps (v${okapi_version})"
    fi

    jq --arg name "$PLUGIN_NAME" \
       --arg version "$VERSION" \
       --arg framework_version "$okapi_version" \
       --arg desc "$DESCRIPTION" \
       --arg checksum "$CHECKSUM" \
       --arg url "$URL" \
       --argjson capabilities "$CAPABILITIES" \
       '.plugins += [{
         name: $name,
         version: $version,
         framework_version: $framework_version,
         description: $desc,
         plugin_type: "bundle",
         install_type: "bridge",
         platform: "",
         checksum: $checksum,
         download_url: $url,
         min_host_version: "0.1.0",
         capabilities: $capabilities
       }]' plugins.json > plugins.tmp && mv plugins.tmp plugins.json
done

echo "Updated plugins.json with $(jq '.plugins | length' plugins.json) entries"
