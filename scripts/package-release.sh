#!/bin/bash
# Package a release archive for a specific Okapi version.
#
# Uses the two-stage pipeline:
#   1. Assemble okapi-data/ (pure Okapi vocabulary)
#   2. Transform to dist/plugin/ (neokapi vocabulary)
#   3. Add JAR and package as tar.gz
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

# Stage 1: Assemble okapi-data (pure Okapi vocabulary)
echo "Stage 1: Assembling okapi-data..."
"$SCRIPT_DIR/assemble-okapi-data.sh" "$OKAPI_VERSION"

# Stage 2: Transform to neokapi plugin format
echo ""
echo "Stage 2: Transforming to neokapi plugin format..."
"$SCRIPT_DIR/transform-to-plugin.sh" "$OKAPI_VERSION" "$VERSION"

# Stage 3: Add JAR and package
echo ""
echo "Stage 3: Packaging release..."
cp "$JAR_FILE" dist/plugin/neokapi-bridge-jar-with-dependencies.jar

# Create archive from dist/plugin/
cd dist/plugin
tar czf "../../${ARCHIVE_NAME}.tar.gz" .
cd ../..

sha256sum "${ARCHIVE_NAME}.tar.gz" > "${ARCHIVE_NAME}.tar.gz.sha256"

echo ""
echo "Created ${ARCHIVE_NAME}.tar.gz"
echo "  Formats: $(jq '[.capabilities[] | select(.type == "format")] | length' dist/plugin/manifest.json)"
echo "  Tools: $(jq '[.capabilities[] | select(.type == "tool")] | length' dist/plugin/manifest.json)"
