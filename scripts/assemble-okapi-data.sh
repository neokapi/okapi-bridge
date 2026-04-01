#!/bin/bash
# Assemble the okapi-data/ directory for a specific Okapi version.
#
# This produces the "okapi reference" artifact — all extracted data organized
# in pure Okapi vocabulary (filters, steps, configurations). No neokapi terms.
#
# Reads from:
#   - schemas/filters/composite/  (versioned composite filter schemas)
#   - schemas/steps/base/         (versioned step schemas)
#   - schemas/versions.json       (version tracking)
#   - docs/                       (curated documentation)
#
# Produces:
#   okapi-data/{version}/
#   ├── meta.json
#   ├── filters/{filterId}/
#   │   ├── schema.json
#   │   └── doc.json
#   ├── steps/{stepId}/
#   │   ├── schema.json
#   │   └── doc.json
#   ├── concepts.json
#   └── versions.json
#
# Usage: ./scripts/assemble-okapi-data.sh <okapi-version>

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <okapi-version>"
    exit 1
fi

OKAPI_VERSION="$1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

OUTPUT_DIR="okapi-data/${OKAPI_VERSION}"

# Verify prerequisites
if [ ! -f "schemas/versions.json" ]; then
    echo "Error: schemas/versions.json not found. Run schema generation first." >&2
    exit 1
fi

# Clean and create output directory
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/filters" "$OUTPUT_DIR/steps"

echo "Assembling okapi-data for Okapi ${OKAPI_VERSION}..."

# --- Filters ---

FILTER_COUNT=0
jq -r --arg ov "${OKAPI_VERSION}" '
  .filters | to_entries[] |
  .key as $f |
  [.value.versions[] | select(.okapiVersions | index($ov))] |
  max_by(.version) | select(.) |
  "\($f) \(.version)"
' schemas/versions.json | {
  while read -r filter version; do
    src="schemas/filters/composite/${filter}.v${version}.schema.json"
    if [ ! -f "$src" ]; then
        echo "  Warning: $src not found, skipping" >&2
        continue
    fi

    mkdir -p "$OUTPUT_DIR/filters/${filter}"
    # Rewrite $id to neutral namespace (okapi-native, not neokapi)
    jq --arg f "$filter" --argjson v "$version" '
        .["$id"] = "https://okapiframework.org/schemas/filters/\($f).v\($v).schema.json"
    ' "$src" > "$OUTPUT_DIR/filters/${filter}/schema.json"

    # Copy filter documentation if available
    if [ -f "docs/filters/${filter}.json" ]; then
        cp "docs/filters/${filter}.json" "$OUTPUT_DIR/filters/${filter}/doc.json"
    fi

    FILTER_COUNT=$((FILTER_COUNT + 1))
  done
  true
} || true
FILTER_COUNT=$(ls -1d "$OUTPUT_DIR/filters"/*/ 2>/dev/null | wc -l | tr -d ' ')
echo "  Filters: $FILTER_COUNT"

# --- Steps ---

STEP_COUNT=0
jq -r --arg ov "${OKAPI_VERSION}" '
  .steps // {} | to_entries[] |
  .key as $s |
  [.value.versions[] | select(.okapiVersions | index($ov))] |
  max_by(.version) | select(.) |
  "\($s) \(.version)"
' schemas/versions.json | {
  while read -r step_id version; do
    src="schemas/steps/base/${step_id}.v${version}.schema.json"
    if [ ! -f "$src" ]; then
        echo "  Warning: $src not found, skipping" >&2
        continue
    fi

    mkdir -p "$OUTPUT_DIR/steps/${step_id}"
    # Strip x-component (neokapi vocabulary) if present — pure Okapi output only
    jq 'del(.["x-component"])' "$src" > "$OUTPUT_DIR/steps/${step_id}/schema.json"

    # Copy step documentation if available
    if [ -f "docs/steps/${step_id}.json" ]; then
        cp "docs/steps/${step_id}.json" "$OUTPUT_DIR/steps/${step_id}/doc.json"
    fi

    STEP_COUNT=$((STEP_COUNT + 1))
  done
  true
} || true
STEP_COUNT=$(ls -1d "$OUTPUT_DIR/steps"/*/ 2>/dev/null | wc -l | tr -d ' ')
echo "  Steps: $STEP_COUNT"

# --- Shared docs ---

if [ -f "docs/concepts.json" ]; then
    cp "docs/concepts.json" "$OUTPUT_DIR/concepts.json"
fi

# --- Meta ---

# Get Java version from per-version meta.json if available
JAVA_VERSION="11"
if [ -f "okapi-releases/${OKAPI_VERSION}/meta.json" ]; then
    JAVA_VERSION=$(jq -r '.javaVersion // "11"' "okapi-releases/${OKAPI_VERSION}/meta.json")
fi

jq -n \
    --arg ov "$OKAPI_VERSION" \
    --arg jv "$JAVA_VERSION" \
    --argjson fc "$FILTER_COUNT" \
    --argjson sc "$STEP_COUNT" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{
        okapiVersion: $ov,
        javaVersion: $jv,
        filterCount: $fc,
        stepCount: $sc,
        generatedAt: $ts
    }' > "$OUTPUT_DIR/meta.json"

# --- Versions subset ---

# Extract only the version entries relevant to this Okapi version
jq --arg ov "$OKAPI_VERSION" '{
    filters: (.filters | to_entries | map(
        .key as $f |
        .value.versions as $vs |
        [$vs[] | select(.okapiVersions | index($ov))] |
        if length > 0 then {key: $f, value: {versions: .}} else empty end
    ) | from_entries),
    steps: ((.steps // {}) | to_entries | map(
        .key as $s |
        .value.versions as $vs |
        [$vs[] | select(.okapiVersions | index($ov))] |
        if length > 0 then {key: $s, value: {versions: .}} else empty end
    ) | from_entries)
}' schemas/versions.json > "$OUTPUT_DIR/versions.json"

echo ""
echo "Assembled: $OUTPUT_DIR/"
echo "  $FILTER_COUNT filters, $STEP_COUNT steps"
