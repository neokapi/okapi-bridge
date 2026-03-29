#!/bin/bash
# Package a release archive for a specific Okapi version.
#
# Creates a tar.gz containing:
#   - neokapi-bridge-jar-with-dependencies.jar
#   - manifest.json (with filter + step capabilities)
#   - schemas/ (composite filter schemas)
#   - schemas/steps/ (step schemas)
#
# Usage: ./scripts/package-release.sh <bridge-version> <okapi-version>
#
# Outputs:
#   okapi-bridge-v<bridge-version>-okapi<okapi-version>.tar.gz
#   okapi-bridge-v<bridge-version>-okapi<okapi-version>.tar.gz.sha256

set -euo pipefail

if [ $# -lt 2 ]; then
    echo "Usage: $0 <bridge-version> <okapi-version>"
    exit 1
fi

VERSION="$1"
OKAPI_VERSION="$2"
ARCHIVE_NAME="okapi-bridge-v${VERSION}-okapi${OKAPI_VERSION}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

# Find the built JAR
JAR_FILE=$(ls "okapi-releases/${OKAPI_VERSION}/target/neokapi-bridge-"*"-jar-with-dependencies.jar" 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "Error: No jar-with-dependencies found in okapi-releases/${OKAPI_VERSION}/target/"
    exit 1
fi

# Set up staging directory
rm -rf staging
mkdir -p staging/schemas staging/schemas/steps
cp "$JAR_FILE" staging/neokapi-bridge-jar-with-dependencies.jar

# Generate manifest.json with filter + step capabilities
echo "Generating manifest.json..."
CAP_JSON=$(java -jar "$JAR_FILE" --list-capabilities 2>/dev/null)

FILTER_CAPS=$(echo "$CAP_JSON" | jq '[.filters[] | {
    type: "format",
    id: .id,
    name: .name,
    display_name: .display_name,
    capabilities: .capabilities,
    mime_types: (if (.mime_types | length) > 0 then .mime_types else null end),
    extensions: (if (.extensions | length) > 0 then .extensions else null end)
  } | with_entries(select(.value != null))]')

STEP_CAPS=$(echo "$CAP_JSON" | jq '[(.steps // [])[] | {
    type: "tool",
    id: ("okapi:" + .stepId),
    name: .name,
    display_name: .name,
    description: .description
  } | with_entries(select(.value != null))]')

CAPABILITIES=$(jq -n --argjson f "$FILTER_CAPS" --argjson s "$STEP_CAPS" '$f + $s')

jq -n --arg name "okapi" \
      --arg version "$VERSION" \
      --arg framework_version "$OKAPI_VERSION" \
      --argjson capabilities "$CAPABILITIES" \
      '{
        name: $name,
        version: $version,
        framework_version: $framework_version,
        plugin_type: "bundle",
        install_type: "bridge",
        command: "java",
        args: ["-jar", "neokapi-bridge-jar-with-dependencies.jar"],
        capabilities: $capabilities
      }' > staging/manifest.json

echo "  Filters: $(echo "$FILTER_CAPS" | jq length)"
echo "  Steps: $(echo "$STEP_CAPS" | jq length)"

# Bundle composite filter schemas for this Okapi version
echo "Bundling filter schemas..."
FILTER_COUNT=0
jq -r --arg ov "${OKAPI_VERSION}" '
  .filters | to_entries[] |
  .key as $f |
  [.value.versions[] | select(.okapiVersions | index($ov))] |
  max_by(.version) | select(.) |
  "\($f) \(.version)"
' schemas/versions.json | while read -r filter version; do
    cp "schemas/filters/composite/${filter}.v${version}.schema.json" staging/schemas/
    FILTER_COUNT=$((FILTER_COUNT + 1))
done
echo "  Filter schemas: $(ls staging/schemas/*.schema.json 2>/dev/null | wc -l | tr -d ' ')"

# Bundle step schemas for this Okapi version
echo "Bundling step schemas..."
jq -r --arg ov "${OKAPI_VERSION}" '
  .steps // {} | to_entries[] |
  .key as $s |
  [.value.versions[] | select(.okapiVersions | index($ov))] |
  max_by(.version) | select(.) |
  "\($s) \(.version)"
' schemas/versions.json | while read -r step_id version; do
    src="schemas/steps/base/${step_id}.v${version}.schema.json"
    if [ -f "$src" ]; then
        cp "$src" "staging/schemas/steps/${step_id}.schema.json"
    fi
done
echo "  Step schemas: $(ls staging/schemas/steps/*.schema.json 2>/dev/null | wc -l | tr -d ' ')"

# Create archive
echo "Creating archive..."
cd staging
tar czf "../${ARCHIVE_NAME}.tar.gz" .
cd ..

sha256sum "${ARCHIVE_NAME}.tar.gz" > "${ARCHIVE_NAME}.tar.gz.sha256"

echo "Created ${ARCHIVE_NAME}.tar.gz"
